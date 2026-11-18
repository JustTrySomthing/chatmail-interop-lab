package app.p2p

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomBarTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun smallPhoneShowsCompleteLabelsAndNavigates() = verify(1f)
    @Test fun largerFontsKeepLabelsAndTouchTargetsVisible() = verify(1.3f)

    @Test fun largeSystemFontsCannotGrowTheBar() = verify(2f)
    @Test @Config(qualifiers = "w280dp-h640dp")
    fun veryNarrowPhoneKeepsEverySelectedLabelOnOneLine() = verify(1.3f)

    private fun verify(fontScale: Float) {
        seedCompletedSetup(ApplicationProvider.getApplicationContext<P2pApplication>())
        val selected = mutableStateOf(Area.Chats)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { P2pTheme(Appearance(mode = ThemeMode.DARK)) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                        RelayNavigationBar(selected.value, { selected.value = it }, { it == Area.Groups })
                    }
                }
            } } }
            var baselineHeight: Float? = null
            for (area in Area.entries) {
                val tab = compose.onNodeWithText(area.label)
                tab.assertIsDisplayed().performClick().assertIsSelected()
                assertEquals(area, selected.value)
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(area.label, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse("${area.label} must not overflow", layouts.single().hasVisualOverflow)
                assertEquals(1, layouts.single().lineCount)
                val bounds = tab.fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.width >= 48f)
                assertTrue(bounds.height >= 48f)
                baselineHeight?.let { assertEquals(it, bounds.height, 0.1f) }
                baselineHeight = bounds.height
            }
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                File("build/ui-checks/bottom-bar-$fontScale.png").apply { parentFile?.mkdirs() }
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
