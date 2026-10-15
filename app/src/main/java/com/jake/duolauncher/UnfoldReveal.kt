package com.jake.duolauncher

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.abs
import kotlin.math.exp

/**
 * The book-style open and close effect: Home blurs and dims as the Duo closes, and comes back
 * into focus as it opens.
 *
 * - Opening without a lock screen: Home follows the hinge, sharp by [OPEN] degrees.
 * - Opening with a lock screen: the lock screen hides the opening, so Home plays a short timed
 *   reveal the moment it appears after unlocking.
 * - Closing: once the hinge drops [CLOSE_TRIGGER] degrees below where it rested, the blur follows
 *   it down and is complete by [CLOSED] degrees, before the screens switch off.
 * - Holding still partway (book posture) finishes the reveal, so Home never stays half blurred.
 * - Folding back past flat into one-screen use never triggers anything.
 *
 * State lives here rather than in one activity because MainActivity is recreated on some
 * configuration changes; each instance only registers listeners through [attach].
 */
internal object UnfoldReveal {
    private const val TAG = "DuoUnfoldReveal"
    /** Fully sharp at or beyond this hinge angle while opening. */
    private const val OPEN = 150f
    /** Fully blurred at or below this hinge angle while closing. */
    private const val CLOSED = 15f
    /** How far the hinge must drop below its resting angle before closing starts the blur. */
    private const val CLOSE_TRIGGER = 8f
    /** No hinge movement for this long while following means the Duo is being held still. */
    private const val HOLD_MS = 300L
    private const val TIMED_MS = 650L
    /** Home must appear this soon after unlocking (or waking, without a lock) to count as opened. */
    private const val UNLOCK_FRESH_MS = 2_000L
    private const val WAKE_FRESH_MS = 4_000L
    const val MAX_BLUR_DP = 22f

    private enum class Mode { IDLE, FOLLOW, TIMED, HELD }

    /** 0 = fully blurred and dim, 1 = sharp. Read by [unfoldReveal] while drawing. */
    val progress = mutableFloatStateOf(1f)

    private var mode = Mode.IDLE
    private var angle = Float.NaN
    private var restAngle = Float.NaN
    private var top = OPEN
    private var target = 1f
    private var lastMoveMs = 0L
    private var screenOnMs = 0L
    private var unlockMs = 0L
    private var openedSinceWake = false
    private var timedFrom = 0f
    private var timedStartMs = 0L
    private var lastFrameNs = 0L
    private var frameQueued = false
    private var controller: Controller? = null

    fun attach(activity: ComponentActivity) {
        controller?.detach()
        Controller(activity).also {
            controller = it
            activity.lifecycle.addObserver(it)
        }
    }

    private val resumed get() = controller?.resumed == true

    private fun locked() = controller?.keyguard?.isKeyguardLocked == true

    private fun animationsOff(context: Context) = Settings.Global.getFloat(context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun onHinge(value: Float) {
        val now = SystemClock.uptimeMillis()
        val previous = angle
        angle = value
        if (previous.isNaN() || abs(value - previous) >= 1f) lastMoveMs = now
        if (value < OPEN) openedSinceWake = true
        if (!resumed || locked() || mode == Mode.HELD) return
        if (mode == Mode.IDLE || mode == Mode.TIMED) {
            if (restAngle.isNaN() || value > restAngle) restAngle = value
            if (value < OPEN && restAngle - value >= CLOSE_TRIGGER) {
                top = restAngle.coerceAtMost(OPEN).coerceAtLeast(CLOSED + 30f)
                mode = Mode.FOLLOW
                Log.i(TAG, "closing from ${restAngle}°")
            }
        }
        if (mode == Mode.FOLLOW) {
            target = ((value - CLOSED) / (top - CLOSED)).coerceIn(0f, 1f)
            if (value >= top) {
                mode = Mode.IDLE
                restAngle = value
            }
        }
        requestFrame()
    }

    private fun onScreenOn() {
        screenOnMs = SystemClock.uptimeMillis()
        openedSinceWake = false
        controller?.setSensor(true)
    }

    private fun onScreenOff() {
        controller?.setSensor(false)
        // Closed (or nearly): keep Home blurred so it can't flash sharp when the screens wake.
        val context = controller?.activity ?: return
        if (!angle.isNaN() && angle < OPEN && !animationsOff(context)) {
            mode = Mode.HELD
            progress.floatValue = 0f
        }
        restAngle = Float.NaN
    }

    /** Home just became visible and usable: on resume, or on unlock while already resumed. */
    private fun onShown() {
        val context = controller?.activity ?: return
        if (!resumed || locked()) return
        val now = SystemClock.uptimeMillis()
        val fresh = now - unlockMs < UNLOCK_FRESH_MS || now - screenOnMs < WAKE_FRESH_MS
        val opened = fresh && (openedSinceWake || mode == Mode.HELD)
        openedSinceWake = false
        restAngle = angle
        if (!opened || animationsOff(context)) {
            if (mode == Mode.HELD || mode == Mode.TIMED) { mode = Mode.IDLE; progress.floatValue = 1f }
            return
        }
        if (!angle.isNaN() && angle < OPEN && now - lastMoveMs < HOLD_MS) {
            // No lock screen in the way and still opening: follow the hinge the rest of the way.
            top = OPEN
            target = ((angle - CLOSED) / (top - CLOSED)).coerceIn(0f, 1f)
            progress.floatValue = 0f
            mode = Mode.FOLLOW
            Log.i(TAG, "opening, following from ${angle}°")
        } else {
            startTimed(0f)
            Log.i(TAG, "opened behind the lock screen; timed reveal (hinge ${angle}°)")
        }
        requestFrame()
    }

    private fun startTimed(from: Float) {
        mode = Mode.TIMED
        timedFrom = from
        timedStartMs = SystemClock.uptimeMillis()
        progress.floatValue = from
    }

    private fun requestFrame() {
        if (frameQueued) return
        frameQueued = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private val frame = Choreographer.FrameCallback { frameNs ->
        frameQueued = false
        val now = SystemClock.uptimeMillis()
        val shown = progress.floatValue
        val dt = if (lastFrameNs == 0L) 0.016f else ((frameNs - lastFrameNs) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNs = frameNs
        var keepGoing = true
        when (mode) {
            Mode.HELD -> keepGoing = false
            Mode.TIMED -> {
                val t = ((now - timedStartMs).toFloat() / TIMED_MS).coerceIn(0f, 1f)
                val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                progress.floatValue = timedFrom + (1f - timedFrom) * eased
                if (t >= 1f) { mode = Mode.IDLE; keepGoing = false }
            }
            Mode.FOLLOW -> {
                if (now - lastMoveMs > HOLD_MS && !angle.isNaN() && angle > CLOSED + 10f) {
                    // Held still partway open: finish the reveal instead of staying half blurred.
                    restAngle = angle
                    startTimed(shown)
                } else {
                    // Smooth sensor steps; the hinge reports less often than the display refreshes.
                    progress.floatValue = shown + (target - shown) * (1f - exp(-dt / 0.07f))
                    // At the closed end there's nothing left to do; the screens are about to turn off.
                    if (target <= 0f && shown < 0.002f) { progress.floatValue = 0f; keepGoing = false }
                }
            }
            Mode.IDLE -> {
                val next = shown + (1f - shown) * (1f - exp(-dt / 0.07f))
                progress.floatValue = if (1f - next < 0.002f) 1f else next
                keepGoing = progress.floatValue < 1f
            }
        }
        if (keepGoing) requestFrame() else lastFrameNs = 0L
    }

    private class Controller(val activity: ComponentActivity) : DefaultLifecycleObserver, SensorEventListener {
        val keyguard: KeyguardManager? = activity.getSystemService(KeyguardManager::class.java)
        private val sensors = activity.getSystemService(SensorManager::class.java)
        private val hinge = sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        private var sensorOn = false
        private var receiverOn = false
        var resumed = false

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_USER_PRESENT -> { unlockMs = SystemClock.uptimeMillis(); onShown() }
                }
            }
        }

        override fun onCreate(owner: LifecycleOwner) {
            if (hinge == null) return
            ContextCompat.registerReceiver(activity, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverOn = true
            if (activity.getSystemService(PowerManager::class.java)?.isInteractive == true) setSensor(true)
        }

        override fun onResume(owner: LifecycleOwner) {
            resumed = true
            onShown()
        }

        override fun onPause(owner: LifecycleOwner) {
            resumed = false
        }

        override fun onDestroy(owner: LifecycleOwner) = detach()

        fun detach() {
            resumed = false
            setSensor(false)
            if (receiverOn) { activity.unregisterReceiver(receiver); receiverOn = false }
            activity.lifecycle.removeObserver(this)
            if (controller === this) controller = null
        }

        /** The hinge sensor runs only while the screens are on, including behind the lock screen. */
        fun setSensor(on: Boolean) {
            if (on == sensorOn || hinge == null || sensors == null) return
            sensorOn = if (on) sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_GAME)
            else { sensors.unregisterListener(this); false }
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (controller === this) event.values.firstOrNull()?.let(::onHinge)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
}

/** Blur, dim and a slight settle-in zoom for Home while the Duo opens or closes. */
internal fun Modifier.unfoldReveal(): Modifier = this.graphicsLayer {
    val p = UnfoldReveal.progress.floatValue
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
