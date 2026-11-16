package app.p2p

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        assertNull(mediaKind("image/svg+xml", "Logo.svg"))
        assertNull(mediaKind("application/vnd.android.package-archive", "App.apk"))
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
    fun writesOriginalMediaBytesToTheChosenLocation() = kotlinx.coroutines.runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val destination = java.io.File.createTempFile("p2p-media", ".mp4", context.cacheDir)
        try {
            destination.writeBytes(ByteArray(64))
            val original = byteArrayOf(1, 2, 3, 4, 5)
            writeMediaDownload(context.contentResolver, MediaDownload("Clip.mp4", "video/mp4"),
                android.net.Uri.fromFile(destination), original.inputStream())
            assertArrayEquals(original, destination.readBytes())
        } finally {
            destination.delete()
        }
    }

    @Test
    fun automaticallyFetchesInlineMediaButLeavesOriginalFilesForManualDownload() {
        val attachment = obj("mime" to "video/mp4", "name" to "Clip.mp4", "size" to CHATMAIL_FILE_LIMIT)
        assertTrue(shouldFetchInlineMedia(attachment))
        assertFalse(shouldFetchInlineMedia(attachment.put("listed", true)))
        assertFalse(shouldFetchInlineMedia(attachment.put("listed", false).put("size", CHATMAIL_FILE_LIMIT + 1)))
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
