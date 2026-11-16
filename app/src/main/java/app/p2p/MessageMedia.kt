package app.p2p

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.util.Size
import android.widget.ImageView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import org.json.JSONArray
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class EmojiEntry(val symbol: String, val name: String)

private val emojiGroups = mutableMapOf<String, List<EmojiEntry>>()

private fun emojiCatalog(context: Context): Map<String, List<EmojiEntry>> = synchronized(emojiGroups) {
    if (emojiGroups.isEmpty()) {
        val rows = JSONArray(context.assets.open("emoji.json").bufferedReader().use { it.readText() })
        emojiGroups.putAll((0 until rows.length()).map(rows::getJSONObject)
            .groupBy({ it.getString("category") }, { EmojiEntry(it.getString("emoji"), it.getString("name")) }))
    }
    emojiGroups.toMap()
}

internal fun isPickerEmoji(context: Context, value: String): Boolean =
    value.length in 1..64 && (value in messageReactions || emojiCatalog(context).values.any { entries ->
        entries.any { it.symbol == value }
    })

@Composable
internal fun EmojiPicker(modifier: Modifier, insert: (String) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val groups by produceState(emptyMap<String, List<EmojiEntry>>(), context) {
        value = withContext(Dispatchers.IO) { emojiCatalog(context) }
    }
    var category by rememberSaveable { mutableStateOf("Faces") }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.keys.forEach { label ->
                        FilterChip(selected = category == label, onClick = { category = label }, label = { Text(label) })
                    }
                }
            }
            key(category) {
                LazyVerticalGrid(columns = GridCells.Adaptive(44.dp), modifier = Modifier.weight(1f)) {
                    items(groups[category].orEmpty()) { emoji ->
                        P2pTextButton(onClick = { insert(emoji.symbol) }, contentPadding = PaddingValues(0.dp)) {
                            Text(emoji.symbol, fontSize = 23.sp, modifier = Modifier.semantics { contentDescription = emoji.name })
                        }
                    }
                }
            }
            P2pTextButton(onClick = close, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }
}

@Composable
internal fun mediaThumbnail(file: File?, kind: MediaKind?): Bitmap? {
    val thumbnail by produceState<Bitmap?>(null, file, kind) {
        value = null
        value = if (file == null || kind == null || kind == MediaKind.Audio) null else withContext(Dispatchers.IO) {
            runCatching {
                if (kind == MediaKind.Video) {
                    runCatching { ThumbnailUtils.createVideoThumbnail(file, Size(480, 480), null) }.getOrNull()
                        ?: MediaMetadataRetriever().use { retriever ->
                            retriever.setDataSource(file.absolutePath)
                            retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
                        }
                }
                else ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val scale = (maxOf(info.size.width, info.size.height) / 480.0).coerceAtLeast(1.0)
                    decoder.setTargetSize((info.size.width / scale).toInt().coerceAtLeast(1),
                        (info.size.height / scale).toInt().coerceAtLeast(1))
                }
            }.getOrNull()
        }
    }
    return thumbnail
}

@Composable
internal fun InlineAnimatedImage(file: File, label: String, fullScreen: Boolean = false) {
    val image by produceState<Result<Drawable>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val ratio = (maxOf(info.size.width, info.size.height) / 480.0).coerceAtLeast(1.0)
                    decoder.setTargetSize((info.size.width / ratio).toInt().coerceAtLeast(1),
                        (info.size.height / ratio).toInt().coerceAtLeast(1))
                }
            }
        }
    }
    val drawable = image?.getOrNull()
    if (drawable == null) {
        if (fullScreen && image != null) Text("This animated image couldn’t be opened.")
        return
    }
    val animated = drawable as? AnimatedImageDrawable
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val shouldPlay = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    DisposableEffect(drawable) {
        animated?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        onDispose { animated?.stop() }
    }
    AndroidView(
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        modifier = if (fullScreen) Modifier.fillMaxSize() else Modifier.widthIn(max = 280.dp).fillMaxWidth().height(150.dp),
        update = { view ->
            view.contentDescription = label
            if (view.drawable !== drawable) view.setImageDrawable(drawable)
            if (shouldPlay) animated?.start() else animated?.stop()
        }
    )
}
