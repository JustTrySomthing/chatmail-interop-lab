package app.p2p

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
