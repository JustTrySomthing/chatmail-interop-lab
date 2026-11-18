package app.p2p

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun Record.isSavedItem() = data().let {
    it.optBoolean("nativeSelfTalk") || it.optBoolean("star") || it.optInt("nativeSavedId") > 9 || it.optInt("savedCopyId") > 9
}

internal fun personalApps(records: List<Record>, thread: String?): List<Record> =
    records.webxdcMessages(thread).filter { it.data().optInt("nativeOriginalId") <= 9 }

internal fun orderedPersonalApps(apps: List<Record>, order: List<String>): List<Record> {
    val byId = apps.associateBy { it.key }
    return order.distinct().mapNotNull(byId::get) + apps.filter { it.key !in order }.sortedBy { it.created }
}

internal fun legacySavedCandidates(records: List<Record>) = records.filter {
    val data = it.data()
    data.optBoolean("star") && !data.optBoolean("locallyDeleted") && data.optInt("webxdcParent") <= 9
}

internal fun legacySavedParts(item: Record, records: List<Record>): List<Record> {
    if (item.kind != "post") return listOf(item)
    val attachments = records.filter { it.kind.startsWith("file-") && it.data().optString("post") == item.key && !it.data().optBoolean("locallyDeleted") }
    check(attachments.size >= item.data().optInt("media")) { "Some post attachments are unavailable. Download them before saving this post." }
    return listOf(item) + attachments
}

internal fun savedRemovalExplanation(item: Record): String =
    if (item.data().optInt("nativeOriginalId") > 9) "The original message in its conversation will remain."
    else "This removes the item from Saved. There may be no other copy."

internal enum class SavedKind(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Message("Messages", Icons.AutoMirrored.Outlined.Chat),
    Photo("Photos", Icons.Outlined.Image), Video("Video", Icons.Outlined.VideoFile),
    Audio("Audio", Icons.Outlined.AudioFile), App("Apps", Icons.Outlined.Apps),
    Contact("Contacts", Icons.Outlined.ContactPage), File("Files", Icons.AutoMirrored.Outlined.InsertDriveFile)
}

internal fun savedKind(item: Record): SavedKind {
    val d = item.data()
    return when {
        d.optBoolean("webxdc") -> SavedKind.App
        d.optBoolean("nativeVcard") || d.optString("name").endsWith(".vcf", true) -> SavedKind.Contact
        !item.kind.startsWith("file-") -> SavedKind.Message
        else -> when (mediaKind(d.optString("mime"), d.optString("name"))) {
            MediaKind.Audio -> SavedKind.Audio
            MediaKind.Video -> SavedKind.Video
            null -> SavedKind.File
            else -> SavedKind.Photo
        }
    }
}

internal fun savedCollectionItems(records: List<Record>, selfThread: String?): List<Record> = records.filter { item ->
    val d = item.data()
    !d.optBoolean("locallyDeleted") && d.optInt("webxdcParent") <= 9 &&
        ((selfThread != null && d.optString("thread") == selfThread && item.isThreadMessage() &&
            (!d.optBoolean("webxdc") || d.optInt("nativeOriginalId") > 9)) || d.optBoolean("star"))
}.sortedByDescending { it.created }

internal fun savedSourceLabel(channel: Boolean, group: Boolean): String = when {
    channel -> "Go to Channel"
    group -> "Go to Group"
    else -> "Go to Chat"
}

@Composable
internal fun SavedCollection(
    records: List<Record>, selfThread: String?, loading: Boolean, error: String?,
    close: () -> Unit, retry: () -> Unit, open: (Record) -> Unit, remove: (Record) -> Unit,
    source: (Record) -> Unit, note: () -> Unit,
    localFile: (Record) -> java.io.File? = { null }, conversationName: (String) -> String = { "Conversation" },
    resolveSourceLabel: (suspend (Record) -> String?)? = null
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var removing by remember { mutableStateOf<Record?>(null) }
    val colors = MaterialTheme.colorScheme
    val all = remember(records, selfThread) { savedCollectionItems(records, selfThread) }
    val saved = all.filter { (filter == "All" || savedKind(it).label == filter) &&
        (query.isBlank() || it.searchText().contains(query.trim(), ignoreCase = true)) }
    WebxdcPage("Saved", close) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your saved collection", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
                    Text("${all.size} ${if (all.size == 1) "item" else "items"} · Newest first", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = note, colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = colors.surfaceVariant, contentColor = readableAccent(colors.surfaceVariant, colors.onSurface))) {
                    Icon(Icons.Outlined.Add, "Add a note or file")
                }
            }
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search saved items") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") } },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
        }
        LazyRow(modifier = Modifier.testTag("saved-filters"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("All") + SavedKind.entries.map { it.label }) { label ->
                FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.primary,
                        selectedLabelColor = colors.onPrimary, labelColor = colors.onSurface))
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(14.dp), color = colors.errorContainer, contentColor = colors.onErrorContainer) {
                Column(Modifier.padding(12.dp)) { Text(it); TextButton(onClick = retry, colors = ButtonDefaults.textButtonColors(contentColor = colors.onErrorContainer)) { Text("Retry") } }
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("saved-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (saved.isEmpty() && !loading) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.StarBorder, null, Modifier.size(44.dp), tint = readableAccent(colors.background, colors.onBackground))
                    Text(if (query.isNotBlank() || filter != "All") "No matching saved items" else "Keep something for later", style = MaterialTheme.typography.titleMedium)
                    Text(if (query.isNotBlank() || filter != "All") "Try another search or choose All." else "Tap the star on a message or attachment to find it here. You can also add a note or file.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = colors.onSurfaceVariant)
                }
            }
            items(saved, key = { it.key }) { item ->
                val original = records.firstOrNull { it.key == nativeMessageKey(item.data().optInt("nativeOriginalId")) }
                val origin = original?.data()?.optString("thread")?.takeIf { it.isNotBlank() }?.let(conversationName)
                    ?: if (item.data().optInt("nativeOriginalId") > 9) "Saved from a conversation" else "Personal saved item"
                val sourceLabel by produceState<String?>(null, item.key, item.data().optInt("nativeOriginalId")) {
                    if (item.data().optInt("nativeOriginalId") > 9 && resolveSourceLabel != null) {
                        value = try { resolveSourceLabel(item) }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    }
                }
                val cachedThread = records.firstOrNull { it.key == original?.data()?.optString("thread") }
                val label = sourceLabel ?: if (resolveSourceLabel == null && original != null) savedSourceLabel(false,
                    cachedThread?.kind == "group" || original.data().optString("group").isNotBlank()) else "Go to Source"
                SavedItemCard(item, origin, localFile(item), label, { open(item) },
                    if (item.data().optInt("nativeOriginalId") > 9) ({ source(item) }) else null,
                    if (selfThread != null && item.data().optString("thread") == selfThread) ({ removing = item }) else null)
            }
        }
    }
    removing?.let { item -> AlertDialog(onDismissRequest = { removing = null },
        containerColor = colors.surface, titleContentColor = colors.onSurface, textContentColor = colors.onSurface,
        icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = readableAccent(colors.surface, colors.onSurface)) },
        title = { Text("Remove from Saved?") }, text = { Text(savedRemovalExplanation(item)) },
        confirmButton = { P2pTextButton(onClick = { remove(item); removing = null }) { Text("Remove") } },
        dismissButton = { P2pTextButton(onClick = { removing = null }) { Text("Cancel") } }) }
}

@Composable
private fun SavedItemCard(item: Record, origin: String, file: java.io.File?, sourceLabel: String, open: () -> Unit, source: (() -> Unit)?, remove: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val d = item.data()
    val kind = savedKind(item)
    val text = d.optString("text").ifBlank { d.optString("caption") }
    val title = d.optString("name").ifBlank { if (kind == SavedKind.Message) "Saved message" else kind.label }
    val thumbnail = mediaThumbnail(file, if (kind in listOf(SavedKind.Photo, SavedKind.Video)) mediaKind(d.optString("mime"), title) else null)
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = open, shape = RoundedCornerShape(20.dp), color = colors.surface, contentColor = colors.onSurface,
        border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column {
            if (thumbnail != null) Box(Modifier.fillMaxWidth().height(160.dp)) {
                Image(thumbnail.asImageBitmap(), title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (kind == SavedKind.Video) Surface(Modifier.align(Alignment.Center), shape = CircleShape, color = colors.surface) {
                    Icon(Icons.Outlined.PlayArrow, "Video preview", Modifier.padding(10.dp).size(28.dp), tint = colors.onSurface)
                }
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceVariant) {
                        Icon(kind.icon, null, Modifier.padding(10.dp).size(24.dp), tint = readableAccent(colors.surfaceVariant, colors.onSurface))
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(if (kind == SavedKind.Message) "Message" else title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(origin, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Options for $title") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.surface) {
                            DropdownMenuItem(text = { Text("Open Saved Item") }, leadingIcon = { Icon(Icons.Outlined.OpenInNew, null) }, onClick = { menu = false; open() })
                            if (source != null) DropdownMenuItem(text = { Text(sourceLabel) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) }, onClick = { menu = false; source() })
                            if (remove != null) DropdownMenuItem(text = { Text("Remove From Saved") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = false; remove() })
                        }
                    }
                }
                if (text.isNotBlank()) Text(text, maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(item.created)),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

internal fun canDeletePersonalApp(item: Record, native: org.json.JSONObject, selfChat: Int): Boolean {
    val data = item.data()
    return selfChat > 9 && data.optInt("nativeMessage") > 9 && data.optBoolean("webxdc") &&
        !data.optBoolean("locallyDeleted") && data.optInt("nativeOriginalId") <= 9 &&
        native.optInt("chatId") == selfChat && native.optString("viewType") == "Webxdc" &&
        native.optInt("originalMsgId") <= 9
}

internal fun conversationMedia(records: List<Record>, thread: String): List<Record> = records.filter { item ->
    val data = item.data()
    item.isThreadMessage() && data.optString("thread") == thread && data.optInt("webxdcParent") <= 9 &&
        (item.kind.startsWith("file-") || data.optBoolean("webxdc") || data.optBoolean("nativeVcard"))
}
