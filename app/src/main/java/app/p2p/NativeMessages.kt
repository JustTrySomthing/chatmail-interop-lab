package app.p2p

import org.json.JSONObject
import java.io.File
import java.util.UUID

internal fun nativeThreadKey(id: Int): String = UUID.nameUUIDFromBytes("chatmail-thread:$id".toByteArray()).toString()
internal fun nativeMessageKey(id: Int): String = UUID.nameUUIDFromBytes("chatmail-message:$id".toByteArray()).toString()
internal fun nativePeerKey(id: Int): String = "chatmail-peer:$id"

internal data class NativeThread(
    val id: Int, val name: String, val group: Boolean, val members: List<Int>,
    val canSend: Boolean, val request: Boolean, val archived: Boolean
) {
    val contactId: Int? get() = if (group) null else members.singleOrNull()
}

internal fun readNativeThread(chat: JSONObject): NativeThread? {
    val id = chat.optInt("id")
    val type = chat.optString("chatType")
    if (id <= 9 || type !in setOf("Single", "Group") || !chat.optBoolean("isEncrypted") ||
        chat.optBoolean("isDeviceChat") || chat.optBoolean("isSelfTalk")) return null
    val ids = chat.optJSONArray("contactIds")
    val members = (0 until (ids?.length() ?: 0)).mapNotNull { ids?.optInt(it)?.takeIf { id -> id > 9 } }
    if (type == "Single" && members.size != 1) return null
    return NativeThread(id, chat.optString("name").ifBlank { if (type == "Single") "Contact" else "Group" },
        type == "Group", members, chat.optBoolean("canSend"), chat.optBoolean("isContactRequest"), chat.optBoolean("archived"))
}

internal data class NativeMessage(
    val id: Int, val from: Int, val text: String, val time: Long, val state: Int, val quote: Int?,
    val quoteText: String, val quoteAuthor: String, val senderName: String,
    val attachment: ChatmailAttachment?,
    val reactions: Map<Int, List<String>>
)

internal data class ChatmailAttachment(
    val path: String?, val name: String, val mime: String, val size: Long,
    val viewType: String, val downloadState: String
) {
    fun displayData(local: File?) = obj(
        "name" to name, "mime" to mime, "size" to size, "listed" to (viewType == "File"),
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
    if (path == null && name.isBlank() && viewType !in setOf("Image", "Gif", "Sticker", "Audio", "Voice", "Video", "File")) {
        return null
    }
    val mime = row.optString("fileMime").takeUnless { row.isNull("fileMime") }.orEmpty().ifBlank {
        when (viewType) {
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

internal data class NativeHistory(val messages: List<NativeMessage>, val first: Int?, val last: Int?, val hasOlder: Boolean)

internal fun readNativeMessage(row: JSONObject, chat: Int): NativeMessage? {
    if (!isEncryptedChatmailMessage(row, chat)) return null
    val id = row.getInt("id")
    val from = row.optInt("fromId")
    if (from != 1 && from <= 9) return null
    val text = row.optString("text")
    if (text.startsWith(PACKET_PREFIX)) return null
    val attachment = readChatmailAttachment(row)
    if (text.isBlank() && attachment == null) return null
    val reactions = row.optJSONObject("reactions")?.optJSONObject("reactionsByContact")
    val values = reactions?.keys()?.asSequence()?.mapNotNull { key ->
        val entries = reactions.optJSONArray(key)
        val emoji = (0 until (entries?.length() ?: 0)).mapNotNull {
            entries?.optString(it)?.takeIf(String::isNotBlank)
        }.distinct()
        key.toIntOrNull()?.takeIf { emoji.isNotEmpty() }?.let { it to emoji }
    }?.toMap().orEmpty()
    val quote = row.optJSONObject("quote")
    return NativeMessage(id, from, text, row.optLong("sortTimestamp", row.optLong("timestamp")) * 1_000L,
        row.optInt("state"), quote?.takeIf { it.optInt("chatId") == chat }?.optInt("messageId")?.takeIf { it > 9 },
        quote?.optString("text").orEmpty(), quote?.optString("authorDisplayName").orEmpty(),
        row.optJSONObject("sender")?.optString("displayName").orEmpty(), attachment, values)
}

internal fun chatmailAttachmentViewType(mime: String, asFile: Boolean, voicemail: Boolean = false): String = when {
    asFile -> "File"
    voicemail && mime.startsWith("audio/") -> "Voice"
    mime == "image/gif" -> "Gif"
    mime.startsWith("image/") -> "Image"
    mime.startsWith("audio/") -> "Audio"
    mime.startsWith("video/") -> "Video"
    else -> "File"
}
