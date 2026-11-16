package app.p2p

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun InteropChats(model: P2pModel, initialChat: Int? = null) {
    var selected by rememberSaveable(initialChat) { mutableStateOf(initialChat) }
    var chats by remember { mutableStateOf<List<InteropChat>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val id = selected
    BackHandler(enabled = id != null) { selected = null }
    if (id == null) {
        LaunchedEffect(model) {
            while (true) {
                try {
                    chats = model.interoperableChats()
                    loadError = null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    loadError = error.message ?: "Couldn’t load Chatmail chats."
                } catch (_: LinkageError) {
                    loadError = "Chatmail couldn’t start on this device."
                }
                delay(3_000)
            }
        }
        val available = chats
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        when {
            available == null && loadError == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            available?.isEmpty() == true -> Text("No Chatmail chats yet. Exchange a SecureJoin invite to start.")
            else -> available.orEmpty().forEach { chat ->
                P2pOutlinedButton(onClick = { selected = chat.id }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (chat.request) "${chat.name} · Contact request" else chat.name)
                }
            }
        }
    } else {
        P2pTextButton(onClick = { selected = null }) { Text("Back to Chatmail chats") }
        InteropConversation(model, id)
    }
}

@Composable
private fun InteropConversation(model: P2pModel, id: Int) {
    var conversation by remember(id) { mutableStateOf<Pair<InteropChat, InteropHistory>?>(null) }
    var limit by remember(id) { mutableIntStateOf(50) }
    var refresh by remember(id) { mutableIntStateOf(0) }
    var text by remember(id) { mutableStateOf("") }
    var readingError by remember(id) { mutableStateOf<String?>(null) }
    var sendError by remember(id) { mutableStateOf<String?>(null) }
    var sending by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    LaunchedEffect(model, id, limit, refresh) {
        while (true) {
            try {
                conversation = model.interoperableConversation(id, limit)
                readingError = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                readingError = error.message ?: "Couldn’t load this conversation."
            } catch (_: LinkageError) {
                readingError = "Chatmail couldn’t start on this device."
            }
            delay(2_000)
        }
    }
    val current = conversation
    readingError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (current == null) {
        if (readingError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    val (chat, history) = current
    NativeContactHeader(chat.name, chat.bio, chat.photoPath)
    Text(when {
        chat.request -> "Contact request"
        chat.canSend -> "Encrypted chat"
        else -> "Waiting for the encrypted connection"
    }, style = MaterialTheme.typography.bodySmall)
    if (chat.request) {
        P2pOutlinedButton(enabled = !sending, onClick = {
            sending = true
            sendError = null
            scope.launch {
                try {
                    model.acceptNativeContactRequest(id)
                    refresh++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    sendError = error.message ?: "Couldn’t accept the contact request."
                } finally {
                    sending = false
                }
            }
        }) { Text("Accept contact") }
    }
    val newest = history.messages.lastOrNull()
    LaunchedEffect(newest?.id) {
        if (newest?.outgoing == true || scroll.firstVisibleItemIndex == 0) scroll.scrollToItem(0)
    }
    if (history.messages.isEmpty() && !history.hasOlder) Text("No messages yet.")
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), state = scroll, reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(history.messages.asReversed(), key = { it.id }) { message ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment =
                if (message.outgoing) Alignment.End else Alignment.Start) {
                Surface(color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    SelectionContainer {
                        Text(message.text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
                ReadAloudButton("chatmail-message:${message.id}", message.text, "message")
                if (message.outgoing && message.state == 20) Text("Queued", style = MaterialTheme.typography.bodySmall)
                if (message.outgoing && message.state == 24) Text("Send failed",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (history.hasOlder) item {
            P2pTextButton(onClick = { limit += 50 }) { Text("Load older messages") }
        }
    }
    OutlinedTextField(text, { text = it.take(INTEROP_TEXT_LIMIT) }, label = { Text("Message") },
        modifier = Modifier.fillMaxWidth(), maxLines = 4, enabled = !sending)
    sendError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(enabled = chat.canSend && readingError == null && text.isNotBlank() && !sending, onClick = {
        if (!sending) {
            val draft = text
            sending = true
            sendError = null
            scope.launch {
                try {
                    model.sendInteropText(id, draft)
                    text = ""
                    refresh++
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    sendError = error.message ?: "Couldn’t send the message."
                } catch (_: LinkageError) {
                    sendError = "Chatmail couldn’t start on this device."
                } finally {
                    sending = false
                }
            }
        }
    }) { Text(if (sending) "Sending…" else "Send") }
}

@Composable
internal fun NativeContactRows(model: P2pModel, query: String, showEmpty: Boolean, open: (Int) -> Unit) {
    var contacts by remember { mutableStateOf<List<ChatmailContact>?>(null) }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) {
        val loaded = try {
            model.nativeContacts()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        } catch (_: LinkageError) {
            emptyList()
        }
        contacts = loaded
    }
    val matches = contacts.orEmpty().filter { it.name.contains(query, ignoreCase = true) }
    if (showEmpty && contacts != null && matches.isEmpty()) {
        EmptyTabMessage(if (query.isBlank()) "No contacts yet" else "No matching contacts")
    }
    for (contact in matches) {
        Surface(onClick = {
            if (!opening) {
                opening = true
                error = null
                scope.launch {
                    try {
                        open(model.openNativeContact(contact.id))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Couldn’t open the contact."
                    } finally {
                        opening = false
                    }
                }
            }
        }, enabled = !opening, shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant) {
            NativeContactHeader(contact.name, contact.bio, contact.photoPath,
                Modifier.fillMaxWidth().padding(12.dp))
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun NativeContactHeader(name: String, bio: String, path: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val photo by produceState("", path) {
        value = withContext(Dispatchers.IO) {
            if (path == null) return@withContext ""
            runCatching {
                val root = File(context.noBackupFilesDir, "chatmail").canonicalFile
                val file = File(path).canonicalFile
                if (file.isFile && file.path.startsWith(root.path + File.separator)) {
                    prepareProfilePhoto(context, Uri.fromFile(file))
                } else ""
            }.getOrDefault("")
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, photo = photo)
        Column(Modifier.weight(1f)) {
            Text(name, style = contentTextStyle(MaterialTheme.typography.titleMedium))
            if (bio.isNotBlank()) Text(bio, style = contentTextStyle(MaterialTheme.typography.bodyMedium))
        }
    }
}
