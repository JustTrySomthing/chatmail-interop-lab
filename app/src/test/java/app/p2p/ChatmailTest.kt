package app.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatmailTest {
    @Test
    fun compactChatmailInviteSurvivesPastingAndMakesAQr() {
        val signed = obj("payload" to """{"name":"Alyssa ☀","chatmail":"https://i.delta.chat/#sample"}""",
            "signature" to "sample", "public" to "sample").toString()
        val code = chatmailInviteCode(signed)
        assertTrue(InviteCode.hasPrefix(code))
        assertEquals(code, InviteCode.compact(code))
        assertEquals(signed, chatmailInviteBytes(code.chunked(31).joinToString("\n")).toString(Charsets.UTF_8))
        val qr = requireNotNull(InviteImage.make(code))
        val scaled = android.graphics.Bitmap.createScaledBitmap(qr, qr.width * 5, qr.height * 5, false)
        try {
            assertEquals(code, InviteImage.readBitmap(scaled))
        } finally {
            scaled.recycle()
            qr.recycle()
        }
        assertFalse(runCatching { chatmailInviteBytes(code.replaceFirst("p2p3:", "p2p2:")) }.isSuccess)
    }

    @Test
    fun decodesAScreenSizedChatmailQrFromCameraLuma() {
        val random = kotlin.random.Random(15)
        val payload = obj("v" to 3, "peer" to encode(random.nextBytes(40)),
            "grant" to encode(random.nextBytes(360)), "token" to encode(random.nextBytes(24)),
            "expires" to 1_800_000_000_000L, "name" to "Alyssa ☀",
            "chatmail" to "https://i.delta.chat/#" + encode(random.nextBytes(130)))
        val code = chatmailInviteCode(obj("payload" to payload.toString(),
            "signature" to random.nextBytes(64).hex(), "public" to random.nextBytes(36).hex()).toString())
        val qr = requireNotNull(InviteImage.make(code))
        try {
            val width = 1280
            val height = 720
            val scale = 3
            val edge = qr.width * scale
            val frame = ByteArray(width * height) { 100.toByte() }
            for (y in 0 until edge) for (x in 0 until edge) {
                val row = y + (height - edge) / 2
                val column = x + (width - edge) / 2
                frame[row * width + column] = if (qr.getPixel(x / scale, y / scale) == android.graphics.Color.WHITE) {
                    245.toByte()
                } else 15
            }
            val camera = com.google.zxing.PlanarYUVLuminanceSource(frame, width, height, 0, 0, width, height, false)
            assertEquals(code, InviteImage.readSource(camera, tryGlobal = true))
        } finally {
            qr.recycle()
        }
    }

    @Test
    fun relayAcceptanceAndDeviceDeliveryHaveDifferentStatuses() {
        val message = obj("targets" to 1, "chatmailSubmitted" to true)
        assertEquals(CHATMAIL_SUBMITTED, messageDeliveryStatus(message))
        message.put("delivered", 1)
        assertEquals("Delivered", messageDeliveryStatus(message))
        val request = obj("state" to INVITE_APPROVAL, "chatmailChat" to 12, "expires" to 200_000L)
        assertEquals(INVITE_APPROVAL, inviteRequestStatus(request, 199_000L))
        assertEquals("Invite expired. An approval sent before expiry can still arrive.", inviteRequestStatus(request, 200_000L))
        request.put("state", CHATMAIL_REQUEST_SENT)
        assertEquals("Connecting…", inviteRequestStatus(request, 199_000L))
        assertEquals("Invite expired. An approval sent before expiry can still arrive.", inviteRequestStatus(request, 200_000L))
        request.put("state", INVITE_REJECTED)
        assertEquals("This invite is no longer valid. Ask for a fresh invite.", inviteRequestStatus(request, 199_000L))
    }

    @Test
    fun packetParserPreservesFullUnicodeAndRejectsMismatchedIds() {
        val transfer = "a".repeat(64)
        val text = "👋 Hello\n".repeat(1_200)
        val packet = obj("transfer" to transfer, "body" to text)
        assertEquals(text, decodeChatmailPacket("P2P/1 $transfer\n$packet")?.getString("body"))
        assertNull(decodeChatmailPacket("P2P/1 ${"b".repeat(64)}\n$packet"))
        assertNull(decodeChatmailPacket("P2P/1 $transfer\n{"))
    }

    @Test
    fun legacyDownloadsAndNearbyApprovalsStayOnTheDirectTransport() {
        val token = "a".repeat(32)
        val contact = obj("chatmailChat" to 17, "token" to token)
        assertEquals(0, chatmailRoute(obj("to" to "peer", "file" to "file"), contact))
        assertEquals(0, chatmailRoute(obj("payload" to obj("type" to "download")), contact))
        val approval = obj("payload" to obj("type" to "paired", "token" to token))
        assertEquals(17, chatmailRoute(approval, contact))
        assertEquals(0, chatmailRoute(approval.put("chatmailChat", 0), contact))
        assertEquals(21, chatmailRoute(approval.put("chatmailChat", 21), contact))
    }

    @Test
    fun lateOrWrongChatApprovalsCannotCompleteAnInvite() {
        val token = "a".repeat(32)
        val invite = obj("token" to token, "chatmailChat" to 17, "expires" to 100L)
        assertTrue(canAcceptPairing(invite, token, 17, 99L))
        assertFalse(canAcceptPairing(invite, token, 18, 99L))
        assertFalse(canAcceptPairing(invite, token, 17, 100L))
        assertFalse(canAcceptPairing(invite.put("state", INVITE_REJECTED), token, 17, 99L))
        assertTrue(validChatmailReceipt("hello", "waiting"))
        assertFalse(validChatmailReceipt("hello", "ok"))
        assertFalse(validChatmailReceipt("message", "paired"))
        assertTrue(validChatmailReceipt("message", "ok"))
    }

    @Test
    fun cancellationTargetsOnlyTheMatchingPendingOutgoingMessage() {
        val transfer = "a".repeat(64)
        val message = obj("fromId" to 1, "chatId" to 17, "state" to 20,
            "text" to "P2P/1 $transfer\n{}")
        assertTrue(isPendingChatmailSend(message, 17, transfer))
        assertFalse(isPendingChatmailSend(message, 18, transfer))
        assertFalse(isPendingChatmailSend(message, 17, "b".repeat(64)))
        assertFalse(isPendingChatmailSend(message.put("state", 26), 17, transfer))
        assertFalse(isPendingChatmailSend(message.put("state", 20).put("fromId", 10), 17, transfer))
    }

    @Test
    fun offlineRecipientCanReceiveAnApprovalSignedBeforeExpiry() {
        val token = "a".repeat(32)
        val invite = obj("token" to token, "chatmailChat" to 17, "expires" to 100L)
        assertTrue(canAcceptPairing(invite, token, 17, 150L, approvedAt = 99L))
        assertFalse(canAcceptPairing(invite, token, 17, 150L, approvedAt = 100L))
        assertFalse(canAcceptPairing(invite, token, 18, 150L, approvedAt = 99L))
        assertFalse(canAcceptPairing(invite.put("state", INVITE_REJECTED), token, 17, 150L, approvedAt = 99L))
    }

    @Test
    fun chatmailChecksBothAdvertisedAndActualAttachmentSizes() {
        val offer = obj("size" to CHATMAIL_FILE_LIMIT)
        val metadata = obj("fileBytes" to CHATMAIL_FILE_LIMIT)
        assertTrue(chatmailDownloadAllowed(offer, metadata))
        assertFalse(chatmailDownloadAllowed(offer, metadata.put("fileBytes", CHATMAIL_FILE_LIMIT + 1)))
        assertFalse(chatmailDownloadAllowed(offer.put("size", -1), metadata.put("fileBytes", 0)))
    }
}
