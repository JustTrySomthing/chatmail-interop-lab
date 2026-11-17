package app.p2p

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
    val inferred = when (name.substringAfterLast('.', "").lowercase()) {
        "vcf" -> "text/x-vcard"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "m4a" -> "audio/mp4"
        "mp4" -> "video/mp4"
        "jpg", "jpeg" -> "image/jpeg"
        else -> null
    }
    val type = mime ?: if (inferred in setOf("text/x-vcard", "image/gif", "image/webp")) inferred else
        context.contentResolver.getType(uri)?.takeUnless { it == "application/octet-stream" } ?: inferred
    return AttachmentDraft(uri, name.take(180), type ?: "application/octet-stream", asFile)
}

internal object MediaCompression {
    private fun preferences(context: Context) = context.getSharedPreferences("media-compression", Context.MODE_PRIVATE)

    fun enabled(context: Context) = preferences(context).getBoolean("automatic", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean("automatic", enabled).apply()
    }
}

internal fun shouldPrepareMedia(media: AttachmentDraft, bytes: Long, automatic: Boolean): Boolean =
    !media.asFile && media.mime != "image/gif" &&
        (media.range != null || automatic && bytes > 1_000_000L)

internal data class CompressionRates(val audio: Int, val video: Int)
internal fun ratesForMedia(durationMs: Long, video: Boolean): CompressionRates {
    val bitsPerSecond = 8_000_000L * 1_000 / durationMs.coerceAtLeast(1_000)
    val audio = if (video) 64_000 else bitsPerSecond.coerceIn(48_000, 96_000).toInt()
    return CompressionRates(audio, (bitsPerSecond - audio).coerceIn(320_000, 1_400_000).toInt())
}

internal data class PreparedMedia(val file: File, val mime: String, val extension: String)

@OptIn(UnstableApi::class)
internal class AttachmentCompressor(private val context: Context) {
    suspend fun compress(input: File, media: AttachmentDraft): PreparedMedia? {
        val automatic = MediaCompression.enabled(context)
        if (!shouldPrepareMedia(media, input.length(), automatic)) return null
        return when {
            media.mime.startsWith("image/") && !isAnimatedWebP(input) -> shrinkImage(input)
            media.mime.startsWith("video/") || media.mime.startsWith("audio/") -> shrinkMotion(input, media, automatic)
            else -> null
        }
    }

    private fun shrinkImage(input: File): PreparedMedia? {
        var bitmap: Bitmap? = null
        var output: File? = null
        try {
            var animated = false
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(input)) { decoder, info, _ ->
                animated = info.isAnimated
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = (maxOf(info.size.width, info.size.height) / 1_600.0).coerceAtLeast(1.0)
                decoder.setTargetSize((info.size.width / scale).toInt().coerceAtLeast(1), (info.size.height / scale).toInt().coerceAtLeast(1))
            }
            val image = bitmap
            if (animated) return null
            val alpha = image.hasAlpha()
            val format = if (alpha) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
            output = File.createTempFile("p2p-image-", if (alpha) ".webp" else ".jpg", context.cacheDir)
            for (quality in listOf(85, 73, 61, 49, 38)) {
                if (!output.outputStream().use { image.compress(format, quality, it) }) return null
                if (output.length() <= 1_000_000L) break
            }
            if (output.length() in 1 until input.length()) {
                val prepared = PreparedMedia(output, if (alpha) "image/webp" else "image/jpeg", if (alpha) "webp" else "jpg")
                output = null
                return prepared
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            bitmap?.recycle()
            output?.delete()
        }
    }

    private suspend fun shrinkMotion(input: File, media: AttachmentDraft, automatic: Boolean): PreparedMedia? {
        val video = media.mime.startsWith("video/")
        val info = runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(input.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 480
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: height
                duration to if (rotation == 90 || rotation == 270) width else height
            }
        }.getOrNull()
        if (info == null || info.first <= 0L) return null
        val range = media.range
        if (range != null && (range.startMs >= info.first || range.endMs > info.first + 1000)) return null
        val clip = MediaItem.ClippingConfiguration.Builder().apply {
            if (range != null) {
                setStartPositionMs(range.startMs)
                setEndPositionMs(minOf(range.endMs, info.first))
            }
        }.build()
        val item = MediaItem.Builder().setUri(Uri.fromFile(input)).setClippingConfiguration(clip).build()
        val effects = if (automatic) {
            val samples = SonicAudioProcessor().apply { setOutputSampleRateHz(32_000) }
            Effects(listOf(samples), if (video) listOf(Presentation.createForHeight(info.second.coerceIn(2, 480))) else emptyList())
        } else Effects(emptyList(), emptyList())
        val edited = EditedMediaItem.Builder(item).setRemoveVideo(!video).setEffects(effects).build()
        val rates = ratesForMedia(if (range == null) info.first else minOf(range.endMs, info.first) - range.startMs, video)
        val suffix = if (video) ".mp4" else ".m4a"
        val output = File.createTempFile("p2p-media-", suffix, context.cacheDir).apply { delete() }
        var keep = false
        try {
            val completed = withTimeoutOrNull(12 * 60_000L) {
                withContext(Dispatchers.Main.immediate) {
                    suspendCancellableCoroutine { ready ->
                        val builder = Transformer.Builder(context)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .apply {
                                if (automatic) setEncoderFactory(DefaultEncoderFactory.Builder(context)
                                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(rates.audio).build())
                                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(rates.video).build())
                                    .build())
                            }
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (ready.isActive) ready.resume(true)
                                }
                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    if (ready.isActive) ready.resume(false)
                                }
                            })
                        if (video) builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                        val transformer = builder.build()
                        ready.invokeOnCancellation {
                            android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel() }
                        }
                        runCatching { transformer.start(edited, output.absolutePath) }
                            .onFailure { if (ready.isActive) ready.resume(false) }
                    }
                }
            } == true
            if (!completed || output.length() <= 0 || (range == null && output.length() >= input.length())) return null
            keep = true
            return PreparedMedia(output, if (video) "video/mp4" else "audio/mp4", suffix.removePrefix("."))
        } finally {
            if (!keep) output.delete()
        }
    }
}
