package app.p2p

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

internal enum class MediaKind(val animationLabel: String? = null) {
    Audio, Video, Photo, Gif("GIF"), AnimatedWebP("WebP")
}

internal fun isAnimatedWebP(file: File): Boolean = try {
    // Extended WebP starts with the VP8X chunk. Bit 1 of its feature byte indicates animation.
    val header = ByteArray(21)
    java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
    String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(header, 8, 8, Charsets.US_ASCII) == "WEBPVP8X" && (header[20].toInt() and 0x02) != 0
} catch (_: java.io.IOException) {
    false
}

private val audioExtensions = setOf("mp3", "m4a", "m4b", "aac", "wav", "wave", "ogg", "oga", "opus", "flac",
    "amr", "awb", "aif", "aiff", "alac", "wma", "ac3", "eac3", "mka", "mid", "midi", "ape", "au")
private val videoExtensions = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "3gpp", "3g2", "ts", "mts",
    "m2ts", "avi", "mpeg", "mpg", "vob", "wmv", "asf", "flv", "f4v", "ogv", "m2v")
private val imageExtensions = setOf("jpg", "jpeg", "jpe", "png", "webp", "heif", "heic", "avif", "bmp")
private val ambiguousMediaTypes = setOf("", "application/octet-stream", "binary/octet-stream",
    "application/ogg", "application/x-ogg", "application/mp4", "application/x-matroska",
    "application/mxf", "application/x-mpegurl")

internal fun mediaKind(mime: String, name: String, local: File? = null): MediaKind? {
    val type = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val kind = when {
        type == "image/svg+xml" || extension == "svg" -> null
        type.startsWith("video/") -> MediaKind.Video
        type.startsWith("audio/") -> MediaKind.Audio
        type == "image/gif" -> MediaKind.Gif
        extension == "gif" && (type.startsWith("image/") || type in ambiguousMediaTypes) -> MediaKind.Gif
        type.startsWith("image/") -> MediaKind.Photo
        type !in ambiguousMediaTypes -> null
        extension in audioExtensions -> MediaKind.Audio
        extension in videoExtensions -> MediaKind.Video
        extension in imageExtensions -> MediaKind.Photo
        type == "application/ogg" || type == "application/x-ogg" -> MediaKind.Audio
        type in setOf("application/mp4", "application/x-matroska", "application/mxf") -> MediaKind.Video
        else -> null
    }
    return if (kind == MediaKind.Photo && local != null && isAnimatedWebP(local)) MediaKind.AnimatedWebP else kind
}

internal data class MediaPage(val id: String, val file: File, val title: String, val kind: MediaKind)

@Composable
internal fun Modifier.mediaLongPress(label: String, action: () -> Unit): Modifier {
    val latest by rememberUpdatedState(action)
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val held = awaitLongPressOrCancellation(down.id)
            if (held != null) {
                held.consume()
                latest()
            }
        }
    }.semantics { onLongClick(label) { latest(); true } }
}

@Composable
internal fun MediaDialog(
    pages: List<MediaPage>, selected: String, close: () -> Unit, feedback: String?, download: (String) -> Unit
) {
    if (pages.isEmpty()) return
    val pager = rememberPagerState(initialPage = pages.indexOfFirst { it.id == selected }.coerceAtLeast(0)) { pages.size }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                val current = pages[pager.currentPage.coerceIn(pages.indices)]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Close media") }
                    Text(current.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (pages.size > 1) Text("${pager.currentPage + 1} / ${pages.size}")
                    IconButton(onClick = { download(current.id) }) { Icon(Icons.Outlined.Download, "Download media") }
                }
                feedback?.let { Text(it, Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                    color = MaterialTheme.colorScheme.primary) }
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()
                    .semantics { contentDescription = "Media pages" }, key = { pages[it].id }) { index ->
                    val page = pages[index]
                    val save = { download(page.id) }
                    val longPress = Modifier.mediaLongPress("Download media", save)
                    Box(Modifier.fillMaxSize().then(if (page.kind == MediaKind.Photo || page.kind == MediaKind.Video) Modifier else longPress),
                        contentAlignment = Alignment.Center) {
                        when (page.kind) {
                            MediaKind.Audio, MediaKind.Video -> if (index == pager.settledPage) {
                                LocalPlayer(page.file, page.kind == MediaKind.Video, save)
                            }
                            MediaKind.Photo -> ZoomPhoto(page.file, page.title, save)
                            MediaKind.Gif, MediaKind.AnimatedWebP -> if (index == pager.settledPage) {
                                InlineAnimatedImage(page.file, page.title, fullScreen = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal val LocalInlineAudio = staticCompositionLocalOf<MutableState<String?>?> { null }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun InlineAudioAttachment(
    file: File, playbackKey: String, label: String, longPress: (() -> Unit)?, longPressLabel: String
) {
    val shared = LocalInlineAudio.current
    val selected = shared ?: remember { mutableStateOf<String?>(null) }
    DisposableEffect(playbackKey, selected) {
        onDispose { if (selected.value == playbackKey) selected.value = null }
    }
    if (selected.value == playbackKey) {
        LocalPlayer(file, false, {}, inlineAudioLabel = label, audioOptions = longPress,
            audioOptionsLabel = longPressLabel)
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Box(Modifier.size(48.dp).combinedClickable(onClickLabel = "Play audio", onClick = { selected.value = playbackKey },
                    onLongClickLabel = longPressLabel, onLongClick = longPress), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PlayArrow, "Play audio")
                }
            }
            Text(label, Modifier.weight(1f).combinedClickable(onClick = { selected.value = playbackKey },
                onLongClickLabel = longPressLabel, onLongClick = longPress), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun LocalPlayer(
    file: File, video: Boolean, download: () -> Unit, inlineAudioLabel: String? = null,
    audioOptions: (() -> Unit)? = null, audioOptionsLabel: String = "Download audio"
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember(file) { mutableStateOf<ExoPlayer?>(null) }
    var failed by remember(file) { mutableStateOf(false) }
    var attempt by remember(file) { mutableIntStateOf(0) }
    DisposableEffect(file, attempt, lifecycle) {
        val instance = ExoPlayer.Builder(context).setUsePlatformDiagnostics(false).build()
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { failed = true }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) instance.pause()
        }
        lifecycle.addObserver(observer)
        instance.addListener(listener)
        instance.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(if (video) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        instance.setHandleAudioBecomingNoisy(true)
        instance.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        instance.prepare()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) instance.play()
        player = instance
        onDispose {
            lifecycle.removeObserver(observer)
            instance.removeListener(listener)
            instance.release()
        }
    }
    if (failed) {
        Column(Modifier.padding(if (inlineAudioLabel == null) 24.dp else 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            inlineAudioLabel?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text("This file couldn’t be played on this device.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                P2pTextButton(onClick = { failed = false; attempt++ }) { Text("Try again") }
                audioOptions?.let { P2pTextButton(onClick = it) { Text(audioOptionsLabel) } }
            }
        }
    } else {
        player?.let { instance ->
            if (video) {
                val save by rememberUpdatedState(download)
                AndroidView(factory = { host ->
                    object : PlayerView(host) {
                        private var longPressed = false
                        private val gestures = GestureDetector(host, object : GestureDetector.SimpleOnGestureListener() {
                            override fun onDown(e: MotionEvent) = true
                            override fun onLongPress(e: MotionEvent) {
                                longPressed = true
                                val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                                try { cancelPlayerTouch(cancel) } finally { cancel.recycle() }
                                save()
                            }
                        })

                        private fun cancelPlayerTouch(event: MotionEvent) { super.dispatchTouchEvent(event) }

                        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) longPressed = false
                            gestures.onTouchEvent(event)
                            return if (longPressed) true else super.dispatchTouchEvent(event)
                        }
                    }.apply { setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) }
                }, modifier = Modifier.fillMaxSize().semantics { onLongClick("Download video") { save(); true } },
                    update = { it.player = instance }, onRelease = { it.player = null })
            } else AudioControls(instance, inlineAudioLabel, audioOptions, audioOptionsLabel)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AudioControls(player: Player, inlineLabel: String?, options: (() -> Unit)?, optionsLabel: String) {
    var position by remember(player) { mutableLongStateOf(0L) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var playing by remember(player) { mutableStateOf(false) }
    var requested by remember(player) { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            delay(200)
        }
    }
    val fraction = requested ?: (position.toDouble() / duration.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f)
    val toggle = {
        if (playing) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        playing = player.playWhenReady
    }
    val progress: @Composable () -> Unit = {
        Slider(value = fraction, enabled = duration > 0L && player.isCurrentMediaItemSeekable,
            onValueChange = { requested = it },
            onValueChangeFinished = {
                requested?.let { position = (duration * it.toDouble()).toLong(); player.seekTo(position) }
                requested = null
            })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(playbackTime((duration * fraction.toDouble()).toLong()), style = MaterialTheme.typography.labelMedium)
            Text(playbackTime(duration), style = MaterialTheme.typography.labelMedium)
        }
    }
    if (inlineLabel == null) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.AudioFile, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            progress()
            Button(onClick = toggle) { Text(if (playing) "Pause" else "Play") }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val action = if (playing) "Pause audio" else "Play audio"
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Box(Modifier.size(48.dp).combinedClickable(onClickLabel = action, onClick = toggle,
                    onLongClickLabel = optionsLabel, onLongClick = options), contentAlignment = Alignment.Center) {
                    Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, action)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(inlineLabel, Modifier.combinedClickable(onClick = toggle,
                    onLongClickLabel = optionsLabel, onLongClick = options),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                progress()
            }
        }
    }
}

private fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val suffix = (seconds % 60).toString().padStart(2, '0')
    return if (hours == 0L) "$minutes:$suffix" else "$hours:${(minutes % 60).toString().padStart(2, '0')}:$suffix"
}

@Composable
private fun ZoomPhoto(file: File, title: String, download: () -> Unit) {
    val decoded by produceState<Result<Bitmap>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val size = info.size
                    val scale = minOf(1f, 3000f / maxOf(size.width, size.height))
                    decoder.setTargetSize((size.width * scale).roundToInt().coerceAtLeast(1),
                        (size.height * scale).roundToInt().coerceAtLeast(1))
                }
            }
        }
    }
    val image = decoded?.getOrNull()
    if (image == null) {
        if (decoded == null) CircularProgressIndicator() else Text("This photo couldn’t be opened.")
        return
    }
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    val save by rememberUpdatedState(download)
    Box(Modifier.fillMaxSize().clipToBounds()
        .semantics { onLongClick("Download image") { save(); true } }
        .pointerInput(file) {
            detectTapGestures(onLongPress = { save() }, onDoubleTap = {
                scale = if (scale > 1f) 1f else 2.5f
                offset = Offset.Zero
            })
        }
        .pointerInput(file) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) break
                    // At normal scale, leave a one-finger drag for the gallery pager.
                    if (scale <= 1f && event.changes.count { it.pressed } < 2) continue
                    val centroid = event.calculateCentroid(useCurrent = false)
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    if (centroid == Offset.Unspecified || (pan == Offset.Zero && zoom == 1f)) continue
                    val next = (scale * zoom).coerceIn(1f, 5f)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val fitted = minOf(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
                    val limitX = ((image.width * fitted * next - size.width) / 2f).coerceAtLeast(0f)
                    val limitY = ((image.height * fitted * next - size.height) / 2f).coerceAtLeast(0f)
                    val anchor = centroid - center
                    val proposed = (offset - anchor) * (next / scale) + anchor + pan
                    offset = Offset(proposed.x.coerceIn(-limitX, limitX), proposed.y.coerceIn(-limitY, limitY))
                    scale = next
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }, contentAlignment = Alignment.Center) {
        Image(remember(image) { image.asImageBitmap() }, title, Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        })
    }
}


internal fun shouldFetchInlineMedia(data: org.json.JSONObject): Boolean =
    !data.optBoolean("listed") && data.optLong("size", -1) in 0..CHATMAIL_FILE_LIMIT &&
        mediaKind(data.optString("mime"), data.optString("name")) != null
