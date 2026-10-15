package com.jake.duolauncher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import java.lang.ref.WeakReference

internal enum class ShadePanel { NOTIFICATIONS, QUICK_SETTINGS }

internal enum class ShadeOpenResult { OPENED, SERVICE_DISABLED, SERVICE_STARTING, ACTION_REJECTED }

/**
 * The platform exposes shade expansion to third-party apps only as accessibility global actions.
 * This service deliberately observes no events and cannot inspect windows or inject gestures.
 */
class SystemShadeAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = WeakReference(this)
        updateScreenCoverage()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) updateScreenCoverage()
    }
    override fun onInterrupt() = Unit

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingUncover: Runnable? = null
    private var pendingCover: Runnable? = null
    private var leftFreedAt = Long.MIN_VALUE / 2
    private var rightFreedAt = Long.MIN_VALUE / 2

    /** A status-bar pull at [x] (screen pixels), as if your finger did it, so the Duo opens the shade there. */
    internal fun pullShade(x: Float): Boolean = try {
        val path = android.graphics.Path().apply { moveTo(x, 4f); lineTo(x, 900f) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 260)
        dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(), null, null)
    } catch (_: Throwable) {
        false
    }
    override fun onDestroy() {
        if (instance.get() === this) instance.clear()
        pendingUncover?.let(main::removeCallbacks); pendingCover?.let(main::removeCallbacks)
        DuoScreenCoverage.set(left = false, right = false)
        super.onDestroy()
    }

    /**
     * Works out which Surface Duo screens other apps' windows cover, from window positions only.
     * Runs in the launcher's process, so it must never throw.
     */
    private fun updateScreenCoverage(recheck: Boolean = false) {
        try {
            val hinge = CurrentDuoHinge.value ?: return DuoScreenCoverage.set(left = false, right = false)
            var left = false
            var right = false
            val leftApps = mutableSetOf<String>()
            val rightApps = mutableSetOf<String>()
            val bounds = android.graphics.Rect()
            val all = windows
            // While the shade or another large system panel is open, Android leaves apps out of this list.
            // Keep the last answer instead of briefly deciding a screen is free.
            if (all.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                    run { it.getBoundsInScreen(bounds); bounds.height() > hinge.left / 2 } }) return
            val screenWidth = hinge.left
            val screenHeight = resources.displayMetrics.heightPixels
            for (window in all) {
                if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                window.getBoundsInScreen(bounds)
                if (bounds.width() <= 0 || bounds.height() <= 0) continue
                val spansBoth = bounds.left < hinge.left && bounds.right > hinge.right
                // Apps can briefly report no owner (Chrome does while a page loads). Don't read that as the
                // screen being free: an unnamed one-screen window still counts. Only Home spans both screens
                // with no name worth trusting, so an unnamed spanning window is skipped.
                val owner = runCatching { window.root?.packageName?.toString() }.getOrNull()
                if (owner == packageName || (owner == null && spansBoth)) continue
                // Only windows filling most of a screen count; popups and menus don't take a screen over.
                if (bounds.height() < screenHeight / 2) continue
                val onLeft = minOf(bounds.right, hinge.left) - maxOf(bounds.left, 0)
                val onRight = minOf(bounds.right, hinge.right + screenWidth) - maxOf(bounds.left, hinge.right)
                if (onLeft >= screenWidth / 2) { left = true; owner?.let { leftApps += it } }
                if (onRight >= screenWidth / 2) { right = true; owner?.let { rightApps += it } }
            }
            DuoScreenCoverage.leftApps = leftApps
            DuoScreenCoverage.rightApps = rightApps
            applyCoverage(left, right, recheck)
        } catch (_: Throwable) {
        }
    }

    /**
     * Covering applies at once; freeing a screen waits about a third of a second so brief window changes
     * don't flip Home. Right after a screen frees, an app that closes with an animation can be reported on
     * it once more; for the next second, covering that screen again only counts if the app is still there 0.4 s later.
     */
    private fun applyCoverage(left: Boolean, right: Boolean, recheck: Boolean = false) {
        pendingUncover?.let(main::removeCallbacks); pendingUncover = null
        pendingCover?.let(main::removeCallbacks); pendingCover = null
        val now = android.os.SystemClock.uptimeMillis()
        val wasLeft = DuoScreenCoverage.left.value
        val wasRight = DuoScreenCoverage.right.value
        // A screen freed under a second ago that is reported covered again: hold it free until that lasts.
        val holdLeft = !recheck && left && !wasLeft && now - leftFreedAt < RECOVER_WINDOW_MS
        val holdRight = !recheck && right && !wasRight && now - rightFreedAt < RECOVER_WINDOW_MS
        val coverLeft = left && !holdLeft
        val coverRight = right && !holdRight
        // Then look at the windows again: cover only if the app is really still there.
        if (holdLeft || holdRight) pendingCover = Runnable { pendingCover = null; updateScreenCoverage(recheck = true) }
            .also { main.postDelayed(it, RECOVER_HOLD_MS) }
        val freesLeft = wasLeft && !coverLeft
        val freesRight = wasRight && !coverRight
        if (!freesLeft && !freesRight) return setCoverage(coverLeft, coverRight)
        // Apply any new covering now, keep a screen that might be freeing until it stays free.
        setCoverage(coverLeft || wasLeft, coverRight || wasRight)
        pendingUncover = Runnable { pendingUncover = null; setCoverage(coverLeft, coverRight) }.also { main.postDelayed(it, 340) }
    }

    /** Sets which screens are covered, remembering when each one was freed. */
    private fun setCoverage(left: Boolean, right: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (DuoScreenCoverage.left.value && !left) leftFreedAt = now
        if (DuoScreenCoverage.right.value && !right) rightFreedAt = now
        DuoScreenCoverage.set(left, right)
    }

    companion object {
        /** How long after a screen frees that it being covered again is double-checked. */
        private const val RECOVER_WINDOW_MS = 1000L
        /** How long that new covering must last before Home moves for it. */
        private const val RECOVER_HOLD_MS = 400L
        private var instance = WeakReference<SystemShadeAccessibilityService>(null)
        internal fun isConnected() = instance.get() != null
        internal fun pullShade(x: Float): Boolean = instance.get()?.pullShade(x) ?: false

        internal fun open(context: Context, panel: ShadePanel): ShadeOpenResult {
            val service = instance.get()
            if (service != null) {
                val action = when (panel) {
                    ShadePanel.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                    ShadePanel.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
                }
                return if (service.performGlobalAction(action)) ShadeOpenResult.OPENED
                else ShadeOpenResult.ACTION_REJECTED
            }
            return if (isEnabled(context)) ShadeOpenResult.SERVICE_STARTING
            else ShadeOpenResult.SERVICE_DISABLED
        }

        private fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, SystemShadeAccessibilityService::class.java)
            val manager = context.getSystemService(AccessibilityManager::class.java)
            return manager.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any {
                val service = it.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(service.packageName, service.name) == component
            }
        }
    }
}
