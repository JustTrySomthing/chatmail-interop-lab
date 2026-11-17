package app.p2p

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaRoutingTest {
    @Test
    fun groupRosterPagesMustAgreeOnMetadataBeforeTheyCanBeCombined() {
        val first = obj("part" to 0, "parts" to 2, "name" to "Family", "secret" to "shared")
        val second = obj("part" to 1, "parts" to 2, "name" to "Family", "secret" to "shared")
        assertTrue(groupPageMatches(second, first))
        assertFalse(groupPageMatches(second.put("name", "Other"), first))
        assertFalse(groupPageMatches(second.put("name", "Family").put("secret", "other"), first))
        assertFalse(groupPageMatches(second.put("secret", "shared").put("parts", 3), first))
    }

    @Test
    fun resolvesMediaWithMimeOrWithAnExtensionWhenTheProviderDoesNotKnowTheType() {
        assertEquals(MediaKind.Audio, mediaKind("audio/mp4", "Recording"))
        assertEquals(MediaKind.Video, mediaKind("application/octet-stream", "Family.MP4"))
        assertEquals(MediaKind.Audio, mediaKind("", "Voice.OPUS"))
        assertEquals(MediaKind.Photo, mediaKind("image/heic", "Photo"))
        assertEquals(MediaKind.Gif, mediaKind("image/gif", "Animation"))
        assertEquals(MediaKind.Photo, mediaKind("application/octet-stream", "Picture.WEBP"))
        assertEquals(MediaKind.Video, mediaKind("application/x-matroska", "Video.mkv"))
        assertEquals(MediaKind.Video, mediaKind("application/ogg", "Video.ogv"))
        assertEquals(MediaKind.Audio, mediaKind("application/mp4", "Recording.m4a"))
        assertNull(mediaKind("application/pdf", "Document.mp4"))
        assertEquals(MediaKind.Svg, mediaKind("image/svg+xml", "Logo.svg"))
        assertEquals(MediaKind.Tgs, mediaKind("application/octet-stream", "Sticker.tgs"))
        assertEquals("Sticker", chatmailAttachmentViewType("application/x-tgsticker", false))
        assertNull(mediaKind("application/vnd.android.package-archive", "App.apk"))
    }

    @Test
    fun readsBoundedTelegramStickerAnimations() {
        val file = java.io.File.createTempFile("relay-sticker", ".tgs")
        try {
            val json = """{"v":"5.7.4","fr":60,"ip":0,"op":60,"w":512,"h":512,"layers":[]}"""
            java.util.zip.GZIPOutputStream(file.outputStream()).use { it.write(json.toByteArray()) }
            val sticker = decodeAnimatedSticker(file)
            assertEquals(60f, sticker.frameRate, 0.01f)
            assertEquals("application/x-tgsticker", MediaDownload("Sticker.tgs", "application/octet-stream").contentType)
            assertEquals("Download image", MediaDownload("Vector.svg", "image/svg+xml").title)
            file.writeText(json)
            assertThrows(java.util.zip.ZipException::class.java) { decodeAnimatedSticker(file) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun downloadPickerPreservesMediaTypeAndSafeFilenames() {
        val audio = MediaDownload("../Voice.OPUS", "application/octet-stream")
        assertEquals("Download audio", audio.title)
        assertEquals("_Voice.OPUS", audio.filename)
        assertTrue(audio.createDocument().type.orEmpty().startsWith("audio/"))
        val photo = MediaDownload("界".repeat(180) + ".png", " IMAGE/PNG ; charset=UTF-8")
        assertEquals("image/png", photo.contentType)
        assertTrue(photo.filename.endsWith(".png"))
        assertTrue(photo.filename.toByteArray().size <= 240)
    }

    @Test
    fun writesOriginalMediaBytesToTheChosenLocation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val destination = java.io.File.createTempFile("p2p-media", ".mp4", context.cacheDir)
        try {
            destination.writeBytes(ByteArray(64))
            val original = byteArrayOf(1, 2, 3, 4, 5)
            writeMediaDownload(context.contentResolver, MediaDownload("Clip.mp4", "video/mp4"),
                Uri.fromFile(destination)) { original.inputStream() }
            assertArrayEquals(original, destination.readBytes())
        } finally {
            destination.delete()
        }
    }

    @Test
    fun failedSaveDiscardsTheNewDocumentEvenWhenTheSourceCannotBeOpened() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val authority = "app.p2p.test.media-documents"
        val uri = DocumentsContract.buildDocumentUri(authority, "new-video")
        val file = java.io.File.createTempFile("relay-export", ".mp4", context.cacheDir)
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?) = error("Unexpected query")
            override fun getType(uri: Uri) = "video/mp4"
            override fun insert(uri: Uri, values: ContentValues?) = error("Unexpected insert")
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Unexpected delete")
            override fun update(uri: Uri, values: ContentValues?, selection: String?,
                selectionArgs: Array<out String>?) = error("Unexpected update")
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)

            override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
                check(method == "android:deleteDocument")
                check(extras?.getParcelable("uri", Uri::class.java) == uri)
                check(file.delete())
                return Bundle()
            }
        }
        provider.attachInfo(context, ProviderInfo().apply {
            this.authority = authority
            exported = true
        })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        try {
            val request = MediaDownload("Clip.mp4", "video/mp4")
            val openFailure = IOException("Source lost before opening")
            val failedOpen = runCatching {
                writeMediaDownload(context.contentResolver, request, uri) { throw openFailure }
            }
            assertSame(openFailure, failedOpen.exceptionOrNull())
            assertFalse(file.exists())

            assertTrue(file.createNewFile())
            val readFailure = IOException("Source lost mid-copy")
            val source = object : InputStream() {
                private var readOnce = false
                override fun read() = error("Expected buffered read")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (readOnce) throw readFailure
                    readOnce = true
                    buffer[offset] = 42
                    return 1
                }
            }
            val failedCopy = runCatching {
                writeMediaDownload(context.contentResolver, request, uri) { source }
            }
            assertSame(readFailure, failedCopy.exceptionOrNull())
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun automaticallyFetchesInlineMediaButLeavesOriginalFilesForManualDownload() {
        val attachment = obj("mime" to "video/mp4", "name" to "Clip.mp4", "size" to NATIVE_ATTACHMENT_FILE_LIMIT)
        assertTrue(shouldFetchInlineMedia(attachment))
        assertTrue(shouldFetchInlineMedia(attachment.put("size", 21_000_000L)))
        assertFalse(shouldFetchInlineMedia(attachment.put("listed", true)))
        assertFalse(shouldFetchInlineMedia(attachment.put("listed", false).put("size", NATIVE_ATTACHMENT_FILE_LIMIT + 1)))
        assertFalse(shouldFetchInlineMedia(obj("mime" to "application/pdf", "size" to 400)))
    }

    @Test
    fun embedsOnlyValidatedVideoIds() {
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?playsinline=1&fs=0",
            youtubeEmbedUrl("dQw4w9WgXcQ"))
        assertNull(youtubeEmbedUrl("example.com"))
        assertNull(youtubeEmbedUrl("\"><script>"))
    }

    @Test
    fun delayedReactionsCannotUndoAChangeAndReplacementDevicesCanStartFresh() {
        val latest = obj("sequence" to 3L, "deviceRevision" to 1L)
        assertFalse(shouldApplyReaction(latest, 1L, 2L))
        assertFalse(shouldApplyReaction(latest, 1L, 3L))
        assertFalse(shouldApplyReaction(latest, 1L, 0L))
        assertTrue(shouldApplyReaction(latest, 1L, 4L))
        assertTrue(shouldApplyReaction(latest, 2L, 1L))
        assertFalse(shouldApplyReaction(obj("sequence" to 1L, "deviceRevision" to 2L), 1L, 99L))
        assertTrue(shouldApplyReaction(obj("text" to "👍"), 1L, 0L))
    }

    @Test
    fun cancellationKeepsConfirmedGroupDeliveriesAndReflectsUncertainAttempts() {
        val group = obj("targets" to 4, "delivered" to 2, "failed" to 1)
        assertEquals(1, remainingMessageTargets(group))
        group.put("cancelled", 1)
        assertEquals(0, remainingMessageTargets(group))
        assertEquals("Delivered to 2 of 4 · canceled", messageDeliveryStatus(group))
        group.put("possiblySent", true)
        assertEquals("Delivered to 2 of 4 · retries canceled", messageDeliveryStatus(group))
        assertEquals("Canceled", messageDeliveryStatus(obj("targets" to 1, "cancelled" to 1)))
    }
}
