package app.p2p

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal data class MediaRange(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && endMs > startMs) }
}

internal data class AttachmentDraft(
    val uri: Uri,
    val label: String,
    val mime: String,
    val asFile: Boolean,
    val range: MediaRange? = null,
    val temporary: Boolean = false
) {
    val canTrim get() = !asFile && (mime.startsWith("audio/") || mime.startsWith("video/"))
}

internal fun describeAttachment(context: Context, uri: Uri, asFile: Boolean, label: String? = null, mime: String? = null): AttachmentDraft {
    val name = label ?: runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "Attachment"
    val providerType = mime ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
    return AttachmentDraft(uri, attachmentLabel(name), attachmentMime(name, providerType), asFile)
}

internal fun attachmentLabel(name: String): String {
    val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..16 } ?: return name.take(180)
    return name.substringBeforeLast('.').take(179 - extension.length) + "." + extension
}

internal fun attachmentMime(name: String, reportedType: String?): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    val type = reportedType?.substringBefore(';')?.trim()?.lowercase()
    val inferred = when (extension) {
        "vcf" -> return "text/x-vcard"
        "gif" -> return "image/gif"
        "webp" -> return "image/webp"
        "xdc" -> return WEBXDC_MIME
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }
    val generic = type.isNullOrBlank() || type.endsWith("/*") ||
        type in setOf("application/octet-stream", "binary/octet-stream", "application/mp4", "application/x-matroska")
    return if (generic) inferred ?: type.orEmpty().takeUnless { it.isEmpty() || it.endsWith("/*") }
        ?: "application/octet-stream" else checkNotNull(type)
}

internal enum class OutgoingMediaQuality(val coreValue: String, val voiceBitrate: Int) {
    Balanced("0", 32_000),
    DataSaver("1", 24_000);

    companion object {
        fun fromCore(value: Any?) = if (value == "1") DataSaver else Balanced
    }
}

internal data class OutgoingMediaPolicy(
    val quality: OutgoingMediaQuality = OutgoingMediaQuality.Balanced,
    val recommendedVideoBytes: Long = 25L * 1024 * 1024
)

internal data class VideoSource(
    val durationMs: Long, val width: Int, val height: Int,
    val mime: String, val bitrate: Long, val hasAudio: Boolean
)

internal enum class VideoPreparation { Original, Compatible, Recode, TooLarge }

// Delta Chat Android v2.69.0 uses the Core recommendation for the output budget,
// and forces an H.264 pass for VP8/VP9 sent as video.
internal fun prepareVideo(
    source: VideoSource?, bytes: Long, policy: OutgoingMediaPolicy, trimmedMs: Long? = null
): VideoPreparation {
    val oversized = bytes > policy.recommendedVideoBytes
    if (source == null || source.durationMs <= 0) {
        return if (oversized) VideoPreparation.TooLarge else VideoPreparation.Original
    }
    val bitrate = source.bitrate.takeIf { it > 0 } ?: (bytes * 8 / (source.durationMs / 1000).coerceAtLeast(1))
    val overBudget = oversized || bitrate > 3_000_000
    val saveData = policy.quality == OutgoingMediaQuality.DataSaver
    val compat = source.mime in setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9")
    if (!saveData && !compat && !overBudget && trimmedMs == null) return VideoPreparation.Original
    val allowedSeconds = (policy.recommendedVideoBytes + policy.recommendedVideoBytes / 4) / 25_000
    if ((trimmedMs ?: source.durationMs) / 1000 > allowedSeconds) {
        return VideoPreparation.TooLarge
    }
    return if (compat && !saveData && !overBudget) VideoPreparation.Compatible else VideoPreparation.Recode
}

internal data class VideoPass(val width: Int, val height: Int, val bitrate: Int)

internal fun videoPass(source: VideoSource, low: Boolean, compatibleOnly: Boolean): VideoPass {
    val limit = if (low) 480 else 640
    val longest = maxOf(source.width, source.height)
    val scale = if (compatibleOnly || longest <= limit) 1.0 else limit.toDouble() / longest
    val width = (source.width * scale).roundToInt().and(-2).coerceAtLeast(2)
    val height = (source.height * scale).roundToInt().and(-2).coerceAtLeast(2)
    val totalBitrate = if (low) 200_000 else 800_000
    return VideoPass(width, height, totalBitrate - if (source.hasAudio) 96_000 else 0)
}

internal data class PreparedMedia(val file: File, val mime: String, val extension: String)

internal fun needsMediaPreparation(media: AttachmentDraft): Boolean =
    when (chatmailAttachmentViewType(media.mime, media.asFile, name = media.label)) {
        "Video" -> true
        "Audio" -> media.range != null
        else -> false
    }

@OptIn(UnstableApi::class)
internal class AttachmentCompressor(private val context: Context) {
    suspend fun compress(input: File, media: AttachmentDraft, policy: OutgoingMediaPolicy): PreparedMedia? {
        // Core handles photo compression. Files, GIFs, Webxdc packages and ordinary
        // audio attachments use their source bytes, including when Data saver is selected.
        if (!needsMediaPreparation(media)) return null
        if (media.mime.startsWith("audio/")) return export(input, media, null, hasAudio = true)

        val source = probeVideo(input)
        val duration = source?.durationMs
        val trim = media.range
        if (trim != null && (duration == null || trim.startMs >= duration || trim.endMs > duration + 1000)) return null
        val clipped = trim?.let { minOf(it.endMs, checkNotNull(duration)) - it.startMs }
        val preparation = prepareVideo(source, input.length(), policy, clipped)
        val message = "This video couldn’t be compressed to a suitable size. Try a shorter clip."
        if (preparation == VideoPreparation.Original) return null
        check(preparation != VideoPreparation.TooLarge) { message }
        val video = checkNotNull(source)
        val compatibleOnly = preparation == VideoPreparation.Compatible
        for (low in listOf(false, true)) {
            val result = export(input, media, videoPass(video, low, compatibleOnly), video.hasAudio,
                endMs = duration)
            if (result == null) {
                check(input.length() <= policy.recommendedVideoBytes) { message }
                return null
            }
            if (result.file.length() <= policy.recommendedVideoBytes) return result
            result.file.delete()
        }
        error(message)
    }

    private fun probeVideo(input: File): VideoSource? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val tracks = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = tracks.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return null
            val duration = video.getLong(MediaFormat.KEY_DURATION, 0) / 1000
            val width = video.getInteger(MediaFormat.KEY_WIDTH)
            val height = video.getInteger(MediaFormat.KEY_HEIGHT)
            if (width <= 0 || height <= 0) return null
            val rotation = video.getInteger(MediaFormat.KEY_ROTATION, 0).mod(360)
            val portrait = rotation == 90 || rotation == 270
            var bitrate = 0L
            var durationMs = duration
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(input.absolutePath)
                    bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0
                    if (durationMs <= 0) {
                        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    }
                }
            }
            VideoSource(durationMs, if (portrait) height else width, if (portrait) width else height,
                video.getString(MediaFormat.KEY_MIME).orEmpty(), bitrate,
                tracks.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true })
        } finally {
            extractor.release()
        }
    }.getOrNull()

    private suspend fun export(
        input: File, media: AttachmentDraft, video: VideoPass?, hasAudio: Boolean, endMs: Long? = null
    ): PreparedMedia? {
        val suffix = if (video == null) "m4a" else "mp4"
        val clip = MediaItem.ClippingConfiguration.Builder().apply {
            media.range?.let {
                setStartPositionMs(it.startMs)
                setEndPositionMs(endMs?.let { duration -> minOf(duration, it.endMs) } ?: it.endMs)
            }
        }.build()
        val item = MediaItem.Builder().setUri(Uri.fromFile(input)).setClippingConfiguration(clip).build()
        val output = File.createTempFile("p2p-media-", ".$suffix", context.cacheDir)
        var keep = false
        try {
            val modes = if (video == null) listOf(Composition.HDR_MODE_KEEP_HDR) else listOf(
                Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
                Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR
            )
            for (mode in modes) {
                output.delete()
                val failure = exportPass(item, video, hasAudio, mode, output, media.range == null)
                if (failure == null && output.length() > 0) {
                    keep = true
                    return PreparedMedia(output, if (video == null) "audio/mp4" else "video/mp4", suffix)
                }
                if (failure == ExportException.ERROR_CODE_MUXING_TIMEOUT) break
            }
            return null
        } finally {
            if (!keep) output.delete()
        }
    }

    private suspend fun exportPass(
        item: MediaItem, video: VideoPass?, hasAudio: Boolean, hdr: Int, output: File, flatten: Boolean
    ): Int? = withTimeoutOrNull(12 * 60_000L) {
        withContext(Dispatchers.Main.immediate) {
            var transformer: Transformer? = null
            try {
                suspendCancellableCoroutine<Int> { ready ->
                    try {
                        val edited = EditedMediaItem.Builder(item)
                            .setRemoveVideo(video == null)
                            .setRemoveAudio(!hasAudio)
                            .setFlattenForSlowMotion(video != null && flatten)
                            .setEffects(Effects(emptyList(), video?.let {
                                listOf(Presentation.createForWidthAndHeight(it.width, it.height, Presentation.LAYOUT_SCALE_TO_FIT))
                            }.orEmpty()))
                            .build()
                        val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
                            .setHdrMode(hdr)
                            .build()
                        val exporter = Transformer.Builder(context).apply {
                            setAudioMimeType(MimeTypes.AUDIO_AAC)
                            if (video != null) {
                                setVideoMimeType(MimeTypes.VIDEO_H264)
                                setPortraitEncodingEnabled(true)
                                setMaxDelayBetweenMuxerSamplesMs(60_000)
                                setEncoderFactory(DefaultEncoderFactory.Builder(context)
                                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(96_000).build())
                                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(video.bitrate)
                                        .setiFrameIntervalSeconds(2f).build())
                                    .setEnableFallback(true)
                                    .build())
                            }
                            addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (ready.isActive) ready.resume(0)
                                }
                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (ready.isActive) ready.resume(exportException.errorCode)
                                }
                            })
                        }.build()
                        transformer = exporter
                        exporter.start(composition, output.absolutePath)
                    } catch (_: RuntimeException) {
                        if (ready.isActive) ready.resume(-1)
                    }
                }
            } catch (cancelled: CancellationException) {
                runCatching { transformer?.cancel() }
                throw cancelled
            }
        }
    }.let { status -> if (status == 0) null else status ?: ExportException.ERROR_CODE_MUXING_TIMEOUT }
}
