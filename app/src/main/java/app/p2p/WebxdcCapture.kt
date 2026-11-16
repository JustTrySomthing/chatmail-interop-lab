package app.p2p

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.PermissionRequest
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

private val captureResources = mapOf(
    PermissionRequest.RESOURCE_VIDEO_CAPTURE to Manifest.permission.CAMERA,
    PermissionRequest.RESOURCE_AUDIO_CAPTURE to Manifest.permission.RECORD_AUDIO
)

internal fun webxdcCapturePermissions(origin: Uri, host: String, resources: Array<String>): List<String>? {
    if (origin.scheme != "https" || origin.encodedAuthority != host ||
        origin.encodedPath.orEmpty() !in setOf("", "/") || origin.query != null || origin.fragment != null ||
        resources.isEmpty()) return null
    return resources.distinct().map { captureResources[it] ?: return null }
}

internal class WebxdcCaptureAccess(private val context: Context, private val host: String) {
    private var request: PermissionRequest? by mutableStateOf(null)
    private var approved by mutableStateOf(false)
    private var androidPromptOpen = false
    var authorized = false
        private set

    val promptPermissions: List<String>?
        get() = request?.takeUnless { approved }?.let { webxdcCapturePermissions(it.origin, host, it.resources) }

    fun receive(incoming: PermissionRequest, foreground: Boolean) {
        if (!foreground || request != null || androidPromptOpen ||
            webxdcCapturePermissions(incoming.origin, host, incoming.resources) == null) {
            incoming.deny()
            return
        }
        request = incoming
        approved = false
    }

    fun approve(foreground: Boolean, launch: (Array<String>) -> Unit) {
        val permissions = promptPermissions ?: return
        if (!foreground) {
            deny()
            return
        }
        approved = true
        val missing = permissions.filterNot(::androidGranted)
        if (missing.isEmpty()) {
            settle(foreground)
        } else {
            androidPromptOpen = true
            try {
                launch(missing.toTypedArray())
            } catch (_: IllegalStateException) {
                androidPromptOpen = false
                deny()
            }
        }
    }

    fun androidResult(foreground: Boolean) {
        androidPromptOpen = false
        settle(foreground)
    }

    fun settle(foreground: Boolean) {
        val incoming = request ?: return
        if (!foreground || !approved || androidPromptOpen) return
        val permissions = webxdcCapturePermissions(incoming.origin, host, incoming.resources)
        request = null
        approved = false
        if (permissions != null && permissions.all(::androidGranted)) {
            // Map back through the allowlist. Never grant unfamiliar WebView resource types.
            val granted = captureResources.filterValues { it in permissions }.keys.toTypedArray()
            authorized = true
            incoming.grant(granted)
        } else incoming.deny()
    }

    fun canceled(incoming: PermissionRequest) {
        if (request === incoming) {
            request = null
            approved = false
        }
    }

    fun deny() {
        val incoming = request
        request = null
        approved = false
        incoming?.deny()
    }

    private fun androidGranted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
internal fun WebxdcCapturePrompt(appName: String, permissions: List<String>, approve: () -> Unit, deny: () -> Unit) {
    val devices = permissions.joinToString(" and ") {
        if (it == Manifest.permission.CAMERA) "camera" else "microphone"
    }
    P2pAlertDialog(
        onDismissRequest = deny,
        title = { Text("Allow $devices access?") },
        text = { Text("$appName wants to use your $devices. Leaving the mini-app ends access and closes it.") },
        confirmButton = { P2pTextButton(onClick = approve) { Text("Allow") } },
        dismissButton = { P2pTextButton(onClick = deny) { Text("Deny") } }
    )
}
