package app.p2p

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt

internal enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

internal enum class Accent(val label: String, val swatch: Color) {
    CRIMSON("Crimson", Color(0xFFC83146)),
    TANGERINE("Tangerine", Color(0xFFD57D21)),
    GOLD("Gold", Color(0xFFD8B828)),
    EMERALD("Emerald", Color(0xFF187950)),
    ROYAL_BLUE("Royal Blue", Color(0xFF245AA4)),
    CLASSIC_VIOLET("Violet", Color(0xFF5C3895)),
    HOT_PINK("Hot Pink", Color(0xFFFF4194)),
    NEON_ORANGE("Neon Orange", Color(0xFFFF793D)),
    ELECTRIC_YELLOW("Electric Yellow", Color(0xFFFFF13B)),
    NEON_GREEN("Neon Green", Color(0xFF23DF3A)),
    ELECTRIC_CYAN("Electric Cyan", Color(0xFF26D8E9)),
    ELECTRIC_PURPLE("Electric Purple", Color(0xFFAA71FF)),
    ROSE("Rose", Color(0xFFD3749D)),
    APRICOT("Apricot", Color(0xFFD99569)),
    SOFT_GOLD("Soft Gold", Color(0xFFCAA95C)),
    SEA_GREEN("Sea Green", Color(0xFF64A58D)),
    STEEL_BLUE("Steel Blue", Color(0xFF709EBD)),
    LAVENDER("Lavender", Color(0xFF9D86C6))
}

internal data class Appearance(
    val mode: ThemeMode = ThemeMode.DARK,
    val accent: Accent = Accent.CLASSIC_VIOLET,
    val fontSize: Int = 100,
    val wallpaper: String = "COSMIC_DRIFT",
    val customWallpaper: String = "",
    val overlay: Int = 10
) {
    fun json() = obj("mode" to mode.name, "accent" to accent.name, "font" to fontSize, "wallpaper" to wallpaper, "customWallpaper" to customWallpaper, "overlay" to overlay)

    companion object {
        fun read(data: JSONObject?) = Appearance(
            ThemeMode.entries.firstOrNull { it.name == data?.optString("mode") } ?: ThemeMode.DARK,
            Accent.entries.firstOrNull { it.name == data?.optString("accent") } ?: Accent.CLASSIC_VIOLET,
            (data?.optInt("font", 100) ?: 100).coerceIn(85, 140),
            data?.optString("wallpaper", "COSMIC_DRIFT") ?: "COSMIC_DRIFT",
            data?.optString("customWallpaper").orEmpty(),
            (data?.optInt("overlay", 10) ?: 10).coerceIn(0,100)
        )
    }
}

private val LocalContentFontSize = compositionLocalOf { 100 }

@Composable
internal fun contentTextStyle(style: TextStyle, percent: Int = LocalContentFontSize.current): TextStyle {
    val factor = percent / 100f
    fun scale(unit: TextUnit) = if (unit.isSp) unit * factor else unit
    return style.copy(fontSize = scale(style.fontSize), lineHeight = scale(style.lineHeight))
}

internal fun contrastRatio(first: Color, second: Color): Float {
    val a = first.luminance()
    val b = second.luminance()
    return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
}

internal fun inkOnAccent(accent: Color): Color =
    if (contrastRatio(accent, Color.Black) >= contrastRatio(accent, Color.White)) Color.Black else Color.White

@Composable
internal fun readableAccent(background: Color, fallback: Color): Color {
    val accent = MaterialTheme.colorScheme.primary
    return when {
        contrastRatio(accent, background) >= 4.5f -> accent
        contrastRatio(fallback, background) >= 4.5f -> fallback
        else -> inkOnAccent(background)
    }
}

@Composable
private fun actionForeground(): Color {
    val colors = MaterialTheme.colorScheme
    return if (listOf(colors.background, colors.surface, colors.surfaceVariant)
            .all { contrastRatio(colors.primary, it) >= 4.5f }) colors.primary else LocalContentColor.current
}

internal val P2pButtonShape = RoundedCornerShape(13.dp)

@Composable
internal fun P2pTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit
) = TextButton(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = contentPadding,
    colors = ButtonDefaults.textButtonColors(contentColor = actionForeground()), content = content)

@Composable
internal fun P2pOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) = Button(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = contentPadding,
    shape = P2pButtonShape, colors = ButtonDefaults.buttonColors(), content = content)

@Composable
internal fun P2pTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance.mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val primary = appearance.accent.swatch
    val onAccent = inkOnAccent(primary)
    val background = if (dark) Color(0xFF171717) else Color(0xFFF8F8F8)
    val surface = if (dark) Color(0xFF242424) else Color(0xFFF0F0F0)
    val variant = if (dark) Color(0xFF303337) else Color(0xFFE4E6E9)
    val text = if (dark) Color(0xFFF2F2F2) else Color(0xFF222222)
    val muted = if (dark) Color(0xFFB9B9B9) else Color(0xFF505050)
    val scheme = if (dark) darkColorScheme(
        primary = primary, onPrimary = onAccent, secondaryContainer = primary,
        onSecondaryContainer = onAccent, background = background, onBackground = text,
        surface = surface, onSurface = text, onSurfaceVariant = muted, surfaceVariant = variant,
        surfaceTint = Color.Transparent,
        outline = Color(0xFF757575)
    ) else lightColorScheme(
        primary = primary, onPrimary = onAccent, secondaryContainer = primary,
        onSecondaryContainer = onAccent, background = background, onBackground = text,
        surface = surface, onSurface = text, onSurfaceVariant = muted, surfaceVariant = variant,
        surfaceTint = Color.Transparent,
        outline = Color(0xFF686868)
    )
    val activity = LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalContentFontSize provides appearance.fontSize, LocalAppearance provides appearance) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
internal fun AppearanceControls(current: Appearance, change: ((Appearance) -> Appearance) -> Unit) {
    Text("Theme", style = MaterialTheme.typography.titleSmall)
    val dark = current.mode == ThemeMode.DARK || current.mode == ThemeMode.SYSTEM && isSystemInDarkTheme()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            P2pTextButton(onClick = { change { it.copy(mode = ThemeMode.DARK) } }) { Text("Dark") }
            Switch(checked = !dark, onCheckedChange = { light ->
                change { it.copy(mode = if (light) ThemeMode.LIGHT else ThemeMode.DARK) }
            }, modifier = Modifier.semantics { contentDescription = "Light theme" })
            P2pTextButton(onClick = { change { it.copy(mode = ThemeMode.LIGHT) } }) { Text("Light") }
        }
    }

    Text("Accent color", style = MaterialTheme.typography.titleSmall)
    Box(Modifier.fillMaxWidth().selectableGroup()) {
        val columns = 6
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Accent.entries.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { accent ->
                        val selected = current.accent == accent
                        Box(Modifier.weight(1f).height(48.dp)
                            .selectable(selected, role = Role.RadioButton, onClick = {
                                change { it.copy(accent = accent) }
                            })
                            .semantics { contentDescription = accent.label }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(32.dp).background(accent.swatch, CircleShape),
                                contentAlignment = Alignment.Center) {
                                if (selected) Icon(Icons.Filled.Check, null, Modifier.size(17.dp),
                                    tint = inkOnAccent(accent.swatch))
                            }
                        }
                    }
                }
            }
        }
    }

    BackgroundControls(current, change)
    var scale by remember(current.fontSize) { mutableFloatStateOf(current.fontSize.toFloat()) }
    val percent = scale.roundToInt()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Content font size", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text("$percent%", style = MaterialTheme.typography.labelLarge)
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium) {
        Text("This is how your messages and posts will look.", Modifier.fillMaxWidth().padding(12.dp),
            style = contentTextStyle(MaterialTheme.typography.bodyLarge, percent))
    }
    Slider(value = scale, onValueChange = { scale = it }, valueRange = 85f..140f, steps = 10,
        modifier = Modifier.semantics { contentDescription = "Content font size" },
        onValueChangeFinished = { change { it.copy(fontSize = scale.roundToInt()) } })
    P2pTextButton(onClick = { scale = 100f; change { it.copy(fontSize = 100) } }) { Text("Reset Font Size") }
}
