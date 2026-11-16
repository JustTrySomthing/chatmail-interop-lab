package app.p2p

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val updates: P2pUpdateModel by viewModels()
    private val readAloud by lazy { ReadAloud(applicationContext, lifecycleScope) }
    private val model: P2pModel get() = (application as P2pApplication).model
    private val shared = MutableStateFlow<List<Uri>>(emptyList())
    private val alert = MutableStateFlow<AlertRoute?>(null)
    private val openUpdates = MutableStateFlow(false)
    private val incomingSecureJoin = MutableStateFlow<String?>(null)

    private fun acceptSecureJoinLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val invite = intent.dataString?.takeIf(::isSecureJoinInvite)
            incomingSecureJoin.value = invite
            if (invite != null) model.hintInitialChatmailInvite(invite)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        acceptShare(intent)
        acceptSecureJoinLink(intent)
        alert.value = LocalAlerts.read(intent)
        openUpdates.value = intent.action == UpdateChecks.OPEN
        setContent {
            val incoming by shared.collectAsState()
            val route by alert.collectAsState()
            val updateRequested by openUpdates.collectAsState()
            val secureJoinLink by incomingSecureJoin.collectAsState()
            val inlineAudio = remember { mutableStateOf<String?>(null) }
            CompositionLocalProvider(LocalReadAloud provides readAloud, LocalInlineAudio provides inlineAudio) {
                P2pApp(model, updates, incoming, route, { alert.value = null }, ::clearShare,
                    updateRequested, { openUpdates.value = false },
                    secureJoinLink, { incomingSecureJoin.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptShare(intent)
        acceptSecureJoinLink(intent)
        alert.value = LocalAlerts.read(intent)
        openUpdates.value = intent.action == UpdateChecks.OPEN
    }

    override fun onResume() {
        super.onResume()
        updates.setForeground(true)
        readAloud.prepare()
        BackgroundMessages.updateConnection(this)
    }

    override fun onPause() {
        updates.setForeground(false)
        super.onPause()
    }

    override fun onStart() {
        model.setForeground(true)
        super.onStart()
        BackgroundMessages.start(this)
    }

    override fun onStop() {
        readAloud.stop()
        // Android restarts the activity on rotation while the call stays in the foreground.
        if (!isChangingConfigurations) model.setForeground(false)
        super.onStop()
    }

    private fun clearShare() {
        shared.value = emptyList()
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            setIntent(Intent(intent).setAction(Intent.ACTION_MAIN).apply { removeExtra(Intent.EXTRA_STREAM) })
        }
    }

    private fun shareable(uri: Uri) = uri.scheme == "content" && uri.authority != "$packageName.files"

    private fun acceptShare(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> shared.value = listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)).filter(::shareable)
            Intent.ACTION_SEND_MULTIPLE -> shared.value = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filter(::shareable)
        }
    }
}

private val Base: Color @Composable get() = MaterialTheme.colorScheme.background
private val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
private val Edge: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
private val Mint: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val Quiet: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val AccentPanel: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer

private const val Feed = "Feed"

private enum class Area(val label: String, val icon: ImageVector) {
    Talk("Talk", Icons.Outlined.Call),
    Chats("Chats", Icons.AutoMirrored.Outlined.Chat),
    Groups("Groups", Icons.Outlined.Groups),
    Social("Channels", Icons.Outlined.Campaign)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun P2pApp(model: P2pModel, updates: P2pUpdateModel, shared: List<Uri>, alert: AlertRoute?, clearAlert: () -> Unit,
                   clearShared: () -> Unit, updateRequested: Boolean, clearUpdateRequest: () -> Unit,
                   secureJoinLink: String?, clearSecureJoinLink: () -> Unit) {
    val loadedRecords by model.items.collectAsState()
    val records = loadedRecords.orEmpty()
    val appearance = Appearance.read(records.firstOrNull { it.kind == "appearance" }?.data())
    P2pTheme(appearance) {
        val context = LocalContext.current
        val peer by model.peer.collectAsState()
        val notice by model.notice.collectAsState()
        val inviteImport by model.inviteImport.collectAsState()
        val listeners by model.listeners.collectAsState()
        val chatmailStatus by model.chatmailStatus.collectAsState()
        val submission by model.sheetSubmission.collectAsState()
        val submitting = submission?.finished == false
        val sendingDraft by model.sendingDraft.collectAsState()
        val talkCall by model.talk.state.collectAsState()
        val reader = LocalReadAloud.current
        val snack = remember { SnackbarHostState() }
        val profileData = records.firstOrNull { it.kind == "profile" }?.data()
        val profile = profileData?.optString("name").orEmpty()
        val setup = remember(context) { setupPreferences(context) }
        var setupComplete by remember { mutableStateOf(setup.getBoolean("complete", false)) }
        if (loadedRecords == null) {
            Box(Modifier.fillMaxSize().background(Base), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@P2pTheme
        }
        if (!setupComplete || profile.isBlank()) {
            val restoring by model.backupBusy.collectAsState()
            var receivingProfile by rememberSaveable { mutableStateOf(false) }
            val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) model.importChatmailProfile(uri)
            }
            Box(Modifier.fillMaxSize()) {
                FirstRunSetup(profile, model::setName, restoreBackup = { restore.launch(arrayOf("*/*")) },
                    restoringBackup = restoring, receiveProfile = { receivingProfile = true }) {
                    setup.edit().putBoolean("complete", true).apply()
                    setupComplete = true
                }
                if (receivingProfile && profile.isBlank()) {
                    ReceiveProfileDialog(model, close = { receivingProfile = false })
                }
                SnackbarHost(snack, Modifier.align(Alignment.TopCenter).statusBarsPadding())
            }
            LaunchedEffect(notice) {
                if (notice.isNotBlank()) {
                    snack.showSnackbar(notice)
                    model.clearNotice()
                }
            }
            return@P2pTheme
        }
        var area by rememberSaveable { mutableStateOf(Area.Chats) }
        var chatFolder by rememberSaveable { mutableStateOf("Inbox") }
        var groupFolder by rememberSaveable { mutableStateOf("Inbox") }
        var socialPage by rememberSaveable { mutableStateOf("My Page") }
        var legacySocial by rememberSaveable { mutableStateOf(false) }
        var channelPage by rememberSaveable { mutableStateOf("Feed") }
        var channelId by rememberSaveable { mutableStateOf<Int?>(null) }
        var sharingChannel by remember { mutableStateOf<ChatmailChannel?>(null) }
        var channelInviteOnly by rememberSaveable { mutableStateOf(false) }
        var thread by rememberSaveable { mutableStateOf<String?>(null) }
        var webxdcDraftText by rememberSaveable(thread) { mutableStateOf<String?>(null) }
        var webxdcHref by rememberSaveable { mutableStateOf("") }
        var threadView by rememberSaveable { mutableStateOf(Feed) }
        var threadQuery by rememberSaveable(thread) { mutableStateOf("") }
        var quote by rememberSaveable { mutableStateOf<String?>(null) }
        var modal by rememberSaveable { mutableStateOf<String?>(null) }
        var addContactTab by rememberSaveable { mutableStateOf("Send invite") }
        var pastedInvite by remember { mutableStateOf("") }
        var trackedInvitePeer by rememberSaveable { mutableStateOf<String?>(null) }
        fun showAddContact(pendingPeer: String? = null) {
            channelInviteOnly = false
            model.clearInviteImport()
            pastedInvite = ""
            trackedInvitePeer = pendingPeer
            addContactTab = if (pendingPeer == null) "Send invite" else "Accept invite"
            modal = "add-contact"
        }
        var selected by rememberSaveable { mutableStateOf<String?>(null) }
        var nativeActionBusy by remember { mutableStateOf(false) }
        var nativeActionError by remember(modal, selected) { mutableStateOf<String?>(null) }
        val actionScope = rememberCoroutineScope()
        fun runNativeAction(done: () -> Unit = { modal = null }, operation: suspend () -> Unit) {
            if (nativeActionBusy) return
            nativeActionBusy = true
            nativeActionError = null
            actionScope.launch {
                try {
                    val error = chatAction(operation)
                    nativeActionError = error
                    if (error == null) done()
                    else snack.showSnackbar(error)
                } finally {
                    nativeActionBusy = false
                }
            }
        }
        val exportChatmailBackup = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/x-tar")
        ) { uri ->
            if (uri != null) runNativeAction(done = {}) { model.exportChatmailProfile(uri) }
        }
        var groupPhotoTarget by rememberSaveable { mutableStateOf<String?>(null) }
        val groupPhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val target = groupPhotoTarget
            groupPhotoTarget = null
            if (uri != null && target != null) runNativeAction(done = {}) {
                model.updateNativeGroupImage(target, uri)
            }
        }
        var reminderArea by rememberSaveable { mutableStateOf<AlertArea?>(null) }
        var reminderTarget by rememberSaveable { mutableStateOf("") }
        var search by rememberSaveable { mutableStateOf("") }
        var asFile by rememberSaveable { mutableStateOf(true) }
        var pickerThread by rememberSaveable { mutableStateOf<String?>(null) }
        var captureThread by rememberSaveable { mutableStateOf<String?>(null) }
        var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
        var captureKind by rememberSaveable { mutableStateOf("photo") }
        var trimIndex by rememberSaveable { mutableIntStateOf(-1) }
        var contactQuery by rememberSaveable { mutableStateOf("") }
        var settingsQuery by rememberSaveable { mutableStateOf("") }
        var fullPageSearch by rememberSaveable { mutableStateOf(false) }
        val appMuted by model.appNotificationsMuted.collectAsState()
        var action by rememberSaveable { mutableStateOf<ThreadAction?>(null) }
        var linkUrl by rememberSaveable { mutableStateOf<String?>(null) }
        val mediaDrafts = model.attachmentDrafts
        var chosen by rememberSaveable { mutableStateOf(arrayListOf<Uri>()) }
        var postMedia by rememberSaveable { mutableStateOf(arrayListOf<Uri>()) }
        var postDraft by rememberSaveable { mutableStateOf("") }
        val postPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { result ->
            result.forEach { uri ->
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            postMedia = ArrayList((postMedia + result).distinct())
        }
        val contacts = remember(records) {
            records.filter { it.kind == "contact" && !it.data().optBoolean("nativeSelfTalk") }.sortedWith(
                compareByDescending<Record> { it.data().optBoolean("pinnedContact") }
                    .thenBy { it.data().optString("name").lowercase() })
        }
        val groups = remember(records) { records.filter { it.kind == "group" } }
        val threadPreferences = remember(records) {
            records.filter { it.kind == "thread-pref" }.associate { it.key.substringAfter("thread-pref:") to it.data() }
        }
        val threadLatest = remember(records) {
            records.filter(Record::isThreadMessage).groupBy { it.data().optString("thread") }.mapValues { it.value.first() }
        }
        val peerProfiles = remember(records) { records.filter { it.kind == "peer-profile" }
            .associate { it.key.removePrefix("peer-profile:") to it.data() } }
        fun name(id: String) = if (id == peer && id.isNotBlank()) "You" else
            peerProfiles[id]?.optString("name")?.takeIf(String::isNotBlank)
                ?: records.firstOrNull { it.key == id }?.data()?.optString("name")?.takeIf(String::isNotBlank)
                ?: "Group member"
        fun photo(id: String) = if (id == peer && id.isNotBlank()) profileData?.optString("photo").orEmpty()
            else peerProfiles[id]?.optString("photo").orEmpty().ifBlank {
                groups.firstOrNull { it.key == id }?.data()?.optString("photo").orEmpty()
            }
        val profilePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) model.setProfilePhoto(uri)
        }
        val backupBusy by model.backupBusy.collectAsState()
        var recoveryUri by rememberSaveable { mutableStateOf<Uri?>(null) }
        var download by rememberSaveable { mutableStateOf<MediaDownload?>(null) }
        var pickingLocation by rememberSaveable { mutableStateOf(false) }
        var downloadJob by remember { mutableStateOf<Job?>(null) }
        var downloadError by rememberSaveable { mutableStateOf<String?>(null) }
        var downloadFeedback by remember { mutableStateOf<String?>(null) }
        val downloadScope = rememberCoroutineScope()
        fun showDownload(request: MediaDownload) {
            downloadError = null
            downloadFeedback = null
            download = request
        }
        fun runDownload(action: suspend () -> Unit) {
            if (downloadJob?.isActive == true) return
            downloadError = null
            downloadJob = downloadScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    pickingLocation = false
                    downloadError = failure.message ?: "Couldn’t save this file. Try again."
                } finally {
                    if (downloadJob == coroutineContext[Job]) downloadJob = null
                }
            }
            downloadJob?.start()
        }
        fun saveDownload(request: MediaDownload, destination: Uri?) = runDownload {
            model.saveMedia(request, destination)
            download = null
            val message = if (destination == null) "Saved to Downloads" else "File saved"
            if (modal == "media") downloadFeedback = message
            else android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
        val chooseDownload = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pickingLocation = false
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val destination = result.data?.data
                if (destination == null) downloadError = "Android didn’t return a save location. Try again."
                else download?.let { saveDownload(it, destination) }
            }
        }
        val localAlerts = remember(context) { LocalAlerts(context) }
        var notificationsEnabled by remember { mutableStateOf(localAlerts.enabled()) }
        androidx.lifecycle.compose.LifecycleResumeEffect(localAlerts) {
            notificationsEnabled = localAlerts.enabled()
            onPauseOrDispose {}
        }
        LaunchedEffect(modal) {
            if (modal == "settings") notificationsEnabled = localAlerts.enabled()
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsEnabled = localAlerts.enabled()
        }
        val notificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            notificationsEnabled = localAlerts.enabled()
        }
        fun configureNotifications() {
            localAlerts.registerChannels()
            val permissionHistory = context.getSharedPreferences("notification-preferences", android.content.Context.MODE_PRIVATE)
            if (Build.VERSION.SDK_INT >= 33 && !permissionHistory.getBoolean("asked", false) &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionHistory.edit().putBoolean("asked", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }
        val saveRecovery = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { result ->
            model.finishBackupPicker(result)
        }
        val openRecovery = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            if (result != null) {
                recoveryUri = result
                modal = "restore-password"
            }
        }
        val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            modal = if (granted) "scan-invite" else "camera-unavailable"
        }
        fun openScanner() {
            if (model.inviteImport.value.busy) return
            model.clearInviteImport()
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                modal = "camera-unavailable"
            } else if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                modal = "scan-invite"
            } else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        val inviteImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
            if (result != null) {
                modal = "add-contact"
                addContactTab = "Accept invite"
                model.importInviteImage(result, channelInviteOnly)
            }
        }
        fun addDraft(id: String, attachments: List<AttachmentDraft>) {
            mediaDrafts[id] = (mediaDrafts[id].orEmpty() + attachments).distinctBy { it.uri }
        }
        fun removeDraft(id: String, index: Int) {
            val items = mediaDrafts[id].orEmpty()
            val attachment = items.getOrNull(index) ?: return
            if (attachment.temporary && attachment.uri.scheme == "file") {
                attachment.uri.path?.let { path ->
                    val file = File(path)
                    if (file.parentFile == File(context.cacheDir, "captures")) file.delete()
                }
            }
            mediaDrafts[id] = items.filterIndexed { i, _ -> i != index }
        }
        fun finishCapture(success: Boolean) {
            val destination = captureThread
            val path = capturePath
            capturePath = null
            captureThread = null
            if (path == null || destination == null) return
            val file = File(path)
            if (!success || file.length() == 0L) {
                file.delete()
                return
            }
            val video = captureKind == "video"
            addDraft(destination, listOf(AttachmentDraft(Uri.fromFile(file),
                if (video) "Recorded video.mp4" else "Camera photo.jpg",
                if (video) "video/mp4" else "image/jpeg", false, temporary = true)))
        }
        val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), ::finishCapture)
        val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo(), ::finishCapture)
        fun launchCapture() {
            if (captureThread == null) return
            val folder = File(context.cacheDir, "captures").apply { mkdirs() }
            val video = captureKind == "video"
            val file = File.createTempFile("p2p-camera-", if (video) ".mp4" else ".jpg", folder)
            capturePath = file.absolutePath
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            runCatching { if (video) takeVideo.launch(uri) else takePhoto.launch(uri) }
                .onFailure { finishCapture(false); modal = "capture-unavailable" }
        }
        val capturePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCapture() else { captureThread = null; modal = "capture-unavailable" }
        }
        val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            modal = if (granted) "record-audio" else "capture-unavailable"
        }
        fun capture(kind: String) {
            captureThread = thread
            captureKind = kind
            modal = null
            if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCapture()
            else capturePermission.launch(Manifest.permission.CAMERA)
        }
        var talkMicAction by rememberSaveable { mutableStateOf<TalkMicAction?>(null) }
        var talkContact by rememberSaveable { mutableStateOf<String?>(null) }
        var voiceFile by rememberSaveable { mutableStateOf<String?>(null) }
        fun useTalkMic(action: TalkMicAction, id: String) {
            when (action) {
                TalkMicAction.Call -> model.talk.start(id)
                TalkMicAction.Answer -> model.talk.accept(id)
                TalkMicAction.Message -> {
                    model.talk.end()
                    talkContact = id
                    modal = "talk-message"
                }
            }
        }
        val talkMicPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val requested = talkMicAction
            val id = talkContact
            talkMicAction = null
            if (granted && requested != null && id != null) useTalkMic(requested, id)
            else modal = "talk-mic-required"
        }
        fun requestTalkMic(action: TalkMicAction, id: String) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                useTalkMic(action, id)
            } else {
                talkMicAction = action
                talkContact = id
                talkMicPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        val callCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && model.talk.state.value?.camera == false) model.talk.toggleCamera()
        }
        fun toggleCallCamera() {
            if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                model.talk.toggleCamera()
            } else callCameraPermission.launch(Manifest.permission.CAMERA)
        }
        val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { result ->
            val target = pickerThread
            pickerThread = null
            if (result.isNotEmpty()) {
                if (target != null) {
                    result.forEach { uri ->
                        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    }
                    addDraft(target, result.map { describeAttachment(context, it, asFile) })
                    modal = null
                }
            }
        }
        fun chooseAttachment(raw: Boolean, vararg formats: String) {
            asFile = raw
            pickerThread = thread
            filePicker.launch(arrayOf(*formats))
        }
        fun blocked(id: String): Boolean =
            contacts.firstOrNull { it.key == id }?.data()?.optBoolean("blocked") == true ||
                threadPreferences[id]?.optBoolean("blocked") == true
        fun showAction(id: String, choice: ThreadAction) {
            selected = id
            action = choice
            modal = "confirm-thread-action"
        }
        fun showItemActions(id: String) {
            selected = id
            modal = "item-actions"
        }
        fun showReplyReminder(reminder: ReplyReminder) {
            selected = reminder.item
            reminderArea = reminder.area
            reminderTarget = reminder.target
            modal = "reply-reminder"
        }
        fun copyItemText(text: String) {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("P2P text", text))
            modal = null
        }
        fun showLink(link: PreviewLink) {
            linkUrl = link.url
            modal = "link-preview"
        }
        fun openLink(link: PreviewLink) {
            if (isSecureJoinInvite(link.url)) {
                if (inviteImport.busy) return
                model.clearInviteImport()
                trackedInvitePeer = null
                channelInviteOnly = false
                addContactTab = "Accept invite"
                pastedInvite = link.url
                modal = "add-contact"
            } else {
                linkUrl = link.url
                modal = "browser"
            }
        }
        fun goThread(id: String) {
            thread = id
            quote = null
            threadView = Feed
            modal = null
        }
        fun goArea(value: Area) {
            chatFolder = "Inbox"
            groupFolder = "Inbox"
            socialPage = "My Page"
            legacySocial = false
            channelPage = "Feed"
            channelId = null
            if (value == Area.Talk) model.markCallHistoryRead()
            area = value
            thread = null
            quote = null
            threadView = Feed
            search = ""
            modal = null
        }
        fun messageContact(id: String) {
            goArea(Area.Chats)
            goThread(id)
        }
        fun callContact(id: String) {
            if (blocked(id)) return
            modal = null
            requestTalkMic(TalkMicAction.Call, id)
        }
        fun openFile(record: Record) {
            val file = model.completedFile(record.key) ?: return
            val data = record.data()
            val label = data.optString("name", "Shared file")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file, label)
            val type = data.optString("mime", "application/octet-stream")
            val openType = if (type in setOf("text/x-vcard", "text/vcard", "application/vcard")) "text/x-vcard" else type
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, openType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(view, "Open file")) }
        }
        val inlineAudio = LocalInlineAudio.current
        fun showAttachment(id: String) {
            inlineAudio?.value = null
            val record = records.firstOrNull { it.key == id } ?: return
            val data = record.data()
            selected = id
            modal = if (mediaKind(data.optString("mime"), data.optString("name")) != null &&
                model.galleryFile(record) != null) "media" else "file"
        }
        DisposableEffect(thread) {
            model.setVisibleThread(thread)
            onDispose { model.setVisibleThread(null) }
        }
        val unreadThread = remember(records, thread) {
            thread?.let { records.unreadThreadItems(it).map(Record::key) }.orEmpty()
        }
        androidx.lifecycle.compose.LifecycleStartEffect(thread, unreadThread) {
            if (unreadThread.isNotEmpty()) thread?.let { model.markRead(it) }
            onStopOrDispose {}
        }
        LaunchedEffect(alert, records) {
            val destination = alert ?: return@LaunchedEffect
            if (destination.target.isNotEmpty() && records.isEmpty()) return@LaunchedEffect
            when (destination.area) {
                AlertArea.CHATS, AlertArea.GROUPS -> {
                    goArea(if (destination.area == AlertArea.GROUPS) Area.Groups else Area.Chats)
                    if (records.any { it.key == destination.target && it.kind in listOf("contact", "group") }) {
                        goThread(destination.target)
                        quote = records.firstOrNull {
                            it.key == destination.replyTo && it.kind == "message" && it.isThreadMessage() &&
                                it.data().optString("thread") == destination.target
                        }?.key
                    }
                }
                AlertArea.CONTACTS -> modal = "contacts"
                AlertArea.SOCIAL -> {
                    goArea(Area.Social)
                    legacySocial = true
                    val post = records.firstOrNull { it.key == destination.target && it.kind == "post" }
                    socialPage = if (post == null) "Friends" else if (post.data().optString("author") == peer) "My Page" else "Feed"
                    if (post != null && post.key == destination.replyTo && !post.data().optBoolean("locallyDeleted")) {
                        selected = post.key
                        modal = "reply"
                    }
                }
                AlertArea.FILES -> {
                    val file = records.firstOrNull { it.key == destination.target && it.kind == "file-received" }
                    val id = file?.data()?.optString("thread").orEmpty()
                    val parent = records.firstOrNull { it.key == id }
                    goArea(if (parent?.kind == "group") Area.Groups else Area.Chats)
                    if (parent?.kind in setOf("contact", "group")) goThread(id)
                    if (file != null) showAttachment(file.key)
                }
                AlertArea.TALK -> {
                    goArea(Area.Talk)
                    if (destination.answer && model.talk.state.value?.let {
                            it.id == destination.target && it.phase == TalkPhase.Incoming
                        } == true) requestTalkMic(TalkMicAction.Answer, destination.target)
                }
                AlertArea.MISSED_CALLS -> {
                    goArea(Area.Talk)
                    modal = "voicemail"
                }
            }
            clearAlert()
        }
        LaunchedEffect(notice) {
            if (notice.isNotBlank()) {
                snack.showSnackbar(notice)
                model.clearNotice()
            }
        }
        LaunchedEffect(submission) {
            val result = submission?.takeIf { it.finished && it.error == null } ?: return@LaunchedEffect
            if (result.sheet == "post") {
                postDraft = ""
                postMedia = arrayListOf()
                if (modal == "post") modal = null
            } else if (result.sheet == "group-new" || result.sheet == "group-edit") {
                if (modal == result.sheet) result.recipient?.let(::goThread)
            } else {
                chosen = arrayListOf()
                if (modal == "send" && result.recipient != null) {
                    goArea(if (groups.any { it.key == result.recipient }) Area.Groups else Area.Chats)
                    goThread(result.recipient)
                }
            }
            model.clearSubmission(result)
        }
        LaunchedEffect(shared, submission) {
            val pendingShare = submission?.takeIf { it.sheet == "send" }
            if (shared.isNotEmpty() && (pendingShare == null || pendingShare.error != null)) {
                pendingShare?.let(model::clearSubmission)
                chosen = ArrayList(shared)
                goArea(Area.Chats)
                modal = "send"
                clearShared()
            }
        }
        LaunchedEffect(updateRequested) {
            if (updateRequested) {
                modal = "updates"
                updates.dismissPrompt()
                clearUpdateRequest()
            }
        }
        LaunchedEffect(secureJoinLink, inviteImport.busy) {
            if (secureJoinLink != null && !inviteImport.busy) {
                model.clearInviteImport()
                trackedInvitePeer = null
                channelInviteOnly = false
                addContactTab = "Accept invite"
                modal = "add-contact"
                pastedInvite = secureJoinLink
                clearSecureJoinLink()
            }
        }
        LaunchedEffect(inviteImport, modal) {
            inviteImport.joinedChannel?.let { joined ->
                goArea(Area.Social)
                channelPage = "Following"
                channelId = joined
                channelInviteOnly = false
                model.clearInviteImport()
                return@LaunchedEffect
            }
            if (inviteImport.peer != null && !inviteImport.failed) {
                pastedInvite = ""
                trackedInvitePeer = inviteImport.peer
            }
            if (modal == "scan-invite" && !inviteImport.busy && !inviteImport.failed &&
                (inviteImport.message != null || inviteImport.peer != null)) modal = "add-contact"
        }
        if (modal == "setup") {
            Box(Modifier.fillMaxSize()) {
                FirstRunSetup(profile, model::setName, back = { modal = "settings" }) { modal = "settings" }
                SnackbarHost(snack, Modifier.align(Alignment.TopCenter).statusBarsPadding())
            }
            return@P2pTheme
        }
        LaunchedEffect(area, thread, socialPage, channelId, channelPage, legacySocial, modal == "text-to-speech") {
            reader?.stop()
        }
        val liveCall = talkCall?.phase?.let { it != TalkPhase.Finished } == true
        LaunchedEffect(liveCall) {
            if (liveCall) reader?.stop()
        }
        val speechError = reader?.error
        LaunchedEffect(speechError, modal == "text-to-speech") {
            if (speechError != null && modal != "text-to-speech") {
                snack.showSnackbar(speechError)
                reader?.clearError(speechError)
            }
        }
        val showTalkCall = talkCall != null && modal !in setOf("talk-message", "talk-mic-required") &&
            (talkCall?.phase != TalkPhase.Finished || modal == null)
        if (!appMuted && modal == null && download == null && !showTalkCall) UpdateNotice(updates) { modal = "updates" }
        val mainPageState = rememberSaveableStateHolder()
        val fullPage = if (showTalkCall) null else FullPageRoute.entries.firstOrNull { it.route == modal }
        fun openSettings() {
            settingsQuery = ""
            modal = "settings"
        }
        fun closeFullPage() {
            if (fullPage == FullPageRoute.Updates) updates.dismissPrompt()
            modal = if (fullPage in setOf(FullPageRoute.Settings, FullPageRoute.Contacts)) null else "settings"
        }
        BackHandler(thread != null || modal != null || (area == Area.Social && (legacySocial || channelId != null))) {
            when {
                fullPage != null -> closeFullPage()
                modal != null -> modal = null
                area == Area.Social && legacySocial -> legacySocial = false
                area == Area.Social && channelId != null -> channelId = null
                threadView != Feed -> threadView = Feed
                else -> thread = null
            }
        }
        CompositionLocalProvider(LocalMediaDownload provides ::showDownload) {
        Box(Modifier.fillMaxSize().background(Panel)
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))) {
        AppBackground(Modifier.matchParentSize())
        if (fullPage != null) {
            val searchingContacts = fullPage == FullPageRoute.Contacts
            val searchLabel = if (searchingContacts) "Search contacts" else "Search settings"
            FullPageScreen(fullPage, ::closeFullPage, actions = {
                AppToolbarActions(appMuted, model::setAppNotificationsMuted, searchLabel,
                    { fullPageSearch = true }, { modal = "contacts" }, ::openSettings)
            }) {
                when (fullPage) {
                    FullPageRoute.Contacts -> {
                        PageActionButton("Add contact", { showAddContact() }, Modifier.fillMaxWidth(),
                            icon = Icons.Outlined.PersonAdd)
                        IncomingContactRequests(records, { model.acceptInvite(it) }, { model.declineInvite(it) })
                        SearchField(contactQuery, { contactQuery = it }, "Search contacts")
                        val pending = records.filter { it.kind == "invite-pending" }
                        if (contactQuery.isBlank() && pending.isNotEmpty()) {
                            val clock by produceState(System.currentTimeMillis()) {
                                while (true) {
                                    kotlinx.coroutines.delay(15_000)
                                    value = System.currentTimeMillis()
                                }
                            }
                            pending.forEach {
                                val data = it.data()
                                P2pRow(data.optString("name").ifBlank { "New contact" },
                                    inviteRequestStatus(data, clock), Icons.Outlined.PersonAdd) {
                                    showAddContact(it.key.removePrefix("request:"))
                                }
                            }
                        }
                        val matchingContacts = contacts.filter {
                            !it.data().has("nativeContact") && threadSearchMatches(contactQuery, name(it.key), emptyList())
                        }
                        NativeContactRows(model, contactQuery, showEmpty = matchingContacts.isEmpty()) { id ->
                            if (area == Area.Talk) {
                                modal = null
                                requestTalkMic(TalkMicAction.Call, "chatmail:$id")
                            } else messageContact(nativeThreadKey(id))
                        }
                        val contactAction = if (area == Area.Talk) ContactAction.Call else ContactAction.Message
                        matchingContacts.forEach { contact ->
                            val id = contact.key
                            val openOptions = { selected = id; modal = "contact-actions" }
                            ContactActionRow(
                                title = name(id), photo = photo(id), action = contactAction,
                                blocked = blocked(id), pinned = contact.data().optBoolean("pinnedContact"),
                                onOptions = openOptions,
                                onAction = { if (contactAction == ContactAction.Call) callContact(id) else messageContact(id) }
                            )
                        }
                    }

                    FullPageRoute.Settings -> {
                        SettingsGrid(settingsQuery) { destination ->
                            modal = destination
                            if (destination == "updates") updates.checkNow()
                        }
                    }
                    FullPageRoute.Updates -> {
                        UpdateSettingsContent(updates)
                    }
                    FullPageRoute.Notifications -> {
                        Text(when {
                            appMuted -> "All app alerts are muted"
                            notificationsEnabled -> "Notifications are enabled"
                            else -> "Notifications are disabled"
                        })
                        PageActionButton("Notification settings", { configureNotifications() }, Modifier.fillMaxWidth())
                    }
                    FullPageRoute.Address -> {
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(peer) }
                        Button(shape = P2pButtonShape, onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("P2P address", peer))
                        }) { Text("Copy address") }
                    }
                    FullPageRoute.Backup -> {
                        P2pRow("Add second device", "Show a QR code for your other device", Icons.Outlined.QrCode,
                            enabled = !backupBusy) { modal = "send-profile" }
                        Text("Chatmail backup", style = MaterialTheme.typography.titleMedium)
                        Text("Export your Chatmail profile, conversations and media. The file contains private keys and messages. Store it somewhere private.")
                        Button(onClick = { exportChatmailBackup.launch("Relay-Lab-Chatmail-backup.tar") },
                            enabled = !nativeActionBusy && !backupBusy, shape = P2pButtonShape,
                            modifier = Modifier.fillMaxWidth()) { Text("Export Chatmail backup") }
                        if (nativeActionBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("To restore on a fresh install, choose Restore Chatmail backup during setup.",
                            style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("Earlier P2P backup", style = MaterialTheme.typography.titleMedium)
                        Text("Save your earlier P2P address, contacts, chats, and attachments. This backup excludes Chatmail conversations and channels.")
                        Button(shape = P2pButtonShape, onClick = { modal = "save-password" }, enabled = !backupBusy,
                            modifier = Modifier.fillMaxWidth()) { Text("Create backup") }
                        Button(shape = P2pButtonShape, onClick = { openRecovery.launch(arrayOf("*/*")) }, enabled = !backupBusy,
                            modifier = Modifier.fillMaxWidth()) { Text("Restore backup") }
                        Text(if (backupBusy) "Working on your backup…"
                            else "Keep the file and password somewhere you can access from a new phone.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    FullPageRoute.AppLog -> {
                        var log by remember { mutableStateOf(AppLog.read(context)) }
                        Text("App activity and errors")
                        Button(shape = P2pButtonShape, onClick = { context.getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("App log", log)) }) { Text("Copy log") }
                        Button(shape = P2pButtonShape, onClick = { AppLog.clear(context); log = AppLog.read(context) }) { Text("Clear log") }
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(log, style = MaterialTheme.typography.bodySmall) }
                    }
                    FullPageRoute.ReadAloud -> {
                        reader?.let { ReadAloudSettings(it) }
                    }
                    FullPageRoute.QuietHours -> {
                        val schedule = remember { QuietHours.read(context) }
                        QuietHoursControls(schedule, cancel = { modal = "settings" }) {
                            it.save(context)
                            modal = "settings"
                        }
                    }
                    FullPageRoute.Connections -> {
                        Text(chatmailStatus)
                        if (listeners.isEmpty()) Text("Nearby devices have no listening address")
                        else listeners.sortedBy { !it.startsWith("/ip4/") }.forEach { address ->
                            val parts = address.trim('/').split('/')
                            if (parts.size >= 4) Text("${parts[1]} · ${parts[2].uppercase(Locale.ROOT)} ${parts[3]}")
                        }
                    }
                    FullPageRoute.BlockList -> {
                        val items = (contacts + groups).filter { blocked(it.key) }
                        if (items.isEmpty()) EmptyTabMessage("No blocked contacts or groups")
                        items.forEach { entry ->
                            P2pRow(name(entry.key), if (entry.kind == "group") "Blocked group" else "Blocked contact",
                                trailing = { P2pTextButton(onClick = { model.setBlocked(entry.key, false) }) { Text("Unblock") } }) {}
                        }
                    }
                    FullPageRoute.Appearance -> {
                        AppearanceControls(appearance, model::setAppearance)
                    }
                    FullPageRoute.Profile -> {
                        val image = profileData?.optString("photo").orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(profile, photo = image, size = 64.dp, save = ::showDownload)
                            Column {
                                P2pTextButton(onClick = { profilePhotoPicker.launch("image/*") }) { Text("Choose profile photo") }
                                if (image.isNotEmpty()) P2pTextButton(onClick = { model.setProfilePhoto(null) }) { Text("Remove photo") }
                            }
                        }
                        P2pRow("Display name", profile, Icons.Outlined.Badge) { modal = "display-name" }
                        P2pRow("Bio", profileData?.optString("bio").orEmpty(), Icons.Outlined.Edit) { modal = "profile-bio" }
                        P2pRow("Add Chatmail relay", "Use a relay or login QR link", Icons.Outlined.Link) { modal = "chatmail-relay" }
                    }
                }
            }
            if (fullPageSearch) ConversationSearchDialog(
                if (searchingContacts) contactQuery else settingsQuery,
                dismiss = { fullPageSearch = false }, title = searchLabel
            ) { query ->
                fullPageSearch = false
                if (searchingContacts) contactQuery = query
                else {
                    settingsQuery = query
                    modal = "settings"
                }
            }
        } else mainPageState.SaveableStateProvider("main") {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                val id = thread
                val group = groups.firstOrNull { it.key == id }
                TopAppBar(
                    title = {
                        if (id == null) Text(area.label, fontWeight = FontWeight.SemiBold, fontSize = 25.sp)
                        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val title = name(id)
                            val hasGroupInfo = group?.data()?.optInt("nativeChat")?.let { it > 9 } == true
                            val hasChatOptions = (records.firstOrNull { it.key == id }?.data()?.optInt("nativeChat") ?: 0) > 9
                            val groupInfo = when {
                                hasGroupInfo -> Modifier.clickable(onClickLabel = "View group info") { modal = "group-info" }
                                hasChatOptions -> Modifier.clickable(onClickLabel = "Chat options") {
                                    selected = id
                                    modal = "native-conversation-options"
                                }
                                else -> Modifier
                            }
                            Column(Modifier.weight(1f).then(groupInfo)) {
                                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                if (group != null) Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (hasGroupInfo) Icon(Icons.Outlined.Info, null, Modifier.size(14.dp), tint = Quiet)
                                    Text(group.data().strings("members").size.toString() + " members",
                                        color = Quiet, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Base),
                    navigationIcon = {
                        if (id != null) IconButton(onClick = {
                            if (threadView != Feed) threadView = Feed else thread = null
                        }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    },
                    actions = {
                        if (group?.data()?.let { it.has("nativeChat") && it.optBoolean("nativeCanSend") &&
                                !it.optBoolean("contactRequest") } == true) {
                            IconButton(onClick = { modal = "group-invite" }) {
                                Icon(Icons.Outlined.Link, "Invite to group")
                            }
                        }
                        AppToolbarActions(appMuted, model::setAppNotificationsMuted,
                            if (id == null) "Search" else "Search conversation",
                            { modal = if (id == null) "area-search" else "thread-search" },
                            { modal = "contacts" }, ::openSettings)
                    }
                )
            },
            bottomBar = {
                if (thread == null) Surface(color = Panel) {
                    NavigationBar(
                        modifier = Modifier.fillMaxWidth().wrapContentWidth().widthIn(max = 760.dp)
                            .fillMaxWidth().padding(horizontal = 8.dp),
                        containerColor = Panel, tonalElevation = 0.dp
                    ) {
                        Area.entries.forEach { value ->
                            NavigationBarItem(
                                selected = area == value,
                                onClick = { goArea(value) },
                                icon = {
                                    val unread = records.any { record ->
                                        val data = record.data()
                                        if (data.optBoolean("locallyDeleted") || !data.optBoolean("unread")) false
                                        else when (value) {
                                            Area.Talk -> (record.kind == "file-received" && data.optBoolean("voicemail")) || record.kind == "call-history"
                                            Area.Chats -> record.kind in setOf("message", "file-received") && !data.optBoolean("voicemail") && groups.none { it.key == data.optString("thread") }
                                            Area.Groups -> record.kind in setOf("message", "file-received") && groups.any { it.key == data.optString("thread") }
                                            Area.Social -> record.kind in setOf("post", "social-action")
                                        }
                                    }
                                    Box { Icon(value.icon, null); if (unread) Badge(Modifier.align(Alignment.TopEnd).offset(x = 16.dp, y = (-6).dp)) }
                                },
                                label = {
                                    FittedLabel(value.label, fontWeight = if (area == value) FontWeight.SemiBold else FontWeight.Normal,
                                        size = 12, modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp))
                                },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = AccentPanel, selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    selectedTextColor = readableAccent(Panel, MaterialTheme.colorScheme.onSurface), unselectedIconColor = Quiet, unselectedTextColor = Quiet)
                            )
                        }
                    }
                } else SystemNavigationBar()
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .wrapContentWidth().widthIn(max = 760.dp).fillMaxWidth()) {
                if (submitting || sendingDraft) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
                when {
                    thread != null -> {
                        val id = thread!!
                        if (threadQuery.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("Results for “" + threadQuery + "”", Modifier.weight(1f), color = Quiet,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { threadQuery = "" }) {
                                    Icon(Icons.Outlined.Close, "Clear conversation search", tint = readableAccent(Panel, Ink))
                                }
                            }
                        }
                        val conversation = records.firstOrNull { it.key == id }?.data()
                        if (conversation?.optBoolean("contactRequest") == true) {
                            val chat = conversation.getInt("nativeChat")
                            Button(onClick = { model.acceptNativeThread(id, chat) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                Text("Accept chat")
                            }
                        }
                        ThreadView(
                            id = id, view = threadView, records = records, peer = peer,
                            find = threadQuery, searchOpen = modal == "thread-search",
                            name = ::name, photo = ::photo, onView = { threadView = it },
                            quote = records.firstOrNull { it.key == quote && it.kind == "message" },
                            clearQuote = { quote = null },
                            attachments = mediaDrafts[id].orEmpty(), preparing = submitting || sendingDraft,
                            loadOlder = if (conversation?.optBoolean("nativeHasOlder") == true)
                                ({ model.loadOlderNativeMessages(id) }) else null,
                            canCompose = !blocked(id) && (conversation?.let {
                                !it.has("nativeChat") || (it.optBoolean("nativeCanSend") && !it.optBoolean("contactRequest"))
                            } ?: true),
                            removeAttachment = { removeDraft(id, it) },
                            trimAttachment = { trimIndex = it; modal = "trim-attachment" },
                            send = { text, done ->
                                val drafts = mediaDrafts[id].orEmpty()
                                val reference = quote
                                model.sendDraft(id, text, reference, drafts) {
                                    val remaining = mediaDrafts[id].orEmpty().filterNot { it in drafts }
                                    if (remaining.isEmpty()) mediaDrafts.remove(id) else mediaDrafts[id] = remaining
                                    if (thread == id && quote == reference) quote = null
                                    done()
                                }
                            },
                            preview = ::showLink, openLink = ::openLink,
                            attach = { modal = "attach" },
                            messageMenu = ::showItemActions,
                            react = { selected = it; modal = "message-reactions" },
                            star = { model.star(it) },
                            galleryFile = model::galleryFile,
                            requestAttachment = { model.requestDownload(it) },
                            file = ::showAttachment,
                            openApps = if (conversation?.optInt("nativeChat")?.let { it > 9 } == true &&
                                conversation.optBoolean("nativeCanSend") && !conversation.optBoolean("contactRequest") && !blocked(id)) {
                                { inlineAudio?.value = null; modal = "webxdc-catalog" }
                            } else null,
                            openWebxdc = { message ->
                                val data = message.data()
                                val parent = data.optInt("webxdcParent")
                                val app = if (parent > 9) records.firstOrNull { it.key == nativeMessageKey(parent) }
                                    else message
                                if (app == null || app.data().optString("thread") != id ||
                                    app.data().optBoolean("locallyDeleted")) {
                                    downloadScope.launch { snack.showSnackbar("Load the original app message to open it.") }
                                } else if (app.data().optString("state") != "Downloading") {
                                    if (model.galleryFile(app) == null) model.requestDownload(app.key)
                                    else {
                                        inlineAudio?.value = null
                                        selected = app.key
                                        webxdcHref = data.optString("webxdcHref")
                                        modal = "webxdc-app"
                                    }
                                }
                            },
                            draftText = webxdcDraftText,
                            consumeDraftText = { webxdcDraftText = null }
                        )
                    }
                    area == Area.Chats || area == Area.Groups -> {
                        val isGroup = area == Area.Groups
                        val folder = if (isGroup) groupFolder else chatFolder
                        Segments(listOf("Inbox", "Archive", "Trash"), folder) {
                            if (isGroup) groupFolder = it else chatFolder = it
                        }
                        PageActionButton(if (isGroup) "Create a group" else "Start a chat",
                            { modal = if (isGroup) "group-new" else "chat-new" },
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
                        val searchable = if (search.isBlank()) emptyMap() else records.filter {
                            it.kind in listOf("message", "file-received", "file-sent")
                        }.groupBy { it.data().optString("thread") }
                        val threads = if (isGroup) groups else contacts + records.filter {
                            it.kind == "contact" && it.data().optBoolean("nativeSelfTalk")
                        }
                        val threadFolders = remember(records) {
                            records.filter { it.kind == "folder" }
                                .associate { it.key.removePrefix("folder:") to it.data().optString("name") }
                        }
                        val rows = threads.filter { item ->
                            val location = threadFolders[item.key] ?: "Inbox"
                            (search.isNotBlank() || location == folder) && threadSearchMatches(search, name(item.key), searchable[item.key].orEmpty().map(Record::searchText))
                        }.sortedWith(compareByDescending<Record> {
                            threadPreferences[it.key]?.optBoolean("pinned") == true || it.data().optBoolean("pinnedContact")
                        }
                            .thenByDescending { threadLatest[it.key]?.created ?: 0L })
                        if (folder == "Trash") {
                            val hasTrash = threads.any { threadFolders[it.key] == "Trash" && !blocked(it.key) }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PageActionButton("Restore all", { model.restoreTrash(isGroup) },
                                    Modifier.weight(1f), enabled = hasTrash)
                                PageActionButton("Empty trash", { modal = "empty-trash" },
                                    Modifier.weight(1f), enabled = hasTrash)
                            }
                        }
                        if (rows.isEmpty()) {
                            val noun = if (isGroup) "groups" else "messages"
                            val message = when {
                                search.isNotBlank() -> "No matching conversations"
                                folder == "Archive" -> "No archived $noun"
                                folder == "Trash" -> "No deleted $noun"
                                else -> "No $noun"
                            }
                            EmptyTabMessage(message, Modifier.weight(1f))
                        } else {
                            val now by produceState(System.currentTimeMillis()) {
                                while (true) {
                                    kotlinx.coroutines.delay(60_000L)
                                    value = System.currentTimeMillis()
                                }
                            }
                            LazyColumn(contentPadding = PaddingValues(16.dp, 5.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                items(rows, key = { it.key }) { row ->
                                    val newest = searchable[row.key]?.firstOrNull { threadSearchMatches(search, "", listOf(it.searchText())) }
                                        ?: threadLatest[row.key]
                                    val data = row.data()
                                    val preference = threadPreferences[row.key]
                                    val pinned = preference?.optBoolean("pinned") == true || data.optBoolean("pinnedContact")
                                    val members = data.strings("members").size
                                    val subtitle = if (isGroup) "$members " + if (members == 1) "member" else "members"
                                        else newest?.data()?.let { it.optString("text").ifBlank { it.optString("name") } }
                                            ?: "Start a private chat"
                                    val unread = records.count { it.kind in listOf("message", "file-received") &&
                                        it.data().optString("thread") == row.key && !it.data().optBoolean("voicemail") && !it.data().optBoolean("locallyDeleted") && it.data().optBoolean("unread") }
                                    SwipeConversation(
                                        if (folder == "Inbox") ThreadAction.ARCHIVE else ThreadAction.RESTORE,
                                        blocked(row.key), folder == "Trash", { showAction(row.key, it) }
                                    ) { closeActions ->
                                        val title = name(row.key)
                                        P2pRow(title, subtitle, photo = photo(row.key),
                                            trailing = {
                                                if (isGroup) {
                                                    val canEdit = peer.isNotBlank() && data.optString("owner") == peer
                                                    IconButton(enabled = canEdit, onClick = {
                                                        closeActions()
                                                        selected = row.key
                                                        modal = "group-edit"
                                                    }) {
                                                        Icon(Icons.Outlined.Edit, if (canEdit) "Edit " + title else "Only the group owner can edit " + title)
                                                    }
                                                } else if (!isGroup && newest != null) {
                                                    Column(horizontalAlignment = Alignment.End) {
                                                        FittedLabel(inboxTimestamp(newest.created, now), size = 12,
                                                            color = Quiet, fontWeight = FontWeight.Normal)
                                                        if (unread > 0) Badge(containerColor = Mint, contentColor = Base) { Text(unread.toString()) }
                                                    }
                                                }
                                                IconToggleButton(checked = pinned, onCheckedChange = {
                                                    closeActions()
                                                    model.setThreadPreference(row.key, "pinned", it)
                                                }) {
                                                    Icon(if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                                        if (pinned) "Unpin " + title else "Pin " + title,
                                                        tint = if (pinned) Mint else Quiet)
                                                }
                                            },
                                            detail = {
                                                if (isGroup && unread > 0) Badge(containerColor = Mint, contentColor = Base) { Text(unread.toString()) }
                                                if (preference?.optBoolean("muted") == true || data.optBoolean("alertMuted")) {
                                                    Icon(Icons.Outlined.NotificationsOff, "Muted", Modifier.size(15.dp), tint = Quiet)
                                                }
                                            },
                                            onLong = { closeActions(); showItemActions(row.key) }
                                        ) { if (!closeActions()) goThread(row.key) }
                                    }
                                }
                            }
                        }
                    }
                    area == Area.Social && !legacySocial -> {
                        ChannelBoard(model, channelPage, channelId, search,
                            selectPage = { channelPage = it; channelId = null },
                            selectChannel = { channelId = it },
                            join = {
                                model.clearInviteImport()
                                channelInviteOnly = true
                                trackedInvitePeer = null
                                pastedInvite = ""
                                addContactTab = "Accept invite"
                                modal = "add-contact"
                            },
                            share = { sharingChannel = it; modal = "channel-invite" },
                            legacy = { legacySocial = true }, preview = ::showLink, openLink = ::openLink,
                            profilePhoto = profileData?.optString("photo").orEmpty())
                    }
                    area == Area.Social -> {
                        Segments(listOf("My Page", "Friends", "Feed"), socialPage) { socialPage = it }
                        LaunchedEffect(socialPage, records) { if (socialPage != "Friends") model.markSocialRead(socialPage) }
                        if (socialPage == "Friends") {
                            val friends = contacts.filter { it.data().optBoolean("friend") || it.data().optBoolean("friendRequest") }
                            PageActionButton("Add friend", { modal = "friend-new" },
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
                            if (friends.isEmpty()) {
                                EmptyTabMessage("No friends yet", Modifier.weight(1f))
                            } else {
                                LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(friends, key = { it.key }) { contact ->
                                        val request = contact.data().optBoolean("friendRequest")
                                        P2pRow(name(contact.key), if (request) "Friend request" else "Friend",
                                            trailing = {
                                                if (request) {
                                                    P2pTextButton(onClick = { model.respondFriend(contact.key, false) }) { Text("Decline") }
                                                    Button(onClick = { model.respondFriend(contact.key, true) }) { Text("Accept") }
                                                }
                                                if (!request) {
                                                    IconButton(onClick = { model.setContactFlag(contact.key, "muted", !contact.data().optBoolean("muted")) }) {
                                                        Icon(if (contact.data().optBoolean("muted")) Icons.Outlined.NotificationsOff else Icons.Outlined.NotificationsNone, "Toggle friend notifications")
                                                    }
                                                    IconButton(onClick = { model.setContactFlag(contact.key, "friend", false) }) { Icon(Icons.Outlined.Close, "Remove friend") }
                                                }
                                            }) {}
                                    }
                                }
                            }
                        } else {
                            val posts = records.filter {
                                val author = it.data().optString("author")
                                val own = author == peer
                                val friend = contacts.any { contact -> contact.key == author && contact.data().optBoolean("friend") && !contact.data().optBoolean("blocked") }
                                it.kind == "post" && !it.data().optBoolean("locallyDeleted") &&
                                    (if (search.isNotBlank()) (own || friend) && it.data().optString("text").contains(search, true)
                                     else if (socialPage == "My Page") own else friend)
                            }
                            LazyColumn(contentPadding = PaddingValues(16.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (socialPage == "My Page") item {
                                    Surface(color = Edge, contentColor = Ink, shape = RoundedCornerShape(22.dp)) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Box(Modifier.fillMaxWidth().height(74.dp).background(Mint))
                                            Column(Modifier.padding(horizontal = 16.dp).offset(y = (-24).dp)) {
                                                Avatar(profile, photo = profileData?.optString("photo").orEmpty(), size = 66.dp)
                                                Text(profile, Modifier.padding(top = 10.dp), style = contentTextStyle(MaterialTheme.typography.headlineSmall), fontWeight = FontWeight.SemiBold)
                                                P2pTextButton(onClick = { modal = "profile" }) { Text("Edit profile") }
                                            }
                                        }
                                    }
                                }
                                if (socialPage == "My Page") item {
                                    PageActionButton("What’s on your mind?", { modal = "post" }, Modifier.fillMaxWidth())
                                }
                                if (posts.isEmpty()) item {
                                    val message = when {
                                        search.isNotBlank() -> "No matching posts"
                                        socialPage == "Feed" -> "No posts in your feed"
                                        else -> "No posts yet"
                                    }
                                    EmptyTabMessage(message,
                                        Modifier.fillParentMaxHeight(if (socialPage == "My Page") .42f else 1f))
                                } else if (socialPage == "My Page") item {
                                    Surface(shape = RoundedCornerShape(16.dp), color = Edge, contentColor = Ink) {
                                        Text("My posts", Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                items(posts, key = { it.key }) { post ->
                                    val data = post.data()
                                    val actions = records.filter { it.kind == "social-action" && it.data().optString("post") == post.key }
                                    SocialPostCard(name(data.optString("author")), shortDate(post.created), post.key,
                                        data.optString("text"), photo = photo(data.optString("author")),
                                        modifier = Modifier.combinedClickable(onClick = {}, onLongClickLabel = "Post options",
                                            onLongClick = { showItemActions(post.key) })) {
                                        if (data.optString("text").isNotBlank()) {
                                            val body = data.optString("text")
                                            MessageBodyWithPreviews(body, ::showLink, ::openLink, Edge)
                                        }
                                        records.filter { item -> item.kind != "message" && item.isThreadMessage() &&
                                            item.data().optString("post") == post.key }
                                            .forEach { attachment ->
                                                key(attachment.key) {
                                                    val media = attachment.data()
                                                    val kind = mediaKind(media.optString("mime"), media.optString("name"))
                                                    val open = { showAttachment(attachment.key) }
                                                    if (kind == null) {
                                                        FileRow(attachment, name(data.optString("author")), open)
                                                    } else {
                                                        val local = model.galleryFile(attachment)
                                                        ChatAttachment(media, local, attachment.kind == "file-received", open,
                                                            local?.let { { showDownload(MediaDownload.attachment(attachment)) } },
                                                            playbackKey = attachment.key, usePlaybackPoster = true)
                                                    }
                                                }
                                            }
                                        FlowRow {
                                            P2pTextButton(onClick = { selected = post.key; modal = "react" }) {
                                                Icon(Icons.Outlined.EmojiEmotions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("React")
                                            }
                                            P2pTextButton(onClick = { selected = post.key; modal = "reply" }) {
                                                Icon(Icons.AutoMirrored.Outlined.Reply, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reply")
                                            }
                                        }
                                        actions.asReversed().forEach { action ->
                                            val detail = action.data()
                                            if (detail.optString("type") == "reply") {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(name(detail.optString("author")), Modifier.weight(1f),
                                                        color = Quiet, style = MaterialTheme.typography.labelMedium)
                                                    ReadAloudButton(action.key, detail.optString("text"), "reply")
                                                }
                                                LinkedMessageText(detail.optString("text"), ::openLink, MaterialTheme.typography.bodyMedium)
                                            } else Text(name(detail.optString("author")) + " · " + detail.optString("text"),
                                                color = Quiet, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    area == Area.Talk -> {
                        PageActionButton("Call", { modal = "call-contacts" },
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
                        RecentTalkCalls(records.filter { it.kind == "call-history" }, ::name, ::photo,
                            onClear = { modal = "clear-recent-calls" }) { contact ->
                            requestTalkMic(TalkMicAction.Call, contact)
                        }
                    }
                }
            }
        }
        }
        }
        }
        talkCall?.let { call ->
            if (showTalkCall) {
                val leaveMessage: (() -> Unit)? = if (call.contact.startsWith("chatmail:")) null
                    else ({ requestTalkMic(TalkMicAction.Message, call.contact) })
                TalkCallPanel(call, call.displayName.ifBlank { name(call.contact) }, photo(call.contact), model.talk::end,
                    { requestTalkMic(TalkMicAction.Answer, call.id) }, model.talk::toggleSpeaker, model.talk::toggleMute,
                    ::toggleCallCamera, model.talk::switchCamera, leaveMessage)
            }
        }

        when (if (showTalkCall || fullPage != null) null else modal) {
            "call-contacts" -> Sheet("Call", { modal = null }) {
                val available = contacts.filterNot { blocked(it.key) || it.data().has("nativeContact") }
                SearchField(search, { search = it }, "Search contacts")
                NativeContactRows(model, search, showEmpty = available.isEmpty()) { chat ->
                    modal = null
                    requestTalkMic(TalkMicAction.Call, "chatmail:$chat")
                }
                val matches = available.filter { threadSearchMatches(search, name(it.key), emptyList()) }
                if (contacts.isEmpty()) {
                    PageActionButton("Add contact", { showAddContact() }, Modifier.fillMaxWidth())
                }
                matches.forEach { contact ->
                    ContactActionRow(name(contact.key), photo(contact.key), ContactAction.Call,
                        onAction = { callContact(contact.key) })
                }
            }
            "voicemail" -> Sheet("VoiceMail", { modal = null }) {
                val messages = records.filter { it.kind == "file-received" && it.data().optBoolean("voicemail") && !it.data().optBoolean("locallyDeleted") }
                if (messages.isEmpty()) EmptyTabMessage("No voicemail")
                messages.forEach { message ->
                    P2pRow(name(message.data().optString("author")), recentCallTime(message.created), trailing = {
                        IconButton(onClick = { model.markVoicemailHeard(message.key); showAttachment(message.key) }) { Icon(Icons.Outlined.PlayArrow, "Listen") }
                        IconButton(onClick = { selected = message.key; modal = "delete-item" }) { Icon(Icons.Outlined.DeleteOutline, "Delete voicemail") }
                    }) { model.markVoicemailHeard(message.key); showAttachment(message.key) }
                }
            }
            "area-search" -> ConversationSearchDialog(search, dismiss = { modal = null }) { search = it; modal = if (area == Area.Talk) "call-contacts" else null }
            "item-actions" -> {
                val item = records.firstOrNull { it.key == selected }
                if (item != null) {
                    val data = item.data()
                    val conversation = item.kind == "contact" || item.kind == "group"
                    val post = item.kind == "post"
                    val threadId = if (conversation) item.key else data.optString("thread")
                    val group = groups.any { it.key == threadId }
                    val folder = records.firstOrNull { it.key == "folder:$threadId" }?.data()?.optString("name") ?: "Inbox"
                    val activeThread = threadId.isNotBlank() && folder != "Trash" && !blocked(threadId)
                    val author = data.optString("author")
                    val ownPost = post && author == peer
                    val received = item.isIncomingThreadItem(peer)
                    val sender = contacts.firstOrNull { it.key == if (conversation) item.key else author }
                    val text = if (item.kind.startsWith("file-")) data.optString("caption") else data.optString("text")
                    val unreadItem = when {
                        conversation -> records.firstOrNull {
                            it.isIncomingThreadItem(peer) && it.data().optString("thread") == threadId
                        }
                        (received || post && !ownPost) -> item
                        else -> null
                    }
                    val actions = buildList {
                        if (text.isNotBlank() && !conversation) add(ItemAction("Copy text", Icons.Outlined.ContentCopy) {
                            copyItemText(text)
                        })
                        if (item.kind == "message" && threadId.isNotBlank()) {
                            add(ItemAction("Reply", Icons.AutoMirrored.Outlined.Reply) {
                                quote = item.key
                                threadView = Feed
                                modal = null
                            })
                            if (author == peer && canCancelLegacyDelivery(data)) {
                                add(ItemAction("Cancel queued delivery", Icons.Outlined.Cancel) {
                                    model.cancelQueuedMessage(item.key)
                                    modal = null
                                })
                            }
                        }
                        if (activeThread && data.optInt("nativeMessage") > 9 && data.optInt("webxdcParent") <= 9) {
                            add(ItemAction("Save to Saved Messages", Icons.Outlined.BookmarkBorder) {
                                model.saveNativeMessage(item.key)
                                modal = null
                            })
                            add(ItemAction(if (data.optBoolean("nativePinned")) "Unpin message" else "Pin message",
                                Icons.Outlined.PushPin) {
                                model.toggleNativeMessagePin(item.key)
                                modal = null
                            })
                            if (author == peer) {
                                if (text.isNotBlank() && !data.optBoolean("webxdc")) {
                                    add(ItemAction("Edit message", Icons.Outlined.Edit) { modal = "native-edit-message" })
                                }
                                add(ItemAction("Delete for everyone", Icons.Outlined.DeleteForever) {
                                    modal = "native-delete-message"
                                })
                            }
                        }
                        if (data.optBoolean("nativeLocation")) add(ItemAction("Open shared location", Icons.Outlined.Place) {
                            runNativeAction {
                                val (latitude, longitude) = model.nativeMessageLocation(item.key)
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")))
                            }
                        })
                        if (post && !ownPost) add(ItemAction("Reply", Icons.AutoMirrored.Outlined.Reply) { modal = "reply" })
                        if (item.kind.startsWith("file-") && mediaKind(data.optString("mime"), data.optString("name")) != null) {
                            add(ItemAction("Download media", Icons.Outlined.Download) {
                                modal = null
                                showDownload(MediaDownload.attachment(item))
                            })
                        }
                        if (post && !ownPost && sender != null && !blocked(author)) {
                            val muted = sender.data().optBoolean("muted")
                            add(ItemAction(if (muted) "Unmute notifications" else "Mute notifications",
                                if (muted) Icons.Outlined.NotificationsNone else Icons.Outlined.NotificationsOff) {
                                model.setContactFlag(author, "muted", !muted)
                                modal = null
                            })
                        } else if (!post && activeThread) {
                            val muted = threadPreferences[threadId]?.optBoolean("muted") == true ||
                                contacts.firstOrNull { it.key == threadId }?.data()?.optBoolean("alertMuted") == true
                            add(ItemAction(if (muted) "Unmute notifications" else "Mute notifications",
                                if (muted) Icons.Outlined.NotificationsNone else Icons.Outlined.NotificationsOff) {
                                model.setThreadPreference(threadId, "muted", !muted)
                                modal = null
                            })
                        }
                        if (unreadItem != null && !unreadItem.data().optBoolean("unread") && (post || activeThread)) {
                            add(ItemAction("Mark unread", Icons.Outlined.MarkChatUnread) {
                                if (post) socialPage = "Friends"
                                if (thread == threadId) {
                                    model.setVisibleThread(null)
                                    thread = null
                                    quote = null
                                }
                                model.markUnread(unreadItem.key)
                                modal = null
                            })
                        }
                        val reminder = when {
                            post && !ownPost && !blocked(author) -> ReplyReminder(AlertArea.SOCIAL, item.key, item.key)
                            (conversation || received) && activeThread && (conversation || !blocked(author)) ->
                                ReplyReminder(if (group) AlertArea.GROUPS else AlertArea.CHATS, threadId, item.key)
                            else -> null
                        }
                        if (reminder?.valid() == true) add(ItemAction("Remind me to reply", Icons.Outlined.Schedule) {
                            showReplyReminder(reminder)
                        })
                        if (conversation) {
                            if (!blocked(threadId)) {
                                val move = if (folder == "Inbox") ThreadAction.ARCHIVE else ThreadAction.RESTORE
                                add(ItemAction(move.label, if (move == ThreadAction.ARCHIVE) Icons.Outlined.Archive else Icons.Outlined.Inbox) {
                                    showAction(threadId, move)
                                })
                            }
                            if (folder != "Trash") add(ItemAction("Delete", Icons.Outlined.DeleteOutline) {
                                showAction(threadId, ThreadAction.DELETE)
                            })
                        } else add(ItemAction("Delete", Icons.Outlined.DeleteOutline) { modal = "delete-item" })
                        val blockTarget = if (conversation) item.key else sender?.key?.takeUnless { it == peer }
                        if (blockTarget != null) {
                            val isBlocked = blocked(blockTarget)
                            val label = if (conversation && group) "group" else if (conversation) "contact" else "sender"
                            add(ItemAction(if (isBlocked) "Unblock $label" else "Block $label", Icons.Outlined.Block) {
                                showAction(blockTarget, if (isBlocked) ThreadAction.UNBLOCK else ThreadAction.BLOCK)
                            })
                        }
                    }
                    val title = when {
                        conversation -> name(item.key)
                        post -> "Post options"
                        item.kind.startsWith("file-") -> "Attachment options"
                        else -> "Message options"
                    }
                    ItemActionsDialog(title, actions) { modal = null }
                }
            }
            "reply-reminder" -> {
                val reminder = reminderArea?.let { area -> selected?.let { ReplyReminder(area, reminderTarget, it) } }
                if (reminder != null) ItemActionsDialog("Remind me to reply",
                    listOf("In 1 hour" to 3_600_000L, "In 1 day" to 86_400_000L, "In 1 week" to 604_800_000L).map { (label, delay) ->
                        ItemAction(label, Icons.Outlined.Schedule) {
                            val scheduled = ReplyReminderJob.schedule(context, reminder, delay)
                            android.widget.Toast.makeText(context,
                                if (scheduled) "Reply reminder scheduled" else "Couldn’t schedule the reminder",
                                android.widget.Toast.LENGTH_SHORT).show()
                            modal = null
                        }
                    }) { modal = null }
            }
            "clear-recent-calls" -> P2pAlertDialog(
                onDismissRequest = { modal = null },
                title = { Text("Clear recent calls?") },
                text = { Text("This clears call history on this phone.") },
                dismissButton = { P2pTextButton(onClick = { modal = null }) { Text("Cancel") } },
                confirmButton = { P2pTextButton(onClick = { model.clearCallHistory(); modal = null }) { Text("Clear recent") } }
            )
            "native-conversation-options" -> selected?.let { target ->
                val chat = records.firstOrNull { it.key == target }?.data()
                val canChange = chat?.optBoolean("nativeCanSend") == true && !chat.optBoolean("contactRequest") &&
                    !chat.optBoolean("nativeSelfTalk") && !blocked(target)
                ItemActionsDialog("Chat options", buildList {
                    add(ItemAction("Pinned messages", Icons.Outlined.PushPin) { modal = "native-pinned-messages" })
                    if (canChange) add(ItemAction("Disappearing messages", Icons.Outlined.Timer) {
                        modal = "native-disappearing-messages"
                    })
                }) { modal = null }
            }
            "native-pinned-messages" -> selected?.let { target ->
                Sheet("Pinned messages", { modal = null }) {
                    val result by produceState<Result<List<NativeMessage>>?>(null, target) {
                        try { value = Result.success(model.pinnedNativeMessages(target)) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { value = Result.failure(failure) }
                    }
                    when {
                        result == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        result?.isFailure == true -> Text(result?.exceptionOrNull()?.message ?: "Couldn’t load pinned messages.")
                        result?.getOrNull().isNullOrEmpty() -> Text("No pinned messages")
                        else -> result?.getOrNull()?.forEach { message ->
                            val text = message.text.ifBlank { message.attachment?.name.orEmpty() }
                            Text(text, style = contentTextStyle(MaterialTheme.typography.bodyLarge))
                            HorizontalDivider()
                        }
                    }
                }
            }
            "native-disappearing-messages" -> selected?.let { target ->
                Sheet("Disappearing messages", { if (!nativeActionBusy) modal = null }) {
                    val choices = listOf(0 to "Off", 300 to "5 minutes", 3600 to "1 hour", 86400 to "1 day",
                        604800 to "1 week", 2419200 to "4 weeks", 31536000 to "1 year")
                    val result by produceState<Result<Int>?>(null, target) {
                        try { value = Result.success(model.nativeDisappearingTimer(target)) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { value = Result.failure(failure) }
                    }
                    val current = result?.getOrNull()
                    when {
                        current != null -> {
                            Text("This timer applies to new messages in this conversation.")
                            if (choices.none { it.first == current }) Text("Current timer: $current seconds")
                            choices.forEach { (seconds, label) ->
                                P2pRow(label, if (seconds == current) "Current setting" else "",
                                    enabled = !nativeActionBusy) {
                                    if (seconds == current) modal = null
                                    else runNativeAction { model.setNativeDisappearingTimer(target, seconds) }
                                }
                            }
                        }
                        result != null -> Text(result?.exceptionOrNull()?.message ?: "Couldn’t load the timer.")
                        else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            "native-edit-message" -> records.firstOrNull { it.key == selected }?.let { message ->
                InputDialog("Edit message", "Message", "Save", message.data().optString("text"),
                    { if (!nativeActionBusy) modal = null }, contentInput = true,
                    busy = nativeActionBusy, error = nativeActionError) { text ->
                    runNativeAction { model.editNativeMessage(message.key, text) }
                }
            }
            "native-delete-message" -> selected?.let { key ->
                P2pAlertDialog(onDismissRequest = { if (!nativeActionBusy) modal = null },
                    title = { Text("Delete for everyone?") },
                    text = { Text(nativeActionError ?: "Ask the other chat members’ apps to delete this message.") },
                    dismissButton = { P2pTextButton(enabled = !nativeActionBusy, onClick = { modal = null }) { Text("Cancel") } },
                    confirmButton = { P2pTextButton(enabled = !nativeActionBusy, onClick = {
                        runNativeAction { model.deleteNativeMessageForEveryone(key) }
                    }) { Text("Delete for everyone") } })
            }
            "delete-item" -> P2pAlertDialog(onDismissRequest = { modal = null }, title = { Text("Delete this item?") },
                text = { Text("This deletes only the copy stored on this phone.") },
                dismissButton = { P2pTextButton(onClick = { modal = null }) { Text("Cancel") } },
                confirmButton = { P2pTextButton(onClick = { selected?.let(model::deleteLocalItem); modal = null }) { Text("Delete") } })
            "talk-mic-required" -> Sheet("Microphone access", { modal = null }) {
                Text("Allow microphone access in Android settings to make calls or record voice messages.")
                Button(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"))) }
                }) { Text("Open app settings") }
            }
            "talk-message" -> {
                val id = talkContact
                val recording = voiceFile?.let(::File)?.takeIf(File::isFile)
                if (id != null) Sheet("Voice message for " + name(id), {
                    if (!sendingDraft) {
                        recording?.delete()
                        voiceFile = null
                        modal = null
                    }
                }) {
                    if (recording == null) AudioCapturePanel(limitSeconds = 60) { voiceFile = it.absolutePath }
                    else {
                        Text("Voice message ready")
                        Button(enabled = !sendingDraft, onClick = {
                            model.sendDraft(id, "", null, listOf(AttachmentDraft(Uri.fromFile(recording),
                                "Voice message.m4a", "audio/mp4", false, temporary = true)), voicemail = true) {
                                voiceFile = null
                                modal = null
                                model.talk.end()
                                goArea(Area.Talk)
                            }
                        }) { Text(if (sendingDraft) "Sending…" else "Send voice message") }
                        P2pTextButton(enabled = !sendingDraft, onClick = {
                            recording.delete()
                            voiceFile = null
                        }) { Text("Record again") }
                    }
                }
            }
            "add-contact" -> Sheet(
                if (channelInviteOnly) "Join channel" else "Add contact", { modal = null }, tall = !channelInviteOnly
            ) {
                if (!channelInviteOnly) Segments(listOf("Send invite", "Accept invite"), addContactTab, prominent = true) { tab ->
                    addContactTab = tab
                }
                if (addContactTab == "Send invite") {
                    SecureJoinCard(InviteTarget.Contact, profile, profileData?.optString("photo").orEmpty()) {
                        model.createSecureJoinInvite()
                    }
                } else {
                    val acceptedPeer = inviteImport.peer ?: trackedInvitePeer
                    val pending = records.firstOrNull { it.key == "request:" + acceptedPeer && it.kind == "invite-pending" }?.data()
                    val connected = contacts.firstOrNull {
                        it.key == acceptedPeer && pending == null && !it.data().optBoolean("blocked")
                    }
                    val clock by produceState(System.currentTimeMillis(), acceptedPeer) {
                        while (true) {
                            kotlinx.coroutines.delay(1_000)
                            value = System.currentTimeMillis()
                        }
                    }
                    val connecting = pending != null && isActiveInviteRequest(pending, clock)
                    if (connected != null) {
                        LaunchedEffect(connected.key) {
                            kotlinx.coroutines.delay(800)
                            modal = null
                            snack.showSnackbar("Invite accepted · " + name(connected.key))
                        }
                        Text("Invite accepted · " + name(connected.key), style = MaterialTheme.typography.titleMedium)
                        Button(onClick = { goArea(Area.Chats); goThread(connected.key) }) { Text("Message") }
                        P2pTextButton(onClick = {
                            model.clearInviteImport()
                            trackedInvitePeer = null
                            pastedInvite = ""
                        }) { Text("Accept another invite") }
                    } else if (inviteImport.busy || connecting) {
                        Text(if (inviteImport.busy) "Checking invite…" else inviteRequestStatus(checkNotNull(pending), clock),
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("You can return to Contacts while P2P connects.", color = Quiet)
                    } else {
                        val status = inviteImport.message ?: pending?.let { inviteRequestStatus(it, clock) }
                        status?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = if (inviteImport.failed) MaterialTheme.colorScheme.error else Quiet) }
                        inviteImport.joinedChat?.let { joined ->
                            P2pTextButton(onClick = {
                                goArea(if (inviteImport.joinedGroup) Area.Groups else Area.Chats)
                                goThread(nativeThreadKey(joined))
                                model.clearInviteImport()
                            }) { Text("Open chat") }
                        }
                        Text(if (channelInviteOnly) "Scan a channel QR code, choose a saved QR image, or paste an invite link."
                            else "Scan a Relay Lab, Delta Chat, or ArcaneChat invite, choose a saved QR image, or paste an invite link or code.")
                        Button(onClick = ::openScanner, enabled = !inviteImport.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.QrCodeScanner, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Scan QR code")
                        }
                        P2pOutlinedButton(onClick = {
                            model.clearInviteImport()
                            inviteImagePicker.launch(arrayOf("image/*"))
                        }, enabled = !inviteImport.busy, modifier = Modifier.fillMaxWidth()) { Text("Choose QR image") }
                        OutlinedTextField(pastedInvite, { pastedInvite = it.take(32_768) }, label = { Text("Invite link or code") },
                            maxLines = 3, enabled = !inviteImport.busy, modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(enabled = !inviteImport.busy, onClick = {
                                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                    val clip = clipboard.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        pastedInvite = clip.getItemAt(0).text?.toString()?.take(32_768).orEmpty()
                                        if (pastedInvite.isNotBlank()) model.importInvite(pastedInvite, channelInviteOnly)
                                    }
                                }) { Icon(Icons.Outlined.ContentPaste, "Paste invite code") }
                            })
                        Button(onClick = { model.importInvite(pastedInvite, channelInviteOnly) },
                            enabled = pastedInvite.isNotBlank() && !inviteImport.busy,
                            modifier = Modifier.fillMaxWidth()) { Text(if (channelInviteOnly) "Join channel" else "Connect") }

                    }
                }
            }
            "group-invite" -> groups.firstOrNull { it.key == thread }?.let { group ->
                Sheet("Group invite", { modal = null }) {
                    val chat = group.data().getInt("nativeChat")
                    SecureJoinCard(InviteTarget.Group, name(group.key), photo(group.key), chat) {
                        model.nativeGroupInvite(chat)
                    }
                }
            }
            "channel-invite" -> sharingChannel?.let { channel ->
                Sheet("Channel invite", { modal = null }) {
                    SecureJoinCard(InviteTarget.Channel, channel.name, "", channel.id) {
                        model.chatmailChannelInvite(channel.id)
                    }
                }
            }
            "link-preview" -> {
                val link = linkUrl?.let(::parsePreviewLink)
                if (link != null) Sheet(if (link.videoId == null) "Web preview" else "YouTube preview", { modal = null }) {
                    LinkPreviewContent(link) { openLink(link) }
                }
            }
            "browser" -> linkUrl?.let(::parsePreviewLink)?.let { link ->
                key(link.url) { InAppBrowser(link) { modal = null } }
            }
            "contact-actions" -> Sheet(selected?.let(::name) ?: "Contact", { modal = "contacts" }) {
                val id = selected
                val contact = contacts.firstOrNull { it.key == id }
                if (id != null && contact != null) {
                    val data = contact.data()
                    val pinned = data.optBoolean("pinnedContact") || threadPreferences[id]?.optBoolean("pinned") == true
                    val muted = data.optBoolean("alertMuted")
                    P2pRow(if (pinned) "Unpin contact" else "Pin contact", "", Icons.Outlined.PushPin) {
                        model.setContactFlag(id, "pinnedContact", !pinned); modal = "contacts"
                    }
                    P2pRow(if (muted) "Unmute contact" else "Mute contact", "Silence this person’s alerts", Icons.Outlined.NotificationsOff) {
                        model.setContactFlag(id, "alertMuted", !muted); modal = "contacts"
                    }
                    val choice = if (blocked(id)) ThreadAction.UNBLOCK else ThreadAction.BLOCK
                    P2pRow(choice.label, "", Icons.Outlined.Block) { showAction(id, choice) }
                }
            }
            "confirm-thread-action" -> {
                val id = selected
                val choice = action
                if (id != null && choice != null) P2pAlertDialog(
                    onDismissRequest = { modal = null },
                    title = { Text(choice.label + " " + name(id) + "?") },
                    text = { Text(when (choice) {
                        ThreadAction.DELETE -> "The conversation will move to Trash."
                        ThreadAction.BLOCK -> "New content from this conversation will be blocked."
                        ThreadAction.UNBLOCK -> "You’ll be able to receive content again."
                        ThreadAction.ARCHIVE -> "The conversation will move to Archive."
                        ThreadAction.RESTORE -> "The conversation will move to Inbox."
                    }) },
                    confirmButton = { P2pTextButton(onClick = {
                        when (choice) {
                            ThreadAction.BLOCK -> model.setBlocked(id, true)
                            ThreadAction.UNBLOCK -> model.setBlocked(id, false)
                            ThreadAction.ARCHIVE -> model.setFolder(id, "Archive")
                            ThreadAction.RESTORE -> model.setFolder(id, "Inbox")
                            ThreadAction.DELETE -> model.setFolder(id, "Trash")
                        }
                        if (thread == id && choice != ThreadAction.UNBLOCK) thread = null
                        modal = null
                    }) { Text(choice.label) } },
                    dismissButton = { P2pTextButton(onClick = { modal = null }) { Text("Cancel") } }
                )
            }
            "empty-trash" -> P2pAlertDialog(
                onDismissRequest = { modal = null },
                title = { Text("Empty trash?") },
                text = { Text("This removes trashed conversation history from this phone. Files saved in Files are kept.") },
                confirmButton = { P2pTextButton(onClick = { model.emptyTrash(area == Area.Groups); modal = null }) { Text("Empty trash") } },
                dismissButton = { P2pTextButton(onClick = { modal = null }) { Text("Cancel") } }
            )
            "send-profile" -> SendProfileDialog(model) { modal = "backup" }
            "save-password" -> InputDialog("Create encrypted backup", "Backup password (12+ characters)", "Choose where to save",
                "", { modal = "backup" }, password = true, confirmPassword = true) { value ->
                model.prepareBackupPicker(value.toCharArray())
                modal = "backup"
                saveRecovery.launch("P2P-Backup.p2pr")
            }
            "restore-password" -> InputDialog("Restore backup", "Backup password", "Restore",
                "", { recoveryUri = null; modal = "backup" }, password = true,
                description = "Restore on a fresh P2P installation. You’ll need to exchange new invites to reconnect with your contacts.") { value ->
                recoveryUri?.let { model.importRecovery(it, value.toCharArray()) }
                recoveryUri = null
                modal = "backup"
            }
            "profile-bio" -> InputDialog("Bio", "Bio", "Save", profileData?.optString("bio").orEmpty(),
                { modal = "profile" }, contentInput = true) { bio ->
                model.setBio(bio) { modal = "profile" }
            }
            "chatmail-relay" -> InputDialog("Add Chatmail relay", "Relay or login link", "Add relay", "",
                { modal = "profile" }, password = true) { qr ->
                if (qr.isNotBlank()) model.addChatmailRelay(qr) { modal = "profile" }
            }
            "display-name" -> InputDialog("Display name", "Name", "Save", profile, { modal = "profile" }) {
                model.setName(it) { modal = "profile" }
            }
            "scan-invite" -> InviteScanner(
                inviteImport,
                close = { modal = "add-contact" },
                pickImage = { model.clearInviteImport(); modal = "add-contact"; inviteImagePicker.launch(arrayOf("image/*")) },
                scanned = { model.importScannedInvite(it, channelInviteOnly) }
            )
            "camera-unavailable" -> Sheet("Camera unavailable", { modal = "add-contact" }) {
                Text("Allow camera access to scan a QR code, or use a saved image or invite code.")
                Button(onClick = { modal = "add-contact" }) { Text(if (channelInviteOnly) "Back to join channel" else "Back to accept invite") }
            }
            "chat-new" -> Sheet("New chat", { modal = null }) {
                P2pRow("Saved Messages", "Notes and saved messages", Icons.Outlined.BookmarkBorder,
                    enabled = !nativeActionBusy) {
                    runNativeAction(done = {}) { goThread(model.savedMessagesThread()) }
                }
                val available = contacts.filterNot { blocked(it.key) || it.data().has("nativeContact") }
                NativeContactRows(model, "", showEmpty = available.isEmpty()) { id ->
                    messageContact(nativeThreadKey(id))
                }
                PageActionButton("Add contact", { showAddContact() }, Modifier.fillMaxWidth())
                available.forEach { contact ->
                    ContactActionRow(name(contact.key), photo(contact.key), ContactAction.Message,
                        onAction = { goThread(contact.key) })
                }
            }
            "webxdc-catalog" -> thread?.let { target ->
                WebxdcCatalog(close = { modal = null }, attach = {
                    if (thread == target) {
                        addDraft(target, listOf(it))
                        modal = null
                    } else it.uri.path?.let { path -> File(path).delete() }
                })
            }
            "webxdc-app" -> thread?.let { target ->
                val record = records.firstOrNull { it.key == selected &&
                    it.data().optString("thread") == target && it.data().optBoolean("webxdc") }
                val local = record?.let(model::galleryFile)
                val appId = record?.data()?.optInt("nativeMessage") ?: 0
                if (local != null && appId > 9) {
                    WebxdcRunner(model, target, appId, local, webxdcHref, close = { modal = null }) { text, attachment ->
                        if (thread == target && !blocked(target)) {
                            attachment?.let { addDraft(target, listOf(it)) }
                            if (text.isNotBlank()) webxdcDraftText = text
                            modal = null
                        } else attachment?.uri?.path?.let { File(it).delete() }
                    }
                } else LaunchedEffect(selected) { modal = null }
            }
            "group-info" -> groups.firstOrNull { it.key == thread }?.let { group ->
                val chat = group.data().optInt("nativeChat")
                if (chat > 9) Sheet(name(group.key), { modal = null }) {
                    val description = rememberGroupDescription(model, chat)
                    Text("Description", style = MaterialTheme.typography.titleMedium)
                    description.value?.let {
                        if (it.isEmpty()) Text("No description", color = Quiet)
                        else LinkedMessageText(it, ::openLink)
                    }
                    GroupDescriptionFeedback(description)
                    P2pTextButton(onClick = {
                        selected = group.key
                        modal = "native-conversation-options"
                    }) { Text("Chat options") }
                    if (peer.isNotBlank() && group.data().optString("owner") == peer) {
                        P2pTextButton(onClick = { selected = group.key; modal = "group-edit" }) { Text("Edit group") }
                        P2pTextButton(enabled = !nativeActionBusy, onClick = {
                            groupPhotoTarget = group.key
                            groupPhotoPicker.launch("image/*")
                        }) { Text("Choose group photo") }
                        if (photo(group.key).isNotBlank()) {
                            P2pTextButton(enabled = !nativeActionBusy, onClick = {
                                runNativeAction(done = {}) { model.updateNativeGroupImage(group.key, null) }
                            }) { Text("Remove group photo") }
                        }
                    }
                }
            }
            "group-new", "group-edit" -> {
                val editing = modal == "group-edit"
                val group = groups.firstOrNull { editing && it.key == selected }
                val chat = group?.data()?.optInt("nativeChat")?.takeIf { it > 9 }
                val useChatmail = !editing || chat != null
                val nativeMembers by produceState(emptyList<Record>(), useChatmail, chat) {
                    if (useChatmail) {
                        try {
                            value = model.nativeGroupContacts()
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            value = emptyList()
                        }
                    }
                }
                val choices = if (useChatmail) nativeMembers else contacts.filterNot { it.data().has("nativeContact") }
                val groupSubmission = submission?.takeIf { it.sheet == modal }
                val busy = groupSubmission?.finished == false
                val description = if (chat == null) GroupDescriptionState("") else rememberGroupDescription(model, chat)
                GroupSheet(if (editing) "Edit group" else "New group", choices, group,
                    description = description.takeIf { useChatmail }, busy = busy,
                    error = groupSubmission?.error, dismiss = {
                        if (!busy) {
                            groupSubmission?.let(model::clearSubmission)
                            modal = null
                        }
                    }) { title, details, members ->
                    if (useChatmail) model.saveNativeGroup(chat, title, details, members)
                    else if (group != null) { model.updateGroup(group.key, title, members); modal = null }
                }
            }
            "friend-new" -> Sheet("Add friend", { modal = null }) {
                if (contacts.isEmpty()) Button(onClick = { modal = "contacts" }) { Text("Add a contact") }
                contacts.filter { !it.data().optBoolean("friend") && !it.data().optBoolean("blocked") }.forEach {
                    P2pRow(name(it.key), if (it.data().optBoolean("friendSent")) "Request sent" else "Send friend request") {
                        if (!it.data().optBoolean("friendSent")) model.requestFriend(it.key)
                    }
                }
            }
            "friend-menu" -> Sheet(selected?.let(::name) ?: "Friend", { modal = null }) {
                val key = selected
                if (key != null) {
                    val data = contacts.firstOrNull { it.key == key }?.data()
                    val muted = data?.optBoolean("muted") == true
                    val blocked = data?.optBoolean("blocked") == true
                    P2pRow(if (muted) "Unmute" else "Mute", "Control what appears in your feed", Icons.Outlined.NotificationsOff) {
                        model.setContactFlag(key, "muted", !muted); modal = null
                    }
                    P2pRow("Remove friend", "Remove this person from Social", Icons.Outlined.PersonRemove) {
                        model.setContactFlag(key, "friend", false); modal = null
                    }
                    P2pRow(if (blocked) "Unblock" else "Block", "Control incoming messages and posts", Icons.Outlined.Block) {
                        model.setContactFlag(key, "blocked", !blocked); modal = null
                    }
                }
            }
            "message-reactions" -> Sheet("React to message", { modal = null }) {
                val message = records.firstOrNull { it.key == selected && it.isThreadMessage() }
                if (message != null) {
                    EmojiPicker(Modifier.fillMaxWidth().height(290.dp), insert = { emoji ->
                        model.reactMessage(message.key, emoji)
                        modal = null
                    }, close = { modal = null })
                }
            }
            "thread-search" -> ConversationSearchDialog(threadQuery, dismiss = { modal = null }) { query ->
                threadQuery = query
                threadView = Feed
                modal = null
            }
            "post" -> Sheet("New post", { if (!submitting) modal = null }) {
                OutlinedTextField(postDraft, { postDraft = it }, label = { Text("What would you like to share?") },
                    enabled = !submitting, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
                    textStyle = contentTextStyle(MaterialTheme.typography.bodyLarge), shape = RoundedFieldShape)
                FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
                    P2pTextButton(enabled = !submitting,
                        onClick = { postPicker.launch(arrayOf("image/*", "video/*", "audio/*")) }) {
                        Icon(Icons.Outlined.PermMedia, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add media")
                    }
                    if (postMedia.isNotEmpty()) {
                        P2pTextButton(enabled = !submitting, onClick = { postMedia = arrayListOf() }) {
                            Text("Remove media (" + postMedia.size + ")")
                        }
                    }
                }
                submission?.takeIf { it.sheet == "post" }?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = { model.publish(postDraft, postMedia) }, shape = P2pButtonShape,
                    enabled = !submitting && (postDraft.isNotBlank() || postMedia.isNotEmpty())) { Text("Post") }
            }
            "reply" -> InputDialog("Write a reply", "Reply", "Reply", "", { modal = null }, contentInput = true) {
                selected?.let { id -> model.socialAction(id, it, false) }
                modal = null
            }
            "react" -> Sheet("React", { modal = null }) {
                EmojiPicker(Modifier.fillMaxWidth().height(290.dp), insert = { reaction ->
                    selected?.let { model.socialAction(it, reaction, true) }
                    modal = null
                }, close = { modal = null })
            }
            "attach" -> Sheet("Add attachment", { modal = null }) {
                Text("(20 MB Max File Size)", style = MaterialTheme.typography.bodySmall)
                P2pRow("Other File Type", "", Icons.Outlined.AttachFile) { chooseAttachment(true, "*/*") }
                P2pRow("Take photo", "", Icons.Outlined.PhotoCamera) { capture("photo") }
                P2pRow("Record video", "", Icons.Outlined.Videocam) { capture("video") }
                P2pRow("Record audio", "", Icons.Outlined.Mic) {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        modal = "record-audio"
                    } else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
                P2pRow("Photos", "", Icons.Outlined.PhotoLibrary) { chooseAttachment(false, "image/*") }
                P2pRow("Videos", "", Icons.Outlined.VideoLibrary) { chooseAttachment(false, "video/*") }
                P2pRow("Audio", "", Icons.Outlined.AudioFile) { chooseAttachment(false, "audio/*") }
                P2pRow("Contact card (.vcf)", "", Icons.Outlined.ContactPage) { modal = "attach-contact" }
            }
            "capture-unavailable" -> Sheet("Capture unavailable", { modal = "attach" }) {
                Text("Check Android permissions, or select a photo, video, or audio file.")
            }
            "record-audio" -> Sheet("Record audio", { modal = "attach" }) {
                AudioCapturePanel { file ->
                    val id = thread
                    if (id != null) {
                        addDraft(id, listOf(AttachmentDraft(Uri.fromFile(file), "Recorded audio.m4a", "audio/mp4", false, temporary = true)))
                    } else file.delete()
                    modal = null
                }
            }
            "attach-contact" -> Sheet("Contact card (.vcf)", { modal = "attach" }) {
                P2pRow("Choose a .vcf file", "", Icons.Outlined.ContactPage) {
                    chooseAttachment(false, "text/x-vcard", "text/vcard", "application/vcard", "application/octet-stream")
                }
                contacts.filter { !it.data().optBoolean("blocked") }.forEach { contact ->
                    P2pRow(name(contact.key), "Share P2P contact card", Icons.Outlined.PersonOutline) {
                        val id = thread
                        if (id != null) {
                            val display = name(contact.key)
                            val folder = File(context.cacheDir, "captures").apply { mkdirs() }
                            val file = File.createTempFile("p2p-contact-", ".vcf", folder)
                            file.writeText(listOf("BEGIN:VCARD", "VERSION:3.0", "FN:" + escapeVCard(display),
                                "N:" + escapeVCard(display) + ";;;;", "X-P2P-ADDRESS:" + contact.key,
                                "END:VCARD", "").joinToString("\r\n"))
                            addDraft(id, listOf(AttachmentDraft(Uri.fromFile(file), "Contact card.vcf", "text/x-vcard", false, temporary = true)))
                        }
                        modal = null
                    }
                }
            }
            "attach-p2p-files" -> Sheet("P2P Files", { modal = "attach" }) {
                val files = records.filter { it.kind in listOf("file-sent", "file-received") && it.data().optBoolean("listed") }
                    .mapNotNull { file -> model.galleryFile(file)?.let { file to it } }
                if (files.isEmpty()) Text("Downloaded and sent files appear here.", color = Quiet)
                files.forEach { (record, local) ->
                    val data = record.data()
                    P2pRow(data.optString("name", "File"), formatSize(data.optLong("size")), Icons.AutoMirrored.Outlined.InsertDriveFile) {
                        thread?.let { id -> addDraft(id, listOf(AttachmentDraft(Uri.fromFile(local),
                            data.optString("name", "Shared file"), data.optString("mime", "application/octet-stream"), true))) }
                        modal = null
                    }
                }
            }
            "trim-attachment" -> {
                val id = thread
                val item = mediaDrafts[id].orEmpty().getOrNull(trimIndex)
                if (id != null && item != null && item.canTrim) Sheet("Trim " + item.label, { modal = null }) {
                    TrimAttachmentPanel(item) { range ->
                        mediaDrafts[id] = mediaDrafts[id].orEmpty().mapIndexed { index, old ->
                            if (index == trimIndex) old.copy(range = range) else old
                        }
                        modal = null
                    }
                }
            }
            "send" -> Sheet("Send files", { if (!submitting) modal = null }) {
                Text(chosen.size.toString() + if (chosen.size == 1) " file selected" else " files selected", color = Quiet)
                submission?.takeIf { it.sheet == "send" }?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
                val recipients = contacts.filterNot { it.data().optBoolean("blocked") } + groups
                if (recipients.isEmpty()) Button(onClick = { modal = "contacts" }) { Text("Add a contact") }
                recipients.forEach { person ->
                    P2pRow(name(person.key), if (person.kind == "group") "Group" else "Contact",
                        enabled = !submitting && chosen.isNotEmpty()) { model.sendFiles(person.key, chosen) }
                }
            }
            "media" -> {
                val record = records.firstOrNull { it.key == selected }
                val data = record?.data()
                val local = record?.let(model::galleryFile)
                val kind = data?.let { mediaKind(it.optString("mime"), it.optString("name")) }
                if (data != null && local != null && kind != null) key(record.key) {
                    val threadId = data.optString("thread")
                    val postId = data.optString("post")
                    val pages = records.asReversed().mapNotNull { item ->
                        val itemData = item.data()
                        val inGallery = when {
                            item.key == record.key -> true
                            itemData.optBoolean("locallyDeleted") -> false
                            postId.isNotBlank() -> itemData.optString("post") == postId
                            else -> threadId.isNotBlank() && itemData.optString("thread") == threadId &&
                                itemData.optString("post").isBlank()
                        }
                        if (!inGallery) return@mapNotNull null
                        val itemFile = model.galleryFile(item) ?: return@mapNotNull null
                        val itemKind = mediaKind(itemData.optString("mime"), itemData.optString("name"), itemFile)
                            ?: return@mapNotNull null
                        MediaPage(item.key, itemFile, itemData.optString("name", "Shared media"), itemKind)
                    }
                    MediaDialog(pages, record.key, close = { modal = null; downloadFeedback = null },
                        feedback = downloadFeedback, download = { id ->
                            records.firstOrNull { it.key == id }?.let { showDownload(MediaDownload.attachment(it)) }
                        })
                } else {
                    P2pAlertDialog(onDismissRequest = { modal = null }, title = { Text("Media unavailable") },
                        text = { Text("This file is no longer available on this phone.") },
                        confirmButton = { P2pTextButton(onClick = { modal = "file" }) { Text("File details") } })
                }
            }
            "delete-received-copy" -> P2pAlertDialog(
                onDismissRequest = { modal = "file" },
                title = { Text("Delete local copy?") },
                text = { Text("The attachment will stay in the conversation.") },
                confirmButton = { P2pTextButton(onClick = {
                    selected?.let { model.deleteReceivedCopy(it) }
                    modal = "file"
                }) { Text("Delete local copy") } },
                dismissButton = { P2pTextButton(onClick = { modal = "file" }) { Text("Cancel") } }
            )
            "file" -> {
                val file = records.firstOrNull { it.key == selected }
                if (file != null) {
                    val data = file.data()
                    val status = downloadState(data)
                    Sheet(data.optString("name", "File"), { modal = null }) {
                        Text(formatSize(data.optLong("size")) +
                            if (file.kind == "file-received" && status.isNotBlank()) " · " + status else "", color = Quiet)
                        if (data.optString("mime") in setOf("text/x-vcard", "text/vcard", "application/vcard")) {
                            model.galleryFile(file)?.let { VCardPreview(it) }
                        }
                        val local = model.galleryFile(file)
                        val kind = mediaKind(data.optString("mime"), data.optString("name"))
                        if (local != null && kind != null) Button(onClick = { modal = "media" }) {
                            Text(if (kind in setOf(MediaKind.Audio, MediaKind.Video)) "Play" else "View")
                        }
                        if (file.kind == "file-received") {
                            if (status == "Complete" && local != null) P2pOutlinedButton(onClick = { openFile(file) }) {
                                Text(when {
                                    kind != null -> "Open in another app"
                                    data.optString("mime") in setOf("text/x-vcard", "text/vcard", "application/vcard") -> "Open in Contacts"
                                    else -> "Open file"
                                })
                            }
                            else if (status in listOf("Downloading", "Waiting to connect")) {
                                if (data.optLong("size") > 0) LinearProgressIndicator(progress = {
                                    (data.optLong("offset").toDouble() / data.optLong("size")).toFloat().coerceIn(0f, 1f)
                                }, modifier = Modifier.fillMaxWidth())
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    P2pOutlinedButton(onClick = { model.pauseDownload(file.key) }) { Text("Pause") }
                                    P2pTextButton(onClick = { model.cancelDownload(file.key) }) { Text("Cancel download") }
                                }
                            } else if (status != "Withdrawn") {
                                Button(onClick = { model.requestDownload(file.key) }) { Text(if (status == "Paused") "Resume download" else "Download now") }
                            }
                        }
                        if (local != null) {
                            P2pOutlinedButton(onClick = {
                                showDownload(MediaDownload.attachment(file))
                            }) { Text("Save a copy") }
                            if (file.kind == "file-received") {
                                P2pTextButton(onClick = { modal = "delete-received-copy" }) { Text("Delete local copy") }
                            }
                        }
                        if (file.kind == "file-sent" && data.optInt("targets") >
                            data.optInt("delivered") + data.optInt("failed") + data.strings("cancelledTo").size) {
                            P2pTextButton(onClick = { model.cancelQueuedOffer(file.key); modal = null }) { Text("Cancel queued offer") }
                        }
                        if (!data.optBoolean("listed")) P2pOutlinedButton(onClick = { model.saveToFiles(file.key); modal = null }) { Text("Save to Files") }
                        P2pTextButton(onClick = { model.star(file.key); modal = null }) {
                            Text(if (data.optBoolean("star")) "Remove star" else "Star attachment")
                        }
                    }
                }
            }
        }
        if (!pickingLocation && !liveCall) download?.let { request ->
            val busy = downloadJob != null
            DownloadMediaDialog(request, busy, downloadError ?: if (busy) "Preparing file…" else null,
                close = {
                    downloadJob?.cancel()
                    downloadJob = null
                    download = null
                },
                save = { saveDownload(request, null) },
                choose = {
                    runDownload {
                        model.prepareMediaDownload(request)
                        pickingLocation = true
                        chooseDownload.launch(request.createDocument())
                    }
                })
        }
    }
}

@Composable
private fun TalkMessagesIcon(unread: Int, description: String?) {
    BadgedBox(badge = {
        if (unread > 0) Badge {
            Text(if (unread > 99) "99+" else unread.toString(), Modifier.clearAndSetSemantics {})
        }
    }) {
        Icon(Icons.AutoMirrored.Outlined.Chat, description)
    }
}

private val FittedLabelMinimum = 11.sp

@Composable
private fun FittedLabel(
    label: String, fontWeight: FontWeight = FontWeight.Medium, size: Int = 14,
    color: Color = LocalContentColor.current, modifier: Modifier = Modifier
) {
    BasicText(label, modifier = modifier, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        style = LocalTextStyle.current.copy(color = color, fontWeight = fontWeight, textAlign = TextAlign.Center),
        autoSize = TextAutoSize.StepBased(minFontSize = FittedLabelMinimum, maxFontSize = size.sp, stepSize = 0.5.sp))
}

private val TabButtonHeight = 48.dp
private val PageActionHorizontalPadding = 10.dp

@Composable
private fun TabButtonBar(content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(17.dp), color = Panel
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
internal fun Segments(options: List<String>, current: String, prominent: Boolean = false, select: (String) -> Unit) {
    TabButtonBar {
        options.forEach { option ->
            val active = current == option
            Surface(
                onClick = { select(option) }, modifier = Modifier.weight(1f),
                color = if (active) AccentPanel else Color.Transparent,
                shape = P2pButtonShape
            ) {
                Box(Modifier.heightIn(min = TabButtonHeight).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    FittedLabel(option, color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else Quiet, size = 14,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
internal fun PageActionButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null,
    enabled: Boolean = true, trailing: @Composable RowScope.() -> Unit = {}
) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = TabButtonHeight), enabled = enabled,
        shape = P2pButtonShape, colors = ButtonDefaults.buttonColors(disabledContainerColor = Edge, disabledContentColor = Quiet),
        contentPadding = PaddingValues(horizontal = PageActionHorizontalPadding, vertical = 8.dp)) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        FittedLabel(label, fontWeight = FontWeight.SemiBold)
        trailing()
    }
}

@Composable
private fun SettingsGrid(query: String, open: (String) -> Unit) {
    val settings = listOf("Profile" to "profile", "Setup" to "setup", "Appearance" to "appearance",
        "Read aloud" to "text-to-speech", "Notifications" to "notifications", "Quiet Hours" to "quiet-hours",
        "P2P address" to "p2p-address", "Backup" to "backup", "Block list" to "block-list",
        "Connections" to "connection-details", "Updates" to "updates", "App log" to "app-log")
    val matches = settings.filter { (label, _) -> threadSearchMatches(query, label, emptyList()) }
    if (matches.isEmpty()) {
        EmptyTabMessage("No matching settings")
        return
    }
    val measurer = rememberTextMeasurer()
    val minimumLabelStyle = MaterialTheme.typography.labelLarge.copy(
        fontSize = FittedLabelMinimum, fontWeight = FontWeight.SemiBold)
    val widestLabel = remember(measurer, minimumLabelStyle) {
        settings.maxOf { (label, _) ->
            measurer.measure(label, minimumLabelStyle, softWrap = false, maxLines = 1).size.width
        }
    }
    val gap = 10.dp
    val minimumButtonWidth = with(LocalDensity.current) { widestLabel.toDp() } + PageActionHorizontalPadding * 2
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= minimumButtonWidth * 2 + gap) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            matches.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { (label, destination) ->
                        PageActionButton(label, { open(destination) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAndAction(search: String, update: (String) -> Unit, action: String, invoke: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = search, onValueChange = update, modifier = Modifier.weight(1f), singleLine = true,
            placeholder = { Text("Search", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
            shape = RoundedCornerShape(15.dp)
        )
        Button(onClick = invoke, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            FittedLabel(action)
        }
    }
}

@Composable
internal fun Avatar(
    label: String, icon: ImageVector? = null, photo: String = "", size: androidx.compose.ui.unit.Dp = 43.dp,
    background: Color = AccentPanel, save: ((MediaDownload) -> Unit)? = LocalMediaDownload.current
) {
    val image by produceState<Bitmap?>(null, photo) {
        value = if (photo.isBlank()) null else withContext(Dispatchers.IO) { decodeProfilePhoto(photo) }
    }
    val first = label.trim().let { it.take(if (it.isEmpty()) 0 else Character.charCount(it.codePointAt(0))) }.uppercase()
    val fill = if (icon != null || image != null) background else inkOnAccent(Mint)
    Box(Modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        when {
            icon != null -> Icon(icon, null, Modifier.size(22.dp), tint = inkOnAccent(background))
            else -> {
                val bitmap = image
                if (bitmap != null) {
                    val downloadPhoto = save?.let { action ->
                        { action(MediaDownload("$label profile photo.jpg", "image/jpeg", profilePhoto = photo)) }
                    }
                    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().then(
                        if (downloadPhoto == null) Modifier else Modifier.mediaLongPress("Download image", downloadPhoto)
                    ), contentScale = ContentScale.Crop)
                }
                else Text(first.ifBlank { "?" }, color = Mint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun IncomingContactRequests(records: List<Record>, accept: (String) -> Unit, decline: (String) -> Unit) {
    val clock by produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            value = System.currentTimeMillis()
        }
    }
    val activeTokens = records.filter { it.kind == "invite-out" && it.data().optLong("expires") > clock }
        .mapTo(mutableSetOf()) { it.key.substringAfter("invite:") }
    val incoming = records.filter { it.kind == "invite-in" && it.data().optString("token") in activeTokens }
    if (incoming.isNotEmpty()) Text("Contact requests", style = MaterialTheme.typography.titleSmall)
    incoming.forEach { request ->
        val sender = request.data().optString("name").ifBlank { "New contact" }
        Surface(shape = RoundedCornerShape(15.dp), color = Edge, contentColor = Ink) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(sender, background = Panel)
                    Text(sender, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    P2pTextButton(onClick = { decline(request.key) }) { Text("Decline") }
                    Button(onClick = { accept(request.key) }) { Text("Accept") }
                }
            }
        }
    }
}

private enum class ContactAction(val label: String, val icon: ImageVector) {
    Call("Call", Icons.Outlined.Call),
    Message("Message", Icons.AutoMirrored.Outlined.Chat)
}

@Composable
private fun ContactActionRow(
    title: String, photo: String, action: ContactAction, blocked: Boolean = false,
    pinned: Boolean = false, onOptions: (() -> Unit)? = null, onAction: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    P2pRow(
        title = title, description = if (blocked) "Blocked" else "", photo = photo,
        onLong = onOptions,
        trailing = {
            if (onOptions != null) {
                IconButton(onClick = onOptions) {
                    Icon(Icons.Outlined.MoreVert, "Contact options", tint = Quiet)
                }
            }
            if (!blocked) {
                FilledIconButton(onClick = onAction, shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary,
                        contentColor = colors.onPrimary)) {
                    Icon(action.icon, "${action.label} $title", Modifier.size(22.dp))
                }
            }
        },
        detail = {
            if (pinned) Icon(Icons.Outlined.PushPin, "Pinned contact", Modifier.size(15.dp), tint = Quiet)
        },
        click = if (blocked && action == ContactAction.Call && onOptions != null) onOptions else onAction
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun P2pRow(
    title: String, description: String, icon: ImageVector? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    onLong: (() -> Unit)? = null, photo: String = "", detail: @Composable RowScope.() -> Unit = {},
    enabled: Boolean = true, click: () -> Unit
) {
    Surface(color = Edge, contentColor = Ink, shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(enabled = enabled, onClick = click, onLongClick = onLong)
                .padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(title, icon, photo)
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (description.isNotBlank()) Text(description, Modifier.weight(1f, fill = false), color = Quiet,
                        fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    detail()
                }
            }
            trailing()
        }
    }
}

@Composable
internal fun EmptyTabMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = 112.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(16.dp), color = Edge, contentColor = Quiet) {
            Text(message, Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

private enum class FullPageRoute(val route: String, val title: String) {
    Contacts("contacts", "Contacts"),
    Settings("settings", "Settings"),
    Updates("updates", "P2P updates"),
    Notifications("notifications", "Notifications"),
    Address("p2p-address", "P2P address"),
    Backup("backup", "Backup"),
    AppLog("app-log", "App log"),
    ReadAloud("text-to-speech", "Read aloud"),
    QuietHours("quiet-hours", "Quiet Hours"),
    Connections("connection-details", "Connections"),
    BlockList("block-list", "Block list"),
    Appearance("appearance", "Appearance"),
    Profile("profile", "Your profile")
}

@Composable
private fun SystemNavigationBar() {
    Spacer(Modifier.fillMaxWidth().background(Panel).windowInsetsBottomHeight(WindowInsets.navigationBars))
}

@Composable
private fun AppToolbarActions(
    muted: Boolean, setMuted: (Boolean) -> Unit, searchLabel: String,
    search: () -> Unit, contacts: () -> Unit, settings: () -> Unit
) {
    val tint = readableAccent(Base, Ink)
    IconToggleButton(checked = muted, onCheckedChange = setMuted) {
        Icon(if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.NotificationsNone,
            if (muted) "Unmute all notifications" else "Mute all notifications", tint = tint)
    }
    IconButton(onClick = search) { Icon(Icons.Outlined.Search, searchLabel, tint = tint) }
    IconButton(onClick = contacts) { Icon(Icons.Outlined.PersonOutline, "Contacts", tint = tint) }
    IconButton(onClick = settings) { Icon(Icons.Outlined.Settings, "Settings", tint = tint) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullPageScreen(
    page: FullPageRoute, back: () -> Unit, actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(page.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Base)
            )
        },
        bottomBar = { SystemNavigationBar() }
    ) { padding ->
        key(page) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (page == FullPageRoute.Settings) {
                    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                } else {
                    Surface(color = Panel, shape = P2pDialogShape) {
                        Column(Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Sheet(
    title: String, dismiss: () -> Unit, tall: Boolean = false, content: @Composable ColumnScope.() -> Unit
) {
    val scroll = rememberScrollState()
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val maxHeight = if (tall) windowHeight * .88f else minOf(540.dp, windowHeight * .88f)
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Panel, contentColor = Ink, sheetMaxWidth = 560.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight)
                .padding(start = 18.dp, end = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.titleLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                Column(Modifier.fillMaxWidth().padding(end = 8.dp).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
                val track = Edge
                val thumb = Quiet
                Canvas(Modifier.matchParentSize()) {
                    val range = scroll.maxValue
                    val viewport = scroll.viewportSize.toFloat()
                    if (range in 1 until Int.MAX_VALUE && viewport > 0f && size.height > 0f) {
                        val height = (size.height * viewport / (viewport + range))
                            .coerceIn(minOf(24.dp.toPx(), size.height), size.height)
                        val position = (size.height - height) * scroll.value / range
                        val radius = 2.dp.toPx()
                        val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) size.width - radius else radius
                        drawLine(track, androidx.compose.ui.geometry.Offset(x, radius),
                            androidx.compose.ui.geometry.Offset(x, maxOf(radius, size.height - radius)), 4.dp.toPx(),
                            androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(thumb, androidx.compose.ui.geometry.Offset(x, position + radius),
                            androidx.compose.ui.geometry.Offset(x, maxOf(position + radius, position + height - radius)),
                            4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
            }
        }
    }
}

private enum class InviteTarget { Contact, Group, Channel }

@Composable
private fun ColumnScope.SecureJoinCard(
    target: InviteTarget, profile: String, photo: String, conversationId: Int? = null,
    createInvite: suspend () -> String
) {
    var attempt by remember(target, conversationId) { mutableIntStateOf(0) }
    var code by remember(target, conversationId) { mutableStateOf<String?>(null) }
    var error by remember(target, conversationId) { mutableStateOf<String?>(null) }
    LaunchedEffect(target, conversationId, attempt) {
        error = null
        try {
            code = createInvite()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Couldn’t create the invite."
        } catch (_: LinkageError) {
            error = "Chatmail couldn’t start on this device."
        }
    }
    val invite = code
    when {
        invite != null -> ShareInvite(invite, target, profile, photo)
        error != null -> {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            P2pTextButton(onClick = { attempt++ }) { Text("Retry") }
        }
        else -> LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ColumnScope.ShareInvite(code: String, target: InviteTarget, profile: String, photo: String) {
    val kind = target.name
    val context = LocalContext.current
    val bitmap = remember(code) { InviteImage.make(code) }
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val cardWidth = (windowHeight * .44f).coerceIn(244.dp, 400.dp)
    if (bitmap != null) {
        var enlarged by remember(code) { mutableStateOf(false) }
        Surface(
            modifier = Modifier.align(Alignment.CenterHorizontally).widthIn(max = cardWidth).fillMaxWidth(),
            shape = P2pDialogShape,
            color = Color.White,
            contentColor = Color.Black
        ) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(profile, photo = photo, size = 38.dp, save = null)
                InviteQr(bitmap, "$kind invite QR code", Modifier.fillMaxWidth().aspectRatio(1f)
                    .clickable(onClickLabel = "Enlarge QR code") { enlarged = true })
                Text(when (target) {
                    InviteTarget.Channel -> "Scan to follow $profile"
                    InviteTarget.Group -> "Scan to join $profile"
                    InviteTarget.Contact -> if (profile.isBlank()) "Scan to start a chat" else "Scan to chat with $profile"
                },
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        }
        if (enlarged) InviteQrDialog(bitmap) { enlarged = false }
    } else {
        Text("This invite couldn’t fit in a QR code. Copy or share it instead.", color = Quiet)
    }
    Row(Modifier.align(Alignment.CenterHorizontally).widthIn(max = cardWidth).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        P2pOutlinedButton(onClick = {
            val clip = android.content.ClipData.newPlainText("$kind invite", code)
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
        }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { FittedLabel("Copy link") }
        Button(onClick = {
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code)
            context.startActivity(Intent.createChooser(intent, "Share ${kind.lowercase()} invite"))
        }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { FittedLabel("Share") }
    }
}

@Composable
internal fun InviteQr(bitmap: Bitmap, label: String, modifier: Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier.background(Color.White).semantics { contentDescription = label }) {
        val pixels = minOf(size.width, size.height).toInt()
        val module = (pixels / bitmap.width).coerceAtLeast(1)
        val edge = module * bitmap.width
        drawImage(image, dstOffset = IntOffset((size.width.toInt() - edge) / 2, (size.height.toInt() - edge) / 2),
            dstSize = IntSize(edge, edge), filterQuality = FilterQuality.None)
    }
}

@Composable
private fun InviteQrDialog(bitmap: Bitmap, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val edge = minOf(maxWidth - 24.dp, maxHeight - 92.dp, 560.dp).coerceAtLeast(1.dp)
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    InviteQr(bitmap, "Full-size contact invite QR code", Modifier.size(edge))
                    P2pTextButton(onClick = close) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun ConversationSearchDialog(initial: String, dismiss: () -> Unit, title: String = "Search", search: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf(initial) }
    fun submit() { search(query.trim()) }
    Sheet(title, dismiss) {
        SearchField(query, { query = it }, "Search", ::submit)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            P2pTextButton(onClick = dismiss) { Text("Cancel") }
            Button(onClick = ::submit, shape = P2pButtonShape) { Text("Search") }
        }
    }
}

@Composable
private fun InputDialog(
    title: String, label: String, button: String, initial: String, dismiss: () -> Unit,
    contentInput: Boolean = false, password: Boolean = false, busy: Boolean = false, error: String? = null,
    confirmPassword: Boolean = false, description: String? = null,
    confirm: (String) -> Unit
) {
    var text by if (password) remember(title) { mutableStateOf(initial) }
        else rememberSaveable(title) { mutableStateOf(initial) }
    var repeated by remember(title) { mutableStateOf("") }
    P2pAlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        val style = MaterialTheme.typography.bodyLarge
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = !contentInput, enabled = !busy,
                minLines = if (contentInput) 2 else 1, maxLines = if (contentInput) 5 else 1, modifier = Modifier.fillMaxWidth(),
                textStyle = if (contentInput) contentTextStyle(style) else style, shape = RoundedFieldShape,
                isError = error != null, supportingText = error?.let { message -> { Text(message) } },
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None)
            if (confirmPassword) {
                OutlinedTextField(repeated, { repeated = it }, label = { Text("Confirm password") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedFieldShape,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = repeated.isNotEmpty() && !text.startsWith(repeated))
            }
        }
    }, confirmButton = {
        Button(onClick = { confirm(text) }, shape = P2pButtonShape, enabled = !busy && text.isNotBlank() &&
            (!password || text.length in 12..1024) && (!confirmPassword || repeated == text)) { FittedLabel(button) }
    }, dismissButton = { P2pTextButton(onClick = dismiss) { Text("Cancel") } })
}

private data class GroupDescriptionState(
    val value: String? = null, val error: String? = null, val retry: (() -> Unit)? = null
)

@Composable
private fun rememberGroupDescription(model: P2pModel, chat: Int): GroupDescriptionState {
    var attempt by remember(chat) { mutableIntStateOf(0) }
    val description by produceState(GroupDescriptionState(), model, chat, attempt) {
        value = GroupDescriptionState()
        var details: String? = null
        val error = chatAction { details = model.chatmailDescription(chat) }
        value = GroupDescriptionState(details, error) { attempt++ }
    }
    return description
}

@Composable
private fun GroupDescriptionFeedback(description: GroupDescriptionState) {
    if (description.error != null) {
        Text(description.error, color = MaterialTheme.colorScheme.error)
        description.retry?.let { P2pTextButton(onClick = it) { Text("Retry") } }
    } else if (description.value == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun GroupSheet(
    title: String, contacts: List<Record>, group: Record?,
    description: GroupDescriptionState?, busy: Boolean, error: String?, dismiss: () -> Unit,
    save: (String, String?, Set<String>) -> Unit
) {
    var name by rememberSaveable(group?.key) { mutableStateOf(group?.data()?.optString("name").orEmpty()) }
    var draft by rememberSaveable(group?.key) { mutableStateOf<String?>(null) }
    var members by rememberSaveable(group?.key) {
        mutableStateOf(ArrayList(group?.data()?.strings("members").orEmpty()))
    }
    fun toggleMember(id: String) {
        members = ArrayList(if (id in members) members - id else members + id)
    }
    val details = draft ?: description?.value.orEmpty()
    Sheet(title, dismiss) {
        OutlinedTextField(name, { name = it }, label = { Text("Group name") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, shape = RoundedFieldShape, enabled = !busy)
        if (description != null) {
            OutlinedTextField(details, { if (it.length <= INTEROP_TEXT_LIMIT) draft = it },
                label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth(),
                minLines = 2, maxLines = 5, shape = RoundedFieldShape, enabled = !busy && description.value != null)
            GroupDescriptionFeedback(description)
        }
        if (contacts.isEmpty()) Text("Add contacts before creating a group.", color = Quiet)
        contacts.filter { !it.data().optBoolean("blocked") }.forEach { contact ->
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { toggleMember(contact.key) }
                .padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = contact.key in members, enabled = !busy, onCheckedChange = { toggleMember(contact.key) })
                Text(contact.data().optString("name"))
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = {
            val editedDescription = draft?.takeUnless { it.trim() == description?.value?.trim() }
            save(name, editedDescription, members.toSet())
        }, shape = P2pButtonShape,
            enabled = !busy && name.isNotBlank() && (description == null || description.value != null) &&
                (group != null || contacts.any { it.key in members })) {
            Text(if (group == null) "Create group" else "Save changes")
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ColumnScope.ThreadView(
    id: String, view: String, records: List<Record>, peer: String, find: String, searchOpen: Boolean,
    name: (String) -> String, photo: (String) -> String,
    onView: (String) -> Unit, quote: Record?, clearQuote: () -> Unit,
    attachments: List<AttachmentDraft>, preparing: Boolean, canCompose: Boolean = true,
    loadOlder: (() -> Unit)? = null, removeAttachment: (Int) -> Unit, trimAttachment: (Int) -> Unit,
    send: (String, () -> Unit) -> Unit, preview: (PreviewLink) -> Unit,
    openLink: (PreviewLink) -> Unit, attach: () -> Unit,
    messageMenu: (String) -> Unit, react: (String) -> Unit,
    star: (String) -> Unit, galleryFile: (Record) -> File?, file: (String) -> Unit,
    requestAttachment: ((String) -> Unit)? = null,
    openApps: (() -> Unit)? = null,
    openWebxdc: ((Record) -> Unit)? = null,
    draftText: String? = null,
    consumeDraftText: () -> Unit = {}
) {
    val messages = remember(records, id) {
        records.filter { it.isThreadMessage() && !it.data().optBoolean("locallyDeleted") && !it.data().optBoolean("voicemail") && it.data().optString("thread") == id }
    }
    val reactionsByMessage = remember(records) {
        records.filter { it.kind == "message-reaction" }.groupBy { it.data().optString("message") }
            .mapValues { (_, reactions) ->
                reactions.associate { record ->
                    val data = record.data()
                    record.key to ReactionMark(data.optString("text"), data.optString("id", record.created.toString()))
                }
            }
    }
    val seenReactions = remember(id) { reactionsByMessage.toMutableMap() }
    val visible = remember(messages, view, find) {
        val filtered = when (view) {
            "Starred" -> messages.filter { it.data().optBoolean("star") }
            "Gallery" -> messages.filter { it.kind.startsWith("file-") && it.data().let { data ->
                mediaKind(data.optString("mime"), data.optString("name")) != null
            } }
            else -> messages
        }
        if (find.isBlank()) filtered else filtered.filter { threadSearchMatches(find, "", listOf(it.searchText())) }
    }
    var draft by rememberSaveable(id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    LaunchedEffect(draftText) {
        draftText?.let { text ->
            val combined = if (draft.text.isBlank()) text else draft.text + "\n" + text
            draft = TextFieldValue(combined, TextRange(combined.length))
            consumeDraftText()
        }
    }
    var emojiPicker by rememberSaveable(id) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val ownCard = Edge
    LaunchedEffect(searchOpen) { if (searchOpen) emojiPicker = false }
    val showEmoji = canCompose && view == Feed && !searchOpen && emojiPicker && !keyboardVisible
    BackHandler(showEmoji) { emojiPicker = false }
    if (!keyboardVisible || LocalConfiguration.current.screenHeightDp >= 480 || view != Feed) {
        Segments(listOf(Feed, "Gallery", "Starred"), view, prominent = true) { next ->
            emojiPicker = false
            focus.clearFocus()
            onView(next)
        }
    }
    key(id, view, find) {
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val closeDistance = with(density) { 96.dp.roundToPx() }
        val nearLatest by remember(listState, closeDistance) {
            derivedStateOf {
                !listState.canScrollBackward ||
                    (listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset <= closeDistance)
            }
        }
        val latest = visible.firstOrNull()
        var previousLatest by remember { mutableStateOf(latest?.key) }
        LaunchedEffect(latest?.key) {
            val previous = previousLatest
            previousLatest = latest?.key
            val newHead = latest != null && previous != null &&
                visible.indexOfFirst { it.key == previous } > 0
            if (view == Feed && find.isBlank() && newHead &&
                ((nearLatest && !listState.isScrollInProgress) ||
                    (peer.isNotBlank() && latest?.data()?.optString("author") == peer))) {
                listState.animateScrollToItem(0)
            }
        }
        val showActions = view == Feed && find.isBlank() && !searchOpen
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (visible.isEmpty()) {
                val message = when {
                    find.isNotBlank() -> "No matching messages"
                    view == "Starred" -> "No starred messages"
                    view == "Gallery" -> "No shared media"
                    else -> "No messages"
                }
                EmptyTabMessage(message, Modifier.fillMaxSize())
            } else if (view == "Gallery") {
                MediaGallery(visible, galleryFile, file, star)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(), reverseLayout = true,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp,
                        bottom = if (showActions) 64.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(visible, key = { it.key }) { message ->
                        val data = message.data()
                        val author = data.optString("author")
                        val isAttachment = message.kind.startsWith("file-")
                        val own = message.kind == "file-sent" || (!isAttachment && peer.isNotBlank() && author == peer)
                        val sender = if (own) "You" else name(author)
                        val status = if (own && !isAttachment) {
                            when {
                                data.optInt("failed") > 0 || data.optInt("cancelled") > 0 -> messageDeliveryStatus(data)
                                data.optInt("delivered") == 0 && !data.optBoolean("chatmailSubmitted") -> "Sending…"
                                else -> ""
                            }
                        } else ""
                        val options = { messageMenu(message.key) }
                        var animatingReaction by remember { mutableStateOf(false) }
                        val bubbleColor = if (own) ownCard else Mint
                        Box(
                            Modifier.zIndex(if (animatingReaction) 1f else 0f).fillMaxWidth().padding(start = if (own) 0.dp else 76.dp, end = if (own) 76.dp else 0.dp),
                            contentAlignment = if (own) Alignment.CenterStart else Alignment.CenterEnd
                        ) {
                            Surface(
                                color = bubbleColor,
                                contentColor = inkOnAccent(bubbleColor),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth()
                                    .combinedClickable(onClick = {}, onLongClick = options, onLongClickLabel = "Message options")
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    BoxWithConstraints {
                                        val identity: @Composable (Modifier) -> Unit = { modifier ->
                                            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                                                Avatar(sender, photo = photo(if (own) peer else author), size = 38.dp)
                                                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                                                    Text(sender, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(shortTime(message.created), fontSize = 12.sp)
                                                }
                                            }
                                        }
                                        val actions: @Composable () -> Unit = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                ReadAloudButton(message.key, if (isAttachment) data.optString("caption") else data.optString("text"), "message")
                                                IconButton(onClick = { react(message.key) }, enabled = canCompose) {
                                                    Icon(Icons.Outlined.EmojiEmotions, if (isAttachment) "React to attachment" else "React to message", Modifier.size(24.dp))
                                                }
                                                StarButton(data.optBoolean("star"), if (isAttachment) "attachment" else "message") { star(message.key) }
                                            }
                                        }
                                        if (maxWidth < 300.dp) Column {
                                            identity(Modifier.fillMaxWidth())
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
                                        } else Row(verticalAlignment = Alignment.CenterVertically) {
                                            identity(Modifier.weight(1f)); actions()
                                        }
                                    }
                                    if (status.isNotBlank()) Text(status, fontSize = 12.sp)
                                    val reference = data.optString("replyTo")
                                    val quoteText = data.optString("quoteText")
                                    if (reference.isNotEmpty() || quoteText.isNotEmpty()) {
                                        val original = messages.firstOrNull { it.key == reference }?.data()
                                        QuotedMessage(original?.let { name(it.optString("author")) }
                                            ?: data.optString("quoteAuthor").ifBlank { "Earlier message" },
                                            original?.optString("text")?.ifBlank { original.optString("name") } ?: quoteText)
                                    }
                                    if (isAttachment && data.optBoolean("webxdc") && openWebxdc != null) {
                                    val local = galleryFile(message)
                                    val downloading = data.optString("state") == "Downloading"
                                    Text(data.optString("name", "App").substringBeforeLast(".xdc"),
                                        style = contentTextStyle(MaterialTheme.typography.titleMedium))
                                    if (downloading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    else Button(onClick = { openWebxdc(message) }, shape = P2pButtonShape) {
                                        Icon(Icons.Outlined.GridView, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (local != null) "Open app" else "Download app")
                                    }
                                } else if (isAttachment) {
                                        val local = galleryFile(message)
                                        val pendingInline = local == null && message.kind == "file-received" &&
                                            data.optInt("nativeMessage") > 9 && mediaKind(data.optString("mime"), data.optString("name")) != null
                                        val open = if (pendingInline && requestAttachment != null) ({ requestAttachment(message.key) })
                                            else ({ file(message.key) })
                                        ChatAttachment(data, local, message.kind == "file-received",
                                            open, options, playbackKey = message.key, onLongClickLabel = "Attachment options")
                                    }
                                    val body = data.optString(if (isAttachment) "caption" else "text")
                                    MessageBodyWithPreviews(body, preview, openLink, bubbleColor)
                                    if (data.optBoolean("nativeLocation")) {
                                        Text("Shared location", style = MaterialTheme.typography.labelMedium)
                                    }
                                    if (data.optInt("webxdcParent") > 9 && openWebxdc != null) {
                                        P2pTextButton(onClick = { openWebxdc(message) }) { Text("Open app") }
                                    }
                                    val details = listOfNotNull(
                                        "Edited".takeIf { data.optBoolean("nativeEdited") },
                                        "Pinned".takeIf { data.optBoolean("nativePinned") }
                                    )
                                    if (details.isNotEmpty()) {
                                        Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                                    }
                                    val reactions = reactionsByMessage[message.key].orEmpty().values
                                        .map(ReactionMark::emoji).distinct().joinToString(" ")
                                    if (reactions.isNotBlank()) {
                                        Surface(modifier = Modifier.align(Alignment.End), shape = CircleShape, color = Panel) {
                                            Text(reactions, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), fontSize = 15.sp)
                                        }
                                    }
                                }
                            }
                            Box(Modifier.matchParentSize()) {
                                ReactionDecoration(message.key, reactionsByMessage[message.key].orEmpty(), seenReactions) {
                                    animatingReaction = it
                                }
                            }
                        }
                    }
                    if (loadOlder != null && view == Feed && find.isBlank()) item(key = "thread-load-older") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            P2pTextButton(onClick = loadOlder) { Text("Load older messages") }
                        }
                    }
                }
            }
            if (showActions && (!nearLatest || openApps != null)) {
                SmallFloatingActionButton(
                    onClick = {
                        if (nearLatest) openApps?.invoke() else scope.launch { listState.animateScrollToItem(0) }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp),
                    shape = CircleShape,
                    containerColor = AccentPanel,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Icon(if (nearLatest) Icons.Outlined.GridView else Icons.Outlined.ArrowDownward,
                        if (nearLatest) "Apps" else "Scroll to latest message")
                }
            }
        }
    }
    if (view == Feed) {
        AttachmentDraftList(attachments, preparing, removeAttachment, trimAttachment,
            if (keyboardVisible || showEmoji) 58.dp else 140.dp)
        if (quote != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { QuotedMessage(name(quote.data().optString("author")), quote.data().optString("text")) }
                IconButton(onClick = clearQuote) { Icon(Icons.Outlined.Close, "Remove reply quote") }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = attach, enabled = canCompose && !preparing) { Icon(Icons.Outlined.AttachFile, "Add attachment", tint = readableAccent(Panel, Ink)) }
            IconButton(onClick = {
                if (emojiPicker) emojiPicker = false else {
                    keyboard?.hide()
                    focus.clearFocus()
                    emojiPicker = true
                }
            }, enabled = canCompose) { Icon(Icons.Outlined.EmojiEmotions, "Emoji picker", tint = readableAccent(Panel, Ink)) }
            OutlinedTextField(draft, { draft = it }, modifier = Modifier.weight(1f).onFocusChanged {
                if (it.isFocused) emojiPicker = false
            }, enabled = canCompose, maxLines = if (LocalConfiguration.current.screenHeightDp < 480) 2 else 4,
                placeholder = { FittedLabel("Message", fontWeight = FontWeight.Normal, size = 16) },
                textStyle = contentTextStyle(MaterialTheme.typography.bodyLarge), shape = RoundedCornerShape(19.dp))
            Spacer(Modifier.width(6.dp))
            FilledIconButton(onClick = {
                val submitted = draft.text
                send(submitted) { if (draft.text == submitted) draft = TextFieldValue() }
            }, enabled = canCompose && !preparing && (draft.text.isNotBlank() || attachments.isNotEmpty())) {
                Icon(Icons.AutoMirrored.Outlined.Send, "Send")
            }
        }
        if (showEmoji) EmojiPicker(Modifier.weight(1f, fill = false).heightIn(max = 245.dp), insert = { emoji ->
            val selection = draft.selection
            draft = TextFieldValue(draft.text.replaceRange(selection.min, selection.max, emoji),
                TextRange(selection.min + emoji.length))
        }, close = { emojiPicker = false })
    }
}

@Composable
internal fun ChatAttachment(
    data: JSONObject, local: File?, received: Boolean, open: () -> Unit, longPress: (() -> Unit)?,
    playbackKey: String, usePlaybackPoster: Boolean = false, onLongClickLabel: String = "Download media"
) {
    val label = data.optString("name", "Shared file")
    val mime = data.optString("mime")
    val kind = remember(mime, label, local) { mediaKind(mime, label, local) }
    if (kind == MediaKind.Audio && local != null) {
        InlineAudioAttachment(local, playbackKey, label, longPress, onLongClickLabel)
        return
    }
    val animation = kind?.animationLabel
    val inlineAnimation = local != null && animation != null && !usePlaybackPoster
    val thumbnail = mediaThumbnail(local?.takeUnless { inlineAnimation }, kind)
    if (usePlaybackPoster && local != null && (kind == MediaKind.Video || animation != null)) {
        val playLabel = if (animation != null) "Play $animation" else "Play video"
        val ratio = thumbnail?.let { (it.width.toFloat() / it.height).coerceIn(0.8f, 1.8f) } ?: 16f / 9f
        Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp)).background(Panel)
            .combinedClickable(onClickLabel = playLabel, onClick = open,
                onLongClickLabel = onLongClickLabel, onLongClick = longPress),
            contentAlignment = Alignment.Center) {
            if (thumbnail != null) {
                Image(thumbnail.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Surface(shape = CircleShape, color = AccentPanel, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PlayArrow, playLabel, Modifier.size(27.dp))
                }
            }
        }
        return
    }
    if (inlineAnimation) {
        Box(Modifier.combinedClickable(onClickLabel = "Open $animation", onClick = open,
            onLongClickLabel = onLongClickLabel, onLongClick = longPress)) {
            if (kind == MediaKind.Tgs) AnimatedSticker(local, label) else InlineAnimatedImage(local, label)
        }
    }
    if (thumbnail != null) {
        Image(thumbnail.asImageBitmap(), label, Modifier.widthIn(max = if (usePlaybackPoster) 420.dp else 260.dp).fillMaxWidth()
            .aspectRatio((thumbnail.width.toFloat() / thumbnail.height).coerceIn(0.8f, 1.8f))
            .clip(RoundedCornerShape(10.dp)).combinedClickable(onClickLabel = "Open $label", onClick = open,
                onLongClickLabel = onLongClickLabel, onLongClick = longPress),
            contentScale = ContentScale.Fit)
    }
    val playLabel = when (kind) {
        MediaKind.Video -> "Play video"
        else -> null
    }
    if (playLabel != null || (thumbnail == null && !inlineAnimation)) {
        val icon = when (kind) {
            MediaKind.Audio -> Icons.Outlined.AudioFile
            MediaKind.Video -> Icons.Outlined.VideoFile
            MediaKind.Photo, MediaKind.Svg, MediaKind.Gif, MediaKind.AnimatedWebP, MediaKind.Tgs -> Icons.Outlined.Image
            null -> Icons.AutoMirrored.Outlined.InsertDriveFile
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (local != null && playLabel != null) {
                Surface(shape = CircleShape, color = AccentPanel, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Box(Modifier.size(48.dp).combinedClickable(onClick = open, onLongClick = longPress,
                        onLongClickLabel = onLongClickLabel), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PlayArrow, playLabel, Modifier.size(27.dp))
                    }
                }
            } else Icon(icon, null, Modifier.size(23.dp))
            Text(label, modifier = Modifier.weight(1f).combinedClickable(onClick = open, onLongClick = longPress,
                onLongClickLabel = onLongClickLabel), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (received && local == null) {
        val state = downloadState(data)
        val bytes = data.optLong("size")
        if (state == "Downloading") {
            if (bytes > 0 && data.has("offset")) {
                val fraction = (data.optLong("offset").toDouble() / bytes).coerceIn(0.0, 1.0)
                LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("Downloading · " + (fraction * 100).toInt() + "%", fontSize = 12.sp)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Downloading…", fontSize = 12.sp)
            }
        } else Text(when (state) {
            "Withdrawn" -> "Attachment removed"
            "Complete", "Expired" -> "Attachment unavailable"
            "Waiting to connect" -> state
            "Paused" -> "Download paused"
            else -> "Tap to download"
        }, fontSize = 12.sp)
    }
}

@Composable
private fun QuotedMessage(author: String, text: String) {
    Column(Modifier.fillMaxWidth().background(Edge, RoundedCornerShape(9.dp)).padding(8.dp)) {
        Text(author, color = Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (text.isNotBlank()) Text(text, color = Ink, style = contentTextStyle(MaterialTheme.typography.bodySmall),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StarButton(starred: Boolean, item: String, toggle: () -> Unit) {
    IconButton(onClick = toggle) {
        Icon(if (starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
            if (starred) "Unstar " + item else "Star " + item,
            Modifier.size(25.dp), tint = LocalContentColor.current)
    }
}

@Composable
private fun MediaGallery(
    records: List<Record>, localFile: (Record) -> File?, open: (String) -> Unit, star: (String) -> Unit
) {
    val saveMedia = LocalMediaDownload.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(130.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(records, key = { it.key }) { record ->
            val data = record.data()
            val path = localFile(record)
            val label = data.optString("name", "Media")
            val kind = mediaKind(data.optString("mime"), label)
            val download = saveMedia?.let { save -> { save(MediaDownload.attachment(record)) } }
            val isVideo = kind == MediaKind.Video
            val thumbnail = mediaThumbnail(path, kind)
            Surface(shape = RoundedCornerShape(15.dp), color = Edge, contentColor = Ink) {
                Column {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Edge)
                        .combinedClickable(onClickLabel = "Open $label", onClick = { open(record.key) },
                            onLongClickLabel = "Download media", onLongClick = download), contentAlignment = Alignment.Center) {
                        val image = thumbnail
                        if (image != null) Image(image.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(when (kind) {
                            MediaKind.Audio -> Icons.Outlined.AudioFile
                            MediaKind.Video -> Icons.Outlined.VideoFile
                            else -> Icons.Outlined.Image
                        }, null, tint = readableAccent(Panel, Ink))
                        if (isVideo && image != null) Icon(Icons.Outlined.PlayCircle, "Video", tint = Color.White)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).heightIn(min = 48.dp).combinedClickable(onClick = { open(record.key) },
                            onLongClickLabel = "Download media", onLongClick = download)
                            .padding(horizontal = 9.dp), contentAlignment = Alignment.CenterStart) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        }
                        StarButton(data.optBoolean("star"), "attachment") { star(record.key) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(record: Record, person: String, click: () -> Unit) {
    val data = record.data()
    val status = if (record.kind != "file-sent") downloadState(data) else {
        val count = data.optInt("delivered")
        val targets = data.optInt("targets")
        when {
            count == 0 && data.strings("cancelledTo").isNotEmpty() -> "Canceled"
            count == 0 && targets > 0 && data.optInt("failed") >= targets -> "Not delivered"
            count == 0 && data.optBoolean("chatmailSubmitted") -> "Sent"
            count == 0 && targets > 0 -> "Sending…"
            else -> ""
        }
    }
    P2pRow(data.optString("name", "Shared file"), formatSize(data.optLong("size")) + " · " + person,
        Icons.AutoMirrored.Outlined.InsertDriveFile, trailing = {
            if (status.isNotBlank()) {
                Text(status, color = Mint, fontSize = 11.sp, maxLines = 1)
            }
        }, click = click)
}

private fun shortTime(date: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(date))

private fun shortDate(date: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(date))

private fun formatSize(bytes: Long): String {
    val scale = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < scale.lastIndex) { value /= 1024; unit++ }
    return if (unit == 0) bytes.toString() + " B" else String.format(Locale.getDefault(), "%.1f %s", value, scale[unit])
}
