package app.p2p

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class HelpEntry(val question: String, val answer: String)

private val helpEntries = listOf(
    HelpEntry("How do I connect with someone?",
        "Open Contacts → Add contact. Share your invite link or QR code, or choose Accept invite to scan their code or paste their link. Relay Lab, Delta Chat, and ArcaneChat can exchange Chatmail invites."),
    HelpEntry("How do I create or join a group?",
        "Open Groups to create a group. Open its details to add members, edit its description, or share its invite. Accept a group invite through Contacts → Add contact. To leave, open the group’s details and choose Leave Group; existing messages stay here."),
    HelpEntry("How do I create or follow a channel?",
        "Open Channels → My Channels to create a channel and write posts. Share the channel’s invite from its details. Use Following → Join a channel to follow someone else’s channel. Feed collects their recent posts. Tap the emoji button to react. To message the owner with the post quoted, open Post Options and choose Reply Privately. Open a channel to unfollow it. For your channels, use Post Options to edit or delete a post, or Delete Channel in the channel’s details."),
    HelpEntry("How do I share a photo, video, or other file?",
        "Use Add attachment in chats, groups, or channels. Choose Take Photo, Record Video, Record Audio, or Attach File for existing media and documents. Local file selection follows the Android clients’ 1 GiB ceiling. Mail relays can enforce smaller message limits."),
    HelpEntry("How do I send media at its original quality?",
        "Use Add Attachment → Attach Original File below the divider. It sends the selected file without media compression; larger files use more data and may take longer. Normal Attach File follows Settings → Compression for supported media. Original sending does not restore quality already lost, bypass file-size or server limits, or change your compression preference. A previously trimmed file remains trimmed."),
    HelpEntry("How do I add a mini-app or a game?",
        "For a shared app, tap the four-square Apps button near the bottom of a Chatmail chat or group. Choose an app, tap Add to chat, and send it. Tap its message to open it. Shared apps and games can exchange updates with compatible clients."),
    HelpEntry("How do I call someone or turn on video?",
        "Open Talk or tap the phone button in a chat. Once connected, choose Turn Video On to enable the camera. Use the camera switch to change lenses. Android’s auto-rotate setting controls screen rotation."),
    HelpEntry("How do I control what notifications show?",
        "Open Settings → Notifications. Choose a preview, turn chat sounds on or off, and choose whether replies in muted groups and incoming calls alert you. Android controls each category’s sound, vibration, and priority."),
    HelpEntry("What if notifications arrive late?",
        "Open Settings → Notifications and check that Android allows alerts. Background delivery is automatic; check Battery optimization and Incoming call screen in Setup. Reliable background connection shows an ongoing Android notification. You can silence alerts overnight from Quiet Hours or mute a particular conversation."),
    HelpEntry("How do I switch profiles or move to a new phone?",
        "Open Settings → Profiles to add, switch, or delete a profile from this device. Back up important data before deleting. Open Settings → Backup to export the selected Chatmail profile or show an Add second device QR code. On the new phone, choose the matching restore option during setup."),
    HelpEntry("How do I free storage automatically?",
        "Open Settings → Storage and choose how long to keep messages on this device. Cleanup is off by default and asks for confirmation before removing existing history. Saved items and personal apps are kept; other people’s copies are unchanged."),
    HelpEntry("How do I read messages aloud or change the theme?",
        "Tap a message’s speaker button to read it aloud. Settings → Read aloud changes voice and speed. Settings → Appearance changes your colors, theme, and text size."),
    HelpEntry("Where are my saved items?",
        "Tap the star in the top bar to open Saved for the current profile. Tap an item’s save star to save a copy; its highlighted theme color shows that it is saved. Tap again to remove it from Saved. Use the filters or search to find messages, photos, files, apps and other saved items."),
    HelpEntry("What do the Saved options mean?",
        "Open Saved Item opens the saved copy. Go to the Chat, Group or Channel returns to its source when that source is available. Remove From Saved removes the saved copy, not the original message. Deleting the original normally leaves its saved copy available. If an item exists only in Saved, removing it may remove your only copy."),
    HelpEntry("What is the difference between personal and shared apps?",
        "Apps added from the Apps page are personal instances for your own use. Apps sent to a chat or group belong to that conversation and can share updates with its participants. Separate copies have separate state. Installing a personal app does not add it to someone else’s conversation."),
    HelpEntry("How do I organize apps or remove one?",
        "Long-press and drag an app icon to an empty desktop position. Drag toward an edge to move between pages, or onto another icon to create a folder. Long-press an app or folder for its options. Remove Icon hides the shortcut without deleting app data. Delete App and Data asks for confirmation and removes that personal instance; other copies are separate. Relay Lab does not add these apps to the Android home screen."),
    HelpEntry("Where can I find files from a conversation?",
        "Open Media in that chat, group or channel to browse its attachments, including photos, videos, audio, documents, contact cards and apps. Media is specific to that conversation. Use the save star on an item to add or remove its saved copy; the top-bar star opens all Saved items for the profile."),
    HelpEntry("How do I reply, react, edit or delete a message?",
        "Open a message’s options to see the actions available for it, such as Reply, Forward, Edit, Delete, Pin and Message Info. Reactions are available from the reaction control. Available actions depend on the message and conversation. Read deletion confirmations carefully: removing something locally is different from requesting deletion for everyone, and recipients may already have kept copies."),
    HelpEntry("How are Archive, Trash, mute and blocking different?",
        "Archive puts a conversation aside; an incoming message can return an unmuted conversation to Inbox. Muted archived conversations stay archived. Mute controls alerts and does not block delivery. Trash is separate from permanent deletion; use the available restore or delete actions there. Blocking restricts incoming contact; review blocked entries in Settings → Block List. Leaving a group or unfollowing a channel is a separate action in its details."),
    HelpEntry("What do unread indicators mean?",
        "Dots and unread counts show where new activity needs attention. Check Chats and Groups for unread messages, Channels for unread posts, and Talk for missed calls or unread voice messages. Muting alerts does not mean a conversation has been read."),
    HelpEntry("How do disappearing messages differ from storage cleanup?",
        "Set Disappearing Messages in the conversation’s options to use its shared message timer. Settings → Storage instead controls how long messages remain on this device. Local cleanup does not remove other people’s copies and excludes Saved and personal apps. Neither feature prevents someone from taking screenshots or saving a separate copy."),
    HelpEntry("What does Privacy control?",
        "Settings → Privacy contains Read Receipts for the current profile and shortcuts to notification privacy, the block list, storage and profiles. Notification previews control what alerts reveal on screen. Disappearing messages are configured per conversation. Turning off read receipts does not block messages or hide all network activity."),
    HelpEntry("What does encryption protect, and what does it not hide?",
        "Chatmail conversations use end-to-end encryption to protect message content in transit between participants. Delivery services still handle routing information. Recipients can save or share what they receive, and someone with access to an unlocked device can read what is on it. Encryption is not a guarantee of anonymity. Treat exported backups and second-device setup codes as sensitive."),
    HelpEntry("Can mini-apps access the internet?",
        "Webxdc mini-apps run in an isolated environment without general internet access. Shared apps exchange supported updates through their conversation. Opening an external link or website is different: the website can receive connection information. Do not assume that browsing a website has the same privacy properties as an encrypted message."),
    HelpEntry("Why is an attachment too large or taking a long time?",
        "Normal attachments use your compression settings where supported. Attach Original File preserves the selected file and can be much larger. The local file-selection ceiling is 1 GiB, but delivery services may impose smaller limits; original sending does not override them. Try the normal attachment option, a shorter clip or a smaller file. Connection speed and the recipient’s download state also affect delivery time."),
    HelpEntry("What should I do if a message, call or app does not work?",
        "Check your connection and the current profile in Settings → Connections. Inspect Message Info for a failed message and check Android permissions for calls or recording. For a mini-app, copy its diagnostics if available. Include your Relay Lab version, Android version and what you were doing when reporting a problem. Review logs before sharing them because they may contain personal details."),
    HelpEntry("Will deleting a profile erase it everywhere?",
        "No. Deleting a profile removes its local data from this device after confirmation. Other profiles, other devices, recipients’ copies and exported backups are separate. Export an important profile before deleting it, and keep the backup somewhere safe. A second-device transfer code grants access to the profile; share it only with a device you control."),
    HelpEntry("How do I find updates or report a problem?",
        "Open Settings → Updates and tap Check now. Check daily and Include release candidates are enabled by default; keep release candidates enabled to get preview builds. For troubleshooting, open Settings → App log and copy the relevant error to share with support.")
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
internal fun CompressionSettingsContent(model: P2pModel) {
    val scope = rememberCoroutineScope()
    var attempt by remember(model) { mutableIntStateOf(0) }
    var busy by remember(model) { mutableStateOf(false) }
    var error by remember(model) { mutableStateOf<String?>(null) }
    val quality by produceState<Result<OutgoingMediaQuality>?>(null, model, attempt) {
        value = runCatching { model.outgoingMediaQuality() }
    }
    Text("Outgoing media quality", style = MaterialTheme.typography.titleMedium)
    Text("Applies to this profile.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    val selected = quality?.getOrNull()
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutgoingMediaQuality.entries.forEach { choice ->
            val balanced = choice == OutgoingMediaQuality.Balanced
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Row(
                    Modifier.fillMaxWidth().selectable(selected == choice,
                        enabled = selected != null && !busy, role = Role.RadioButton, onClick = {
                            if (choice != selected) {
                                busy = true
                                scope.launch {
                                    try {
                                        error = chatAction { model.setOutgoingMediaQuality(choice) }
                                        if (error == null) attempt++
                                    } finally { busy = false }
                                }
                            }
                        }).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(selected = selected == choice, onClick = null, enabled = selected != null && !busy)
                    Column(Modifier.weight(1f)) {
                        Text(if (balanced) "Balanced" else "Data saver",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (balanced) "Compress media for everyday sharing."
                            else "Use less data with stronger media compression.")
                    }
                }
            }
        }
    }
    if (quality == null || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (quality?.isFailure == true) {
        Text("Connect this profile to load its media quality setting.")
        P2pTextButton(onClick = { attempt++ }) { Text("Retry") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("Attach File uses these settings for supported media. Documents and app packages are not recompressed. Choose Attach Original File in the attachment menu to bypass media compression for an individual selection.",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        BackgroundMessages.start(context, catchUp = true)
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
    P2pRow("Android Notification Settings", "Enable alerts and choose which categories can appear.", click = systemSettings)
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
    SettingsSwitch("Reliable background connection", "Keep a connection active with a persistent Android notification.",
        reliable) {
        if (!BackgroundMessages.setReliable(context, it)) {
            error = "Android couldn’t start the background connection yet."
        } else error = null
        reliable = it
    }
    P2pRow("Battery Optimization", if (batteryExempt) "Relay Lab can keep its connection active" else
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
