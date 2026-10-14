package com.jake.duolauncher

import kotlin.math.abs

/**
 * Maps the native full-width pager position to the scroll distance shown by the
 * expanded workspace. Home pages advance by one pane while the pages outside
 * Home still enter and leave across the full pager width.
 */
internal data class WorkspacePageMotion(
    val firstHome: Int,
    val homePages: Int,
    val pageWidth: Float,
    val homeStride: Float,
    /**
     * How far the pager moves from the last Home page to All apps. Upstream it is a full
     * viewport, so All apps fills the window. On a dual-screen device it is one Home stride,
     * so All apps lands on the right screen beside the last Home page.
     */
    val trailingStride: Float = pageWidth,
) {
    init {
        require(homePages > 0)
        require(pageWidth.isFinite() && pageWidth > 0f)
        require(homeStride.isFinite() && homeStride > 0f)
        require(trailingStride.isFinite() && trailingStride > 0f)
    }

    private val lastHome = homePages - 1
    private val lastHomeOffset = lastHome * homeStride

    /** Visual scroll offset for a physical (and possibly fractional) pager position. */
    fun offset(position: Float): Float {
        val logical = position - firstHome
        return when {
            logical < 0f -> logical * pageWidth
            logical <= lastHome -> logical * homeStride
            else -> lastHomeOffset + (logical - lastHome) * trailingStride
        }
    }

    /** Physical pager position for a visual scroll offset. */
    fun position(offset: Float): Float {
        val logical = when {
            offset < 0f -> offset / pageWidth
            offset <= lastHomeOffset && lastHome > 0 -> offset / homeStride
            else -> lastHome + (offset - lastHomeOffset) / trailingStride
        }
        return logical + firstHome
    }

    fun positionAfterVisualDelta(position: Float, delta: Float): Float =
        position(offset(position) + delta)

    fun stride(fromPosition: Int, towardPosition: Int): Float =
        abs(offset(towardPosition.toFloat()) - offset(fromPosition.toFloat()))
}
