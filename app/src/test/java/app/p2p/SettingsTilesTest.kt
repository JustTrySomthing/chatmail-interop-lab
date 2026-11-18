package app.p2p

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTilesTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun darkTilesKeepEveryRouteAndSearchWorking() = verify(ThemeMode.DARK)
    @Test fun lightTilesKeepEveryRouteAndSearchWorking() = verify(ThemeMode.LIGHT)

    @Test @Config(qualifiers = "w320dp-h900dp")
    fun compactPhoneKeepsTwoColumns() = verify(ThemeMode.DARK)

    @Test @Config(qualifiers = "w280dp-h900dp")
    fun veryNarrowScreenKeepsTwoColumns() = verify(ThemeMode.DARK)

    @Test @Config(qualifiers = "w320dp-h900dp")
    fun largerSystemFontsKeepTwoColumnsAndCompleteLabels() = verify(ThemeMode.DARK, fontScale = 1.6f)

    private fun verify(mode: ThemeMode, fontScale: Float = 1f) {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        val query = mutableStateOf("")
        var opened: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { P2pTheme(Appearance(mode = mode)) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState()).padding(16.dp)) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        SettingsGrid(query.value) { opened = it }
                    }
                }
            } } }
            assertEquals(16, settingsDestinations.size)
            assertEquals(16, settingsDestinations.map { it.route }.distinct().size)
            val first = compose.onNodeWithText("Profile").fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithText("Profiles").fetchSemanticsNode().boundsInRoot
            assertTrue(second.left > first.right)
            assertEquals(first.top, second.top, 0.5f)
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File("build/ui-checks/settings-${mode.name.lowercase()}-${first.width.toInt()}-$fontScale-two-columns.png").apply { parentFile?.mkdirs() }
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            for (item in settingsDestinations) {
                compose.onNodeWithText(item.label, substring = false).performScrollTo().performClick()
                assertEquals(item.route, opened)
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(item.label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse(item.label, layouts.single().hasVisualOverflow)
                assertEquals(1, layouts.single().lineCount)
            }
            compose.runOnIdle { query.value = "updates" }
            compose.onNodeWithText("Updates").performScrollTo().assertIsDisplayed().performClick()
            assertEquals("updates", opened)
            compose.onNodeWithText("Profile").assertDoesNotExist()
            compose.runOnIdle { query.value = "no_such_setting" }
            compose.onNodeWithText("No matching settings").assertExists()
        }
    }
}
