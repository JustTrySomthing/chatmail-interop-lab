package app.p2p

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SavedCollectionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun darkCollectionFiltersSearchesAndOpensExactItems() = verify(ThemeMode.DARK)
    @Test fun lightCollectionFiltersSearchesAndOpensExactItems() = verify(ThemeMode.LIGHT)

    private fun verify(mode: ThemeMode) {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        val self = nativeThreadKey(12)
        val text = Record(nativeMessageKey(40), "message", obj("thread" to self, "text" to "Remember the picnic on Sunday", "nativeOriginalId" to 20).toString(), 1700000100000)
        val file = Record(nativeMessageKey(41), "file-sent", obj("thread" to self, "name" to "Picnic checklist.pdf", "mime" to "application/pdf").toString(), 1700000000000)
        val source = Record(nativeMessageKey(20), "message", obj("thread" to "family", "text" to "Original only").toString())
        var opened: String? = null
        var origin: String? = null
        var removed: String? = null
        var added = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { P2pTheme(Appearance(mode = mode)) {
                SavedCollection(listOf(text, file, source), self, false, null, {}, {}, { opened = it.key }, { removed = it.key },
                    { origin = it.key }, { added = true }, conversationName = { "Family" })
            } } }
            compose.onNodeWithText("Family", substring = false).assertIsDisplayed()
            compose.onNodeWithText("Original only").assertDoesNotExist()
            scenario.onActivity {
                val view = ShadowDialog.getLatestDialog().window!!.decorView
                val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bmp))
                File("build/ui-checks/saved-${mode.name.lowercase()}.png").apply { parentFile?.mkdirs() }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithText("Remember the picnic on Sunday").performClick()
            assertEquals(text.key, opened)
            compose.onNodeWithContentDescription("Options for Saved message").performClick()
            compose.onNodeWithText("Original conversation").performClick()
            assertEquals(text.key, origin)
            compose.onNodeWithContentDescription("Options for Saved message").performClick()
            compose.onNodeWithText("Remove saved copy").performClick()
            compose.onNodeWithText("Cancel").performClick()
            assertNull(removed)
            compose.onNodeWithTag("saved-filters").performScrollToNode(hasText("Files", substring = false))
            compose.onNodeWithText("Files", substring = false).performClick()
            compose.onNodeWithText("Remember the picnic on Sunday").assertDoesNotExist()
            compose.onNodeWithText("Picnic checklist.pdf").performClick()
            assertEquals(file.key, opened)
            compose.onNodeWithText("Search saved items").performTextInput("missing")
            compose.onNodeWithText("No matching saved items").assertIsDisplayed()
            compose.onNodeWithContentDescription("Clear search").performClick()
            compose.onNodeWithText("Picnic checklist.pdf").assertIsDisplayed()
            compose.onNodeWithContentDescription("Add a note or file").performClick()
            assertTrue(added)
        }
    }
}
