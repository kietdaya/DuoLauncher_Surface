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

/** A town from the weather service's place search. */
data class PlaceMatch(
    val name: String, val region: String, val country: String, val countryCode: String,
    val latitude: Double, val longitude: Double,
) {
    /** "Pennsylvania, US": what tells two towns of the same name apart. */
    val label get() = listOf(region, countryCode).filter { it.isNotBlank() }.joinToString(", ")
}

private val UsStates = mapOf(
    "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California",
    "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware", "DC" to "District of Columbia", "FL" to "Florida",
    "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa",
    "KS" to "Kansas", "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
    "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi", "MO" to "Missouri",
    "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada", "NH" to "New Hampshire", "NJ" to "New Jersey",
    "NM" to "New Mexico", "NY" to "New York", "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio",
    "OK" to "Oklahoma", "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina",
    "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont",
    "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
)

/** Splits "Erie, PA" into the town to search for and the part that narrows it down. */
fun splitPlaceQuery(query: String): Pair<String, String?> {
    val parts = query.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    return (parts.firstOrNull() ?: "") to parts.drop(1).joinToString(" ").ifBlank { null }
}

/**
 * Keeps the towns that fit [hint] (a state, its US abbreviation, or a country), in the service's order.
 * If nothing fits, every town is kept, so a typo in the hint never hides the result.
 */
fun rankPlaceMatches(matches: List<PlaceMatch>, hint: String?): List<PlaceMatch> {
    val h = hint?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return matches
    val state = UsStates[h.uppercase()]?.lowercase()
    val fitting = matches.filter { m ->
        val region = m.region.lowercase()
        region.startsWith(h) || (state != null && region == state) ||
            m.countryCode.equals(h, ignoreCase = true) || m.country.lowercase().startsWith(h)
    }
    return fitting.ifEmpty { matches }
}
