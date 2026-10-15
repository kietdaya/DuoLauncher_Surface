package com.jake.duolauncher

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator

/** Where the hinge measurement came from. */
enum class HingeSource(val label: String) {
    WINDOW_MANAGER("WindowManager"),
    SURFACE_DUO_FALLBACK("Surface Duo fallback"),
}

/**
 * A vertical hinge that physically hides part of the window, in window pixels.
 * Pixels in [left, right) are under the hinge and can never show content.
 *
 * Only dual-screen devices such as the Surface Duo produce one. Seamless foldables
 * report a zero-width fold, which is ignored so they keep the upstream layout.
 */
data class DuoHinge(val left: Int, val right: Int, val source: HingeSource) {
    val width get() = right - left
}

/** Known spanned window sizes for devices whose WindowManager may not report the hinge. */
private data class KnownDualScreen(val windowWidth: Int, val windowHeight: Int, val screenWidth: Int, val hingeWidth: Int)

private val knownDualScreens = listOf(
    // Original Surface Duo, spanned portrait: two 1350 x 1800 screens and an 84 px hinge.
    KnownDualScreen(windowWidth = 2784, windowHeight = 1800, screenWidth = 1350, hingeWidth = 84),
)

private fun surfaceDuoFallback(activity: Activity): DuoHinge? {
    if (!Build.MANUFACTURER.equals("Microsoft", ignoreCase = true)) return null
    if (!Build.MODEL.startsWith("Surface Duo", ignoreCase = true)) return null
    val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
    val match = knownDualScreens.firstOrNull { it.windowWidth == bounds.width() && it.windowHeight == bounds.height() }
        ?: return null
    return DuoHinge(match.screenWidth, match.screenWidth + match.hingeWidth, HingeSource.SURFACE_DUO_FALLBACK)
}

private fun FoldingFeature.asDuoHinge(): DuoHinge? =
    if (isSeparating && orientation == FoldingFeature.Orientation.VERTICAL && bounds.width() > 0)
        DuoHinge(bounds.left, bounds.right, HingeSource.WINDOW_MANAGER)
    else null

/**
 * The current vertical hinge, or null when the window sits on one screen.
 * WindowManager's report wins; the Surface Duo table only fills in when it reports nothing.
 * MainActivity is recreated when the Duo spans or folds, so both values start fresh each time.
 */
@Composable
internal fun rememberDuoHinge(activity: Activity): DuoHinge? {
    val configuration = LocalConfiguration.current
    val reported by produceState<DuoHinge?>(initialValue = null, activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            value = info.displayFeatures.filterIsInstance<FoldingFeature>().firstNotNullOfOrNull { it.asDuoHinge() }
        }
    }
    val fallback = remember(activity, configuration.screenWidthDp, configuration.screenHeightDp, configuration.orientation) {
        surfaceDuoFallback(activity)
    }
    val hinge = reported ?: fallback
    SideEffect { CurrentDuoHinge.value = hinge }
    return hinge
}

/** The hinge Home last measured, for code outside composition such as app launches. */
internal object CurrentDuoHinge {
    @Volatile var value: DuoHinge? = null
}

/** Which Duo screens other apps cover right now, as reported by the Duo Dev screens service. */
object DuoScreenCoverage {
    val left = kotlinx.coroutines.flow.MutableStateFlow(false)
    val right = kotlinx.coroutines.flow.MutableStateFlow(false)
    fun set(left: Boolean, right: Boolean) { this.left.value = left; this.right.value = right }
}
