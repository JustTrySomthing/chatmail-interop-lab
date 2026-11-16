package app.p2p

import org.json.JSONObject
import java.util.UUID

internal fun nativeThreadKey(id: Int): String = UUID.nameUUIDFromBytes("chatmail-thread:$id".toByteArray()).toString()
internal fun nativeMessageKey(id: Int): String = UUID.nameUUIDFromBytes("chatmail-message:$id".toByteArray()).toString()
internal fun nativePeerKey(id: Int): String = "chatmail-peer:$id"

internal data class NativeThread(
    val id: Int, val name: String, val group: Boolean, val members: List<Int>,
    val canSend: Boolean, val request: Boolean, val archived: Boolean
)

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
    val path: String?, val name: String, val mime: String, val size: Long, val viewType: String,
    val reactions: Map<Int, List<String>>
)

internal data class NativeHistory(val messages: List<NativeMessage>, val first: Int?, val last: Int?, val hasOlder: Boolean)

internal fun readNativeMessage(row: JSONObject, chat: Int): NativeMessage? {
    val id = row.optInt("id")
    val from = row.optInt("fromId")
    if (id <= 9 || (from != 1 && from <= 9) || row.optInt("chatId") != chat ||
        row.optString("kind") != "message" || row.optBoolean("isInfo") || !row.optBoolean("showPadlock")) return null
    val text = row.optString("text")
    if (text.startsWith("P2P/1 ")) return null
    val path = row.optString("file").takeUnless { it.isBlank() || row.isNull("file") }
    val name = row.optString("fileName").takeUnless { row.isNull("fileName") }.orEmpty()
    if (text.isBlank() && path == null && name.isBlank()) return null
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
        row.optJSONObject("sender")?.optString("displayName").orEmpty(), path, name,
        row.optString("fileMime").takeUnless { row.isNull("fileMime") }.orEmpty(),
        row.optLong("fileBytes"), row.optString("viewType"), values)
}
