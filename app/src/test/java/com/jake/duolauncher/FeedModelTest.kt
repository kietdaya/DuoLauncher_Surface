package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedModelTest {
    private val hour = 3_600_000L
    private val day = 1_000_000L * hour // an arbitrary whole-hour epoch
    private fun ev(id: Long, startH: Double, endH: Double, allDay: Boolean = false) =
        FeedEvent(id, "Event $id", day + (startH * hour).toLong(), day + (endH * hour).toLong(), allDay)

    @Test fun `window starts at the top of the local hour and runs six hours`() {
        val offset = -4 * hour // UTC-4
        val now = day + 17 * hour + 25 * 60_000L
        val window = feedWindow(now, offset)
        assertEquals(day + 17 * hour, window.start)
        assertEquals(6 * hour, window.span)
    }

    @Test fun `window respects half-hour zones`() {
        val offset = (5.5 * hour).toLong() // UTC+5:30
        val now = day + 10 * hour + 10 * 60_000L // local 15:40
        // Local 15:00 is 09:30 UTC.
        assertEquals(day + (9.5 * hour).toLong(), feedWindow(now, offset).start)
    }

    @Test fun `separate events each get a full-width lane`() {
        val placed = placeFeedEvents(listOf(ev(1, 17.0, 18.0), ev(2, 18.0, 19.0)), FeedWindow(day + 17 * hour, day + 23 * hour))
        assertEquals(listOf(1, 1), placed.map { it.lanes })
    }

    @Test fun `overlapping events sit side by side`() {
        val placed = placeFeedEvents(listOf(ev(1, 17.0, 19.0), ev(2, 18.0, 20.0), ev(3, 21.0, 22.0)),
            FeedWindow(day + 17 * hour, day + 23 * hour)).associateBy { it.event.id }
        assertEquals(2, placed.getValue(1).lanes)
        assertEquals(0, placed.getValue(1).lane)
        assertEquals(1, placed.getValue(2).lane)
        assertEquals(1, placed.getValue(3).lanes)
    }

    @Test fun `all-day and out-of-window events are left off the timeline`() {
        val placed = placeFeedEvents(listOf(ev(1, 0.0, 24.0, allDay = true), ev(2, 8.0, 9.0), ev(3, 23.5, 24.0)),
            FeedWindow(day + 17 * hour, day + 23 * hour))
        assertEquals(emptyList<PlacedEvent>(), placed)
    }

    @Test fun `the event in progress is highlighted, else the next one`() {
        val events = listOf(ev(1, 17.0, 17.8), ev(2, 18.0, 19.0))
        assertEquals(1L, highlightedEvent(events, day + (17.5 * hour).toLong())?.id)
        assertEquals(2L, highlightedEvent(events, day + (17.9 * hour).toLong())?.id)
        assertNull(highlightedEvent(events, day + 20 * hour))
    }

    @Test fun `rain hint finds the first likely hour from now`() {
        val hours = listOf("2026-10-14T16:00" to 60, "2026-10-14T18:00" to 10, "2026-10-14T21:00" to 40, "2026-10-15T01:00" to 90)
        assertEquals(40 to "21:00", rainHint(hours, "2026-10-14T17:25"))
        assertNull(rainHint(hours.take(2), "2026-10-14T17:25"))
    }

    @Test fun `weather codes read as words`() {
        assertEquals("Partly cloudy", weatherDescription(2))
        assertEquals("Thunderstorm", weatherDescription(95))
    }

    @Test fun `dial fraction sits between low and high`() {
        assertEquals(.5f, temperatureFraction(56.0, 49.0, 63.0), .001f)
        assertEquals(1f, temperatureFraction(70.0, 49.0, 63.0), 0f)
        assertEquals(.5f, temperatureFraction(50.0, 50.0, 50.0), 0f)
    }
}
