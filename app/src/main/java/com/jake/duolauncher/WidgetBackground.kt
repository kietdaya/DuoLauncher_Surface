package com.jake.duolauncher

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/** A launcher-drawn glass plate behind one native widget: an ARGB [color] at [opacity] (0–1). */
data class WidgetBackground(val color: Int, val opacity: Float) {
    init { require(opacity in 0f..1f) }
}

/** Plate colours offered in Widget options. Graphite and the orange match the dark KO II palette. */
val WidgetBackgroundSwatches: List<Pair<String, Int>> = listOf(
    "Graphite" to 0xFF1C1E22.toInt(),
    "Warm white" to 0xFFECE8DF.toInt(),
    "Slate" to 0xFF4A5866.toInt(),
    "Olive" to 0xFF5B6145.toInt(),
    "Signal orange" to 0xFFFF5A1F.toInt(),
)

/** Opacity a plate starts at when first turned on: glass, so the wallpaper still shows through. */
const val DEFAULT_WIDGET_BACKGROUND_OPACITY = .4f

/** Encodes a plate as "color,opacity" for preferences; [decodeWidgetBackground] reverses it. */
internal fun encodeWidgetBackground(value: WidgetBackground) = "${value.color},${value.opacity}"

internal fun decodeWidgetBackground(value: String?): WidgetBackground? = runCatching {
    val parts = value?.split(',') ?: return null
    if (parts.size != 2) null
    else WidgetBackground(parts[0].toInt(), parts[1].toFloat().coerceIn(0f, 1f))
}.getOrNull()

/**
 * Per-widget glass plates, keyed by Android widget ID. Kept apart from the saved Home layout, so a widget
 * with no plate looks exactly as before and layout backups are unchanged. A plate follows its widget
 * when it moves or resizes; replacing the widget gives the new one no plate.
 */
class WidgetBackgroundStore(context: Context) {
    private val prefs = context.getSharedPreferences("widget_backgrounds", 0)
    private val values = mutableStateMapOf<Int, WidgetBackground>()

    init {
        prefs.all.forEach { (key, raw) ->
            val id = key.toIntOrNull() ?: return@forEach
            decodeWidgetBackground(raw as? String)?.let { values[id] = it }
        }
    }

    /** The plate for widget [id], or null for none. Reading it from composition observes changes. */
    operator fun get(id: Int): WidgetBackground? = values[id]

    /** Sets or, with null, removes the plate for native widget [id]. Built-in cards (negative IDs) are ignored. */
    fun set(id: Int, value: WidgetBackground?) {
        if (id < 0) return
        if (value == null) {
            values.remove(id)
            prefs.edit().remove(id.toString()).apply()
        } else {
            values[id] = value
            prefs.edit().putString(id.toString(), encodeWidgetBackground(value)).apply()
        }
    }

    /** Forgets plates for widget IDs no longer on Home, so a reused ID never inherits an old plate. */
    fun retainOnly(ids: Set<Int>) {
        val stale = values.keys.filter { it !in ids }
        if (stale.isEmpty()) return
        stale.forEach(values::remove)
        prefs.edit().apply { stale.forEach { remove(it.toString()) } }.apply()
    }
}
