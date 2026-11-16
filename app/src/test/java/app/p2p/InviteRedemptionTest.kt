package app.p2p

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InviteRedemptionTest {
    @Test
    fun activeSharedInvitePairsOnlyOnePeerAndRetriesAreIdempotent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<P2pApplication>()
        val token = "A".repeat(32)
        val inviteKey = "invite:" + token
        withTestRecords(app) {
            it.put(Record(inviteKey, "invite-out", obj("chatmail" to true, "autoAccept" to true,
                "expires" to System.currentTimeMillis() + 60_000).toString()))
        }
        val model = app.model
        val first = "F".repeat(40)
        val second = "S".repeat(40)
        val hello = obj("token" to token, "name" to "New contact")
        val a = async { model.receiveInvite(first, hello, null, 20, 1) }
        val b = async { model.receiveInvite(second, hello, null, 21, 1) }
        val result = listOf(a.await(), b.await())
        assertEquals(listOf("paired", "rejected"), result.sorted())
        val winner = if (result.first() == "paired") first else second
        val chat = if (winner == first) 20 else 21
        assertEquals("paired", model.receiveInvite(winner, hello, null, chat, 1))
        withTestRecords(app) { records ->
            assertNull(records.get(inviteKey))
            assertEquals(listOf(winner), records.all("contact").map { it.key })
            assertEquals(emptyList<Record>(), records.all("invite-in"))
            val approval = records.all("outbox").single().data().getJSONObject("payload")
            assertEquals("paired", approval.getString("type"))
            assertEquals(token, approval.getString("token"))
        }
    }
    @Test
    fun incomingInviteClosesTheLosingRequestWhenBothPeopleSendInvites() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<P2pApplication>()
        val remote = "Z".repeat(40)
        val incoming = "I".repeat(32)
        val outgoing = "O".repeat(32)
        val requestKey = "request:" + remote
        val expires = System.currentTimeMillis() + 60_000
        withTestRecords(app) { records ->
            records.put(Record("invite:" + incoming, "invite-out", obj("chatmail" to true,
                "autoAccept" to true, "expires" to expires).toString()))
            records.put(Record(requestKey, "invite-pending", obj("token" to outgoing,
                "chatmailChat" to 21, "expires" to expires).toString()))
            records.put(Record("outgoing-hello", "outbox", obj("to" to remote, "chatmailChat" to 21,
                "payload" to obj("type" to "hello", "token" to outgoing)).toString()))
        }
        assertEquals("paired", app.model.receiveInvite(remote,
            obj("token" to incoming, "name" to "New contact"), null, 20, 1))
        withTestRecords(app) { records ->
            assertNull(records.get(requestKey))
            assertNull(records.get("outgoing-hello"))
            assertEquals(incoming, records.get(remote)?.data()?.optString("token"))
            assertEquals("paired", records.all("outbox").single().data().getJSONObject("payload").getString("type"))
        }
    }

}
