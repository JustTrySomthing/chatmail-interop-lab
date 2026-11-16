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
        val code = signedInviteFixture(signed.toByteArray())
        assertTrue(isSupportedInviteCode(code))
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
    fun recognizesStandardSecureJoinEnvelopesAlongsideSignedInvites() {
        val fingerprint = "ABCD".repeat(10)
        val fields = "a=relay%40example.org&n=Relay&i=invite&s=auth"

        assertTrue(isSupportedInviteCode("  https://i.delta.chat/#$fingerprint&$fields  "))
        assertTrue(isSupportedInviteCode("https://i.delta.chat#$fingerprint&$fields"))
        assertTrue(isSupportedInviteCode("OPENPGP4FPR:$fingerprint#$fields"))
        assertTrue(isSupportedInviteCode("openpgp4fpr:$fingerprint#$fields"))
        assertTrue(isSupportedInviteCode("p2p3:legacy"))
        assertFalse(isSecureJoinInvite("p2p3:legacy"))
        assertFalse(isSupportedInviteCode("https://i.delta.chat.evil.example/#$fingerprint&$fields"))
        assertFalse(isSupportedInviteCode("https://i.delta.chat/#"))
        assertFalse(isSupportedInviteCode("DCACCOUNT:https://example.org/new"))
    }

    @Test
    fun secureJoinQrPreservesTheExactInviteLink() {
        val fingerprint = "ABCD".repeat(10)
        val link = "https://i.delta.chat/#$fingerprint&a=relay%40example.org&n=Relay%20Lab&i=invite&s=auth"
        val qr = requireNotNull(InviteImage.make(link))
        val scaled = android.graphics.Bitmap.createScaledBitmap(qr, qr.width * 5, qr.height * 5, false)
        try {
            assertEquals(link, InviteImage.readBitmap(scaled))
        } finally {
            scaled.recycle()
            qr.recycle()
        }
    }

    @Test
    fun decodesAScreenSizedChatmailQrFromCameraLuma() {
        val random = kotlin.random.Random(15)
        val payload = obj("v" to 3, "peer" to encode(random.nextBytes(40)),
            "grant" to encode(random.nextBytes(360)), "token" to encode(random.nextBytes(24)),
            "expires" to 1_800_000_000_000L, "name" to "Alyssa ☀",
            "chatmail" to "https://i.delta.chat/#" + encode(random.nextBytes(130)))
        val code = signedInviteFixture(obj("payload" to payload.toString(),
            "signature" to random.nextBytes(64).hex(), "public" to random.nextBytes(36).hex()).toString().toByteArray())
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
    fun channelsDistinguishReadOnlySubscriptionsFromPublishers() {
        val chat = obj("id" to 14, "chatType" to "InBroadcast", "isEncrypted" to true,
            "name" to "Trail notes", "canSend" to true, "freshMessageCounter" to 2)
        val followed = requireNotNull(parseChatmailChannel(chat))
        assertFalse(followed.owned)
        assertFalse(followed.canPost)
        assertEquals(2, followed.unread)
        val owned = requireNotNull(parseChatmailChannel(chat.put("chatType", "OutBroadcast")))
        assertTrue(owned.owned)
        assertTrue(owned.canPost)
        assertNull(parseChatmailChannel(chat.put("chatType", "Group")))
        assertNull(parseChatmailChannel(chat.put("chatType", "InBroadcast").put("isEncrypted", false)))
    }

    @Test
    fun channelHistoryPreservesDatesAndFiltersUnencryptedAndSystemMessages() {
        val channel = ChatmailChannel(14, "Trail notes", false, false, 0)
        val message = obj("id" to 51, "kind" to "message", "chatId" to 14, "showPadlock" to true,
            "timestamp" to 900L, "text" to "The trail is open", "fileName" to "trail.png")
        val media = ChatmailAttachment(null, "trail.png", "", 0, "", "")
        assertEquals(ChannelPost(51, 14, "Trail notes", "The trail is open", 900_000L, 0, media),
            parseChannelPost(message, channel))
        message.put("fileMime", "image/png").put("downloadState", "InProgress").put("text", "")
        val attachment = requireNotNull(parseChannelPost(message, channel)?.attachment)
        assertEquals("image/png", attachment.mime)
        assertEquals("Downloading", attachment.displayData(null).getString("state"))
        assertNull(parseChannelPost(message.put("isInfo", true), channel))
        assertNull(parseChannelPost(message.put("isInfo", false).put("showPadlock", false), channel))
        assertNull(parseChannelPost(message.put("showPadlock", true).put("chatId", 12), channel))
    }

    @Test
    fun readsNativeContactPhotoAndBioAndExcludesBlockedContacts() {
        val row = obj("id" to 20, "isKeyContact" to true, "displayName" to "Sam",
            "status" to "Trail runner", "profileImage" to "/profiles/avatar.jpg")
        assertEquals(ChatmailContact(20, "Sam", "Trail runner", "/profiles/avatar.jpg"), parseChatmailContact(row))
        row.put("profileImage", org.json.JSONObject.NULL)
        assertEquals(ChatmailContact(20, "Sam", "Trail runner"), parseChatmailContact(row))
        assertNull(parseChatmailContact(row.put("isBlocked", true)))
    }

    @Test
    fun historyFindsAnOlderPageWhenCoreRemovedTheCursorMessage() {
        val ids = listOf(90, 23, 19, 100, 41)
        val cached = setOf(100, 41, 77)
        assertEquals(1 until 3, chatmailHistoryWindow(ids, 2, before = 77, after = null, cached = cached))
        assertEquals(3 until 5, chatmailHistoryWindow(ids, 2, before = null, after = 100, cached = cached))
        assertEquals(0 until 0, chatmailHistoryWindow(ids, 2, before = 90, after = null, cached = cached))
        assertEquals(2 until 5, chatmailHistoryWindow(ids, 2, before = null, after = 77, cached = setOf(23)))
    }

    @Test
    fun cachedHistoryReconcilesOnlyNativeMessagesInTheAffectedThread() {
        fun row(key: String, thread: String, native: Int?) = Record(key, "message",
            obj("thread" to thread, "unread" to true, "nativeMessage" to native).toString())
        val expired = row("expired", "chat", 19)
        val live = row("live", "chat", 20)
        val legacy = row("legacy", "chat", null)
        val other = row("other", "another", 21)
        val cached = listOf(expired, live, legacy, other)
        assertEquals(listOf(expired), cached.removedChatmailMessages("chat", setOf(20)))
        assertEquals(listOf(expired, live, legacy), cached.unreadThreadItems("chat"))
        assertFalse(canCancelLegacyDelivery(live.data()))
        assertTrue(canCancelLegacyDelivery(legacy.data()))
    }

    @Test
    fun callsUseTheSharedEncryptedThreadAndContactRecords() {
        val chat = obj("id" to 12, "chatType" to "Single", "isEncrypted" to true,
            "contactIds" to org.json.JSONArray(listOf(1, 20)), "canSend" to true)
        val contact = obj("id" to 20, "displayName" to "Delta peer", "isKeyContact" to true, "e2eeAvail" to true)
        val thread = requireNotNull(readNativeThread(chat))
        val profile = requireNotNull(parseChatmailContact(contact))
        assertEquals(20, thread.contactId)
        assertEquals(ChatmailCallContact(12, "Delta peer"), callContactFor(thread, profile))
        assertNull(callContactFor(thread, profile.copy(encryptionAvailable = false)))
        assertNull(callContactFor(thread, profile.copy(id = 21)))
        assertNull(callContactFor(thread.copy(request = true), profile))
        assertNull(callContactFor(thread.copy(canSend = false), profile))
        assertNull(callContactFor(thread.copy(group = true), profile))
        assertNull(parseChatmailContact(contact.put("isBlocked", true)))
        assertNull(readNativeThread(chat.put("isEncrypted", false)))
    }

    @Test
    fun nativeHistoryRetainsStandardAttachmentsQuotesAndReactions() {
        val reactions = obj("reactionsByContact" to obj("1" to org.json.JSONArray(listOf("👍")),
            "20" to org.json.JSONArray(listOf("👋", "✨"))))
        val quote = obj("text" to "Earlier", "chatId" to 12, "messageId" to 28, "authorDisplayName" to "Alex")
        val raw = obj("kind" to "message", "id" to 30, "chatId" to 12,
            "fromId" to 20, "showPadlock" to true, "viewType" to "Video", "text" to "Clip",
            "timestamp" to 1234, "fileName" to "clip.mp4", "fileMime" to "video/mp4",
            "fileBytes" to 1024, "quote" to quote, "reactions" to reactions)
        val message = requireNotNull(readNativeMessage(raw, 12))
        assertEquals(28, message.quote)
        assertEquals("Alex", message.quoteAuthor)
        val media = requireNotNull(message.attachment)
        assertEquals("clip.mp4", media.name)
        assertEquals("video/mp4", media.mime)
        assertEquals(1024L, media.size)
        assertEquals(1234000L, message.time)
        assertEquals(mapOf(1 to listOf("👍"), 20 to listOf("👋", "✨")), message.reactions)
        assertNull(readNativeMessage(raw, 13))
        assertNull(readNativeMessage(raw.put("showPadlock", false), 12))
        assertNull(readNativeMessage(raw.put("showPadlock", true).put("isInfo", true), 12))
        assertNull(readNativeMessage(raw.put("isInfo", false).put("text", "P2P/1 old envelope"), 12))
    }

    @Test
    fun nativeGroupsRetainOnlyCoreMembersAndHaveSeparateChatAndMessageKeys() {
        val raw = obj("id" to 12, "name" to "Trip", "chatType" to "Group", "isEncrypted" to true,
            "contactIds" to org.json.JSONArray(listOf(1, 20, 34)), "canSend" to true)
        val group = requireNotNull(readNativeThread(raw))
        assertTrue(group.group)
        assertTrue(group.canSend)
        assertEquals(listOf(20, 34), group.members)
        assertFalse(nativeThreadKey(12) == nativeMessageKey(12))
        assertTrue(isId(nativeThreadKey(12)))
        assertNull(readNativeThread(raw.put("isEncrypted", false)))
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
