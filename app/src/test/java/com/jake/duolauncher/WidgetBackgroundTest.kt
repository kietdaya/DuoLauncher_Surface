package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetBackgroundTest {
    @Test fun `plates survive saving and loading`() {
        WidgetBackgroundSwatches.forEach { (_, color) ->
            val plate = WidgetBackground(color, DEFAULT_WIDGET_BACKGROUND_OPACITY)
            assertEquals(plate, decodeWidgetBackground(encodeWidgetBackground(plate)))
        }
        val clear = WidgetBackground(0xFF1C1E22.toInt(), 0f)
        assertEquals(clear, decodeWidgetBackground(encodeWidgetBackground(clear)))
    }

    @Test fun `damaged saved plates are dropped instead of crashing Home`() {
        assertNull(decodeWidgetBackground(null))
        assertNull(decodeWidgetBackground(""))
        assertNull(decodeWidgetBackground("graphite,0.4"))
        assertNull(decodeWidgetBackground("1,2,3"))
    }

    @Test fun `out of range opacity is clamped when loading`() {
        assertEquals(1f, decodeWidgetBackground("-14935518,7")!!.opacity, 0f)
        assertEquals(0f, decodeWidgetBackground("-14935518,-1")!!.opacity, 0f)
    }
}
