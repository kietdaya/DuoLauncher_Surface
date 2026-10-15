package com.jake.duolauncher

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.exp

/**
 * The unfold reveal: when the Duo opens from one screen to both, Home starts blurred and dim
 * and comes into focus as the hinge reaches flat.
 *
 * Progress follows the hinge angle sensor, measured from wherever the hinge was when Home
 * spanned, so the whole blur range is used however late the Duo spans. If the hinge stops
 * moving (book posture) or the sensor is missing or quiet, a short timed finish clears it,
 * so Home can never stay blurred.
 */
internal object UnfoldReveal {
    private const val TAG = "DuoUnfoldReveal"
    private const val WIDE_DP = 650
    private const val FLAT = 180f
    /** Within this many degrees of flat counts as fully open. */
    private const val FLAT_TOLERANCE = 8f
    /** Spanning closer to flat than this leaves too little travel to follow; use the timed finish. */
    private const val MIN_TRAVEL = 20f
    private const val IDLE_MS = 280L
    private const val MAX_MS = 1_600L
    private const val FINISH_MS = 420L
    const val MAX_BLUR_DP = 22f

    /** Window width of the previous MainActivity instance, or -1 before the first one. */
    private var lastWidthDp = -1

    /**
     * Call once per MainActivity creation. True when the window just went from one screen to
     * both, which on the Duo means it was unfolded. Rotations, app returns and the first launch
     * after a process start keep the width class and return false.
     */
    fun consume(context: Context, widthDp: Int): Boolean {
        val previous = lastWidthDp
        lastWidthDp = widthDp
        if (previous < 0 || previous >= WIDE_DP || widthDp < WIDE_DP) return false
        val animationsOff = Settings.Global.getFloat(context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        return !animationsOff
    }

    /** Hinge-driven progress from 0 (just spanned) to 1 (sharp). Starts at 1 when [active] is false. */
    @Composable
    fun rememberProgress(active: Boolean): MutableFloatState {
        val progress = remember { mutableFloatStateOf(if (active) 0f else 1f) }
        val context = LocalContext.current
        LaunchedEffect(active) {
            if (!active) return@LaunchedEffect
            val sensors = context.getSystemService(SensorManager::class.java)
            val hinge = sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
            var startAngle = Float.NaN
            var target = 0f
            var lastMoveMs = SystemClock.uptimeMillis()
            var lastAngle = Float.NaN
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val angle = event.values.firstOrNull() ?: return
                    if (startAngle.isNaN()) {
                        startAngle = angle
                        Log.i(TAG, "spanned at hinge=$angle°")
                    }
                    if (lastAngle.isNaN() || abs(angle - lastAngle) >= 1f) lastMoveMs = SystemClock.uptimeMillis()
                    lastAngle = angle
                    val travel = abs(startAngle - FLAT) - FLAT_TOLERANCE
                    if (travel < MIN_TRAVEL) return
                    val remaining = (abs(angle - FLAT) - FLAT_TOLERANCE).coerceAtLeast(0f)
                    // Never move backwards: closing a little while opening shouldn't re-blur Home.
                    target = maxOf(target, (1f - remaining / travel).coerceIn(0f, 1f))
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            val registered = sensors != null && hinge != null &&
                sensors.registerListener(listener, hinge, SensorManager.SENSOR_DELAY_GAME)
            if (!registered) Log.i(TAG, "no hinge sensor; timed reveal")
            try {
                val startMs = SystemClock.uptimeMillis()
                var finishFrom = -1f
                var finishStartMs = 0L
                var lastFrameNs = 0L
                while (progress.floatValue < 1f) {
                    val frameNs = withFrameNanos { it }
                    val now = SystemClock.uptimeMillis()
                    val shown = progress.floatValue
                    if (finishFrom < 0f && (target >= 1f || now - lastMoveMs > IDLE_MS || now - startMs > MAX_MS)) {
                        finishFrom = shown
                        finishStartMs = now
                    }
                    progress.floatValue = if (finishFrom >= 0f) {
                        val t = ((now - finishStartMs).toFloat() / FINISH_MS).coerceIn(0f, 1f)
                        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                        finishFrom + (1f - finishFrom) * eased
                    } else {
                        // Smooth sensor steps; the hinge reports far less often than the display refreshes.
                        val dt = if (lastFrameNs == 0L) 0.016f else (frameNs - lastFrameNs) / 1e9f
                        shown + (target - shown) * (1f - exp(-dt / 0.07f))
                    }
                    lastFrameNs = frameNs
                }
            } finally {
                sensors?.unregisterListener(listener)
            }
        }
        return progress
    }
}

/** Blur, dim and a slight settle-in zoom for an unfold [progress] from 0 to 1. */
internal fun Modifier.unfoldReveal(progress: MutableFloatState): Modifier = this.graphicsLayer {
    val p = progress.floatValue
    if (p >= 1f) {
        renderEffect = null
        scaleX = 1f; scaleY = 1f
        return@graphicsLayer
    }
    val ease = 1f - (1f - p) * (1f - p)
    val radius = (UnfoldReveal.MAX_BLUR_DP * density * (1f - ease)).coerceAtLeast(0.01f)
    val brightness = 0.5f + 0.5f * ease
    val dim = ColorMatrixColorFilter(ColorMatrix().apply { setScale(brightness, brightness, brightness, 1f) })
    renderEffect = RenderEffect.createChainEffect(
        RenderEffect.createColorFilterEffect(dim),
        RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
    ).asComposeRenderEffect()
    // Zoom in slightly and settle, so blurred edges never pull in from the screen borders.
    val scale = 1f + 0.035f * (1f - ease)
    scaleX = scale; scaleY = scale
}
