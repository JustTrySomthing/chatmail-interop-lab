package app.p2p

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Delta Chat and ArcaneChat Android use this local picker ceiling. Relay limits apply during delivery.
internal const val NATIVE_ATTACHMENT_FILE_LIMIT = 1024L * 1024 * 1024

internal const val CHATMAIL_SUBMITTED = "Sent via Chatmail"
internal const val CHATMAIL_REQUEST_QUEUED = "Queued for Chatmail delivery"
internal const val CHATMAIL_REQUEST_SENT = "Sent via Chatmail · waiting for their device"
internal const val PACKET_PREFIX = "P2P/1 "
private val MESSAGE_EVENTS = setOf("IncomingMsg", "MsgsChanged", "MsgsNoticed", "MsgDelivered", "MsgFailed",
    "MsgRead", "ChatModified", "ChatlistChanged", "ReactionsChanged")

internal fun InputStream.copyBoundedTo(output: OutputStream, limit: Long, error: String, bufferSize: Int = 8192): Long {
    val buffer = ByteArray(bufferSize)
    var bytes = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return bytes
        bytes += count
        check(bytes <= limit) { error }
        output.write(buffer, 0, count)
    }
}

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
    private val eventReaderExited = CountDownLatch(1)
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

    fun listenForEvents(
        onMessagesChanged: (Set<Int>) -> Unit, onWebxdc: (JSONObject) -> Unit, onCall: (ChatmailCallEvent) -> Unit
    ) {
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
                        if (event.optString("kind") in WEBXDC_EVENTS) onWebxdc(event)
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
                eventReaderExited.countDown()
            }
        }, "Chatmail events").apply { isDaemon = true }.start()
    }

    fun placeCall(chat: Int, offer: String): Int = (call("place_outgoing_call", chat, offer, false) as Number).toInt()
    fun acceptCall(id: Int, answer: String) { call("accept_incoming_call", id, answer) }
    fun endCall(id: Int) { call("end_call", id) }
    fun isRingingCall(id: Int): Boolean = (call("call_info", id) as JSONObject)
        .optJSONObject("state")?.optString("kind") == "Alerting"

    fun ringingOffer(call: ProfileIncomingCall): ChatmailCallEvent? =
        readRingingProfileOffer(call, this.call("call_info", call.message) as JSONObject)

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
            // Earlier signed P2P messages need their complete text for verification.
            call("batch_set_config", obj("displayname" to name, "download_limit" to "0", "bot" to "1"))
            displayName = name
        }
        if (!running) {
            if (!isConfigured()) {
                call("set_config", "mdns_enabled", "0")
                call("init_transports", setupQr)
            }
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

    fun secureJoinInvite(): String = secureJoinLink(null)

    private fun secureJoinLink(chat: Int?): String = (call("get_chat_securejoin_qr_code", chat) as String).also {
        check(isSecureJoinInvite(it)) { "Chatmail returned an invalid invite." }
    }

    fun joinLegacyContact(code: String): Int {
        require(inviteKind(code) == "askVerifyContact") { "This Chatmail invite can’t be used to add a contact." }
        return joinChecked(code)
    }

    fun inviteKind(code: String): String = (call("check_qr", code) as JSONObject).optString("kind")

    fun joinChecked(code: String): Int = (call("secure_join", code) as Number).toInt()

    fun channels(): List<ChatmailChannel> {
        val active = chatIds().mapNotNull(::channel)
        val archived = chatIds(ARCHIVED_CHATS_ONLY).mapNotNull(::channel).filter { it.owned }
        return (active + archived).distinctBy(ChatmailChannel::id).sortedByDescending(ChatmailChannel::pinned)
    }

    fun channel(id: Int): ChatmailChannel? = parseChatmailChannel(fullChat(id))

    fun channelPosts(channel: ChatmailChannel, limit: Int): ChannelHistory {
        val ids = messageIds(channel.id)
        val posts = messageRows(ids.takeLast(limit)).mapNotNull { parseChannelPost(it, channel) }
        return ChannelHistory(posts.toList(), ids.size > limit)
    }

    fun channelPost(id: Int): ChannelPost? {
        val row = messageRows(listOf(id)).firstOrNull() ?: return null
        val chatId = row.optInt("chatId")
        if (chatId <= 9) return null
        return channel(chatId)?.let { parseChannelPost(row, it) }
    }

    fun createChannel(name: String): Int = (call("create_broadcast", name) as Number).toInt()

    fun chatDescription(id: Int): String = call("get_chat_description", id) as String

    private fun updateChatDescription(id: Int, description: String) {
        if (description != chatDescription(id)) call("set_chat_description", id, description)
    }

    fun updateChannel(id: Int, name: String, description: String) {
        val channel = requireOwnedChannel(id)
        if (name != channel.name) call("set_chat_name", id, name)
        updateChatDescription(id, description)
    }

    fun setChannelImage(id: Int, file: File?) {
        requireOwnedChannel(id)
        setChatImage(id, file)
    }

    fun channelViewCount(message: Int): Int {
        val row = message(message)
        val channel = requireOwnedChannel(row.optInt("chatId"))
        require(isEncryptedChatmailMessage(row, channel.id)) { "This post is unavailable." }
        return (call("get_message_read_receipt_count", message) as Number).toInt()
    }

    fun pinChannel(id: Int, pinned: Boolean) {
        requireOwnedChannel(id)
        call("set_chat_visibility", id, if (pinned) "Pinned" else "Normal")
    }

    private fun requireOwnedChannel(id: Int): ChatmailChannel =
        requireNotNull(channel(id)?.takeIf(ChatmailChannel::owned)) { "This channel is unavailable or read-only." }

    fun channelInvite(channel: ChatmailChannel): String {
        require(channel.owned) { "Only the owner can share this channel’s invite." }
        return secureJoinLink(channel.id)
    }

    fun postToChannel(channel: ChatmailChannel, text: String, file: File? = null,
                      name: String? = null, viewType: String? = null) {
        check(channel.owned && channel.canPost) { "This channel can’t publish posts yet." }
        require((text.isNotBlank() || file != null) && text.length <= INTEROP_TEXT_LIMIT) {
            "Add a file or enter a post of up to $INTEROP_TEXT_LIMIT characters."
        }
        sendMessage(channel.id, text, file = file, name = name, viewType = viewType)
    }

    fun noticeChannel(channel: ChatmailChannel) {
        if (!channel.owned && channel.unread > 0) call("marknoticed_chat", channel.id)
    }

    fun canSendLegacyPacket(chat: Int): Boolean {
        val details = fullChat(chat)
        return details.optBoolean("isEncrypted") && details.optBoolean("canSend")
    }

    fun addRelay(code: String) {
        require(inviteKind(code) in setOf("account", "login")) { "Use a Chatmail relay or login QR code." }
        call("add_transport_from_qr", code)
    }

    fun relayAddresses(): List<String> {
        val rows = call("list_transports") as JSONArray
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.optString("addr")?.takeIf(String::isNotBlank)
        }.distinct()
    }

    fun removeRelay(address: String) {
        val addresses = relayAddresses()
        require(address in addresses) { "This relay is unavailable." }
        check(addresses.size > 1) { "Add another relay before removing this one." }
        call("delete_transport", address)
    }

    fun contactEncryptionInfo(chat: Int): String {
        val contactId = requireNotNull(nativeThread(chat)?.contactId) { "This encrypted contact is unavailable." }
        return call("get_contact_encryption_info", contactId) as String
    }

    fun contacts(): List<ChatmailContact> {
        val entries = call("get_contacts", 0, null) as JSONArray
        return (0 until entries.length()).mapNotNull { entries.optJSONObject(it)?.let(::parseChatmailContact) }
    }

    private fun contact(id: Int): ChatmailContact? = parseChatmailContact(call("get_contact", id) as JSONObject)

    fun openContact(id: Int): Int {
        require(id > 9 && contact(id) != null) {
            "This Chatmail contact is unavailable."
        }
        return (call("create_chat_by_contact_id", id) as Number).toInt()
    }

    private fun fullChat(id: Int): JSONObject = call("get_full_chat_by_id", id) as JSONObject

    private fun chatIds(flags: Int? = null): List<Int> =
        (call("get_chatlist_entries", flags, null, null) as JSONArray).intValues().filter { it > 9 }

    private fun messageIds(chat: Int): List<Int> =
        (call("get_message_ids", chat, false, false) as JSONArray).intValues()

    fun nativeThread(id: Int): NativeThread? = readNativeThread(fullChat(id))

    fun nativeThreadIds(): List<Int> = (chatIds() + chatIds(ARCHIVED_CHATS_ONLY)).distinct()

    fun nativeHistory(chat: Int, limit: Int, before: Int?, after: Int?, cached: Set<Int>): NativeHistory {
        val all = messageIds(chat)
        val window = chatmailHistoryWindow(all, limit, before, after, cached)
        val ids = all.subList(window.first, window.last + 1)
        val rows = nativeRows(chat, ids)
        return NativeHistory(rows, ids.firstOrNull(), ids.lastOrNull(), window.first > 0, all.toSet())
    }

    fun nativeSend(chat: Int, text: String, quote: Int?, file: File? = null,
                   name: String? = null, viewType: String? = null): Int {
        check(nativeThread(chat)?.let { it.canSend && !it.request } == true) { "This encrypted conversation cannot send yet." }
        return sendMessage(chat, text, quote, file, name, viewType)
    }

    fun nativeMessage(chat: Int, id: Int): NativeMessage? = nativeRows(chat, listOf(id)).firstOrNull()

    private fun nativeRows(chat: Int, ids: List<Int>): List<NativeMessage> {
        val rows = messageRows(ids).toList()
        val parents = rows.asSequence().filter { it.optBoolean("isInfo") && it.optString("systemMessageType") == "WebxdcInfoMessage" }
            .map { it.optInt("parentId") }.filter { it > 9 }.distinct().toList()
        val apps = messageRows(parents).filter {
            isEncryptedChatmailMessage(it, chat) && it.optString("viewType") == "Webxdc"
        }.map { it.getInt("id") }.toSet()
        return rows.mapNotNull { readNativeMessage(it, chat, it.optInt("parentId").takeIf { id ->
            id in apps && it.optBoolean("isInfo")
        }) }
    }

    fun nativeReact(id: Int, emoji: String) {
        call("send_reaction", id, JSONArray(if (emoji.isEmpty()) emptyList() else listOf(emoji)))
    }

    fun alertPreferences() = ProfileAlertPreferences(
        mentions = mentionsNotifyWhenMuted(),
        calls = call("get_config", "who_can_call_me") != "2"
    )

    fun mentionsNotifyWhenMuted(): Boolean = call("get_config", "ui.mute_mentions_if_muted") != "1"

    fun setMentionAlerts(enabled: Boolean) {
        call("set_config", "ui.mute_mentions_if_muted", if (enabled) "0" else "1")
    }

    fun setCallAlerts(enabled: Boolean) {
        call("set_config", "who_can_call_me", if (enabled) "1" else "2")
    }

    fun outgoingMediaQuality() = OutgoingMediaQuality.fromCore(call("get_config", "media_quality"))

    fun setOutgoingMediaQuality(quality: OutgoingMediaQuality) {
        call("set_config", "media_quality", quality.coreValue)
    }

    fun outgoingMediaPolicy(): OutgoingMediaPolicy {
        val quality = outgoingMediaQuality()
        val recommended = runCatching { call("get_config", "sys.msgsize_max_recommended").toString().toLong() }
            .getOrNull()?.takeIf { it in 1..NATIVE_ATTACHMENT_FILE_LIMIT }
        return if (recommended == null) OutgoingMediaPolicy(quality) else OutgoingMediaPolicy(quality, recommended)
    }

    fun readReceiptsEnabled(): Boolean = call("get_config", "mdns_enabled") == "1"
    fun setReadReceipts(enabled: Boolean) { call("set_config", "mdns_enabled", if (enabled) "1" else "0") }

    fun forwardMessage(id: Int, target: Int) { call("forward_messages", JSONArray(listOf(id)), target) }

    fun makeContactCard(id: Int): String {
        require(contact(id) != null) { "This Chatmail contact is unavailable." }
        return call("make_vcard", JSONArray(listOf(id))) as String
    }

    fun importContactCard(file: File): List<Int> =
        (call("import_vcard", file.absolutePath) as JSONArray).intValues()

    fun messageDetails(message: NativeMessage): NativeMessageDetails {
        val receipts = if (message.from == 1) (call("get_message_read_receipts", message.id) as JSONArray)
        else JSONArray()
        val readers = (0 until receipts.length()).mapNotNull { index ->
            val row = receipts.optJSONObject(index) ?: return@mapNotNull null
            val timestamp = row.optLong("timestamp")
            if (timestamp <= 0) return@mapNotNull null
            val name = contact(row.optInt("contactId"))?.name ?: "Contact"
            name to timestamp * 1_000
        }
        val info = call("get_message_info_object", message.id) as JSONObject
        return NativeMessageDetails(message.time, message.from == 1, message.state, readers,
            info.optLong("ephemeralTimestamp").takeIf { it > 0 }?.times(1_000))
    }

    fun editMessage(id: Int, text: String) { call("send_edit_request", id, text) }
    fun deleteMessageForEveryone(id: Int) { call("delete_messages_for_all", JSONArray(listOf(id))) }
    fun setMessagePinned(id: Int, pinned: Boolean) { call("set_pinned_message_state", id, pinned) }
    fun pinnedMessages(chat: Int): List<NativeMessage> {
        val ids = call("get_pinned_messages", chat) as JSONArray
        return nativeRows(chat, (0 until ids.length()).map(ids::getInt))
    }
    fun saveMessage(id: Int) { call("save_msgs", JSONArray(listOf(id))) }
    fun savedMessagesChat(): Int = (call("create_chat_by_contact_id", 1) as Number).toInt()
    fun disappearingTimer(chat: Int): Int = (call("get_chat_ephemeral_timer", chat) as Number).toInt()
    fun setDisappearingTimer(chat: Int, seconds: Int) { call("set_chat_ephemeral_timer", chat, seconds) }
    fun setChatImage(chat: Int, file: File?) { call("set_chat_profile_image", chat, file?.absolutePath) }

    fun nativeGroupInvite(chat: Int): String {
        check(nativeThread(chat)?.let { it.group && it.canSend && !it.request } == true) {
            "This group can’t share an invite."
        }
        return secureJoinLink(chat)
    }

    fun nativeArchive(chat: Int, archived: Boolean) {
        if (fullChat(chat).optBoolean("archived") != archived) {
            call("set_chat_visibility", chat, if (archived) "Archived" else "Normal")
        }
    }

    fun nativeRead(ids: List<Int>) {
        if (ids.isNotEmpty()) call("markseen_msgs", JSONArray(ids))
    }

    fun nativeAccept(id: Int) {
        require(nativeThread(id)?.request == true) { "This request is no longer pending." }
        call("accept_chat", id)
    }

    fun nativeCreateGroup(name: String, description: String, members: Set<Int>, photo: File? = null): Int {
        val id = (call("create_group_chat", name, false) as Number).toInt()
        try {
            members.forEach { call("add_contact_to_chat", id, it) }
            if (description.isNotEmpty()) updateChatDescription(id, description)
            if (photo != null) setChatImage(id, photo)
        } catch (failure: Exception) {
            runCatching { call("delete_chat", id) }
            throw failure
        }
        return id
    }

    fun nativeUpdateGroup(id: Int, name: String, description: String?, members: Set<Int>) {
        val group = requireNotNull(nativeThread(id)?.takeIf { it.group && it.canSend })
        if (group.name != name) call("set_chat_name", id, name)
        (members - group.members.toSet()).forEach { call("add_contact_to_chat", id, it) }
        (group.members.toSet() - members).forEach { call("remove_contact_from_chat", id, it) }
        if (description != null) updateChatDescription(id, description)
    }

    fun provideProfileTransfer() { call("provide_backup") }
    fun profileTransferQr(): String = call("get_backup_qr") as String
    fun stopProfileTransfer() { call("stop_ongoing_process") }

    fun receiveProfileTransfer(code: String) {
        require(inviteKind(code).equals("backup2", ignoreCase = true)) {
            "Scan an Add Second Device QR code."
        }
        call("get_backup", code)
    }

    fun exportProfileBackup(directory: File) { call("export_backup", directory.absolutePath, null) }
    fun importProfileBackup(archive: File) { call("import_backup", archive.absolutePath, null) }
    fun profileName(): String = call("get_config", "displayname").takeUnless { it == JSONObject.NULL }?.toString().orEmpty()

    fun messageLocation(chat: Int, message: Int, timestamp: Long): Pair<Double, Double>? {
        val rows = call("get_locations", chat, null, timestamp, timestamp) as JSONArray
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optInt("msgId") != message || !row.optBoolean("isIndependent")) continue
            val latitude = row.optDouble("latitude")
            val longitude = row.optDouble("longitude")
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0) return latitude to longitude
        }
        return null
    }

    fun isConfigured(): Boolean = call("is_configured") == true

    fun chatContactId(id: Int): Int? = nativeThread(id)?.contactId

    fun callContact(id: Int): ChatmailCallContact? {
        val thread = nativeThread(id) ?: return null
        val profile = thread.contactId?.let(::contact) ?: return null
        return callContactFor(thread, profile)
    }

    fun legacyInboxAfter(after: Int): List<Int> {
        if (lastReadCursor != after) {
            call("set_config", "last_msg_id", after.toString())
            lastReadCursor = after
        }
        val ids = call("get_next_msgs") as JSONArray
        return (0 until ids.length()).map { ids.getInt(it) }
    }

    fun cancelLegacyPacket(id: Int, chat: Int, transfer: String) {
        val message = messages(listOf(id)).optJSONObject(id.toString())
            ?: error("Chatmail message state is unavailable.")
        if (message.optString("error") == "Message does not exist") return
        check(message.optString("kind") == "message") { "Chatmail message state is unavailable." }
        if (isPendingChatmailSend(message, chat, transfer)) call("delete_messages", JSONArray(listOf(id)))
    }

    fun webxdcInfo(id: Int): JSONObject = (call("get_webxdc_info", id) as JSONObject)
        .put("selfName", displayName ?: (call("get_config", "displayname") as? String).orEmpty())
    fun webxdcUpdates(id: Int, after: Int): String = call("get_webxdc_status_updates", id, after) as String
    fun webxdcUpdate(id: Int, update: String) { call("send_webxdc_status_update", id, update, null) }
    fun webxdcAdvertise(id: Int) { call("send_webxdc_realtime_advertisement", id) }
    fun webxdcSendRealtime(id: Int, bytes: JSONArray) { call("send_webxdc_realtime_data", id, bytes) }
    fun webxdcLeave(id: Int) { call("leave_webxdc_realtime", id) }

    fun download(id: Int) { call("download_full_message", id) }

    fun message(id: Int): JSONObject = call("get_message", id) as JSONObject

    fun messages(ids: List<Int>): JSONObject =
        if (ids.isEmpty()) obj() else call("get_messages", JSONArray(ids)) as JSONObject

    private fun messageRows(ids: List<Int>): Sequence<JSONObject> =
        ids.asSequence().chunked(100).flatMap { page ->
            val batch = messages(page)
            page.asSequence().mapNotNull { batch.optJSONObject(it.toString()) }
        }

    private fun sendMessage(chat: Int, text: String, quote: Int? = null, file: File? = null,
                            name: String? = null, viewType: String? = null): Int {
        val data = obj("text" to text)
        if (quote != null) data.put("quotedMessageId", quote)
        if (file != null) {
            data.put("file", file.absolutePath).put("filename", name)
            if (viewType != null) data.put("viewtype", viewType)
        }
        return (call("send_msg", chat, data) as Number).toInt()
    }

    fun sendLegacyPacket(chat: Int, packet: JSONObject, file: File? = null, name: String? = null): Int {
        val text = PACKET_PREFIX + packet.getString("transfer") + "\n" + packet
        return sendMessage(chat, text, file = file, name = name)
    }

    fun findLegacyPacket(chat: Int, transfer: String): Int? {
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
            val waiting = synchronized(eventLock) { readingEvents }
            if (waiting) runCatching {
                // A stopped or offline Core can still be waiting in get_next_event_batch.
                // Setting the same local-only tag emits AccountsItemChanged without changing the profile.
                val tag = call("get_config", "private_tag").takeUnless { it == JSONObject.NULL }
                call("set_config", "private_tag", tag)
            }
            synchronized(eventLock) {
                closeReady = true
                if (!readingEvents) ChatmailNative.close(handle)
            }
            if (waiting) {
                try {
                    eventReaderExited.await(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
}

internal suspend fun chatAction(action: suspend () -> Unit): String? = try {
    action()
    null
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (error: Exception) {
    error.message ?: "Couldn’t complete that action."
} catch (_: LinkageError) {
    "Chatmail couldn’t start on this device."
}

private fun JSONArray.intValues(): List<Int> = (0 until length()).map(::getInt)

private const val ARCHIVED_CHATS_ONLY = 1

internal const val INTEROP_TEXT_LIMIT = 12_000

internal data class ChatmailChannel(
    val id: Int, val name: String, val owned: Boolean, val canPost: Boolean, val unread: Int,
    val pinned: Boolean = false, val imagePath: String? = null
)
internal data class ChannelPost(
    val id: Int, val channelId: Int, val channelName: String, val text: String,
    val time: Long, val state: Int, val attachment: ChatmailAttachment?, val localFile: File? = null,
    val reactions: Map<Int, List<String>> = emptyMap()
)
internal data class ChannelHistory(val posts: List<ChannelPost>, val hasOlder: Boolean)

internal fun parseChatmailChannel(chat: JSONObject): ChatmailChannel? {
    val id = chat.optInt("id")
    val type = chat.optString("chatType")
    if (id <= 9 || !chat.optBoolean("isEncrypted") ||
        (type != "InBroadcast" && type != "OutBroadcast")) return null
    val owned = type == "OutBroadcast"
    return ChatmailChannel(id, chat.optString("name").ifBlank { "Channel" }, owned,
        owned && chat.optBoolean("canSend"), chat.optInt("freshMessageCounter"), chat.optBoolean("pinned"),
        chat.optString("profileImage").takeUnless { chat.isNull("profileImage") || it.isBlank() })
}

internal fun parseChannelPost(message: JSONObject, channel: ChatmailChannel): ChannelPost? {
    if (!isEncryptedChatmailMessage(message, channel.id)) return null
    val id = message.getInt("id")
    val text = message.optString("text")
    val attachment = readChatmailAttachment(message)
    if (text.isBlank() && attachment == null) return null
    return ChannelPost(id, channel.id, channel.name, text,
        message.optLong("timestamp") * 1_000L, message.optInt("state"), attachment,
        reactions = readNativeReactions(message))
}

internal data class ChatmailContact(
    val id: Int, val name: String, val bio: String = "", val photoPath: String? = null,
    val encryptionAvailable: Boolean = false, val lastSeen: Long = 0L
)

internal fun parseChatmailContact(data: JSONObject): ChatmailContact? {
    val id = data.optInt("id")
    if (id <= 9 || !data.optBoolean("isKeyContact") || data.optBoolean("isBlocked")) return null
    val name = data.optString("displayName").ifBlank { "Chatmail contact" }
    return ChatmailContact(id, name, data.optString("status"),
        data.optString("profileImage").takeUnless { data.isNull("profileImage") || it.isBlank() },
        data.optBoolean("e2eeAvail"), data.optLong("lastSeen"))
}

internal data class ChatmailCallContact(val id: Int, val name: String)

internal fun callContactFor(thread: NativeThread, profile: ChatmailContact): ChatmailCallContact? =
    if (thread.contactId == profile.id && thread.canSend && !thread.request && profile.encryptionAvailable)
        ChatmailCallContact(thread.id, profile.name) else null

internal fun isEncryptedChatmailMessage(message: JSONObject, chat: Int): Boolean =
    message.optInt("id") > 9 && message.optInt("chatId") == chat && message.optString("kind") == "message" &&
        !message.optBoolean("isInfo") && message.optBoolean("showPadlock")

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
    offer.optLong("size", -1) in 0..NATIVE_ATTACHMENT_FILE_LIMIT &&
        message.optLong("fileBytes", -1) in 0..NATIVE_ATTACHMENT_FILE_LIMIT
