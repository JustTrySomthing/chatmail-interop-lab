package app.p2p

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class HelpEntry(val question: String, val answer: String)

private val helpEntries = listOf(
    HelpEntry("How do I connect with someone?",
        "Open Contacts → Add contact. Share your invite link or QR code, or choose Accept invite to scan their code or paste their link. Relay Lab, Delta Chat, and ArcaneChat can exchange Chatmail invites."),
    HelpEntry("How do I create or join a group?",
        "Open Groups to create a group. Open its details to add members, edit its description, or share its invite. Accept a group invite through Contacts → Add contact."),
    HelpEntry("How do I create or follow a channel?",
        "Open Channels → My Channels to create a channel and write posts. Share the channel’s invite from its details. Use Following → Join a channel to follow someone else’s channel. Feed collects their recent posts."),
    HelpEntry("How do I share a photo, video, or other file?",
        "Tap the paperclip in a chat or group. Choose a media type for inline playback or Other File Type to send the original as a file. Local file selection follows the Android clients’ 1 GiB ceiling. Mail relays can enforce smaller message limits."),
    HelpEntry("How do I send media at its original quality?",
        "Open Settings → Compression and turn off Automatic compression. You can also choose Other File Type for an individual attachment. Trimming a clip still prepares a new file."),
    HelpEntry("How do I add a mini-app or a game?",
        "Tap the four-square Apps button near the bottom of a Chatmail chat or group. Choose an app, tap Add to chat, and send it. Tap its message to open it. Shared apps and games can exchange updates with compatible clients."),
    HelpEntry("How do I call someone or turn on video?",
        "Open Talk or tap the phone button in a chat. Once connected, tap the camera to turn on video. Use the camera switch to change lenses. Android’s auto-rotate setting controls screen rotation."),
    HelpEntry("How do I control what notifications show?",
        "Open Settings → Notifications. Choose a preview, turn chat sounds on or off, and choose whether replies in muted groups and incoming calls alert you. Android controls each category’s sound, vibration, and priority."),
    HelpEntry("What if notifications arrive late?",
        "Open Settings → Notifications and check that Android allows alerts. Turn on Background delivery and check Battery optimization. Reliable background connection shows an ongoing Android notification. You can silence alerts overnight from Quiet Hours or mute a particular conversation."),
    HelpEntry("How do I switch profiles or move to a new phone?",
        "Open Settings → Profiles to add or switch profiles. Open Settings → Backup to export the selected Chatmail profile or show an Add second device QR code. On the new phone, choose the matching restore option during setup."),
    HelpEntry("How do I read messages aloud or change the theme?",
        "Tap a message’s speaker button to read it aloud. Settings → Read aloud changes voice and speed. Settings → Appearance changes your colors, theme, and text size."),
    HelpEntry("How do I find updates or report a problem?",
        "Open Settings → Updates and tap Check now. Turn on Include release candidates to get preview builds. For troubleshooting, open Settings → App log and copy the relevant error to share with support.")
)

@Composable
internal fun HelpSettingsContent() {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    SearchField(query, { query = it }, "Search help")
    val matching = helpEntries.filter {
        it.question.contains(query.trim(), ignoreCase = true) || it.answer.contains(query.trim(), ignoreCase = true)
    }
    if (matching.isEmpty()) Text("No matching questions", color = MaterialTheme.colorScheme.onSurfaceVariant)
    matching.forEach { entry ->
        val open = expanded == entry.question
        P2pRow(entry.question, if (open) "Hide answer" else "Show answer") {
            expanded = if (open) null else entry.question
        }
        if (open) Text(entry.answer, Modifier.padding(horizontal = 12.dp),
            style = contentTextStyle(MaterialTheme.typography.bodyMedium))
    }
}

@Composable
internal fun CompressionSettingsContent() {
    val context = LocalContext.current
    var automatic by remember { mutableStateOf(MediaCompression.enabled(context)) }
    SettingsSwitch("Automatic compression", "Let Relay Lab reduce the size of photos, videos, and audio before sending.", automatic) {
        MediaCompression.setEnabled(context, it)
        automatic = it
    }
    Text("Choose Other File Type to send a file as-is. Trimming still prepares a new clip.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun NotificationSettingsContent(
    model: P2pModel, muted: Boolean, allowed: Boolean, toggleMuted: (Boolean) -> Unit,
    systemSettings: () -> Unit, quietHours: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf(NotificationPreferences.preview(context)) }
    var chatSounds by remember { mutableStateOf(NotificationPreferences.inChatSounds(context)) }
    var background by remember { mutableStateOf(BackgroundMessages.enabled(context)) }
    var reliable by remember { mutableStateOf(BackgroundMessages.reliable(context)) }
    var batteryExempt by remember { mutableStateOf(BackgroundMessages.batteryExempt(context)) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val profile by produceState<Result<ProfileAlertPreferences>?>(null, model, attempt) {
        value = null
        value = runCatching { model.alertPreferences() }
    }
    fun update(action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                error = chatAction(action)
                if (error == null) attempt++
            } finally { busy = false }
        }
    }
    val batterySettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryExempt = BackgroundMessages.batteryExempt(context)
        if (background) BackgroundMessages.start(context, catchUp = true)
    }

    SettingsSwitch("Notifications", "Turn Relay Lab alerts on or off.", !muted) { toggleMuted(!it) }
    Text("Show in notifications", style = MaterialTheme.typography.titleMedium)
    AlertPreview.entries.forEach { choice ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = preview == choice, onClick = {
                NotificationPreferences.setPreview(context, choice)
                preview = choice
            })
            Text(choice.label)
        }
    }
    SettingsSwitch("In-chat sounds", "Play a sound for new messages in the conversation you’re viewing.", chatSounds) {
        NotificationPreferences.setInChatSounds(context, it)
        chatSounds = it
    }
    val profileOptions = profile?.getOrNull()
    SettingsSwitch("Replies in muted groups", "Alert when someone replies to one of your messages in a muted group.",
        profileOptions?.mentions == true, enabled = profileOptions != null && !busy) {
        update { model.setMentionAlerts(it) }
    }
    SettingsSwitch("Incoming calls", "Allow calls to this Chatmail profile.", profileOptions?.calls == true,
        enabled = profileOptions != null && !busy) { update { model.setCallAlerts(it) } }
    if (profile == null || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (profile?.isFailure == true) {
        Text("Connect this profile to load its call and reply options.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        P2pTextButton(onClick = { attempt++ }) { Text("Retry") }
    }

    Text("Sound, vibration, and priority", style = MaterialTheme.typography.titleMedium)
    if (!allowed) Text("Android is blocking Relay Lab notifications.", color = MaterialTheme.colorScheme.error)
    P2pRow("Android notification settings", "Enable alerts and choose which categories can appear.", click = systemSettings)
    listOf(AlertArea.CHATS, AlertArea.GROUPS, AlertArea.TALK).forEach { area ->
        P2pRow(area.label, "Sound, vibration, and other Android options") {
            LocalAlerts(context).registerChannels()
            error = runCatching {
                context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, area.channel))
            }.exceptionOrNull()?.let { "Android couldn’t open this notification category." }
        }
    }

    Text("Background delivery", style = MaterialTheme.typography.titleMedium)
    SettingsSwitch("Background delivery", "Keep checking for messages when Relay Lab is closed.", background) {
        if (!BackgroundMessages.setEnabled(context, it) && it) {
            error = "Android couldn’t start background delivery yet. Check Battery optimization."
        } else error = null
        background = it
    }
    SettingsSwitch("Reliable background connection", "Keep a connection active with a persistent Android notification.",
        reliable, enabled = background) {
        if (!BackgroundMessages.setReliable(context, it)) {
            error = "Android couldn’t start the background connection yet."
        } else error = null
        reliable = it
    }
    P2pRow("Battery optimization", if (batteryExempt) "Relay Lab can keep its connection active" else
        "Allow Relay Lab to keep its connection active") {
        val intent = if (batteryExempt) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else BackgroundMessages.batteryPermissionIntent(context)
        runCatching { batterySettings.launch(intent) }.onFailure { error = "Android couldn’t open battery settings." }
    }
    P2pRow("Quiet Hours", "Silence sounds during a schedule.", click = quietHours)
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun SettingsSwitch(title: String, detail: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = change, enabled = enabled)
    }
}
