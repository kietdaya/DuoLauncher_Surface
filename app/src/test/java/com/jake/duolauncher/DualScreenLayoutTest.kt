package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Original Surface Duo, spanned portrait: two 1350 px screens and an 84 px hinge at
 * density 2.5, so 540 dp screens and a 33.6 dp hinge in a 1113.6 dp wide Home box.
 */
class DualScreenLayoutTest {
    private val boxWidth = 2784f / 2.5f
    private val hingeLeft = 1350f / 2.5f
    private val hingeRight = 1434f / 2.5f
    private val split = dualScreenSplit(boxWidth, hingeLeft, hingeRight)!!
    private val preset = LayoutPreset()
    private val geometry = homeGeometry(boxWidth, 720f, preset, labels = true, screenWidth = split.screenWidth)

    @Test fun `surface duo splits into two 540dp screens`() {
        assertEquals(540f, split.screenWidth, 0.01f)
        assertEquals(573.6f, split.rightScreenStart, 0.01f)
        assertEquals(split.rightScreenStart, split.stride, 0f)
    }

    @Test fun `hinges that don't cut the box are ignored`() {
        assertNull(dualScreenSplit(boxWidth, 0f, 20f))
        assertNull(dualScreenSplit(boxWidth, boxWidth - 20f, boxWidth))
        assertNull(dualScreenSplit(boxWidth, 500f, 500f))
    }

    @Test fun `each home page is one screen wide`() {
        assertTrue(geometry.expanded)
        assertEquals(split.screenWidth, geometry.homeWidth, 0f)
    }

    @Test fun `home pages never reach under the hinge or the dock`() {
        val pane = geometry.gridWidth + 16f
        // Left page starts at the left screen's edge and ends before the hinge.
        assertTrue(pane <= hingeLeft)
        // Right page ends where the pager ends, leaving the dock its gutter.
        val pagerWidth = boxWidth - preset.dockWidth - 28f
        assertTrue(split.rightScreenStart + pane <= pagerWidth + 0.01f)
    }

    @Test fun `all apps sits one screen past the last home page`() {
        val stride = split.stride
        val motion = WorkspacePageMotion(firstHome = 0, homePages = 3, pageWidth = 1000f,
            homeStride = stride, trailingStride = stride)
        // Logical page 3 is All apps: one stride after the last Home page.
        assertEquals(3 * stride, motion.offset(3f), 0.01f)
        assertEquals(3f, motion.position(3 * stride), 0.0001f)
        // Placed at the right screen origin + 3 strides, it shows on the right screen.
        val libraryOnScreen = split.rightScreenStart + 3 * stride - motion.offset(3f)
        assertEquals(split.rightScreenStart, libraryOnScreen, 0.01f)
    }

    @Test fun `upstream layout is unchanged without a hinge`() {
        val upstream = homeGeometry(boxWidth, 720f, preset, labels = true)
        assertEquals(minOf(460f, boxWidth * 0.56f), upstream.homeWidth, 0f)
        val motion = WorkspacePageMotion(firstHome = 0, homePages = 3, pageWidth = 1000f, homeStride = 500f)
        assertEquals(2000f, motion.offset(3f), 0f)
    }
}
