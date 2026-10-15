package com.jake.duolauncher

import android.Manifest
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

/** Colours for the feed's instrument panel, following the launcher's light or dark theme. */
private data class FeedStyle(
    val ink: Color, val muted: Color, val panel: Color, val panelEdge: Color,
    val key: Color, val keyEdge: Color, val keyInk: Color, val eventFill: Color, val eventEdge: Color,
    val track: Color, val signal: Color = Color(0xFFFF5A1F),
) {
    companion object {
        fun of(palette: DuoPalette) = if (palette.dark) FeedStyle(
            ink = palette.ink, muted = Color(0xFF8D8C87),
            panel = Color.White.copy(alpha = .06f), panelEdge = Color.White.copy(alpha = .10f),
            key = Color(0xFFD9D8D2), keyEdge = Color(0xFFC4C3BD), keyInk = Color(0xFF1B1C1E),
            eventFill = Color.White.copy(alpha = .09f), eventEdge = Color.White.copy(alpha = .12f),
            track = Color.White.copy(alpha = .10f),
        ) else FeedStyle(
            ink = palette.ink, muted = palette.ink.copy(alpha = .62f),
            panel = palette.glass.copy(alpha = .48f), panelEdge = Color.White.copy(alpha = .38f),
            key = Color.White.copy(alpha = .92f), keyEdge = Color.White, keyInk = palette.ink,
            eventFill = Color.White.copy(alpha = .22f), eventEdge = Color.White.copy(alpha = .4f),
            track = palette.ink.copy(alpha = .12f),
        )
    }
}

private val Mono = FontFamily.Monospace

/**
 * The feed on the Duo's left screen: today's date, weather, the next six hours of the calendar and
 * what's playing. Now playing only appears while something plays; the calendar takes its room otherwise.
 */
@Composable
internal fun FeedPage(latitude: Double?, longitude: Double?, placeName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val leftOptions = leftScreenOptions()
    val style = FeedStyle.of(LocalDuoPalette.current)
    val use24h = DateFormat.is24HourFormat(context)

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000L - System.currentTimeMillis() % 30_000L); now = System.currentTimeMillis() }
    }
    val minute = now / 60_000L
    val window = remember(minute) { feedWindow(now, TimeZone.getDefault().getOffset(now).toLong()) }

    var resumes by remember { mutableIntStateOf(0) }
    var calendarAllowed by remember { mutableStateOf(hasCalendarAccess(context)) }
    var nowPlayingAllowed by remember { mutableStateOf(hasNowPlayingAccess(context)) }
    LifecycleResumeEffect(Unit) {
        resumes++
        now = System.currentTimeMillis()
        calendarAllowed = hasCalendarAccess(context)
        nowPlayingAllowed = hasNowPlayingAccess(context)
        onPauseOrDispose { }
    }
    val requestCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calendarAllowed = it }

    var events by remember { mutableStateOf(emptyList<FeedEvent>()) }
    LaunchedEffect(calendarAllowed, window, resumes, minute) { events = loadFeedEvents(context, window.start, window.end) }

    // Weather place: the one saved by tapping Sync, else the sunrise/sunset place if there is one.
    FeedPlaceSync.load(context) // loads the saved place once; later calls do nothing
    val synced by FeedPlaceSync.place.collectAsState()
    val syncing by FeedPlaceSync.syncing.collectAsState()
    val syncProblem by FeedPlaceSync.problem.collectAsState()
    val place = synced ?: if (latitude != null && longitude != null) FeedPlace(latitude, longitude, placeName) else null
    val launcherActivity = androidx.activity.compose.LocalActivity.current as? MainActivity
    val onSync: () -> Unit = { launcherActivity?.syncFeedLocation() }

    var weather by remember { mutableStateOf(FeedWeatherCache.value) }
    var weatherLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(place?.latitude, place?.longitude, resumes) {
        if (place != null) weather = loadFeedWeather(place.latitude, place.longitude)
        weatherLoaded = true
    }

    var nowPlaying by remember { mutableStateOf<NowPlaying?>(null) }
    var tracker by remember { mutableStateOf<NowPlayingTracker?>(null) }
    DisposableEffect(nowPlayingAllowed) {
        // Always try: if access is missing Android refuses quietly and nothing shows. Restarts when access changes.
        val t = NowPlayingTracker(context) { nowPlaying = it }.takeIf { it.start() }
        if (t == null) nowPlaying = null
        tracker = t
        onDispose { t?.stop() }
    }
    LaunchedEffect(minute) { tracker?.refresh() }

    Column(modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp).testTag("feed-page"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WeatherPanel(weather, place, loaded = weatherLoaded, syncing = syncing, syncProblem = syncProblem, use24h = use24h,
            style = style, onSync = onSync)
        CalendarPanel(events, window, now, calendarAllowed, use24h, style, Modifier.weight(1f),
            onAllow = { requestCalendar.launch(Manifest.permission.READ_CALENDAR) },
            onOpen = { openCalendarEvent(context, it, leftOptions) })
        AnimatedVisibility(nowPlaying != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            // Keep showing the last track while the panel animates out.
            val last = remember { arrayOfNulls<NowPlaying>(1) }
            nowPlaying?.let { last[0] = it }
            last[0]?.let { NowPlayingPanel(it, style) }
        }
    }
}

@Composable
private fun FeedPanel(style: FeedStyle, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(style.panel).border(1.dp, style.panelEdge, shape).padding(14.dp),
        content = content)
}

@Composable
private fun PanelLabel(left: String, right: String, style: FeedStyle) {
    Row(Modifier.fillMaxWidth()) {
        Text(left.uppercase(), Modifier.weight(1f), color = style.muted, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.6.sp)
        Text(right.uppercase(), color = style.muted, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.6.sp)
    }
}

/** A KO II-style key: light grey with a pressed-in bottom edge, or dark smoke with an orange glyph. */
@Composable
private fun FeedKey(text: String?, style: FeedStyle, modifier: Modifier = Modifier, light: Boolean = true,
    icon: ImageVector? = null, height: Dp = 44.dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.height(height).clip(shape)
        .background(if (light) style.key else style.panel)
        .border(1.dp, if (light) style.keyEdge else style.panelEdge, shape)
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (light) .10f else .18f)),
            startY = with(androidx.compose.ui.platform.LocalDensity.current) { (height - 4.dp).toPx() }))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        val tint = if (light) style.keyInk else style.signal
        if (icon != null) Icon(icon, text, tint = tint, modifier = Modifier.size(22.dp))
        else if (text != null) Text(text, color = if (light) style.keyInk else style.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun clock(hourOfDay: Int, minute: Int, use24h: Boolean) =
    if (use24h) "%d:%02d".format(hourOfDay, minute)
    else "%d:%02d".format(if (hourOfDay % 12 == 0) 12 else hourOfDay % 12, minute)

private fun hourLabel(hourOfDay: Int, use24h: Boolean) =
    if (use24h) "$hourOfDay" else "${if (hourOfDay % 12 == 0) 12 else hourOfDay % 12}"

// ---------- Weather ----------

@Composable
private fun WeatherPanel(weather: FeedWeather?, place: FeedPlace?, loaded: Boolean, syncing: Boolean, syncProblem: String?,
    use24h: Boolean, style: FeedStyle, onSync: () -> Unit) {
    FeedPanel(style, Modifier.testTag("feed-weather")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(("Weather" + (place?.name?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")).uppercase(), Modifier.weight(1f),
                color = style.muted, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Reads the location once, only on tap. Nothing runs in the background.
            Text(if (syncing) "SYNCING…" else "↻ SYNC", Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !syncing, onClick = onSync)
                .padding(horizontal = 8.dp, vertical = 4.dp).testTag("feed-weather-sync"),
                color = if (syncing) style.muted else style.ink, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.6.sp)
        }
        if (!syncing) syncProblem?.let { Text(it, color = style.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        if (place == null) {
            Text("Tap Sync to show weather for where you are. Your location is read once, only when you tap.",
                color = style.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
            FeedKey(if (syncing) "Syncing…" else "Sync location", style, Modifier.fillMaxWidth(), height = 40.dp, onClick = onSync)
            return@FeedPanel
        }
        if (weather == null) {
            Text(if (loaded) "Weather is unavailable right now." else "Loading weather…", color = style.muted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp))
            return@FeedPanel
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TemperatureDial(temperatureFraction(weather.temperature, weather.low, weather.high), style, Modifier.size(74.dp))
            Text("${Math.round(weather.temperature)}°", color = style.ink, fontSize = 40.sp, fontWeight = FontWeight.Normal)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(weatherDescription(weather.code), color = style.muted, fontSize = 13.sp)
                Text("H ${Math.round(weather.high)} · L ${Math.round(weather.low)}", color = style.muted, fontFamily = Mono, fontSize = 11.sp)
                weather.rain?.let { (chance, time) ->
                    val (h, m) = time.split(":").map(String::toInt)
                    Text("RAIN $chance% AFTER ${clock(h, m, use24h)}", color = style.muted, fontFamily = Mono, fontSize = 11.sp)
                }
            }
        }
    }
}

/** Arc from today's low to high, filled to the current temperature, with an orange marker. */
@Composable
private fun TemperatureDial(fraction: Float, style: FeedStyle, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 6.dp.toPx()
        val inset = stroke / 2 + 2.dp.toPx()
        val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        drawArc(style.track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(style.ink, 135f, 270f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val angle = Math.toRadians(135.0 + 270.0 * fraction)
        val r = arcSize.width / 2
        drawCircle(style.signal, stroke * .55f, Offset(center.x + (r * cos(angle)).toFloat(), center.y + (r * sin(angle)).toFloat()))
    }
}

// ---------- Calendar ----------

@Composable
private fun CalendarPanel(events: List<FeedEvent>, window: FeedWindow, now: Long, allowed: Boolean, use24h: Boolean,
    style: FeedStyle, modifier: Modifier, onAllow: () -> Unit, onOpen: (FeedEvent) -> Unit) {
    val zone = ZoneId.systemDefault()
    val startHour = Instant.ofEpochMilli(window.start).atZone(zone).hour
    val endHour = Instant.ofEpochMilli(window.end).atZone(zone).hour
    FeedPanel(style, modifier.testTag("feed-calendar")) {
        PanelLabel("Next 6 hours", "${hourLabel(startHour, use24h)}–${hourLabel(endHour, use24h)}", style)
        if (!allowed) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your calendar shows here once Duo Dev can read it. If you use Outlook, turn on calendar sync in the Outlook app.",
                    color = style.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                FeedKey("Allow calendar", style, Modifier.fillMaxWidth(), onClick = onAllow)
            }
            return@FeedPanel
        }
        val allDay = events.filter { it.allDay }
        if (allDay.isNotEmpty()) Text("ALL DAY · " + allDay.joinToString(" · ") { it.title }, color = style.muted, fontFamily = Mono,
            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Timeline(events, window, now, use24h, style, Modifier.fillMaxSize().padding(top = 8.dp), onOpen)
    }
}

@Composable
private fun Timeline(events: List<FeedEvent>, window: FeedWindow, now: Long, use24h: Boolean, style: FeedStyle,
    modifier: Modifier, onOpen: (FeedEvent) -> Unit) {
    val zone = ZoneId.systemDefault()
    val placed = remember(events, window) { placeFeedEvents(events, window) }
    val highlighted = remember(events, now / 60_000L) { highlightedEvent(events, now) }
    BoxWithConstraints(modifier) {
        val labelWidth = 30.dp
        val pad = 6.dp
        val usable = maxHeight - pad * 2
        fun y(time: Long) = pad + usable * window.fraction(time)
        val startHour = Instant.ofEpochMilli(window.start).atZone(zone).hour
        // Hour rules and labels
        Canvas(Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            for (i in 0..FEED_WINDOW_HOURS) {
                val yy = (pad + usable * (i / FEED_WINDOW_HOURS.toFloat())).toPx()
                drawLine(style.track, Offset(labelWidth.toPx() - 6.dp.toPx(), yy), Offset(size.width, yy), 1.dp.toPx(), pathEffect = dash)
            }
        }
        for (i in 0..FEED_WINDOW_HOURS) {
            Text(hourLabel((startHour + i) % 24, use24h), color = style.muted, fontFamily = Mono, fontSize = 10.sp,
                modifier = Modifier.offset(y = pad + usable * (i / FEED_WINDOW_HOURS.toFloat()) - 7.dp))
        }
        val trackWidth = maxWidth - labelWidth
        placed.forEach { (event, lane, lanes) ->
            val top = y(maxOf(event.begin, window.start))
            val bottom = y(minOf(event.end, window.end))
            val height = maxOf(bottom - top - 2.dp, 22.dp)
            val laneWidth = trackWidth / lanes
            val key = event == highlighted
            val shape = RoundedCornerShape(10.dp)
            val begin = Instant.ofEpochMilli(event.begin).atZone(zone)
            val end = Instant.ofEpochMilli(event.end).atZone(zone)
            Column(Modifier.offset(x = labelWidth + laneWidth * lane, y = top + 1.dp).width(laneWidth - 3.dp).height(height)
                .clip(shape).background(if (key) style.key else style.eventFill)
                .border(1.dp, if (key) style.keyEdge else style.eventEdge, shape)
                .clickable { onOpen(event) }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(event.title, color = if (key) style.keyInk else style.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = if (height > 52.dp) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (height > 36.dp) Text("${clock(begin.hour, begin.minute, use24h)}–${clock(end.hour, end.minute, use24h)}",
                    color = (if (key) style.keyInk else style.ink).copy(alpha = .7f), fontFamily = Mono, fontSize = 10.sp, maxLines = 1)
            }
        }
        if (placed.isEmpty()) Text("Nothing in the next 6 hours", Modifier.align(Alignment.Center).padding(start = labelWidth),
            color = style.muted, fontSize = 13.sp)
        // Now line
        val nowY = y(now)
        Box(Modifier.offset(x = labelWidth - 10.dp, y = nowY - 4.dp).size(8.dp).background(style.signal, RoundedCornerShape(50)))
        Box(Modifier.offset(x = labelWidth - 6.dp, y = nowY - 1.dp).fillMaxWidth().height(2.dp).background(style.signal))
    }
}

// ---------- Now playing ----------

@Composable
private fun NowPlayingPanel(playing: NowPlaying, style: FeedStyle) {
    val context = LocalContext.current
    val options = leftScreenOptions()
    val appName = remember(playing.controller.packageName) {
        runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(playing.controller.packageName, 0)).toString()
        }.getOrDefault("")
    }
    var position by remember(playing) { mutableLongStateOf(playing.currentPosition()) }
    LaunchedEffect(playing) { while (playing.playing) { position = playing.currentPosition(); delay(1000) } }
    val controls = playing.controller.transportControls
    FeedPanel(style, Modifier.testTag("feed-now-playing")) {
        PanelLabel("Now playing", appName, style)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable { runCatching { playing.controller.sessionActivity?.send(context, 0, null, null, null, null, options) } },
            verticalAlignment = Alignment.CenterVertically) {
            val artShape = RoundedCornerShape(10.dp)
            if (playing.art != null) Image(playing.art.asImageBitmap(), null, Modifier.size(56.dp).clip(artShape), contentScale = ContentScale.Crop)
            else Box(Modifier.size(56.dp).clip(artShape).background(Brush.linearGradient(listOf(Color(0xFF4B4F57), Color(0xFF2A2C30), Color(0xFF6A5A48)))))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(playing.title, color = style.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (playing.artist.isNotBlank()) Text(playing.artist, color = style.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val fraction = if (playing.durationMs > 0) (position.toFloat() / playing.durationMs).coerceIn(0f, 1f) else 0f
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(style.track)) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(style.ink))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeedKey("Previous", style, Modifier.weight(1f), icon = Icons.Rounded.SkipPrevious) { controls.skipToPrevious() }
            FeedKey(if (playing.playing) "Pause" else "Play", style, Modifier.weight(1f), light = false,
                icon = if (playing.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow) {
                if (playing.playing) controls.pause() else controls.play()
            }
            FeedKey("Next", style, Modifier.weight(1f), icon = Icons.Rounded.SkipNext) { controls.skipToNext() }
        }
    }
}

/** The feed lives on the Duo's left screen, so whatever it opens should open there too. */
@Composable
private fun leftScreenOptions(): android.os.Bundle? =
    (androidx.activity.compose.LocalActivity.current as? MainActivity)?.duoLaunchOptions(left = true)
