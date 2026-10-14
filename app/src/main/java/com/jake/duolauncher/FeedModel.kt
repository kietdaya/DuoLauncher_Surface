package com.jake.duolauncher

/*
 * Plain logic behind the feed page, kept free of Android so it runs in JVM tests.
 */

const val FEED_WINDOW_HOURS = 6
private const val HOUR_MS = 3_600_000L

/** One calendar instance in the feed window. Times are epoch milliseconds. */
data class FeedEvent(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int? = null)

/** The next six hours, starting at the top of the current hour, like the instrument's "17–23" scale. */
data class FeedWindow(val start: Long, val end: Long) {
    val span get() = end - start
    /** Fraction of the window at [time], clamped to 0..1. */
    fun fraction(time: Long) = ((time - start).toFloat() / span).coerceIn(0f, 1f)
}

fun feedWindow(now: Long, zoneOffsetMs: Long): FeedWindow {
    val local = now + zoneOffsetMs
    val start = local - Math.floorMod(local, HOUR_MS) - zoneOffsetMs
    return FeedWindow(start, start + FEED_WINDOW_HOURS * HOUR_MS)
}

/** An event placed on the timeline: [lane] of [lanes] side-by-side columns where events overlap. */
data class PlacedEvent(val event: FeedEvent, val lane: Int, val lanes: Int)

/**
 * Timed events that touch [window], in columns so overlapping events sit side by side.
 * All-day events are left out; the page lists them separately.
 */
fun placeFeedEvents(events: List<FeedEvent>, window: FeedWindow): List<PlacedEvent> {
    val timed = events.filter { !it.allDay && it.end > window.start && it.begin < window.end }
        .sortedWith(compareBy<FeedEvent> { it.begin }.thenByDescending { it.end })
    val placed = mutableListOf<PlacedEvent>()
    var cluster = mutableListOf<Pair<FeedEvent, Int>>()
    var clusterEnd = Long.MIN_VALUE
    val laneEnds = mutableListOf<Long>()
    fun flush() {
        val lanes = (cluster.maxOfOrNull { it.second } ?: -1) + 1
        cluster.forEach { (event, lane) -> placed += PlacedEvent(event, lane, lanes) }
        cluster = mutableListOf(); laneEnds.clear()
    }
    for (event in timed) {
        if (event.begin >= clusterEnd && cluster.isNotEmpty()) flush()
        val free = laneEnds.indexOfFirst { it <= event.begin }
        val lane = if (free >= 0) free else laneEnds.size.also { laneEnds += 0L }
        laneEnds[lane] = event.end
        cluster += event to lane
        clusterEnd = maxOf(clusterEnd, event.end)
    }
    if (cluster.isNotEmpty()) flush()
    return placed
}

/** The event to light up as a key: the one happening now, otherwise the next to start. */
fun highlightedEvent(events: List<FeedEvent>, now: Long): FeedEvent? =
    events.filter { !it.allDay && it.end > now }.minByOrNull { if (it.begin <= now) Long.MIN_VALUE else it.begin }

/** Short description for a WMO weather code, as Open-Meteo reports it. */
fun weatherDescription(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55, 56, 57 -> "Drizzle"
    61, 63, 65, 66, 67 -> "Rain"
    71, 73, 75, 77 -> "Snow"
    80, 81, 82 -> "Showers"
    85, 86 -> "Snow showers"
    95, 96, 99 -> "Thunderstorm"
    else -> "—"
}

/**
 * First upcoming hour today whose chance of rain is at least [threshold] percent.
 * [hours] are local "yyyy-MM-ddTHH:mm" strings paired with chances; [nowLocal] uses the same format.
 */
fun rainHint(hours: List<Pair<String, Int>>, nowLocal: String, threshold: Int = 30): Pair<Int, String>? {
    val today = nowLocal.take(10)
    val currentHour = nowLocal.take(13)
    return hours.firstOrNull { (time, chance) -> time.take(10) == today && time.take(13) >= currentHour && chance >= threshold }
        ?.let { (time, chance) -> chance to time.substring(11, 16) }
}

/** Where the current temperature sits between today's low and high, for the dial. */
fun temperatureFraction(current: Double, low: Double, high: Double): Float =
    if (high <= low) .5f else ((current - low) / (high - low)).toFloat().coerceIn(0f, 1f)
