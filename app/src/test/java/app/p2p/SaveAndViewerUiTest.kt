package app.p2p

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.robolectric.Robolectric
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SaveAndViewerUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun lockScreenAccessEndsWhenCallUiCloses() {
        val visible = mutableStateOf(true)
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent { CallLockScreen(visible.value, incoming = true) }
            compose.waitForIdle()
            val activity = org.robolectric.Shadows.shadowOf(controller.get())
            assertTrue(activity.showWhenLocked)
            assertTrue(activity.turnScreenOn)
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            assertFalse(activity.showWhenLocked)
            assertFalse(activity.turnScreenOn)
            compose.runOnIdle { controller.get().setContent {} }
            compose.waitForIdle()
        }
    }

    @Test fun saveStarShowsProgressThenSavedAndCanRetryAfterFailure() {
        val saving = mutableStateOf(false)
        val saved = mutableStateOf(false)
        var clicks = 0
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent { P2pTheme(Appearance()) {
                StarButton(saved.value, "message", MaterialTheme.colorScheme.surface, saving.value) { clicks++; saving.value = true }
            } }
            compose.onNodeWithContentDescription("Save message").performClick()
            compose.onNodeWithContentDescription("Saving message").assertExists()
            assertEquals(1, clicks)
            compose.runOnIdle { saving.value = false }
            compose.onNodeWithContentDescription("Save message").performClick()
            assertEquals(2, clicks)
            compose.runOnIdle { saving.value = false; saved.value = true }
            compose.onNodeWithContentDescription("Remove message from Saved").performClick()
            assertEquals(3, clicks)
            compose.onNodeWithContentDescription("Removing saved message").assertExists()
            compose.onNodeWithContentDescription("Save message").assertDoesNotExist()
            compose.runOnIdle { controller.get().setContent {} }
            compose.waitForIdle()
        }
    }

    @Test fun savedStarIsVisibleOnAnAccentColoredMessage() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent {
                P2pTheme(Appearance(accent = Accent.CLASSIC_VIOLET)) {
                    val colors = MaterialTheme.colorScheme
                    Surface(Modifier.fillMaxSize(), color = colors.primary, contentColor = colors.onPrimary) {
                        Box {
                            StarButton(true, "message", colors.primary) {}
                        }
                    }
                }
            }
            compose.onNodeWithContentDescription("Remove message from Saved").assertIsDisplayed()
            val background = Accent.CLASSIC_VIOLET.swatch
            compose.runOnIdle {
                val view = controller.get().findViewById<android.view.View>(android.R.id.content)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
                assertTrue(pixels.any { it == background.toArgb() })
                assertTrue(pixels.any { contrastRatio(Color(it), background) >= 4.5f })
            }
        }
    }

    @Test fun landscapeViewerHidesHostToolbarAndRestoresItInPortrait() {
        val fullscreen = mutableStateOf(true)
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent { P2pTheme(Appearance()) {
                WebxdcFrame("Calculator", Modifier.fillMaxSize(), {}, {}, {}, fullscreen = fullscreen.value) { Text("App content") }
            } }
            compose.onNodeWithText("App content").assertIsDisplayed()
            compose.onNodeWithText("Calculator").assertDoesNotExist()
            compose.onNodeWithContentDescription("Open apps").assertDoesNotExist()
            compose.runOnIdle { fullscreen.value = false }
            compose.onNodeWithText("Calculator").assertIsDisplayed()
            compose.onNodeWithContentDescription("Open apps").assertIsDisplayed()
            compose.runOnIdle { controller.get().setContent {} }
            compose.waitForIdle()
        }
    }
}
