package com.jake.duolauncher

import android.Manifest
import android.annotation.SuppressLint
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
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
import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
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

fun openCalendarEvent(context: Context, event: FeedEvent, options: android.os.Bundle? = null) {
    val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent, options) }
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

// ---------- Place for weather ----------

/** Where weather is shown for: set once by tapping Sync, never tracked in the background. */
data class FeedPlace(val latitude: Double, val longitude: Double, val name: String)

object FeedPlaceStore {
    private fun prefs(context: Context) = context.getSharedPreferences("feed", Context.MODE_PRIVATE)
    fun load(context: Context): FeedPlace? = prefs(context).takeIf { it.contains("lat") }?.let {
        FeedPlace(Double.fromBits(it.getLong("lat", 0)), Double.fromBits(it.getLong("lon", 0)), it.getString("name", "") ?: "")
    }
    fun save(context: Context, place: FeedPlace) = prefs(context).edit()
        .putLong("lat", place.latitude.toRawBits()).putLong("lon", place.longitude.toRawBits()).putString("name", place.name).apply()
}

fun hasLocationAccess(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** What a location sync produced: the place, or a short reason it couldn't. */
data class PlaceSyncResult(val place: FeedPlace?, val problem: String?)

/**
 * Reads the Duo's approximate location once, names the town, and saves it for weather.
 * Uses a fix the Duo already has from the last 30 minutes when there is one (instant), otherwise asks
 * each available provider in turn, and as a last resort takes the newest fix of any age.
 */
@SuppressLint("MissingPermission")
suspend fun syncFeedPlace(context: Context): PlaceSyncResult {
    if (!hasLocationAccess(context)) return PlaceSyncResult(null, "Location permission is off for Duo Dev.")
    val manager = context.getSystemService(LocationManager::class.java)
    if (!runCatching { manager.isLocationEnabled }.getOrDefault(true))
        return PlaceSyncResult(null, "Turn on Location for a moment, then tap Sync.")
    val enabled = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
    val newest = enabled.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    val recent = newest?.takeIf { System.currentTimeMillis() - it.time <= 30 * 60_000L }
    val location = recent ?: run {
        var found: Location? = null
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)) {
            if (provider !in enabled) continue
            found = withTimeoutOrNull(12_000L) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { if (cont.isActive) cont.resume(it) }
                }
            }
            if (found != null) break
        }
        found ?: newest
    } ?: return PlaceSyncResult(null, "Couldn't get a location fix. Try again on Wi-Fi or near a window.")
    val name = withContext(Dispatchers.IO) {
        runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull()
    } ?: ""
    return PlaceSyncResult(FeedPlace(location.latitude, location.longitude, name).also { FeedPlaceStore.save(context, it) }, null)
}

/**
 * Runs location syncs outside any screen, so the sync and the permission answer survive Home
 * switching layouts or the feed page being rebuilt while it waits for a fix.
 */
object FeedPlaceSync {
    private val scope = kotlinx.coroutines.MainScope()
    val syncing = kotlinx.coroutines.flow.MutableStateFlow(false)
    val problem = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val place = kotlinx.coroutines.flow.MutableStateFlow<FeedPlace?>(null)
    private var loaded = false

    fun load(context: Context) {
        if (!loaded) { loaded = true; place.value = FeedPlaceStore.load(context) }
    }

    fun start(context: Context) {
        if (syncing.value) return
        val app = context.applicationContext
        syncing.value = true; problem.value = null
        scope.launch {
            val result = try { syncFeedPlace(app) } catch (_: Throwable) { PlaceSyncResult(null, "Couldn't get a location fix.") }
            result.place?.let { place.value = it }
            problem.value = result.problem
            syncing.value = false
        }
    }
}

// ---------- Now playing ----------

/**
 * Android only shares other apps' media sessions with an app the user has allowed as a
 * notification listener. This service exists for that permission. The only thing it takes from
 * notifications is the media session a player attaches to its playback notification, which is how
 * apps like YouTube and YouTube Music announce themselves; it reads no notification text.
 */
class NowPlayingListenerService : NotificationListenerService() {
    // These run inside the launcher's process for every notification on the phone, so nothing here
    // may ever throw: an exception would take Home down with it.
    override fun onListenerConnected() {
        try { activeNotifications?.forEach(::remember) } catch (_: Throwable) { }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) remember(sbn)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        try { sbn?.key?.let(NowPlayingTokens::remove) } catch (_: Throwable) { }
    }

    private fun remember(sbn: StatusBarNotification) {
        try {
            // Only playback notifications matter. Reading extras decodes the app's data, which can fail
            // for notifications carrying an app's private types, so this stays inside the try.
            val notification = sbn.notification ?: return
            @Suppress("DEPRECATION")
            val token = notification.extras?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            if (token != null) NowPlayingTokens.put(sbn.key, token) else NowPlayingTokens.remove(sbn.key)
        } catch (_: Throwable) {
        }
    }
}

/** Media sessions announced through playback notifications, shared with the feed. */
object NowPlayingTokens {
    private val tokens = linkedMapOf<String, MediaSession.Token>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun all(): List<MediaSession.Token> = synchronized(tokens) { tokens.values.toList() }
    fun put(key: String, token: MediaSession.Token) { synchronized(tokens) { tokens[key] = token }; notifyChanged() }
    fun remove(key: String) { if (synchronized(tokens) { tokens.remove(key) } != null) notifyChanged() }
    fun listen(listener: () -> Unit) { main.post { listeners += listener } }
    fun unlisten(listener: () -> Unit) { main.post { listeners -= listener } }
    private fun notifyChanged() = main.post { listeners.toList().forEach { it() } }
}

private fun listenerComponent(context: Context) = ComponentName(context, NowPlayingListenerService::class.java)

fun hasNowPlayingAccess(context: Context) =
    context.getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(listenerComponent(context))

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
    private val listenerComponent = listenerComponent(context)
    // Keyed by session token: Android hands out new MediaController objects for the same session.
    private val callbacks = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private val pausedAt = mutableMapOf<String, Long>()
    private var activeSessions: List<MediaController> = emptyList()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        activeSessions = it.orEmpty(); runCatching { watchAll() }
    }
    private val tokensListener: () -> Unit = { runCatching { watchAll() } }
    /** A paused track stays briefly so you can resume it, then the panel goes away. */
    private val pausedGraceMs = 60_000L

    fun start(): Boolean = runCatching {
        manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
        activeSessions = manager.getActiveSessions(listenerComponent)
        NowPlayingTokens.listen(tokensListener)
        watchAll()
        true
    }.getOrElse { onChange(null); false }

    fun stop() {
        NowPlayingTokens.unlisten(tokensListener)
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        callbacks.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
    }

    /** Re-evaluate, e.g. once a minute, so a long-paused session drops off. */
    fun refresh() = onChange(runCatching { pick(callbacks.values.map { it.first }) }.getOrNull())

    /** Sessions from Android's active list plus any announced only through a playback notification. */
    private fun watchAll() {
        val known = activeSessions.map { it.sessionToken }.toSet()
        val fromNotifications = NowPlayingTokens.all().filter { it !in known }
            .mapNotNull { runCatching { MediaController(context, it) }.getOrNull() }
        watch(activeSessions + fromNotifications)
    }

    private fun watch(controllers: List<MediaController>) {
        val live = controllers.map { it.sessionToken }.toSet()
        callbacks.keys.filter { it !in live }.forEach { token ->
            callbacks.remove(token)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
        }
        controllers.filter { it.sessionToken !in callbacks }.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() {
                    // A closed player can leave a stale "playing" state behind; drop it right away.
                    callbacks.remove(controller.sessionToken)?.let { (c, cb) -> c.unregisterCallback(cb) }
                    refresh()
                }
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
