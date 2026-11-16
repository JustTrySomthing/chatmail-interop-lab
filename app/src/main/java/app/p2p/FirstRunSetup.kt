package app.p2p

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Job

internal fun setupPreferences(context: Context) =
    context.getSharedPreferences("first-run-setup", Context.MODE_PRIVATE)

private data class PermissionStep(val permission: String, val title: String, val detail: String, val action: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FirstRunSetup(
    name: String,
    saveName: (String, () -> Unit) -> Job,
    back: (() -> Unit)? = null,
    restoreBackup: (() -> Unit)? = null,
    restoringBackup: Boolean = false,
    receiveProfile: (() -> Unit)? = null,
    finish: () -> Unit
) {
    val context = LocalContext.current
    val alerts = remember(context) { LocalAlerts(context) }
    val permissions = remember(context) {
        buildList {
            add(PermissionStep(Manifest.permission.POST_NOTIFICATIONS, "Notifications",
                "Get alerts for chats, groups, contact requests, social activity, and files.", "Allow notifications"))
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                add(PermissionStep(Manifest.permission.CAMERA, "Camera",
                    "Scan invite QR codes and take photos in chats.", "Allow camera"))
            }
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                add(PermissionStep(Manifest.permission.RECORD_AUDIO, "Microphone",
                    "Record voice messages.", "Allow microphone"))
            }
        }
    }
    fun missing() = permissions.filter {
        if (it.permission == Manifest.permission.POST_NOTIFICATIONS) !alerts.enabled()
        else context.checkSelfPermission(it.permission) != PackageManager.PERMISSION_GRANTED
    }.map { it.permission }
    var pending by remember { mutableStateOf(missing()) }
    var attempted by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var editing by rememberSaveable { mutableStateOf(name.isBlank()) }
    var draft by rememberSaveable { mutableStateOf(name) }
    var saving by remember { mutableStateOf<Job?>(null) }
    val busy = saving != null || restoringBackup
    LaunchedEffect(name) { if (name.isNotBlank() && draft.isBlank()) editing = false }
    var background by remember { mutableStateOf(BackgroundMessages.enabled(context)) }
    var batteryExempt by remember { mutableStateOf(BackgroundMessages.batteryExempt(context)) }
    var backgroundError by rememberSaveable { mutableStateOf(false) }
    var batterySettingsError by rememberSaveable { mutableStateOf(false) }
    fun refreshBackground() {
        background = BackgroundMessages.enabled(context)
        batteryExempt = BackgroundMessages.batteryExempt(context)
        backgroundError = background && !BackgroundMessages.start(context)
    }
    val batterySettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshBackground()
    }
    LaunchedEffect(saving) {
        saving?.join()
        saving = null
    }
    LifecycleResumeEffect(Unit) {
        alerts.registerChannels()
        pending = missing()
        refreshBackground()
        onPauseOrDispose {}
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending = missing()
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pending = missing()
    }
    fun enable(permission: String) {
        val isNotification = permission == Manifest.permission.POST_NOTIFICATIONS
        val runtimeNeeded = (!isNotification || Build.VERSION.SDK_INT >= 33) &&
            context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
        if (runtimeNeeded && permission !in attempted) {
            attempted = ArrayList(attempted + permission)
            if (isNotification) context.getSharedPreferences("notification-preferences", Context.MODE_PRIVATE)
                .edit().putBoolean("asked", true).apply()
            request.launch(permission)
        } else {
            settings.launch(if (isNotification) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }

    val total = 2 + permissions.size
    val liveDelivery = background && batteryExempt && !backgroundError
    val completed = (if (name.isNotBlank()) 1 else 0) + permissions.size - pending.size + (if (liveDelivery) 1 else 0)
    if (back != null) BackHandler(onBack = back)
    Scaffold(topBar = {
        TopAppBar(title = { Text("Setup") }, navigationIcon = {
            if (back != null) IconButton(onClick = back) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (completed == total) "P2P is ready" else "Set up P2P", style = MaterialTheme.typography.headlineSmall)
                Text("$completed of $total complete", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { completed.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                SetupCard(1, "Profile", name.isNotBlank(), name.isBlank()) {
                    if (editing) {
                        OutlinedTextField(draft, { draft = it.take(60) }, label = { Text("Display name") },
                            enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { saving = saveName(draft) { editing = false } },
                            enabled = !busy && draft.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text(if (name.isBlank()) "Continue" else "Save display name")
                        }
                        if (name.isBlank() && restoreBackup != null) {
                            P2pTextButton(onClick = restoreBackup, enabled = !busy) { Text("Restore Chatmail backup") }
                            receiveProfile?.let { action ->
                                P2pTextButton(onClick = action, enabled = !busy) { Text("Add as second device") }
                            }
                            if (restoringBackup) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f))
                            P2pTextButton(onClick = { draft = name; editing = true }) { Text("Edit name") }
                        }
                    }
                }
                permissions.forEachIndexed { index, step ->
                    val allowed = step.permission !in pending
                    SetupCard(index + 2, step.title, allowed, name.isNotBlank() && pending.firstOrNull() == step.permission) {
                        if (!allowed) {
                            Text(step.detail, style = MaterialTheme.typography.bodyMedium)
                            P2pOutlinedButton(onClick = { enable(step.permission) }, modifier = Modifier.fillMaxWidth()) {
                                Text(step.action)
                            }
                        }
                    }
                }
                SetupCard(total, "Calls and messages in the background", liveDelivery,
                    name.isNotBlank() && pending.isEmpty() && !liveDelivery) {
                    if (liveDelivery) Text("P2P can stay connected when the app is closed.")
                    else Text("Allow P2P to stay connected when the app is closed. Without this permission, Android schedules message checks and calls may be missed.")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Receive in background", Modifier.weight(1f))
                        Switch(background, { enabled ->
                            backgroundError = !BackgroundMessages.setEnabled(context, enabled)
                            background = BackgroundMessages.enabled(context)
                        }, enabled = name.isNotBlank(),
                            modifier = Modifier.semantics { contentDescription = "Receive in background" })
                    }
                    if (background && !batteryExempt) {
                        P2pOutlinedButton(enabled = name.isNotBlank(), onClick = {
                            batterySettingsError = runCatching {
                                batterySettings.launch(BackgroundMessages.batteryPermissionIntent(context))
                            }.recoverCatching {
                                batterySettings.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }.isFailure
                        }, modifier = Modifier.fillMaxWidth()) { Text("Allow background connection") }
                    }
                    if (backgroundError || batterySettingsError) {
                        Text(if (batterySettingsError) "Open Android battery settings and allow unrestricted use for P2P."
                            else "Couldn’t start background delivery. Try again from Setup.",
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (name.isNotBlank() && !editing) {
                Button(onClick = finish, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                    .padding(vertical = 10.dp).heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp)) {
                    Text(if (back != null) "Done" else if (pending.isEmpty() && background) "Start chatting" else "Continue with current permissions")
                }
            }
        }
    }
}

@Composable
private fun SetupCard(number: Int, title: String, complete: Boolean, next: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = if (next) colors.secondaryContainer else colors.surface, contentColor = colors.onSurface) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(Modifier.size(34.dp), shape = CircleShape, color = if (complete) colors.primary else colors.surfaceVariant,
                    contentColor = if (complete) colors.onPrimary else colors.onSurface) {
                    Box(contentAlignment = Alignment.Center) {
                        if (complete) Icon(Icons.Outlined.Check, null, Modifier.size(19.dp))
                        else Text(number.toString(), fontWeight = FontWeight.Bold)
                    }
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(if (complete) "Complete" else if (next) "Next step" else "To do",
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
            content()
        }
    }
}
