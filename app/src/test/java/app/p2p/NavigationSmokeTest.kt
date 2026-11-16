package app.p2p

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
            compose.onNodeWithText("Add contact").assertIsDisplayed()
            compose.onNodeWithText("Notifications").assertDoesNotExist()
            compose.onNodeWithText("Inbox").assertDoesNotExist()
            org.junit.Assert.assertEquals(toolbarId, back.fetchSemanticsNode().id)

            settings.performClick()
            compose.onNodeWithText("Notifications").assertIsDisplayed()
            compose.onNodeWithText("Add contact").assertDoesNotExist()
            org.junit.Assert.assertEquals(toolbarId, back.fetchSemanticsNode().id)
        }
    }

    @Test
    fun appLogHasWorkingClearControl() {
        AppLog.record(compose.activity, "old-call-marker")
        val settings = compose.onNodeWithContentDescription("Settings")
        compose.waitUntil(5_000) { settings.isDisplayed() }
        settings.performClick()
        compose.onNodeWithText("App log").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Copy log").assertIsDisplayed()
        compose.onNodeWithText("Clear log").performClick()
        org.junit.Assert.assertFalse(AppLog.read(compose.activity).contains("old-call-marker"))
        compose.onNodeWithText("No activity recorded yet.", substring = true).assertIsDisplayed()
    }

    @Test
    fun opensTextToSpeechSettings() {
        val settings = compose.onNodeWithContentDescription("Settings")
        compose.waitUntil(5_000) { settings.isDisplayed() }
        settings.performClick()
        compose.onNodeWithText("Read aloud").performClick()
        compose.onNodeWithText("Read aloud").assertIsDisplayed()
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
        compose.onNodeWithText("Set time").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)
        org.junit.Assert.assertTrue(QuietHours.read(compose.activity).enabled)
        compose.onNodeWithText("Quiet Hours").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Enable schedule").assertIsOn()
    }

    @Test
    fun socialComposerKeepsItsOnlyAttachmentActionInsideTheSheet() {
        compose.onAllNodesWithText("Social")[0].performClick()
        compose.onNodeWithText("My Page").performClick()
        compose.onNodeWithText("No posts yet").assertIsDisplayed()
        compose.onNodeWithText("Add media").assertDoesNotExist()
        compose.onNodeWithText("What’s on your mind?").performClick()
        compose.onNodeWithText("New post").assertIsDisplayed()
        compose.onNodeWithText("Add media").assertIsDisplayed()
    }

    @Test
    fun opensAllFourAreasAndTheirPrimaryControls() {
        compose.onNodeWithText("Inbox").assertIsDisplayed()
        compose.onNodeWithText("Archive").assertIsDisplayed()
        compose.onNodeWithText("Trash").assertIsDisplayed()
        compose.onNodeWithText("No messages").assertIsDisplayed()
        compose.onNodeWithText("Archive").performClick()
        compose.onNodeWithText("No archived messages").assertIsDisplayed()
        compose.onNodeWithText("Trash").performClick()
        compose.onNodeWithText("No deleted messages").assertIsDisplayed()
        compose.onNodeWithText("Empty trash").assertIsDisplayed().assertIsNotEnabled()

        compose.onAllNodesWithText("Groups")[0].performClick()
        compose.onNodeWithText("Create a group").assertIsDisplayed()
        compose.onNodeWithContentDescription("New group").assertDoesNotExist()
        compose.onNodeWithText("No groups").assertIsDisplayed()
        compose.onNodeWithText("Create group").assertDoesNotExist()
        compose.onNodeWithText("Trash").performClick()
        compose.onNodeWithText("No deleted groups").assertIsDisplayed()
        compose.onNodeWithText("Empty trash").assertIsDisplayed().assertIsNotEnabled()

        compose.onAllNodesWithText("Social")[0].performClick()
        compose.onNodeWithText("My Page").assertIsDisplayed()
        compose.onNodeWithText("Friends").performClick()
        compose.onNodeWithText("No friends yet").assertIsDisplayed()
        org.junit.Assert.assertEquals(1, compose.onAllNodesWithText("Add friend").fetchSemanticsNodes().size)
        compose.onNodeWithText("Feed").performClick()
        compose.onNodeWithText("No posts in your feed").assertIsDisplayed()

        compose.onAllNodesWithText("Talk")[0].performClick()
        compose.onNodeWithText("VoiceMail").assertIsDisplayed()
        compose.onNodeWithText("Call").assertIsDisplayed()
        compose.onNodeWithText("Files").assertDoesNotExist()
        compose.onAllNodesWithText("Chats")[0].performClick()
        compose.onNodeWithText("Inbox").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Backup").performClick()
        compose.onNodeWithText("Create backup").assertIsDisplayed()
        compose.onNodeWithText("Restore backup").assertIsDisplayed()
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
        mute.assertIsOff().performClick()
        for (area in listOf("Talk", "Groups", "Social", "Chats")) {
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
