package app.p2p

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import android.graphics.Bitmap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonalSpaceUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun desktopPagesOpenExactInstancesAndKeepRemovedIconData() {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        val apps = mutableStateOf((20..32).map { id -> Record(nativeMessageKey(id), "file-sent",
            obj("name" to "App $id.xdc", "nativeMessage" to id, "webxdc" to true,
                "thread" to nativeThreadKey(12)).toString(), id.toLong()) })
        var opened: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { P2pTheme(Appearance()) {
                PersonalAppsDesktop(apps.value, emptyList(), { null }, { opened = it.key }, {},
                    hide = { key, hidden -> apps.value = apps.value.map { if (it.key == key) it.copy(payload = it.data().put("desktopHidden", hidden).toString()) else it } },
                    movePage = { _, _ -> }, add = {}, loading = false, error = null, retry = {})
            } } }
            compose.onNodeWithText("Personal apps · Only you").assertIsDisplayed()
            compose.onNodeWithText("App 20").performClick()
            assertEquals(nativeMessageKey(20), opened)
            compose.onNodeWithContentDescription("Next page").performClick()
            compose.onNodeWithText("App 32").assertIsDisplayed().performClick()
            assertEquals(nativeMessageKey(32), opened)
            compose.onNodeWithContentDescription("Previous page").performClick()
            compose.onNodeWithContentDescription("Options for App 20").performClick()
            compose.onNodeWithText("Remove icon (keep app data)").performClick()
            compose.runOnIdle { assertEquals(13, apps.value.size) }
            compose.onNodeWithContentDescription("Manage app icons").performClick()
            compose.onNodeWithText("Restore App 20").performClick()
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("App 20").assertIsDisplayed()
            scenario.onActivity { screenshot("personal-apps", it.window.decorView) }
        }
    }

    @Test fun savedCollectionShowsCopiesAcrossChatsAndConfirmsRemoval() {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        val self = nativeThreadKey(12)
        fun message(id: Int, text: String, thread: String, original: Int = 0) = Record(nativeMessageKey(id), "message",
            obj("thread" to thread, "text" to text, "nativeMessage" to id, "nativeOriginalId" to original).toString())
        val rows = listOf(message(40, "Saved from Avery", self, 20), message(41, "Saved from family", self, 21),
            message(20, "Original Avery", nativeThreadKey(13)), message(21, "Original family", nativeThreadKey(14)))
        var removed: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { P2pTheme(Appearance()) {
                SavedCollection(rows, self, false, "Unable to remove this saved item", {}, {}, {}, { removed = it.key }, {}, {})
            } } }
            compose.onNodeWithText("Unable to remove this saved item").assertIsDisplayed()
            compose.onNodeWithText("Saved from Avery").assertIsDisplayed()
            compose.onNodeWithText("Saved from family").assertIsDisplayed()
            compose.onNodeWithText("Original Avery").assertDoesNotExist()
            
            compose.onAllNodesWithText("Remove saved copy")[0].performClick()
            assertNull(removed)
            compose.onNodeWithText("Remove", useUnmergedTree = true).performClick()
            assertEquals(nativeMessageKey(40), removed)
        }
    }

    @Test fun draggingAnIconReordersWithoutOpeningTheApp() {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        val apps = (20..22).map { id -> Record(nativeMessageKey(id), "file-sent",
            obj("name" to "App $id.xdc", "nativeMessage" to id, "webxdc" to true, "thread" to nativeThreadKey(12)).toString(), id.toLong()) }
        val order = mutableStateOf(apps.map { it.key })
        var opened = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { P2pTheme(Appearance()) {
                PersonalAppsDesktop(apps, order.value, { null }, { opened = true }, { order.value = it },
                    { _, _ -> }, { _, _ -> }, {}, false, null, {})
            } } }
            val icon = compose.onNodeWithTag("desktop-app-${apps[0].key}")
            icon.performTouchInput { down(Offset(20f, 70f)) }
            compose.mainClock.advanceTimeBy(1000)
            icon.performTouchInput { moveBy(Offset(110f, 0f)); up() }
            compose.runOnIdle {
                assertEquals(listOf(apps[1].key, apps[0].key, apps[2].key), order.value)
                assertFalse(opened)
            }
        }
    }

    private fun screenshot(name: String, view: android.view.View) {
        val file = File("build/ui-checks/$name.png").apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
