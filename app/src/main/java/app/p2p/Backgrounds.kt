package app.p2p

import android.net.Uri
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal val LocalAppearance = compositionLocalOf { Appearance() }

@Composable
internal fun AppBackground(modifier: Modifier = Modifier, appearance: Appearance = LocalAppearance.current) {
    val dark = appearance.mode != ThemeMode.LIGHT
    val resource = when (appearance.wallpaper) {
        "COSMIC_DRIFT" -> if (dark) R.drawable.wallpaper_cosmos_dark else R.drawable.wallpaper_cosmos_light
        "STARRY_NIGHTS" -> if (dark) R.drawable.wallpaper_starry_dark else R.drawable.wallpaper_starry_light
        "ANGKOR_WAT" -> if (dark) R.drawable.wallpaper_angkor_dark else R.drawable.wallpaper_angkor_light
        "GREAT_WAVE" -> if (dark) R.drawable.wallpaper_greatwave_dark else R.drawable.wallpaper_greatwave_light
        else -> null
    }
    val context = LocalContext.current
    val custom by produceState<android.graphics.Bitmap?>(null, appearance.customWallpaper, appearance.wallpaper) {
        value = withContext(Dispatchers.IO) {
            if (appearance.wallpaper != "CUSTOM" || appearance.customWallpaper.isBlank()) return@withContext null
            runCatching {
                val uri = Uri.parse(appearance.customWallpaper)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                options.inJustDecodeBounds = false
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 2048 || options.outHeight / options.inSampleSize > 4096) options.inSampleSize *= 2
                context.contentResolver.openInputStream(Uri.parse(appearance.customWallpaper))?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }.getOrNull()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        if (resource != null) Image(painterResource(resource), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else if (appearance.wallpaper == "CUSTOM" && custom != null) Image(custom!!.asImageBitmap(), null,
            Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        if (appearance.wallpaper != "AUTOMATIC") Box(Modifier.matchParentSize()
            .background((if (dark) Color.Black else Color.White).copy(alpha = appearance.overlay / 100f)))
    }
}

@Composable
internal fun BackgroundControls(current: Appearance, change: ((Appearance) -> Appearance) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    var overlay by remember(current.overlay) { mutableFloatStateOf(current.overlay.toFloat()) }
    AppBackground(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(16.dp)), current.copy(overlay = overlay.roundToInt()))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Background overlay")
        Text("${overlay.roundToInt()}%")
    }
    Slider(overlay, { overlay = it }, valueRange = 0f..100f,
        onValueChangeFinished = { change { it.copy(overlay = overlay.roundToInt()) } })
    Button(onClick = { choosing = true }, shape = P2pButtonShape, modifier = Modifier.fillMaxWidth()) { Text("Background") }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            change { it.copy(wallpaper = "CUSTOM", customWallpaper = uri.toString()) }
            choosing = false
        }
    }
    if (choosing) P2pAlertDialog(onDismissRequest = { choosing = false }, title = { Text("Background") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("AUTOMATIC" to "◐ Automatic", "COSMIC_DRIFT" to "Cosmic Drift", "STARRY_NIGHTS" to "Starry Nights",
                "ANGKOR_WAT" to "Angkor Wat", "GREAT_WAVE" to "The Great Wave").forEach { (key, label) ->
                Button(onClick = { change { it.copy(wallpaper = key) }; choosing = false }, shape = P2pButtonShape, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
            Button(onClick = { picker.launch(arrayOf("image/*")) }, shape = P2pButtonShape, modifier = Modifier.fillMaxWidth()) { Text("Upload Your Own") }
        }
    }, confirmButton = { P2pTextButton(onClick = { choosing = false }) { Text("Done") } })
}
