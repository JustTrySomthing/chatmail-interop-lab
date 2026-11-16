package app.p2p

import android.os.SystemClock
import androidx.compose.ui.viewinterop.AndroidView
import org.thoughtcrime.securesms.EglUtils
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import android.text.format.DateFormat
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallMissed
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.util.Date

internal enum class TalkMicAction { Call, Answer, Message }

@Composable
internal fun RecentTalkCalls(
    calls: List<Record>,
    name: (String) -> String,
    photo: (String) -> String,
    onClear: () -> Unit,
    call: (String) -> Unit
) {
    if (calls.isEmpty()) {
        EmptyTabMessage("No recent calls", Modifier.fillMaxSize())
        return
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val zone = ZoneId.systemDefault()
    val dateFormat = remember(context, configuration, zone) { DateFormat.getMediumDateFormat(context) }
    val timeFormat = remember(context, configuration, zone) { DateFormat.getTimeFormat(context) }
    val groups = remember(calls, zone) {
        calls.sortedByDescending { it.created }.groupBy { Instant.ofEpochMilli(it.created).atZone(zone).toLocalDate() }
    }
    val colors = MaterialTheme.colorScheme

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceVariant, contentColor = colors.onSurface) {
                        Text("Recent calls", Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { heading() },
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
                Button(onClick = onClear, shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceVariant, contentColor = colors.onSurface),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Clear recent")
                }
            }
        }
        groups.forEach { (date, entries) ->
            item(key = "date:$date") {
                Surface(Modifier.fillMaxWidth().padding(top = 4.dp), shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceVariant) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dateFormat.format(Date(entries.first().created)), style = MaterialTheme.typography.titleSmall,
                            color = readableAccent(colors.surfaceVariant, colors.onSurface),
                            modifier = Modifier.semantics { heading() })
                        HorizontalDivider(Modifier.weight(1f), color = colors.outline.copy(alpha = .8f))
                    }
                }
            }
            items(entries, key = { it.key }) { entry ->
                val data = entry.data()
                val contact = data.optString("contact")
                RecentTalkCallRow(
                    name = data.optString("name").ifBlank { name(contact) }, photo = photo(contact), time = timeFormat.format(Date(entry.created)),
                    outgoing = data.optBoolean("outgoing"), missed = data.optString("detail") == "Missed call",
                    onCall = { call(contact) }
                )
            }
        }
    }
}

@Composable
private fun RecentTalkCallRow(
    name: String, photo: String, time: String, outgoing: Boolean, missed: Boolean, onCall: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val direction = when {
        missed -> "Missed"
        outgoing -> "Outgoing"
        else -> "Incoming"
    }
    val directionIcon = when {
        missed -> Icons.AutoMirrored.Outlined.CallMissed
        outgoing -> Icons.AutoMirrored.Outlined.CallMade
        else -> Icons.AutoMirrored.Outlined.CallReceived
    }
    Surface(color = colors.surfaceVariant, contentColor = colors.onSurface,
        shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name, photo = photo, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(directionIcon, null, Modifier.size(16.dp),
                        tint = if (missed) colors.error else readableAccent(colors.surfaceVariant, colors.onSurface))
                    Text("$direction · $time", style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant)
                }
            }
            FilledIconButton(onClick = onCall, modifier = Modifier.size(48.dp), shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) {
                Icon(Icons.Outlined.Call, "Call $name", Modifier.size(22.dp))
            }
        }
    }
}

@Composable
internal fun TalkCallPanel(
    call: TalkCall, name: String, photo: String, end: () -> Unit,
    answer: () -> Unit, speaker: () -> Unit, mute: () -> Unit, camera: () -> Unit, message: (() -> Unit)?
) {
    val colors = MaterialTheme.colorScheme
    val pending = call.phase == TalkPhase.Connecting || call.phase == TalkPhase.Incoming
    var elapsed by remember(call.id) { mutableLongStateOf(0L) }
    LaunchedEffect(call.connectedAt, call.phase) {
        while (call.connectedAt > 0 && call.phase == TalkPhase.Active) {
            elapsed = (SystemClock.elapsedRealtime() - call.connectedAt) / 1_000
            delay(500)
        }
    }
    Dialog(onDismissRequest = { if (call.phase == TalkPhase.Finished) end() },
        properties = DialogProperties(
            dismissOnClickOutside = false, dismissOnBackPress = call.phase == TalkPhase.Finished,
            usePlatformDefaultWidth = false
        )) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window, call.phase) {
            if (call.phase != TalkPhase.Finished) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        Surface(Modifier.padding(horizontal = 20.dp).widthIn(max = 424.dp).fillMaxWidth(),
            shape = P2pDialogShape, color = colors.surface, contentColor = colors.onSurface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                    if (pending) TalkPulse()
                    else Box(Modifier.size(104.dp).background(colors.primaryContainer, CircleShape))
                    Avatar(name, photo = photo, size = 80.dp)
                }
                Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (call.phase == TalkPhase.Active) {
                    call.remoteVideo?.takeIf { call.remoteCamera }?.let { track ->
                        TalkVideo(track, Modifier.fillMaxWidth().aspectRatio(4f / 3f), mirror = false)
                    }
                    call.localVideo?.takeIf { call.camera }?.let { track ->
                        TalkVideo(track, Modifier.width(110.dp).aspectRatio(3f / 4f), mirror = true)
                    }
                }
                if (pending || call.phase == TalkPhase.Active) {
                    TalkRouteAnimation(call.setupStep, pending, call.phase == TalkPhase.Incoming)
                }
                val status = when (call.phase) {
                    TalkPhase.Connecting -> call.setupStep.label
                    TalkPhase.Incoming -> if (call.startsWithVideo) "Incoming video call" else "Incoming Talk call"
                    TalkPhase.Active -> (elapsed / 60).toString() + ":" + (elapsed % 60).toString().padStart(2, '0')
                    TalkPhase.Finished -> call.detail
                }
                if (call.phase == TalkPhase.Active) {
                    Text("Connected", style = MaterialTheme.typography.labelLarge, color = colors.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                Text(status, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = if (call.phase == TalkPhase.Active) Modifier
                    else Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.height(4.dp))
                when (call.phase) {
                    TalkPhase.Incoming -> {
                        Button(onClick = answer, shape = P2pButtonShape, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Call, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Answer")
                        }
                        P2pOutlinedButton(onClick = end, modifier = Modifier.fillMaxWidth()) { Text("Decline") }
                    }
                    TalkPhase.Active, TalkPhase.Connecting -> {
                        if (call.phase == TalkPhase.Active) {
                            IconButton(onClick = camera, modifier = Modifier.semantics {
                                stateDescription = if (call.camera) "On" else "Off"
                            }) {
                                Icon(if (call.camera) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff,
                                    if (call.camera) "Turn camera off" else "Turn camera on",
                                    tint = if (call.camera) colors.primary else colors.onSurfaceVariant)
                            }
                            Button(onClick = mute, shape = P2pButtonShape, modifier = Modifier.fillMaxWidth()) {
                                Icon(if (call.muted) Icons.Outlined.MicOff else Icons.Outlined.Mic, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (call.muted) "Unmute microphone" else "Mute microphone")
                            }
                            P2pOutlinedButton(onClick = speaker, modifier = Modifier.fillMaxWidth().semantics {
                                stateDescription = if (call.speaker) "On" else "Off"
                            }) {
                                Icon(if (call.speaker) Icons.AutoMirrored.Outlined.VolumeUp else Icons.Outlined.Hearing, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (call.speaker) "Speaker on" else "Speaker off")
                            }
                        }
                        Button(onClick = end, shape = P2pButtonShape, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)) {
                            Icon(Icons.Outlined.CallEnd, null)
                            Spacer(Modifier.width(8.dp))
                            Text("End call")
                        }
                    }
                    TalkPhase.Finished -> {
                        if (message != null && call.outgoing && call.connectedAt == 0L) {
                            Button(onClick = message, shape = P2pButtonShape, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.MicNone, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Leave message")
                            }
                        }
                        P2pTextButton(onClick = end) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TalkPulse() {
    val color = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val transition = rememberInfiniteTransition(label = "Call pulse")
    val progress by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1_650, easing = LinearEasing)), label = "Ring progress")
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(container, radius = size.minDimension * .37f)
        repeat(2) { index ->
            val p = (progress + index * .5f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * .58f), radius = size.minDimension * (.34f + .15f * p),
                style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
private fun TalkRouteAnimation(step: TalkSetupStep, pending: Boolean, incoming: Boolean) {
    val colors = MaterialTheme.colorScheme
    val phase = if (pending) {
        val transition = rememberInfiniteTransition(label = "Call route")
        transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
            label = "Call route pulse"
        )
    } else null
    Canvas(Modifier.widthIn(max = 280.dp).fillMaxWidth().height(64.dp)) {
        val centerY = size.height / 2f
        val first = 18.dp.toPx()
        val last = size.width - first
        val span = last - first
        if (span <= 0f) return@Canvas

        val active = if (pending) {
            if (incoming) 2 else step.ordinal
        } else TalkSetupStep.entries.size
        val points = List(TalkSetupStep.entries.size + 1) { index ->
            Offset(first + span * index / TalkSetupStep.entries.size, centerY)
        }
        drawLine(colors.primary.copy(alpha = .24f), points.first(), points.last(),
            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        if (active > 0) {
            drawLine(colors.primary, points.first(), points[active],
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
        points.forEachIndexed { index, point ->
            val reached = index <= active
            drawCircle(if (reached) colors.primary else colors.onSurfaceVariant.copy(alpha = .48f),
                radius = (if (index == active) 6.dp else 4.dp).toPx(), center = point)
        }
        if (phase != null) {
            val progress = phase.value
            val node = points[active]
            drawCircle(colors.primary.copy(alpha = (1f - progress) * .5f),
                radius = (9.dp + 13.dp * progress).toPx(), center = node,
                style = Stroke(1.5.dp.toPx()))
            val next = points[active + 1]
            val dot = node + (next - node) * progress
            drawCircle(colors.primary.copy(alpha = kotlin.math.sin(progress * Math.PI).toFloat()),
                radius = 3.dp.toPx(), center = dot)
        }
    }
}

internal fun recentCallTime(time: Long): String {
    val today = java.time.LocalDate.now()
    val date = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault())
    return if (date.toLocalDate() == today) java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(time))
        else java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(time))
}

@Composable
private fun TalkVideo(track: VideoTrack, modifier: Modifier, mirror: Boolean) {
    val context = LocalContext.current
    val renderer = remember(track) { SurfaceViewRenderer(context) }
    DisposableEffect(track, renderer) {
        renderer.init(EglUtils.getEglBase().eglBaseContext, null)
        renderer.setMirror(mirror)
        track.addSink(renderer)
        onDispose {
            runCatching { track.removeSink(renderer) }
            renderer.release()
        }
    }
    key(track) { AndroidView(factory = { renderer }, modifier = modifier) }
}
