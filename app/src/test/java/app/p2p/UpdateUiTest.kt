package app.p2p

import android.net.ConnectivityManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun showsUpdateWithoutNavigatingAwayFromGroupsOrTalk() {
        // Robolectric reuses this AndroidX singleton between sandbox applications.
        org.robolectric.util.ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        val context = RuntimeEnvironment.getApplication()
        seedCompletedSetup(context)
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        val version = requireNotNull(UpdateVersion.parse("9.0.0-rc1"))
        val release = UpdateRelease(version, "P2P-$version-ARM64-Preview.apk", 10L, "a".repeat(64), true)
        val manager = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        manager.clearAllNetworks()
        manager.setActiveNetworkInfo(null)
        val now = System.currentTimeMillis()
        val boot = UpdateChecks.boot(context)
        val prefs = UpdateChecks.prefs(context)
        prefs.edit(commit = true) {
            putBoolean("automatic", true)
            putInt("openedBoot", boot)
            putInt("attemptBoot", boot)
            putLong("attempted", now)
            putLong("checked", now)
            putString("available", release.json().toString())
            putString("dismissed", version.toString())
            putLong("dismissedAt", now)
        }
        assertEquals(release, UpdateChecks.available(prefs))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Exercise the cached prompt without a periodic check competing with the fixture.
            scenario.onActivity { ViewModelProvider(it)[P2pUpdateModel::class.java].setForeground(false) }
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText("Inbox").assertIsDisplayed() }.isSuccess
            }
            for ((tab, control) in listOf("Groups" to "Inbox", "Talk" to "Make a Call")) {
                compose.onAllNodesWithText(tab)[0].performClick()
                compose.onNodeWithText(control).assertIsDisplayed()
                scenario.onActivity {
                    UpdateChecks.prefs(it).edit(commit = true) {
                        putBoolean("automatic", true)
                        putString("available", release.json().toString())
                        remove("dismissed")
                        remove("dismissedAt")
                    }
                    val updater = ViewModelProvider(it)[P2pUpdateModel::class.java]
                    val current = UpdateChecks.prefs(it)
                    assertEquals("Cached release on $tab", release, UpdateChecks.available(current))
                    assertEquals("Prompt eligibility on $tab", true, UpdateChecks.shouldPrompt(current, release))
                    assertEquals("Busy state on $tab", false, updater.state.busy)
                    assertEquals("Install request on $tab", false, updater.state.requestInstall)
                    updater.refreshPrompt()
                }
                compose.waitForIdle()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("Relay Lab update available").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Update Now").assertIsDisplayed()
                if (tab == "Talk") {
                    compose.onNodeWithText("Update Now").performClick()
                    compose.waitUntil(5_000) {
                        compose.onAllNodesWithText("Check for Updates").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText("Check for Updates").assertIsDisplayed()
                    compose.onNodeWithText("Update Now").assertIsDisplayed()
                    continue
                }
                compose.onNodeWithText("Later").performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("Relay Lab update available").fetchSemanticsNodes().isEmpty()
                }
                compose.onNodeWithText(control).assertIsDisplayed()
            }
        }
    }
}
