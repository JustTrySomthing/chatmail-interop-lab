package app.p2p

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
@Config(sdk = [34], qualifiers = "w320dp-h700dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentMenuTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun fourActionsAreReadableAndRouteIndependently() {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        var action = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {
                P2pTheme(Appearance(mode = ThemeMode.DARK)) {
                    Column {
                        AttachmentActions({ action = "photo" }, { action = "video" },
                            { action = "audio" }, { action = "file" })
                    }
                }
            } }
            for ((label, expected) in listOf("Take Photo" to "photo", "Record Video" to "video",
                "Record Audio" to "audio", "Attach File" to "file")) {
                compose.onNodeWithText(label).assertIsDisplayed().performClick()
                assertEquals(expected, action)
            }
            compose.onAllNodes(hasClickAction()).assertCountEquals(4)
            compose.onNodeWithText("Other File Type").assertDoesNotExist()
            compose.onNodeWithText("Photos").assertDoesNotExist()
        }
    }
}
