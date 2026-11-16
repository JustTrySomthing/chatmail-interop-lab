package app.p2p

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal const val CHATMAIL_FILE_LIMIT = 20_000_000L
internal const val CHATMAIL_SUBMITTED = "Sent via Chatmail"
internal const val CHATMAIL_REQUEST_QUEUED = "Queued for Chatmail delivery"
internal const val CHATMAIL_REQUEST_SENT = "Sent via Chatmail · waiting for their device"
private const val PACKET_PREFIX = "P2P/1 "
private val MESSAGE_EVENTS = setOf("IncomingMsg", "MsgsChanged", "MsgsNoticed", "MsgDelivered", "MsgFailed",
    "MsgRead", "ChatModified", "ChatlistChanged", "ReactionsChanged")

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
    private val eventLock = Any()
    private var readingEvents = false
    private var closing = false
    private var closeReady = false
    private var displayName: String? = null
    private var profileRevision: Long? = null
    private var running = false
    private var lastReadCursor: Int? = null

    private fun rpc(method: String, vararg params: Any?): Any {
        val request = obj("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to JSONArray(params.toList()))
        val reply = ChatmailNative.call(handle, request.toString().toByteArray()) ?: error("Chatmail is unavailable.")
        val response = JSONObject(reply.toString(Charsets.UTF_8))
        // Core errors can include account configuration and authentication details.
        check(!response.has("error")) { "Chatmail couldn’t complete ${method.replace('_', ' ')}." }
        return response.get("result")
    }

    private fun call(method: String, vararg params: Any?): Any = rpc(method, account, *params)

    fun listenForEvents(onMessagesChanged: (Set<Int>) -> Unit, onCall: (ChatmailCallEvent) -> Unit) {
        synchronized(eventLock) {
            if (readingEvents || closing) return
            readingEvents = true
        }
        Thread({
            try {
                while (synchronized(eventLock) { !closing }) {
                    val batch = rpc("get_next_event_batch") as JSONArray
                    var messagesChanged = false
                    val updatedMessages = mutableSetOf<Int>()
                    for (index in 0 until batch.length()) {
                        val row = batch.optJSONObject(index) ?: continue
                        if (row.optInt("contextId") != account) continue
                        val event = row.optJSONObject("event") ?: continue
                        if (synchronized(eventLock) { closing }) break
                        if (event.optString("kind") in MESSAGE_EVENTS) {
                            messagesChanged = true
                            event.optInt("msgId", event.optInt("msg_id")).takeIf { it > 9 }?.let(updatedMessages::add)
                        }
                        readChatmailCallEvent(event)?.let(onCall)
                    }
                    if (messagesChanged && synchronized(eventLock) { !closing }) onMessagesChanged(updatedMessages)
                }
            } catch (error: Exception) {
                android.util.Log.w("RelayLabChatmail", "Chatmail event reader stopped", error)
            } finally {
                synchronized(eventLock) {
                    readingEvents = false
                    if (closeReady) ChatmailNative.close(handle)
                }
            }
        }, "Chatmail events").apply { isDaemon = true }.start()
    }

    fun placeCall(chat: Int, offer: String): Int = (call("place_outgoing_call", chat, offer, false) as Number).toInt()
    fun acceptCall(id: Int, answer: String) { call("accept_incoming_call", id, answer) }
    fun endCall(id: Int) { call("end_call", id) }
    fun isRingingCall(id: Int): Boolean = (call("call_info", id) as JSONObject)
        .optJSONObject("state")?.optString("kind") == "Alerting"

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

    fun secureJoinInvite(): String = (call("get_chat_securejoin_qr_code", null) as String).also {
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

    fun nativeThread(id: Int): NativeThread? = readNativeThread(call("get_full_chat_by_id", id) as JSONObject)

    fun nativeThreadIds(): List<Int> =
        (interoperableChatIds() + (call("get_chatlist_entries", 1, null, null) as JSONArray).intValues())
            .filter { it > 9 }.distinct()

    fun nativeHistory(chat: Int, limit: Int, before: Int?, after: Int?): NativeHistory {
        val all = (call("get_message_ids", chat, false, false) as JSONArray).intValues()
        val end = before?.let(all::indexOf)?.takeIf { it >= 0 } ?: all.size
        val recentStart = (end - limit).coerceAtLeast(0)
        val previous = after?.let(all::indexOf)?.takeIf { it in 0 until end }
        val start = if (previous == null) recentStart else minOf(recentStart, previous + 1)
        val ids = all.subList(start, end)
        val rows = ids.chunked(100).flatMap { page ->
            val batch = messages(page)
            page.mapNotNull { batch.optJSONObject(it.toString())?.let { row -> readNativeMessage(row, chat) } }
        }
        return NativeHistory(rows, ids.firstOrNull(), ids.lastOrNull(), start > 0)
    }

    fun nativeSend(chat: Int, text: String, quote: Int?, file: File? = null,
                   name: String? = null, viewType: String? = null): Int {
        check(nativeThread(chat)?.let { it.canSend && !it.request } == true) { "This encrypted conversation cannot send yet." }
        val data = obj("text" to text)
        if (quote != null) data.put("quotedMessageId", quote)
        if (file != null) data.put("file", file.absolutePath).put("filename", name).put("viewtype", viewType)
        return (call("send_msg", chat, data) as Number).toInt()
    }

    fun nativeMessage(chat: Int, id: Int): NativeMessage? =
        messages(listOf(id)).optJSONObject(id.toString())?.let { readNativeMessage(it, chat) }

    fun nativeReact(id: Int, emoji: String) { call("send_reaction", id, JSONArray(listOf(emoji))) }

    fun nativeGroupInvite(chat: Int): String {
        check(nativeThread(chat)?.let { it.group && it.canSend && !it.request } == true) {
            "This group can’t share an invite."
        }
        return call("get_chat_securejoin_qr_code", chat) as String
    }

    fun nativeArchive(chat: Int, archived: Boolean) {
        call("set_chat_visibility", chat, if (archived) "Archived" else "Normal")
    }

    fun nativeRead(ids: List<Int>) {
        if (ids.isNotEmpty()) call("markseen_msgs", JSONArray(ids))
    }

    fun nativeAccept(id: Int) {
        require(nativeThread(id)?.request == true) { "This request is no longer pending." }
        call("accept_chat", id)
    }

    fun nativeCreateGroup(name: String, members: Set<Int>): Int {
        val id = (call("create_group_chat", name, false) as Number).toInt()
        try {
            members.forEach { call("add_contact_to_chat", id, it) }
        } catch (failure: Exception) {
            runCatching { call("delete_chat", id) }
            throw failure
        }
        return id
    }

    fun nativeUpdateGroup(id: Int, name: String, members: Set<Int>) {
        val group = requireNotNull(nativeThread(id)?.takeIf { it.group && it.canSend })
        if (group.name != name) call("set_chat_name", id, name)
        (members - group.members.toSet()).forEach { call("add_contact_to_chat", id, it) }
        (group.members.toSet() - members).forEach { call("remove_contact_from_chat", id, it) }
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
        // The event read holds the native handle until a blocked read returns.
        synchronized(eventLock) {
            if (closing) return
            closing = true
        }
        try {
            disconnect()
        } finally {
            synchronized(eventLock) {
                closeReady = true
                if (!readingEvents) ChatmailNative.close(handle)
            }
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

internal fun decodeChatmailPacket(text: String): JSONObject? {
    if (!text.startsWith(PACKET_PREFIX) || text.length > 256_000) return null
    val marker = text.substringBefore('\n').removePrefix(PACKET_PREFIX)
    if (!marker.matches(Regex("[0-9a-f]{64}"))) return null
    return runCatching { JSONObject(text.substringAfter('\n', "")).takeIf { it.optString("transfer") == marker } }.getOrNull()
}

internal fun isChatmailInviteLink(value: String): Boolean =
    value.startsWith("https://i.delta.chat/#") && value.substringAfter('#').isNotBlank()

// Chatmail Core validates the QR data and identifies the invite type.
internal fun isSecureJoinInvite(value: String): Boolean {
    if (value.length >= 32_768) return false
    val code = value.trim()
    return isChatmailInviteLink(code) ||
        (code.startsWith("https://i.delta.chat#") && code.substringAfter('#').isNotBlank()) ||
        (code.startsWith("openpgp4fpr:", ignoreCase = true) && code.substringAfter(':').isNotBlank())
}

internal fun isSupportedInviteCode(value: String): Boolean =
    value.trimStart().startsWith(CHATMAIL_INVITE_PREFIX) || isSecureJoinInvite(value)

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
        // Previously saved approvals can omit the explicit Chatmail route.
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
