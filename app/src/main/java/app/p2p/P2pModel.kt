package app.p2p

import android.app.Application
import android.net.Network
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal const val PORT = 41736
private const val RETIRED_TALK_PACKET = "talkCall"

internal data class InviteImportState(
    val busy: Boolean = false, val message: String? = null, val failed: Boolean = false, val peer: String? = null,
    val joinedChannel: Int? = null, val joinedChat: Int? = null, val joinedGroup: Boolean = false
)
internal const val INVITE_CONNECTING = "Waiting to reach the other device"
internal const val INVITE_APPROVAL = "Waiting for their approval"
internal const val INVITE_REJECTED = "Invite declined, canceled, or expired"
internal const val INVITE_APPROVAL_STALE = "Waiting to reconnect and confirm the request"
internal const val INVITE_DIRECT_FAILED = "No response from the other device. P2P will retry."
private const val MAX_MESSAGE_LENGTH = 12_000

internal fun inviteRequestStatus(data: JSONObject, now: Long): String {
    val state = data.optString("state", INVITE_CONNECTING)
    if (state == INVITE_REJECTED) return "This invite is no longer valid. Ask for a fresh invite."
    if (data.optLong("expires", Long.MAX_VALUE) <= now) {
        if (data.optInt("chatmailChat") > 0 && state in setOf(INVITE_APPROVAL, CHATMAIL_REQUEST_SENT)) {
            return "Invite expired. An approval sent before expiry can still arrive."
        }
        return "Invite expired. Ask for a fresh invite."
    }
    if (data.has("chatmailChat")) return when (state) {
        CHATMAIL_REQUEST_QUEUED, CHATMAIL_REQUEST_SENT -> "Connecting…"
        else -> state
    }
    if (state == INVITE_CONNECTING && data.has("routes") && data.strings("routes").isEmpty() &&
        !data.optBoolean("hasDiscoveryPeers")) {
        return "Invite has no connection address. Ask for a fresh invite while the other device is online."
    }
    val acknowledged = data.optLong("acknowledgedAt")
    return if (state == INVITE_APPROVAL && (acknowledged <= 0L || now - acknowledged >= 45_000L)) {
        INVITE_APPROVAL_STALE
    } else state
}

internal fun isActiveInviteRequest(data: JSONObject, now: Long): Boolean =
    data.optLong("expires", Long.MAX_VALUE) > now && data.optString("state") != INVITE_REJECTED

internal fun keepOutgoingInvite(local: String, remote: String, request: JSONObject, now: Long): Boolean =
    local > remote && isActiveInviteRequest(request, now)

internal fun Record.isThreadMessage() = (kind == "message" || kind == "file-sent" || kind == "file-received") && !data().optBoolean("voicemail") && !data().optBoolean("locallyDeleted")

internal fun Record.isIncomingThreadItem(peer: String): Boolean = isThreadMessage() &&
    (kind == "file-received" || (kind == "message" && data().optString("author").let { it.isNotBlank() && it != peer }))

internal fun shouldApplyReaction(current: JSONObject?, deviceRevision: Long, sequence: Long): Boolean {
    val previousDevice = current?.optLong("deviceRevision") ?: 0L
    val previousSequence = current?.optLong("sequence") ?: 0L
    return deviceRevision > previousDevice ||
        (deviceRevision == previousDevice && (sequence > previousSequence || (sequence == 0L && previousSequence == 0L)))
}

internal fun groupPageMatches(page: JSONObject, previous: JSONObject): Boolean =
    page.optInt("parts") == previous.optInt("parts") &&
        page.optString("name").take(80) == previous.optString("name").take(80) &&
        page.optString("secret") == previous.optString("secret")

internal fun matchesInviteToken(record: JSONObject?, token: String): Boolean =
    token.length == 32 && record?.optString("token") == token

internal const val CHUNK = 393_216
internal val messageReactions = listOf("❤️", "👍", "😂", "🙌", "😮", "😢")
private const val SEND_TIMEOUT = 22_000L
private const val CHATMAIL_DEFER_WINDOW = 7 * 24 * 60 * 60 * 1_000L
private val notificationTypes = setOf("message", "messageReaction", "offer", "friendRequest", "friendResponse", "post", "reaction", "reply")

internal data class SheetSubmission(val sheet: String, val recipient: String? = null, val finished: Boolean = false,
    val error: String? = null)

class P2pModel internal constructor(application: Application, internal val profileId: String = PRIMARY_PROFILE,
    private val backgroundOnly: Boolean = false,
    private val profileCallEvent: (String, ChatmailCallEvent, ChatmailCallContact?) -> Unit = { _, _, _ -> }
) : AndroidViewModel(application) {
    private val context = application
    internal val storage = ProfileStorage(application, profileId)
    @Volatile internal var retiring = false
        private set
    private val database = LocalStore.open(application, profileId)
    private val records = database.records()
    private val devices = DeviceDirectory(records)
    @Volatile private var credentials: DeviceAccount? = null
    @Volatile private var identityProblem: String? = null
    val items = records.observe().stateIn<List<Record>?>(viewModelScope, SharingStarted.Eagerly, null)
    val peer = MutableStateFlow("")
    val notice = MutableStateFlow("")
    private val submission = MutableStateFlow<SheetSubmission?>(null)
    internal val sheetSubmission = submission.asStateFlow()
    private val draftSendState = MutableStateFlow(false)
    internal val sendingDraft = draftSendState.asStateFlow()
    internal val attachmentDrafts = androidx.compose.runtime.mutableStateMapOf<String, List<AttachmentDraft>>()
    private val recoveryQueued = AtomicBoolean(false)
    private val recoveryLock = Mutex()
    private var pendingBackupPassword: CharArray? = null
    private val backupWorking = MutableStateFlow(false)
    internal val backupBusy = backupWorking.asStateFlow()
    private val backupStaging get() = storage.noBackup("restore-attachments")
    private val rendezvousLookups = mutableMapOf<String, Pair<String, String>>()
    private val observedRoutes = linkedMapOf<String, Long>()
    private val punches = linkedMapOf<String, Punch>()
    private data class Punch(val at: Long, val candidates: List<String>, val listener: Boolean)
    private var nextDiscovery = 0L
    private var discoveryIndex = 0
    private var nextMediaCleanup = 0L
    private var nextInviteCleanup = 0L
    private var lastTraffic = 0L
    private var nextNetworkCheck = 0L
    private data class Attempt(val ticket: String, val started: Long, val offset: Long?, val account: String, val device: String)
    private val pending = mutableMapOf<String, Attempt>()
    private val downloadLock = Mutex()
    private val outboxLock = Mutex()
    private val alerts = LocalAlerts(application, profileId)
    private val incomingCallRoutes = ProfileCallInbox(application)
    private val notificationsMuted = MutableStateFlow(AppAlertMute.isMuted(context))
    internal val appNotificationsMuted = notificationsMuted.asStateFlow()
    internal val talk = TalkCalls(application, viewModelScope, ::resolveTalkContact, ::talkIce,
        { chat, offer -> withChatmail { it.placeCall(chat, offer) } },
        { id, answer -> withChatmail { it.acceptCall(id, answer) } },
        { id -> withChatmail { it.endCall(id) } },
        ::announceTalkCall, { call -> alerts.clear(AlertArea.TALK, call) },
        { call -> incomingCallRoutes.withdraw(profileId, token = call) },
        { call -> save("call:" + call.id, "call-history", obj("contact" to call.contact,
            "name" to call.displayName, "outgoing" to call.outgoing, "detail" to call.detail,
            "unread" to (!call.outgoing && call.connectedAt == 0L))) })

    private suspend fun resolveTalkContact(contact: String): ChatmailCallContact {
        val chat = if (contact.startsWith("chatmail:")) contact.substringAfter(':').toIntOrNull()
        else records.get(contact)?.takeIf { it.kind == "contact" && !it.data().optBoolean("blocked") }
            ?.data()?.let { it.optInt("nativeChat").takeIf { id -> id > 9 } ?: it.optInt("chatmailChat") }
        require(chat != null && chat > 9) { "Exchange a Chatmail invite before calling this contact." }
        return withChatmail { core ->
            check(!isBlockedChatmailContact(chat)) { "Unblock this contact before calling." }
            requireNotNull(core.callContact(chat)) {
                "An accepted, encrypted contact is required to call."
            }
        }
    }

    private suspend fun isBlockedChatmailContact(chat: Int): Boolean =
        records.get(nativeThreadKey(chat))?.data()?.optBoolean("blocked") == true ||
            records.all("contact").any {
                val data = it.data()
                data.optBoolean("blocked") &&
                    (data.optInt("nativeChat") == chat || data.optInt("chatmailChat") == chat)
            }

    private val webxdcEventFlow = kotlinx.coroutines.flow.MutableSharedFlow<JSONObject>(extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    internal val webxdcEvents = webxdcEventFlow.asSharedFlow()

    private val pinEventFlow = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    internal val pinEvents = pinEventFlow.asSharedFlow()

    private val incomingCoreEvents = Channel<Pair<Chatmail, ChatmailCallEvent>>(Channel.UNLIMITED)

    private fun openChatmail(directory: File): Chatmail {
        directory.parentFile?.mkdirs()
        return Chatmail(directory).also(::listenForChatmailEvents)
    }

    private fun listenForChatmailEvents(core: Chatmail) {
        core.listenForEvents(onMessagesChanged = { changed ->
            nativeChangedMessages.addAll(changed)
            nextNativeSync = 0L
        }, onPinsChanged = { chat ->
            if (chatmail === core) pinEventFlow.tryEmit(chat)
        }, onWebxdc = { event ->
            if (chatmail === core) webxdcEventFlow.tryEmit(event)
        }) { event -> incomingCoreEvents.trySend(core to event) }
    }

    private suspend fun handleChatmailCall(core: Chatmail, event: ChatmailCallEvent) {
        val caller = withContext(Dispatchers.IO) {
            chatmailLock.withLock {
                if (retiring || chatmail !== core) return@withLock null
                if (event.kind == "IncomingCall" && !isBlockedChatmailContact(event.chatId)) {
                    core.callContact(event.chatId)?.takeIf { core.isRingingCall(event.id) }
                } else null
            }
        }
        if (retiring || chatmail !== core) return
        profileCallEvent(profileId, event, caller)
        if (!backgroundOnly) talk.event(event, caller)
    }

    internal suspend fun resumeProfileCall(call: ProfileIncomingCall): String? = withActiveSync {
        check(call.profile == profileId)
        val incoming = withChatmail { core ->
            val event = core.ringingOffer(call)
            val caller = if (event != null && !isBlockedChatmailContact(call.chat)) core.callContact(call.chat) else null
            if (event != null && caller != null) event to caller else null
        } ?: return@withActiveSync null
        val pending = talk.incomingIdFor(call.message)
        if (pending != null) return@withActiveSync pending
        if (talk.state.value?.phase?.let { it != TalkPhase.Finished } == true) return@withActiveSync null
        talk.event(incoming.first, incoming.second, call.token, call.expiresAt)
        talk.incomingIdFor(call.message)
    }

    internal suspend fun isProfileCallRinging(call: ProfileIncomingCall): Boolean = withActiveSync {
        check(call.profile == profileId)
        withChatmail { core -> core.isRingingCall(call.message) }
    }

    internal suspend fun declineProfileCall(call: ProfileIncomingCall) = withActiveSync {
        check(call.profile == profileId)
        withChatmail { core ->
            if (core.isRingingCall(call.message)) core.endCall(call.message)
        }
    }

    private fun announceTalkCall(call: String, expiresAt: Long) {
        val name = talk.state.value?.displayName.orEmpty().ifBlank { "Chatmail contact" }
        if (talk.state.value?.let { it.id == call && it.phase == TalkPhase.Incoming } != true ||
            System.currentTimeMillis() >= expiresAt) return
        if (visible && !QuietHours.read(context).active()) {
            AppAlertMute.deliver(context, talk::ring)
        } else if (!visible) {
            val route = talk.incomingRoute(profileId)?.takeIf { it.token == call } ?: return
            if (incomingCallRoutes.save(route)) {
                alerts.show(AlertArea.TALK, call, name, "Incoming Talk call", expiresAt)
            }
        }
    }

    private suspend fun talkIce(): String = withContext(Dispatchers.IO) {
        // Existing direct calls can still negotiate locally when the relay supplies no ICE servers.
        val name = requireChatmailName()
        chatmailLock.withLock {
            try {
                connectedChatmail(name).iceServers()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.record(context, "Talk: couldn’t load Chatmail ICE servers", error)
                "[]"
            }
        }
    }

    private data class SyncDemand(val foreground: Boolean = false, val jobs: Int = 0) {
        val active: Boolean get() = foreground || jobs > 0
    }

    private val syncDemand = MutableStateFlow(SyncDemand())
    private val visible: Boolean get() = syncDemand.value.foreground
    @Volatile private var visibleThread: String? = null
    private val retryAfter = mutableMapOf<String, Long>()
    private val outgoing = storage.files("sent").apply { mkdirs() }
    private val incoming = storage.files("received").apply { mkdirs() }
    private val partial = storage.files("partial").apply { mkdirs() }

    private val chatmailLock = Mutex()
    @Volatile private var chatmail: Chatmail? = null
    private var chatmailNetwork: Network? = null
    @Volatile private var selectedNetwork: EngineRoute? = null
    @Volatile private var nextNativeSync = 0L
    private val nativeChangedMessages = ConcurrentHashMap.newKeySet<Int>()
    private var nextChatmailPoll = 0L
    private var nextChatmailConnect = 0L
    private var chatmailCursor: Int? = null
    private val chatmailState = MutableStateFlow("Set your display name to start Chatmail")
    internal val chatmailStatus = chatmailState.asStateFlow()

    private val inviteImportState = MutableStateFlow(InviteImportState())
    internal val inviteImport = inviteImportState.asStateFlow()
    private val listenerState = MutableStateFlow<Set<String>>(emptySet())
    internal val listeners = listenerState.asStateFlow()

    private val network = AppNetwork(application)
    private data class EngineRoute(val network: Network, val addresses: List<String>)
    @Volatile private var engineRoute: EngineRoute? = null

    init {
        viewModelScope.launch {
            for ((core, event) in incomingCoreEvents) {
                try {
                    handleChatmailCall(core, event)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AppLog.record(context, "Chatmail call event couldn’t be handled.", failure)
                }
            }
        }
        work {
            val networkChanges = Channel<Unit>(Channel.CONFLATED)
            val networkObserver = runCatching {
                network.observeDefaultNetwork { networkChanges.trySend(Unit) }
            }.getOrNull()
            var migratedDownloads = false
            try {
                while (true) {
                    if (!syncDemand.value.active) {
                        closeChatmail()
                        stopEngine()
                        selectedNetwork = null
                        nextNetworkCheck = 0L
                        nextChatmailPoll = 0L
                        nextChatmailConnect = 0L
                        syncDemand.first { it.active }
                    }
                    try {
                        if (!migratedDownloads) {
                            migrateLegacyDownloads()
                            migratedDownloads = true
                        }
                        if (credentials == null || recoveryQueued.get()) {
                            val started = initializeAccount()
                            if (!started) {
                                delay(5_000)
                                continue
                            }
                        }
                        if (networkChanges.tryReceive().isSuccess) {
                            nextNetworkCheck = 0L
                            nextChatmailPoll = 0L
                            nextChatmailConnect = 0L
                        }
                        expireInvites()
                        try {
                            refreshEngine()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // Internet delivery uses the selected network even if the LAN listener fails.
                        }
                        try {
                            syncChatmail()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            closeChatmail()
                            nextChatmailConnect = android.os.SystemClock.elapsedRealtime() + 2_000L
                            chatmailState.value = "Chatmail sync interrupted. P2P will retry."
                        }
                        if (engineRoute != null) {
                            for (index in 0 until 64) {
                                val raw = NativeEngine.poll()
                                if (raw.isEmpty()) break
                                lastTraffic = android.os.SystemClock.elapsedRealtime()
                                handleNative(JSONObject(raw))
                            }
                            dispatch()
                            runPunches()
                            discover()
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        notice.value = error.message ?: "Sync paused. Retrying."
                    }
                    val recentlyActive = android.os.SystemClock.elapsedRealtime() - lastTraffic < 2_000
                    val interval = when {
                        recentlyActive || outboxLock.withLock { pending.isNotEmpty() } -> 25L
                        visible || talk.state.value?.phase in setOf(TalkPhase.Incoming, TalkPhase.Connecting, TalkPhase.Active) -> 300L
                        else -> 1_000L
                    }
                    if (withTimeoutOrNull(interval) { networkChanges.receive() } != null) {
                        nextNetworkCheck = 0L
                        nextChatmailPoll = 0L
                        nextChatmailConnect = 0L
                    }
                }
            } finally {
                runCatching { networkObserver?.close() }
                networkChanges.close()
                withContext(kotlinx.coroutines.NonCancellable) {
                    closeChatmail()
                }
                if (engineRoute != null) NativeEngine.stop()
            }
        }
    }

    private suspend fun closeChatmail() = chatmailLock.withLock {
        chatmail?.let { runCatching { it.close() } }
        chatmail = null
        chatmailNetwork = null
    }

    private suspend fun initializeAccount(): Boolean = recoveryLock.withLock {
        try {
            val vault = IdentityVault(context, profileId)
            val pending = vault.pendingRecovery()
            if (pending != null) {
                credentials = null
                peer.value = ""
                chatmailLock.withLock {
                    chatmail?.let { runCatching { it.close() } }
                    chatmail = null
                    chatmailNetwork = null
                    chatmailCursor = null
                    nextChatmailConnect = 0L
                }
                stopEngine()
                applyRecovery(vault, pending)
            }
            loadAccount(vault)
            identityProblem = null
            recoveryQueued.set(false)
            if (pending != null) notice.value = "Backup restored. Exchange new invites to reconnect through Chatmail."
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            reportIdentityProblem("P2P couldn’t open this phone’s identity. Unlock the phone and try again.")
            false
        } catch (_: LinkageError) {
            reportIdentityProblem("P2P couldn’t start its connection engine on this device.")
            false
        }
    }

    private fun reportIdentityProblem(message: String) {
        if (identityProblem != message) notice.value = message
        identityProblem = message
        chatmailState.value = message
    }

    private suspend fun awaitInviteAccount(): DeviceAccount = withTimeoutOrNull(5_000L) {
        while (credentials == null && identityProblem == null) delay(150)
        credentials
    } ?: error(identityProblem ?: "P2P is still starting. Try again in a moment.")

    private suspend fun awaitInviteNetwork() {
        val ready = withTimeoutOrNull(12_000L) {
            while (selectedNetwork == null) delay(200)
            true
        }
        check(ready == true) { "Connect to the internet and try again." }
    }

    private suspend fun requireChatmailName(): String =
        records.get("profile")?.data()?.optString("name")?.takeIf(String::isNotBlank)
            ?: error("Set your display name in Settings to use Chatmail.")

    @Volatile private var initialChatmailInvite: String? = null

    internal fun hintInitialChatmailInvite(invite: String) {
        initialChatmailInvite = invite
    }

    private suspend fun connectedChatmail(name: String, setupQr: String? = null): Chatmail {
        val current = selectedNetwork?.network
            ?: error("Connect to the internet to set up Chatmail.")
        val service = chatmail ?: openChatmail(storage.noBackup("chatmail")).also { chatmail = it }
        if (chatmailNetwork != current) service.disconnect()
        val profile = records.get("profile")?.data()
        if (profile != null && service.profileNeedsSync(profile.optLong("revision"))) {
            val photo = profile.optString("photo").takeIf(String::isNotBlank)?.let(::checkedPhoto)
            val avatar = photo?.let { File.createTempFile("chatmail-avatar-", ".jpg", context.cacheDir) }
            try {
                if (photo != null) avatar?.writeBytes(photo)
                service.applyProfile(profile.optLong("revision"), profile.takeIf { it.has("bio") }?.optString("bio"),
                    avatar, profile.has("photo") && (avatar != null || profile.optString("photo").isEmpty()))
            } finally {
                avatar?.delete()
            }
        }
        val onboardingHint = initialChatmailInvite
        val candidate = setupQr ?: onboardingHint?.takeIf { raw ->
            runCatching { service.inviteKind(raw) in setOf("askVerifyContact", "askJoinBroadcast") }.getOrDefault(false)
        }
        service.connect(name, candidate)
        if (onboardingHint == initialChatmailInvite) initialChatmailInvite = null
        chatmailNetwork = current
        return service
    }

    internal suspend fun createSecureJoinInvite(): String =
        withChatmail(requireNetwork = true) { it.secureJoinInvite() }

    private suspend fun <T> withChatmail(
        requireNetwork: Boolean = false, setupQr: String? = null, action: suspend (Chatmail) -> T
    ): T = withContext(Dispatchers.IO) {
        check(!retiring) { "Relay Lab is switching profiles." }
        awaitInviteAccount()
        if (requireNetwork) awaitInviteNetwork()
        val name = requireChatmailName()
        chatmailLock.withLock {
            check(!retiring) { "Relay Lab is switching profiles." }
            check(!recoveryQueued.get() && credentials != null) { "Relay Lab is restoring your identity. Try again in a moment." }
            val directory = storage.noBackup("chatmail")
            val service = if (selectedNetwork != null || requireNetwork) connectedChatmail(name, setupQr)
            else chatmail ?: directory.takeIf(File::isDirectory)?.let { openChatmail(it).also { opened -> chatmail = opened } }
            check(service != null && service.isConfigured()) { "Connect to the internet to set up Chatmail." }
            action(service)
        }
    }

    private suspend fun signedChatmailIds(): Set<Int> =
        listOf("contact", "invite-in", "invite-pending").flatMap { records.all(it) }
            .mapTo(mutableSetOf()) { it.data().optInt("chatmailChat") }

    internal suspend fun nativeContacts(): List<ChatmailContact> = withChatmail { core ->
        val hiddenIds = signedChatmailIds().filter { it > 9 }
            .mapNotNullTo(mutableSetOf()) { runCatching { core.chatContactId(it) }.getOrNull() }
        records.all("contact").forEach { record ->
            val data = record.data()
            if (data.optBoolean("blocked")) data.optInt("nativeContact").takeIf { it > 9 }?.let(hiddenIds::add)
        }
        core.contacts().filterNot { it.id in hiddenIds }
    }

    internal suspend fun openNativeContact(contact: Int): Int = withChatmail { core ->
        core.openContact(contact).also { id ->
            core.nativeThread(id)?.let { cacheNativeThread(it) }
            nextNativeSync = 0L
        }
    }

    internal suspend fun nativeGroupContacts(): List<Record> = withChatmail { core ->
        core.contacts().map { contact ->
            Record(nativePeerKey(contact.id), "contact", obj("name" to contact.name).toString(), 0L)
        }
    }

    internal fun acceptNativeThread(thread: String, chat: Int) = work {
        withChatmail { core ->
            core.nativeAccept(chat)
            core.nativeThread(chat)?.let { cacheNativeThread(it, thread) }
            nextNativeSync = 0L
        }
    }

    internal suspend fun chatmailDescription(id: Int) = withChatmail { it.chatDescription(id) }

    internal fun saveNativeGroup(
        chat: Int?, name: String, description: String?, members: Set<String>, cloneFrom: Int? = null
    ) = submitSheet(SheetSubmission(when {
        cloneFrom != null -> "group-clone"
        chat != null -> "group-edit"
        else -> "group-new"
    })) {
            val title = name.trim()
            require(title.isNotBlank() && title.length <= 80) { "Enter a group name of up to 80 characters." }
            val details = description?.let(::checkedChatDescription)
            val ids = members.filter { it.startsWith("chatmail-peer:") }
                .mapNotNull { it.substringAfter(':').toIntOrNull()?.takeIf { id -> id > 9 } }.toSet()
            require(chat != null || ids.isNotEmpty()) { "Choose at least one contact." }
            withChatmail { core ->
                val id = when {
                    cloneFrom != null -> {
                        val original = requireNotNull(core.nativeThread(cloneFrom)?.takeIf { it.group && !it.request }) {
                            "This group is unavailable."
                        }
                        val allowed = core.contacts().mapTo(mutableSetOf(), ChatmailContact::id)
                        require(ids.all { it in allowed }) { "Choose contacts available on this profile." }
                        val photo = original.imagePath?.let(storage::chatmailFile)
                        core.nativeCreateGroup(title, details ?: core.chatDescription(original.id), ids, photo)
                    }
                    chat == null -> core.nativeCreateGroup(title, details.orEmpty(), ids)
                    else -> chat.also { core.nativeUpdateGroup(it, title, details, ids) }
                }
                core.nativeThread(id)?.let { cacheNativeThread(it) }
                nextNativeSync = 0L
                nativeThreadKey(id)
            }
        }

    internal suspend fun updateNativeGroupImage(thread: String, uri: Uri?) = withChatmail { core ->
        val chat = writableNativeChat(core, thread)
        check(chat.group) { "This conversation is not a group." }
        withPreparedChatImage(uri) { file -> core.setChatImage(chat.id, file) }
        core.nativeThread(chat.id)?.let { cacheNativeThread(it, thread) }
        nextNativeSync = 0L
    }

    private fun withPreparedChatImage(uri: Uri?, update: (File?) -> Unit) {
        val file = uri?.let {
            val photo = requireNotNull(checkedPhoto(prepareProfilePhoto(context, it)))
            File.createTempFile("chat-photo-", ".jpg", context.cacheDir).also { file ->
                try { file.writeBytes(photo) }
                catch (failure: Exception) { file.delete(); throw failure }
            }
        }
        try {
            update(file)
        } finally {
            file?.delete()
        }
    }

    internal suspend fun nativeGroupInvite(chat: Int): String = withChatmail { it.nativeGroupInvite(chat) }

    internal fun addChatmailRelay(code: String, saved: () -> Unit) = work {
        withChatmail { it.addRelay(code.trim()) }
        withContext(Dispatchers.Main) { saved() }
    }

    private suspend fun cacheChannelAttention(channels: List<ChatmailChannel>) {
        cacheNativeRecord("channel-attention", "channel-attention",
            obj("unread" to channels.any { !it.owned && it.unread > 0 }), 0L)
    }

    internal suspend fun chatmailChannels() = withChatmail { core ->
        core.channels().also { cacheChannelAttention(it) }
    }

    internal suspend fun chatmailChannelHistory(id: Int, limit: Int, reveal: Int? = null) = withChatmail { core ->
        val channel = requireNotNull(core.channel(id)) { "Waiting for the channel owner to process your join request." }
        core.noticeChannel(channel)
        cacheChannelAttention(core.channels())
        val history = core.channelPosts(channel, limit, reveal)
        history.copy(posts = history.posts.map(::channelPostMedia), pinned = core.pinnedChannelPosts(channel))
    }

    internal suspend fun chatmailChannelFeed(channels: List<ChatmailChannel>, limit: Int) =
        withChatmail { core ->
            channels.asSequence().filterNot { it.owned }.flatMap { core.channelPosts(it, limit).posts }
                .sortedWith(compareByDescending<ChannelPost> { it.time }.thenByDescending { it.id })
                .take(limit).map(::channelPostMedia).toList()
        }

    private fun channelPostMedia(post: ChannelPost): ChannelPost =
        post.copy(localFile = post.attachment?.let(::availableChatmailFile))

    internal suspend fun saveChannelPost(id: Int) = collectionMutex.withLock {
        withChatmail { core ->
            requireNotNull(core.channelPost(id)) { "This post is unavailable." }
            val savedId = core.message(id).optInt("savedMessageId")
            if (savedId > 9) removeSavedId(core, savedId) else {
                core.saveMessage(id)
                check(core.message(id).optInt("savedMessageId") > 9) { "The post could not be saved. Please try again." }
            }
            notice.value = if (savedId > 9) "Removed from Saved." else "Saved."
            val chat = requireNotNull(core.nativeThread(core.savedMessagesChat()))
            cacheNativeThread(chat)
            refreshAfterNativeSend(core, chat.id, nativeThreadKey(chat.id))
        }
    }

    internal suspend fun requestChannelAttachment(id: Int) = withChatmail { core ->
        val post = channelAttachment(core, id)
        val attachment = requireNotNull(post.attachment) { "This post no longer has an attachment." }
        if (post.localFile != null || attachment.downloadState == "InProgress") return@withChatmail
        require(attachment.size <= NATIVE_ATTACHMENT_FILE_LIMIT) { "This attachment exceeds the 1 GiB limit." }
        check(attachment.canRequestDownload) { "This attachment is unavailable." }
        core.download(id)
    }

    private fun channelAttachment(core: Chatmail, id: Int): ChannelPost =
        channelPostMedia(requireNotNull(core.channelPost(id)) { "This post is unavailable." })

    private fun availableChatmailFile(attachment: ChatmailAttachment): File? =
        attachment.path?.let(storage::chatmailFile)?.takeIf { it.length() in 0..NATIVE_ATTACHMENT_FILE_LIMIT }

    internal suspend fun createChatmailChannel(name: String) =
        withChatmail { it.createChannel(checkedChannelName(name)) }

    internal suspend fun updateChatmailChannel(id: Int, name: String, description: String) =
        withChatmail {
            it.updateChannel(id, checkedChannelName(name), checkedChatDescription(description))
        }

    private fun checkedChatDescription(description: String): String = description.trim().also {
        require(it.length <= INTEROP_TEXT_LIMIT) { "The description is too long." }
    }

    internal suspend fun setChatmailChannelImage(id: Int, uri: Uri?) = withChatmail { core ->
        withPreparedChatImage(uri) { file -> core.setChannelImage(id, file) }
    }

    internal suspend fun chatmailChannelImage(path: String): String = withContext(Dispatchers.IO) {
        val file = storage.chatmailFile(path)?.takeIf { it.length() in 1..NATIVE_ATTACHMENT_FILE_LIMIT }
        if (file != null) prepareProfilePhoto(context, Uri.fromFile(file)) else ""
    }

    internal suspend fun chatmailChannelViewCount(post: Int): Int = withChatmail { it.channelViewCount(post) }

    internal suspend fun pinChatmailChannel(id: Int, pinned: Boolean) =
        withChatmail { it.pinChannel(id, pinned) }

    private fun checkedChannelName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= 80) { "Enter a channel name of up to 80 characters." }
    }

    internal suspend fun chatmailChannelInvite(id: Int) = withChatmail { core ->
        core.channelInvite(requireNotNull(core.channel(id)) { "Channel unavailable." })
    }

    internal suspend fun publishChatmailChannelPost(id: Int, text: String, attachments: List<AttachmentDraft>) =
        withContext(Dispatchers.IO) {
            require(text.isNotBlank() || attachments.isNotEmpty()) { "Add text or media before publishing." }
            sendChatmailDraft(id, nativeThreadKey(id), text.trim(), null, attachments, channelPost = true)
            deleteTemporaryDrafts(attachments)
        }

    private suspend fun importSecureJoinInvite(code: String, channelOnly: Boolean): InviteImportState =
        withChatmail(requireNetwork = true, setupQr = code) { core ->
            val kind = core.inviteKind(code)
            val channel = kind == "askJoinBroadcast"
            val group = kind == "askVerifyGroup"
            require(channel || (!channelOnly && (kind == "askVerifyContact" || group))) {
                if (channelOnly) "This isn’t a channel invite." else "This isn’t a contact, group, or channel invite."
            }
            val chat = core.joinChecked(code)
            check(chat > 9) { "Chatmail couldn’t open this conversation." }
            if (channel) InviteImportState(joinedChannel = chat)
            else {
                core.nativeThread(chat)?.let { cacheNativeThread(it) }
                nextNativeSync = 0L
                InviteImportState(message = "SecureJoin started. Keep both apps online while they verify the connection.",
                    joinedChat = chat, joinedGroup = group)
            }
        }

    private suspend fun importChatmailInvite(text: String): InviteImportState {
        val bytes = chatmailInviteBytes(text)
        val signed = JSONObject(bytes.toString(Charsets.UTF_8))
        val body = signed.getString("payload")
        val invite = JSONObject(body)
        val grant = devices.checked(invite.getString("grant")) ?: error("Invalid contact credential")
        val id = grant.account
        val token = invite.getString("token")
        val expires = invite.getLong("expires")
        require(invite.optInt("v") == 3 && invite.optString("peer") == id &&
            token.matches(Regex("[A-Za-z0-9_-]{32}")) &&
            NativeEngine.verify(body, grant.device, signed.optString("public"), signed.optString("signature"))) {
            "This invitation could not be verified."
        }
        require(expires > System.currentTimeMillis()) { "This invite expired. Ask for a fresh invite." }
        val qr = invite.getString("chatmail")
        require(isChatmailInviteLink(qr)) {
            "Invalid Chatmail invite."
        }
        require(id != awaitInviteAccount().grant.account) { "This is your own invite. Use the invite from the other device." }
        val name = requireChatmailName()
        awaitInviteNetwork()
        return chatmailLock.withLock {
            check(!recoveryQueued.get() && credentials != null) { "Relay Lab is restoring your identity. Try again in a moment." }
            require(id != peer.value) { "This is your own invite. Use the invite from the other device." }
            require(expires > System.currentTimeMillis()) { "This invite expired. Ask for a fresh invite." }
            val contact = records.get(id)?.takeIf { it.kind == "contact" }?.data()
            require(contact?.optBoolean("blocked") != true) { "Unblock this contact before accepting their invite." }
            if ((contact?.optInt("chatmailChat") ?: 0) > 0 &&
                contact?.optLong("chatmailRevision") == grant.revision) return@withLock InviteImportState(peer = id)
            val requestKey = "request:" + id
            val existing = records.get(requestKey)
            if (existing?.kind == "invite-in") return@withLock InviteImportState(message = "You already have a request from this person. Open Contacts to accept it.")
            if (existing?.kind == "invite-pending" && matchesInviteToken(existing.data(), token)) {
                val request = existing.data()
                require(isActiveInviteRequest(request, System.currentTimeMillis())) {
                    inviteRequestStatus(request, System.currentTimeMillis())
                }
                return@withLock InviteImportState(peer = id)
            }
            val known = devices.find(id)
            require(grantDecision(known?.device.orEmpty(), known?.revision ?: 0L, grant.device, grant.revision) != GrantDecision.REJECT) {
                "This invite is from a replaced device. Ask for a fresh invite."
            }
            val chat = connectedChatmail(name).joinLegacyContact(qr)
            check(chat > 9) { "Chatmail couldn’t create this conversation." }
            check(devices.accept(grant)) { "This invite is from a replaced device. Ask for a fresh invite." }
            database.withTransaction {
                val current = records.get(id)?.takeIf { it.kind == "contact" }?.data()
                require(current?.optBoolean("blocked") != true) { "Unblock this contact before accepting their invite." }
                if ((current?.optInt("chatmailChat") ?: 0) > 0 &&
                    current?.optLong("chatmailRevision") == grant.revision) return@withTransaction
                if (records.get(requestKey)?.kind == "invite-in") {
                    error("You already have a request from this person. Open Contacts to accept it.")
                }
                retireInviteHellos(id)
                save(requestKey, "invite-pending", obj("peer" to id, "token" to token, "expires" to expires,
                    "name" to invite.optString("name", "P2P contact").take(60), "chatmailChat" to chat,
                    "chatmailRevision" to grant.revision, "state" to CHATMAIL_REQUEST_QUEUED))
                queue(id, obj("type" to "hello", "id" to newId(), "name" to name, "token" to token,
                    "expires" to expires), chat)
            }
            InviteImportState(peer = id)
        }
    }

    private suspend fun chatmailTarget(data: JSONObject, to: String): Int {
        val contact = records.get(to)?.takeIf { it.kind == "contact" }?.data()
        return chatmailRoute(data, contact)
    }

    private suspend fun syncChatmail() = chatmailLock.withLock {
        val name = records.get("profile")?.data()?.optString("name").orEmpty()
        val route = selectedNetwork
        if (route == null || name.isBlank()) {
            chatmail?.disconnect()
            chatmailNetwork = null
            chatmailState.value = if (name.isBlank()) "Set your display name to start Chatmail" else "Waiting for internet"
            return@withLock
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < nextChatmailPoll || now < nextChatmailConnect) return@withLock
        nextChatmailPoll = now + 1_000L
        val service = try {
            if (chatmailNetwork != route.network) chatmailState.value = "Connecting to Chatmail"
            connectedChatmail(name)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            chatmailState.value = "Chatmail connection unavailable. P2P will retry."
            nextChatmailConnect = now + 15_000L
            return@withLock
        }
        chatmailState.value = if (service.connectivity() >= 3000) "Connected to Chatmail" else "Connecting to Chatmail"
        val cursor = chatmailCursor ?: (records.get("chatmail-cursor")?.data()?.optInt("id") ?: 0)
        val queued = service.legacyInboxAfter(cursor).take(64)
        val messages = service.messages(queued)
        for (id in queued) {
            val message = messages.optJSONObject(id.toString())
            val processed = message != null && when (message.optString("kind")) {
                "message" -> tryConsumeChatmail(service, message)
                else -> message.optString("error") == "Message does not exist"
            }
            database.withTransaction {
                if (!processed) save("chatmail-in:" + id, "chatmail-in", obj("id" to id))
                save("chatmail-cursor", "chatmail-cursor", obj("id" to id))
            }
            chatmailCursor = id
        }
        val deferred = records.all("chatmail-in").filter { it.data().getInt("id") !in queued }
        val batch = if (deferred.size > 32) {
            val start = (now / 1000 % deferred.size).toInt()
            (deferred.drop(start) + deferred.take(start)).take(32)
        } else deferred
        val deferredMessages = service.messages(batch.map { it.data().getInt("id") })
        for (entry in batch) {
            val message = deferredMessages.optJSONObject(entry.data().getInt("id").toString())
            val processed = message != null && when (message.optString("kind")) {
                "message" -> tryConsumeChatmail(service, message)
                else -> message.optString("error") == "Message does not exist"
            }
            if (processed || System.currentTimeMillis() - entry.created >= CHATMAIL_DEFER_WINDOW) records.remove(entry.key)
        }
        cancelChatmailSends(service, now)
        dispatchChatmail(service, now)
        try { updateNativeCatalog(service, now) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { AppLog.record(context, "Chatmail conversation sync failed", failure) }
    }

    private suspend fun updateNativeCatalog(core: Chatmail, now: Long) {
        if (now < nextNativeSync) return
        nextNativeSync = now + 8_000
        cacheChannelAttention(core.channels())
        val legacy = records.all("contact").mapNotNull { row ->
            row.data().optInt("chatmailChat").takeIf { it > 9 }?.let { it to row.key }
        }.toMap()
        val pending = (records.all("invite-pending") + records.all("invite-in"))
            .map { it.data().optInt("chatmailChat") }.toSet() - legacy.keys
        val threads = core.nativeThreadIds().filterNot { it in pending }.mapNotNull(core::nativeThread)
        for (chat in threads) {
            val key = legacy[chat.id] ?: nativeThreadKey(chat.id)
            val prefKey = "thread-pref:$key"
            val pref = records.get(prefKey)?.data() ?: obj()
            // Import the old local mute once. Afterwards Core is authoritative, including other devices.
            val legacyMuted = pref.optBoolean("muted") || records.get(key)?.data()?.optBoolean("alertMuted") == true
            val muted = if (!pref.optBoolean("nativeMuteSynced") && legacyMuted) {
                core.nativeMute(chat.id, true)
                if (records.get("folder:$key")?.data()?.optString("name") == "Archive") core.nativeArchive(chat.id, true)
                true
            } else chat.muted
            pref.put("muted", muted).put("nativeMuteSynced", true)
            cacheNativeRecord(prefKey, "thread-pref", pref, 0L)
            if (records.get(key)?.data()?.optBoolean("alertMuted") == true) change(key) { put("alertMuted", false) }
        }
        database.withTransaction {
            for (chat in threads) cacheNativeThread(chat, legacy[chat.id] ?: nativeThreadKey(chat.id))
            for (contact in core.contacts()) {
                val key = "peer-profile:" + nativePeerKey(contact.id)
                cacheNativeRecord(key, "peer-profile", obj("name" to contact.name), 0L)
            }
        }
        val chatsById = threads.associateBy(NativeThread::id)
        val cached = records.threadItems().filter { it.data().optInt("nativeMessage") > 9 }
            .groupBy { it.data().optString("thread") }
        val recent = threads.flatMapTo(mutableSetOf<Int>()) { chat ->
            val thread = legacy[chat.id] ?: nativeThreadKey(chat.id)
            updateNativeHistory(core, chat, thread, 25, cached = cached[thread].orEmpty())
        }
        for (id in nativeChangedMessages.toList()) {
            if (!nativeChangedMessages.remove(id) || id in recent) continue
            val thread = records.get(nativeMessageKey(id))?.data()?.optString("thread") ?: continue
            val chatId = records.get(thread)?.data()?.optInt("nativeChat") ?: continue
            val chat = chatsById[chatId] ?: continue
            updateNativeHistory(core, chat, thread, 25, messageId = id)
        }
    }

    private suspend fun cacheNativeThread(chat: NativeThread, key: String = nativeThreadKey(chat.id)) {
        val saved = records.get(key)
        val data = saved?.data() ?: obj()
        data.put("nativeChat", chat.id).put("contactRequest", chat.request).put("nativeCanSend", chat.canSend)
        data.put("nativeSelfTalk", chat.selfTalk).put("nativeSelfInGroup", chat.selfInGroup)
        if (chat.group) {
            val path = chat.imagePath
            if (path == null) {
                data.remove("nativePhotoPath")
                data.remove("photo")
            } else if (path != data.optString("nativePhotoPath")) {
                try {
                    val image = storage.chatmailFile(path)
                    if (image != null && image.length() in 1..NATIVE_ATTACHMENT_FILE_LIMIT) {
                        data.put("photo", prepareProfilePhoto(context, Uri.fromFile(image)))
                        data.put("nativePhotoPath", path)
                    }
                } catch (failure: Exception) {
                    AppLog.record(context, "Couldn’t load Chatmail group image", failure)
                }
            }
        }
        if (key == nativeThreadKey(chat.id)) {
            data.put("name", chat.name)
                .put("members", jsonArray((if (!chat.group || chat.selfInGroup) listOf(peer.value) else emptyList()) + chat.members.map(::nativePeerKey)))
                .put("owner", if (chat.canSend) peer.value else "")
            chat.contactId?.let { data.put("nativeContact", it) }
        }
        cacheNativeRecord(key, if (chat.group) "group" else "contact", data, saved?.created ?: 0L)
        if (chat.archived && records.get("folder:" + key) == null) save("folder:" + key, "folder", obj("name" to "Archive"))
    }

    private suspend fun cacheNativeRecord(key: String, kind: String, data: JSONObject, time: Long) {
        val previous = records.get(key)
        if (previous?.payload != data.toString() || previous?.kind != kind) save(key, kind, data, time)
    }

    internal fun loadOlderNativeMessages(thread: String) = work {
        withChatmail { core ->
            val data = records.get(thread)?.data() ?: return@withChatmail
            val chat = core.nativeThread(data.optInt("nativeChat")) ?: return@withChatmail
            val before = data.optInt("nativeHistoryFirst").takeIf { it > 9 } ?: return@withChatmail
            val cached = records.threadItems().filter { it.data().optString("thread") == thread }
            updateNativeHistory(core, chat, thread, 100, before, cached = cached)
        }
    }

    private suspend fun updateNativeHistory(
        core: Chatmail, chat: NativeThread, thread: String, limit: Int, before: Int? = null,
        messageId: Int? = null, cached: List<Record> = emptyList(), resumeAttachment: Boolean = false
    ): List<Int> {
        val saved = records.get(thread)?.data()
        val loaded = saved?.optInt("nativeHistoryFirst") ?: 0
        val latest = saved?.optInt("nativeHistoryLast")?.takeIf { before == null && it > 9 }
        val cachedIds = cached.mapTo(mutableSetOf()) { it.data().optInt("nativeMessage") }
        val page = if (messageId == null) core.nativeHistory(chat.id, limit, before, latest, cachedIds)
            else NativeHistory(listOfNotNull(core.nativeMessage(chat.id, messageId)), null, null, false)
        val batch = page.messages
        val missing = if (messageId == null) cached.removedChatmailMessages(thread, page.existingIds) else emptyList()
        if (batch.isEmpty() && page.first == null && before == null && missing.isEmpty()) return emptyList()
        val discardedFiles = mutableListOf<Record>()
        var newMessage: NativeMessage? = null
        var replyToSelf: NativeMessage? = null
        val preferences = records.get("thread-pref:$thread")?.data()
        val contact = records.get(thread)?.data()
        val muted = preferences?.optBoolean("muted") == true || contact?.optBoolean("alertMuted") == true
        val canNotify = preferences?.optBoolean("blocked") != true && contact?.optBoolean("blocked") != true
        val checkForReplies = chat.group && canNotify && muted && batch.any {
            it.from != 1 && it.state == 10 && it.quote != null
        } && core.mentionsNotifyWhenMuted()
        database.withTransaction {
            if (messageId == null && (loaded <= 9 || latest == null)) change(thread) {
                put("nativeHasOlder", page.hasOlder)
                if (page.first != null) put("nativeHistoryFirst", page.first)
                else if (!page.hasOlder) remove("nativeHistoryFirst")
            }
            if (before == null && latest != page.last && page.last != null) change(thread) {
                put("nativeHistoryLast", page.last)
            }
            val knownReactions = records.all("message-reaction").filter { it.data().optString("thread") == thread }
                .groupBy { it.data().optString("message") }
            for (expired in missing) {
                val current = records.get(expired.key) ?: continue
                if (current.data().optString("thread") != thread) continue
                records.remove(expired.key)
                knownReactions[expired.key].orEmpty().forEach { records.remove(it.key) }
                if (current.kind.startsWith("file-")) discardedFiles.add(current)
            }
            for (message in batch) {
                val id = nativeMessageKey(message.id)
                if (records.get("discarded-message:" + id) != null) continue
                val old = records.get(id)?.data()
                val outgoingMessage = message.from == 1
                val peerKey = nativePeerKey(message.from)
                val author = when {
                    outgoingMessage -> peer.value
                    !chat.group && isPeer(thread) -> thread
                    else -> peerKey
                }
                if (!outgoingMessage && author == peerKey && message.senderName.isNotBlank() &&
                    records.get("peer-profile:$author") == null) {
                    save("peer-profile:$author", "peer-profile", obj("name" to message.senderName), 0L)
                }
                val attachment = message.attachment
                if (before == null && old == null && message.state == 10) {
                    newMessage = message
                    if (checkForReplies && message.from != 1 && message.quote != null) {
                        val quoted = records.get(nativeMessageKey(message.quote))?.data()
                        val fromSelf = quoted?.optString("author")?.let { it == peerKey }
                            ?: runCatching { core.message(message.quote).optInt("fromId") == 1 }.getOrDefault(false)
                        if (fromSelf) replyToSelf = message
                    }
                }
                val data = old ?: obj()
                data.put("nativeMessage", message.id).put("thread", thread).put("author", author)
                    .put("text", message.text).put("caption", message.text)
                    .put("nativeEdited", message.edited).put("nativePinned", message.pinned)
                    .put("nativeSavedId", message.savedMessageId).put("nativeOriginalId", message.originalMsgId.takeIf { it > 9 } ?: data.optInt("nativeOriginalId"))
                    .put("nativeSelfTalk", chat.selfTalk)
                    .put("nativeLocation", message.hasLocation).put("nativeForwarded", message.forwarded)
                    .put("group", if (chat.group) thread else "")
                    .put("unread", !data.optBoolean("locallyDeleted") &&
                        (message.state == 10 || data.optBoolean("locallyUnread")))
                    .put("replyTo", message.quote?.let(::nativeMessageKey).orEmpty())
                    .put("quoteText", message.quoteText).put("quoteAuthor", message.quoteAuthor)
                message.webxdcParent?.let { data.put("webxdcParent", it).put("webxdcHref", message.webxdcHref.orEmpty()) }
                if (outgoingMessage) {
                    data.put("targets", 1).put("chatmailSubmitted", message.state == 26)
                        .put("delivered", if (message.state == 28) 1 else 0)
                        .put("failed", if (message.state == 24) 1 else 0)
                }
                val kind = if (attachment == null) "message" else {
                    val folder = if (outgoingMessage) outgoing else incoming
                    val paused = !outgoingMessage && data.optString("state") == "Paused" && !resumeAttachment
                    val media = attachment.cacheForMessage(File(folder, id), paused) {
                        availableChatmailFile(attachment)
                    }
                    media.keys().forEach { key -> data.put(key, media.get(key)) }
                    if (outgoingMessage) "file-sent" else "file-received"
                }
                cacheNativeRecord(id, kind, data, message.time)
                val current = mutableSetOf<String>()
                for ((contact, emojis) in message.reactions) {
                    val sender = if (contact == 1) peer.value else nativePeerKey(contact)
                    emojis.forEachIndexed { index, emoji ->
                        val key = "reaction:$id:$sender:$index"
                        current.add(key)
                        cacheNativeRecord(key, "message-reaction",
                            obj("nativeMessage" to message.id, "thread" to thread, "message" to id,
                                "id" to emoji, "author" to sender, "text" to emoji), message.time)
                    }
                }
                knownReactions[id].orEmpty().filter { it.key !in current }.forEach { records.remove(it.key) }
            }
            if (newMessage != null) {
                val key = "folder:$thread"
                val location = records.get(key)?.data()?.optString("name")
                if (shouldRestoreNativeInbox(chat.archived, muted, location,
                        groupBlocked(thread) || records.get(thread)?.data()?.optBoolean("blocked") == true)) records.remove(key)
            }
        }
        for (file in discardedFiles) {
            if (!isId(file.key)) continue
            val directory = if (file.kind == "file-sent") outgoing else incoming
            File(directory, file.key).delete()
            alerts.clear(AlertArea.FILES, file.key)
        }
        val alertMessage = if (muted) replyToSelf else newMessage
        if (alertMessage != null && canNotify) {
            val area = if (chat.group) AlertArea.GROUPS else AlertArea.CHATS
            if (visible && visibleThread == thread) alerts.playInChatSound(area)
            else {
                val preview = alertMessage.text.take(240).ifBlank { alertMessage.attachment?.name ?: "New message" }
                val sender = alertMessage.senderName.takeIf { chat.group && it.isNotBlank() }
                alerts.show(area, thread, chat.name, if (sender == null) preview else "$sender: $preview")
            }
        }
        return batch.map(NativeMessage::id)
    }

    private suspend fun tryConsumeChatmail(service: Chatmail, message: JSONObject): Boolean =
        try {
            consumeChatmail(service, message)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }

    private suspend fun consumeChatmail(service: Chatmail, message: JSONObject): Boolean {
        if (message.optInt("fromId") <= 9 || message.optBoolean("isInfo")) return true
        if (!message.optBoolean("showPadlock")) return true
        val packet = decodeChatmailPacket(message.optString("text")) ?: return true
        if (packet.optString("to") != peer.value) return true
        val grant = devices.checked(packet.optString("grant")) ?: return true
        if (grant.account == peer.value) return true
        val body = packet.optString("body")
        if (!NativeEngine.verify(body, grant.device, packet.optString("public"), packet.optString("signature"))) return true
        val signed = runCatching { JSONObject(body) }.getOrNull() ?: return true
        val transfer = signed.optString("transfer")
        if (transfer != packet.optString("transfer") || signed.optString("to") != peer.value) return true
        val payload = signed.optJSONObject("data") ?: return true
        val outbox = signed.optString("outbox")
        if (!outbox.startsWith("out:" + peer.value + ":") || digest(outbox.toByteArray()) != transfer) return true
        val chat = message.optInt("chatId")
        if (chat <= 9) return true
        val type = payload.optString("type")
        val to = grant.account
        if (type == "chatmailAck") {
            outboxLock.withLock {
                val key = payload.optString("outbox")
                if (!key.startsWith("out:" + to + ":") || digest(key.toByteArray()) != payload.optString("transfer")) return@withLock
                val row = records.get(key)?.takeIf { it.kind == "outbox" } ?: return@withLock
                val status = payload.optString("status")
                if (status !in setOf("ok", "paired", "waiting", "rejected")) return@withLock
                val queued = row.data()
                val type = queued.optJSONObject("payload")?.optString("type").orEmpty()
                if (type == "chatmailAck" || !validChatmailReceipt(type, status)) return@withLock
                if (chatmailTarget(queued, to) == chat && devices.accept(grant)) handleResult(key, status)
            }
            return true
        }
        if (type == "paired" || type == "inviteDeclined") {
            val request = records.get("request:" + to)?.takeIf { it.kind == "invite-pending" }?.data()
            val contact = records.get(to)?.takeIf { it.kind == "contact" }?.data()
            val mapped = request?.optInt("chatmailChat") ?: contact?.optInt("chatmailChat") ?: 0
            if (mapped != chat) return true
        } else if (type != "hello") {
            val mapped = records.get(to)?.takeIf { it.kind == "contact" }?.data()?.optInt("chatmailChat") ?: 0
            if (mapped != chat) {
                val request = records.get("request:" + to)
                val pendingPair = request?.data() ?: return true
                if (pendingPair.optInt("chatmailChat") != chat) return true
                return when (request.kind) {
                    "invite-pending" -> pendingPair.optString("state") == INVITE_REJECTED
                    "invite-in" -> (records.get("invite:" + pendingPair.optString("token"))
                        ?.data()?.optLong("expires") ?: 0L) <= System.currentTimeMillis()
                    else -> true
                }
            }
        }
        val download = message.optString("downloadState")
        if (download == "InProgress") return false
        if (download == "Available" && type == "offer" &&
            records.get(to)?.data()?.optBoolean("blocked") != true &&
            chatmailDownloadAllowed(payload, message)) {
            service.download(message.getInt("id"))
            return false
        }
        val file = message.optString("file").takeIf { it.isNotBlank() && it != "null" }?.let(::File)
        val wire = obj("grant" to grant.encoded, "data" to payload)
        val status = acceptWireMessage(grant.device, wire, chat, file, message.optInt("id"))
        if (status in setOf("retry", "paused", "unauthorized")) return false
        // A durable, deterministic acknowledgment can be retried after either app restarts.
        val ackId = java.util.UUID.nameUUIDFromBytes(("ack:" + transfer + ":" + status).toByteArray()).toString()
        queue(to, obj("type" to "chatmailAck", "id" to ackId, "outbox" to outbox, "transfer" to transfer,
            "status" to status), chat)
        return true
    }

    private suspend fun cancelChatmailSends(service: Chatmail, now: Long) = outboxLock.withLock {
        val due = records.all("chatmail-cancel").asSequence()
            .filter { (retryAfter[it.key] ?: 0L) <= now }.take(32)
        for (entry in due) {
            val data = entry.data()
            val chat = data.getInt("chat")
            val transfer = data.getString("transfer")
            try {
                val message = data.optInt("message").takeIf { it > 0 } ?: service.findLegacyPacket(chat, transfer)
                if (message != null) service.cancelLegacyPacket(message, chat, transfer)
                records.remove(entry.key)
                retryAfter.remove(entry.key)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                retryAfter[entry.key] = now + 15_000L
            }
        }
    }

    private suspend fun scheduleChatmailCancellation(entry: Record, chat: Int) {
        val transfer = digest(entry.key.toByteArray())
        save("chatmail-cancel:$chat:$transfer", "chatmail-cancel",
            obj("chat" to chat, "transfer" to transfer, "message" to entry.data().optInt("chatmailMessage")))
    }

    // Call inside the same Room transaction that removes the original outbox row.
    private suspend fun retireSend(entry: Record) {
        val data = entry.data()
        val chat = data.optInt("submittedChatmailChat").takeIf { it > 0 }
            ?: chatmailTarget(data, data.getString("to"))
        if (chat > 0) scheduleChatmailCancellation(entry, chat)
        records.remove(entry.key)
    }

    private suspend fun inviteHellos(remote: String, token: String? = null): List<Record> =
        records.all("outbox").filter { record ->
            val data = record.data()
            val payload = data.optJSONObject("payload")
            data.optString("to") == remote && payload?.optString("type") == "hello" &&
                (token == null || payload.optString("token") == token)
        }

    private suspend fun retireInviteHellos(remote: String) {
        inviteHellos(remote).forEach { retireSend(it) }
    }

    private suspend fun dispatchChatmail(service: Chatmail, now: Long) = outboxLock.withLock {
        val account = credentials ?: return@withLock
        retryAfter.entries.removeIf { now >= it.value }
        var processed = 0
        val cancellations = records.all("chatmail-cancel").mapTo(hashSetOf()) { it.data().optString("transfer") }
        for (record in records.all("outbox")) {
            val data = record.data()
            val to = data.getString("to")
            val chat = chatmailTarget(data, to)
            if (chat <= 0) continue
            val payload = data.getJSONObject("payload")
            val type = payload.optString("type")
            // Drop proprietary call packets left in the outbox by earlier builds.
            if (type == RETIRED_TALK_PACKET) {
                database.withTransaction { retireSend(record) }
                continue
            }
            if (records.get(to)?.data()?.optBoolean("blocked") == true && type != "chatmailAck") continue
            if ((retryAfter[record.key] ?: 0L) > now) continue
            if (type == "hello") {
                val request = records.get("request:" + to)?.takeIf { it.kind == "invite-pending" }?.data()
                if (request == null || !matchesInviteToken(request, payload.optString("token")) ||
                    !isActiveInviteRequest(request, System.currentTimeMillis())) {
                    // An already submitted hello may have been accepted before expiry. Let the
                    // signed paired response arrive without trying to recall the hello.
                    if (request != null && matchesInviteToken(request, payload.optString("token")) &&
                        request.optString("state") != INVITE_REJECTED && data.optInt("chatmailMessage") > 0) {
                        records.remove(record.key)
                    } else database.withTransaction { retireSend(record) }
                    continue
                }
            }
            if (type in setOf("message", "offer") && payload.optString("group").isNotEmpty() &&
                to !in recipients(payload.getString("group"))) {
                database.withTransaction {
                    retireSend(record)
                    change(payload.getString("id")) { put("failed", optInt("failed") + 1) }
                }
                continue
            }
            val transfer = digest(record.key.toByteArray())
            val submitted = data.optInt("chatmailMessage")
            val submittedChat = data.optInt("submittedChatmailChat", chat)
            if (submitted > 0 && submittedChat != chat) {
                database.withTransaction {
                    scheduleChatmailCancellation(record, submittedChat)
                    change(record.key) {
                        remove("chatmailMessage")
                        remove("submittedChatmailChat")
                    }
                }
                continue
            }
            try {
                if (submitted > 0) {
                    val message = service.messages(listOf(submitted)).optJSONObject(submitted.toString())
                    if (message?.optString("error") == "Message does not exist") {
                        change(record.key) {
                            remove("chatmailMessage")
                            remove("submittedChatmailChat")
                        }
                        continue
                    }
                    when (message?.optInt("state")) {
                        24 -> handleResult(record.key, "rejected")
                        26, 28 -> {
                            if (type == "chatmailAck") records.remove(record.key)
                            else if (type == "hello") {
                                val key = "request:" + to
                                if (records.get(key)?.data()?.optString("state") == CHATMAIL_REQUEST_QUEUED) {
                                    change(key) { put("state", CHATMAIL_REQUEST_SENT) }
                                }
                            } else if (type == "message" || type == "offer") {
                                val item = records.get(payload.getString("id"))
                                if (item != null && !item.data().optBoolean("chatmailSubmitted")) {
                                    change(item.key) { put("chatmailSubmitted", true) }
                                }
                            }
                        }
                    }
                    retryAfter[record.key] = now + 5_000L
                    continue
                }
                if (processed >= 6) continue
                if (transfer in cancellations) continue
                if (!service.canSendLegacyPacket(chat)) {
                    retryAfter[record.key] = now + 5_000L
                    continue
                }
                val body = obj("outbox" to record.key, "transfer" to transfer, "to" to to, "data" to payload).toString()
                val packet = JSONObject(NativeEngine.signWith(account.deviceSecret, body))
                    .put("body", body).put("transfer", transfer).put("to", to).put("grant", account.grant.encoded)
                val file = if (type == "offer") File(outgoing, payload.getString("id")) else null
                if (file != null && (!file.isFile || file.length() > NATIVE_ATTACHMENT_FILE_LIMIT)) {
                    handleResult(record.key, "rejected")
                    continue
                }
                val message = service.findLegacyPacket(chat, transfer)
                    ?: service.sendLegacyPacket(chat, packet, file, payload.optString("name"))
                change(record.key) {
                    put("chatmailMessage", message).put("submittedChatmailChat", chat).put("submitted", true)
                }
                processed++
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                retryAfter[record.key] = now + 10_000L
            }
        }
    }

    private fun storeChatmailFile(source: File, id: String, data: JSONObject): Boolean {
        val size = data.optLong("size", -1)
        if (!source.isFile || size !in 0..NATIVE_ATTACHMENT_FILE_LIMIT || source.length() != size) return false
        val staging = File(partial, "chatmail-" + id)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            java.security.DigestInputStream(source.inputStream().buffered(), hash).use { input ->
                staging.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (hash.digest().hex() != data.optString("digest")) return false
            val destination = File(incoming, id)
            check(staging.renameTo(destination)) { "Couldn’t save the Chatmail attachment." }
            return true
        } finally {
            staging.delete()
        }
    }

    private suspend fun loadAccount(vault: IdentityVault) {
        devices.load()
        val account = DeviceAccount.load(vault)
        credentials = account
        peer.value = account.grant.account
    }

    private suspend fun stopEngine() {
        val running = engineRoute
        engineRoute = null
        listenerState.value = emptySet()
        if (running != null) NativeEngine.stop()
        outboxLock.withLock {
            pending.clear()
            retryAfter.clear()
        }
        punches.clear()
        observedRoutes.clear()
        rendezvousLookups.clear()
        nextDiscovery = 0L
    }

    private suspend fun refreshEngine() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < nextNetworkCheck) return
        nextNetworkCheck = now + 350L
        val current = network.select()
        val next = current?.let { EngineRoute(it, network.addresses(it)) }
        if (next == selectedNetwork && (backgroundOnly || next == engineRoute)) return
        if (selectedNetwork != next) chatmailLock.withLock {
            selectedNetwork = null
            chatmail?.disconnect()
            chatmailNetwork = null
            nextChatmailConnect = 0L
        }
        stopEngine()
        selectedNetwork = next ?: return
        if (backgroundOnly) return
        val account = credentials ?: return
        if (next.addresses.isEmpty()) return
        val result = JSONObject(NativeEngine.start(account.deviceSecret, PORT, jsonArray(next.addresses).toString()))
        check(!result.has("error")) { result.optString("error") }
        check(result.getString("peer") == account.grant.device)
        engineRoute = next
        records.all("contact").forEach { route(it.key, it.data().strings("routes")) }
        records.all("discovery-peer").takeLast(12).forEach { offerSeed(it.data()) }
        records.all("invite-in").forEach { route(it.data().getString("peer"), it.data().strings("routes")) }
        announceReplacement(account)
    }

    private suspend fun announceReplacement(account: DeviceAccount) {
        if (account.grant.revision == 1L) return
        val marker = "announced-device:" + account.grant.device
        if (records.get(marker) != null) return
        val groups = records.all("group").filter { account.grant.account in it.data().strings("members") }
        val routes = jsonArray(localAddresses())
        database.withTransaction {
            val peers = linkedMapOf<String, String>()
            records.all("contact").filter { !it.data().optBoolean("blocked") }.forEach { peers[it.key] = "" }
            for (group in groups) {
                group.data().strings("members").filter { it != account.grant.account }.forEach { peers.putIfAbsent(it, group.key) }
            }
            peers.forEach { (to, group) ->
                queue(to, obj("type" to "deviceUpdate", "id" to newId(), "group" to group, "routes" to routes))
            }
            save(marker, "receipt", obj())
        }
    }

    private data class BackupMedia(val id: String, val size: Long, val directory: File) {
        val file get() = File(directory, id)
    }

    private fun backupOperation(password: CharArray, operation: suspend () -> Unit) {
        if (!backupWorking.compareAndSet(expect = false, update = true)) {
            password.fill('\u0000')
            return
        }
        work {
            try {
                recoveryLock.withLock { operation() }
            } finally {
                password.fill('\u0000')
                backupWorking.value = false
            }
        }
    }

    internal suspend fun exportChatmailProfile(destination: Uri) = withContext(Dispatchers.IO) {
        check(backupWorking.compareAndSet(false, true)) { "A backup operation is already running." }
        var folder: File? = null
        try {
            val staging = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "chatmail-export-").toFile()
            folder = staging
            withChatmail { it.exportProfileBackup(staging) }
            val archive = requireNotNull(staging.listFiles()?.singleOrNull { it.isFile && it.extension == "tar" }) {
                "Chatmail did not create a backup."
            }
            checkNotNull(context.contentResolver.openOutputStream(destination, "wt")) {
                "Couldn’t open the backup destination."
            }.use { output -> archive.inputStream().use { it.copyTo(output) } }
            notice.value = "Chatmail backup saved."
        } finally {
            folder?.deleteRecursively()
            backupWorking.value = false
        }
        Unit
    }

    private suspend fun restoreFreshChatmailProfile(restore: suspend (Chatmail) -> Unit) =
        withContext(Dispatchers.IO) {
            check(backupWorking.compareAndSet(false, true)) { "A backup operation is already running." }
            try {
                recoveryLock.withLock {
                    chatmailLock.withLock {
                        check(records.get("profile")?.data()?.optString("name").isNullOrBlank()) {
                            "Restore a Chatmail profile before setting up this profile."
                        }
                        val root = storage.noBackup("chatmail")
                        val core = chatmail ?: openChatmail(root).also { chatmail = it }
                        check(!core.isConfigured()) { "This Chatmail profile is already set up." }
                        restore(core)
                        val name = core.profileName().trim().take(60).ifBlank { "Chatmail profile" }
                        save("profile", "profile", obj("name" to name))
                        nextChatmailConnect = 0L
                        nextNativeSync = 0L
                    }
                }
                notice.value = "Chatmail profile restored."
            } finally {
                backupWorking.value = false
            }
        }

    internal fun importChatmailProfile(source: Uri) = work {
        restoreFreshChatmailProfile { core ->
            val directory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "chatmail-import-").toFile()
            try {
                val staged = File(directory, "chatmail-backup.tar")
                checkNotNull(context.contentResolver.openInputStream(source)) { "Couldn’t open the backup." }
                    .use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
                core.importProfileBackup(staged)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    internal suspend fun receiveChatmailProfile(code: String) {
        require(isProfileTransferQr(code)) { "Scan an Add Second Device QR code." }
        awaitInviteNetwork()
        restoreFreshChatmailProfile { core ->
            runProfileTransfer(core, transfer = { core.receiveProfileTransfer(code.trim()) })
        }
    }

    internal suspend fun shareChatmailProfile(onQr: suspend (String) -> Unit) {
        check(backupWorking.compareAndSet(false, true)) { "A backup operation is already running." }
        try {
            withChatmail(requireNetwork = true) { core ->
                check(talk.state.value?.phase.let { it == null || it == TalkPhase.Finished }) {
                    "End your call before starting a profile transfer."
                }
                runProfileTransfer(core, transfer = core::provideProfileTransfer,
                    untilReady = { onQr(core.profileTransferQr()) })
            }
        } finally {
            backupWorking.value = false
        }
    }

    internal fun prepareBackupPicker(password: CharArray) {
        pendingBackupPassword?.fill('\u0000')
        pendingBackupPassword = password
    }

    internal fun finishBackupPicker(uri: Uri?) {
        val password = pendingBackupPassword
        pendingBackupPassword = null
        when {
            uri == null -> password?.fill('\u0000')
            password == null -> notice.value = "Create the backup again to enter your password"
            else -> exportRecovery(uri, password)
        }
    }

    private fun exportRecovery(uri: Uri, password: CharArray) = backupOperation(password) {
        val account = credentials?.takeUnless { recoveryQueued.get() } ?: error("P2P is starting. Try again in a moment.")
        val root = runCatching { account.recoverySecret(password) }.getOrElse { error("Use your existing backup password") }
        val media = mutableListOf<BackupMedia>()
        val saved = records.recoveryRecords().mapNotNull {
            val data = backupRecordData(it.data())
            if (data.optBoolean("locallyDeleted") || data.has("nativeMessage") ||
                it.key.startsWith("peer-profile:chatmail-peer:")) return@mapNotNull null
            if (data.has("nativeChat")) {
                if (it.kind != "contact" || data.optInt("chatmailChat") <= 9) return@mapNotNull null
                listOf("nativeChat", "contactRequest", "nativeCanSend", "nativeHistoryFirst",
                    "nativeHistoryLast", "nativeHasOlder").forEach { field -> data.remove(field) }
            }
            val attachment = backupMedia(it.key, it.kind, data)
            if (attachment != null && attachment.file.isFile && attachment.file.length() == attachment.size &&
                (it.kind == "file-sent" || data.optString("state") == "Complete")) {
                media.add(attachment)
                data.put("backedUpMedia", true)
            } else if (it.kind == "file-received" && data.optString("state") !in setOf("Withdrawn", "Expired")) {
                data.put("state", "Source unavailable").put("offset", 0L)
            }
            obj("key" to it.key, "kind" to it.kind, "data" to data, "created" to it.created)
        }
        val key = BackupArchive.newKey()
        try {
            val bundle = obj("v" to 3, "secret" to root, "revision" to account.grant.revision,
                "peer" to account.grant.account, "records" to JSONArray(saved), "mediaKey" to encode(key))
            val plain = bundle.toString().toByteArray()
            try {
                check(plain.size <= RecoveryCipher.MAX_PAYLOAD_BYTES) { "There is too much chat history for one backup." }
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn’t write the backup file")
                DataOutputStream(output.buffered()).use { stream ->
                    BackupArchive.writeHeader(stream, plain, password)
                    media.forEach { item ->
                        item.file.inputStream().use { source -> BackupArchive.writeMedia(stream, source, key, item.id, item.size) }
                    }
                }
            } finally {
                plain.fill(0)
            }
            val protected = account.protectRoot(root, password)
            IdentityVault(context, profileId).save(protected.encode())
            credentials = protected
            notice.value = "Encrypted backup saved"
        } finally {
            key.fill(0)
        }
    }

    fun importRecovery(uri: Uri, password: CharArray) = backupOperation(password) {
        check(records.restoreConflicts() == 0 && !recoveryQueued.get()) {
            "Restore on a fresh P2P installation before adding contacts or messages"
        }
        val input = context.contentResolver.openInputStream(uri) ?: error("Couldn’t open the backup file")
        var staged = false
        try {
            DataInputStream(input.buffered()).use { stream ->
                val plain = runCatching { BackupArchive.readHeader(stream, password) }
                    .getOrElse { error("Check the password and backup file") }
                try {
                    val bundle = JSONObject(String(plain, Charsets.UTF_8))
                    check(bundle.optInt("v") in 1..3 && validBackupIdentity(bundle)) { "Invalid P2P backup file" }
                    val rows = recoveryRows(bundle)
                    val replacement = DeviceAccount.replacement(bundle.getString("secret"), bundle.optLong("revision", 1), password)
                    check(backupStaging.deleteRecursively()) { "Couldn’t clear the previous backup restore" }
                    if (bundle.optInt("v") == 3) restoreBackupMedia(stream, bundle, rows)
                    check(stream.read() == -1) { "Invalid P2P backup file" }
                    check(records.restoreConflicts() == 0) {
                        "Restore on a fresh P2P installation before adding contacts or messages"
                    }
                    bundle.remove("secret")
                    bundle.remove("mediaKey")
                    bundle.put("credentials", replacement.encode())
                    IdentityVault(context, profileId).stageRecovery(bundle.toString())
                    staged = true
                    recoveryQueued.set(true)
                    notice.value = "Restoring backup…"
                } finally {
                    plain.fill(0)
                }
            }
        } finally {
            if (!staged) backupStaging.deleteRecursively()
        }
    }

    private fun restoreBackupMedia(stream: DataInputStream, bundle: JSONObject, rows: List<Record>) {
        val key = decode(bundle.getString("mediaKey"))
        try {
            check(key.size == BackupArchive.KEY_BYTES) { "Invalid P2P backup file" }
            val media = backedUpMedia(rows)
            if (media.isNotEmpty()) {
                check(backupStaging.mkdirs()) { "Couldn’t prepare storage for the backup" }
                check(backupStaging.usableSpace > media.sumOf { it.size }) { "Free up space to restore this backup" }
            }
            for (item in media) {
                try {
                    File(backupStaging, item.id).outputStream().use { output ->
                        BackupArchive.readMedia(stream, output, key, item.id, item.size)
                    }
                } catch (_: java.security.GeneralSecurityException) {
                    error("An attachment in this backup is damaged")
                } catch (_: java.io.EOFException) {
                    error("The backup file is incomplete")
                }
            }
        } finally {
            key.fill(0)
        }
    }

    private fun validBackupIdentity(bundle: JSONObject): Boolean {
        val id = bundle.optString("peer")
        return isPeer(id) && id == NativeEngine.identityAddress(bundle.optString("secret"))
    }

    private fun backupRecordData(data: JSONObject): JSONObject = data.apply {
        remove("chatmailChat")
        remove("chatmailRevision")
        remove("chatmailAttachment")
        remove("backedUpMedia")
    }

    private fun backupMedia(id: String, kind: String, value: JSONObject): BackupMedia? {
        val directory = when (kind) {
            "file-sent" -> outgoing
            "file-received" -> incoming
            else -> return null
        }
        val size = value.optLong("size", -1)
        if (!isId(id) || size !in 0..NATIVE_ATTACHMENT_FILE_LIMIT) return null
        return BackupMedia(id, size, directory)
    }

    private fun backedUpMedia(rows: List<Record>): List<BackupMedia> = rows.mapNotNull {
        val value = it.data()
        if (!value.optBoolean("backedUpMedia")) return@mapNotNull null
        backupMedia(it.key, it.kind, value) ?: error("Invalid attachment in backup file")
    }

    private fun recoveryRows(bundle: JSONObject): List<Record> {
        val rows = bundle.getJSONArray("records")
        val version = bundle.getInt("v")
        val self = bundle.getString("peer")
        val seen = mutableSetOf<String>()
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val kind = row.getString("kind")
            val key = row.getString("key")
            val data = row.getJSONObject("data")
            val backupMedia = version == 3 && data.optBoolean("backedUpMedia")
            val value = backupRecordData(data)
            if (backupMedia) value.put("backedUpMedia", true)
            val valid = when (kind) {
                "contact" -> isPeer(key) && key != self
                "group" -> isId(key) && value.optString("id") == key
                "profile" -> key == "profile"
                "device" -> DeviceGrant.verify(value.optString("grant"))?.let { key == "device:" + it.account } == true
                else -> version == 3 && validBackupHistoryKey(key, kind, value)
            }
            check(seen.add(key) && valid) { "Invalid record in backup file" }
            Record(key, kind, value.toString(), row.optLong("created"))
        }
    }

    private fun validBackupHistoryKey(key: String, kind: String, value: JSONObject): Boolean = when (kind) {
        "message", "file-sent", "file-received", "post", "social-action" -> isId(key) && key == value.optString("id")
        "folder" -> key.startsWith("folder:") && validBackupThread(key.removePrefix("folder:"))
        "thread-pref" -> key.startsWith("thread-pref:") &&
            (key == "thread-pref:social-feed" || validBackupThread(key.removePrefix("thread-pref:")))
        "peer-profile" -> key.startsWith("peer-profile:") && isPeer(key.removePrefix("peer-profile:"))
        "call-history" -> key.startsWith("call:") && isId(key.removePrefix("call:"))
        "message-reaction" -> isId(value.optString("message")) && isPeer(value.optString("author")) &&
            key == "reaction:" + value.optString("message") + ":" + value.optString("author")
        else -> false
    }

    private fun validBackupThread(id: String) = isId(id) || isPeer(id)

    private suspend fun applyRecovery(vault: IdentityVault, value: String) {
        val bundle = JSONObject(value)
        val replacement = DeviceAccount.read(bundle.getString("credentials"))
        check(replacement.grant.account == bundle.getString("peer")) { "Invalid saved backup data" }
        val rows = recoveryRows(bundle)
        for (item in backedUpMedia(rows)) {
            val staged = File(backupStaging, item.id)
            if (staged.isFile) {
                check(staged.length() == item.size && staged.renameTo(item.file)) { "Couldn’t restore an attachment" }
            }
            check(item.file.isFile && item.file.length() == item.size) { "A restored attachment is missing" }
        }
        database.withTransaction {
            records.remove("chatmail-cursor")
            rows.forEach { row ->
                val data = backupRecordData(row.data())
                records.put(row.copy(payload = data.toString()))
            }
        }
        // Keep the staged backup until both stores have been committed. Replaying the rows is safe.
        vault.finishRecovery(replacement.encode())
        backupStaging.deleteRecursively()
    }

    internal fun setAppearance(update: (Appearance) -> Appearance) = work {
        database.withTransaction {
            val existing = Appearance.read(records.get("appearance")?.data())
            save("appearance", "appearance", update(existing).json())
        }
    }

    internal fun checkCanSwitchProfiles() {
        check(talk.state.value?.phase.let { it == null || it == TalkPhase.Finished }) {
            "End the call before switching profiles."
        }
        check(!backupWorking.value && !recoveryQueued.get() && pendingBackupPassword == null) {
            "Finish the backup or transfer before switching profiles."
        }
        check(!draftSendState.value) { "Wait for the message to finish sending before switching profiles." }
    }

    internal suspend fun retireForProfileSwitch() {
        retiring = true
        incomingCoreEvents.cancel()
        setForeground(false)
        val scopeJob = checkNotNull(viewModelScope.coroutineContext[Job])
        scopeJob.cancel()
        scopeJob.join()
        withContext(Dispatchers.IO) {
            closeChatmail()
            database.close()
        }
    }

    internal fun setForeground(foreground: Boolean) {
        syncDemand.update { it.copy(foreground = foreground) }
        if (!foreground) {
            talk.onAppHidden()
            talk.incomingRoute(profileId)?.let { announceTalkCall(it.token, it.expiresAt) }
        } else refreshForegroundRinging()
    }

    private fun refreshForegroundRinging() {
        val quiet = notificationsMuted.value || QuietHours.read(context).active()
        if (!visible) return
        if (quiet) {
            talk.silence()
            talk.state.value?.takeIf { it.phase == TalkPhase.Incoming }?.let { alerts.clear(AlertArea.TALK, it.id) }
        } else AppAlertMute.deliver(context, talk::ring)
    }

    internal suspend fun alertPreferences() = withChatmail { it.alertPreferences() }
    internal suspend fun setMentionAlerts(enabled: Boolean) = withChatmail { it.setMentionAlerts(enabled) }
    internal suspend fun setCallAlerts(enabled: Boolean) = withChatmail { it.setCallAlerts(enabled) }

    internal fun setAppNotificationsMuted(muted: Boolean) {
        AppAlertMute.set(context, muted)
        notificationsMuted.value = muted
        refreshForegroundRinging()
    }

    internal fun enableBackgroundSync() {
        check(backgroundOnly)
        syncDemand.value = SyncDemand(jobs = 1)
    }

    internal suspend fun <T> withActiveSync(action: suspend () -> T): T {
        syncDemand.update { it.copy(jobs = it.jobs + 1) }
        try {
            return action()
        } finally {
            syncDemand.update { it.copy(jobs = it.jobs - 1) }
        }
    }
    internal fun setVisibleThread(thread: String?) {
        if (visibleThread != thread) nextNativeSync = 0L
        visibleThread = thread
    }

    fun clearNotice() { notice.value = "" }

    private fun work(action: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            notice.value = error.message ?: "Couldn’t complete that action"
        }
    }

    private suspend fun save(key: String, kind: String, data: JSONObject, created: Long = System.currentTimeMillis()) {
        records.put(Record(key, kind, data.toString(), created))
    }

    private suspend fun change(key: String, update: JSONObject.() -> Unit) {
        val existing = records.get(key) ?: return
        save(key, existing.kind, existing.data().apply(update), existing.created)
    }

    private fun sendNative(command: JSONObject): Boolean =
        engineRoute != null && NativeEngine.submit(command.toString())

    private fun transportFor(account: String): String = devices.find(account)?.device ?: account

    private fun route(id: String, addresses: List<String>) {
        if (addresses.isNotEmpty()) sendNative(obj("op" to "route", "peer" to transportFor(id), "addresses" to jsonArray(addresses.take(12))))
    }

    private fun offerSeed(seed: JSONObject) {
        val id = seed.optString("peer")
        if (isPeer(id) && id != credentials?.grant?.device) sendNative(obj("op" to "seed", "peer" to id,
            "addresses" to jsonArray(seed.strings("routes").filter { it.length < 192 }.take(8))))
    }

    private fun localAddresses(limit: Int = 12): List<String> {
        val id = credentials?.grant?.device ?: return emptyList()
        val addresses = engineRoute?.addresses.orEmpty()
        return advertisedRoutes(addresses, id, limit)
    }

    private suspend fun requestsForInvite(token: String) =
        records.all("invite-in").filter { it.data().optString("token") == token }

    private suspend fun closeInvite(
        token: String, requests: List<Record>, acceptedPeer: String? = null
    ): List<Record> {
        records.remove("invite:" + token)
        for (request in requests) {
            val data = request.data()
            val remote = data.getString("peer")
            val chat = data.optInt("chatmailChat")
            if (chat > 0 && remote != acceptedPeer) {
                queue(remote, obj("type" to "inviteDeclined", "id" to newId(), "token" to token), chat)
            }
            records.remove(request.key)
        }
        return requests
    }

    private suspend fun clearInviteAlerts(requests: List<Record>) {
        for (request in requests) {
            if (records.get(request.key)?.kind != "invite-in") {
                alerts.clear(AlertArea.CONTACTS, request.data().optString("peer"))
            }
        }
    }

    private suspend fun expireInvites() {
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (elapsed < nextInviteCleanup) return
        nextInviteCleanup = elapsed + 15_000
        val now = System.currentTimeMillis()
        records.all("talk-receipt").filter { it.data().optLong("expires") <= now }
            .forEach { records.remove(it.key) }
        val closed = database.withTransaction {
            val offers = records.all("invite-out")
            val valid = offers.filter { it.data().optLong("expires") > now }.mapTo(mutableSetOf()) { it.key }
            offers.filter { it.key !in valid }.forEach { records.remove(it.key) }
            records.all("invite-in").filter { "invite:" + it.data().optString("token") !in valid }
                .onEach { records.remove(it.key) }
        }
        clearInviteAlerts(closed)
    }

    internal fun clearInviteImport() {
        val current = inviteImportState.value
        if (!current.busy) inviteImportState.compareAndSet(current, InviteImportState())
    }

    private fun submitInvite(read: suspend () -> String, channelOnly: Boolean = false) {
        val previous = inviteImportState.value
        if (previous.busy || !inviteImportState.compareAndSet(previous, InviteImportState(busy = true))) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val code = read()
                inviteImportState.value = when {
                    isSecureJoinInvite(code) -> importSecureJoinInvite(code.trim(), channelOnly)
                    channelOnly -> error("This isn’t a channel invite.")
                    else -> importChatmailInvite(code)
                }
            } catch (error: CancellationException) {
                inviteImportState.value = InviteImportState()
                throw error
            } catch (error: Exception) {
                inviteImportState.value = InviteImportState(message = if (error is org.json.JSONException) {
                    "This P2P invite is incomplete. Ask for a fresh invite."
                } else error.message ?: "Couldn’t add the contact.", failed = true)
            } catch (_: LinkageError) {
                inviteImportState.value = InviteImportState(message = "P2P couldn’t open the invite on this device.", failed = true)
            }
        }
    }

    fun importInviteImage(uri: Uri, channelOnly: Boolean = false) = submitInvite(channelOnly = channelOnly, read = {
        val text = runCatching { InviteImage.read(context, uri) }.getOrElse {
            error("Couldn’t read the QR code. Try a close-up image with the full code visible, or paste the invite.")
        }
        require(isSupportedInviteCode(text)) { "This QR code isn’t a supported invite." }
        text
    })

    fun importInvite(text: String, channelOnly: Boolean = false) = submitInvite(read = { text }, channelOnly = channelOnly)

    fun importScannedInvite(text: String, channelOnly: Boolean = false) = submitInvite(channelOnly = channelOnly, read = {
        require(isSupportedInviteCode(text)) { "This QR code isn’t a supported invite." }
        text
    })

    fun acceptInvite(key: String) = work {
        val (request, closed) = database.withTransaction {
            val current = records.get(key)?.takeIf { it.kind == "invite-in" }?.data() ?: return@withTransaction null
            val tokenKey = "invite:" + current.getString("token")
            val token = records.get(tokenKey)?.takeIf { it.kind == "invite-out" }?.data() ?: error("The invitation has expired")
            val approvedAt = System.currentTimeMillis()
            check(token.optLong("expires") > approvedAt) { "The invitation has expired" }
            check(records.get(current.getString("peer"))?.takeIf { it.kind == "contact" }
                ?.data()?.optBoolean("blocked") != true) { "Unblock this contact before accepting their invite." }
            val closed = completeInvite(current, approvedAt)
            current to closed
        } ?: return@work
        route(request.getString("peer"), request.strings("routes"))
        clearInviteAlerts(closed)
    }

    // Call inside the same transaction that validates the token so only one phone can redeem it.
    private suspend fun completeInvite(current: JSONObject, approvedAt: Long): List<Record> {
        val id = current.getString("peer")
        val contact = obj("name" to current.getString("name"), "token" to current.getString("token"),
            "routes" to jsonArray(current.strings("routes")))
        receiveProfile(id, current.optLong("profileDeviceRevision"), current.optJSONObject("profile"))
        if (current.has("chatmailChat")) {
            contact.put("chatmailChat", current.getInt("chatmailChat"))
                .put("chatmailRevision", current.getLong("chatmailRevision"))
        }
        val previous = records.get(id)?.takeIf { it.kind == "contact" }?.data()
        save(id, "contact", previous?.apply {
            put("token", contact.getString("token"))
            if (contact.has("chatmailChat")) {
                put("chatmailChat", contact.getInt("chatmailChat"))
                put("chatmailRevision", contact.getLong("chatmailRevision"))
            }
        } ?: contact)
        val requestToken = current.getString("token")
        val closed = closeInvite(requestToken, requestsForInvite(requestToken), acceptedPeer = id)
        queue(id, obj("type" to "paired", "id" to newId(), "name" to ownName(),
            "token" to requestToken, "approvedAt" to approvedAt), current.optInt("chatmailChat"))
        return closed
    }

    fun declineInvite(key: String) = work {
        val closed = database.withTransaction {
            val request = records.get(key)?.takeIf { it.kind == "invite-in" }?.data() ?: return@withTransaction null
            val token = request.getString("token")
            closeInvite(token, requestsForInvite(token))
        } ?: return@work
        clearInviteAlerts(closed)
    }

    private suspend fun ownName() = records.get("profile")?.data()?.optString("name")?.takeIf(String::isNotBlank) ?: "P2P contact"

    fun setName(name: String, saved: () -> Unit = {}) = work {
        require(name.trim().isNotEmpty()) { "Enter a display name" }
        updateProfile { put("name", name.trim().take(60)) }
        withContext(Dispatchers.Main) { saved() }
    }

    fun setBio(bio: String, saved: () -> Unit = {}) = work {
        require(bio.length <= 500) { "Enter a bio of up to 500 characters." }
        updateProfile { put("bio", bio.trim()) }
        withContext(Dispatchers.Main) { saved() }
    }

    fun setProfilePhoto(uri: Uri?) = work {
        val photo = uri?.let { prepareProfilePhoto(context, it) }.orEmpty()
        updateProfile { put("photo", photo) }
    }

    private suspend fun updateProfile(update: JSONObject.() -> Unit) = database.withTransaction {
        val current = records.get("profile")?.data() ?: obj()
        val revision = maxOf(System.currentTimeMillis(), current.optLong("revision") + 1)
        current.update()
        save("profile", "profile", current.put("revision", revision))
        val packet = obj("type" to "profileUpdate", "id" to newId())
        includeProfile(packet)
        records.all("contact").filterNot { it.data().let { data -> data.has("nativeContact") || data.optBoolean("blocked") } }
            .forEach { queue(it.key, packet) }
    }

    private suspend fun includeProfile(payload: JSONObject) {
        val profile = records.get("profile")?.data() ?: return
        val name = profile.optString("name").take(60)
        if (name.isBlank()) return
        payload.put("profile", obj("name" to name, "photo" to profile.optString("photo"),
            "revision" to profile.optLong("revision", 1)))
    }

    private suspend fun receiveProfile(sender: String, deviceRevision: Long, profile: JSONObject?) {
        if (profile == null) return
        val revision = profile.optLong("revision")
        val name = profile.optString("name").trim().take(60)
        if (revision <= 0 || name.isBlank()) return
        val key = "peer-profile:" + sender
        val old = records.get(key)?.data()
        val oldDevice = old?.optLong("deviceRevision") ?: -1
        if (deviceRevision < oldDevice ||
            (deviceRevision == oldDevice && revision <= (old?.optLong("revision") ?: 0))) return
        val photo = profile.optString("photo")
        if (!validProfilePhoto(photo)) return
        save(key, "peer-profile", obj("name" to name, "photo" to photo, "revision" to revision,
            "deviceRevision" to deviceRevision))
    }

    fun setContactFlag(id: String, key: String, enabled: Boolean) = work {
        if (key == "blocked") {
            setBlockedState(id, enabled)
        } else if (key in listOf("muted", "friend", "alertMuted", "pinnedContact") && records.get(id)?.kind == "contact") {
            database.withTransaction {
                change(id) { put(key, enabled) }
                if (key == "pinnedContact") {
                    val preferenceKey = "thread-pref:" + id
                    val preferences = records.get(preferenceKey)?.data() ?: obj()
                    save(preferenceKey, "thread-pref", preferences.put("pinned", enabled))
                }
            }
            if (key == "alertMuted" && enabled) clearThreadAlerts(id)
        }
    }

    fun setBlocked(id: String, blocked: Boolean) = work { setBlockedState(id, blocked) }

    private suspend fun setBlockedState(id: String, blocked: Boolean) {
        database.withTransaction {
            val target = records.get(id) ?: return@withTransaction
            if (target.kind !in setOf("contact", "group")) return@withTransaction
            val flagsKey = if (target.kind == "contact") id else "thread-pref:" + id
            val flags = records.get(flagsKey)?.data() ?: obj()
            if (flags.optBoolean("blocked") == blocked) return@withTransaction
            val folderKey = "folder:" + id
            if (blocked) {
                flags.put("beforeBlock", records.get(folderKey)?.data()?.optString("name") ?: "Inbox")
                save(folderKey, "folder", obj("name" to "Trash"))
            } else {
                val previous = flags.optString("beforeBlock", "Inbox")
                if (previous == "Inbox") records.remove(folderKey)
                else save(folderKey, "folder", obj("name" to previous))
                flags.remove("beforeBlock")
            }
            save(flagsKey, if (target.kind == "contact") "contact" else "thread-pref", flags,
                if (target.kind == "contact") target.created else System.currentTimeMillis())
        }
        if (blocked) clearThreadAlerts(id)
    }

    private suspend fun groupBlocked(id: String): Boolean =
        records.get("thread-pref:" + id)?.data()?.optBoolean("blocked") == true

    fun requestFriend(id: String) = work {
        require(isPeer(id) && id != peer.value) { "This contact can’t receive a friend request." }
        database.withTransaction {
            val contact = records.get(id)?.takeIf { it.kind == "contact" }?.data()
            check(contact != null && !contact.optBoolean("blocked")) { "This contact can’t receive a friend request." }
            if (!contact.optBoolean("friend") && !contact.optBoolean("friendSent")) {
                change(id) { put("friendSent", true) }
                queue(id, obj("type" to "friendRequest", "id" to newId()))
            }
        }
    }

    fun respondFriend(id: String, accept: Boolean) = work {
        database.withTransaction {
            val contact = records.get(id)?.takeIf { it.kind == "contact" }?.data() ?: return@withTransaction
            if (!contact.optBoolean("friendRequest") || contact.optBoolean("blocked")) return@withTransaction
            change(id) {
                put("friendRequest", false)
                if (accept) {
                    put("friend", true)
                    put("friendSent", false)
                }
            }
            queue(id, obj("type" to "friendResponse", "id" to newId(), "accept" to accept))
            if (accept) recentPosts(id)
        }
    }

    private suspend fun recentPosts(id: String) {
        val files = records.all("file-sent")
        records.all("post").filter { it.data().optString("author") == peer.value }.takeLast(3).forEach { post ->
            queue(id, post.data())
            files.filter { it.data().optString("post") == post.key }.forEach { queue(id, it.data()) }
        }
    }

    fun updateGroup(id: String, name: String, members: Set<String>) = work {
        val title = name.trim().take(80)
        require(title.isNotEmpty()) { "Enter a group name" }
        database.withTransaction {
            val record = records.get(id)?.takeIf { it.kind == "group" } ?: return@withTransaction
            val next = record.data()
            require(next.optString("owner") == peer.value)
            val old = next.strings("members")
            val eligible = members.filter { records.get(it)?.kind == "contact" }
            next.put("name", title)
                .put("members", jsonArray(listOf(peer.value) + eligible))
                .put("revision", next.optInt("revision") + 1)
                .put("secret", randomToken())
            save(id, "group", next, record.created)
            queueGroup((old + eligible).distinct().filter { it != peer.value }, next)
        }
    }

    private suspend fun queueGroup(to: List<String>, group: JSONObject) {
        val roster = group.strings("members")
        val pages = roster.chunked(128)
        pages.forEachIndexed { index, page ->
            val payload = obj("type" to "group", "id" to group.getString("id"), "name" to group.getString("name"),
                "owner" to peer.value, "members" to jsonArray(page), "revision" to group.getInt("revision"),
                "part" to index, "parts" to pages.size)
            to.forEach { recipient ->
                val scoped = JSONObject(payload.toString())
                if (recipient in roster) scoped.put("secret", group.getString("secret"))
                queue(recipient, scoped)
            }
        }
    }

    fun setFolder(thread: String, folder: String) = work { moveConversation(thread, folder) }

    private suspend fun moveConversation(thread: String, folder: String) {
        if (folder != "Trash" && (records.get(thread)?.data()?.optBoolean("blocked") == true || groupBlocked(thread))) {
            notice.value = "Unblock this conversation before moving it"
            return
        }
        val native = records.get(thread)?.data()?.optInt("nativeChat") ?: 0
        if (native > 9 && (folder == "Inbox" || folder == "Archive")) {
            withChatmail { core ->
                core.nativeArchive(native, folder == "Archive")
                saveLocalFolder(thread, folder)
            }
        } else saveLocalFolder(thread, folder)
    }

    private suspend fun saveLocalFolder(thread: String, folder: String) {
        if (folder == "Inbox") records.remove("folder:" + thread)
        else save("folder:" + thread, "folder", obj("name" to folder))
    }

    internal suspend fun leaveNativeGroup(thread: String) = withChatmail { core ->
        val id = requireNotNull(records.get(thread)).data().optInt("nativeChat")
        core.leaveGroup(id)
        core.nativeThread(id)?.let { cacheNativeThread(it, thread) }
        clearThreadAlerts(thread)
        nextNativeSync = 0L
    }

    internal suspend fun removeChatmailChannel(id: Int) = withChatmail { core ->
        core.removeChannel(id)
        cacheChannelAttention(core.channels())
        clearThreadAlerts(nativeThreadKey(id))
    }
    internal suspend fun editChannelPost(id: Int, text: String) = withChatmail { it.editChannelPost(id, text.trim()) }
    internal suspend fun deleteChannelPost(id: Int) = withChatmail { it.deleteChannelPost(id) }
    internal suspend fun toggleChannelPostPin(channelId: Int, id: Int) = withChatmail { core ->
        val channel = core.channel(channelId)
        val post = core.channelPost(id)
        require(channel?.canPost == true && post?.channelId == channelId && post.state != 24) {
            "This post can’t be pinned."
        }
        core.setMessagePinned(id, !post.pinned)
    }
    internal suspend fun localRetention() = withChatmail { it.localRetention() }
    internal suspend fun setLocalRetention(seconds: Long) = withChatmail { it.setLocalRetention(seconds) }

    fun setThreadPreference(thread: String, name: String, enabled: Boolean) = work {
        require(name in setOf("pinned", "muted"))
        val native = records.get(thread)?.data()?.optInt("nativeChat") ?: 0
        if (name == "muted" && native > 9) withChatmail { it.nativeMute(native, enabled) }
        database.withTransaction {
            val key = "thread-pref:" + thread
            val value = records.get(key)?.data() ?: obj()
            save(key, "thread-pref", value.put(name, enabled).apply {
                if (name == "muted" && native > 9) put("nativeMuteSynced", true)
            })
            if (records.get(thread)?.kind == "contact") {
                if (name == "pinned") change(thread) { put("pinnedContact", enabled) }
                if (name == "muted" && !enabled) change(thread) { put("alertMuted", false) }
            }
        }
        if (name == "muted" && enabled) clearThreadAlerts(thread)
    }

    private suspend fun trashThreads(groups: Boolean): Set<String> = records.all("folder")
        .filter { it.data().optString("name") == "Trash" }
        .map { it.key.substringAfter("folder:") }
        .filter { thread ->
            val record = records.get(thread)
            if (groups) record?.kind == "group" && !groupBlocked(thread)
            else record?.kind == "contact" && !record.data().optBoolean("blocked")
        }.toSet()

    fun restoreTrash(groups: Boolean) = work {
        var failed = false
        for (thread in trashThreads(groups)) {
            try {
                moveConversation(thread, "Inbox")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed = true
                AppLog.record(context, "Chatmail conversation restore failed", error)
            }
        }
        if (failed) notice.value = "Some conversations couldn’t be restored. Try again."
    }

    fun emptyTrash(groups: Boolean) = work {
        val removedFiles = mutableListOf<Record>()
        downloadLock.withLock {
            outboxLock.withLock {
                database.withTransaction {
                    val threads = trashThreads(groups)
                    val deleted = (records.all("message") + records.all("file-received") + records.all("file-sent"))
                        .filter { it.data().optString("thread") in threads }
                    val removed = mutableSetOf<String>()
                    val cleared = deleted.mapTo(mutableSetOf(), Record::key)
                    for (record in deleted) {
                        if (record.kind.startsWith("file-") && record.data().optBoolean("listed")) {
                            change(record.key) {
                                put("retainedThread", optString("thread"))
                                put("thread", "")
                                put("unread", false)
                            }
                        } else {
                            records.remove(record.key)
                            removed.add(record.key)
                            if (record.kind.startsWith("file-")) removedFiles.add(record)
                        }
                        save("discarded-message:" + record.key, "receipt",
                            obj("thread" to record.data().optString("thread")))
                    }
                    records.all("message-reaction").filter { it.data().optString("message") in cleared }
                        .forEach { records.remove(it.key) }
                    records.all("outbox").filter {
                        val payload = it.data().optJSONObject("payload") ?: return@filter false
                        payload.optString("id") in removed || payload.optString("file") in removed ||
                            (payload.optString("type") == "messageReaction" && payload.optString("message") in cleared)
                    }.forEach { retireSend(it) }
                    records.all("stream").filter { it.data().optString("file") in removed }
                        .forEach { records.remove(it.key) }
                    threads.forEach { save("folder:" + it, "folder", obj("name" to "Cleared")) }
                }
                for (record in removedFiles) {
                    if (record.kind == "file-sent") File(outgoing, record.key).delete()
                    else {
                        File(partial, record.key).delete()
                        File(incoming, record.key).delete()
                    }
                }
            }
        }
    }

    private suspend fun clearThreadAlerts(thread: String) {
        val area = if (records.get(thread)?.kind == "group") AlertArea.GROUPS else AlertArea.CHATS
        alerts.clear(area, thread)
    }

    // The historical star entry points now use standard Chatmail saved copies.
    fun star(key: String) = work { saveToCollection(key, toggle = true) }

    internal val savingItems = MutableStateFlow<Set<String>>(emptySet())
    private val collectionMutex = Mutex()

    internal suspend fun saveToCollection(key: String, toggle: Boolean = false) = collectionMutex.withLock {
        val record = requireNotNull(records.get(key)) { "This item is unavailable." }
        val data = record.data()
        savingItems.value = setOf(key)
        var removed = false
        try {
            withChatmail { core ->
                val chat = requireNotNull(core.nativeThread(core.savedMessagesChat()))
                cacheNativeThread(chat)
                val target = nativeThreadKey(chat.id)
                if (data.optString("thread") == target) return@withChatmail
                val native = data.optInt("nativeMessage")
                if (native > 9) {
                    val original = core.message(native)
                    if (toggle && original.optInt("savedMessageId") > 9) {
                        removeSavedId(core, original.optInt("savedMessageId"))
                        refreshAfterNativeSend(core, chat.id, target)
                        removed = true
                        return@withChatmail
                    }
                    if (original.optInt("savedMessageId") <= 9 && original.optInt("originalMsgId") <= 9) core.saveMessage(native)
                    val savedId = core.message(native).optInt("savedMessageId")
                    check(savedId > 9) { "The item could not be saved. Please try again." }
                    change(key) { put("nativeSavedId", savedId); remove("star") }
                } else {
                    val parts = legacySavedParts(record, records.threadItems())
                    if (toggle && record.isSavedItem()) {
                        for (part in parts) {
                            val id = part.data().optInt("savedCopyId")
                            if (id > 9) removeSavedId(core, id)
                            change(part.key) { remove("star"); remove("savedCopyId") }
                        }
                        refreshAfterNativeSend(core, chat.id, target)
                        removed = true
                        return@withChatmail
                    }
                    val files = parts.associate { part -> part.key to galleryFile(part) }
                    check(parts.none { it.kind.startsWith("file-") && files[it.key] == null }) {
                        "Download all attachments before saving this item."
                    }
                    if (record.kind == "post") change(record.key) { put("star", true) }
                    for (part in parts) {
                        val partData = part.data()
                        val prior = partData.optInt("savedCopyId")
                        val exists = prior > 9 && runCatching { core.message(prior).optInt("chatId") == chat.id }.getOrDefault(false)
                        if (!exists) {
                            val file = files[part.key]
                            val text = partData.optString("text").ifBlank { partData.optString("caption") }
                                .ifBlank { partData.optString("name").ifBlank { "Saved item" } }
                            val id = core.nativeSend(chat.id, text, null, file, partData.optString("name").takeIf { file != null })
                            change(part.key) { put("savedCopyId", id) }
                        }
                    }
                    // Preserve the legacy star until every attachment is safely copied.
                    // Per-part copy ids let a retry resume without duplicating earlier parts.
                    for (part in parts) change(part.key) { remove("star") }
                }
                refreshAfterNativeSend(core, chat.id, target)
            }
            notice.value = if (removed) "Removed from Saved." else "Saved."
        } finally {
            savingItems.value = emptySet()
        }
    }

    internal suspend fun preparePersonalSpace(): String {
        // Load saved history in pages so older personal apps are not omitted.
        val target = savedMessagesThread()
        withChatmail { core ->
            val chat = requireNotNull(core.nativeThread(core.savedMessagesChat()))
            var previous = Int.MAX_VALUE
            while (true) {
                val data = records.get(target)?.data() ?: break
                val before = data.optInt("nativeHistoryFirst")
                if (!data.optBoolean("nativeHasOlder") || before <= 9 || before >= previous) break
                previous = before
                updateNativeHistory(core, chat, target, 100, before,
                    cached = records.threadItems().filter { it.data().optString("thread") == target })
            }
        }
        return target
    }

    internal suspend fun migrateSavedStars() {
        val starred = legacySavedCandidates(records.threadItems() + records.all("post"))
        var failed = 0
        for (item in starred) try { saveToCollection(item.key) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed++ } // Keep unsuccessful legacy stars visible for retry.
        if (failed > 0) notice.value = "$failed older saved items need a download or connection; their stars were preserved."
    }

    internal suspend fun removeSavedCopy(key: String) = collectionMutex.withLock {
        withChatmail { core ->
            val item = requireNotNull(records.get(key))
            val id = item.data().optInt("nativeMessage")
            val chat = core.savedMessagesChat()
            check(id > 9 && core.message(id).optInt("chatId") == chat) { "Only a saved copy can be removed here." }
            removeSavedId(core, id)
        }
    }

    // Only delete the self-chat copy; never delete the source conversation message.
    private suspend fun removeSavedId(core: Chatmail, id: Int) {
        check(core.message(id).optInt("chatId") == core.savedMessagesChat()) { "Only a saved copy can be removed here." }
        core.deleteSavedMessage(id)
        for (source in records.threadItems() + records.all("post")) {
            val data = source.data()
            if (data.optInt("nativeMessage") == id) change(source.key) { put("locallyDeleted", true) }
            if (data.optInt("nativeSavedId") == id || data.optInt("savedCopyId") == id)
                change(source.key) { remove("nativeSavedId"); remove("savedCopyId"); remove("star") }
        }
    }

    internal suspend fun deletePersonalApp(key: String): String = collectionMutex.withLock {
        withChatmail { core ->
            val item = requireNotNull(records.get(key)) { "This app is unavailable." }
            val data = item.data()
            val id = data.optInt("nativeMessage")
            val message = core.message(id)
            check(canDeletePersonalApp(item, message, core.savedMessagesChat())) { "Only a personal app can be deleted here." }
            val host = webxdcHost(profileId, WebxdcInfo.from(core.webxdcInfo(id)).selfAddr, id)
            core.deleteSavedMessage(id)
            change(key) { put("locallyDeleted", true) }
            host
        }
    }

    internal suspend fun installPersonalApp(draft: AttachmentDraft) {
        try {
            val target = savedMessagesThread()
            commitDraft(target, "", null, listOf(draft))
        } finally { deleteTemporaryDrafts(listOf(draft)) }
    }

    private val desktopMutex = Mutex()

    internal fun editDesktop(thread: String?, edit: (PersonalDesktopLayout) -> PersonalDesktopLayout) = work {
        desktopMutex.withLock {
            if (thread == null || records.get(thread)?.data()?.optBoolean("nativeSelfTalk") != true) return@withLock
            val saved = records.get("personal-app-desktop")?.data()
            val current = personalDesktopLayout(personalApps(records.threadItems(), thread), saved)
            val updated = edit(current)
            if (updated != current) save("personal-app-desktop", "thread-pref", updated.encode())
        }
    }

    internal fun setDesktopHidden(key: String, hidden: Boolean) = work {
        desktopMutex.withLock {
            database.withTransaction {
                val item = records.get(key) ?: return@withTransaction
                if (records.get(item.data().optString("thread"))?.data()?.optBoolean("nativeSelfTalk") == true) {
                    change(key) { put("desktopHidden", hidden) }
                }
            }
        }
    }

    fun markRead(thread: String) = work {
        if (!visible || visibleThread != thread) return@work
        val unread = records.threadItems().unreadThreadItems(thread)
        val nativeIds = unread.mapNotNull { it.data().optInt("nativeMessage").takeIf { id -> id > 9 } }
        if (nativeIds.isNotEmpty()) withChatmail { core ->
            if (visible && visibleThread == thread) core.nativeRead(nativeIds)
        }
        val attachments = database.withTransaction {
            if (!visible || visibleThread != thread) return@withTransaction emptyList()
            unread.mapNotNull { item ->
                val current = records.get(item.key) ?: return@mapNotNull null
                if (current != item) return@mapNotNull null
                val data = current.data().put("unread", false)
                data.remove("locallyUnread")
                save(current.key, current.kind, data, current.created)
                current.key.takeIf { current.kind == "file-received" }
            }
        }
        attachments.forEach { alerts.clear(AlertArea.FILES, it) }
        if (visible && visibleThread == thread && records.threadItems().unreadThreadItems(thread).isEmpty()) {
            clearThreadAlerts(thread)
        }
    }

    fun markUnread(id: String) = work {
        database.withTransaction {
            val item = records.get(id) ?: return@withTransaction
            val data = item.data()
            if (data.optBoolean("locallyDeleted")) return@withTransaction
            if (item.isIncomingThreadItem(peer.value) || (item.kind == "post" && data.optString("author") != peer.value)) {
                if (data.has("nativeMessage")) data.put("locallyUnread", true)
                save(id, item.kind, data.put("unread", true), item.created)
            }
        }
    }

    fun reactMessage(id: String, emoji: String) = work {
        if (!isPickerEmoji(context, emoji)) return@work
        val record = records.get(id)?.data()
        val native = record?.optInt("nativeMessage") ?: 0
        if (native > 9) {
            val thread = requireNotNull(record).getString("thread")
            val chat = records.get(thread)?.data()?.optInt("nativeChat") ?: 0
            withChatmail { core ->
                val conversation = records.get(thread)?.data()
                check(!groupBlocked(thread) && conversation?.optBoolean("blocked") != true) { "This conversation is blocked." }
                check(core.nativeThread(chat)?.let { it.canSend && !it.request } == true) {
                    "This encrypted conversation cannot send reactions yet."
                }
                core.nativeReact(native, emoji)
                refreshAfterNativeSend(core, chat, thread, native)
            }
            return@work
        }
        database.withTransaction {
            val target = records.get(id)?.takeIf(Record::isThreadMessage)?.data() ?: return@withTransaction
            val thread = target.optString("thread")
            if (thread.isBlank()) return@withTransaction
            val targets = recipients(thread)
            require(targets.isNotEmpty()) { "There is no active recipient" }
            val key = "reaction:" + id + ":" + peer.value
            val sequence = (records.get(key)?.data()?.optLong("sequence") ?: 0L) + 1
            val reaction = obj("type" to "messageReaction", "id" to newId(), "message" to id,
                "thread" to thread, "group" to target.optString("group"), "author" to peer.value,
                "text" to emoji, "sequence" to sequence)
            save(key, "message-reaction", reaction)
            targets.forEach { queue(it, reaction) }
        }
    }

    private suspend fun writableNativeChat(core: Chatmail, thread: String): NativeThread {
        val record = records.get(thread)?.data()
        check(record?.optBoolean("blocked") != true && !groupBlocked(thread) &&
            records.get("folder:$thread")?.data()?.optString("name") != "Trash") {
            "This conversation is unavailable."
        }
        return requireNotNull(core.nativeThread(record?.optInt("nativeChat") ?: 0)
            ?.takeIf { it.canSend && !it.request }) { "This conversation cannot send yet." }
    }

    private suspend fun changeNativeMessage(
        key: String, own: Boolean = false, refreshSingle: Boolean = true, change: (Chatmail, NativeMessage) -> Unit
    ) = withChatmail { core ->
        val data = requireNotNull(records.get(key)?.data()) { "This message is unavailable." }
        val thread = data.optString("thread")
        val chat = writableNativeChat(core, thread)
        val message = requireNotNull(core.nativeMessage(chat.id, data.optInt("nativeMessage"))
            ?.takeIf { it.webxdcParent == null && (!own || it.from == 1) }) { "This message is unavailable." }
        change(core, message)
        if (refreshSingle) refreshAfterNativeSend(core, chat.id, thread, message.id)
        else updateNativeHistory(core, chat, thread, 25, cached = records.threadItems().filter {
            it.data().optString("thread") == thread
        })
        nextNativeSync = 0L
    }

    internal suspend fun chatmailRelays() = withChatmail { it.relayAddresses() }

    internal suspend fun removeChatmailRelay(address: String) = withChatmail { it.removeRelay(address) }

    internal suspend fun nativeEncryptionInfo(thread: String): String = withChatmail { core ->
        val chat = records.get(thread)?.data()?.optInt("nativeChat")?.takeIf { it > 9 }
        core.contactEncryptionInfo(requireNotNull(chat) { "This encrypted contact is unavailable." })
    }

    internal suspend fun outgoingMediaQuality() = withChatmail { it.outgoingMediaQuality() }

    internal suspend fun setOutgoingMediaQuality(quality: OutgoingMediaQuality) =
        withChatmail { it.setOutgoingMediaQuality(quality) }

    private suspend fun mediaPolicy(attachments: List<AttachmentDraft>, legacy: Boolean = false): OutgoingMediaPolicy {
        if (attachments.none { it.mime.startsWith("video/") && needsMediaPreparation(it) }) return OutgoingMediaPolicy()
        return try {
            withChatmail { it.outgoingMediaPolicy() }
        } catch (failure: IllegalStateException) {
            if (!legacy || failure is CancellationException) throw failure
            OutgoingMediaPolicy()
        }
    }

    internal suspend fun chatmailReadReceipts(): Boolean = withChatmail { it.readReceiptsEnabled() }

    internal suspend fun setChatmailReadReceipts(enabled: Boolean) = withChatmail { it.setReadReceipts(enabled) }

    private suspend fun existingNativeMessage(core: Chatmail, key: String): NativeMessage {
        val row = requireNotNull(records.get(key)?.data()?.takeUnless { it.optBoolean("locallyDeleted") }) {
            "This message is unavailable."
        }
        val chat = records.get(row.optString("thread"))?.data()?.optInt("nativeChat") ?: 0
        return requireNotNull(chat.takeIf { it > 9 }?.let { core.nativeMessage(it, row.optInt("nativeMessage")) }
            ?.takeIf { it.webxdcParent == null }) { "This message is unavailable." }
    }

    internal suspend fun nativeMessageDetails(key: String): NativeMessageDetails = withChatmail { core ->
        core.messageDetails(existingNativeMessage(core, key))
    }

    internal suspend fun forwardNativeMessage(key: String, target: String) = withChatmail { core ->
        val destination = writableNativeChat(core, target)
        val message = existingNativeMessage(core, key)
        core.forwardMessage(message.id, destination.id)
        refreshAfterNativeSend(core, destination.id, target)
        notice.value = "Message forwarded."
    }

    internal suspend fun chatmailCardContacts(): List<ChatmailContact> = withChatmail { core ->
        core.contacts().sortedBy { it.name.lowercase() }
    }

    internal suspend fun chatmailContactCard(id: Int): AttachmentDraft = withChatmail { core ->
        val card = core.makeContactCard(id)
        val directory = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File.createTempFile("chatmail-contact-", ".vcf", directory)
        try {
            file.writeText(card)
            AttachmentDraft(Uri.fromFile(file), "Contact card.vcf", "text/x-vcard", false, temporary = true)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    internal suspend fun importChatmailCard(key: String) = withChatmail { core ->
        val message = existingNativeMessage(core, key)
        val attachment = requireNotNull(message.attachment?.takeIf { it.viewType == "Vcard" }) {
            "This message does not have a Chatmail contact card."
        }
        val file = requireNotNull(availableChatmailFile(attachment)) { "Download the contact card first." }
        check(core.importContactCard(file).any { it > 9 }) { "The card does not contain a Chatmail contact." }
        notice.value = "Contact added to Chatmail."
    }

    internal suspend fun editNativeMessage(key: String, draft: String) {
        val text = draft.trim()
        require(text.isNotEmpty() && text.length <= INTEROP_TEXT_LIMIT) { "Enter up to $INTEROP_TEXT_LIMIT characters." }
        changeNativeMessage(key, own = true) { core, message -> core.editMessage(message.id, text) }
    }

    internal suspend fun deleteNativeMessageForEveryone(key: String) =
        changeNativeMessage(key, own = true, refreshSingle = false) { core, message ->
            core.deleteMessageForEveryone(message.id)
        }

    internal fun toggleNativeMessagePin(key: String) = work {
        changeNativeMessage(key) { core, message -> core.setMessagePinned(message.id, !message.pinned) }
    }

    internal fun saveNativeMessage(key: String) = work { saveToCollection(key) }

    internal suspend fun savedOriginalLabel(key: String): String? = withChatmail { core ->
        val id = records.get(key)?.data()?.optInt("nativeOriginalId") ?: return@withChatmail null
        if (id <= 9) return@withChatmail null
        val chatId = core.message(id).optInt("chatId")
        if (core.channel(chatId) != null) return@withChatmail savedSourceLabel(true, false)
        val chat = core.nativeThread(chatId) ?: return@withChatmail null
        savedSourceLabel(false, chat.group)
    }

    internal suspend fun savedOriginalThread(key: String): Pair<String?, Int?> = withChatmail { core ->
        val item = requireNotNull(records.get(key))
        val id = item.data().optInt("nativeOriginalId")
        val message = core.message(id)
        val chatId = message.optInt("chatId")
        val channel = core.channel(chatId)
        if (channel != null) return@withChatmail null to channel.id
        val chat = requireNotNull(core.nativeThread(chatId)) { "The original conversation is unavailable." }
        cacheNativeThread(chat)
        refreshAfterNativeSend(core, chat.id, nativeThreadKey(chat.id), id)
        nativeThreadKey(chat.id) to null
    }

    internal suspend fun savedMessagesThread(): String = withChatmail { core ->
        val chat = requireNotNull(core.nativeThread(core.savedMessagesChat())?.takeIf { it.selfTalk })
        cacheNativeThread(chat)
        refreshAfterNativeSend(core, chat.id, nativeThreadKey(chat.id))
        nativeThreadKey(chat.id)
    }

    internal suspend fun pinnedNativeMessages(thread: String): List<NativeMessage> = withChatmail { core ->
        val chat = records.get(thread)?.data()?.optInt("nativeChat") ?: 0
        check(core.nativeThread(chat) != null) { "This conversation is unavailable." }
        core.pinnedMessages(chat).filter { records.get("discarded-message:" + nativeMessageKey(it.id)) == null }
    }

    internal suspend fun revealPinnedNativeMessage(thread: String, id: Int) = withChatmail { core ->
        val chatId = records.get(thread)?.data()?.optInt("nativeChat") ?: 0
        val chat = core.nativeThread(chatId)
        val key = nativeMessageKey(id)
        check(chat != null && core.nativeMessage(chatId, id) != null && records.get("discarded-message:" + key) == null) {
            "This message is unavailable."
        }
        updateNativeHistory(core, chat, thread, 1, messageId = id)
        check(records.get(key)?.data()?.let { it.optString("thread") == thread && !it.optBoolean("locallyDeleted") } == true) {
            "This message is unavailable."
        }
    }

    internal suspend fun nativeDisappearingTimer(thread: String): Int = withChatmail { core ->
        val chat = records.get(thread)?.data()?.optInt("nativeChat") ?: 0
        check(core.nativeThread(chat) != null) { "This conversation is unavailable." }
        core.disappearingTimer(chat)
    }

    internal suspend fun setNativeDisappearingTimer(thread: String, seconds: Int) = withChatmail { core ->
        val chat = writableNativeChat(core, thread)
        core.setDisappearingTimer(chat.id, seconds)
    }

    internal suspend fun nativeMessageLocation(key: String): Pair<Double, Double> = withChatmail { core ->
        val row = requireNotNull(records.get(key)?.data()) { "This message is unavailable." }
        val thread = row.optString("thread")
        val chat = records.get(thread)?.data()?.optInt("nativeChat") ?: 0
        val id = row.optInt("nativeMessage")
        val message = core.message(id)
        require(id > 9 && chat > 9 && message.optInt("chatId") == chat &&
            isEncryptedChatmailMessage(message, chat) && message.optBoolean("hasLocation")) {
            "This message has no shared location."
        }
        requireNotNull(core.messageLocation(chat, id, message.getLong("timestamp"))) { "This location is unavailable." }
    }

    internal suspend fun reactToChannelPost(id: Int, emoji: String) = withChatmail { core ->
        require(emoji.isEmpty() || isPickerEmoji(context, emoji)) { "Choose an emoji." }
        check(core.channelPost(id) != null) { "This post is unavailable." }
        core.nativeReact(id, emoji)
    }

    internal suspend fun prepareChannelReply(id: Int, channelId: Int): ChannelReply = withChatmail { core ->
        val post = core.channelPostForPrivateReply(id, channelId)
        val contact = checkNotNull(post.replyContactId)
        val chatId = core.openContact(contact)
        val chat = requireNotNull(core.nativeThread(chatId)?.takeIf { it.contactId == contact }) {
            "This encrypted conversation is unavailable."
        }
        check(!isBlockedChatmailContact(chatId)) { "Unblock this contact before replying." }
        val thread = records.all("contact").firstOrNull { it.data().optInt("chatmailChat") == chatId }?.key
            ?: nativeThreadKey(chatId)
        cacheNativeThread(chat, thread)
        nextNativeSync = 0L
        ChannelReply(thread, channelId, id, post.channelName, post.text.ifBlank {
            post.attachment?.name ?: "Attachment"
        }.take(500))
    }

    private suspend fun recipients(thread: String): List<String> {
        val record = records.get(thread) ?: return emptyList()
        return if (record.kind == "group") {
            if (groupBlocked(thread)) return emptyList()
            val members = record.data().strings("members")
            if (peer.value !in members) emptyList() else members.filter { it != peer.value }
        } else if (record.kind == "contact" && !record.data().optBoolean("blocked")) listOf(thread) else emptyList()
    }

    private suspend fun queueText(thread: String, text: String, replyTo: String?, targets: List<String>, group: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        val id = newId()
        val quoted = replyTo?.let { key -> records.get(key)?.takeIf {
            it.kind == "message" && it.data().optString("thread") == thread
        }?.key }.orEmpty()
        val data = obj("type" to "message", "id" to id, "text" to message,
            "author" to peer.value, "thread" to thread, "group" to group,
            "replyTo" to quoted, "at" to System.currentTimeMillis(), "targets" to targets.size)
        save(id, "message", data)
        if (records.get("folder:" + thread)?.data()?.optString("name") == "Cleared") records.remove("folder:" + thread)
        targets.forEach { queue(it, data) }
    }

    internal fun sendDraft(
        thread: String, text: String, replyTo: String?, attachments: List<AttachmentDraft>,
        voicemail: Boolean = false, channelReply: ChannelReply? = null, sent: () -> Unit
    ) {
        if (text.isBlank() && attachments.isEmpty()) return
        if (!draftSendState.compareAndSet(false, true)) return
        work {
            try {
                commitDraft(thread, text, replyTo, attachments, voicemail, channelReply)
                withContext(Dispatchers.Main.immediate) { sent() }
            } finally {
                draftSendState.value = false
            }
        }
    }

    private suspend fun commitDraft(
        thread: String, text: String, replyTo: String?, attachments: List<AttachmentDraft>,
        voicemail: Boolean = false, channelReply: ChannelReply? = null
    ) {
        require(text.trim().length <= MAX_MESSAGE_LENGTH) { "This message is too long. Split it into shorter messages." }
        val record = records.get(thread)
        val native = record?.data()?.optInt("nativeChat") ?: 0
        check(channelReply == null || (channelReply.thread == thread && native > 9)) {
            "This encrypted conversation is unavailable."
        }
        if (native > 9) {
            check(record?.data()?.optBoolean("blocked") != true && !groupBlocked(thread)) { "This conversation is blocked." }
            sendChatmailDraft(native, thread, text.trim(), replyTo, attachments, voicemail, channelReply = channelReply)
            deleteTemporaryDrafts(attachments)
            return
        }
        require(recipients(thread).isNotEmpty()) { "There is no active recipient" }
        val group = if (record?.kind == "group") thread else ""
        val staged = mutableListOf<JSONObject>()
        try {
            val policy = mediaPolicy(attachments, legacy = true)
            for (item in attachments) staged.add(stageFile(item, thread, group, policy = policy))
            database.withTransaction {
                val targets = recipients(thread)
                require(targets.isNotEmpty()) { "There is no active recipient" }
                if (staged.isEmpty()) queueText(thread, text, replyTo, targets, group)
                else staged.first().put("caption", text.trim())
                for (offer in staged) {
                    if (voicemail) offer.put("voicemail", true)
                    save(offer.getString("id"), "file-sent", offer.put("targets", targets.size))
                    targets.forEach { queue(it, offer) }
                }
                if (records.get("folder:" + thread)?.data()?.optString("name") == "Cleared") records.remove("folder:" + thread)
            }
        } catch (error: Throwable) {
            staged.forEach { File(outgoing, it.getString("id")).delete() }
            throw error
        }
        deleteTemporaryDrafts(attachments)
    }

    internal suspend fun openWebxdc(thread: String, id: Int): WebxdcInfo = withChatmail { core ->
        val chat = records.get(thread)?.data()?.optInt("nativeChat") ?: 0
        val message = core.message(id)
        require(chat > 9 && message.optInt("chatId") == chat && message.optString("viewType") == "Webxdc") {
            "This app is no longer in the conversation."
        }
        check(message.optString("downloadState") == "Done") { "Download the app before opening it." }
        WebxdcInfo.from(core.webxdcInfo(id)).also {
            storage.rememberWebxdcSite(id, webxdcHost(profileId, it.selfAddr, id))
        }
    }

    internal suspend fun prepareWebxdcShortcut(shortcut: WebxdcShortcut): Pair<Record, File> =
        withContext(Dispatchers.IO) {
            check(shortcut.profile == profileId)
            val key = nativeMessageKey(shortcut.id)
            suspend fun readApp() = checkNotNull(records.get(key)?.takeIf {
                it.webxdcMessageId(shortcut.thread) == shortcut.id
            }) { "This shared app is no longer available." }
            readApp()
            val local = downloadForExport(key)
            val record = readApp()
            check(galleryFile(record) == local) {
                "This shared app is no longer available."
            }
            record to local
        }

    internal suspend fun webxdcUpdates(id: Int, after: Int): String = withChatmail { it.webxdcUpdates(id, after) }

    internal suspend fun webxdcWrite(thread: String, id: Int, write: (Chatmail) -> Unit) = withChatmail { core ->
        val record = records.get(thread)?.data()
        val chat = record?.optInt("nativeChat") ?: 0
        check(chat > 9 && record?.optBoolean("blocked") != true && !groupBlocked(thread) &&
            core.nativeThread(chat)?.let { it.canSend && !it.request } == true &&
            core.message(id).let { it.optInt("chatId") == chat && it.optString("viewType") == "Webxdc" }) {
            "This conversation cannot send app updates."
        }
        write(core)
    }

    internal fun leaveWebxdc(id: Int) = work { withChatmail { it.webxdcLeave(id) } }

    private suspend fun sendChatmailDraft(
        chat: Int, thread: String, text: String, replyTo: String?, attachments: List<AttachmentDraft>,
        voicemail: Boolean = false, channelPost: Boolean = false, channelReply: ChannelReply? = null
    ) {
        val quote = channelReply?.postId ?: replyTo?.let { key ->
            records.get(key)?.data()?.takeIf { it.optString("thread") == thread }?.optInt("nativeMessage")?.takeIf { it > 9 }
        }
        val submission = UUID.nameUUIDFromBytes(obj("thread" to thread, "text" to text, "quote" to quote,
            "files" to JSONArray(attachments.map(AttachmentDraft::toString))).toString().toByteArray())
        val checkpoint = "native-draft:$submission"
        val start = if (attachments.isEmpty()) 0 else records.get(checkpoint)?.data()?.optInt("next") ?: 0
        val staged = mutableListOf<JSONObject>()
        try {
            val pending = attachments.drop(start)
            val policy = mediaPolicy(pending)
            for (item in pending) staged.add(stageFile(item, thread, "", policy = policy))
            val ready = staged.map { offer ->
                val file = File(outgoing, offer.getString("id"))
                val name = offer.getString("name")
                val viewType = chatmailAttachmentViewType(offer.getString("mime"), offer.optBoolean("listed"), voicemail, name)
                if (viewType == "Webxdc") {
                    require(!channelPost) { "Share apps in a chat or group." }
                    inspectWebxdc(file)
                }
                Triple(file, name, viewType)
            }
            withChatmail { core ->
                val channel = if (channelPost) requireNotNull(core.channel(chat)) { "Channel unavailable." } else null
                if (channelReply != null) {
                    check(!isBlockedChatmailContact(chat)) { "Unblock this contact before replying." }
                    if (start == 0) {
                        val post = core.channelPostForPrivateReply(channelReply.postId, channelReply.channelId)
                        check(core.nativeThread(chat)?.contactId == post.replyContactId) {
                            "This private reply must go to the channel owner."
                        }
                    }
                }
                if (attachments.isEmpty()) {
                    if (channel != null) core.postToChannel(channel, text) else core.nativeSend(chat, text, quote)
                }
                for ((offset, attachment) in ready.withIndex()) {
                    val index = start + offset
                    val caption = text.takeIf { index == 0 }.orEmpty()
                    val (file, name, viewType) = attachment
                    if (channel != null) core.postToChannel(channel, caption, file, name, viewType)
                    else core.nativeSend(chat, caption, quote.takeIf { index == 0 }, file, name, viewType)
                    save(checkpoint, "native-draft", obj("next" to index + 1))
                }
                if (attachments.isNotEmpty()) records.remove(checkpoint)
                if (channel == null) refreshAfterNativeSend(core, chat, thread)
            }
        } finally {
            staged.forEach { File(outgoing, it.getString("id")).delete() }
            if (!channelPost) nextNativeSync = 0L
        }
        if (!channelPost && records.get("folder:$thread")?.data()?.optString("name") == "Cleared") {
            records.remove("folder:$thread")
        }
    }

    private suspend fun refreshAfterNativeSend(core: Chatmail, chat: Int, thread: String, message: Int? = null) {
        nextNativeSync = 0L
        try {
            core.nativeThread(chat)?.let { updateNativeHistory(core, it, thread, 25, messageId = message) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.record(context, "Chatmail local message refresh failed", error)
        }
    }

    private fun deleteTemporaryDrafts(attachments: List<AttachmentDraft>) {
        attachments.filter { it.temporary && it.uri.scheme == "file" }.forEach { item ->
            item.uri.path?.let { name ->
                val file = File(name)
                if (file.parentFile == File(context.cacheDir, "captures")) file.delete()
            }
        }
    }

    internal fun clearSubmission(value: SheetSubmission) {
        if (value.finished) submission.compareAndSet(value, null)
    }

    private fun submitSheet(request: SheetSubmission, action: suspend () -> String?): Job? {
        val previous = submission.value
        if (previous?.finished == false || !submission.compareAndSet(previous, request)) return null
        return work {
            try {
                val recipient = action()
                submission.value = request.copy(finished = true, recipient = recipient ?: request.recipient)
            } catch (error: CancellationException) {
                submission.compareAndSet(request, null)
                throw error
            } catch (error: Exception) {
                submission.value = request.copy(finished = true, error = error.message ?: "Couldn’t complete that action")
            } catch (_: LinkageError) {
                submission.value = request.copy(finished = true, error = "Chatmail couldn’t start on this device.")
            }
        }
    }

    internal fun publish(text: String, media: List<Uri>) = submitSheet(SheetSubmission("post")) {
        val value = text.trim()
        require(value.isNotEmpty() || media.isNotEmpty()) { "Add text or media before posting." }
        val id = newId()
        val attachments = mutableListOf<JSONObject>()
        try {
            val drafts = media.distinct().map { describeAttachment(context, it, false) }
            val policy = mediaPolicy(drafts, legacy = true)
            for (draft in drafts) attachments.add(stageFile(draft, "", "", id, policy))
            val data = obj("type" to "post", "id" to id, "text" to value.take(12_000),
                "author" to peer.value, "at" to System.currentTimeMillis(), "media" to attachments.size)
            database.withTransaction {
                save(id, "post", data)
                attachments.forEach { save(it.getString("id"), "file-sent", it) }
                records.all("contact").filter { it.data().optBoolean("friend") && !it.data().optBoolean("blocked") }
                    .forEach { contact ->
                        queue(contact.key, data)
                        attachments.forEach { queue(contact.key, it) }
                    }
            }
        } catch (error: Throwable) {
            attachments.forEach { File(outgoing, it.getString("id")).delete() }
            throw error
        }
        null
    }

    fun socialAction(post: String, value: String, reaction: Boolean) = work {
        val text = value.trim().take(4_000)
        if (text.isEmpty() || reaction && !isPickerEmoji(context, text)) return@work
        database.withTransaction {
            val target = records.get(post)?.takeIf { it.kind == "post" }?.data() ?: return@withTransaction
            val author = target.optString("author")
            val self = peer.value
            if (author != self) {
                val contact = records.get(author)?.takeIf { it.kind == "contact" }?.data()
                if (contact?.optBoolean("friend") != true || contact.optBoolean("blocked")) return@withTransaction
            }
            if (reaction && hasSocialReaction(post, self, text)) return@withTransaction
            val id = newId()
            val data = obj("type" to if (reaction) "reaction" else "reply", "id" to id, "post" to post,
                "author" to self, "text" to text)
            save(id, "social-action", data)
            if (author != self) queue(author, data)
        }
    }

    private suspend fun hasSocialReaction(post: String, author: String, emoji: String): Boolean =
        records.all("social-action").any {
            val item = it.data()
            item.optString("type") == "reaction" && item.optString("post") == post &&
                item.optString("author") == author && item.optString("text") == emoji
        }

    private suspend fun queue(to: String, payload: JSONObject, chatmailChat: Int? = null) {
        if (!isPeer(to) || to == peer.value) return
        val id = payload.getString("id")
        val sequence = payload.optInt("revision", 0).toString() + ":" + payload.optInt("part")
        val outgoingPayload = if (payload.optString("type") in setOf("message", "offer", "hello", "paired")) {
            JSONObject(payload.toString()).also { includeProfile(it) }
        } else payload
        val data = obj("to" to to, "payload" to outgoingPayload)
        if (chatmailChat != null) data.put("chatmailChat", chatmailChat)
        if (payload.optString("type") == "message") data.put("submitted", false)
        save("out:" + to + ":" + id + ":" + sequence, "outbox", data)
    }

    private suspend fun stageFile(
        attachment: AttachmentDraft, thread: String, group: String, post: String = "", policy: OutgoingMediaPolicy
    ): JSONObject {
        val operation = currentCoroutineContext()
        operation.ensureActive()
        val id = newId()
        var name = attachment.label
        var mime = attachment.mime
        val source = File(outgoing, id)
        try {
            val originalHash = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(attachment.uri)?.use { input ->
                source.outputStream().buffered().use { output ->
                    java.security.DigestInputStream(input, originalHash).copyBoundedTo(
                        output, NATIVE_ATTACHMENT_FILE_LIMIT, "This attachment is too large.",
                        bufferSize = 128 * 1024, checkActive = operation::ensureActive
                    )
                }
            } ?: error("Couldn’t open " + name)
            val compressed = AttachmentCompressor(context).compress(source, attachment, policy)
            val hash = if (compressed == null) {
                if (attachment.range != null) error("Couldn’t prepare the selected trim. Your draft is still available.")
                originalHash.digest().hex()
            } else {
                try {
                    operation.ensureActive()
                    if (!compressed.file.renameTo(source)) {
                        compressed.file.inputStream().use { input ->
                            source.outputStream().use { output ->
                                input.copyBoundedTo(output, NATIVE_ATTACHMENT_FILE_LIMIT, "This attachment is too large.",
                                    bufferSize = 128 * 1024, checkActive = operation::ensureActive)
                            }
                        }
                    }
                    name = name.substringBeforeLast('.', name) + "." + compressed.extension
                    mime = compressed.mime
                    val finalHash = MessageDigest.getInstance("SHA-256")
                    source.inputStream().buffered().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            operation.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            finalHash.update(buffer, 0, count)
                        }
                    }
                    finalHash.digest().hex()
                } finally {
                    compressed.file.delete()
                }
            }
            operation.ensureActive()
            require(source.length() <= NATIVE_ATTACHMENT_FILE_LIMIT) { "This attachment is too large." }
            return obj("type" to "offer", "id" to id, "name" to name, "mime" to mime,
                "size" to source.length(), "digest" to hash, "thread" to thread,
                "group" to group, "post" to post, "author" to peer.value, "listed" to attachment.asFile, "state" to "Offer queued")
        } catch (error: Throwable) {
            source.delete()
            throw error
        }
    }

    internal fun sendFiles(thread: String, uris: List<Uri>, text: String = "") = submitSheet(SheetSubmission("send", thread)) {
        require(uris.isNotEmpty() || text.isNotBlank()) { "Choose something to share." }
        commitDraft(thread, text, null, uris.distinct().map { describeAttachment(context, it, asFile = false) })
        thread
    }

    private suspend fun mayDownload(to: String, offer: JSONObject): Boolean {
        if (to in offer.strings("cancelledTo")) return false
        if (offer.optString("post").isBlank()) {
            val thread = offer.optString("thread").ifBlank { offer.optString("retainedThread") }
            return to in recipients(thread)
        }
        val contact = records.get(to)?.takeIf { it.kind == "contact" }?.data() ?: return false
        return contact.optBoolean("friend") && !contact.optBoolean("blocked")
    }

    internal suspend fun prepareMediaDownload(request: MediaDownload) {
        request.attachment?.let { withContext(Dispatchers.IO) { downloadForExport(it) } }
    }

    internal suspend fun saveMedia(request: MediaDownload, newDocument: Uri?) = withContext(Dispatchers.IO) {
        writeMediaDownload(context.contentResolver, request, newDocument) {
            when {
                request.attachment != null -> downloadForExport(request.attachment).inputStream()
                request.channelMessage != null -> withChatmail { core ->
                    requireNotNull(channelAttachment(core, request.channelMessage).localFile) {
                        "This attachment is unavailable. Download it from the channel first."
                    }.inputStream()
                }
                request.profilePhoto != null -> checkNotNull(checkedPhoto(request.profilePhoto)) {
                    "This profile photo is no longer available."
                }.inputStream()
                request.thumbnail != null -> fetchYoutubeThumbnail(request.thumbnail).inputStream()
                else -> error("This file is no longer available.")
            }
        }
    }

    private suspend fun downloadForExport(id: String): File {
        val record = checkNotNull(records.get(id)) { "This file is no longer available." }
        galleryFile(record)?.let { return it }
        check(record.kind == "file-received" && record.data().optString("state") !in setOf("Withdrawn", "Expired")) {
            "This file is no longer available."
        }
        return checkNotNull(withTimeoutOrNull(120_000L) {
            withActiveSync {
                startDownload(id)
                val ready = records.observeRecord(id).first { current ->
                    current == null || galleryFile(current) != null ||
                        current.data().optString("state") in setOf("Withdrawn", "Expired", "Paused", "Source unavailable")
                }
                ready?.let(::galleryFile) ?: error("This file is unavailable. Ask the sender to share it again.")
            }
        }) { "The file hasn’t arrived yet. Try again when the connection is available." }
    }

    fun cancelQueuedMessage(id: String) = work {
        outboxLock.withLock {
            database.withTransaction {
                val message = records.get(id)?.takeIf { it.kind == "message" }?.data() ?: return@withTransaction
                if (message.optString("author") != peer.value) return@withTransaction
                val queued = records.all("outbox").filter { row ->
                    val payload = row.data().optJSONObject("payload")
                    payload?.optString("type") == "message" && payload.optString("id") == id
                }
                if (queued.isEmpty()) return@withTransaction
                val possiblySent = queued.any { it.data().optBoolean("submitted", true) }
                for (row in queued) {
                    retireSend(row)
                    pending.remove(row.key)
                    retryAfter.remove(row.key)
                }
                change(id) {
                    put("cancelled", optInt("cancelled") + queued.size)
                    put("possiblySent", optBoolean("possiblySent") || possiblySent)
                }
            }
        }
    }

    fun cancelQueuedOffer(id: String) = work {
        outboxLock.withLock {
            database.withTransaction {
                val offer = records.get(id)?.takeIf { it.kind == "file-sent" }?.data() ?: return@withTransaction
                val queued = records.all("outbox").filter { row ->
                    val payload = row.data().optJSONObject("payload")
                    payload?.optString("type") == "offer" && payload.optString("id") == id
                }
                if (queued.isEmpty()) return@withTransaction
                val targets = queued.map { it.data().getString("to") }.distinct()
                queued.forEach {
                    retireSend(it)
                    pending.remove(it.key)
                    retryAfter.remove(it.key)
                }
                change(id) { put("cancelledTo", jsonArray((strings("cancelledTo") + targets).distinct())) }
                targets.forEach { to ->
                    queue(to, obj("type" to "offerCancel", "id" to newId(), "file" to id,
                        "group" to offer.optString("group"), "post" to offer.optString("post")))
                }
            }
        }
    }

    fun markCallHistoryRead() = work {
        database.withTransaction {
            records.all("call-history").filter { it.data().optBoolean("unread") }
                .forEach { change(it.key) { put("unread", false) } }
        }
    }

    fun clearCallHistory() = work {
        records.clearCallHistory()
    }

    suspend fun markSocialRead(page: String) {
        val read = database.withTransaction {
            val posts = records.all("post").filter {
                (it.data().optString("author") == peer.value) == (page == "My Page")
            }.map { it.key }.toSet()
            (records.all("post") + records.all("social-action")).filter {
                it.data().optBoolean("unread") && (it.key in posts || it.data().optString("post") in posts)
            }.onEach { change(it.key) { put("unread", false) } }
        }
        read.forEach { alerts.clear(AlertArea.SOCIAL, it.data().optString("post").ifBlank { it.key }) }
    }

    fun markVoicemailHeard(id: String) = work { change(id) { put("unread", false) }; alerts.clear(AlertArea.MISSED_CALLS, id) }

    fun deleteLocalItem(id: String) = work { change(id) { put("locallyDeleted", true); put("unread", false) } }

    fun saveToFiles(id: String) = work { change(id) { put("listed", true) } }

    fun deleteReceivedCopy(id: String) = work {
        if (!isId(id)) return@work
        downloadLock.withLock {
            database.withTransaction {
                val record = records.get(id)?.takeIf { it.kind == "file-received" } ?: return@withTransaction
                if (record.data().optString("state") != "Complete") return@withTransaction
                val file = File(incoming, id)
                check(!file.exists() || file.delete()) { "Relay Lab couldn’t delete the local copy." }
                clearQueuedDownloads(id)
                change(id) { put("state", "Paused"); put("offset", 0L); put("listed", false) }
            }
        }
    }


    internal suspend fun migrateLegacyDownloads() = downloadLock.withLock {
        val migrated = database.withTransaction {
            records.all("file-received").mapNotNull { record ->
                val data = record.data()
                if (!data.has("wifi") && data.optString("state") != "Waiting for Wi-Fi") return@mapNotNull null
                val state = downloadState(data)
                data.remove("wifi")
                save(record.key, record.kind, data.put("state", state), record.created)
                record.key
            }
        }
        migrated.forEach { TransferJob.cancelLegacyConstraint(context, it, profileId) }
    }

    fun requestDownload(id: String) = work { startDownload(id) }

    private suspend fun startDownload(id: String) {
        val snapshot = records.get(id)?.takeIf { it.kind == "file-received" }?.data() ?: return
        if (snapshot.optString("state") == "Withdrawn" ||
            (snapshot.optString("state") == "Complete" && File(incoming, id).isFile)) return
        val native = snapshot.optInt("nativeMessage")
        if (native > 9) {
            withChatmail { core ->
                val thread = snapshot.optString("thread")
                val chatId = records.get(thread)?.data()?.optInt("nativeChat") ?: 0
                check(chatId > 9) { "This conversation is unavailable." }
                val chat = checkNotNull(core.nativeThread(chatId)) { "This conversation is unavailable." }
                val attachment = checkNotNull(core.nativeMessage(chatId, native)?.attachment) {
                    "This attachment is unavailable."
                }
                require(attachment.size <= NATIVE_ATTACHMENT_FILE_LIMIT) { "This attachment exceeds the 1 GiB limit." }
                if (attachment.canRequestDownload) core.download(native)
                // A previous failure in Room must not end a newly started retry.
                updateNativeHistory(core, chat, thread, 25, messageId = native, resumeAttachment = true)
                nextNativeSync = 0L
            }
            return
        }
        val chatmailMessage = snapshot.optInt("chatmailAttachment")
        val retainedFile = if (chatmailMessage > 9) chatmailLock.withLock {
            val service = chatmail ?: openChatmail(storage.noBackup("chatmail")).also { chatmail = it }
            val cachedPath = service.message(chatmailMessage).optString("file")
            if (cachedPath.isBlank() || cachedPath == "null") null else File(cachedPath).takeIf(File::isFile)
        } else null
        downloadLock.withLock {
            val record = records.get(id)?.takeIf { it.kind == "file-received" } ?: return@withLock
            val data = record.data()
            val state = downloadState(data)
            if (state in setOf("Withdrawn", "Downloading", "Waiting to connect") ||
                (state == "Complete" && File(incoming, id).isFile)) return@withLock
            val size = data.optLong("size", -1)
            check(size >= 0) { "This file has an invalid size." }
            if (chatmailMessage > 9) {
                check(retainedFile != null && data.optInt("chatmailAttachment") == chatmailMessage &&
                    storeChatmailFile(retainedFile, id, data)) { "This copy is unavailable. Ask the sender to share it again." }
                change(id) { put("state", "Complete"); put("offset", size) }
                return@withLock
            }
            val offset = if (state == "Complete") 0L else data.optLong("offset").coerceIn(0, size)
            val remaining = size - offset
            check(context.filesDir.usableSpace > remaining) { "Free up space to download this file" }
            database.withTransaction {
                clearQueuedDownloads(id)
                queueDownload(id, data, offset)
                save(id, record.kind, data, record.created)
            }
            if (android.os.Build.VERSION.SDK_INT >= 34) TransferJob.schedule(context, id, remaining, profileId)
        }
    }

    private suspend fun queueDownload(id: String, data: JSONObject, offset: Long) {
        val download = newId()
        val revision = data.optLong("downloadRevision") + 1
        data.remove("wifi")
        data.put("state", "Waiting to connect").put("offset", offset)
            .put("download", download).put("downloadRevision", revision)
        queue(data.getString("author"), obj("type" to "download", "id" to download,
            "file" to id, "offset" to offset, "revision" to revision))
    }

    private suspend fun clearQueuedDownloads(id: String) {
        records.all("outbox").filter { record ->
            val payload = record.data().optJSONObject("payload")
            payload?.optString("type") == "download" && payload.optString("file") == id
        }.forEach { records.remove(it.key) }
    }

    fun pauseDownload(id: String) = work {
        downloadLock.withLock {
            val data = records.get(id)?.takeIf { it.kind == "file-received" }?.data() ?: return@withLock
            if (data.optString("state") in setOf("Complete", "Withdrawn")) return@withLock
            database.withTransaction {
                change(id) { put("state", "Paused") }
                clearQueuedDownloads(id)
            }
        }
    }

    fun cancelDownload(id: String) = work {
        downloadLock.withLock {
            val record = records.get(id) ?: return@withLock
            if (record.kind != "file-received" || record.data().optString("state") in setOf("Complete", "Withdrawn")) {
                return@withLock
            }
            database.withTransaction {
                change(id) { put("state", "Paused"); put("offset", 0L) }
                clearQueuedDownloads(id)
            }
            File(partial, id).delete()
        }
    }

    fun completedFile(id: String): File? {
        if (!isId(id)) return null
        val record = items.value.orEmpty().firstOrNull { it.key == id && it.kind == "file-received" } ?: return null
        if (record.data().optString("state") != "Complete") return null
        return File(incoming, id).takeIf(File::isFile)
    }

    fun galleryFile(record: Record): File? {
        if (!isId(record.key)) return null
        return when (record.kind) {
            "file-sent" -> File(outgoing, record.key).takeIf(File::isFile)
            "file-received" -> File(incoming, record.key).takeIf {
                record.data().optString("state") == "Complete" && it.isFile
            }
            else -> null
        }
    }

    private suspend fun discover() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < nextDiscovery || peer.value.isBlank()) return
        nextDiscovery = now + 15_000
        val clock = System.currentTimeMillis()
        observedRoutes.entries.removeIf { it.value <= clock }
        val addresses = (observedRoutes.keys + localAddresses()).distinct().take(12)
        if (addresses.isEmpty()) return
        if (clock > nextMediaCleanup) {
            nextMediaCleanup = clock + 60 * 60 * 1000
            val cutoff = clock - 30L * 24 * 60 * 60 * 1000
            records.all("file-received").filter { it.created < cutoff }.forEach { record ->
                val file = record.data()
                if (file.optString("post").isNotEmpty() && !file.optBoolean("listed") && file.optString("state") == "Complete") {
                    File(incoming, record.key).delete()
                    change(record.key) { put("state", "Expired"); put("offset", 0L) }
                }
            }
        }
        val contacts = records.all("contact").filter { !it.data().optBoolean("blocked") }
            .map { it.key to it.data().optString("token") }
        val groupTopics = records.all("group").flatMap { record ->
            val group = record.data()
            val secret = group.optString("secret")
            val members = group.strings("members")
            if (peer.value !in members) emptyList() else members.filter { it != peer.value }.map { it to secret }
        }
        val pendingInvites = records.all("invite-pending").map { it.data() }
            .filter { isActiveInviteRequest(it, clock) }.map { it.optString("peer") to it.optString("token") }
        val offers = records.all("invite-out").filter { it.data().optLong("expires") > clock }
            .map { "" to it.key.substringAfter("invite:") }
        val topics = (contacts + groupTopics + pendingInvites + offers).filter { it.second.length == 32 }.distinct()
        if (topics.isEmpty()) return
        val local = credentials ?: return
        val payload = obj("peer" to local.grant.account, "grant" to local.grant.encoded,
            "expires" to clock + 120_000, "routes" to jsonArray(addresses.take(8))).toString()
        val signed = JSONObject(NativeEngine.signWith(local.deviceSecret, payload)).put("payload", payload).toString()
        if (discoveryIndex >= topics.size) discoveryIndex = 0
        for (i in 0 until minOf(topics.size, 10)) {
            val (remote, secret) = topics[discoveryIndex]
            discoveryIndex = (discoveryIndex + 1) % topics.size
            sendNative(obj("op" to "publish", "key" to Rendezvous.key(secret, peer.value),
                "value" to Rendezvous.seal(secret, signed)))
            if (pendingInvites.any { it.first == remote && it.second == secret }) {
                sendNative(obj("op" to "publish", "key" to Rendezvous.key(secret, "join"),
                    "value" to Rendezvous.seal(secret, signed)))
            }
            val key = Rendezvous.key(secret, remote.ifBlank { "join" })
            if (rendezvousLookups.size >= 100) rendezvousLookups.remove(rendezvousLookups.keys.first())
            rendezvousLookups[key] = remote to secret
            sendNative(obj("op" to "lookup", "key" to key))
        }
    }

    private suspend fun applyRendezvous(event: JSONObject) {
        val (expected, secret) = rendezvousLookups[event.getString("key")] ?: return
        if (expected.isBlank() && records.get("invite:" + secret)?.data()?.optLong("expires", 0)
                ?.let { it <= System.currentTimeMillis() } != false) return
        val (record, grant) = runCatching {
            val signed = JSONObject(Rendezvous.open(secret, event.getString("value")))
            val payload = signed.getString("payload")
            val data = JSONObject(payload)
            val id = data.optString("peer")
            val certificate = devices.checked(data.optString("grant")) ?: return
            if (certificate.account != id || id == peer.value || (expected.isNotBlank() && id != expected) ||
                !NativeEngine.verify(payload, certificate.device, signed.getString("public"), signed.getString("signature"))) {
                return
            }
            data to certificate
        }.getOrNull() ?: return
        val remote = grant.account
        if (records.get(remote)?.data()?.optBoolean("blocked") == true) return
        val now = System.currentTimeMillis()
        if (record.optLong("expires") !in now..(now + 180_000) || !devices.accept(grant)) return
        val candidates = record.strings("routes").filter { it.length < 192 }.take(12)
        route(remote, candidates)
        if (expected.isNotBlank()) {
            outboxLock.withLock {
                retryAfter.keys.removeIf { it.startsWith("out:" + remote + ":") || it.startsWith("stream:" + remote + ":") }
            }
        }
        val udp = candidates.filter { it.contains("/quic-v1/p2p/") && isPublicCandidate(it) }.take(2)
        if (udp.isNotEmpty() && remote !in punches && punches.size < 24) {
            val listener = peer.value < remote
            val round = ((now + 1_000) / 8_000 + 1) * 8_000
            punches[remote] = Punch(round + if (listener) 0 else 350, udp, listener)
        }
    }

    private fun runPunches() {
        val now = System.currentTimeMillis()
        val ready = punches.entries.filter { it.value.at <= now }
        for ((remote, attempt) in ready) {
            punches.remove(remote)
            if (now - attempt.at < 3_000) {
                sendNative(obj("op" to "punch", "peer" to transportFor(remote), "addresses" to jsonArray(attempt.candidates),
                    "listener" to attempt.listener))
            }
        }
    }

    private fun isPublicCandidate(address: String): Boolean {
        val parts = address.split('/')
        if (parts.size < 6 || parts[1] !in listOf("ip4", "ip6")) return false
        val host = parts[2]
        if (host.any { it !in "0123456789abcdefABCDEF:." }) return false
        val ip = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return false
        if (ip.isAnyLocalAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress || ip.isMulticastAddress || ip.isLoopbackAddress) return false
        val raw = ip.address
        val first = raw[0].toInt() and 255
        val second = raw[1].toInt() and 255
        return if (raw.size == 4) first !in listOf(0, 127) && !(first == 100 && second in 64..127) else (first and 0xe0) == 0x20
    }

    private suspend fun handleNative(event: JSONObject) {
        when (event.optString("kind")) {
            "listen" -> listenerState.value = listenerState.value + event.getString("address")
            "rendezvous" -> applyRendezvous(event)
            "peer" -> {
                val id = event.getString("peer")
                val account = devices.accountFor(id)
                val observed = event.optString("observed")
                if (event.optBoolean("seed") && observed.endsWith("/quic-v1") && isPublicCandidate(observed)) {
                    val device = credentials?.grant?.device ?: return
                    observedRoutes[observed + "/p2p/" + device] = System.currentTimeMillis() + 90_000
                    if (observedRoutes.size > 4) observedRoutes.remove(observedRoutes.keys.first())
                }
                if (event.optBoolean("seed") && event.strings("addresses").isNotEmpty()) {
                    val seed = obj("peer" to id, "routes" to jsonArray(event.strings("addresses").take(6)))
                    save("seed:" + id, "discovery-peer", seed)
                    val cache = records.all("discovery-peer")
                    cache.take((cache.size - 20).coerceAtLeast(0)).forEach { records.remove(it.key) }
                }
                val known = account?.let { records.get(it)?.takeIf { row -> row.kind == "contact" } }
                if (known != null) change(known.key) {
                    put("routes", jsonArray((event.strings("addresses") + strings("routes")).distinct().take(12)))
                }
            }
            "result" -> outboxLock.withLock {
                val ticket = event.getString("ticket")
                val entry = pending.entries.firstOrNull { it.value.ticket == ticket } ?: return@withLock
                if (transportFor(entry.value.account) == entry.value.device) {
                    handleResult(entry.key, event.optString("status"), entry.value.offset)
                } else {
                    pending.remove(entry.key)
                }
            }
            "incoming" -> {
                val transport = event.getString("peer")
                val status = try {
                    acceptWireMessage(transport, JSONObject(event.getString("payload")))
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    "retry"
                }
                sendNative(obj("op" to "ack", "ticket" to event.getLong("ticket"), "status" to status))
            }
        }
    }

    private suspend fun acceptWireMessage(
        transport: String, wire: JSONObject, chatmailChat: Int = 0, file: File? = null, chatmailMessage: Int = 0
    ): String {
        val grant = devices.checked(wire.optString("grant")) ?: return "rejected"
        val sender = grant.account
        if (grant.device != transport || sender == peer.value) return "rejected"
        val data = wire.optJSONObject("data") ?: return "rejected"
        val type = data.optString("type")
        if (devices.find(sender) == null && !canLearnDevice(sender, type, data)) {
            return if (type == "hello") "rejected" else "unauthorized"
        }
        if (!devices.accept(grant)) return "rejected"
        val notificationCandidate = type in notificationTypes
        val receipt = if (notificationCandidate) receiptKey(sender, data) else ""
        val seen = receipt.isNotEmpty() && records.get(receipt) != null
        val existingInvite = if (type == "hello") records.get("request:" + sender)?.data()?.optString("token") else null
        val alreadyPaired = type == "hello" && matchesInviteToken(
            records.get(sender)?.takeIf { it.kind == "contact" }?.data(), data.optString("token"))
        val result = receive(sender, grant.revision, data, chatmailChat, file, chatmailMessage)
        if (type == "hello" && result == "paired" && !alreadyPaired) {
            runCatching { alerts.show(AlertArea.CONTACTS, sender, "Contact added",
                data.optString("name", "New contact").take(60)) }
        }
        if ((notificationCandidate && !seen && result == "ok" && records.get(receipt) != null &&
                (type != "messageReaction" || records.get("reaction:" + data.optString("message") + ":" + sender)
                    ?.data()?.optString("id") == data.optString("id"))) ||
            (type == "hello" && result == "waiting" && existingInvite != data.optString("token"))) {
            runCatching { notifyReceived(sender, data) }
        }
        return result
    }

    private suspend fun canLearnDevice(account: String, type: String, payload: JSONObject): Boolean {
        if (records.get(account)?.kind == "contact" || records.get("request:" + account)?.kind == "invite-pending") return true
        if (type == "hello") {
            val token = payload.optString("token")
            if (token.length == 32 &&
                (records.get("invite:" + token)?.takeIf { it.kind == "invite-out" }?.data()?.optLong("expires") ?: 0) > System.currentTimeMillis()) return true
        }
        val group = payload.optString("group")
        if (!isId(group)) return false
        val members = records.get(group)?.takeIf { it.kind == "group" }?.data()?.strings("members").orEmpty()
        return account in members && peer.value in members
    }

    private suspend fun dispatch() = outboxLock.withLock {
        val account = credentials ?: return@withLock
        val now = android.os.SystemClock.elapsedRealtime()
        pending.entries.removeIf { now - it.value.started >= SEND_TIMEOUT }
        retryAfter.entries.removeIf { now >= it.value }
        val attemptsByPeer = pending.values.groupingBy { it.account }.eachCount().toMutableMap()
        val outbox = records.all("outbox")
        val streams = records.all("stream")
        for (record in (outbox + streams)) {
            if (pending.size >= 12) break
            if (record.key in pending || record.key in retryAfter) continue
            val data = record.data()
            val to = data.getString("to")
            val attempts = attemptsByPeer[to] ?: 0
            if (attempts >= 2) continue
            val contact = records.get(to)?.data()
            if (contact?.optBoolean("blocked") == true) continue
            // Only the dispatcher that owns the transport may expire or reject this row.
            if (record.kind == "outbox" && (chatmailRoute(data, contact) > 0 ||
                    data.optJSONObject("payload")?.optString("type") == "chatmailAck")) {
                continue
            }
            val payload = if (record.kind == "stream") makeChunk(record) ?: continue else data.getJSONObject("payload")
            val expires = payload.optLong("expires")
            if (payload.optString("type") == RETIRED_TALK_PACKET) {
                handleResult(record.key, "rejected")
                continue
            }
            if (payload.optString("type") == "hello") {
                val request = records.get("request:" + to)?.takeIf { it.kind == "invite-pending" }?.data()
                if (request?.optString("token") != payload.optString("token") ||
                    (expires > 0 && expires <= System.currentTimeMillis())) {
                    handleResult(record.key, "rejected")
                    continue
                }
                payload.put("routes", jsonArray(localAddresses()))
            }
            if (payload.optString("type") in listOf("message", "offer") && payload.optString("group").isNotEmpty() &&
                to !in recipients(payload.optString("group"))) {
                handleResult(record.key, "rejected")
                continue
            }
            val addresses = contact?.strings("routes").orEmpty().ifEmpty {
                records.get("request:" + to)?.data()?.strings("routes").orEmpty()
            }
            route(to, addresses)
            val ticket = newId()
            val device = transportFor(to)
            val wire = obj("grant" to account.grant.encoded, "data" to payload)
            val firstAttempt = record.kind == "outbox" && payload.optString("type") == "message" &&
                data.has("submitted") && !data.optBoolean("submitted")
            if (firstAttempt) change(record.key) { put("submitted", true) }
            if (sendNative(obj("op" to "send", "ticket" to ticket, "peer" to device, "payload" to wire.toString()))) {
                val offset = if (record.kind == "stream") payload.getLong("offset") else null
                pending[record.key] = Attempt(ticket, now, offset, to, device)
                attemptsByPeer[to] = attempts + 1
            } else if (firstAttempt) {
                change(record.key) { put("submitted", false) }
            }
        }
    }

    private suspend fun handleResult(ticket: String, status: String, sentOffset: Long? = null) {
        pending.remove(ticket)
        val record = records.get(ticket) ?: return
        val queued = if (record.kind == "outbox") record.data().optJSONObject("payload") else null
        if (queued?.optString("type") == "download" && status in setOf("unavailable", "rejected")) {
            database.withTransaction {
                markDownloadUnavailable(queued.optString("file"), queued.optString("id"))
                records.remove(ticket)
            }
            return
        }
        if (queued?.optString("type") == "hello") {
            val remote = record.data().getString("to")
            val requestKey = "request:" + remote
            if (status == "retry") {
                val request = records.get(requestKey)?.takeIf { it.kind == "invite-pending" }?.data()
                if (request != null && matchesInviteToken(request, queued.optString("token")) &&
                    !request.has("chatmailChat") && request.optString("state") == INVITE_CONNECTING && request.strings("routes").isNotEmpty()) {
                    change(requestKey) { put("state", INVITE_DIRECT_FAILED) }
                }
            }
            if (status == "waiting") {
                val request = records.get(requestKey)?.takeIf { it.kind == "invite-pending" }?.data()
                if (request != null && matchesInviteToken(request, queued.optString("token")) &&
                    isActiveInviteRequest(request, System.currentTimeMillis())) {
                    change(requestKey) {
                        put("state", INVITE_APPROVAL)
                        put("acknowledgedAt", System.currentTimeMillis())
                    }
                }
                retryAfter[ticket] = android.os.SystemClock.elapsedRealtime() + 15_000
                return
            }
            if (status == "paired") {
                database.withTransaction {
                    val request = records.get(requestKey)?.takeIf { it.kind == "invite-pending" }
                    if (request != null && canAcceptPairing(request.data(), queued.optString("token"),
                            record.data().optInt("chatmailChat"), System.currentTimeMillis())) {
                        val incoming = request.data()
                        val previous = records.get(remote)?.takeIf { it.kind == "contact" }?.data()
                        if (previous?.optBoolean("blocked") == true) {
                            records.remove(ticket)
                            return@withTransaction
                        }
                        save(remote, "contact", previous?.apply {
                            put("token", incoming.getString("token"))
                            if (incoming.has("chatmailChat")) {
                                put("chatmailChat", incoming.getInt("chatmailChat"))
                                put("chatmailRevision", incoming.getLong("chatmailRevision"))
                            }
                        } ?: incoming)
                        records.remove(requestKey)
                    }
                    records.remove(ticket)
                }
                return
            }
            if (status == "rejected") {
                database.withTransaction {
                    val request = records.get(requestKey)?.takeIf { it.kind == "invite-pending" }
                    if (request?.data()?.optString("token") == queued.optString("token")) {
                        change(requestKey) { put("state", INVITE_REJECTED) }
                    }
                    records.remove(ticket)
                }
                return
            }
        }
        if (queued?.optString("type") == "friendRequest" && status == "rejected") {
            val remote = record.data().getString("to")
            database.withTransaction {
                change(remote) { put("friendSent", false) }
                records.remove(ticket)
            }
            notice.value = "A friend request couldn’t be delivered. You can try again from Social."
            return
        }
        if (status == "retry" || status == "paused" || status == "unauthorized") {
            retryAfter[ticket] = android.os.SystemClock.elapsedRealtime() + if (status == "unauthorized") 30_000 else 7_000
            return
        }
        if (record.kind == "stream" && status.startsWith("next:")) {
            val value = status.substringAfter(':').toLongOrNull() ?: return
            val stream = record.data()
            val size = records.get(stream.optString("file"))?.data()?.optLong("size") ?: return
            if (sentOffset != null && sentOffset == stream.optLong("offset") && value > sentOffset && value <= size) {
                change(ticket) { put("offset", value) }
            }
            return
        }
        if (status != "ok" && status != "complete") {
            database.withTransaction {
                val payload = record.data().optJSONObject("payload")
                if (payload?.optString("type") in listOf("message", "offer")) {
                    change(payload!!.getString("id")) { put("failed", optInt("failed") + 1) }
                }
                records.remove(ticket)
            }
            return
        }
        database.withTransaction {
            if (record.kind == "outbox") {
                val payload = record.data().optJSONObject("payload")
                if (payload?.optString("type") in listOf("message", "offer")) {
                    change(payload!!.getString("id")) { put("delivered", optInt("delivered") + 1) }
                }
            }
            records.remove(ticket)
        }
    }

    private suspend fun makeChunk(stream: Record): JSONObject? {
        val request = stream.data()
        val id = request.getString("file")
        val source = File(outgoing, id)
        val offer = records.get(id)?.takeIf { it.kind == "file-sent" }?.data()
        val recipient = request.getString("to")
        if (offer == null || !mayDownload(recipient, offer) || !source.isFile || source.length() != offer.optLong("size")) {
            database.withTransaction {
                records.remove(stream.key)
                queue(recipient, obj("type" to "downloadUnavailable", "id" to newId(),
                    "file" to id, "download" to request.optString("download")))
            }
            return null
        }
        val offset = request.getLong("offset")
        if (offset !in 0..source.length()) {
            records.remove(stream.key)
            return null
        }
        val bytes = ByteArray(minOf(CHUNK.toLong(), source.length() - offset).toInt())
        RandomAccessFile(source, "r").use { it.seek(offset); it.readFully(bytes) }
        return obj("type" to "chunk", "id" to id, "download" to request.getString("download"),
            "offset" to offset, "bytes" to encode(bytes), "digest" to digest(bytes))
    }

    private fun receiptKey(sender: String, data: JSONObject) =
        "seen:" + sender + ":" + data.optString("id") + ":" + data.optInt("revision") + ":" + data.optInt("part")

    private suspend fun notifyReceived(sender: String, data: JSONObject) {
        val contact = records.get(sender)?.takeIf { it.kind == "contact" }?.data()
        val person = contact?.optString("name")?.ifBlank { "Contact" } ?: "P2P contact"
        val group = data.optString("group").takeIf(::isId)
        val thread = group ?: sender
        val muted = records.get("thread-pref:" + thread)?.data()?.optBoolean("muted") == true ||
            contact?.optBoolean("alertMuted") == true
        when (data.optString("type")) {
            "hello" -> {
                val request = records.get("request:" + sender)?.takeIf { it.kind == "invite-in" } ?: return
                alerts.show(AlertArea.CONTACTS, sender, "Contact request", request.data().optString("name"))
            }
            "message", "messageReaction", "offer" -> {
                if (data.optBoolean("voicemail")) {
                    if (!muted) alerts.show(AlertArea.MISSED_CALLS, data.optString("id"), person, "New voicemail")
                    return
                }
                if (muted || (visible && visibleThread == thread)) return
                val type = data.optString("type")
                if (type == "offer" && data.optString("post").isNotEmpty()) return
                if (type == "offer") {
                    alerts.show(AlertArea.FILES, data.optString("id"), person, "Sent a file")
                    return
                }
                val area = if (group == null) AlertArea.CHATS else AlertArea.GROUPS
                val title = if (group == null) person else records.get(group)?.data()?.optString("name") ?: "Group"
                alerts.show(area, thread, title, if (type == "message") "New message" else "New reaction")
            }
            "friendRequest", "friendResponse", "post", "reaction", "reply" -> {
                if (contact?.optBoolean("muted") == true || contact?.optBoolean("alertMuted") == true) return
                val body = when (data.optString("type")) {
                    "friendRequest" -> "Friend request"
                    "friendResponse" -> if (data.optBoolean("accept")) "Accepted your friend request" else return
                    "post" -> "Shared a new post"
                    "reaction" -> "Reacted to a post"
                    else -> "Replied to a post"
                }
                alerts.show(AlertArea.SOCIAL, data.optString("post").ifBlank { data.optString("id") }, person, body)
            }
        }
    }

    private suspend fun receive(
        sender: String, deviceRevision: Long, data: JSONObject, chatmailChat: Int = 0,
        file: File? = null, chatmailMessage: Int = 0
    ): String {
        if (!isPeer(sender)) return "rejected"
        val id = data.optString("id")
        if (!isId(id)) return "rejected"
        val type = data.optString("type")
        val seen = receiptKey(sender, data)
        // An acknowledgement lost in transit must report the original successful delivery.
        if (type in setOf("message", "offer", "messageReaction") && records.get(seen) != null) return "ok"
        val contact = records.get(sender)?.takeIf { it.kind == "contact" }?.data()
        if (contact?.optBoolean("blocked") == true) return "rejected"
        if (type == RETIRED_TALK_PACKET) return "rejected"
        val group = data.optString("group")
        val post = data.optString("post")
        if (isId(group) && type !in setOf("group", "deviceUpdate", "offerCancel") && groupBlocked(group)) return "rejected"
        val thread = if (isId(group)) group else if (type == "offer" && post.isNotEmpty()) "" else sender
        val isMember = if (isId(group)) {
            val members = records.get(group)?.takeIf { it.kind == "group" }?.data()?.strings("members").orEmpty()
            sender in members && peer.value in members
        } else false
        if (type == "chunk") return downloadLock.withLock { receiveChunk(sender, id, data) }
        if (type == "downloadUnavailable") return downloadLock.withLock {
            database.withTransaction {
                val file = records.get(data.optString("file"))?.takeIf { it.kind == "file-received" }
                if (file?.data()?.optString("author") != sender) return@withTransaction "rejected"
                markDownloadUnavailable(file.key, data.optString("download"))
                "ok"
            }
        }
        if (type == "offerCancel") return downloadLock.withLock { receiveOfferCancellation(sender, data, contact != null || isMember) }
        if (type == "hello") return receiveInvite(sender, data, contact, chatmailChat, deviceRevision)
        if (type == "inviteDeclined" && chatmailChat > 0) return database.withTransaction {
            val key = "request:" + sender
            val request = records.get(key)?.takeIf { it.kind == "invite-pending" }?.data()
            if (request?.optString("token") == data.optString("token")) {
                change(key) { put("state", INVITE_REJECTED) }
            }
            "ok"
        }
        return database.withTransaction {
            if (records.get(seen) != null) return@withTransaction "ok"
            val status = when (type) {
                "paired" -> {
                    val current = records.get(sender)?.takeIf { it.kind == "contact" }?.data()
                    if (current?.optBoolean("blocked") == true) return@withTransaction "rejected"
                    val token = data.optString("token")
                    if (matchesInviteToken(current, token) && (current?.optInt("chatmailChat") ?: 0) == chatmailChat) {
                        receiveProfile(sender, deviceRevision, data.optJSONObject("profile"))
                        return@withTransaction "ok"
                    }
                    val invite = records.get("request:" + sender)?.takeIf { it.kind == "invite-pending" }
                    if (invite == null || !canAcceptPairing(invite.data(), token, chatmailChat,
                            System.currentTimeMillis(), data.optLong("approvedAt"))) {
                        "rejected"
                    } else {
                        val incoming = invite.data().apply { put("name", data.optString("name", "P2P contact").take(60)) }
                        save(sender, "contact", current?.apply {
                            put("token", incoming.getString("token"))
                            if (chatmailChat > 0) {
                                put("chatmailChat", chatmailChat)
                                put("chatmailRevision", deviceRevision)
                            }
                        } ?: incoming)
                        records.remove(invite.key)
                        inviteHellos(sender, token).forEach { records.remove(it.key) }
                        receiveProfile(sender, deviceRevision, data.optJSONObject("profile"))
                        "ok"
                    }
                }
                "profileUpdate" -> if (contact != null) {
                    receiveProfile(sender, deviceRevision, data.optJSONObject("profile"))
                    "ok"
                } else "rejected"
                "deviceUpdate" -> if (contact != null || isMember) {
                    val addresses = data.strings("routes").filter { it.length < 192 }.take(12)
                    route(sender, addresses)
                    if (contact != null) change(sender) { put("routes", jsonArray(addresses)) }
                    "ok"
                } else "unauthorized"
                "group" -> receiveGroup(sender, id, data, contact)
                "message", "offer" -> {
                    if (type == "offer" && records.get("withdrawn:" + sender + ":" + id) != null) {
                        "rejected"
                    } else if (type == "offer" && post.isNotEmpty() && (contact?.optBoolean("friend") != true ||
                            records.get(post)?.takeIf { it.kind == "post" }?.data()?.optString("author") != sender)) {
                        "unauthorized"
                    } else if ((group.isNotEmpty() && !isMember) || (group.isEmpty() && contact == null)) {
                        "unauthorized"
                    } else if (records.get(id) != null) {
                        "rejected"
                    } else {
                        val at = System.currentTimeMillis()
                        data.put("locallyDeleted", false)
                        data.put("thread", thread).put("author", sender).put("at", at)
                            .put("unread", thread.isNotEmpty()).remove("star")
                        if (type == "offer") {
                            if (data.optLong("size", -1) < 0 || !data.optString("digest").matches(Regex("[0-9a-f]{64}"))) {
                                return@withTransaction "rejected"
                            }
                            data.put("name", data.optString("name").take(180).ifBlank { "Shared file" })
                                .put("mime", data.optString("mime").take(140).ifBlank { "application/octet-stream" })
                                .put("state", "Ready to download").put("offset", 0L)
                            data.remove("chatmailAttachment")
                            if (chatmailChat > 0) {
                                if (file == null || !storeChatmailFile(file, id, data)) return@withTransaction "rejected"
                                data.put("state", "Complete").put("offset", data.getLong("size"))
                                if (chatmailMessage > 9) data.put("chatmailAttachment", chatmailMessage)
                            }
                            if (post.isNotEmpty()) data.put("listed", false)
                            if (chatmailChat == 0 && thread.isNotEmpty() && shouldFetchInlineMedia(data) &&
                                context.filesDir.usableSpace > data.optLong("size")) {
                                queueDownload(id, data, 0L)
                            }
                        } else {
                            if (data.optString("text").isBlank() || data.optString("text").length > MAX_MESSAGE_LENGTH) return@withTransaction "rejected"
                            if (!isId(data.optString("replyTo"))) data.remove("replyTo")
                        }
                        receiveProfile(sender, deviceRevision, data.optJSONObject("profile"))
                        data.remove("profile")
                        save(id, if (type == "message") "message" else "file-received", data, at)
                        records.remove("folder:" + thread)
                        "ok"
                    }
                }
                "messageReaction" -> {
                    val originalId = data.optString("message")
                    val original = records.get(originalId)?.takeIf(Record::isThreadMessage)?.data()
                    val valid = if (group.isNotEmpty()) isMember else contact != null
                    if (!valid) "unauthorized"
                    else if (!isPickerEmoji(context, data.optString("text"))) "rejected"
                    else if (original == null) {
                        if (!isId(originalId)) "rejected"
                        else if (records.get("discarded-message:" + originalId)?.data()?.optString("thread") == thread) "ok"
                        else "unauthorized"
                    } else if (original.optString("thread").ifBlank { original.optString("retainedThread") } != thread) "rejected"
                    else if (original.optString("thread").isBlank()) "ok"
                    else {
                        val key = "reaction:" + originalId + ":" + sender
                        val sequence = data.optLong("sequence")
                        if (sequence < 0L) return@withTransaction "rejected"
                        if (shouldApplyReaction(records.get(key)?.data(), deviceRevision, sequence)) {
                            save(key, "message-reaction", data.put("author", sender).put("deviceRevision", deviceRevision))
                        }
                        "ok"
                    }
                }
                "download" -> startStream(sender, data)
                "friendRequest" -> if (contact != null) {
                    if (contact.optBoolean("friendSent") && !contact.optBoolean("friend")) {
                        change(sender) { put("friend", true); put("friendSent", false); put("friendRequest", false) }
                        queue(sender, obj("type" to "friendResponse", "id" to newId(), "accept" to true))
                        recentPosts(sender)
                    } else change(sender) { if (!optBoolean("friend")) put("friendRequest", true) }
                    "ok"
                } else "unauthorized"
                "friendResponse" -> if (contact?.optBoolean("friendSent") == true) {
                    change(sender) {
                        put("friendSent", false)
                        if (data.optBoolean("accept")) { put("friend", true); put("friendRequest", false) }
                    }
                    if (data.optBoolean("accept")) recentPosts(sender)
                    "ok"
                } else "unauthorized"
                "post" -> if (contact?.optBoolean("friend") == true && records.get(id) == null &&
                    data.optString("text").length <= 12_000 &&
                    (data.optString("text").isNotBlank() || data.optInt("media") > 0)) {
                    save(id, "post", data.put("author", sender).put("unread", true).put("locallyDeleted", false)); "ok"
                } else "unauthorized"
                "reaction", "reply" -> if (contact?.optBoolean("friend") == true &&
                    records.get(data.optString("post"))?.kind == "post" && records.get(id) == null &&
                    data.optString("text").length in 1..4_000) {
                    val text = data.optString("text")
                    if (type == "reaction" && !isPickerEmoji(context, text)) "rejected"
                    else {
                        if (type != "reaction" || !hasSocialReaction(post, sender, text)) {
                            save(id, "social-action", data.put("author", sender).put("unread", true))
                        }
                        "ok"
                    }
                } else "unauthorized"
                else -> "rejected"
            }
            if (status == "ok") save(seen, "receipt", obj())
            status
        }
    }

    private suspend fun receiveOfferCancellation(sender: String, data: JSONObject, authorized: Boolean): String {
        val id = data.optString("file")
        if (!authorized || !isId(id)) return "unauthorized"
        return database.withTransaction {
            val record = records.get(id)
            if (record != null && (record.kind != "file-received" || record.data().optString("author") != sender)) {
                return@withTransaction "rejected"
            }
            save("withdrawn:" + sender + ":" + id, "receipt", obj())
            if (record != null && record.data().optString("state") != "Complete") {
                change(id) { put("state", "Withdrawn"); put("unread", false) }
                records.all("outbox").filter {
                    val payload = it.data().optJSONObject("payload")
                    payload?.optString("type") == "download" && payload.optString("file") == id
                }.forEach { records.remove(it.key) }
                File(partial, id).delete()
            }
            "ok"
        }
    }

    internal suspend fun receiveInvite(
        sender: String, data: JSONObject, contact: JSONObject?, chatmailChat: Int, deviceRevision: Long
    ): String {
        val token = data.optString("token")
        if (token.length != 32 || contact?.optBoolean("blocked") == true) return "rejected"
        if (matchesInviteToken(contact, token) && (chatmailChat == 0 || contact?.optInt("chatmailChat") == chatmailChat)) {
            return "paired"
        }
        return database.withTransaction {
            val current = records.get(sender)?.takeIf { it.kind == "contact" }?.data()
            if (current?.optBoolean("blocked") == true) return@withTransaction "rejected"
            if (matchesInviteToken(current, token) && (chatmailChat == 0 || current?.optInt("chatmailChat") == chatmailChat)) {
                return@withTransaction "paired"
            }
            val invitation = records.get("invite:" + token)?.takeIf { it.kind == "invite-out" }?.data()
            if (invitation == null || invitation.optBoolean("chatmail") != (chatmailChat > 0) ||
                (chatmailChat == 0 && current != null) || invitation.optLong("expires") <= System.currentTimeMillis()) {
                return@withTransaction "rejected"
            }
            val key = "request:" + sender
            val previous = records.get(key)
            val previousData = previous?.data()
            if (previous?.kind == "invite-pending" && previousData != null &&
                (chatmailChat == 0 || previousData.optInt("chatmailChat") > 0) &&
                keepOutgoingInvite(peer.value, sender, previousData, System.currentTimeMillis())) {
                // Both peers keep the invite created by the account that sorts first.
                return@withTransaction "retry"
            }
            val addresses = data.strings("routes").filter { it.length < 192 }.take(12)
            route(sender, addresses)
            if (previous?.kind != "invite-in" || previousData?.optString("token") != token ||
                invitation.optBoolean("autoAccept")) {
                val request = obj("peer" to sender, "token" to token,
                    "name" to data.optString("name", "New contact").take(60), "routes" to jsonArray(addresses))
                if (chatmailChat > 0) request.put("chatmailChat", chatmailChat).put("chatmailRevision", deviceRevision)
                data.optJSONObject("profile")?.let { profile ->
                    if (validProfilePhoto(profile.optString("photo"))) {
                        request.put("profile", profile).put("profileDeviceRevision", deviceRevision)
                    }
                }
                if (chatmailChat > 0 && invitation.optBoolean("autoAccept")) {
                    val approvedAt = System.currentTimeMillis()
                    if (approvedAt >= invitation.optLong("expires")) return@withTransaction "rejected"
                    completeInvite(request, approvedAt)
                    if (previous?.kind == "invite-pending") retireInviteHellos(sender)
                    records.remove(key)
                    // The durable paired message carries the signed approval time even after expiry.
                    return@withTransaction "paired"
                }
                save(key, "invite-in", request)
            } else if (addresses.isNotEmpty() && previousData.strings("routes") != addresses) {
                change(key) { put("routes", jsonArray(addresses)) }
            }
            // Keep retrying from the importing phone. It may be able to dial the inviter while the
            // inviter cannot dial it back once an earlier connection has closed.
            "waiting"
        }
    }

    private suspend fun receiveGroup(sender: String, id: String, data: JSONObject, contact: JSONObject?): String {
        val current = records.get(id)
        val old = current?.takeIf { it.kind == "group" }?.data()
        if (contact == null || data.optString("owner") != sender ||
            (current != null && old?.optString("owner") != sender)) return "unauthorized"
        val revision = data.optInt("revision")
        if (revision <= 0) return "rejected"
        if (old != null && old.optInt("revision") >= revision) return "ok"
        val members = data.strings("members")
        val part = data.optInt("part", -1)
        val parts = data.optInt("parts")
        val title = data.optString("name").take(80)
        if (title.isBlank() || part !in 0 until parts || members.size !in 1..128 ||
            members.any { !isPeer(it) }) return "rejected"
        val prefix = "group-part:" + sender + ":" + id + ":" + revision + ":"
        val staged = records.all("group-part").filter { it.key.startsWith(prefix) }
        if (staged.any { !groupPageMatches(data, it.data()) }) return "rejected"
        val secret = data.optString("secret")
        if (secret.isNotEmpty() && (secret.length != 32 || runCatching { decode(secret).size }.getOrDefault(0) != 24)) {
            return "rejected"
        }
        val pages = (staged.filter { it.key != prefix + part }.map(Record::data) + data).sortedBy { it.optInt("part") }
        if (pages.size != parts) {
            save(prefix + part, "group-part", data)
            return "ok"
        }
        val roster = pages.flatMap { it.strings("members") }.distinct()
        if (sender !in roster || (old == null && peer.value !in roster)) {
            pages.forEach { records.remove(prefix + it.optInt("part")) }
            return "rejected"
        }
        if (peer.value in roster && secret.isEmpty()) {
            return "rejected"
        }
        save(id, "group", obj("type" to "group", "id" to id, "name" to title, "owner" to sender,
            "members" to jsonArray(roster), "revision" to revision, "secret" to secret))
        val groupPrefix = "group-part:" + sender + ":" + id + ":"
        records.all("group-part").filter { it.key.startsWith(groupPrefix) && it.data().optInt("revision") <= revision }
            .forEach { records.remove(it.key) }
        return "ok"
    }

    internal suspend fun startStream(sender: String, request: JSONObject): String {
        val file = request.optString("file")
        if (!isId(file)) return "rejected"
        val offer = records.get(file)?.takeIf { it.kind == "file-sent" }?.data() ?: return "rejected"
        val offset = request.optLong("offset", -1)
        if (!mayDownload(sender, offer)) return "unavailable"
        if (offset !in 0..offer.optLong("size")) return "rejected"
        val source = File(outgoing, file)
        if (!source.isFile || source.length() != offer.optLong("size")) return "unavailable"
        val prefix = "stream:" + sender + ":" + file + ":"
        val streams = records.all("stream").filter { it.key.startsWith(prefix) }
        val revision = request.optLong("revision")
        val download = request.getString("id")
        if (streams.any { stream ->
            val active = stream.data()
            active.optString("download") == download || active.optLong("revision") > revision
        }) return "ok"
        streams.forEach { records.remove(it.key) }
        save(prefix + download, "stream", obj("to" to sender, "file" to file, "offset" to offset,
            "download" to download, "revision" to revision))
        return "ok"
    }

    private suspend fun markDownloadUnavailable(file: String, download: String) {
        val record = records.get(file)?.takeIf { it.kind == "file-received" } ?: return
        val data = record.data()
        if (!isId(download) || data.optString("download") != download ||
            downloadState(data) !in setOf("Waiting to connect", "Downloading")) return
        save(file, record.kind, data.put("state", "Source unavailable"), record.created)
    }

    private suspend fun receiveChunk(sender: String, id: String, data: JSONObject): String {
        val record = records.get(id)?.takeIf { it.kind == "file-received" } ?: return "rejected"
        val file = record.data()
        if (file.optString("author") != sender) return "rejected"
        val group = file.optString("group")
        if (group.isNotEmpty() && sender !in recipients(group)) return "unauthorized"
        if (file.optString("post").isNotEmpty() && records.get(sender)?.data()?.optBoolean("friend") != true) return "unauthorized"
        val state = file.optString("state")
        if (state == "Complete") return "complete"
        if (file.optString("download") != data.optString("download")) return "rejected"
        if (state == "Paused") return "paused"
        if (downloadState(file) !in setOf("Waiting to connect", "Downloading")) return "rejected"
        val bytes = runCatching { decode(data.optString("bytes")) }.getOrNull() ?: return "rejected"
        if (bytes.size > CHUNK) return "rejected"
        return when (val result = TransferDisk.commit(
            File(partial, id), File(incoming, id), file.optLong("offset"), data.optLong("offset", -1),
            file.optLong("size"), bytes, data.optString("digest"), file.optString("digest")
        )) {
            is TransferDisk.Result.Committed -> {
                change(id) {
                    put("offset", result.offset)
                    put("state", if (result.complete) "Complete" else "Downloading")
                }
                if (result.complete) "complete" else "next:" + result.offset
            }
            TransferDisk.Result.Retry -> "retry"
            TransferDisk.Result.Restart, TransferDisk.Result.IntegrityFailure -> {
                change(id) {
                    put("offset", 0L)
                    put("state", if (result == TransferDisk.Result.Restart) "Download interrupted" else "Verification failed")
                }
                "rejected"
            }
        }
    }
}
