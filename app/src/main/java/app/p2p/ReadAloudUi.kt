package app.p2p

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.NumberFormat

internal val LocalReadAloud = staticCompositionLocalOf<ReadAloud?> { null }

@Composable
internal fun ReadAloudButton(key: String, text: String, label: String) {
    val reader = LocalReadAloud.current ?: return
    if (text.isBlank()) return
    val active = reader.active == key
    IconButton(onClick = { reader.toggle(key, text) }, modifier = Modifier.size(48.dp)) {
        if (active) {
            val rotation by rememberInfiniteTransition(label = "message-speech").animateFloat(
                0f, 360f, infiniteRepeatable(tween(950, easing = LinearEasing)), label = "speech-rotation"
            )
            val ink = LocalContentColor.current
            Canvas(Modifier.size(24.dp).graphicsLayer { rotationZ = rotation }
                .semantics { contentDescription = "Reading $label aloud. Tap to stop." }) {
                val unit = size.minDimension / 24f
                withTransform({ scale(unit, unit, pivot = Offset.Zero) }) {
                    drawArc(ink, 140f, 265f, false, Offset(4f, 4f), Size(16f, 16f),
                        style = Stroke(2.5f, cap = StrokeCap.Butt))
                    drawPath(Path().apply {
                        moveTo(14f, 12.5f)
                        lineTo(20f, 20f)
                        lineTo(14f, 20f)
                        close()
                    }, ink)
                }
            }
        } else {
            Icon(painterResource(R.drawable.ref_message_speaker), "Read $label aloud", Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun ReadAloudSettings(reader: ReadAloud) {
    if (!reader.supported) {
        Text("The included voices are available on ARM64 Android devices.")
        return
    }
    Text("Voice", style = MaterialTheme.typography.titleMedium)
    Segments(ReadAloudVoice.entries.map(ReadAloudVoice::label), reader.voice.label) { label ->
        reader.choose(ReadAloudVoice.entries.first { it.label == label })
    }
    val speedLabel = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(reader.speed)
    Text("Speed · ${speedLabel}×", style = MaterialTheme.typography.titleMedium)
    Slider(value = reader.speed, onValueChange = reader::changeSpeed, valueRange = 0.75f..1.5f, steps = 14,
        onValueChangeFinished = reader::saveSpeed, modifier = Modifier.semantics { contentDescription = "Reading speed" })
    Button(onClick = {
        reader.toggle("voice-preview", "This is how your messages will sound when read aloud.")
    }) {
        Text(if (reader.active == "voice-preview") "Stop Preview" else "Preview Voice")
    }
    reader.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    LaunchedEffect(Unit) { reader.prepare() }
}
