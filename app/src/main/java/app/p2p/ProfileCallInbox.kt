package app.p2p

import android.content.Context
import org.json.JSONObject

internal data class ProfileIncomingCall(
    val profile: String, val token: String, val message: Int, val chat: Int, val expiresAt: Long
)

/** Routes an Android notification action to the exact ringing call and profile that created it. */
internal class ProfileCallInbox(context: Context) {
    private val pending = context.getSharedPreferences("incoming-profile-calls", Context.MODE_PRIVATE)

    fun offer(profile: String, event: ChatmailCallEvent, now: Long): ProfileIncomingCall? {
        if (read(profile)?.expiresAt?.let { it - now in 1..TALK_RING_MS } == true) return null
        val call = ProfileIncomingCall(profile, newId(), event.id, event.chatId, now + TALK_RING_MS)
        val record = obj("token" to call.token, "message" to call.message, "chat" to call.chat,
            "expiresAt" to call.expiresAt)
        // Save before posting the notification, which Android can retain after the process exits.
        return call.takeIf { pending.edit().putString(profile, record.toString()).commit() }
    }

    fun find(profile: String, token: String, now: Long): ProfileIncomingCall? =
        read(profile)?.takeIf { it.token == token && it.expiresAt - now in 1..TALK_RING_MS }

    fun withdraw(profile: String, message: Int? = null): ProfileIncomingCall? {
        val entry = read(profile)?.takeIf { message == null || it.message == message } ?: return null
        pending.edit().remove(profile).commit()
        return entry
    }

    private fun read(profile: String): ProfileIncomingCall? = try {
        pending.getString(profile, null)?.let { stored ->
            val data = JSONObject(stored)
            val token = data.optString("token")
            val message = data.optInt("message")
            val chat = data.optInt("chat")
            val expiresAt = data.optLong("expiresAt")
            if (isId(token) && message > 9 && chat > 9 && expiresAt > 0) {
                ProfileIncomingCall(profile, token, message, chat, expiresAt)
            } else null
        }
    } catch (_: org.json.JSONException) {
        null
    }
}

internal fun readRingingProfileOffer(call: ProfileIncomingCall, info: JSONObject): ChatmailCallEvent? {
    if (info.optJSONObject("state")?.optString("kind") != "Alerting") return null
    return readChatmailCallEvent(obj("kind" to "IncomingCall", "msgId" to call.message,
        "chatId" to call.chat, "placeCallInfo" to info.optString("sdpOffer"),
        "hasVideo" to info.optBoolean("hasVideo")))
}
