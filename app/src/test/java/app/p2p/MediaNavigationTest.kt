package app.p2p

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.longClick
import android.graphics.Bitmap
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun longPressOffersContextualActionsAndSchedulesAReplyReminder() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        val contact = "A".repeat(40)
        val group = newId()
        val message = newId()
        val post = newId()
        withTestRecords(context) { rows ->
            rows.put(Record(contact, "contact", obj("name" to "River Park", "friend" to true).toString()))
            rows.put(Record(group, "group", obj("name" to "Weekend plans").toString()))
            rows.put(Record(message, "message",
                obj("thread" to contact, "author" to contact, "text" to "Could you reply?").toString()))
            rows.put(Record(post, "post", obj("author" to contact, "text" to "A new post").toString()))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val chat = compose.onNode(hasText("River Park") and hasClickAction())
            compose.waitUntil(5_000) { runCatching { chat.assertIsDisplayed() }.isSuccess }
            chat.performTouchInput { longClick() }
            compose.onNode(hasText("Archive") and hasAnyAncestor(isDialog())).assertExists()
            compose.onNodeWithText("Copy Text").assertDoesNotExist()
            compose.onNodeWithText("Remind Me to Reply").performScrollTo().performClick()
            compose.onNodeWithText("In 1 hour").performClick()
            val jobs = context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs
            val scheduled = jobs.single { it.service.className == ReplyReminderJob::class.java.name }
            org.junit.Assert.assertTrue(scheduled.isPersisted)
            org.junit.Assert.assertEquals(3_600_000L, scheduled.minLatencyMillis)
            org.junit.Assert.assertEquals(ReplyReminder(AlertArea.CHATS, contact, contact), ReplyReminder.read(scheduled.extras))

            chat.performClick()
            compose.onNodeWithText("Could you reply?", useUnmergedTree = true).performTouchInput { longClick() }
            compose.onNode(hasText("Message options") and hasAnyAncestor(isDialog())).assertExists()
            compose.onNodeWithText("Copy Text").performClick()
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            org.junit.Assert.assertEquals("Could you reply?", clipboard.primaryClip?.getItemAt(0)?.text.toString())
            compose.onNodeWithText("Could you reply?", useUnmergedTree = true).performTouchInput { longClick() }
            compose.onNodeWithText("Mark Unread").performScrollTo().performClick()
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Inbox").assertIsDisplayed() }.isSuccess }
            compose.waitUntil(5_000) { withTestRecords(context) { it.get(message)?.data()?.optBoolean("unread") == true } }

            compose.onAllNodesWithText("Groups")[0].performClick()
            compose.onNode(hasText("Weekend plans") and hasClickAction()).performTouchInput { longClick() }
            compose.onNodeWithText("Block group").assertExists()
            compose.onNodeWithText("Block sender").assertDoesNotExist()
            compose.onNodeWithText("Close").performClick()

            compose.onAllNodesWithText("Channels")[0].performClick()
            compose.onNodeWithText("My Channels").performClick()
            compose.onNodeWithText("Previous Social Posts").performClick()
            compose.onNodeWithText("Feed").performClick()
            compose.onNodeWithText("A new post", useUnmergedTree = true).performTouchInput { longClick() }
            compose.onNodeWithText("Post options").assertIsDisplayed()
            compose.onNodeWithText("Block sender").assertExists()
            compose.onNode(hasText("Archive") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        }
    }

    @Test
    fun emptyTrashConfirmsAndOnlyClearsTheCurrentSection() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        val chat = "A".repeat(40)
        val group = newId()
        withTestRecords(context) { rows ->
            rows.put(Record(chat, "contact", obj("name" to "Trashed chat").toString()))
            rows.put(Record(group, "group", obj("id" to group).toString()))
            for (thread in listOf(chat, group)) {
                rows.put(Record("folder:$thread", "folder", obj("name" to "Trash").toString()))
                rows.put(Record(newId(), "message", obj("thread" to thread, "text" to "Trashed history").toString()))
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Inbox").assertIsDisplayed() }.isSuccess
            }
            for ((area, emptyLabel) in listOf("Chats" to "No deleted messages", "Groups" to "No deleted groups")) {
                compose.onNode(hasText(area) and hasClickAction()).performClick()
                compose.onNodeWithText("Trash").performClick()
                compose.onNodeWithText("Empty Trash").assertIsEnabled().performClick()
                compose.onNodeWithText("Empty trash?").assertIsDisplayed()
                compose.onNode(hasText("Empty Trash") and hasAnyAncestor(isDialog())).performClick()
                compose.waitUntil(5_000) { runCatching { compose.onNodeWithText(emptyLabel).assertIsDisplayed() }.isSuccess }
            }
        }
    }

    @Test
    fun contactsUseMessageOrCallActionsForTheCurrentArea() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        withTestRecords(context) { rows ->
            rows.put(Record("A".repeat(40), "contact", obj("name" to "River Park").toString()))
            rows.put(Record("B".repeat(40), "contact", obj("name" to "Blocked contact", "blocked" to true).toString()))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val contacts = compose.onNodeWithContentDescription("Contacts")
            compose.waitUntil(5_000) { runCatching { contacts.assertIsDisplayed() }.isSuccess }
            contacts.performClick()
            val message = compose.onNodeWithContentDescription("Message River Park")
            message.assertIsDisplayed()
            compose.onNodeWithContentDescription("Call River Park").assertDoesNotExist()
            compose.onNodeWithContentDescription("Message Blocked contact").assertDoesNotExist()
            message.performClick()
            compose.onNodeWithContentDescription("Search conversation").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()

            compose.onNodeWithText("Start a Chat").performClick()
            message.assertIsDisplayed()
            message.performClick()
            compose.onNodeWithContentDescription("Back").performClick()

            compose.onAllNodesWithText("Talk")[0].performClick()
            contacts.performClick()
            val call = compose.onNodeWithContentDescription("Call River Park")
            call.assertIsDisplayed()
            message.assertDoesNotExist()
            compose.onNodeWithContentDescription("Call Blocked contact").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()

            compose.onNodeWithText("Make a Call").performClick()
            call.assertIsDisplayed()
            compose.onNodeWithText("Blocked contact").assertDoesNotExist()
        }
    }

    @Test
    fun socialPostsShowAPhotoAndPlayControlsForGifAndWebP() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        val contact = "A".repeat(40)
        val post = newId()
        val folder = File(context.filesDir, "received").apply { mkdirs() }
        val photo = newId()
        val gif = newId()
        val webp = newId()
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        File(folder, photo).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(folder, gif).writeBytes(java.util.Base64.getDecoder()
            .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"))
        File(folder, webp).writeBytes(exampleAnimatedWebP())
        withTestRecords(context) { rows ->
            rows.put(Record(contact, "contact", obj("name" to "River Park", "friend" to true).toString()))
            rows.put(Record(post, "post", obj("author" to contact).toString()))
            val receivedAt = System.currentTimeMillis()
            for ((index, attachment) in listOf(photo to "Garden.png", gif to "Animation.gif", webp to "Animation.webp").withIndex()) {
                val (id, filename) = attachment
                rows.put(Record(id, "file-received", obj("post" to post, "name" to filename,
                    "mime" to "application/octet-stream", "state" to "Complete").toString(), created = receivedAt - index))
            }
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onAllNodesWithText("Channels")[0].performClick()
            compose.onNodeWithText("My Channels").performClick()
            compose.onNodeWithText("Previous Social Posts").performClick()
            compose.onNodeWithText("Feed").performClick()
            val picture = compose.onNodeWithContentDescription("Garden.png", useUnmergedTree = true)
            try {
                compose.waitUntil(5_000) { runCatching { picture.assertIsDisplayed() }.isSuccess }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(), failure)
            }
            compose.onNodeWithContentDescription("Play WebP").performScrollTo().assertIsDisplayed()
            compose.onNode(hasContentDescription("Play WebP") and hasClickAction()).assertExists()
            compose.onNodeWithContentDescription("Play GIF").performScrollTo().assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithContentDescription("Media pages").assertIsDisplayed()
        }
    }

    @Test
    fun recentCallsShowDatesAndCanBeClearedWithoutClearingVoicemail() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        val contact = "Qm" + "a".repeat(44)
        val noon = java.time.LocalDate.of(2026, 11, 14).atTime(12, 0)
        val zone = java.time.ZoneId.systemDefault()
        val outgoing = noon.atZone(zone).toInstant().toEpochMilli()
        val incoming = outgoing - 60_000L
        val missed = noon.minusDays(1).atZone(zone).toInstant().toEpochMilli()
        val voicemail = newId()
        val store = LocalStore.open(context)
        try {
            runBlocking {
                val records = store.records()
                records.put(Record(contact, "contact", obj("name" to "River Park").toString()))
                records.put(Record(voicemail, "file-received",
                    obj("author" to contact, "voicemail" to true, "unread" to true).toString()))
                listOf(
                    Triple(incoming, false, "Call ended"),
                    Triple(missed, false, "Missed call"),
                    Triple(outgoing, true, "Call ended")
                ).forEach { (created, sent, detail) ->
                    records.put(Record("call:" + newId(), "call-history",
                        obj("contact" to contact, "outgoing" to sent, "detail" to detail).toString(), created))
                }
            }
        } finally {
            store.close()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onAllNodesWithText("Talk")[0].performClick()
            val dates = android.text.format.DateFormat.getMediumDateFormat(context)
            val times = android.text.format.DateFormat.getTimeFormat(context)
            val latestDate = compose.onNodeWithText(dates.format(java.util.Date(outgoing)))
            val olderDate = compose.onNodeWithText(dates.format(java.util.Date(missed)))
            val incomingRow = compose.onNodeWithText("Incoming · " + times.format(java.util.Date(incoming)))
            val outgoingRow = compose.onNodeWithText("Outgoing · " + times.format(java.util.Date(outgoing)))
            val missedRow = compose.onNodeWithText("Missed · " + times.format(java.util.Date(missed)))
            compose.waitUntil(5_000) { runCatching { missedRow.assertIsDisplayed() }.isSuccess }
            listOf(latestDate, olderDate, incomingRow, outgoingRow).forEach { it.assertIsDisplayed() }
            val title = compose.onNodeWithText("Recent calls").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val clear = compose.onNodeWithText("Clear Recent")
            val action = clear.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(title.center.x < screen.center.x && action.center.x > screen.center.x)
            org.junit.Assert.assertTrue(kotlin.math.abs(title.center.y - action.center.y) <= 2f)
            org.junit.Assert.assertTrue(outgoingRow.fetchSemanticsNode().boundsInRoot.top <
                incomingRow.fetchSemanticsNode().boundsInRoot.top)
            org.junit.Assert.assertEquals(3, compose.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Call River Park") and androidx.compose.ui.test.hasClickAction()
            ).fetchSemanticsNodes().size)
            compose.onNode(hasText("River Park") and hasClickAction()).assertDoesNotExist()

            clear.performClick()
            compose.onNodeWithText("Clear recent calls?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            outgoingRow.assertIsDisplayed()
            clear.performClick()
            compose.onNode(hasText("Clear Recent") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("No recent calls").assertIsDisplayed() }.isSuccess }
            org.junit.Assert.assertTrue(withTestRecords(context) { it.all("call-history").isEmpty() })
            org.junit.Assert.assertTrue(withTestRecords(context) { it.get(voicemail)?.data()?.optBoolean("unread") == true })
        }
    }

    @Test
    fun showsAnIncomingRequestAboveInviteToolsOnAShortScreen() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        val token = newId()
        val store = LocalStore.open(context)
        try {
            runBlocking {
                val records = store.records()
                records.put(Record("invite:" + token, "invite-out",
                    obj("expires" to System.currentTimeMillis() + 60_000L).toString()))
                records.put(Record("request:" + newId(), "invite-in",
                    obj("name" to "Pairing test contact", "token" to token).toString()))
            }
        } finally {
            store.close()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val contacts = compose.onNodeWithContentDescription("Contacts")
            compose.waitUntil(5_000) { runCatching { contacts.assertIsDisplayed() }.isSuccess }
            contacts.performClick()
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Accept").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithText("Pairing test contact").assertIsDisplayed()
        }
    }

    @Test
    fun opensSendAndAcceptInTheSameAddContactSheet() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Inbox").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithContentDescription("Contacts").performSemanticsAction(SemanticsActions.OnClick)
            val add = compose.onNodeWithText("Add Contact")
            compose.waitUntil(5_000) { runCatching { add.assertIsDisplayed() }.isSuccess }
            add.performClick()
            compose.onNodeWithText("Send invite").assertIsDisplayed()
            compose.onNodeWithText("Copy code").assertDoesNotExist()
            compose.onNodeWithText("Accept invite").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Scan QR Code").assertIsDisplayed()
            compose.onNodeWithText("Choose QR Image").assertIsDisplayed()
            compose.onNodeWithText("Invite link or code").assertIsDisplayed()
            compose.onNodeWithText("Nearby devices").assertDoesNotExist()
        }
    }

    @Test
    fun swipesBetweenUnzoomedPhotos() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        val contact = "A".repeat(40)
        val folder = File(context.filesDir, "received").apply { mkdirs() }
        val photos = listOf("First photo.png", "Second photo.png")
        withTestRecords(context) { rows ->
            rows.put(Record(contact, "contact", obj("name" to "Morgan").toString()))
            photos.forEachIndexed { index, name ->
                val id = newId()
                val file = File(folder, id)
                val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                rows.put(Record(id, "file-received", obj(
                    "thread" to contact, "author" to contact, "name" to name, "mime" to "image/png",
                    "state" to "Complete", "size" to file.length()
                ).toString(), created = index + 1L))
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Morgan").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithText("Morgan").performClick()
            val firstPhoto = compose.onNodeWithContentDescription(photos.first())
            compose.waitUntil(5_000) { runCatching { firstPhoto.assertIsDisplayed() }.isSuccess }
            firstPhoto.performTouchInput { longClick() }
            compose.onNodeWithText("Attachment options").assertIsDisplayed()
            compose.onNodeWithText("Download Media").performScrollTo().performClick()
            compose.onNodeWithText("Download image").assertIsDisplayed()
            compose.onNodeWithText("Choose Location").assertIsDisplayed()
            compose.onNodeWithText("Save to Downloads").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            firstPhoto.performClick()
            compose.onNodeWithText("1 / 2").assertIsDisplayed()
            compose.onNodeWithContentDescription("Media pages").performTouchInput { swipeLeft() }
            compose.onNodeWithText("2 / 2").assertIsDisplayed()
            compose.onNodeWithContentDescription("Download Media").performClick()
            compose.onNodeWithText("Download image").assertIsDisplayed()
            org.junit.Assert.assertEquals(2, compose.onAllNodesWithText(photos.last()).fetchSemanticsNodes().size)
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithContentDescription("Media pages").performTouchInput { longClick() }
            compose.onNodeWithText("Download image").assertIsDisplayed()
        }
    }

    @Test
    fun playsDownloadedAudioInlineAndKeepsTheSharedDownloadDialog() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        val id = newId()
        val contact = "A".repeat(40)
        val audio = File(File(context.filesDir, "received").apply { mkdirs() }, id)
        val samples = 8000 * 4
        audio.writeBytes(ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2).array())
        val store = LocalStore.open(context)
        try {
            runBlocking {
                store.records().put(Record(contact, "contact", obj("name" to "Morgan").toString()))
                store.records().put(Record(id, "file-received", obj(
                    "thread" to contact, "author" to contact,
                    "name" to "Family recording.wav", "mime" to "audio/wav", "state" to "Complete",
                    "size" to audio.length(), "listed" to true
                ).toString()))
            }
        } finally {
            store.close()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Morgan").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithText("Morgan").performClick()
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Family recording.wav").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithContentDescription("Play audio").performClick()
            compose.onNodeWithContentDescription("Close media").assertDoesNotExist()
            compose.onNodeWithContentDescription("File Details").assertDoesNotExist()
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithContentDescription("Pause audio").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithContentDescription("Pause audio").performClick()
            compose.onNodeWithText("Family recording.wav").performTouchInput { longClick() }
            compose.onNodeWithText("Download Media").performScrollTo().performClick()
            compose.onNodeWithText("Download audio").assertIsDisplayed()
            compose.onNodeWithText("Choose Location").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithContentDescription("Close media").assertDoesNotExist()
        }
    }
}
