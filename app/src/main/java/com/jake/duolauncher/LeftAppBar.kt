package com.jake.duolauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The dock as a row along the bottom of the Duo's left screen, with an All apps key, for when an app
 * covers the right screen and its dock. Swipe it down to put it away.
 */
@Composable
internal fun LeftAppBar(apps: List<AppEntry>, onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onAllApps: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalDuoPalette.current
    val shape = RoundedCornerShape(30.dp)
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp).fillMaxWidth().clip(shape)
        .background(palette.dockFill ?: palette.glass.copy(alpha = .6f))
        .border(1.dp, if (palette.dockFill != null) palette.dockEdge else Color.White.copy(alpha = .35f), shape)
        .pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(onDragStart = { total = 0f },
                onDragEnd = { if (total > 32.dp.toPx()) onDismiss() }) { _, dy -> total += dy }
        }
        .padding(horizontal = 12.dp, vertical = 12.dp).testTag("left-app-bar"),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        apps.take(4).forEach { app ->
            val bounds = remember(app.id) { android.graphics.Rect() }
            Image(app.icon.asImageBitmap(), app.label, Modifier.size(52.dp)
                .onGloballyPositioned { bounds.set(it.boundsInWindow().toAndroidBounds()) }
                .clip(RoundedCornerShape(14.dp)).clickable { onLaunch(app, bounds) })
        }
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f))
            .clickable(onClick = onAllApps).testTag("left-app-bar-all-apps"), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, "All apps", tint = palette.ink)
        }
    }
}
