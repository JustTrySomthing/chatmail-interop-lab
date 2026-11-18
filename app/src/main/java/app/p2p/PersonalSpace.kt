package app.p2p

import androidx.compose.foundation.layout.*
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
        text = { Text(savedRemovalExplanation(item)) },
        confirmButton = { TextButton(onClick = { remove(item); removing = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }) }
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
