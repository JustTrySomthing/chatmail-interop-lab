package app.p2p

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TalkSignalTest {
    private val call = "c0583d52-0812-4a0e-a4ef-bd728479db7e"
    private val audio = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=fingerprint:sha-256 " + List(32) { "00" }.joinToString(":") + "\r\n"

    @Test fun preservesCandidateLessAnswerFixButRejectsUnroutableOffers() {
        val credentials = audio + "a=ice-ufrag:abcd\r\na=ice-pwd:abcdefghijklmnopqrstuv\r\n"
        assertNull(localTalkSdpError(credentials, answer = true))
        assertEquals("No ICE candidates for the outgoing call", localTalkSdpError(credentials, answer = false))
        assertEquals("Invalid local WebRTC offer", localTalkSdpError("broken", answer = false))
        val candidate = credentials + "a=candidate:1 1 udp 1 192.0.2.1 1234 typ relay\r\n"
        assertNull(localTalkSdpError(candidate, answer = false))
        assertEquals(mapOf("relay" to 1), talkCandidateCounts(candidate))
        assertEquals(30_000L, TALK_CONNECT_MS)
    }

    @Test
    fun acceptsOnlyFreshAudioCallsAndValidControlSignals() {
        val now = 100_000L
        val offer = TalkSignal(call, "offer", now + TALK_RING_MS, audio)
        assertEquals(offer, TalkSignal.read(offer.packet(), now))
        val lateOffer = offer.copy(expires = now)
        assertNull(TalkSignal.read(lateOffer.packet(), now))
        assertEquals(lateOffer, TalkSignal.read(lateOffer.packet(), now, allowMissedOffer = true))
        assertNull(TalkSignal.read(lateOffer.copy(action = "answer").packet(), now, allowMissedOffer = true))
        assertNull(TalkSignal.read(lateOffer.copy(expires = now - MISSED_CALL_WINDOW).packet(),
            now, allowMissedOffer = true))
        assertNull(TalkSignal.read(offer.copy(sdp = audio + "m=video 9 UDP/TLS/RTP/SAVPF 111").packet(), now))
        assertNull(TalkSignal.read(offer.copy(sdp = "v=0\r\nm=audio 9").packet(), now))
        assertNull(TalkSignal.read(offer.copy(action = "end").packet(), now))
        val end = TalkSignal(call, "end", now + TALK_RING_MS)
        assertEquals(end, TalkSignal.read(end.packet(), now))
    }

    @Test
    fun expiredAndOutOfOrderCallsDoNotRing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val rang = mutableListOf<String>()
            val talk = TalkCalls(RuntimeEnvironment.getApplication(), scope, { _, _ -> },
                { "[]" }, { _, id, _ -> rang.add(id) }, {})
            val contact = "A".repeat(40)
            val now = System.currentTimeMillis()
            talk.receive(contact, TalkSignal(call, "offer", now - 1, audio))
            talk.receive(contact, TalkSignal(call, "end", now + TALK_RING_MS))
            talk.receive(contact, TalkSignal(call, "offer", now + TALK_RING_MS, audio))
            assertNull(talk.state.value)
            assertTrue(rang.isEmpty())

            val other = newId()
            talk.receive(contact, TalkSignal(other, "offer", now + TALK_RING_MS, audio))
            talk.receive(contact, TalkSignal(other, "offer", now + TALK_RING_MS, audio))
            assertEquals(TalkPhase.Incoming, talk.state.value?.phase)
            assertEquals(listOf(other), rang)
            talk.decline(call)
            assertEquals(other, talk.state.value?.id)
            talk.decline(other)
            assertNull(talk.state.value)
        } finally {
            scope.cancel()
        }
    }
}
