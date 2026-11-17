package app.p2p

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
internal fun ProfileChooser(
    profiles: RelayProfiles, current: String, alertProfile: String?, error: String?,
    select: (String?) -> Unit, close: () -> Unit
) {
    var options by remember { mutableStateOf<List<RelayProfileOption>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(profiles, current) {
        options = try {
            profiles.options()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadFailed = true
            emptyList()
        }
    }
    P2pAlertDialog(
        onDismissRequest = close,
        title = { Text("Profiles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (alertProfile != null) Text("Choose the profile that received this alert to open it.")
                Text("Relay Lab checks for new messages and calls for the active profile.")
                if (options == null) Text("Loading profiles…")
                if (loadFailed) Text("Couldn’t load profiles.", color = MaterialTheme.colorScheme.error)
                options.orEmpty().forEach { profile ->
                    val label = when (profile.id) {
                        current -> "${profile.name} · Active"
                        alertProfile -> "${profile.name} · Alert"
                        else -> profile.name
                    }
                    PageActionButton(label, { select(profile.id) }, Modifier.fillMaxWidth(), enabled = profile.id != current)
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { P2pTextButton(onClick = { select(null) }) { Text("Add profile") } },
        dismissButton = { P2pTextButton(onClick = close) { Text("Close") } }
    )
}
