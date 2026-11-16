package app.p2p

import org.json.JSONObject
import java.io.File
import java.util.UUID

internal fun nativeThreadKey(id: Int): String = UUID.nameUUIDFromBytes("chatmail-thread:$id".toByteArray()).toString()
internal fun nativeMessageKey(id: Int): String = UUID.nameUUIDFromBytes("chatmail-message:$id".toByteArray()).toString()
internal fun nativePeerKey(id: Int): String = "chatmail-peer:$id"

internal data class NativeThread(
    val id: Int, val name: String, val group: Boolean, val members: List<Int>,
    val canSend: Boolean, val request: Boolean, val archived: Boolean,
    val selfTalk: Boolean = false, val imagePath: String? = null
) {
    val contactId: Int? get() = if (group) null else members.singleOrNull()
}

internal fun readNativeThread(chat: JSONObject): NativeThread? {
    val id = chat.optInt("id")
    val type = chat.optString("chatType")
    if (id <= 9 || type !in setOf("Single", "Group") || !chat.optBoolean("isEncrypted") ||
        chat.optBoolean("isDeviceChat")) return null
    val selfTalk = type == "Single" && chat.optBoolean("isSelfTalk")
    val ids = chat.optJSONArray("contactIds")
    val members = (0 until (ids?.length() ?: 0)).mapNotNull { ids?.optInt(it)?.takeIf { id -> id > 9 } }
    if (selfTalk && (members.isNotEmpty() || ids?.optInt(0) != 1)) return null
    if (type == "Single" && !selfTalk && members.size != 1) return null
    return NativeThread(id, chat.optString("name").ifBlank { if (type == "Single") "Contact" else "Group" },
        type == "Group", members, chat.optBoolean("canSend"), chat.optBoolean("isContactRequest"), chat.optBoolean("archived"),
        selfTalk, chat.optString("profileImage").takeIf { type == "Group" && !chat.isNull("profileImage") && it.isNotBlank() })
}

internal data class NativeMessage(
    val id: Int, val from: Int, val text: String, val time: Long, val state: Int, val quote: Int?,
    val quoteText: String, val quoteAuthor: String, val senderName: String,
    val attachment: ChatmailAttachment?,
    val reactions: Map<Int, List<String>>,
    val webxdcParent: Int? = null, val webxdcHref: String? = null,
    val edited: Boolean = false, val pinned: Boolean = false, val hasLocation: Boolean = false
)

internal data class ChatmailAttachment(
    val path: String?, val name: String, val mime: String, val size: Long,
    val viewType: String, val downloadState: String
) {
    fun displayData(local: File?) = obj(
        "name" to name, "mime" to mime, "size" to size, "listed" to (viewType == "File"),
        "webxdc" to (viewType == "Webxdc"),
        "state" to when {
            local != null || downloadState == "Done" -> "Complete"
            downloadState == "InProgress" -> "Downloading"
            downloadState == "Undecipherable" -> "Expired"
            downloadState == "Failure" -> "Source unavailable"
            else -> "Available"
        }
    )
}

internal fun readChatmailAttachment(row: JSONObject): ChatmailAttachment? {
    val path = row.optString("file").takeUnless { it.isBlank() || row.isNull("file") }
    val name = row.optString("fileName").takeUnless { row.isNull("fileName") }.orEmpty()
    val viewType = row.optString("viewType")
    if (path == null && name.isBlank() && viewType !in setOf("Image", "Gif", "Sticker", "Audio", "Voice", "Video", "File", "Webxdc")) {
        return null
    }
    val mime = row.optString("fileMime").takeUnless { row.isNull("fileMime") }.orEmpty().ifBlank {
        when (viewType) {
            "Webxdc" -> WEBXDC_MIME
            "Gif" -> "image/gif"
            "Image", "Sticker" -> "image/*"
            "Audio", "Voice" -> "audio/*"
            "Video" -> "video/*"
            else -> ""
        }
    }
    return ChatmailAttachment(path, name.ifBlank { path?.substringAfterLast('/') ?: "Attachment" },
        mime, row.optLong("fileBytes"), viewType, row.optString("downloadState"))
}

internal data class NativeHistory(
    val messages: List<NativeMessage>, val first: Int?, val last: Int?, val hasOlder: Boolean,
    val existingIds: Set<Int> = emptySet()
)

internal fun chatmailHistoryWindow(
    ids: List<Int>, limit: Int, before: Int?, after: Int?, cached: Set<Int>
): IntRange {
    val anchor = before?.let(ids::indexOf)?.takeIf { it >= 0 }
    // Core may remove the oldest cached message between requests.
    val fallback = if (before == null || anchor != null) null
        else ids.indexOfFirst { it in cached }.takeIf { it >= 0 }
    val end = anchor ?: fallback ?: ids.size
    val recentStart = (end - limit).coerceAtLeast(0)
    val previous = after?.let { id ->
        ids.indexOf(id).takeIf { it in 0 until end }
            ?: ids.subList(0, end).indexOfLast { it in cached }.takeIf { it >= 0 }
    }
    val start = if (previous == null) recentStart else minOf(recentStart, previous + 1)
    return start until end
}

internal fun Collection<Record>.unreadThreadItems(thread: String): List<Record> = filter {
    val data = it.data()
    (it.kind == "message" || it.kind == "file-received") && data.optString("thread") == thread &&
        data.optBoolean("unread") &&
        !data.optBoolean("voicemail") && !data.optBoolean("locallyDeleted")
}

internal fun Collection<Record>.removedChatmailMessages(thread: String, existingIds: Set<Int>): List<Record> = filter {
    val data = it.data()
    val id = data.optInt("nativeMessage")
    id > 9 && data.optString("thread") == thread && id !in existingIds
}

internal fun readNativeMessage(row: JSONObject, chat: Int, webxdcParent: Int? = null): NativeMessage? {
    if (webxdcParent == null && !isEncryptedChatmailMessage(row, chat)) return null
    if (webxdcParent != null && (row.optInt("chatId") != chat || row.optInt("id") <= 9 ||
        row.optString("kind") != "message" || !row.optBoolean("isInfo") ||
        row.optString("systemMessageType") != "WebxdcInfoMessage" || row.optInt("parentId") != webxdcParent)) return null
    val id = row.getInt("id")
    val from = row.optInt("fromId")
    if (from != 1 && from <= 9) return null
    val text = row.optString("text")
    if (text.startsWith(PACKET_PREFIX)) return null
    val attachment = readChatmailAttachment(row)
    if (text.isBlank() && attachment == null && !row.optBoolean("hasLocation")) return null
    val quote = row.optJSONObject("quote")
    return NativeMessage(id, from, text, row.optLong("sortTimestamp", row.optLong("timestamp")) * 1_000L,
        row.optInt("state"), quote?.takeIf { it.optInt("chatId") == chat }?.optInt("messageId")?.takeIf { it > 9 },
        quote?.optString("text").orEmpty(), quote?.optString("authorDisplayName").orEmpty(),
        row.optJSONObject("sender")?.optString("displayName").orEmpty(), attachment, readNativeReactions(row),
        webxdcParent, row.optString("webxdcHref").takeIf { webxdcParent != null && !row.isNull("webxdcHref") },
        row.optBoolean("isEdited"), row.optBoolean("isPinned"), row.optBoolean("hasLocation"))
}

internal fun chatmailAttachmentViewType(
    mime: String, asFile: Boolean, voicemail: Boolean = false, name: String = ""
): String = when {
    mime == WEBXDC_MIME || name.endsWith(".xdc", ignoreCase = true) -> "Webxdc"
    asFile -> "File"
    isTgsSticker(mime, name) -> "Sticker"
    voicemail && mime.startsWith("audio/") -> "Voice"
    mime == "image/gif" -> "Gif"
    mime.startsWith("image/") -> "Image"
    mime.startsWith("audio/") -> "Audio"
    mime.startsWith("video/") -> "Video"
    else -> "File"
}

internal fun readNativeReactions(row: JSONObject): Map<Int, List<String>> {
    val reactions = row.optJSONObject("reactions")?.optJSONObject("reactionsByContact")
    return reactions?.keys()?.asSequence()?.mapNotNull { key ->
        val entries = reactions.optJSONArray(key)
        val emoji = (0 until (entries?.length() ?: 0)).mapNotNull {
            entries?.optString(it)?.takeIf(String::isNotBlank)
        }.distinct()
        key.toIntOrNull()?.takeIf { emoji.isNotEmpty() }?.let { it to emoji }
    }?.toMap().orEmpty()
}
