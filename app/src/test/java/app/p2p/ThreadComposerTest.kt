package app.p2p

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadComposerTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun headerUsesGlobalMuteAndSearchesOnlyTheConversation() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        withTestRecords(context) { records ->
            records.put(Record("contact-test", "contact", obj("name" to "Avery", "alertMuted" to true).toString()))
            records.put(Record("msg-a", "message", obj("thread" to "contact-test", "text" to "First message").toString(), 1))
            records.put(Record("msg-b", "message", obj("thread" to "contact-test", "text" to "Second message").toString(), 2))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { compose.onNodeWithText("Avery").isDisplayed() }
            compose.onNodeWithText("Avery").performClick()
            compose.onNodeWithContentDescription("Conversation options").assertDoesNotExist()
            compose.onNodeWithContentDescription("Pin conversation").assertDoesNotExist()
            compose.onNodeWithContentDescription("Mute all notifications").performClick()
            compose.waitUntil(5_000) { compose.onNodeWithContentDescription("Unmute all notifications").isDisplayed() }
            compose.onNodeWithContentDescription("Contacts").assertIsDisplayed()
            compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
            compose.onNodeWithContentDescription("Search conversation").performClick()
            compose.onNode(hasSetTextAction() and hasText("Search"))
                .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("Second")) }
            compose.onNode(androidx.compose.ui.test.hasClickAction() and hasText("Search") and !hasSetTextAction()).performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Second message").assertIsDisplayed()
            compose.onNodeWithText("First message").assertDoesNotExist()
            compose.onNodeWithContentDescription("Clear conversation search").performClick()
            compose.onNodeWithText("First message").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Unmute all notifications").assertIsDisplayed()
        }
    }

    @Test
    fun closesEmojiTrayWhenTheMessageFieldTakesFocus() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        withTestRecords(context) { it.put(Record("contact-test", "contact", obj("name" to "Avery").toString())) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Avery").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithText("Avery").performClick()
            compose.onNodeWithText("Avery").assertIsDisplayed()
            compose.onNodeWithContentDescription("Search conversation").performClick()
            compose.onNode(hasSetTextAction() and hasText("Search")).assertIsDisplayed()
            compose.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.OnClick)
            // The host simulator has no AndroidKeyStore, so the model can show a startup snackbar over the composer.
            compose.onNodeWithContentDescription("Emoji picker").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(5_000) { compose.onNodeWithText("Done").isDisplayed() }
            compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithText("Done").assertDoesNotExist()
            compose.onNode(hasSetTextAction()).performTextInput("Hello")
            compose.onNodeWithText("Hello").assertIsDisplayed()
            compose.onNodeWithContentDescription("Send").assertIsDisplayed()

            compose.onNodeWithContentDescription("Settings").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Appearance").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Hello").assertIsDisplayed()
            compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        }
    }
}
