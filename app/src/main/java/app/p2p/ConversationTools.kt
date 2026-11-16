package app.p2p

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class ThreadAction(val label: String) {
    ARCHIVE("Archive"), RESTORE("Restore"), DELETE("Delete"), BLOCK("Block"), UNBLOCK("Unblock")
}

internal fun threadSearchMatches(query: String, title: String, values: Iterable<String>): Boolean {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return terms.isEmpty() || terms.all { term ->
        title.contains(term, ignoreCase = true) || values.any { it.contains(term, ignoreCase = true) }
    }
}

internal fun Record.searchText(): String = data().let { value ->
    listOf("text", "name", "mime").map(value::optString).filter(String::isNotBlank).joinToString(" ")
}

internal fun inboxTimestamp(created: Long, now: Long): String {
    val format = if (now - created < 86_400_000L) DateFormat.getTimeInstance(DateFormat.SHORT)
        else SimpleDateFormat("MMM d", Locale.getDefault())
    return format.format(Date(created))
}

@Composable
internal fun SwipeConversation(
    archiveAction: ThreadAction,
    blocked: Boolean,
    inTrash: Boolean,
    onAction: (ThreadAction) -> Unit,
    content: @Composable (closeActions: () -> Boolean) -> Unit
) {
    val actions = listOfNotNull(archiveAction, if (blocked) ThreadAction.UNBLOCK else ThreadAction.BLOCK,
        ThreadAction.DELETE.takeUnless { inTrash })
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val widths = actions.map {
        val textWidth = measurer.measure(it.label, style, maxLines = 1, softWrap = false,
            constraints = Constraints()).size.width
        with(density) { maxOf(48.dp, textWidth.toDp() + 20.dp) }
    }
    val actionWidth = widths.reduce { total, width -> total + width }
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        val available = (maxWidth - 40.dp).coerceAtLeast(0.dp)
        val stacked = actionWidth > available
        val revealWidth = if (stacked) widths.max().coerceAtMost(available) else actionWidth
        val reveal = with(density) { revealWidth.toPx() }
        var offset by remember(reveal) { mutableFloatStateOf(0f) }
        var dragging by remember(reveal) { mutableStateOf(false) }
        val animated by animateFloatAsState(offset, animationSpec = tween(240), label = "Conversation actions")
        val position = if (dragging) offset else animated
        val closeActions = {
            val wasOpen = offset != 0f
            offset = 0f
            wasOpen
        }
        Box(Modifier.fillMaxWidth()
            .pointerInput(reveal) {
                var origin = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        origin = offset
                        dragging = true
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        // A closing swipe stays on its original side until the next gesture.
                        offset = (offset + delta).coerceIn(
                            if (origin > 0f) 0f else -reveal,
                            if (origin < 0f) 0f else reveal
                        )
                    },
                    onDragEnd = {
                        offset = when {
                            origin != 0f -> if (abs(origin - offset) >= reveal * 0.25f) 0f else origin
                            offset >= reveal * 0.30f -> reveal
                            offset <= -reveal * 0.30f -> -reveal
                            else -> 0f
                        }
                        dragging = false
                    },
                    onDragCancel = { offset = origin; dragging = false }
                )
            }) {
            if (position != 0f) {
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(18.dp)), contentAlignment =
                    if (position > 0f) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight) {
                    val buttons: @Composable () -> Unit = {
                        actions.forEachIndexed { index, action ->
                            TextButton(
                                onClick = { offset = 0f; onAction(action) },
                                enabled = !dragging && offset != 0f,
                                modifier = Modifier.width(if (stacked) revealWidth else widths[index]),
                                contentPadding = PaddingValues(horizontal = 0.dp)
                            ) {
                                Text(action.label, maxLines = 1, softWrap = false,
                                    color = if (action == ThreadAction.DELETE) MaterialTheme.colorScheme.error else LocalContentColor.current)
                            }
                        }
                    }
                    if (stacked) Column { buttons() } else Row { buttons() }
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = if (stacked) (48 * actions.size).dp else 0.dp)
                .absoluteOffset { IntOffset(position.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                content(closeActions)
            }
        }
    }
}
