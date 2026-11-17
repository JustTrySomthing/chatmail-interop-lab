package app.p2p

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.random.Random
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
            assertNull(AttachmentCompressor(context).compress(original, draft))
            assertArrayEquals(bytes, original.readBytes())

            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            try {
                original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it)) }
                assertEquals(MediaKind.Photo, mediaKind("image/webp", "Test.webp", original))
            } finally {
                bitmap.recycle()
            }
        } finally {
            original.delete()
        }
    }

    @Test
    fun compressesLargeImageAndLeavesOriginalUntouched() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val original = File.createTempFile("p2p-test-image", ".png", context.cacheDir)
        val random = Random(17)
        val bitmap = Bitmap.createBitmap(IntArray(1024 * 1024) { random.nextInt() or 0xff000000.toInt() },
            1024, 1024, Bitmap.Config.ARGB_8888)
        var result: PreparedMedia? = null
        try {
            original.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val size = original.length()
            assertTrue(size > 1_000_000L)
            val media = AttachmentDraft(Uri.fromFile(original), "Test.png", "image/png", false)
            val compressor = AttachmentCompressor(context)
            assertNull(compressor.compress(original, media.copy(asFile = true)))
            assertTrue(MediaCompression.enabled(context))
            MediaCompression.setEnabled(context, false)
            assertFalse(MediaCompression.enabled(context))
            assertNull(compressor.compress(original, media))
            val clip = media.copy(mime = "video/mp4", range = MediaRange(0, 1_000))
            assertTrue(shouldPrepareMedia(clip, 900, automatic = false))
            MediaCompression.setEnabled(context, true)
            result = compressor.compress(original, media)
            assertNotNull(result)
            assertTrue(result!!.file.length() in 1..1_000_000L)
            assertNotNull(BitmapFactory.decodeFile(result!!.file.absolutePath)?.also { it.recycle() })
            assertEquals(size, original.length())
        } finally {
            bitmap.recycle()
            result?.file?.delete()
            original.delete()
        }
    }
}
