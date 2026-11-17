package app.p2p

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.InputStream
import java.io.Serializable

internal data class MediaDownload(
    val name: String,
    val mime: String,
    val attachment: String? = null,
    val profilePhoto: String? = null,
    val thumbnail: String? = null,
    val channelMessage: Int? = null
) : Serializable {
    val filename: String
        get() {
            val clean = name.map { char ->
                if (char in "/\\:*?\"<>|" || char.isISOControl()) '_' else char
            }.joinToString("").trim(' ', '.').ifBlank { "Download" }
            if (clean.toByteArray().size <= 240) return clean
            val extension = clean.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
            val stem = if (extension == null) clean else clean.substringBeforeLast('.')
            var end = minOf(stem.length, 220)
            while (stem.substring(0, end).toByteArray().size > 220) end--
            if (end < stem.length && stem[end].isLowSurrogate()) end--
            return stem.substring(0, end).trimEnd(' ', '.') + (extension?.let { ".$it" } ?: "")
        }

    val title get() = when (mediaKind(mime, name)) {
        MediaKind.Photo, MediaKind.Svg, MediaKind.Gif, MediaKind.AnimatedWebP -> "Download image"
        MediaKind.Tgs -> "Download sticker"
        MediaKind.Audio -> "Download audio"
        MediaKind.Video -> "Download video"
        null -> "Download file"
    }

    val contentType: String
        get() {
            val declared = mime.substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
            if (declared.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")) &&
                declared != "application/octet-stream") return declared
            val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            if (extension == "opus") return "audio/ogg"
            if (extension == "tgs") return "application/x-tgsticker"
            if (extension == "svg") return "image/svg+xml"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }

    fun createDocument() = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(contentType)
        .putExtra(Intent.EXTRA_TITLE, filename)

    companion object {
        fun attachment(record: Record): MediaDownload {
            val data = record.data()
            return MediaDownload(data.optString("name", "Shared file"), data.optString("mime"), attachment = record.key)
        }
    }
}

internal val LocalMediaDownload = staticCompositionLocalOf<((MediaDownload) -> Unit)?> { null }

internal suspend fun writeMediaDownload(
    resolver: ContentResolver, request: MediaDownload, newDocument: Uri?, openSource: suspend () -> InputStream
) {
    val operation = currentCoroutineContext()
    var target = newDocument
    try {
        operation.ensureActive()
        openSource().use { input ->
            operation.ensureActive()
            val uri = newDocument ?: checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, request.filename)
                    put(MediaStore.Downloads.MIME_TYPE, request.contentType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                })) { "Couldn’t create a file in Downloads." }
            target = uri
            checkNotNull(resolver.openOutputStream(uri, "wt")) { "Couldn’t open the save location." }.use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    operation.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    operation.ensureActive()
                    output.write(buffer, 0, count)
                }
            }
        }
        operation.ensureActive()
        if (newDocument == null) {
            check(resolver.update(checkNotNull(target), ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null, null) == 1) { "Couldn’t finish saving to Downloads." }
        }
    } catch (failure: Exception) {
        target?.let { uri ->
            runCatching {
                if (newDocument == null) resolver.delete(uri, null, null)
                // ACTION_CREATE_DOCUMENT returns a new file, so a failed save can discard it.
                else DocumentsContract.deleteDocument(resolver, uri)
            }
        }
        throw failure
    }
}

@Composable
internal fun DownloadMediaDialog(
    request: MediaDownload,
    busy: Boolean,
    status: String?,
    close: () -> Unit,
    save: () -> Unit,
    choose: () -> Unit
) {
    P2pAlertDialog(
        onDismissRequest = { if (!busy) close() },
        icon = { Icon(Icons.Outlined.Download, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(request.title) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(request.filename, Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error) }
                if (!busy) OutlinedButton(onClick = choose, shape = P2pButtonShape, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose location")
                }
            }
        },
        confirmButton = { Button(onClick = save, shape = P2pButtonShape, enabled = !busy) { Text("Save to Downloads") } },
        dismissButton = { P2pTextButton(onClick = close) { Text("Cancel") } }
    )
}
