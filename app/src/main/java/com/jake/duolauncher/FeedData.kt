package com.jake.duolauncher

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.CalendarContract
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/*
 * Data for the feed page. Everything is read on the Duo itself: the calendar from Android's
 * calendar store (Outlook syncs into it), media from Android's media sessions, and weather from
 * Open-Meteo, the one network request the launcher makes.
 */

// ---------- Calendar ----------

fun hasCalendarAccess(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

/** Calendar instances overlapping [from, to), all-day ones included. Empty without permission. */
suspend fun loadFeedEvents(context: Context, from: Long, to: Long): List<FeedEvent> = withContext(Dispatchers.IO) {
    if (!hasCalendarAccess(context)) return@withContext emptyList()
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, from); ContentUris.appendId(it, to)
    }.build()
    val projection = arrayOf(
        CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
        CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
        CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
    )
    runCatching {
        context.contentResolver.query(uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null,
            "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) add(FeedEvent(
                    id = c.getLong(0), title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Busy",
                    begin = c.getLong(2), end = c.getLong(3), allDay = c.getInt(4) == 1,
                    color = if (c.isNull(5)) null else c.getInt(5)))
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())
}

fun openCalendarEvent(context: Context, event: FeedEvent) {
    val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

// ---------- Weather ----------

data class FeedWeather(
    val temperature: Double, val code: Int, val high: Double, val low: Double,
    val rain: Pair<Int, String>?, val fahrenheit: Boolean, val fetchedAt: Long,
)

/** Last good forecast, kept for half an hour so swiping Home doesn't refetch. */
object FeedWeatherCache {
    @Volatile var value: FeedWeather? = null
    @Volatile var key: String? = null
    fun fresh(key: String) = value?.takeIf { this.key == key && SystemClock.elapsedRealtime() - it.fetchedAt < 30 * 60_000L }
}

private fun usesFahrenheit() = Locale.getDefault().country in setOf("US", "LR", "MM")

suspend fun loadFeedWeather(latitude: Double, longitude: Double): FeedWeather? = withContext(Dispatchers.IO) {
    val fahrenheit = usesFahrenheit()
    val key = "%.2f,%.2f,%b".format(Locale.US, latitude, longitude, fahrenheit)
    FeedWeatherCache.fresh(key)?.let { return@withContext it }
    val url = URL("https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(Locale.US, latitude, longitude) +
        "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min" +
        "&hourly=precipitation_probability&timezone=auto&forecast_days=1" +
        if (fahrenheit) "&temperature_unit=fahrenheit" else "")
    runCatching {
        val connection = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
        try {
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val hourly = json.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val chances = hourly.getJSONArray("precipitation_probability")
            val hours = (0 until times.length()).map { times.getString(it) to chances.optInt(it, 0) }
            FeedWeather(
                temperature = current.getDouble("temperature_2m"), code = current.getInt("weather_code"),
                high = daily.getJSONArray("temperature_2m_max").getDouble(0),
                low = daily.getJSONArray("temperature_2m_min").getDouble(0),
                rain = rainHint(hours, current.getString("time")), fahrenheit = fahrenheit,
                fetchedAt = SystemClock.elapsedRealtime(),
            ).also { FeedWeatherCache.value = it; FeedWeatherCache.key = key }
        } finally {
            connection.disconnect()
        }
    }.getOrNull() ?: FeedWeatherCache.value
}

// ---------- Now playing ----------

/**
 * Android only shares other apps' media sessions with an app the user has allowed as a
 * notification listener. This service exists for that permission; it doesn't read notifications.
 */
class NowPlayingListenerService : NotificationListenerService()

fun hasNowPlayingAccess(context: Context) =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

fun openNowPlayingSettings(context: Context) {
    runCatching {
        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

data class NowPlaying(
    val controller: MediaController, val title: String, val artist: String, val art: Bitmap?,
    val playing: Boolean, val durationMs: Long, val positionMs: Long, val positionAt: Long, val speed: Float,
) {
    /** Playback position now, advancing while playing. */
    fun currentPosition(nowElapsed: Long = SystemClock.elapsedRealtime()): Long {
        val moved = if (playing) ((nowElapsed - positionAt) * speed).toLong() else 0L
        return (positionMs + moved).coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
    }
}

/**
 * Watches the Duo's media sessions and reports what to show. A playing session wins; a paused one
 * stays for [pausedGraceMs] after it stops so the play key is still there, then the panel hides.
 */
class NowPlayingTracker(private val context: Context, private val onChange: (NowPlaying?) -> Unit) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, NowPlayingListenerService::class.java)
    // Keyed by session token: Android hands out new MediaController objects for the same session.
    private val callbacks = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val pausedAt = mutableMapOf<String, Long>()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }
    private val pausedGraceMs = 5 * 60_000L

    fun start(): Boolean = runCatching {
        manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
        watch(manager.getActiveSessions(listenerComponent))
        true
    }.getOrElse { onChange(null); false }

    fun stop() {
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        callbacks.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
    }

    /** Re-evaluate, e.g. once a minute, so a long-paused session drops off. */
    fun refresh() = onChange(pick(callbacks.values.map { it.first }))

    private fun watch(controllers: List<MediaController>) {
        val live = controllers.map { it.sessionToken }.toSet()
        callbacks.keys.filter { it !in live }.forEach { token ->
            callbacks.remove(token)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
        }
        controllers.filter { it.sessionToken !in callbacks }.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() = refresh()
            }
            controller.registerCallback(callback)
            callbacks[controller.sessionToken] = controller to callback
        }
        refresh()
    }

    private fun pick(controllers: List<MediaController>): NowPlaying? {
        val now = SystemClock.elapsedRealtime()
        controllers.forEach { c ->
            val state = c.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) pausedAt.remove(c.packageName)
            else if (state == PlaybackState.STATE_PAUSED) pausedAt.putIfAbsent(c.packageName, now)
        }
        val playing = controllers.firstOrNull {
            it.metadata != null && it.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)
        }
        val recentPause = controllers.firstOrNull {
            it.metadata != null && it.playbackState?.state == PlaybackState.STATE_PAUSED &&
                now - (pausedAt[it.packageName] ?: now) < pausedGraceMs
        }
        val controller = playing ?: recentPause ?: return null
        val metadata = controller.metadata ?: return null
        val state = controller.playbackState
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        return NowPlaying(
            controller = controller, title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
            art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = controller === playing,
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
            positionMs = state?.position ?: 0L,
            positionAt = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: now,
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
    }
}
