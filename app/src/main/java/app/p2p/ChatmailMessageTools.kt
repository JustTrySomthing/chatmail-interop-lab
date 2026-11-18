package app.p2p

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import java.text.DateFormat
import java.util.Date

internal data class NativeMessageDetails(
    val time: Long, val outgoing: Boolean, val state: Int,
    val readers: List<Pair<String, Long>>, val expiresAt: Long?
) {
    val status: String
        get() = if (!outgoing) "Received" else when (state) {
            20 -> "Queued"
            24 -> "Failed"
            26 -> "Sent to relay"
            28 -> "Read"
            else -> "Status unavailable"
        }
}

@Composable
internal fun MessageInfoSheet(model: P2pModel, message: String, close: () -> Unit) {
    val details by produceState<Result<NativeMessageDetails>?>(null, model, message) {
        value = try {
            Result.success(model.nativeMessageDetails(message))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    Sheet("Message Info", close) {
        val result = details
        val info = result?.getOrNull()
        if (info != null) {
            val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            Text(info.status)
            SelectionContainer { Text(dates.format(Date(info.time))) }
            info.expiresAt?.let { Text("Disappears " + dates.format(Date(it))) }
            if (info.outgoing && info.readers.isNotEmpty()) {
                Text("Read by")
                info.readers.forEach { (name, time) -> Text("$name · " + dates.format(Date(time))) }
            }
        } else if (result == null) LinearProgressIndicator()
        else Text(result.exceptionOrNull()?.message ?: "Message information is unavailable.")
    }
}

@Composable
internal fun ForwardMessageSheet(
    chats: List<Record>, busy: Boolean, error: String?, close: () -> Unit, forward: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    Sheet("Forward message", close) {
        val selected = chats.firstOrNull { it.key == target }
        if (selected == null) {
            SearchField(query, { query = it }, "Search chats and groups")
            val results = chats.filter { it.data().optString("name").contains(query.trim(), ignoreCase = true) }
            if (results.isEmpty()) Text("No matching Chatmail conversations.")
            results.forEach {
                P2pRow(it.data().optString("name"), if (it.kind == "group") "Group" else "", enabled = !busy) {
                    target = it.key
                }
            }
        } else {
            Text("Forward to " + selected.data().optString("name") + "?")
            error?.let { Text(it) }
            PageActionButton("Forward", { forward(selected.key) }, enabled = !busy)
            P2pTextButton(enabled = !busy, onClick = { target = null }) { Text("Choose Another Chat") }
        }
    }
}

@Composable
internal fun EncryptionInfoSheet(model: P2pModel, thread: String, close: () -> Unit) {
    var info by remember(model, thread) { mutableStateOf<String?>(null) }
    val error by produceState<String?>(null, model, thread) {
        value = chatAction { info = model.nativeEncryptionInfo(thread) }
    }
    Sheet("Encryption Info", close) {
        val details = info
        when {
            details != null -> {
                Text("Compare these fingerprints with your contact over a trusted channel.")
                SelectionContainer { Text(details) }
            }
            error != null -> Text(error.orEmpty())
            else -> LinearProgressIndicator()
        }
    }
}

@Composable
internal fun ChatmailRelaysSheet(
    model: P2pModel, revision: Int, busy: Boolean, close: () -> Unit, remove: (String) -> Unit
) {
    var addresses by remember(model, revision) { mutableStateOf<List<String>?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val error by produceState<String?>(null, model, revision) {
        value = chatAction { addresses = model.chatmailRelays() }
    }
    Sheet("Chatmail relays", close) {
        val list = addresses
        when {
            error != null -> Text(error.orEmpty())
            list == null -> LinearProgressIndicator()
            list.isEmpty() -> Text("No relays configured for this profile.")
            else -> {
                list.forEach { address ->
                    Text(address)
                    P2pTextButton(enabled = list.size > 1 && !busy, onClick = { pending = address }) {
                        Text("Remove Relay")
                    }
                }
                if (list.size == 1) Text("Add another relay in Your profile before removing this one.")
            }
        }
    }
    pending?.let { address ->
        P2pAlertDialog(onDismissRequest = { pending = null },
            title = { Text("Remove relay?") }, text = { Text(address) },
            confirmButton = { P2pTextButton(onClick = { pending = null; remove(address) }) { Text("Remove") } },
            dismissButton = { P2pTextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
}
