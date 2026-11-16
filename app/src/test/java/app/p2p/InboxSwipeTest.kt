package app.p2p

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InboxSwipeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun revealsEveryActionOnEitherSideAndStopsAtCenter() {
        withRow(width = 328) { actions ->
            swipe(0.1f, 0.9f)
            assertActionsVisible()
            swipe(0.9f, 0.1f)
            compose.onNodeWithText("Delete").assertDoesNotExist()
            swipe(0.9f, 0.1f)
            assertActionsVisible()
            swipe(0.1f, 0.9f)
            compose.onNodeWithText("Archive").assertDoesNotExist()
            swipe(0.1f, 0.9f)
            compose.onNodeWithText("Delete").performClick()
            assertEquals(listOf(ThreadAction.DELETE), actions)
        }
    }

    @Test
    fun keepsEveryActionVisibleOnANarrowRowAtLargeFontSize() {
        withRow(width = 230, font = 140) {
            swipe(0.9f, 0.1f)
            assertActionsVisible()
            swipe(0.1f, 0.9f)
            compose.onNodeWithText("Block").assertDoesNotExist()
        }
    }

    @Test
    fun pinningAnOlderChatMovesItAboveTheNewestChat() {
        val app = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(app)
        val now = System.currentTimeMillis()
        withTestRecords(app) { records ->
            listOf("Older" to now - 90_000_000, "Newer" to now - 1_000).forEach { (name, created) ->
                records.put(Record(name, "contact", obj("name" to name).toString()))
                records.put(Record("msg-" + name, "message", obj("thread" to name, "text" to "Hello " + name).toString(), created))
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithContentDescription("Pin Older").assertIsDisplayed() }.isSuccess
            }
            fun top(name: String) = compose.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top
            org.junit.Assert.assertTrue(top("Newer") < top("Older"))
            compose.onNodeWithContentDescription("Pin Older").performClick()
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithContentDescription("Unpin Older").assertIsDisplayed() }.isSuccess
            }
            org.junit.Assert.assertTrue(top("Older") < top("Newer"))
        }
    }

    private fun swipe(from: Float, to: Float) {
        compose.onNodeWithTag("row").performTouchInput {
            swipe(Offset(width * from, centerY), Offset(width * to, centerY))
        }
    }

    private fun assertActionsVisible() {
        listOf("Archive", "Block", "Delete").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    private fun withRow(width: Int, font: Int = 100, verify: (List<ThreadAction>) -> Unit) {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        val selected = mutableListOf<ThreadAction>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    P2pTheme(Appearance(fontSize = font)) {
                        Box(Modifier.width(width.dp).testTag("row")) {
                            SwipeConversation(ThreadAction.ARCHIVE, blocked = false, inTrash = false,
                                onAction = { selected.add(it) }) {
                                Box(Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text("Conversation") }
                            }
                        }
                    }
                }
            }
            verify(selected)
        }
    }
}
