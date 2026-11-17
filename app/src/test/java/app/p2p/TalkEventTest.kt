package app.p2p

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TalkEventTest {
    @Test
    fun notificationTokensAreScopedToTheProfileAndExpireWithTheCall() {
        val inbox = ProfileCallInbox(RuntimeEnvironment.getApplication())
        val now = 100_000L
        val event = ChatmailCallEvent("IncomingCall", 12, 22, "v=0", false, false)
        val first = requireNotNull(inbox.offer("profile-a", event, now))
        val second = requireNotNull(inbox.offer("profile-b", event, now))
        assertNotEquals(first.token, second.token)
        assertNull(inbox.offer("profile-a", event, now + 1))
        assertNull(inbox.find("profile-b", first.token, now + 1))
        assertEquals(first, inbox.find("profile-a", first.token, first.expiresAt - 1))
        assertNull(inbox.find("profile-a", first.token, first.expiresAt))
        assertNull(inbox.withdraw("profile-b", 13))
        assertEquals(second, inbox.withdraw("profile-b", 12))
        assertNull(inbox.find("profile-b", second.token, now + 1))
    }

    @Test
    fun notificationRoutingSurvivesProcessRecreationWithoutSavingTheMediaOffer() {
        val app = RuntimeEnvironment.getApplication()
        val now = 100_000L
        val offer = "v=0\r\na=private-test-offer"
        val pending = requireNotNull(ProfileCallInbox(app).offer("profile-a",
            ChatmailCallEvent("IncomingCall", 12, 22, offer, true, false), now))

        val recreated = ProfileCallInbox(app)
        assertEquals(pending, recreated.find("profile-a", pending.token, now + 1))
        assertNull(recreated.find("profile-b", pending.token, now + 1))
        assertNull(recreated.find("profile-a", pending.token, now - 1))
        assertNull(recreated.find("profile-a", pending.token, now + TALK_RING_MS))
        val stored = app.getSharedPreferences("incoming-profile-calls", android.content.Context.MODE_PRIVATE).all
        assertFalse(stored.values.any { it.toString().contains("private-test-offer") })

        recreated.withdraw("profile-a", pending.message)
        assertNull(ProfileCallInbox(app).find("profile-a", pending.token, now + 2))
    }

    @Test
    fun restoresTheOriginalOfferOnlyWhileCoreReportsThatTheCallIsRinging() {
        val pending = ProfileIncomingCall("profile-a", "token", 13, 14, 100_000L)
        val sdp = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
        val core = obj("state" to obj("kind" to "Alerting"), "sdpOffer" to sdp, "hasVideo" to true)
        assertEquals(ChatmailCallEvent("IncomingCall", 13, 14, sdp, true, false), readRingingProfileOffer(pending, core))
        assertNull(readRingingProfileOffer(pending, core.put("state", obj("kind" to "Missed"))))
        assertNull(readRingingProfileOffer(pending, core.put("state", obj("kind" to "Active"))))
        assertNull(readRingingProfileOffer(pending, core.put("state", obj("kind" to "Alerting")).put("sdpOffer", "")))
    }

    @Test
    fun readsTheChatmailCoreOfferAndAnswerWithoutChangingTheSdp() {
        val sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
        val incoming = obj("kind" to "IncomingCall", "msg_id" to 12, "chat_id" to 22,
            "has_video" to true, "place_call_info" to sdp)
        assertEquals(ChatmailCallEvent("IncomingCall", 12, 22, sdp, true, false), readChatmailCallEvent(incoming))
        val accepted = obj("kind" to "OutgoingCallAccepted", "msg_id" to 12, "chat_id" to 22,
            "accept_call_info" to sdp)
        assertEquals(ChatmailCallEvent("OutgoingCallAccepted", 12, 22, sdp, false, false), readChatmailCallEvent(accepted))
    }

    @Test
    fun preservesOtherDeviceAnswerAndIgnoresUnrelatedEvents() {
        val accepted = obj("kind" to "IncomingCallAccepted", "msg_id" to 12, "chat_id" to 22,
            "from_this_device" to true)
        assertTrue(requireNotNull(readChatmailCallEvent(accepted)).thisDevice)
        assertEquals("CallEnded", readChatmailCallEvent(accepted.put("kind", "CallEnded"))?.kind)
        assertNull(readChatmailCallEvent(accepted.put("kind", "IncomingCall")))
        assertNull(readChatmailCallEvent(accepted.put("kind", "MsgsChanged")))
    }
}
