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
                PersonalAppsDesktop(apps.value, null, { null }, { opened = it.key }, {},
                    hide = { key, hidden -> apps.value = apps.value.map { if (it.key == key) it.copy(payload = it.data().put("desktopHidden", hidden).toString()) else it } },
                    add = {}, loading = false, error = null, retry = {})
            } } }
            compose.onNodeWithText("Personal apps · Only you").assertDoesNotExist()
            compose.onNodeWithText("App 20").performClick()
            assertEquals(nativeMessageKey(20), opened)
            compose.onNodeWithContentDescription("Next page").performClick()
            compose.onNodeWithText("App 32").assertIsDisplayed().performClick()
            assertEquals(nativeMessageKey(32), opened)
            compose.onNodeWithContentDescription("Previous page").performClick()
            val icon = compose.onNodeWithTag("desktop-app-${nativeMessageKey(20)}")
            icon.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(700)
            icon.performTouchInput { up() }
            compose.onNodeWithText("Remove icon").assertIsDisplayed().performClick()
            assertEquals(nativeMessageKey(32), opened)
            compose.runOnIdle { assertEquals(13, apps.value.size) }
            compose.onNodeWithContentDescription("Manage hidden apps").performClick()
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
        val layout = mutableStateOf(personalDesktopLayout(apps, null))
        var opened = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { P2pTheme(Appearance()) {
                PersonalAppsDesktop(apps, layout.value.encode(), { null }, { opened = true }, { layout.value = it(layout.value) },
                    { _, _ -> }, {}, false, null, {})
            } } }
            val icon = compose.onNodeWithTag("desktop-app-${apps[0].key}")
            icon.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(1000)
            icon.performTouchInput { moveBy(Offset(width.toFloat(), 0f)); up() }
            compose.runOnIdle {
                assertEquals(listOf(apps[1].key, apps[0].key, apps[2].key), layout.value.pages.single())
                assertFalse(opened)
            }
            compose.onNodeWithText("Done").assertDoesNotExist()
        }
    }

    @Test fun holdOverAppCreatesFolderAndDraggingOutReturnsAppToDesktop() {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        val apps = (20..22).map { id -> Record(nativeMessageKey(id), "file-sent",
            obj("name" to "App $id.xdc", "nativeMessage" to id, "webxdc" to true,
                "thread" to nativeThreadKey(12)).toString(), id.toLong()) }
        val layout = mutableStateOf(personalDesktopLayout(apps, null))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { P2pTheme(Appearance()) {
                PersonalAppsDesktop(apps, layout.value.encode(), { null }, {}, { layout.value = it(layout.value) },
                    { _, _ -> }, {}, false, null, {})
            } } }
            val icon = compose.onNodeWithTag("desktop-app-${apps[0].key}")
            icon.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(700)
            icon.performTouchInput { moveBy(Offset(width.toFloat(), 0f)) }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(600)
            compose.onNodeWithTag("desktop-viewport").performTouchInput { up() }
            assertEquals("Folder", layout.value.folders.singleOrNull()?.name)
            compose.onNodeWithTag("desktop-folder-dialog").assertIsDisplayed()
            val member = compose.onNodeWithTag("desktop-member-${apps[0].key}")
            member.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(700)
            val y = member.fetchSemanticsNode().boundsInRoot.center.y
            val folderTop = compose.onNodeWithTag("desktop-folder-dialog").fetchSemanticsNode().boundsInRoot.top
            member.performTouchInput { moveBy(Offset(0f, folderTop - y - 24f)); up() }
            compose.runOnIdle {
                assertEquals(listOf(apps[1].key), layout.value.folders.single().members)
                assertTrue(apps[0].key in layout.value.pages.flatten())
            }
        }
    }

    @Test fun dragAcrossRightEdgeAndBackLeftKeepsChosenEmptyCell() {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        val app = Record(nativeMessageKey(20), "file-sent", obj("name" to "App.xdc", "webxdc" to true).toString())
        val layout = mutableStateOf(personalDesktopLayout(listOf(app), null))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { P2pTheme(Appearance()) {
                PersonalAppsDesktop(listOf(app), layout.value.encode(), { null }, {}, { layout.value = it(layout.value) },
                    { _, _ -> }, {}, false, null, {})
            } } }
            val viewport = compose.onNodeWithTag("desktop-viewport")
            viewport.performTouchInput { down(Offset(width / 6f, height / 8f)) }
            compose.mainClock.advanceTimeBy(700)
            viewport.performTouchInput { moveTo(Offset(width - 40f, height / 8f)) }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000)
            viewport.performTouchInput { moveTo(Offset(width * 5f / 6, height * 7f / 8)); up() }
            compose.waitForIdle()
            assertEquals(app.key, layout.value.pages[1][11])
            viewport.performTouchInput { down(Offset(width * 5f / 6, height * 7f / 8)) }
            compose.mainClock.advanceTimeBy(700)
            viewport.performTouchInput { moveTo(Offset(40f, height / 2f)) }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000)
            viewport.performTouchInput { moveTo(Offset(width / 2f, height * 5f / 8)); up() }
            compose.waitForIdle()
            assertEquals(app.key, layout.value.pages[0][7])
            compose.onNodeWithText("Done").assertDoesNotExist()
            scenario.onActivity { screenshot("desktop-free-placement", it.window.decorView) }
        }
    }

    @Test fun appMenuConfirmsOnlySelectedInstanceDeletion() = verifyAppMenu(ThemeMode.DARK)
    @Test fun appMenuIsReadableInLightTheme() = verifyAppMenu(ThemeMode.LIGHT)

    private fun verifyAppMenu(mode: ThemeMode) {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        val apps = (20..21).map { id -> Record(nativeMessageKey(id), "file-sent",
            obj("name" to "Calculator.xdc", "webxdc" to true).toString()) }
        var deleted: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { P2pTheme(Appearance(mode = mode)) {
                PersonalAppsDesktop(apps, null, { null }, {}, {}, { _, _ -> }, {}, false, null, {},
                    deleteApp = { deleted = it.key })
            } } }
            val icon = compose.onNodeWithTag("desktop-app-${nativeMessageKey(20)}")
            icon.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { it() }
            compose.onNodeWithText("Move to next page").assertDoesNotExist()
            compose.onNodeWithText("Move to previous page").assertDoesNotExist()
            compose.onNodeWithText("Remove icon").assertIsDisplayed()
            scenario.onActivity { screenshot("app-options-${mode.name.lowercase()}", org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView) }
            compose.onNodeWithText("Delete app and data").performClick()
            assertNull(deleted)
            compose.onNodeWithText("Cancel").performClick()
            assertNull(deleted)
            icon.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { it() }
            compose.onNodeWithText("Delete app and data").performClick()
            compose.onNodeWithText("Delete", substring = false).performClick()
            assertEquals(apps[0].key, deleted)
        }
    }

    private fun screenshot(name: String, view: android.view.View) {
        val file = File("build/ui-checks/$name.png").apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
