package app.p2p

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/** One chooser for all composers. Selection never opts media out of compression. */
@Composable
internal fun rememberAttachmentPicker(
    model: P2pModel,
    complete: (String, List<AttachmentDraft>) -> Unit
): (String) -> Unit {
    val context = LocalContext.current
    val onComplete by rememberUpdatedState(complete)
    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    var screen by rememberSaveable { mutableStateOf<String?>(null) }
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var video by rememberSaveable { mutableStateOf(false) }
    fun finish(items: List<AttachmentDraft> = emptyList()) {
        val target = destination
        destination = null
        screen = null
        if (target != null) onComplete(target, items)
    }
    fun captured(ok: Boolean) {
        val file = capturePath?.let(::File)
        capturePath = null
        if (file == null || !ok || file.length() == 0L) {
            file?.delete()
            finish()
        } else finish(listOf(AttachmentDraft(Uri.fromFile(file),
            if (video) "Recorded video.mp4" else "Camera photo.jpg",
            if (video) "video/mp4" else "image/jpeg", false, temporary = true)))
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), ::captured)
    val movie = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo(), ::captured)
    fun capture() {
        screen = null
        runCatching {
            val folder = File(context.cacheDir, "captures").apply { mkdirs() }
            val file = File.createTempFile("relay-camera-", if (video) ".mp4" else ".jpg", folder)
            capturePath = file.absolutePath
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            if (video) movie.launch(uri) else photo.launch(uri)
        }.onFailure {
            capturePath?.let { File(it).delete() }
            capturePath = null
            screen = "unavailable"
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) capture() else screen = "unavailable"
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        screen = if (it) "audio" else "unavailable"
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val items = uris.distinct().map { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            describeAttachment(context, uri, asFile = false)
        }
        finish(items)
    }
    when (screen) {
        "menu" -> Sheet("Add attachment", { finish() }) {
            AttachmentActions(
                photo = {
                    video = false
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) capture()
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                },
                video = {
                    video = true
                    if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) capture()
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                },
                audio = {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) screen = "audio"
                    else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                file = {
                    screen = null
                    runCatching { files.launch(arrayOf("*/*")) }.onFailure { screen = "unavailable" }
                })
        }
        "audio" -> Sheet("Record Audio", { screen = "menu" }) {
            AudioCapturePanel(model) { file ->
                finish(listOf(AttachmentDraft(Uri.fromFile(file), "Recorded audio.m4a", "audio/mp4", false, temporary = true)))
            }
        }
        "unavailable" -> Sheet("Capture unavailable", { screen = "menu" }) {
            Text("Check Android permissions, or use Attach File to select an existing file.")
        }
    }
    return { target -> if (destination == null) { destination = target; screen = "menu" } }
}

@Composable
internal fun AttachmentActions(photo: () -> Unit, video: () -> Unit, audio: () -> Unit, file: () -> Unit) {
    P2pRow("Take Photo", "", Icons.Outlined.PhotoCamera, click = photo)
    P2pRow("Record Video", "", Icons.Outlined.Videocam, click = video)
    P2pRow("Record Audio", "", Icons.Outlined.Mic, click = audio)
    P2pRow("Attach File", "", Icons.Outlined.AttachFile, click = file)
}
