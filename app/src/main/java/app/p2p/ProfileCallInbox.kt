package app.p2p

import org.json.JSONObject

internal data class ProfileIncomingCall(
    val profile: String, val token: String, val message: Int, val chat: Int, val expiresAt: Long
)

/** Routes an Android notification action to the exact ringing call and profile that created it. */
internal class ProfileCallInbox {
    private val pending = mutableMapOf<String, ProfileIncomingCall>()

    fun offer(profile: String, event: ChatmailCallEvent, now: Long): ProfileIncomingCall? {
        pending.values.removeAll { it.expiresAt <= now }
        if (profile in pending) return null
        return ProfileIncomingCall(profile, newId(), event.id, event.chatId, now + TALK_RING_MS)
            .also { pending[profile] = it }
    }

    fun find(profile: String, token: String, now: Long): ProfileIncomingCall? =
        pending[profile]?.takeIf { it.token == token && it.expiresAt > now }

    fun withdraw(profile: String, message: Int? = null): ProfileIncomingCall? {
        val entry = pending[profile]?.takeIf { message == null || it.message == message } ?: return null
        pending.remove(profile)
        return entry
    }
}


internal fun readRingingProfileOffer(call: ProfileIncomingCall, info: JSONObject): ChatmailCallEvent? {
    if (info.optJSONObject("state")?.optString("kind") != "Alerting") return null
    return readChatmailCallEvent(obj("kind" to "IncomingCall", "msgId" to call.message,
        "chatId" to call.chat, "placeCallInfo" to info.optString("sdpOffer"),
        "hasVideo" to info.optBoolean("hasVideo")))
}
