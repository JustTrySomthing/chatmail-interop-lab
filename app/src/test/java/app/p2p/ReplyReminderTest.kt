package app.p2p

import android.app.Application
import android.app.job.JobScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReplyReminderTest {
    @Test fun nativeChatAndMessageRemindersSurviveSchedulingAndRescheduling() {
        val app = RuntimeEnvironment.getApplication()
        val chat = nativeThreadKey(42)
        val message = nativeMessageKey(73)
        val conversation = ReplyReminder(AlertArea.CHATS, chat, chat)
        val reply = ReplyReminder(AlertArea.CHATS, chat, message)
        assertTrue(conversation.valid())
        assertTrue(reply.valid())
        assertTrue(ReplyReminderJob.schedule(app, conversation, 60_000, PRIMARY_PROFILE))
        assertTrue(ReplyReminderJob.schedule(app, reply, 60_000, PRIMARY_PROFILE))
        assertTrue(ReplyReminderJob.schedule(app, reply, 120_000, PRIMARY_PROFILE))
        val jobs = app.getSystemService(JobScheduler::class.java).allPendingJobs
        assertEquals(2, jobs.size)
        assertEquals(setOf(conversation, reply), jobs.map { ReplyReminder.read(it.extras) }.toSet())
        assertEquals(120_000L, jobs.single { ReplyReminder.read(it.extras) == reply }.minLatencyMillis)
    }

    @Test fun preservesLegacyPeersAndRejectsMalformedTargets() {
        val peer = "a".repeat(64)
        assertTrue(ReplyReminder(AlertArea.CHATS, peer, peer).valid())
        assertFalse(ReplyReminder(AlertArea.CHATS, "bad-target", nativeMessageKey(73)).valid())
        assertFalse(ReplyReminder(AlertArea.CHATS, nativeThreadKey(42), "bad-item").valid())
        assertFalse(ReplyReminder(AlertArea.TALK, nativeThreadKey(42), nativeMessageKey(73)).valid())
    }
}
