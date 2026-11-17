package app.p2p

import android.Manifest
import android.app.NotificationManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeliveryRegressionTest {
    @Test
    fun messagesAndAttachmentsStayUnreadUntilTheirThreadIsVisible() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<P2pApplication>()
        val model = app.model
        val thread = "C".repeat(40)
        val message = newId()
        val attachment = newId()
        withTestRecords(app) { records ->
            val unread = obj("thread" to thread, "unread" to true).toString()
            records.put(Record(message, "message", unread))
            records.put(Record(attachment, "file-received", unread))
        }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        val alerts = LocalAlerts(app)
        alerts.show(AlertArea.FILES, attachment, "Contact", "Sent a file")
        alerts.show(AlertArea.CHATS, thread, "Contact", "New message")
        fun unreadStates() = withTestRecords(app) { records ->
            listOf(message, attachment).map { records.get(it)?.data()?.optBoolean("unread") }
        }

        model.setVisibleThread(thread)
        model.setForeground(false)
        model.markRead(thread).join()
        assertEquals(listOf(true, true), unreadStates())

        model.setForeground(true)
        model.setVisibleThread("A different chat")
        model.markRead(thread).join()
        assertEquals(listOf(true, true), unreadStates())
        assertEquals(2, notifications.allNotifications.size)

        model.setVisibleThread(thread)
        model.markRead(thread).join()
        assertEquals(listOf(false, false), unreadStates())
        assertEquals(0, notifications.allNotifications.size)
    }

    @Test
    fun failedShareRollsBackEveryAttachmentAndCanBeRetried() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<P2pApplication>()
        val model = app.model
        val contact = "D".repeat(40)
        withTestRecords(app) { records -> records.put(Record(contact, "contact", obj().toString())) }
        val valid = Uri.fromFile(File(app.cacheDir, "example.txt").apply { writeText("Shared text") })
        val missing = Uri.fromFile(File(app.cacheDir, "missing.txt"))

        checkNotNull(model.sendFiles(contact, listOf(valid, missing))).join()
        val failure = checkNotNull(model.sheetSubmission.value)
        check(failure.finished && failure.error != null)
        withTestRecords(app) { records ->
            assertEquals(0, records.all("file-sent").size)
            assertEquals(0, records.all("outbox").size)
        }
        assertEquals(0, File(app.filesDir, "sent").listFiles().orEmpty().size)

        checkNotNull(model.sendFiles(contact, listOf(valid, valid), "Shared caption")).join()
        assertEquals(SheetSubmission("send", contact, finished = true), model.sheetSubmission.value)
        withTestRecords(app) { records ->
            assertEquals("Shared caption", records.all("file-sent").single().data().optString("caption"))
        }
    }

    @Test
    fun legacyWifiDownloadsResumeWithTheirOriginalProgressAndRequest() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<P2pApplication>()
        val file = newId()
        val paused = newId()
        val attempt = newId()
        val queued = Record(newId(), "outbox", obj("payload" to obj("type" to "download",
            "id" to attempt, "file" to file, "offset" to 40, "revision" to 2, "wifi" to true)).toString())
        val waiting = Record(file, "file-received", obj("state" to "Waiting for Wi-Fi", "wifi" to true,
            "download" to attempt, "downloadRevision" to 2, "offset" to 40).toString())
        withTestRecords(app) { records ->
            records.put(waiting)
            records.put(Record(paused, "file-received", obj("state" to "Paused", "wifi" to true).toString()))
            records.put(queued)
        }

        repeat(2) { app.model.migrateLegacyDownloads() }

        withTestRecords(app) { records ->
            val migrated = checkNotNull(records.get(file))
            val data = migrated.data()
            assertEquals(waiting.created, migrated.created)
            assertEquals("Waiting to connect", data.getString("state"))
            assertEquals(40L, data.getLong("offset"))
            assertEquals(2L, data.getLong("downloadRevision"))
            assertEquals(attempt, data.getString("download"))
            assertEquals(false, data.has("wifi"))
            val pausedData = checkNotNull(records.get(paused)).data()
            assertEquals("Paused", pausedData.getString("state"))
            assertEquals(false, pausedData.has("wifi"))
            assertEquals(queued, records.get(queued.key))
        }
    }

    @Test
    fun duplicateAndOlderDownloadRequestsPreserveStreamProgress() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<P2pApplication>()
        val model = app.model
        val contact = "D".repeat(40)
        val file = newId()
        val download = newId()
        val streamKey = "stream:$contact:$file:$download"
        File(app.filesDir, "sent/$file").writeBytes(ByteArray(100))
        withTestRecords(app) { records ->
            records.put(Record(contact, "contact", obj().toString()))
            records.put(Record(file, "file-sent", obj("thread" to contact, "size" to 100).toString()))
            records.put(Record(streamKey, "stream", obj("download" to download, "revision" to 2,
                "offset" to 40).toString()))
        }

        assertEquals("ok", model.startStream(contact,
            obj("file" to file, "id" to download, "revision" to 2, "offset" to 0)))
        assertEquals("ok", model.startStream(contact,
            obj("file" to file, "id" to newId(), "revision" to 1, "offset" to 0)))
        withTestRecords(app) { records ->
            val active = records.all("stream").single()
            assertEquals(streamKey, active.key)
            assertEquals(40L, active.data().getLong("offset"))
        }
    }
}
