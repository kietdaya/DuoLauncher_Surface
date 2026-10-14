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
}
