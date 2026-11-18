package app.p2p

import android.graphics.BitmapFactory
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun Record.isSavedItem() = data().let {
    it.optBoolean("nativeSelfTalk") || it.optBoolean("star") || it.optInt("nativeSavedId") > 9 || it.optInt("savedCopyId") > 9
}

internal fun personalApps(records: List<Record>, thread: String?): List<Record> =
    records.webxdcMessages(thread).filter { it.data().optInt("nativeOriginalId") <= 9 }

internal fun orderedPersonalApps(apps: List<Record>, order: List<String>): List<Record> {
    val byId = apps.associateBy { it.key }
    return order.distinct().mapNotNull(byId::get) + apps.filter { it.key !in order }.sortedBy { it.created }
}

internal fun movedApp(order: List<String>, key: String, destination: Int): List<String> {
    val result = order.distinct().toMutableList()
    if (!result.remove(key)) return result
    result.add(destination.coerceIn(0, result.size), key)
    return result
}

internal fun personalAppPages(apps: List<Record>): List<List<Record>> {
    val pages = mutableListOf<MutableList<Record>>(mutableListOf())
    for (app in apps) {
        var page = app.data().optInt("desktopPage", 0).coerceIn(0, apps.size)
        while (true) {
            while (pages.size <= page) pages.add(mutableListOf())
            if (pages[page].size < 12) { pages[page].add(app); break }
            page++
        }
    }
    return pages
}

@Composable
private fun PersonalAppIcon(item: Record, localFile: (Record) -> File?) {
    val file = localFile(item)
    val bitmap by produceState<android.graphics.Bitmap?>(null, file?.path) {
        value = withContext(Dispatchers.IO) { runCatching {
            file?.let { openWebxdcAsset(it, "icon.png").use { input ->
                val bytes = input.readBytesLimited(2 * 1024 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 128).coerceAtLeast(1) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            } }
        }.getOrNull() }
    }
    val icon = bitmap
    if (icon != null) Image(icon.asImageBitmap(), null, Modifier.size(54.dp))
    else Icon(Icons.Outlined.GridView, null, Modifier.size(48.dp))
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    copyBoundedTo(out, limit.toLong(), "Icon too large")
    return out.toByteArray()
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PersonalAppsDesktop(
    apps: List<Record>, order: List<String>, localFile: (Record) -> File?,
    open: (Record) -> Unit, reorder: (List<String>) -> Unit, hide: (String, Boolean) -> Unit,
    movePage: (String, Int) -> Unit, add: () -> Unit, loading: Boolean, error: String?, retry: () -> Unit
) {
    val ordered = orderedPersonalApps(apps, order)
    val shown = ordered.filterNot { it.data().optBoolean("desktopHidden") }
    var selected by remember { mutableStateOf<Record?>(null) }
    var manage by remember { mutableStateOf(false) }
    val arrangedPages = personalAppPages(shown)
    val pages = arrangedPages.size
    val pager = rememberPagerState(pageCount = { pages })
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Personal apps · Only you", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { manage = true }) { Icon(Icons.Outlined.MoreVert, "Manage app icons") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = retry) { Text("Retry") } }
        if (!loading && shown.isEmpty()) {
            Text("Your personal app desktop", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 32.dp))
            Text("Add tools and games for yourself. Apps shared in chats keep their own separate data.", Modifier.padding(vertical = 12.dp))
            Button(onClick = add) { Text("Add apps") }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cellWidth = with(density) { (maxWidth / 3).toPx() }
            val cellHeight = with(density) { 112.dp.toPx() }
            HorizontalPager(pager, Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { page ->
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    repeat(4) { row ->
                        Row(Modifier.fillMaxWidth()) {
                            repeat(3) { column ->
                                val app = arrangedPages[page].getOrNull(row * 3 + column)
                                if (app == null) Spacer(Modifier.weight(1f).height(112.dp))
                                else key(app.key) {
                                    var dragX by remember { mutableFloatStateOf(0f) }
                                    var dragY by remember { mutableFloatStateOf(0f) }
                                    Column(Modifier.weight(1f).height(112.dp)
                                        .pointerInput(app.key, shown.map { it.key }) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { dragX = 0f; dragY = 0f },
                                                onDragEnd = {
                                                    val dx = kotlin.math.round(dragX / cellWidth).toInt()
                                                    val dy = kotlin.math.round(dragY / cellHeight).toInt()
                                                    val cell = row * 3 + column
                                                    val targetCell = (cell + dx + dy * 3).coerceIn(0, arrangedPages[page].lastIndex)
                                                    val targetKey = arrangedPages[page][targetCell].key
                                                    if (targetCell != cell) reorder(movedApp(ordered.map { it.key }, app.key, ordered.indexOfFirst { it.key == targetKey }))
                                                    else selected = app
                                                    dragX = 0f; dragY = 0f
                                                }, onDragCancel = { dragX = 0f; dragY = 0f }
                                            ) { change, amount -> change.consume(); dragX += amount.x; dragY += amount.y }
                                        }
                                        .combinedClickable(onClick = { open(app) })
                                        .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(Modifier.fillMaxWidth()) {
                                            Box(Modifier.align(Alignment.Center).offset(x = with(density) { dragX.toDp() }, y = with(density) { dragY.toDp() })) { PersonalAppIcon(app, localFile) }
                                            IconButton(onClick = { selected = app }, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                                                Icon(Icons.Outlined.MoreVert, "Options for " + app.data().optString("name").substringBeforeLast('.'), modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        Text(app.data().optString("name").substringBeforeLast('.'), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }, enabled = pager.currentPage > 0) { Icon(Icons.Outlined.ChevronLeft, "Previous page") }
            Text("${pager.currentPage + 1} / $pages · Swipe pages", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(pages - 1)) } }, enabled = pager.currentPage < pages - 1) { Icon(Icons.Outlined.ChevronRight, "Next page") }
        }
    }
    }
    selected?.let { app ->
        val page = arrangedPages.indexOfFirst { entries -> entries.any { it.key == app.key } }.coerceAtLeast(0)
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(app.data().optString("name").substringBeforeLast('.')) },
            text = { Column {
                Text("Hold and drag to arrange icons. Pages grow as you add apps.")
                if (page > 0) TextButton(onClick = { movePage(app.key, page - 1); selected = null }) { Text("Move to previous page") }
                TextButton(onClick = { movePage(app.key, page + 1); selected = null }) { Text("Move to next page") }
                TextButton(onClick = { hide(app.key, true); selected = null }) { Text("Remove icon (keep app data)") }
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Done") } })
    }
    if (manage) AlertDialog(onDismissRequest = { manage = false }, title = { Text("Hidden app icons") }, text = {
        LazyColumn { val hidden = ordered.filter { it.data().optBoolean("desktopHidden") }
            if (hidden.isEmpty()) item { Text("No hidden icons. Tap an app’s options button to hide it.") }
            items(hidden, key = { it.key }) { app -> TextButton(onClick = { hide(app.key, false) }) { Text("Restore " + app.data().optString("name").substringBeforeLast('.')) } }
    } }, confirmButton = { TextButton(onClick = { manage = false }) { Text("Done") } })
}

@Composable
internal fun SavedCollection(
    records: List<Record>, selfThread: String?, loading: Boolean, error: String?,
    close: () -> Unit, retry: () -> Unit, open: (Record) -> Unit, remove: (Record) -> Unit,
    source: (Record) -> Unit, note: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var removing by remember { mutableStateOf<Record?>(null) }
    val saved = records.filter { item -> val d = item.data()
        !d.optBoolean("locallyDeleted") && d.optInt("webxdcParent") <= 9 &&
            ((selfThread != null && d.optString("thread") == selfThread && item.isThreadMessage() &&
                (!d.optBoolean("webxdc") || d.optInt("nativeOriginalId") > 9)) || d.optBoolean("star"))
    }.filter { query.isBlank() || it.searchText().contains(query, ignoreCase = true) }
    WebxdcPage("Saved", close) {
        OutlinedTextField(query, { query = it }, label = { Text("Search saved items") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        TextButton(onClick = note) { Text("Add a note or file") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = retry) { Text("Retry") } }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (saved.isEmpty() && !loading) item { Text("Save messages and files from any conversation. They will appear here.") }
            items(saved, key = { it.key }) { item -> val d = item.data()
                ElevatedCard(onClick = { open(item) }) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        val text = d.optString("text").ifBlank { d.optString("caption") }
                        if (d.optString("name").isNotBlank()) Text(d.optString("name"), style = MaterialTheme.typography.titleMedium)
                        if (text.isNotBlank()) Text(text, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        Column {
                            if (d.optInt("nativeOriginalId") > 9) TextButton(onClick = { source(item) }) { Text("Original conversation") }
                            if (d.optString("thread") == selfThread) TextButton(onClick = { removing = item }) { Text("Remove saved copy") }
                            else Text("Older starred item · tap to open", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    removing?.let { item -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove from Saved?") },
        text = { Text("The original message in its conversation will remain.") },
        confirmButton = { TextButton(onClick = { remove(item); removing = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}
