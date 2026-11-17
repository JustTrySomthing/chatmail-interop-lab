package app.p2p

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal fun exampleAnimatedWebP(): ByteArray = Base64.getDecoder().decode(
    "UklGRoQAAABXRUJQVlA4WAoAAAACAAAADwAADwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAA8AAA8AAJYAAAJWUDhMDwAAAC8PwAMABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAA8AAA8AAJYAAABWUDhMDwAAAC8PwAMABxDR//4HIqL/AQA="
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentCompressionTest {
    @Test
    fun recognizesSharedVideosWithGenericMimeTypesAndPreservesExtensions() {
        assertEquals("video/quicktime", attachmentMime("clip.MOV", "application/octet-stream"))
        assertEquals("video/webm", attachmentMime("clip.webm", "video/*"))
        assertEquals("audio/webm", attachmentMime("recording.webm", "AUDIO/WEBM; codecs=opus"))
        assertEquals(WEBXDC_MIME, attachmentMime("miniapp.xdc", "application/zip"))
        val longName = attachmentLabel("a".repeat(190) + ".webm")
        assertEquals(180, longName.length)
        assertTrue(longName.endsWith(".webm"))
        assertTrue(needsMediaPreparation(AttachmentDraft(Uri.EMPTY, longName, attachmentMime(longName, null), false)))
    }

    @Test
    fun keepsLargeAnimatedWebPBytesAndRoutesThemToAnimationPlayback() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val original = File.createTempFile("p2p-test-animation", ".webp", context.cacheDir)
        val animation = exampleAnimatedWebP()
        val bytes = ByteBuffer.allocate(animation.size + 8 + 1_000_000).order(ByteOrder.LITTLE_ENDIAN)
            .put(animation).put("JUNK".toByteArray()).putInt(1_000_000).apply { putInt(4, capacity() - 8) }.array()
        try {
            original.writeBytes(animation)
            assertTrue(ImageDecoder.decodeDrawable(ImageDecoder.createSource(original)) is AnimatedImageDrawable)
            original.writeBytes(bytes)
            assertEquals(MediaKind.AnimatedWebP, mediaKind("application/octet-stream", "Test.webp", original))
            val draft = describeAttachment(context, Uri.fromFile(original), asFile = false)
            assertEquals("image/webp", draft.mime)
            assertNull(AttachmentCompressor(context).compress(original, draft, OutgoingMediaPolicy()))
            assertArrayEquals(bytes, original.readBytes())

        } finally {
            original.delete()
        }
    }

    @Test
    fun leavesOriginalFilesAndCoreImagesToTheirSendPaths() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val original = File.createTempFile("p2p-test-original", ".dat", context.cacheDir)
        val source = byteArrayOf(1, 2, 3, 4)
        original.writeBytes(source)
        try {
            val compressor = AttachmentCompressor(context)
            val policy = OutgoingMediaPolicy(OutgoingMediaQuality.DataSaver)
            for ((name, mime) in listOf(
                "photo.jpg" to "image/jpeg", "song.mp3" to "audio/mpeg", "doc.pdf" to "application/pdf",
                "app.xdc" to WEBXDC_MIME, "clip.mp4" to "video/mp4"
            )) {
                val draft = AttachmentDraft(Uri.fromFile(original), name, mime, asFile = name == "clip.mp4")
                assertNull(compressor.compress(original, draft, policy))
                assertArrayEquals(source, original.readBytes())
            }
            val audio = AttachmentDraft(Uri.fromFile(original), "voice.m4a", "audio/mp4", false, MediaRange(0, 1000))
            assertTrue(needsMediaPreparation(audio))
            assertFalse(needsMediaPreparation(audio.copy(asFile = true)))
        } finally {
            original.delete()
        }
    }

    @Test
    fun usesDeltaChatVideoThresholdsAndCompatibilityPasses() {
        val base = VideoSource(12_000, 1920, 1080, "video/avc", 2_400_000, true)
        val balanced = OutgoingMediaPolicy(recommendedVideoBytes = 5_000_000)
        assertEquals(VideoPreparation.Original, prepareVideo(base, 4_000_000, balanced))
        assertEquals(VideoPreparation.Recode, prepareVideo(base, 5_000_001, balanced))
        assertEquals(VideoPreparation.Recode, prepareVideo(base.copy(bitrate = 3_000_001), 4_000_000, balanced))
        assertEquals(VideoPreparation.Recode, prepareVideo(base, 400_000,
            balanced.copy(quality = OutgoingMediaQuality.DataSaver)))
        assertEquals(VideoPreparation.Compatible, prepareVideo(base.copy(mime = "video/x-vnd.on2.vp9"), 4_000_000, balanced))
        assertEquals(VideoPreparation.TooLarge, prepareVideo(base.copy(durationMs = 300_000), 8_000_000, balanced))
        assertEquals(VideoPreparation.Recode, prepareVideo(base.copy(durationMs = 300_000), 8_000_000, balanced,
            trimmedMs = 10_000))
        assertEquals(VideoPass(640, 360, 704_000), videoPass(base, low = false, compatibleOnly = false))
        assertEquals(VideoPass(480, 270, 104_000), videoPass(base, low = true, compatibleOnly = false))
        assertEquals(VideoPass(1920, 1080, 704_000), videoPass(base, low = false, compatibleOnly = true))
    }
}
