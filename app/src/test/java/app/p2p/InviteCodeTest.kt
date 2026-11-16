package app.p2p

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InviteCodeTest {
    @Test
    fun noAddressStatusDistinguishesInvitesWithoutDiscoveryPeers() {
        val request = obj("routes" to jsonArray(emptyList()), "state" to INVITE_CONNECTING)
        assertTrue(inviteRequestStatus(request, 20_000L).contains("no connection address"))
        request.put("hasDiscoveryPeers", true)
        assertEquals(INVITE_CONNECTING, inviteRequestStatus(request, 20_000L))
    }

    @Test
    fun oldAcknowledgementDoesNotClaimTheRequestStillAwaitsApproval() {
        val request = obj("state" to INVITE_APPROVAL, "expires" to 90_000L)
        assertEquals(INVITE_APPROVAL_STALE, inviteRequestStatus(request, 20_000L))
        request.put("acknowledgedAt", 20_000L)
        assertEquals(INVITE_APPROVAL, inviteRequestStatus(request, 64_999L))
        assertEquals(INVITE_APPROVAL_STALE, inviteRequestStatus(request, 65_000L))
        assertEquals("Invite expired. Ask for a fresh invite.", inviteRequestStatus(request, 90_000L))
    }

    @Test
    fun aCompletedPairingRecognizesOnlyItsOriginalToken() {
        val token = "a".repeat(32)
        val contact = obj("token" to token)
        assertTrue(matchesInviteToken(contact, token))
        assertFalse(matchesInviteToken(contact, "b".repeat(32)))
        assertFalse(matchesInviteToken(contact, ""))
        assertFalse(matchesInviteToken(null, token))
    }

    @Test
    fun crossedInvitesChooseOneDirectionAndIgnoreClosedRequests() {
        val request = obj("expires" to 10_000L, "state" to INVITE_CONNECTING)
        assertFalse(keepOutgoingInvite("first", "second", request, 9_999L))
        assertTrue(keepOutgoingInvite("second", "first", request, 9_999L))
        assertFalse(keepOutgoingInvite("second", "first", request, 10_000L))
        request.put("state", INVITE_REJECTED)
        assertFalse(keepOutgoingInvite("second", "first", request, 9_999L))
    }

    private val random = Random(42)
    private val signed = buildString {
        append("""{"signature":"${encode(random.nextBytes(256))}","payload":[""")
        repeat(10) {
            if (it > 0) append(',')
            append('"')
            append("/ip4/192.168.1.$it/udp/41736/quic-v1/p2p/")
            append("12D3KooW")
            append(encode(random.nextBytes(36)))
            append('"')
        }
        append("]}")
    }.toByteArray()
    private val legacy = "p2p1:" + encode(signed)

    @Test
    fun supportsLegacyWrappedPasteAndCompactQrWithoutChangingSignedBytes() {
        val wrapped = "  " + legacy.chunked(80).joinToString("\n  ") + "\n"
        assertArrayEquals(signed, InviteCode.signedBytes(wrapped))
        val compact = InviteCode.compact(legacy)
        assertTrue(compact.startsWith("p2p2:"))
        assertTrue(compact.length < legacy.length)
        assertArrayEquals(signed, InviteCode.signedBytes(compact))
    }

    @Test
    fun rejectsBadInputsAndBoundedDecompression() {
        for (bad in listOf("12D3KooWPublicAddress", "p2p1:..", "p2p2:AAAA", "p2p1:" + "A".repeat(16_384))) {
            assertThrows(IllegalArgumentException::class.java) { InviteCode.signedBytes(bad) }
        }
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(ByteArray(20_000)) }
        assertThrows(IllegalArgumentException::class.java) {
            InviteCode.signedBytes("p2p2:" + encode(compressed.toByteArray()))
        }
    }

    @Test
    fun readsCompactQrInALargerTiltedImage() {
        val qr = requireNotNull(InviteImage.make(legacy))
        val photo = Bitmap.createBitmap(1500, 1400, Bitmap.Config.ARGB_8888)
        try {
            Canvas(photo).apply {
                drawColor(Color.rgb(220, 220, 220))
                rotate(7f, 750f, 700f)
                drawBitmap(qr, null, android.graphics.Rect(250, 200, 1250, 1200), null)
            }
            assertArrayEquals(signed, InviteCode.signedBytes(InviteImage.readBitmap(photo)))
        } finally {
            qr.recycle()
            photo.recycle()
        }
    }
}
