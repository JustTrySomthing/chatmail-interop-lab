package app.p2p

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TalkEventTest {
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
