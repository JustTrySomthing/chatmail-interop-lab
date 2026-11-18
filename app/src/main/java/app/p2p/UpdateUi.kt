package app.p2p

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.text.DateFormat
import java.util.Date

@Composable
internal fun UpdateNotice(model: P2pUpdateModel, open: () -> Unit) {
    val state = model.state
    val release = state.release ?: return
    if (!state.prompt || state.busy || state.requestInstall) return
    P2pAlertDialog(
        onDismissRequest = model::dismissPrompt,
        title = { Text("P2P update available") },
        text = { Text(if (state.ready) "Version ${release.version} is ready to install."
            else "Version ${release.version} is ready to download.") },
        dismissButton = { P2pTextButton(onClick = model::dismissPrompt) { Text("Later") } },
        confirmButton = {
            Button(onClick = { model.dismissPrompt(); open() }, shape = P2pButtonShape) {
                Text("View Update")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdateSettingsContent(model: P2pUpdateModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = model.state
    var active by remember { mutableStateOf(false) }
    DisposableEffect(model) {
        active = true
        onDispose {
            active = false
            model.consumeInstallRequest()
        }
    }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.installReturned()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.installPermissionReturned()
    }
    LaunchedEffect(state.requestInstall, state.busy, lifecycle) {
        if (state.requestInstall && !state.busy) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!model.state.requestInstall) return@repeatOnLifecycle
            model.consumeInstallRequest()
            if (context.packageManager.canRequestPackageInstalls()) model.install {
                if (active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    runCatching { installer.launch(it) }.isSuccess
                } else false
            }
            else runCatching {
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }.onFailure { model.installPermissionDenied() }
        }
    }
    Text("Installed ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
    state.release?.let { release ->
        Text("Version ${release.version} available", style = MaterialTheme.typography.titleMedium)
        if (state.downloading) {
            val progress = (state.progress.toDouble() / release.bytes).coerceIn(0.0, 1.0)
            LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("Downloading ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        }
    }
    val status = state.error ?: when {
        state.checking -> "Checking GitHub…"
        state.preparingInstall -> "Verifying update…"
        state.message != null -> state.message
        state.ready && !state.requestInstall -> "Ready to install."
        else -> null
    }
    if (status != null) Text(status, color = if (state.error != null) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface)
    if (state.checked > 0) {
        val checked = remember(state.checked) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.checked)) }
        Text("Last checked $checked", style = MaterialTheme.typography.bodySmall)
    }
    UpdateToggle("Check daily for updates", model.automatic, onChange = model::changeAutomatic)
    UpdateToggle("Include release candidates", model.previews, enabled = !state.busy, onChange = model::changePreviews)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state.downloading -> P2pOutlinedButton(onClick = model::cancelDownload) { Text("Cancel Download") }
            state.release != null -> {
                Button(onClick = model::updateNow, shape = P2pButtonShape, enabled = !state.busy) {
                    Text(if (state.ready) "Install Update" else "Download Update")
                }
                P2pTextButton(onClick = { model.checkNow() }, enabled = !state.busy) { Text("Check Again") }
            }
            else -> Button(onClick = { model.checkNow() }, shape = P2pButtonShape, enabled = !state.busy) { Text("Check Now") }
        }
    }
}

@Composable
private fun UpdateToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}
