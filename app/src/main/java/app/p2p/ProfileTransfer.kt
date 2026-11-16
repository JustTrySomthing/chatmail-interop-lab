package app.p2p

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val profileTransferPrefix = Regex("DCBACKUP[0-9]+:", RegexOption.IGNORE_CASE)

internal fun isProfileTransferQr(value: String): Boolean {
    val code = value.trim()
    if (code.length !in 14..8192 || code.any(Char::isWhitespace)) return false
    val prefix = profileTransferPrefix.find(code)?.takeIf { it.range.first == 0 } ?: return false
    return prefix.range.last < code.lastIndex
}

internal suspend fun runProfileTransfer(
    core: Chatmail, transfer: () -> Unit, untilReady: suspend () -> Unit = {}
) = coroutineScope {
    val worker = async(Dispatchers.IO) { transfer() }
    try {
        untilReady()
        worker.await()
    } finally {
        // Cancel Core before releasing the profile lock. Cancellation can arrive while Core starts up.
        if (!worker.isCompleted) withContext(NonCancellable + Dispatchers.IO) {
            while (!worker.isCompleted) {
                runCatching { core.stopProfileTransfer() }
                withTimeoutOrNull(250L) { worker.join() }
            }
        }
    }
}

private val privateTransferDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

@Composable
internal fun SendProfileDialog(model: P2pModel, close: () -> Unit) {
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model) {
        val error = chatAction {
            model.shareChatmailProfile { code ->
                val image = requireNotNull(InviteImage.make(code)) { "Couldn’t display the transfer QR code." }
                withContext(Dispatchers.Main) { qr = image }
            }
        }
        qr = null
        status = error ?: "Your profile was sent to the second device."
    }
    P2pAlertDialog(
        onDismissRequest = close, title = { Text("Add second device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val image = qr
                when {
                    status != null -> Text(status.orEmpty())
                    image == null -> {
                        Text("Preparing the transfer…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text("Connect both devices to the same network. Scan this code from Add as second device on the other device.")
                        InviteQr(image, "Second-device transfer QR code", Modifier.size(230.dp))
                        Text("Scan the code yourself. It gives access to this profile and its history.")
                    }
                }
            }
        },
        confirmButton = { P2pTextButton(onClick = close) { Text(if (status == null) "Cancel" else "Close") } },
        properties = privateTransferDialog
    )
}

@Composable
internal fun ReceiveProfileDialog(model: P2pModel, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }
    fun receive(read: suspend () -> String) {
        if (working) return
        working = true
        error = null
        scope.launch {
            try {
                error = chatAction { model.receiveChatmailProfile(read()) }
                if (error == null) close()
            } finally {
                working = false
            }
        }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) receive { withContext(Dispatchers.IO) { InviteImage.read(context, uri) } }
    }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else error = "Allow camera access or use a QR image."
    }
    if (scanning) {
        InviteScanner(InviteImportState(busy = working, message = error, failed = error != null),
            close = close, pickImage = { scanning = false; pickImage.launch(arrayOf("image/*")) },
            transferProfile = true, scanned = { code -> receive { code } })
    } else P2pAlertDialog(
        onDismissRequest = close, title = { Text("Add as second device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Connect both devices to the same network. Open Add second device on the device that already has your profile.")
                if (working) {
                    Text("Receiving profile…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    P2pTextButton(onClick = {
                        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                        else requestCamera.launch(Manifest.permission.CAMERA)
                    }) { Text("Scan QR code") }
                    P2pTextButton(onClick = { pickImage.launch(arrayOf("image/*")) }) { Text("Read QR from image") }
                    OutlinedTextField(pasted, { pasted = it.take(8192) },
                        label = { Text("Transfer code") }, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedFieldShape, maxLines = 2)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            P2pTextButton(enabled = !working && isProfileTransferQr(pasted), onClick = { receive { pasted } }) {
                Text("Receive")
            }
        },
        dismissButton = { P2pTextButton(onClick = close) { Text("Cancel") } },
        properties = privateTransferDialog
    )
}
