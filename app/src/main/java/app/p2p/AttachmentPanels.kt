package app.p2p

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun AudioCapturePanel(model: P2pModel, limitSeconds: Int? = null, recorded: (File) -> Unit) {
    val context = LocalContext.current
    val quality by produceState<OutgoingMediaQuality?>(null, model) {
        value = runCatching { model.outgoingMediaQuality() }.getOrDefault(OutgoingMediaQuality.Balanced)
    }
    var current by remember { mutableStateOf<Pair<MediaRecorder, File>?>(null) }
    var started by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf("") }
    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            current?.let { (recorder, file) ->
                current = null
                started = 0L
                runCatching { recorder.release() }
                file.delete()
            }
        }
    }
    fun finish() {
        val (recorder, file) = current ?: return
        current = null
        started = 0L
        val ok = runCatching { recorder.stop(); file.length() > 0L }.getOrDefault(false)
        runCatching { recorder.release() }
        if (ok) recorded(file) else {
            file.delete()
            error = "The recording was too short or could not be saved."
        }
    }
    LaunchedEffect(started) {
        while (started != 0L) {
            val duration = SystemClock.elapsedRealtime() - started
            elapsed = duration / 1000
            delay(100)
        }
    }
    if (current != null) {
        val time = (elapsed / 60).toString() + ":" + (elapsed % 60).toString().padStart(2, '0')
        Text(if (limitSeconds == null) "Recording $time" else "Recording $time / 1:00")
        Button(onClick = ::finish) { Text(if (limitSeconds == null) "Stop and Add" else "Stop Recording") }
    } else {
        Text(if (limitSeconds == null) "Record an audio attachment for your draft."
            else "Record up to one minute.")
        Button(enabled = quality != null, onClick = {
            val directory = File(context.cacheDir, "captures").apply { mkdirs() }
            val output = runCatching { File.createTempFile("p2p-audio-", ".m4a", directory) }.getOrElse {
                error = "Couldn’t create an audio attachment."
                return@Button
            }
            val recorder = runCatching { MediaRecorder(context) }.getOrElse {
                output.delete()
                error = "The microphone couldn’t start."
                return@Button
            }
            runCatching {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioChannels(1)
                recorder.setAudioSamplingRate(44_100)
                recorder.setAudioEncodingBitRate(checkNotNull(quality).voiceBitrate)
                recorder.setOutputFile(output.absolutePath)
                if (limitSeconds != null) {
                    recorder.setMaxDuration(limitSeconds * 1_000)
                    recorder.setOnInfoListener { source, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED && current?.first === source) finish()
                    }
                }
                recorder.prepare()
                recorder.start()
            }.onSuccess {
                elapsed = 0L
                started = SystemClock.elapsedRealtime()
                current = recorder to output
                error = ""
            }.onFailure {
                runCatching { recorder.release() }
                output.delete()
                error = "The microphone couldn’t start."
            }
        }) { Text("Start Recording") }
    }
    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrimAttachmentPanel(attachment: AttachmentDraft, done: (MediaRange?) -> Unit) {
    val context = LocalContext.current
    val duration by produceState<Long?>(null, attachment.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(context, attachment.uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                }
            }.getOrDefault(0L)
        }
    }
    val length = duration
    if (length == null) { CircularProgressIndicator(); return }
    if (length < 250L) { Text("This media duration is unavailable."); return }

    var range by remember(length, attachment.range) {
        mutableStateOf((attachment.range?.startMs ?: 0L).toFloat()..(attachment.range?.endMs ?: length).toFloat())
    }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var audioPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf(false) }
    val isVideo = attachment.mime.startsWith("video/")
    fun stop() {
        runCatching { videoView?.pause() }
        runCatching { audioPlayer?.pause() }
        playing = false
    }
    DisposableEffect(attachment.uri) {
        onDispose {
            runCatching { videoView?.stopPlayback() }
            runCatching { audioPlayer?.release() }
        }
    }
    LaunchedEffect(playing) {
        if (playing) {
            delay((range.endInclusive - range.start).toLong().coerceAtLeast(1L))
            stop()
        }
    }
    if (isVideo) {
        androidx.compose.runtime.key(attachment.uri) {
            AndroidView(
                factory = { viewContext -> VideoView(viewContext).apply {
                    setVideoURI(attachment.uri)
                    setOnErrorListener { _, _, _ -> problem = true; playing = false; true }
                    videoView = this
                } },
                modifier = Modifier.fillMaxWidth().height(150.dp)
            )
        }
    }
    Text(formatClipTime(range.start.toLong()) + " – " + formatClipTime(range.endInclusive.toLong()))
    RangeSlider(value = range, valueRange = 0f..length.toFloat(), onValueChange = {
        stop()
        if (it.endInclusive - it.start >= 250f) range = it
    })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        P2pOutlinedButton(onClick = {
            if (playing) stop() else {
                runCatching {
                    if (isVideo) {
                        val player = checkNotNull(videoView)
                        player.seekTo(range.start.toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        player.start()
                    } else {
                        val player = audioPlayer ?: checkNotNull(MediaPlayer.create(context, attachment.uri)).also { audioPlayer = it }
                        player.seekTo(range.start.toLong(), MediaPlayer.SEEK_CLOSEST)
                        player.start()
                    }
                    playing = true
                    problem = false
                }.onFailure { problem = true }
            }
        }) { Text(if (playing) "Pause" else "Preview Selection") }
        Button(onClick = {
            stop()
            done(MediaRange(range.start.toLong(), range.endInclusive.toLong().coerceAtMost(length)))
        }) { Text("Use Trim") }
    }
    P2pTextButton(onClick = { stop(); done(null) }) { Text("Use Full Length") }
    if (problem) Text("This format couldn’t be previewed here.", color = MaterialTheme.colorScheme.error)
}

private fun formatClipTime(ms: Long): String {
    val seconds = ms / 1000
    return (seconds / 60).toString() + ":" + (seconds % 60).toString().padStart(2, '0')
}

internal fun escapeVCard(value: String): String = value.replace("\\", "\\\\").replace("\r", "")
    .replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")

@Composable
internal fun VCardPreview(file: File) {
    val fields by produceState(emptyList<String>(), file) {
        value = withContext(Dispatchers.IO) {
            if (file.length() !in 1..65_536) emptyList() else runCatching {
                val entries = file.readLines().mapNotNull { line ->
                    val property = line.substringBefore(':').substringBefore(';').uppercase()
                    val content = line.substringAfter(':', "").trim()
                    if (content.isNotEmpty() && property in setOf("FN", "TEL", "EMAIL", "X-P2P-ADDRESS")) {
                        property to content.replace("\\,", ",").replace("\\;", ";").take(180)
                    } else null
                }
                val name = entries.firstOrNull { it.first == "FN" }?.second
                listOfNotNull(name) + entries.filter { it.first != "FN" }.map { it.second }.take(3)
            }.getOrDefault(emptyList())
        }
    }
    if (fields.isNotEmpty()) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Contact card", style = MaterialTheme.typography.labelLarge)
                fields.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2) }
            }
        }
    }
}

@Composable
internal fun AttachmentDraftList(
    attachments: List<AttachmentDraft>, preparing: Boolean, removeAttachment: (Int) -> Unit,
    trimAttachment: (Int) -> Unit, maxHeight: Dp = 140.dp
) {
    if (attachments.isNotEmpty()) {
        val colors = MaterialTheme.colorScheme
        Surface(color = colors.surface) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
                .padding(horizontal = 12.dp)) {
                LazyColumn {
                    items(attachments.size) { index ->
                        val item = attachments[index]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AttachFile, null, Modifier.size(17.dp), tint = readableAccent(colors.surface, colors.onSurface))
                            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                                Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                Text(when {
                                    item.range != null -> "Trim selected"
                                    item.asFile -> "Original file"
                                    else -> "Attachment"
                                }, fontSize = 11.sp, color = colors.onSurfaceVariant)
                            }
                            if (item.canTrim) P2pTextButton(onClick = { trimAttachment(index) }, enabled = !preparing) { Text("Trim") }
                            IconButton(onClick = { removeAttachment(index) }, enabled = !preparing) { Icon(Icons.Outlined.Close, "Remove attachment") }
                        }
                    }
                }
            }
        }
    }
}
