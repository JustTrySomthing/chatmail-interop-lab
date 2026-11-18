package app.p2p

import android.content.ClipData
import android.content.Intent
import android.net.Uri

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Rule
import org.junit.rules.ExternalResource
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationSmokeTest {
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() = seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun deletingProfileRequiresConfirmationAndCancelPreservesIt() {
        compose.waitUntil(5_000) { compose.onNodeWithContentDescription("Settings").isDisplayed() }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Profiles", substring = false).performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText("Delete Profile", substring = false).isDisplayed() }
        compose.onNodeWithText("Delete Profile", substring = false).performClick()
        compose.onNodeWithText("Delete Profile?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Delete Profile?").assertDoesNotExist()
        compose.onNodeWithText("Edit Active Profile").assertIsDisplayed()
        org.junit.Assert.assertEquals(setOf(PRIMARY_PROFILE), ProfileSelection(compose.activity).ids())
    }

    @Test
    fun profilesCombinesManagementAndEditing() {
        compose.waitUntil(5_000) { compose.onNodeWithContentDescription("Settings").isDisplayed() }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Profile", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Profiles", substring = false).performClick()
        compose.onNodeWithText("Add Profile").assertIsDisplayed()
        compose.onNodeWithText("Edit Active Profile").performClick()
        compose.onNodeWithText("Display Name").assertIsDisplayed()
        compose.onNodeWithText("Bio", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Choose Profile Photo").assertIsDisplayed()
    }

    @Test
    fun readsTextAndCaptionsFromAndroidShareIntents() {
        val own = "app.p2p.interop.files"
        val url = "https://example.org/article"
        val link = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        org.junit.Assert.assertEquals(IncomingShare(emptyList(), url), readIncomingShare(link, own))

        val video = Uri.parse("content://example.provider/video")
        val caption = Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*")
            .putExtra(Intent.EXTRA_TEXT, "Two clips from today")
            .apply {
                clipData = ClipData.newRawUri("video", video).apply {
                    addItem(ClipData.Item(Uri.parse("content://$own/private")))
                    addItem(ClipData.Item(Uri.parse("file:///private.mp4")))
                    addItem(ClipData.Item(video))
                }
            }
        org.junit.Assert.assertEquals(IncomingShare(listOf(video), "Two clips from today"),
            readIncomingShare(caption, own))
    }

    @Test
    fun settingsAndContactsShareTheSameToolbarWhileSwitching() {
        val settings = compose.onNodeWithContentDescription("Settings")
        compose.waitUntil(5_000) { settings.isDisplayed() }
        settings.performClick()
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        val back = compose.onNodeWithContentDescription("Back")
        val toolbarId = back.fetchSemanticsNode().id

        repeat(2) {
            compose.onNodeWithContentDescription("Contacts").performClick()
            compose.onNodeWithText("Contacts").assertIsDisplayed()
            compose.onNodeWithText("Add Contact").assertIsDisplayed()
            compose.onNodeWithText("Notifications").assertDoesNotExist()
            compose.onNodeWithText("Inbox").assertDoesNotExist()
            org.junit.Assert.assertEquals(toolbarId, back.fetchSemanticsNode().id)

            settings.performClick()
            compose.onNodeWithText("Notifications").assertIsDisplayed()
            compose.onNodeWithText("Add Contact").assertDoesNotExist()
            org.junit.Assert.assertEquals(toolbarId, back.fetchSemanticsNode().id)
        }
    }

    @Test
    fun appLogHasWorkingClearControl() {
        AppLog.record(compose.activity, "old-call-marker")
        val settings = compose.onNodeWithContentDescription("Settings")
        compose.waitUntil(5_000) { settings.isDisplayed() }
        settings.performClick()
        compose.onNodeWithText("App Log").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Copy Log").assertIsDisplayed()
        compose.onNodeWithText("Clear Log").performClick()
        org.junit.Assert.assertFalse(AppLog.read(compose.activity).contains("old-call-marker"))
        compose.onNodeWithText("No activity recorded yet.", substring = true).assertIsDisplayed()
    }

    @Test
    fun opensTextToSpeechSettings() {
        val settings = compose.onNodeWithContentDescription("Settings")
        compose.waitUntil(5_000) { settings.isDisplayed() }
        settings.performClick()
        compose.onNodeWithText("Read Aloud").performClick()
        compose.onNodeWithText("Read Aloud").assertIsDisplayed()
    }

    @Test
    fun savesQuietHoursFromSettings() {
        val settings = compose.onNodeWithContentDescription("Settings")
        compose.waitUntil(5_000) { settings.isDisplayed() }
        settings.performClick()
        compose.onNodeWithText("YouTube thumbnails").assertDoesNotExist()
        compose.onNodeWithText("Mobile data only").assertDoesNotExist()
        compose.onNodeWithText("Quiet Hours").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Enable schedule").assertIsOff()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("From").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Start time").assertIsDisplayed()
        compose.onNodeWithText("Set Time").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)
        org.junit.Assert.assertTrue(QuietHours.read(compose.activity).enabled)
        compose.onNodeWithText("Quiet Hours").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Enable schedule").assertIsOn()
    }

    @Test
    fun newChatmailGroupHasAnOptionalDescription() {
        val groups = compose.onAllNodesWithText("Groups")[0]
        compose.waitUntil(5_000) { groups.isDisplayed() }
        groups.performClick()
        compose.onNodeWithText("Create a Group").performSemanticsAction(SemanticsActions.OnClick)
        val description = compose.onNode(hasSetTextAction() and hasText("Description (optional)"))
        description.assertIsDisplayed().performTextInput("Local trail updates")
        compose.onNodeWithText("Local trail updates").assertIsDisplayed()
    }

    @Test
    fun myChannelsOffersCreationFromItsOwnTab() {
        val channels = compose.onAllNodesWithText("Channels")[0]
        compose.waitUntil(5_000) { channels.isDisplayed() }
        channels.performClick()
        compose.onNodeWithText("Create a Channel").assertDoesNotExist()
        compose.onNodeWithText("My Channels").performClick()
        compose.onNodeWithText("Create a Channel").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Channel name").assertIsDisplayed()
        val create = compose.onNode(hasClickAction() and hasText("Create Channel"))
        create.assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("Channel name")).performTextInput("Trail notes")
        create.assertIsDisplayed()
    }

    @Test
    fun socialComposerKeepsItsOnlyAttachmentActionInsideTheSheet() {
        val channels = compose.onAllNodesWithText("Channels")[0]
        compose.waitUntil(5_000) { channels.isDisplayed() }
        channels.performClick()
        compose.onNodeWithText("My Channels").performClick()
        compose.onNodeWithText("Previous Social Posts").performClick()
        compose.onNodeWithText("My Page").performClick()
        compose.onNodeWithText("No posts yet").assertIsDisplayed()
        compose.onNodeWithText("Add Attachment").assertDoesNotExist()
        compose.onNodeWithText("What’s on Your Mind?").performClick()
        compose.onNodeWithText("New post").assertIsDisplayed()
        compose.onNodeWithText("Add Attachment").assertIsDisplayed()
        compose.onNodeWithText("Add Attachment").performClick()
        for (label in listOf("Take Photo", "Record Video", "Record Audio", "Attach File")) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }

    }

    @Test
    fun opensAllFourAreasAndTheirPrimaryControls() {
        val inbox = compose.onNodeWithText("Inbox")
        compose.waitUntil(5_000) { inbox.isDisplayed() }
        inbox.assertIsDisplayed()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        compose.onNodeWithText("Trash").assertIsDisplayed()
        compose.onNodeWithText("No messages").assertIsDisplayed()
        compose.onNodeWithText("Archive").performClick()
        compose.onNodeWithText("No archived messages").assertIsDisplayed()
        compose.onNodeWithText("Trash").performClick()
        compose.onNodeWithText("No deleted messages").assertIsDisplayed()
        compose.onNodeWithText("Empty Trash").assertIsDisplayed().assertIsNotEnabled()

        compose.onAllNodesWithText("Groups")[0].performClick()
        compose.onNodeWithText("Create a Group").assertIsDisplayed()
        compose.onNodeWithContentDescription("New group").assertDoesNotExist()
        compose.onNodeWithText("No groups").assertIsDisplayed()
        compose.onNodeWithText("Create Group").assertDoesNotExist()
        compose.onNodeWithText("Trash").performClick()
        compose.onNodeWithText("No deleted groups").assertIsDisplayed()
        compose.onNodeWithText("Empty Trash").assertIsDisplayed().assertIsNotEnabled()

        compose.onAllNodesWithText("Channels")[0].performClick()
        compose.onNodeWithText("Feed").assertIsDisplayed()
        compose.onNodeWithText("Publish").assertDoesNotExist()
        compose.onNodeWithText("My Channels").assertIsDisplayed()
        compose.onNodeWithText("Following").performClick()
        compose.onNodeWithText("Join Channel").assertIsDisplayed()

        compose.onAllNodesWithText("Talk")[0].performClick()
        compose.onNodeWithText("VoiceMail").assertDoesNotExist()
        compose.onNodeWithText("Make a Call").assertIsDisplayed()
        compose.onNodeWithText("Files").assertDoesNotExist()
        compose.onAllNodesWithText("Chats")[0].performClick()
        compose.onNodeWithText("Inbox").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Backup").performClick()
        compose.onNodeWithText("Create Backup").assertIsDisplayed()
        compose.onNodeWithText("Restore Backup").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Appearance").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Dark").assertIsDisplayed()
        compose.onNodeWithText("System").assertDoesNotExist()
        Accent.entries.forEach { compose.onNodeWithContentDescription(it.label).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Violet").assertIsSelected()
        compose.onNodeWithContentDescription("Crimson").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithContentDescription("Crimson").assertIsSelected() }.isSuccess
        }
    }

    @Test
    fun theSameNotificationBellWorksAcrossPages() {
        val mute = compose.onNodeWithContentDescription("Mute all notifications")
        val unmute = compose.onNodeWithContentDescription("Unmute all notifications")
        compose.waitUntil(5_000) { mute.isDisplayed() }
        mute.assertIsOff().performClick()
        for (area in listOf("Talk", "Groups", "Channels", "Chats")) {
            compose.onAllNodesWithText(area)[0].performClick()
            unmute.assertIsOn()
            compose.onNodeWithContentDescription("Search").assertIsDisplayed()
            compose.onNodeWithContentDescription("Contacts").assertIsDisplayed()
            compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Settings").performClick()
        unmute.assertIsOn()
        compose.onNodeWithContentDescription("Search settings").assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Backup")
        compose.onNode(hasClickAction() and hasText("Search") and !hasSetTextAction()).performClick()
        compose.onNodeWithText("Backup").assertIsDisplayed()
        compose.onNodeWithText("Notifications").assertDoesNotExist()
        compose.onNodeWithContentDescription("Contacts").performClick()
        compose.onNodeWithContentDescription("Search contacts").assertIsDisplayed()
        unmute.assertIsOn().performClick()
        mute.assertIsOff()
    }
}
