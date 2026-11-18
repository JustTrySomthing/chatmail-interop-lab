package app.p2p

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean

internal enum class WebxdcCapability(val label: String, val androidPermissions: List<String> = emptyList()) {
    CAMERA("camera", listOf(Manifest.permission.CAMERA)),
    MICROPHONE("microphone", listOf(Manifest.permission.RECORD_AUDIO)),
    LOCATION("location", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    MOTION("motion sensors");

    fun granted(check: (String) -> Boolean): Boolean = when (this) {
        LOCATION -> androidPermissions.any(check)
        else -> androidPermissions.all(check)
    }
}

private val captureResources = mapOf(
    PermissionRequest.RESOURCE_VIDEO_CAPTURE to WebxdcCapability.CAMERA,
    PermissionRequest.RESOURCE_AUDIO_CAPTURE to WebxdcCapability.MICROPHONE
)

private fun isWebxdcPermissionOrigin(origin: Uri, host: String): Boolean =
    origin.scheme == "https" && origin.encodedAuthority == host &&
        origin.encodedPath.orEmpty() in setOf("", "/") && origin.query == null && origin.fragment == null

internal fun webxdcCaptureCapabilities(origin: Uri, host: String, resources: Array<String>): List<WebxdcCapability>? {
    if (!isWebxdcPermissionOrigin(origin, host) || resources.isEmpty()) return null
    return resources.distinct().map { captureResources[it] ?: return null }
}

internal fun webxdcPermissionsPolicy(motionAllowed: Boolean): String {
    val motion = if (motionAllowed) "(self)" else "()"
    return "fullscreen=(self), camera=(self), microphone=(self), geolocation=(self), accelerometer=$motion, " +
        "gyroscope=$motion, magnetometer=$motion, ambient-light-sensor=()"
}

internal class WebxdcPermissions(private val context: Context, private val host: String) {
    private data class Request(
        val token: Any,
        val capabilities: List<WebxdcCapability>,
        val answer: (Boolean) -> Unit
    )

    private val motionToken = Any()
    private val locationToken = Any()
    private val motion = AtomicBoolean(false)
    private var request by mutableStateOf<Request?>(null)
    private var approved by mutableStateOf(false)

    var androidPromptOpen = false
        private set
    var authorized = false
        private set
    val motionAllowed: Boolean
        get() = motion.get()
    val promptCapabilities: List<WebxdcCapability>?
        get() = request?.takeUnless { approved }?.capabilities

    fun receiveCapture(incoming: PermissionRequest, foreground: Boolean) {
        val capabilities = webxdcCaptureCapabilities(incoming.origin, host, incoming.resources)
        if (capabilities == null) {
            incoming.deny()
            return
        }
        receive(Request(incoming, capabilities) { allowed ->
            if (allowed) {
                val granted = captureResources.filterValues { it in capabilities }.keys.toTypedArray()
                incoming.grant(granted)
            } else incoming.deny()
        }, foreground)
    }

    fun receiveLocation(origin: String, callback: GeolocationPermissions.Callback, foreground: Boolean) {
        if (!isWebxdcPermissionOrigin(Uri.parse(origin), host)) {
            callback.invoke(origin, false, false)
            return
        }
        receive(Request(locationToken, listOf(WebxdcCapability.LOCATION)) { allowed ->
            callback.invoke(origin, allowed, false)
        }, foreground)
    }

    fun receiveMotion(foreground: Boolean, answer: (Boolean) -> Unit) {
        if (motionAllowed || request?.token === motionToken) return
        receive(Request(motionToken, listOf(WebxdcCapability.MOTION), answer), foreground)
    }

    private fun receive(incoming: Request, foreground: Boolean) {
        if (!foreground || request != null || androidPromptOpen) {
            incoming.answer(false)
            return
        }
        request = incoming
    }

    fun approve(foreground: Boolean, launch: (Array<String>) -> Unit) {
        val capabilities = promptCapabilities ?: return
        if (!foreground) {
            deny()
            return
        }
        approved = true
        val missing = capabilities.filterNot { it.granted(::androidGranted) }
            .flatMap { it.androidPermissions }.distinct()
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
        clearRequest()
        val allowed = incoming.capabilities.all { it.granted(::androidGranted) }
        if (allowed) {
            if (WebxdcCapability.MOTION in incoming.capabilities) motion.set(true)
            authorized = true
        }
        incoming.answer(allowed)
    }

    fun captureCanceled(incoming: PermissionRequest) = cancel(incoming)
    fun locationCanceled() = cancel(locationToken)

    private fun cancel(token: Any) {
        if (request?.token === token) clearRequest()
    }

    fun deny() {
        val incoming = request
        clearRequest()
        incoming?.answer?.invoke(false)
    }

    private fun clearRequest() {
        request = null
        approved = false
    }

    private fun androidGranted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
internal fun WebxdcPermissionPrompt(
    appName: String, capabilities: List<WebxdcCapability>, approve: () -> Unit, deny: () -> Unit
) {
    val requested = capabilities.joinToString(" and ") { it.label }
    val restart = if (WebxdcCapability.MOTION in capabilities) " Allowing access reloads this mini-app." else ""
    P2pAlertDialog(
        onDismissRequest = deny,
        title = { Text("Allow access to your $requested?") },
        text = { Text("$appName wants to use your $requested.$restart Leaving the mini-app ends access and closes it.") },
        confirmButton = { P2pTextButton(onClick = approve) { Text("Allow") } },
        dismissButton = { P2pTextButton(onClick = deny) { Text("Deny") } }
    )
}
