package app.p2p

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

internal data class ReactionMark(val emoji: String, val token: String)

private const val BurstDuration = 4600
private fun progress(elapsed: Float, delay: Float, duration: Float) =
    ((elapsed - delay) / duration).coerceIn(0f, 1f)

private fun opacity(progress: Float): Float = when {
    progress <= 0f || progress >= 1f -> 0f
    progress < .17f -> progress / .17f
    progress > .76f -> (1f - progress) / .24f
    else -> 1f
}

private data class Particle(
    val side: Int, val drift: Float, val fontSize: Float, val rise: Float,
    val delay: Float, val duration: Float, val tilt: Float
)

@Composable
internal fun BoxScope.ReactionDecoration(
    messageKey: String,
    marks: Map<String, ReactionMark>,
    seen: MutableMap<String, Map<String, ReactionMark>>,
    onAnimating: (Boolean) -> Unit
) {
    var active by remember { mutableStateOf<ReactionMark?>(null) }
    val elapsed = remember { Animatable(BurstDuration.toFloat()) }
    val density = LocalDensity.current.density
    val reportAnimation by rememberUpdatedState(onAnimating)
    LaunchedEffect(marks) {
        val previous = seen[messageKey].orEmpty()
        val incoming = marks.entries.lastOrNull { (actor, mark) -> previous[actor] != mark }?.value
        if (marks.isEmpty()) seen.remove(messageKey) else seen[messageKey] = marks
        active = incoming
        reportAnimation(incoming != null)
        if (incoming != null) {
            elapsed.snapTo(0f)
            elapsed.animateTo(BurstDuration.toFloat(), tween(BurstDuration, easing = LinearEasing))
        }
        active = null
        reportAnimation(false)
    }

    val animation = active ?: return
    val time = elapsed.value
    val main = progress(time, 70f, 4200f)
    Text(animation.emoji, Modifier.align(Alignment.BottomCenter).clearAndSetSemantics {}.graphicsLayer {
        alpha = opacity(main)
        translationY = (40f - 265f * main) * density
        translationX = sin(main * 4 * PI).toFloat() * 5 * density
        scaleX = if (main < .17f) .75f + .33f * main / .17f else 1.08f - .16f * ((main - .17f) / .83f)
        scaleY = scaleX
    }, fontSize = 80.sp)

    val particles = remember(animation) {
        val random = Random(animation.token.hashCode())
        List(10) { index ->
            Particle(if (index % 2 == 0) -1 else 1, 36 + random.nextFloat() * 65,
                17 + random.nextFloat() * 13, 155 + random.nextFloat() * 105, 140f + index * 105,
                3000 + random.nextFloat() * 350, 7 + random.nextFloat() * 15)
        }
    }
    particles.forEach { particle ->
        val t = progress(time, particle.delay, particle.duration)
        Text(animation.emoji, Modifier.align(Alignment.BottomCenter).clearAndSetSemantics {}.graphicsLayer {
            alpha = opacity(t) * (.74f - .15f * t)
            translationX = particle.side * (22 + (particle.drift + 10) * t + sin(t * PI * 2).toFloat() * 12) * density
            translationY = (52 - particle.rise * t) * density
            rotationZ = particle.side * particle.tilt * (2 * t - 1)
            scaleX = when {
                t < .21f -> .45f + .5f * t / .21f
                t > .62f -> 1 - .34f * (t - .62f) / .38f
                else -> 1f
            }
            scaleY = scaleX
        }, fontSize = particle.fontSize.sp)
    }
}
