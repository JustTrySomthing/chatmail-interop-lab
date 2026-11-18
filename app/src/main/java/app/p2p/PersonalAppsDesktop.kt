package app.p2p

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class DesktopDrag(val id: String, val folder: String?, val start: Offset, val tile: Rect)

internal typealias DesktopEdit = (PersonalDesktopLayout) -> PersonalDesktopLayout

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PersonalAppsDesktop(
    apps: List<Record>, saved: JSONObject?, localFile: (Record) -> File?,
    open: (Record) -> Unit, edit: (DesktopEdit) -> Unit, hide: (String, Boolean) -> Unit,
    add: () -> Unit, loading: Boolean, error: String?, retry: () -> Unit, deleteApp: (Record) -> Unit = {}
) {
    val layout = remember(apps, saved) { personalDesktopLayout(apps, saved) }
    val shown = remember(apps) { apps.filterNot { it.data().optBoolean("desktopHidden") }.associateBy(Record::key) }
    val folders = layout.folders.associateBy(DesktopFolder::id)
    val hidden = apps.filter { it.data().optBoolean("desktopHidden") }
    val iconCache = remember { LruCache<String, Bitmap>(24) }
    var options by rememberSaveable { mutableStateOf<String?>(null) }
    var manage by rememberSaveable { mutableStateOf(false) }
    var folderOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingApp by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var drag by remember { mutableStateOf<DesktopDrag?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var moved by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf<String?>(null) }
    var extraPage by remember { mutableStateOf(false) }
    var pendingPage by remember { mutableStateOf<Int?>(null) }
    val pager = rememberPagerState { layout.pages.size + if (extraPage) 1 else 0 }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val activeFolder = folders[folderOpen]
    val members = activeFolder?.members.orEmpty().filter(shown::containsKey)
    val folderPager = rememberPagerState { ((members.size + 8) / 9).coerceAtLeast(1) }

    fun newFolderId() = "desktop-folder:${UUID.randomUUID()}"
    fun nextPage(id: String, page: Int, index: Int) {
        pendingPage = page
        edit { it.move(id, page, index) }
    }
    fun endDrag(cancel: Boolean = false) {
        if (cancel && drag?.folder == null && armed == folderOpen) folderOpen = null
        drag = null
        armed = null
        moved = false
        extraPage = false
    }

    LaunchedEffect(layout.pages.size, pendingPage) {
        val target = pendingPage
        if (target != null && target in layout.pages.indices) {
            pager.scrollToPage(target)
            pendingPage = null
        } else if (pager.currentPage >= pager.pageCount) pager.scrollToPage(pager.pageCount - 1)
    }
    LaunchedEffect(activeFolder?.id) {
        folderPager.scrollToPage(0)
        val page = activeFolder?.let { layout.pageOf(it.id) }
        if (drag == null && page != null && page >= 0 && page != pager.currentPage) {
            pager.scrollToPage(page)
        }
    }
    BackHandler(drag != null || activeFolder != null) {
        if (drag != null) endDrag(cancel = true) else folderOpen = null
    }

    val foreground = MaterialTheme.colorScheme.onBackground
    Column(Modifier.fillMaxSize()) {
        if (hidden.isNotEmpty() || shown.isNotEmpty() || folders.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { naming = "" }, enabled = drag == null && !loading) {
                    Icon(Icons.Outlined.CreateNewFolder, "New folder", tint = foreground)
                }
                if (hidden.isNotEmpty()) IconButton(onClick = { manage = true }, enabled = drag == null) {
                    Icon(Icons.Outlined.VisibilityOff, "Manage hidden apps", tint = foreground)
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = retry) { Text("Retry") }
        }
        if (!loading && shown.isEmpty() && folders.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                Text("No apps yet", color = foreground, style = MaterialTheme.typography.headlineSmall)
                Button(onClick = add, modifier = Modifier.padding(top = 12.dp)) { Text("Add apps") }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val workspaceHeight = maxHeight
            val cellHeight = maxOf(workspaceHeight / DesktopRows, 72.dp + (32f * density.fontScale).dp)
            val cellWidth = maxWidth / DesktopColumns
            val cellPx = with(density) { Size(cellWidth.toPx(), cellHeight.toPx()) }
            val viewport = Rect(Offset.Zero, with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) })
            val edgeWidth = with(density) { 56.dp.toPx() }.coerceAtMost(viewport.width / 7)
            var origin by remember { mutableStateOf(Offset.Zero) }
            var folderBounds by remember { mutableStateOf(Rect.Zero) }
            val memberBounds = remember(activeFolder?.id) { mutableMapOf<String, Rect>() }

            fun slot(point: Offset): Int {
                val column = (point.x / cellPx.width).toInt().coerceIn(0, DesktopColumns - 1)
                val row = ((point.y + scroll.value) / cellPx.height).toInt().coerceIn(0, DesktopRows - 1)
                return row * DesktopColumns + column
            }
            fun cell(index: Int) = Rect(Offset(
                index % DesktopColumns * cellPx.width,
                index / DesktopColumns * cellPx.height - scroll.value
            ), cellPx)
            fun tileAt(point: Offset) = layout.pages.getOrNull(pager.currentPage)?.getOrNull(slot(point))?.takeIf { it.isNotEmpty() }

            val start by rememberUpdatedState<(Offset) -> Boolean>(start@{ point ->
                if (!pager.isScrollInProgress && viewport.contains(point)) {
                    val member = if (activeFolder != null && folderBounds.contains(point)) {
                        memberBounds.entries.firstOrNull { (id, bounds) ->
                            id in members.drop(folderPager.currentPage * 9).take(9) && bounds.contains(point)
                        }
                    } else null
                    val id = if (activeFolder == null) tileAt(point) else member?.key
                    if (id != null) {
                        finger = point
                        drag = DesktopDrag(id, activeFolder?.id, point, member?.value ?: cell(slot(point)))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        return@start true
                    }
                }
                false
            })
            val motion by rememberUpdatedState<(Offset, Float) -> Unit>({ delta, slop ->
                drag?.let { current ->
                    finger += delta
                    if ((finger - current.start).getDistance() > slop) moved = true
                }
            })
            val finish by rememberUpdatedState<() -> Unit>({
                drag?.let { current ->
                    val id = current.id
                    val target = armed
                    val page = pager.currentPage
                    if (!moved) {
                        options = id
                    } else if (finger.y in 0f..viewport.height) {
                        when {
                            current.folder != null && folderBounds.contains(finger) -> Unit
                            current.folder != null -> {
                                nextPage(id, page, slot(finger))
                                folderOpen = null
                            }
                            target in folders && (folderBounds.contains(finger) || tileAt(finger) == target) -> {
                                edit { it.group(id, requireNotNull(target)) }
                                folderOpen = target
                            }
                            target != null && tileAt(finger) == target -> {
                                val folderId = newFolderId()
                                edit { it.group(id, target, folderId) }
                                folderOpen = folderId
                            }
                            else -> {
                                nextPage(id, page, slot(finger))
                                if (target == folderOpen) folderOpen = null
                            }
                        }
                    } else if (current.folder == null && target == folderOpen) {
                        folderOpen = null
                    }
                }
                endDrag()
            })
            val abort by rememberUpdatedState<() -> Unit>({ if (drag != null) endDrag(cancel = true) })
            val inFolder = activeFolder != null && folderBounds.contains(finger)
            val edge = when {
                !moved || finger.y !in 0f..viewport.height || inFolder -> 0
                finger.x < edgeWidth -> -1
                finger.x > viewport.width - edgeWidth -> 1
                else -> 0
            }
            val hover = tileAt(finger).takeIf {
                moved && edge == 0 && viewport.contains(finger) && drag?.folder == null &&
                    drag?.id in shown && it != drag?.id && (activeFolder == null || activeFolder.id == it)
            }
            LaunchedEffect(drag?.id, hover, inFolder) {
                if (activeFolder != null && drag?.folder == null && armed == activeFolder.id && (inFolder || hover == armed)) {
                    return@LaunchedEffect
                }
                if (activeFolder != null && drag?.folder == null && armed == activeFolder.id) folderOpen = null
                armed = null
                if (hover != null) {
                    delay(450)
                    armed = hover
                    if (hover in folders) folderOpen = hover
                }
            }
            LaunchedEffect(drag?.id, edge, pager.settledPage) {
                if (edge == 0) return@LaunchedEffect
                delay(250)
                val next = pager.currentPage + edge
                if (next in 0..layout.pages.size) {
                    if (next == layout.pages.size) {
                        if (extraPage) return@LaunchedEffect
                        extraPage = true
                        withFrameNanos { }
                    }
                    pager.animateScrollToPage(next, animationSpec = tween(220))
                }
            }
            val verticalEdge = when {
                !moved || inFolder || !viewport.contains(finger) -> 0
                finger.y < edgeWidth -> -1
                finger.y > viewport.height - edgeWidth -> 1
                else -> 0
            }
            LaunchedEffect(drag?.id, verticalEdge) {
                if (verticalEdge == 0) return@LaunchedEffect
                while (drag != null) {
                    val next = (scroll.value + verticalEdge * 6).coerceIn(0, scroll.maxValue)
                    if (next == scroll.value) break
                    scroll.scrollTo(next)
                    delay(16)
                }
            }

            Box(Modifier.fillMaxSize().testTag("desktop-viewport").clipToBounds().onGloballyPositioned { origin = it.positionInRoot() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val hold = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        if (hold.id != down.id || !start(hold.position)) return@awaitEachGesture
                        try {
                            while (true) {
                                // Own the release before the tile's ordinary tap handler sees it.
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == hold.id } ?: break
                                if (change.isConsumed || event.changes.any { it.id != hold.id && it.pressed }) break
                                change.consume()
                                motion(change.position - change.previousPosition, viewConfiguration.touchSlop)
                                if (!change.pressed) {
                                    finish()
                                    break
                                }
                            }
                        } finally {
                            abort()
                        }
                    }
                }) {
                Box(Modifier.fillMaxSize().verticalScroll(scroll, enabled = drag == null && activeFolder == null)
                    .then(if (activeFolder != null) Modifier.clearAndSetSemantics {} else Modifier)) {
                    HorizontalPager(pager, Modifier.fillMaxWidth().height(cellHeight * DesktopRows),
                        userScrollEnabled = drag == null && activeFolder == null) { page ->
                        val items = layout.pages.getOrNull(page).orEmpty()
                        Column {
                            repeat(DesktopRows) { row ->
                                Row(Modifier.fillMaxWidth()) {
                                    repeat(DesktopColumns) { column ->
                                        val id = items.getOrNull(row * DesktopColumns + column)
                                        Box(Modifier.weight(1f).height(cellHeight)) {
                                            if (!id.isNullOrEmpty()) key(id) {
                                                val folder = folders[id]
                                                DesktopTile(shown[id], folder, localFile, iconCache,
                                                    Modifier.fillMaxSize().testTag("desktop-app-$id")
                                                        .alpha(if (moved && drag?.id == id) 0f else 1f)
                                                        .then(if (armed == id) Modifier.background(MaterialTheme.colorScheme.primaryContainer, P2pDialogShape) else Modifier),
                                                    click = { if (folder != null) folderOpen = id else shown[id]?.let(open) },
                                                    longClick = { options = id })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (activeFolder != null) {
                    Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .55f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClickLabel = "Close folder") { folderOpen = null })
                    Surface(
                        Modifier.align(Alignment.Center).widthIn(max = 392.dp).fillMaxWidth(.92f)
                            .onGloballyPositioned { folderBounds = it.boundsInRoot().translate(-origin) }
                            .testTag("desktop-folder-dialog"),
                        color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface, shape = P2pDialogShape, shadowElevation = 12.dp
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(activeFolder.name, Modifier.weight(1f).padding(start = 8.dp),
                                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { naming = activeFolder.id }, enabled = drag == null) {
                                    Icon(Icons.Outlined.Edit, "Rename folder")
                                }
                                IconButton(onClick = { folderOpen = null }, enabled = drag == null) {
                                    Icon(Icons.Outlined.Close, "Close folder")
                                }
                            }
                            if (members.isEmpty()) {
                                Text("Drag apps onto this folder to add them.", Modifier.padding(8.dp))
                            } else {
                                val rows = ((members.size + 2) / 3).coerceAtMost(3)
                                val folderHeight = (workspaceHeight - 120.dp).coerceAtLeast(52.dp).coerceAtMost(cellHeight * rows)
                                HorizontalPager(folderPager, Modifier.fillMaxWidth().height(folderHeight),
                                    userScrollEnabled = drag == null, verticalAlignment = Alignment.Top) { page ->
                                    Column(Modifier.verticalScroll(rememberScrollState(), enabled = drag == null)) {
                                        members.drop(page * 9).take(9).chunked(3).forEach { row ->
                                            Row(Modifier.fillMaxWidth()) {
                                                row.forEach { id ->
                                                    DesktopTile(shown[id], null, localFile, iconCache,
                                                        Modifier.weight(1f).height(cellHeight).testTag("desktop-member-$id")
                                                            .alpha(if (moved && drag?.id == id) 0f else 1f)
                                                            .onGloballyPositioned {
                                                                memberBounds[id] = it.boundsInRoot().translate(-origin)
                                                            },
                                                        click = { shown[id]?.let(open) }, longClick = { options = id })
                                                }
                                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                            }
                                        }
                                    }
                                }
                                if (folderPager.pageCount > 1) {
                                    PageDots(folderPager.currentPage, folderPager.pageCount, drag == null, "folder page") {
                                        scope.launch { folderPager.animateScrollToPage(it) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (drag != null && edge != 0 && (edge > 0 || pager.currentPage > 0)) {
                    Box(Modifier.align(if (edge < 0) Alignment.CenterStart else Alignment.CenterEnd)
                        .width(with(density) { edgeWidth.toDp() }).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .18f))
                        .testTag("desktop-edge-highlight"), contentAlignment = Alignment.Center) {
                        Icon(if (edge < 0) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight,
                            "Move to adjacent page", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(32.dp))
                    }
                }
                drag?.takeIf { moved }?.let { current ->
                    val topLeft = current.tile.topLeft + finger - current.start
                    DesktopTile(shown[current.id], folders[current.id], localFile, iconCache,
                        Modifier.zIndex(1f).offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                            .size(with(density) { current.tile.width.toDp() }, cellHeight)
                            .clearAndSetSemantics {})
                }
            }
        }
        PageDots(pager.currentPage, pager.pageCount, drag == null && activeFolder == null, "page") {
            scope.launch { pager.animateScrollToPage(it) }
        }
    }

    options?.let { id ->
        val app = shown[id]
        val folder = folders[id]
        val parent = layout.folders.firstOrNull { id in it.members }
        if (app != null || folder != null) {
            P2pAlertDialog(onDismissRequest = { options = null }, title = { Text(folder?.name ?: app!!.desktopName()) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (folder != null) {
                            DesktopAction("Rename folder", Icons.Outlined.Edit) { options = null; naming = id }
                            DesktopAction("Delete folder", Icons.Outlined.FolderDelete) { options = null; deleting = id }
                        }
                        if (parent != null) DesktopAction("Remove from folder", Icons.Outlined.FolderOpen) {
                            edit { current ->
                                val page = current.pageOf(parent.id).coerceAtLeast(0)
                                val cells = current.pages[page]
                                current.move(id, page, cells.indexOf("").takeIf { it >= 0 } ?: cells.size)
                            }
                            options = null
                        }
                        if (app != null) {
                            DesktopAction("Remove icon", Icons.Outlined.HideSource, "Keep app and saved data") { hide(id, true); options = null }
                            DesktopAction("Delete app and data", Icons.Outlined.DeleteOutline, "Delete this personal copy", destructive = true) { deletingApp = id; options = null }
                        }
                    }
                }, confirmButton = { P2pTextButton(onClick = { options = null }) { Text("Done", color = MaterialTheme.colorScheme.onSurface) } })
        }
    }
    deletingApp?.let { id ->
        apps.firstOrNull { it.key == id }?.let { app ->
            P2pAlertDialog(onDismissRequest = { deletingApp = null }, title = { Text("Delete app and data?") },
                text = { Text("Delete ${app.desktopName()} and its saved data? This cannot be undone. Other installed copies and apps in chats will remain.") },
                confirmButton = { P2pTextButton(onClick = { deletingApp = null; deleteApp(app) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { P2pTextButton(onClick = { deletingApp = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurface) } })
        }
    }
    naming?.let { id ->
        val folder = folders[id]
        if (id.isEmpty() || folder != null) {
            var name by rememberSaveable(id) { mutableStateOf(folder?.name.orEmpty()) }
            P2pAlertDialog(onDismissRequest = { naming = null },
                title = { Text(if (id.isEmpty()) "New folder" else "Rename folder") },
                text = { OutlinedTextField(name, { name = it.take(40) }, label = { Text("Folder name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {
                    P2pTextButton(enabled = validDesktopFolderName(name), onClick = {
                        val value = name.trim()
                        if (id.isEmpty()) {
                            val created = DesktopFolder(newFolderId(), value)
                            val page = pager.currentPage
                            edit { current -> current.addFolder(created, page, current.pages.getOrNull(page).orEmpty().let { cells -> cells.indexOf("").takeIf { it >= 0 } ?: cells.size }) }
                            folderOpen = created.id
                        } else edit { it.rename(id, value) }
                        naming = null
                    }) { Text(if (id.isEmpty()) "Create" else "Save", color = MaterialTheme.colorScheme.onSurface) }
                },
                dismissButton = { P2pTextButton(onClick = { naming = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurface) } })
        }
    }
    deleting?.let { id ->
        P2pAlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete folder?") },
            text = { Text("The apps in this folder will move back to the Apps pages.") },
            confirmButton = {
                P2pTextButton(onClick = { edit { it.dissolve(id) }; deleting = null; folderOpen = null }) { Text("Delete folder", color = MaterialTheme.colorScheme.error) }
            }, dismissButton = { P2pTextButton(onClick = { deleting = null }) { Text("Cancel", color = MaterialTheme.colorScheme.onSurface) } })
    }
    if (manage) P2pAlertDialog(onDismissRequest = { manage = false }, title = { Text("Hidden app icons") },
        text = {
            Column {
                if (hidden.isEmpty()) Text("No hidden icons.")
                hidden.forEach { app -> DesktopAction("Restore ${app.desktopName()}") { hide(app.key, false) } }
            }
        }, confirmButton = { P2pTextButton(onClick = { manage = false }) { Text("Done", color = MaterialTheme.colorScheme.onSurface) } })
}

@Composable
private fun DesktopAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Outlined.Restore,
    subtitle: String? = null, destructive: Boolean = false, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = action, modifier = Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = colors.surfaceContainerHigh, contentColor = colors.onSurface) {
        Row(Modifier.heightIn(min = 64.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, Modifier.size(26.dp), tint = if (destructive) colors.error else readableAccent(colors.surfaceContainerHigh, colors.onSurface))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun PageDots(page: Int, count: Int, enabled: Boolean, label: String, select: (Int) -> Unit) {
    val ink = readableAccent(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onBackground)
    Box(Modifier.fillMaxWidth().height(48.dp).semantics {
        contentDescription = "${label.replaceFirstChar(Char::uppercase)} ${page + 1} of $count"
    }, contentAlignment = Alignment.Center) {
        if (page > 0) IconButton(onClick = { select(page - 1) }, enabled = enabled, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(Icons.Outlined.ChevronLeft, "Previous $label", tint = ink)
        }
        if (page + 1 < count) IconButton(onClick = { select(page + 1) }, enabled = enabled, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(Icons.Outlined.ChevronRight, "Next $label", tint = ink)
        }
        val limit = minOf(count, 9)
        val first = (page - limit / 2).coerceIn(0, count - limit)
        Row {
            repeat(limit) { index ->
                val target = first + index
                Box(Modifier.size(24.dp, 48.dp).clickable(enabled = enabled && target != page, role = Role.Button) { select(target) }
                    .semantics { contentDescription = "Go to $label ${target + 1}"; selected = target == page },
                    contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(if (target == page) 8.dp else 5.dp)) {
                        drawCircle(ink, alpha = if (target == page) 1f else .42f)
                    }
                }
            }
        }
    }
}

private fun Record.desktopName() = data().optString("name").substringBeforeLast('.').ifBlank { "App" }

@Composable
private fun DesktopTile(
    app: Record?, folder: DesktopFolder?, localFile: (Record) -> File?,
    iconCache: LruCache<String, Bitmap>, modifier: Modifier,
    click: (() -> Unit)? = null, longClick: (() -> Unit)? = null
) {
    val title = folder?.name ?: app?.desktopName() ?: return
    Column(modifier.then(if (click == null) Modifier else Modifier
        .clickable(role = Role.Button, interactionSource = remember { MutableInteractionSource() },
            indication = null, onClick = click)
        .semantics { onLongClick(label = "Options for $title") { longClick?.invoke(); true } })
        .padding(horizontal = 5.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (folder != null) {
            Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                val tint = readableAccent(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onBackground)
                Icon(Icons.Filled.Folder, null, Modifier.fillMaxSize(), tint = tint)
                val initial = folder.name.substring(0, folder.name.offsetByCodePoints(0, 1)).uppercase()
                Text(initial, Modifier.padding(top = 4.dp), color = inkOnAccent(tint), style = MaterialTheme.typography.labelLarge)
            }
        } else if (app != null) PersonalAppIcon(app, localFile, iconCache)
        Text(title, Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PersonalAppIcon(item: Record, localFile: (Record) -> File?, cache: LruCache<String, Bitmap>) {
    val file = localFile(item)
    val path = file?.path
    val bitmap by produceState(path?.let(cache::get), path) {
        value = path?.let(cache::get)
        if (file != null && value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    openWebxdcAsset(file, "icon.png").use { input ->
                        val bytes = input.readBytesLimited(2 * 1024 * 1024)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 128).coerceAtLeast(1)
                        }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    }
                }.getOrNull()
            }?.also { cache.put(file.path, it) }
        }
    }
    val icon = bitmap
    if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(54.dp))
    else Icon(Icons.Outlined.GridView, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onBackground)
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    copyBoundedTo(out, limit.toLong(), "Icon too large")
    return out.toByteArray()
}
