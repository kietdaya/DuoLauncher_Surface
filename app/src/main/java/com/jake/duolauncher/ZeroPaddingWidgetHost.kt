package com.jake.duolauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.SizeF
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.geometry.Offset

internal class ZeroPaddingWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(context: Context, appWidgetId: Int,
        appWidget: AppWidgetProviderInfo): AppWidgetHostView =
        ZeroPaddingWidgetHostView(context)
}

/**
 * The launcher's widget palette: one graphite-to-warm-white ramp in Android's 13 tonal steps
 * (0, 10, 50, 100 … 900, 1000). Widgets that colour themselves from Android's dynamic palette, such as
 * Google Keep, then use the dock's monochrome KO II look instead of the system's default purple and blue.
 */
private val WidgetTones = longArrayOf(
    0xFFFFFFFF, 0xFFFAF9F6, 0xFFF5F4F0, 0xFFF3F2EE, 0xFFECEBE6, 0xFFC9C8C3, 0xFFA3A29E,
    0xFF7F7E7A, 0xFF5C5C59, 0xFF2C2E33, 0xFF1C1D1F, 0xFF141518, 0xFF000000,
)

/** Every system accent and neutral colour, in [WidgetTones] order per ramp. */
private val SystemPaletteColors = intArrayOf(
    android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50, android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300, android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600, android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900, android.R.color.system_accent1_1000,
    android.R.color.system_accent2_0, android.R.color.system_accent2_10, android.R.color.system_accent2_50, android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_300, android.R.color.system_accent2_400, android.R.color.system_accent2_500, android.R.color.system_accent2_600, android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900, android.R.color.system_accent2_1000,
    android.R.color.system_accent3_0, android.R.color.system_accent3_10, android.R.color.system_accent3_50, android.R.color.system_accent3_100, android.R.color.system_accent3_200, android.R.color.system_accent3_300, android.R.color.system_accent3_400, android.R.color.system_accent3_500, android.R.color.system_accent3_600, android.R.color.system_accent3_700, android.R.color.system_accent3_800, android.R.color.system_accent3_900, android.R.color.system_accent3_1000,
    android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50, android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300, android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600, android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900, android.R.color.system_neutral1_1000,
    android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50, android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300, android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600, android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900, android.R.color.system_neutral2_1000,
)

private val WidgetColorResources = SparseIntArray(SystemPaletteColors.size).apply {
    SystemPaletteColors.forEachIndexed { index, id -> put(id, WidgetTones[index % WidgetTones.size].toInt()) }
}

/**
 * Hides or restores the provider's own background fill on a widget view from [ZeroPaddingWidgetHost], so a
 * launcher glass plate behind it shows through. Other views are left alone.
 */
internal fun setProviderBackgroundHidden(view: View, hidden: Boolean) {
    (view as? ZeroPaddingWidgetHostView)?.providerBackgroundHidden = hidden
}

private class ZeroPaddingWidgetHostView(context: Context) : AppWidgetHostView(context) {
    private var providerOwnsVerticalGesture = false

    init { setColorResources(WidgetColorResources) }

    /**
     * When true, the view the provider marks `@android:id/background` (Android 12's convention for a
     * widget's background) draws no fill. Providers without that ID keep their own background.
     */
    var providerBackgroundHidden = false
        set(value) {
            if (field == value) return
            field = value
            applyProviderBackground()
        }
    private var hiddenBackgroundView: View? = null
    private var hiddenBackground: Drawable? = null
    private val reapplyProviderBackground = Runnable { applyProviderBackground() }

    private fun applyProviderBackground() {
        val target: View? = findViewById(android.R.id.background)
        if (hiddenBackgroundView != null && (!providerBackgroundHidden || hiddenBackgroundView !== target)) {
            // Restore the fill we removed; a re-inflated layout brings its own and needs nothing.
            if (hiddenBackgroundView === target) target?.background = hiddenBackground
            hiddenBackgroundView = null
            hiddenBackground = null
        }
        if (!providerBackgroundHidden || target == null) return
        val current = target.background ?: return
        // The provider may set a new fill on every update; remember the latest one for restoring.
        hiddenBackgroundView = target
        hiddenBackground = current
        target.background = null
    }

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        super.updateAppWidget(remoteViews)
        // Updates re-run the provider's actions, which can set the fill again. Clear it now and once more
        // after any view work the update posts.
        applyProviderBackground()
        removeCallbacks(reapplyProviderBackground)
        post(reapplyProviderBackground)
    }
    private val publishCurrentSize = Runnable {
        if (!isAttachedToWindow || appWidgetId < 0 || width <= 0 || height <= 0) return@Runnable
        val density = resources.displayMetrics.density
        publishExactWidgetSize(this, width / density, height / density)
    }

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        super.setAppWidget(appWidgetId, info)
        setPadding(0, 0, 0, 0)
        scheduleSizePublication()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleSizePublication()
    }

    override fun onSizeChanged(widthPx: Int, heightPx: Int, oldWidthPx: Int, oldHeightPx: Int) {
        super.onSizeChanged(widthPx, heightPx, oldWidthPx, oldHeightPx)
        if (appWidgetId < 0 || widthPx <= 0 || heightPx <= 0) return
        // Avoid provider IPC inside a View/Compose layout callback. Coalesce size publication
        // until after the current layout pass finishes.
        scheduleSizePublication()
    }

    override fun onDetachedFromWindow() {
        if (providerOwnsVerticalGesture) parent?.requestDisallowInterceptTouchEvent(false)
        providerOwnsVerticalGesture = false
        removeCallbacks(publishCurrentSize)
        removeCallbacks(reapplyProviderBackground)
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Compose's Home column is vertically scrollable too. Give a provider's
                // ListView/ScrollView the uninterrupted native stream when DOWN hit that
                // content. The common pager observes Initial events before AndroidView, so
                // a horizontal swipe can still claim its axis and cancel this stream.
                providerOwnsVerticalGesture = nativeWidgetConsumesVerticalGesture(
                    this,
                    Offset(event.rawX, event.rawY),
                )
                if (providerOwnsVerticalGesture) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (providerOwnsVerticalGesture) parent?.requestDisallowInterceptTouchEvent(false)
                providerOwnsVerticalGesture = false
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun scheduleSizePublication() {
        removeCallbacks(publishCurrentSize)
        post(publishCurrentSize)
    }
}

internal fun exactWidgetSizeOptions(widthDp: Float, heightDp: Float) = Bundle().apply {
    val width = widthDp.coerceAtLeast(1f)
    val height = heightDp.coerceAtLeast(1f)
    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width.toInt())
    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width.toInt())
    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height.toInt())
    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height.toInt())
    putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width, height)))
}

private fun publishExactWidgetSize(view: AppWidgetHostView, widthDp: Float, heightDp: Float) {
    val options = exactWidgetSizeOptions(widthDp, heightDp)
    val current = AppWidgetManager.getInstance(view.context).getAppWidgetOptions(view.appWidgetId)
    val sameBounds = listOf(
        AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
        AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
        AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
        AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
    ).all { current.getInt(it) == options.getInt(it) }
    val currentSizes = current.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
    val nextSizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
    if (!sameBounds || currentSizes != nextSizes) view.updateAppWidgetOptions(options)
}
