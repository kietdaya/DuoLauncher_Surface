package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlphabetIndexTest {
    private val available = setOf("A", "C", "G", "#")

    @Test fun `a letter with apps jumps to itself`() {
        assertEquals("C", nearestIndexLetter("C", available))
    }

    @Test fun `an empty letter jumps to the next letter with apps`() {
        assertEquals("C", nearestIndexLetter("B", available))
        assertEquals("G", nearestIndexLetter("D", available))
        assertEquals("#", nearestIndexLetter("H", available))
    }

    @Test fun `past the last group it falls back to the last letter with apps`() {
        assertEquals("G", nearestIndexLetter("Z", setOf("A", "G")))
    }

    @Test fun `no apps means no jump`() {
        assertNull(nearestIndexLetter("A", emptySet()))
    }

    @Test fun `index runs A to Z then number sign`() {
        assertEquals(27, IndexLetters.size)
        assertEquals("A", IndexLetters.first())
        assertEquals("#", IndexLetters.last())
    }

    @Test fun `strip shows every letter when there is room`() {
        assertEquals((0 until 27).toList(), indexSlots(27, 40))
    }

    @Test fun `condensed strip alternates letters and dots and keeps A and number sign`() {
        val slots = indexSlots(27, 14)
        assertEquals(13, slots.size)
        assertEquals(0, slots.first())
        assertEquals(26, slots.last())
        slots.forEachIndexed { i, slot -> assertEquals(i % 2 == 1, slot == null) }
    }

    @Test fun `tiny strip still shows first and last letters`() {
        assertEquals(listOf(0, null, 26), indexSlots(27, 1))
    }
}
