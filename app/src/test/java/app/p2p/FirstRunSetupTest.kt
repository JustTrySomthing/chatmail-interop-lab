package app.p2p

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal fun <T> withTestRecords(context: Context, action: suspend (Records) -> T): T {
    val store = LocalStore.open(context)
    return try {
        runBlocking { action(store.records()) }
    } finally {
        store.close()
    }
}

internal fun seedCompletedSetup(context: Context) {
    withTestRecords(context) { it.put(Record("profile", "profile", obj("name" to "Test User").toString())) }
    setupPreferences(context).edit().putBoolean("complete", true).commit()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FirstRunSetupTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun promptsExistingProfilesForPermissionsWithoutAskingForTheirNameAgain() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        setupPreferences(context).edit().remove("complete").commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Allow notifications").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithText("Display name").assertDoesNotExist()
            compose.onNodeWithText("Receive in background").assertDoesNotExist()
            compose.onNodeWithText("P2P can stay connected when the app is closed.").assertDoesNotExist()
        }
    }

    @Test
    fun opensSetupFromSettingsAndReturnsToSettings() {
        val context = ApplicationProvider.getApplicationContext<P2pApplication>()
        seedCompletedSetup(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Inbox").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Setup").performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Edit name").assertIsDisplayed()
            compose.onNodeWithText("Set up Relay Lab").assertIsDisplayed()
            compose.onNodeWithText("Receive in background").assertDoesNotExist()
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File("build/ui-checks/setup.png").apply { parentFile.mkdirs() }.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Settings").assertIsDisplayed()
        }
    }

    @Test
    fun savesDisplayNameBeforePermissionsAndKeepsSetupCompleteAcrossRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario: ActivityScenario<MainActivity> ->
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Set up Relay Lab").assertIsDisplayed() }.isSuccess
            }
            compose.onNode(hasSetTextAction()).performTextInput("Charles")
            compose.onNodeWithText("Continue").performClick()
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Allow notifications").assertIsDisplayed() }.isSuccess
            }
            compose.onNodeWithText("Continue with current permissions").performClick()
            compose.onNodeWithText("Inbox").assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithText("Inbox").assertIsDisplayed()
            val context = ApplicationProvider.getApplicationContext<P2pApplication>()
            assertEquals("Charles", withTestRecords(context) { it.get("profile")?.data()?.optString("name") })
        }
    }
}
