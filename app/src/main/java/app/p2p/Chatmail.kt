package app.p2p

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal const val CHATMAIL_FILE_LIMIT = 20_000_000L
internal const val CHATMAIL_INVITE_PREFIX = "p2p3:"
internal const val CHATMAIL_SUBMITTED = "Sent via Chatmail"
internal const val CHATMAIL_REQUEST_QUEUED = "Queued for Chatmail delivery"
internal const val CHATMAIL_REQUEST_SENT = "Sent via Chatmail · waiting for their device"
private const val PACKET_PREFIX = "P2P/1 "

internal object ChatmailNative {
    init { System.loadLibrary("p2p_chatmail") }
    @JvmStatic external fun open(directory: ByteArray): Long
    @JvmStatic external fun call(handle: Long, request: ByteArray): ByteArray?
    @JvmStatic external fun close(handle: Long)
}

internal class Chatmail(directory: File) : AutoCloseable {
    private val handle = ChatmailNative.open(directory.path.toByteArray()).also {
        check(it != 0L) { "Couldn’t open the Chatmail profile." }
    }
    private val account = try {
        (rpc("get_all_account_ids") as JSONArray).optInt(0).takeIf { it > 0 }
            ?: (rpc("add_account") as Number).toInt()
    } catch (error: Exception) {
        ChatmailNative.close(handle)
        throw error
    }
    private var displayName: String? = null
    private var profileRevision: Long? = null
    private var running = false

    private fun rpc(method: String, vararg params: Any?): Any {
        val request = obj("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to JSONArray(params.toList()))
        val reply = ChatmailNative.call(handle, request.toString().toByteArray()) ?: error("Chatmail is unavailable.")
        val response = JSONObject(reply.toString(Charsets.UTF_8))
        // Core errors can include account configuration and authentication details.
        check(!response.has("error")) { "Chatmail couldn’t complete ${method.replace('_', ' ')}." }
        return response.get("result")
    }

    private fun call(method: String, vararg params: Any?): Any = rpc(method, account, *params)

    fun profileNeedsSync(revision: Long): Boolean = profileRevision != revision

    fun applyProfile(revision: Long, bio: String?, avatar: File?, updateAvatar: Boolean) {
        if (profileRevision == revision) return
        val stored = call("get_config", "ui.relay_lab_profile_revision")
        if (stored != revision.toString()) {
            if (bio != null) call("set_config", "selfstatus", bio)
            if (updateAvatar) call("set_config", "selfavatar", avatar?.absolutePath)
            call("set_config", "ui.relay_lab_profile_revision", revision.toString())
        }
        profileRevision = revision
    }

    fun connect(name: String, setupQr: String? = null) {
        if (displayName != name) {
            // P2P consumes signed protocol messages and needs their complete text.
            call("batch_set_config", obj("displayname" to name, "download_limit" to "0", "bot" to "1",
                "mdns_enabled" to "0"))
            displayName = name
        }
        if (!running) {
            if (call("is_configured") != true) call("init_transports", setupQr)
            call("start_io")
            running = true
        }
    }

    fun disconnect() {
        if (running) call("stop_io")
        running = false
    }

    fun iceServers(): String = call("ice_servers") as String

    fun connectivity(): Int = (call("get_connectivity") as Number).toInt()

    fun invite(): String = call("get_chat_securejoin_qr_code", null) as String

    fun secureJoinInvite(): String = invite().also {
        check(isSecureJoinInvite(it)) { "Chatmail returned an invalid contact invite." }
    }

    fun join(code: String): Int {
        require(inviteKind(code) == "askVerifyContact") { "This Chatmail invite can’t be used to add a contact." }
        return joinChecked(code)
    }

    fun inviteKind(code: String): String = (call("check_qr", code) as JSONObject).optString("kind")

    fun joinChecked(code: String): Int = (call("secure_join", code) as Number).toInt()

    fun channels(): List<ChatmailChannel> = interoperableChatIds().mapNotNull(::channel)

    fun channel(id: Int): ChatmailChannel? =
        parseChatmailChannel(call("get_full_chat_by_id", id) as JSONObject)

    fun channelPosts(channel: ChatmailChannel, limit: Int): ChannelHistory {
        val ids = (call("get_message_ids", channel.id, false, false) as JSONArray).intValues()
        val batch = messages(ids.takeLast(limit))
        val posts = ids.takeLast(limit).mapNotNull { id ->
            batch.optJSONObject(id.toString())?.let { parseChannelPost(it, channel) }
        }
        return ChannelHistory(posts, ids.size > limit)
    }

    fun createChannel(name: String): Int = (call("create_broadcast", name) as Number).toInt()

    fun channelInvite(channel: ChatmailChannel): String {
        require(channel.owned) { "Only the owner can share this channel’s invite." }
        return call("get_chat_securejoin_qr_code", channel.id) as String
    }

    fun postToChannel(channel: ChatmailChannel, text: String) {
        check(channel.owned && channel.canPost) { "This channel can’t publish posts yet." }
        require(text.isNotBlank() && text.length <= INTEROP_TEXT_LIMIT) { "Enter a post of up to $INTEROP_TEXT_LIMIT characters." }
        call("send_msg", channel.id, obj("text" to text))
    }

    fun noticeChannel(channel: ChatmailChannel) {
        if (!channel.owned && channel.unread > 0) call("marknoticed_chat", channel.id)
    }

    fun canSend(chat: Int): Boolean {
        val details = call("get_full_chat_by_id", chat) as JSONObject
        return details.optBoolean("isEncrypted") && details.optBoolean("canSend")
    }

    fun addRelay(code: String) {
        require(inviteKind(code) in setOf("account", "login")) { "Use a Chatmail relay or login QR code." }
        call("add_transport_from_qr", code)
    }

    fun contacts(): List<ChatmailContact> {
        val entries = call("get_contacts", 0, null) as JSONArray
        return (0 until entries.length()).mapNotNull { entries.optJSONObject(it)?.let(::parseChatmailContact) }
    }

    fun openContact(id: Int): Int {
        require(id > 9 && parseChatmailContact(call("get_contact", id) as JSONObject) != null) {
            "This Chatmail contact is unavailable."
        }
        return (call("create_chat_by_contact_id", id) as Number).toInt()
    }

    fun acceptContactRequest(chat: InteropChat) {
        if (chat.request) call("accept_chat", chat.id)
    }

    fun isConfigured(): Boolean = call("is_configured") == true

    fun interoperableChatIds(): List<Int> =
        (call("get_chatlist_entries", null, null, null) as JSONArray).intValues().filter { it > 9 }

    fun chatContactId(id: Int): Int? = interopPeerId(call("get_full_chat_by_id", id) as JSONObject)

    fun interoperableChat(id: Int): InteropChat? {
        val chat = call("get_full_chat_by_id", id) as JSONObject
        val peer = interopPeerId(chat) ?: return null
        return parseInteropChat(chat, call("get_contact", peer) as JSONObject, peer)
    }

    fun interoperableMessages(chat: InteropChat, limit: Int): InteropHistory {
        val ids = (call("get_message_ids", chat.id, false, false) as JSONArray).intValues()
        val recent = ids.takeLast(limit)
        val batch = messages(recent)
        return InteropHistory(recent.mapNotNull { id ->
            batch.optJSONObject(id.toString())?.let { parseInteropText(it, chat) }
        }, ids.size > limit)
    }

    fun sendInteropText(chat: InteropChat, text: String): Int {
        check(chat.canSend) { "Wait for the encrypted connection before sending." }
        require(text.isNotBlank() && text.length <= INTEROP_TEXT_LIMIT && !text.startsWith(PACKET_PREFIX)) {
            "Enter a message of up to $INTEROP_TEXT_LIMIT characters."
        }
        return (call("send_msg", chat.id, obj("text" to text)) as Number).toInt()
    }

    private var lastReadCursor: Int? = null

    fun unread(after: Int): List<Int> {
        if (lastReadCursor != after) {
            call("set_config", "last_msg_id", after.toString())
            lastReadCursor = after
        }
        val ids = call("get_next_msgs") as JSONArray
        return (0 until ids.length()).map { ids.getInt(it) }
    }

    fun cancelPending(id: Int, chat: Int, transfer: String) {
        val message = messages(listOf(id)).optJSONObject(id.toString())
            ?: error("Chatmail message state is unavailable.")
        if (message.optString("error") == "Message does not exist") return
        check(message.optString("kind") == "message") { "Chatmail message state is unavailable." }
        if (isPendingChatmailSend(message, chat, transfer)) call("delete_messages", JSONArray(listOf(id)))
    }

    fun download(id: Int) { call("download_full_message", id) }

    fun message(id: Int): JSONObject = call("get_message", id) as JSONObject

    fun messages(ids: List<Int>): JSONObject =
        if (ids.isEmpty()) obj() else call("get_messages", JSONArray(ids)) as JSONObject

    fun send(chat: Int, packet: JSONObject, file: File? = null, name: String? = null): Int {
        val text = PACKET_PREFIX + packet.getString("transfer") + "\n" + packet
        val data = obj("text" to text)
        if (file != null) data.put("file", file.absolutePath).put("filename", name)
        return (call("send_msg", chat, data) as Number).toInt()
    }

    fun findSent(chat: Int, transfer: String): Int? {
        val prefix = PACKET_PREFIX + transfer + "\n"
        val ids = call("search_messages", PACKET_PREFIX + transfer, chat) as JSONArray
        return (0 until ids.length()).map { ids.getInt(it) }.lastOrNull { id ->
            val message = message(id)
            message.optInt("fromId") == 1 && message.optString("text").startsWith(prefix)
        }
    }

    override fun close() {
        try {
            disconnect()
        } finally {
            ChatmailNative.close(handle)
        }
    }
}

private fun JSONArray.intValues(): List<Int> = (0 until length()).map(::getInt)

internal const val INTEROP_TEXT_LIMIT = 12_000

internal data class ChatmailChannel(
    val id: Int, val name: String, val owned: Boolean, val canPost: Boolean, val unread: Int
)
internal data class ChannelPost(
    val id: Int, val channelId: Int, val channelName: String, val text: String,
    val time: Long, val state: Int, val attachmentName: String?
)
internal data class ChannelHistory(val posts: List<ChannelPost>, val hasOlder: Boolean)

internal fun parseChatmailChannel(chat: JSONObject): ChatmailChannel? {
    val id = chat.optInt("id")
    val type = chat.optString("chatType")
    if (id <= 9 || !chat.optBoolean("isEncrypted") ||
        (type != "InBroadcast" && type != "OutBroadcast")) return null
    val owned = type == "OutBroadcast"
    return ChatmailChannel(id, chat.optString("name").ifBlank { "Channel" }, owned,
        owned && chat.optBoolean("canSend"), chat.optInt("freshMessageCounter"))
}

internal fun parseChannelPost(message: JSONObject, channel: ChatmailChannel): ChannelPost? {
    val id = message.optInt("id")
    if (id <= 9 || message.optInt("chatId") != channel.id || message.optString("kind") != "message" ||
        message.optBoolean("isInfo") || !message.optBoolean("showPadlock")) return null
    val text = message.optString("text")
    val attachment = message.optString("fileName").takeUnless { it.isBlank() || it == "null" }
    if (text.isBlank() && attachment == null) return null
    return ChannelPost(id, channel.id, channel.name, text,
        message.optLong("timestamp") * 1_000L, message.optInt("state"), attachment)
}

internal data class ChatmailContact(val id: Int, val name: String, val bio: String = "", val photoPath: String? = null)

internal fun parseChatmailContact(data: JSONObject): ChatmailContact? {
    val id = data.optInt("id")
    if (id <= 9 || !data.optBoolean("isKeyContact") || data.optBoolean("isBlocked")) return null
    val name = data.optString("displayName").ifBlank { "Chatmail contact" }
    return ChatmailContact(id, name, data.optString("status"),
        data.optString("profileImage").takeUnless { data.isNull("profileImage") || it.isBlank() })
}

internal data class InteropChat(
    val id: Int, val contactId: Int, val name: String, val canSend: Boolean,
    val bio: String = "", val photoPath: String? = null, val request: Boolean = false
)
internal data class InteropText(val id: Int, val text: String, val outgoing: Boolean, val state: Int)
internal data class InteropHistory(val messages: List<InteropText>, val hasOlder: Boolean)

internal fun interopPeerId(chat: JSONObject): Int? {
    if (chat.optInt("id") <= 9 || chat.optString("chatType") != "Single" ||
        !chat.optBoolean("isEncrypted") || chat.optBoolean("isDeviceChat") || chat.optBoolean("isSelfTalk")) return null
    return chat.optJSONArray("contactIds")?.intValues()?.filter { it > 9 }?.singleOrNull()
}

internal fun parseInteropChat(chat: JSONObject, contact: JSONObject, peer: Int): InteropChat? {
    val profile = parseChatmailContact(contact)?.takeIf { it.id == peer } ?: return null
    val request = chat.optBoolean("isContactRequest")
    return InteropChat(chat.getInt("id"), peer, profile.name,
        chat.optBoolean("canSend") && !request && contact.optBoolean("e2eeAvail"),
        profile.bio, profile.photoPath, request)
}

internal fun parseInteropText(message: JSONObject, chat: InteropChat): InteropText? {
    val sender = message.optInt("fromId")
    if (message.optString("kind") != "message" || message.optInt("chatId") != chat.id ||
        message.optInt("id") <= 9 || (sender != 1 && sender != chat.contactId) ||
        !message.optBoolean("showPadlock") || message.optBoolean("isInfo") ||
        message.optString("viewType") != "Text") return null
    val text = message.optString("text")
    if (text.isBlank() || text.startsWith(PACKET_PREFIX)) return null
    return InteropText(message.getInt("id"), text, sender == 1, message.optInt("state"))
}

internal fun decodeChatmailPacket(text: String): JSONObject? {
    if (!text.startsWith(PACKET_PREFIX) || text.length > 256_000) return null
    val marker = text.substringBefore('\n').removePrefix(PACKET_PREFIX)
    if (!marker.matches(Regex("[0-9a-f]{64}"))) return null
    return runCatching { JSONObject(text.substringAfter('\n', "")).takeIf { it.optString("transfer") == marker } }.getOrNull()
}

internal fun isChatmailInviteLink(value: String): Boolean =
    value.startsWith("https://i.delta.chat/#") && value.substringAfter('#').isNotBlank()

// This recognizes envelopes only. Chatmail Core validates the invite and distinguishes
// contact invites from groups, account setup, and invitations from the current account.
internal fun isSecureJoinInvite(value: String): Boolean {
    if (value.length >= 32_768) return false
    val code = value.trim()
    return isChatmailInviteLink(code) ||
        (code.startsWith("https://i.delta.chat#") && code.substringAfter('#').isNotBlank()) ||
        (code.startsWith("openpgp4fpr:", ignoreCase = true) && code.substringAfter(':').isNotBlank())
}

internal fun isContactInviteCode(value: String): Boolean =
    InviteCode.hasPrefix(value.trim()) || isSecureJoinInvite(value)

internal fun chatmailInviteCode(signed: String): String =
    CHATMAIL_INVITE_PREFIX + InviteCode.compact("p2p1:" + encode(signed.toByteArray())).substringAfter(':')

internal fun chatmailInviteBytes(code: String): ByteArray {
    require(code.length < 32_768) { "This invite code is too long." }
    val normalized = code.filterNot(Char::isWhitespace)
    require(normalized.startsWith(CHATMAIL_INVITE_PREFIX)) { "Ask for a fresh P2P invite from the Chatmail version." }
    return InviteCode.signedBytes("p2p2:" + normalized.substringAfter(':'))
}

internal fun validChatmailReceipt(type: String, status: String): Boolean =
    if (type == "hello") status in setOf("waiting", "paired", "rejected")
    else status in setOf("ok", "rejected")

internal fun canAcceptPairing(
    request: JSONObject, token: String, chat: Int, now: Long, approvedAt: Long = 0
): Boolean {
    if (!matchesInviteToken(request, token) || request.optInt("chatmailChat") != chat ||
        request.optString("state") == INVITE_REJECTED) return false
    val expires = request.optLong("expires", Long.MAX_VALUE)
    if (now < expires) return true
    // Chatmail can deliver a signed approval after the recipient comes back online.
    return chat > 0 && approvedAt in 1 until expires
}

internal fun chatmailRoute(queued: JSONObject, contact: JSONObject?): Int {
    val payload = queued.optJSONObject("payload") ?: return 0
    if (queued.has("chatmailChat")) return queued.optInt("chatmailChat")
    return when (payload.optString("type")) {
        "hello", "download", "downloadUnavailable" -> 0
        // 0.7.0 approvals inferred the route from the contact record.
        "paired" -> contact?.takeIf { matchesInviteToken(it, payload.optString("token")) }?.optInt("chatmailChat") ?: 0
        else -> contact?.optInt("chatmailChat") ?: 0
    }
}

internal fun isPendingChatmailSend(message: JSONObject, chat: Int, transfer: String): Boolean =
    message.optInt("fromId") == 1 && message.optInt("chatId") == chat &&
        message.optInt("state") == 20 && message.optString("text").startsWith(PACKET_PREFIX + transfer + "\n")

internal fun chatmailDownloadAllowed(offer: JSONObject, message: JSONObject): Boolean =
    offer.optLong("size", -1) in 0..CHATMAIL_FILE_LIMIT &&
        message.optLong("fileBytes", -1) in 0..CHATMAIL_FILE_LIMIT
