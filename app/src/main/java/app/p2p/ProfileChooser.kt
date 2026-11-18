package app.p2p

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProfileChooser(
    profiles: RelayProfiles, current: String, alertProfile: String?, error: String?,
    select: (String?) -> Unit, close: () -> Unit, edit: () -> Unit = {},
    remove: suspend (String) -> Unit = { profiles.deleteInactiveProfile(it) }
) {
    var options by remember { mutableStateOf<List<RelayProfileOption>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RelayProfileOption?>(null) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profiles, current) {
        if (saving) return@LaunchedEffect
        loadFailed = false
        options = try {
            profiles.options()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
            emptyList()
        }
    }
    deleting?.let { profile ->
        P2pAlertDialog(onDismissRequest = { if (!saving) deleting = null },
            title = { Text("Delete Profile?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Delete ${profile.name} from this device, including its messages, Saved items, personal apps and settings? Back up anything you want to keep first. Other profiles and other devices are unaffected.")
                if (profile.id == current) Text("Another profile will open. If needed, a new empty profile will be created.")
                settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { P2pTextButton(enabled = !saving, onClick = {
                saving = true
                settingsError = null
                scope.launch {
                    try {
                        remove(profile.id)
                        options = profiles.options()
                        deleting = null
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: Exception) {
                        settingsError = failure.message ?: "Couldn’t delete this profile."
                    } finally { saving = false }
                }
            }) { Text(if (saving) "Deleting…" else "Delete Profile") } },
            dismissButton = { P2pTextButton(enabled = !saving, onClick = { deleting = null }) { Text("Cancel") } })
    }
    if (deleting == null) P2pAlertDialog(
        onDismissRequest = { if (!saving) close() },
        title = { Text("Profiles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (alertProfile != null) Text("Choose the profile that received this alert to open it.")
                Text("Enabled profiles can receive background messages. Calls ring for the active profile.")
                PageActionButton("Edit Active Profile", edit, Modifier.fillMaxWidth(), enabled = !saving)
                if (options == null) Text("Loading profiles…")
                if (loadFailed) Text("Couldn’t load profiles.", color = MaterialTheme.colorScheme.error)
                options.orEmpty().forEach { profile ->
                    val label = when (profile.id) {
                        current -> "${profile.name} · Active"
                        else -> if (!profile.enabled) "${profile.name} · Disabled" else profile.name
                    }.let { if (profile.enabled && profile.id == alertProfile) "$it · Alert" else it }
                    PageActionButton(label, { select(profile.id) }, Modifier.fillMaxWidth(),
                        enabled = profile.enabled && profile.id != current && !saving)
                    if (profile.id != current) P2pTextButton(enabled = !saving, onClick = {
                        scope.launch {
                            saving = true
                            settingsError = null
                            try {
                                profiles.setEnabled(profile.id, !profile.enabled)
                                options = profiles.options()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                settingsError = failure.message ?: "Couldn’t change this profile."
                            } finally {
                                saving = false
                            }
                        }
                    }) { Text(if (profile.enabled) "Disable Profile" else "Enable Profile") }
                    P2pTextButton(enabled = !saving, onClick = { settingsError = null; deleting = profile }) { Text("Delete Profile") }
                }
                settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { P2pTextButton(enabled = !saving, onClick = { select(null) }) { Text("Add Profile") } },
        dismissButton = { P2pTextButton(enabled = !saving, onClick = close) { Text("Close") } }
    )
}
