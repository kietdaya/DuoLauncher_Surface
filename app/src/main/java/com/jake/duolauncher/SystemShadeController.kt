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
    override fun onDestroy() {
        if (instance.get() === this) instance.clear()
        DuoScreenCoverage.set(left = false, right = false)
        super.onDestroy()
    }

    /**
     * Works out which Surface Duo screens other apps' windows cover, from window positions only.
     * Runs in the launcher's process, so it must never throw.
     */
    private fun updateScreenCoverage() {
        try {
            val hinge = CurrentDuoHinge.value ?: return DuoScreenCoverage.set(left = false, right = false)
            var left = false
            var right = false
            val bounds = android.graphics.Rect()
            for (window in windows) {
                if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root
                val owner = root?.packageName?.toString()
                if (owner == null || owner == packageName) continue
                window.getBoundsInScreen(bounds)
                if (bounds.width() <= 0 || bounds.height() <= 0) continue
                val coversLeft = bounds.left < hinge.left && bounds.right > hinge.left / 2
                val coversRight = bounds.right > hinge.right && bounds.left < hinge.right + (bounds.right - hinge.right) / 2
                if (coversLeft) left = true
                if (coversRight) right = true
            }
            DuoScreenCoverage.set(left, right)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private var instance = WeakReference<SystemShadeAccessibilityService>(null)
        internal fun isConnected() = instance.get() != null

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
