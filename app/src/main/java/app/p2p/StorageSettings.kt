package app.p2p

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal val LOCAL_RETENTION_CHOICES = listOf(
    0L to "Keep All Messages", 3_600L to "1 Hour", 86_400L to "1 Day",
    604_800L to "1 Week", 2_592_000L to "30 Days", 31_536_000L to "1 Year"
)

@Composable
internal fun StorageSettingsContent(model: P2pModel) {
    val scope = rememberCoroutineScope()
    var selected by remember(model) { mutableStateOf<Long?>(null) }
    var pending by remember(model) { mutableStateOf<Long?>(null) }
    var busy by remember(model) { mutableStateOf(false) }
    var error by remember(model) { mutableStateOf<String?>(null) }
    var attempt by remember(model) { mutableIntStateOf(0) }
    LaunchedEffect(model, attempt) {
        error = chatAction { selected = model.localRetention() }
    }
    fun save(seconds: Long) {
        busy = true
        scope.launch {
            try {
                error = chatAction { model.setLocalRetention(seconds); selected = seconds; pending = null }
            } finally { busy = false }
        }
    }
    Text("Automatic Cleanup", style = MaterialTheme.typography.titleMedium)
    Text("Remove old Chatmail messages and their attachments from this device for the current profile. Saved items and personal apps are kept. Other people’s copies are unchanged.")
    Text("Off by default. Cleanup does not replace a backup.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (selected == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    LOCAL_RETENTION_CHOICES.forEach { (seconds, label) ->
        P2pRow(label, if (selected == seconds) "Selected" else "", enabled = selected != null && !busy) {
            if (selected != seconds) {
                error = null
                if (seconds == 0L) save(seconds) else pending = seconds
            }
        }
    }
    if (selected != null && LOCAL_RETENTION_CHOICES.none { it.first == selected }) Text("Current limit: $selected seconds")
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        if (selected == null) P2pTextButton(onClick = { attempt++ }) { Text("Retry") }
    }
    pending?.let { seconds ->
        val label = LOCAL_RETENTION_CHOICES.first { it.first == seconds }.second
        P2pAlertDialog(onDismissRequest = { if (!busy) pending = null },
            title = { Text("Enable Automatic Cleanup?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chatmail messages older than $label and their attachments will be removed from this device, including existing history. This cannot be undone. Saved items and personal apps are kept.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { P2pTextButton(enabled = !busy, onClick = { save(seconds) }) { Text(if (busy) "Saving…" else "Enable Cleanup") } },
            dismissButton = { P2pTextButton(enabled = !busy, onClick = { pending = null }) { Text("Cancel") } })
    }
}
