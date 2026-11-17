package app.p2p

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResponsiveAppearanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun contentSizeChangesLinkedTextAndPreviewWithoutResizingControls() {
        val appearance = mutableStateOf(Appearance(mode = ThemeMode.LIGHT, accent = Accent.CLASSIC_VIOLET))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    P2pTheme(appearance.value) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text("12:34 PM")
                            LinkedMessageText("Message with a link https://example.org", {})
                            AppearanceControls(appearance.value) { change ->
                                appearance.value = change(appearance.value)
                            }
                        }
                    }
                }
            }
            val time = layout("12:34 PM")
            val message = layout("Message with a link https://example.org")
            val heading = layout("Content font size")
            val sample = "This is how your messages and posts will look."
            val preview = layout(sample)
            compose.onNodeWithContentDescription("Content font size").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(140f) }
            compose.onNodeWithText("140%").assertIsDisplayed()
            assertTrue(layout(sample).layoutInput.style.fontSize > preview.layoutInput.style.fontSize)
            compose.runOnIdle { appearance.value = appearance.value.copy(fontSize = 140) }
            assertTrue(layout("Message with a link https://example.org").layoutInput.style.fontSize >
                message.layoutInput.style.fontSize)
            assertEquals(time.size, layout("12:34 PM").size)
            assertEquals(heading.layoutInput.style.fontSize, layout("Content font size").layoutInput.style.fontSize)
            assertEquals(ThemeMode.LIGHT, appearance.value.mode)
            assertEquals(Accent.CLASSIC_VIOLET, appearance.value.accent)
        }
    }

    @Test
    fun compactPhoneKeepsMainActionsOnOneLine() = checkMainActions()

    @Test
    @Config(qualifiers = "w412dp-h915dp")
    fun tallPhoneKeepsMainActionsOnOneLine() = checkMainActions()

    @Test
    @Config(qualifiers = "w740dp-h360dp-land")
    fun landscapeKeepsMainActionsOnOneLine() = checkMainActions()

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun compactTalkKeepsMessagesAndSettingsReachable() {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Inbox").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithText("Talk").performClick()
            compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
            compose.onNodeWithText("Chats").assertIsDisplayed().performClick()
            compose.onNodeWithText("Inbox").assertIsDisplayed()
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h360dp")
    fun shortScreenCanReachTheLastBackgroundDialogAction() {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        ActivityScenario.launch(MainActivity::class.java).use {
            val settings = compose.onNodeWithContentDescription("Settings")
            compose.waitUntil(5_000) { settings.isDisplayed() }
            settings.performClick()
            compose.onNodeWithText("Appearance").performClick()
            compose.onNodeWithText("Background").performScrollTo().performClick()
            compose.onNodeWithText("Upload your own").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Done").assertIsDisplayed().performClick()
            compose.onNodeWithText("Upload your own").assertDoesNotExist()
        }
    }

    @Test
    fun linksKeepReadableContrastAcrossAllAccentSwatches() {
        val checks = mutableListOf<Pair<Float, Float>>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column {
                        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                            for (accent in Accent.entries) {
                                P2pTheme(Appearance(mode, accent)) {
                                    val colors = MaterialTheme.colorScheme
                                    val link = readableAccent(colors.surface, colors.onSurface)
                                    val ink = inkOnAccent(accent.swatch)
                                    SideEffect { checks.add(contrastRatio(link, colors.surface) to contrastRatio(ink, accent.swatch)) }
                                }
                            }
                        }
                    }
                }
            }
            compose.runOnIdle {
                assertTrue(checks.size >= Accent.entries.size * 2)
                checks.forEach { (link, ink) ->
                    assertTrue("Link contrast $link", link >= 4.5f)
                    assertTrue("Button contrast $ink", ink >= 4.5f)
                }
            }
        }
    }

    private fun checkMainActions() {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Inbox").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithText("Start a chat").assertIsDisplayed()
            val empty = compose.onNodeWithText("No messages").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val whole = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Empty message is horizontally centered", kotlin.math.abs(empty.center.x - whole.center.x) <= 2f)
            assertTrue("Empty message is below the tab bar, near the center", empty.center.y >= whole.height * .42f)
            for (label in listOf("Inbox", "Archive", "Trash")) {
                compose.onNodeWithText(label).assertIsDisplayed()
                val text = layout(label)
                assertEquals(label, 1, text.lineCount)
                assertFalse(label, text.isLineEllipsized(0))
                assertFalse(label, text.hasVisualOverflow)
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            for (label in listOf("Notifications", "P2P address", "Connections", "Compression", "Help")) {
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                val text = layout(label)
                assertEquals(label, 1, text.lineCount)
                assertFalse(label, text.isLineEllipsized(0))
                assertFalse(label, text.hasVisualOverflow)
            }
            compose.onNodeWithText("Notifications").performScrollTo().performClick()
            compose.onNodeWithText("Show in notifications").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Android notification settings").performScrollTo().assertIsDisplayed()
        }
    }

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
}
