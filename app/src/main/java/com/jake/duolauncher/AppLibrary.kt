@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

@Composable
internal fun AppLibrary(
    state: LauncherState, query: String, onQuery: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit, onPin: (String, Boolean) -> Unit, onActions: (AppEntry) -> Unit,
    modifier: Modifier = Modifier, editing: Boolean = false,
    drag: HomeDragState? = null, page: Int? = null,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit = { app, _ -> onLaunch(app) },
    onTurnOnWork: (Long) -> Unit = {},
) {
    val glass = !editing
    val palette = LocalDuoPalette.current
    val ink = if (glass) Ink else MaterialTheme.colorScheme.onSurface
    val pinned = remember(state.homeSlots, state.leadingSlots) {
        (state.homeSlots.asSequence() + state.leadingSlots.asSequence()).filterNotNull().toSet()
    }
    val hasWork = state.profiles.any { it.isWork } || state.apps.any { it.isWork }
    var showWork by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selectedProfile = if (showWork) state.profiles.firstOrNull { it.isWork } else state.profiles.firstOrNull { it.isPersonal }
    LaunchedEffect(showWork, selectedProfile?.available, selectedProfile?.quiet) {
        listState.scrollToItem(0)
    }
    val visibleApps = remember(state.apps, query, showWork, hasWork) {
        state.apps.filter { (!hasWork || it.isWork == showWork) && it.label.contains(query.trim(), true) }
    }
    val groups = remember(visibleApps) {
        visibleApps.groupBy {
            it.label.firstOrNull()?.takeIf(Char::isLetter)?.uppercaseChar()?.toString() ?: "#"
        }
    }
    Surface(modifier, shape = RoundedCornerShape(24.dp),
        color = if (glass) Glass.copy(alpha = .48f) else MaterialTheme.colorScheme.surface,
        contentColor = ink,
        border = if (glass) BorderStroke(1.dp, Color.White.copy(alpha = .38f)) else null) {
        Column(Modifier.background(Brush.verticalGradient(if (glass)
            listOf(Color.White.copy(alpha = .09f), Color.Transparent) else listOf(Color.Transparent, Color.Transparent)))
            .padding(horizontal = 16.dp).padding(top = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (editing) "Choose home apps" else "All apps", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                Text(if (editing) "${pinned.size} pinned" else "${visibleApps.size}", color = ink, fontSize = 12.sp)
            }
            if (hasWork) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showWork, onClick = { showWork = false }, label = { Text("Personal") })
                FilterChip(selected = showWork, onClick = { showWork = true }, label = { Text("Work") })
            }
            OutlinedTextField(query, onQuery, Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag(if (editing) "pin-search" else "library-search"),
                placeholder = { Text("Search apps") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear search") } },
                colors = if (glass) OutlinedTextFieldDefaults.colors(
                    focusedTextColor = ink, unfocusedTextColor = ink, cursorColor = ink,
                    focusedContainerColor = Color.White.copy(alpha = .18f), unfocusedContainerColor = Color.White.copy(alpha = .12f),
                    focusedBorderColor = Color.White.copy(alpha = .8f), unfocusedBorderColor = Color.White.copy(alpha = .45f),
                    focusedPlaceholderColor = ink, unfocusedPlaceholderColor = ink,
                    focusedLeadingIconColor = ink, unfocusedLeadingIconColor = ink,
                    focusedTrailingIconColor = ink, unfocusedTrailingIconColor = ink,
                ) else OutlinedTextFieldDefaults.colors())
            val letterRows = remember(groups, showWork, selectedProfile?.available) {
                // Index of each letter's heading in the LazyColumn, counting any status row above it.
                var row = if (showWork && selectedProfile?.available == false) 1 else 0
                if (groups.isEmpty()) row++
                buildMap { groups.forEach { (letter, entries) -> put(letter, row); row += 1 + entries.size } }
            }
            Row(Modifier.weight(1f)) {
            LazyColumn(Modifier.weight(1f).fadeTopEdge(14.dp).testTag("all-apps-list"), state = listState,
                contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
                if (showWork && selectedProfile?.available == false) item("work-paused") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (selectedProfile.quiet) "Work apps are paused" else "Work profile is unavailable")
                        if (selectedProfile.quiet) Button(onClick = { onTurnOnWork(selectedProfile.userSerial) },
                            Modifier.padding(top = 10.dp).testTag("turn-on-work")) { Text("Turn on work apps") }
                    }
                }
                if (groups.isEmpty()) item { Text(if (state.loading) "Loading apps…" else "No apps found", Modifier.padding(vertical = 20.dp)) }
                groups.forEach { (letter, entries) ->
                    // Letters scroll away with their apps, so nothing ever slides underneath them.
                    item(key = "heading-$letter") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(width = 32.dp, height = 28.dp).background(
                                if (glass) (if (palette.dark) Color(0xFF2C2D30) else Color(0xFFB7CBD3))
                                else MaterialTheme.colorScheme.surfaceContainer,
                                RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                Text(letter, color = ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                            if (glass) HorizontalDivider(Modifier.weight(1f).padding(start = 10.dp), color = Color.White.copy(alpha = .24f))
                        }
                    }
                    items(entries, key = { it.id }) { app ->
                        val isPinned = app.id in pinned
                        val launchBounds = remember { android.graphics.Rect() }
                        val dragModifier = if (drag != null) Modifier.dropRegion(drag, DropTarget.Library(app.id), app.id, page) else Modifier
                        val click = { if (editing) onPin(app.id, !isPinned) else onLaunchFrom(app, launchBounds) }
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).then(dragModifier).clip(RoundedCornerShape(14.dp)).testTag("library-app-${app.id}")
                            .then(if (drag == null) Modifier.combinedClickable(onClick = click, onLongClick = { onActions(app) })
                                else Modifier.clickable(onClick = click).semantics { onLongClick("App options") { onActions(app); true } })
                            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(app.icon.asImageBitmap(), null, Modifier.size(40.dp)
                                .onGloballyPositioned { launchBounds.set(it.boundsInWindow().toAndroidBounds()) }.clip(RoundedCornerShape(10.dp)))
                            Text(app.label, Modifier.weight(1f).padding(start = 12.dp), maxLines = 2, fontSize = 14.sp)
                            if (editing) IconButton(onClick = { onPin(app.id, !isPinned) }, Modifier.testTag("pin-${app.id}")) {
                                Icon(if (isPinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                                    if (isPinned) "Remove ${app.label} from home" else "Pin ${app.label} to home",
                                    tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            if (letterRows.size > 1) AlphabetIndex(letterRows.keys, ink, Modifier.fillMaxHeight().padding(start = 4.dp, bottom = 12.dp)) { letter ->
                scope.launch { listState.scrollToItem(letterRows.getValue(letter)) }
            }
            }
        }
    }
}

/** Fades content out over [height] at the top edge instead of cutting it off. */
private fun Modifier.fadeTopEdge(height: Dp) = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(Brush.verticalGradient(0f to Color.Transparent, height.toPx() / size.height.coerceAtLeast(1f) to Color.Black),
            blendMode = BlendMode.DstIn)
    }

/**
 * A–Z strip on the right of All apps. Tap a letter or slide along the strip to jump; each new
 * letter gives a light haptic tick. Letters with no apps are dimmed and jump to the next group.
 */
@Composable
private fun AlphabetIndex(available: Set<String>, ink: Color, modifier: Modifier, onLetter: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var active by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(modifier.width(18.dp).testTag("all-apps-index")
        .semantics { contentDescription = "Alphabet index" }) {
        val rowHeight = minOf(16.dp, maxHeight / IndexLetters.size)
        val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
        val topPx = with(LocalDensity.current) { ((maxHeight - rowHeight * IndexLetters.size) / 2).toPx() }
        fun pick(y: Float) {
            val raw = IndexLetters[((y - topPx) / rowHeightPx).toInt().coerceIn(0, IndexLetters.lastIndex)]
            val letter = nearestIndexLetter(raw, available) ?: return
            if (letter != active) {
                active = letter
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onLetter(letter)
            }
        }
        Column(Modifier.fillMaxSize()
            .pointerInput(available, rowHeightPx, topPx) { detectTapGestures(onPress = { pick(it.y); tryAwaitRelease(); active = null }) }
            .pointerInput(available, rowHeightPx, topPx) {
                detectVerticalDragGestures(onDragStart = { pick(it.y) }, onDragEnd = { active = null }, onDragCancel = { active = null }) { change, _ ->
                    change.consume(); pick(change.position.y)
                }
            },
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            IndexLetters.forEach { letter ->
                val has = letter in available
                Text(letter, Modifier.height(rowHeight), color = ink.copy(alpha = if (letter == active) 1f else if (has) .85f else .35f),
                    fontSize = 10.sp, lineHeight = 10.sp,
                    fontWeight = if (letter == active) FontWeight.Bold else FontWeight.SemiBold)
            }
        }
    }
}
