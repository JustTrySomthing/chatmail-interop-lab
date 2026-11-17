package app.p2p

import android.app.Application
import android.content.Context
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

internal const val PRIMARY_PROFILE = "primary"
internal const val EXTRA_PROFILE = "relay_profile"

internal class ProfileStorage(private val context: Context, val id: String) {
    init { require(id == PRIMARY_PROFILE || isId(id)) }

    fun scoped(name: String): String = if (id == PRIMARY_PROFILE) name else "$name-$id"

    fun noBackup(name: String): File = if (id == PRIMARY_PROFILE) File(context.noBackupFilesDir, name)
        else File(context.noBackupFilesDir, "relay-profiles/$id/$name")

    fun files(name: String): File = if (id == PRIMARY_PROFILE) File(context.filesDir, name)
        else File(context.filesDir, "$name/relay-profiles/$id")

    fun preferences(name: String) = context.getSharedPreferences(scoped(name), Context.MODE_PRIVATE)

    fun chatmailFile(path: String): File? = runCatching {
        val root = noBackup("chatmail").canonicalFile.toPath()
        File(path).canonicalFile.takeIf { it.isFile && it.toPath().startsWith(root) }
    }.getOrNull()
}

internal class ProfileSelection(context: Context) {
    private val preferences = context.getSharedPreferences("relay-profiles", Context.MODE_PRIVATE)

    fun ids(): Set<String> = preferences.getStringSet("profiles", emptySet()).orEmpty().filter(::isId).toSet() + PRIMARY_PROFILE

    private fun disabledIds(): Set<String> = preferences.getStringSet("disabled", emptySet()).orEmpty()

    fun isEnabled(id: String): Boolean = id in ids() && id !in disabledIds()

    fun setEnabled(id: String, enabled: Boolean) {
        require(id in ids()) { "This profile is unavailable." }
        check(enabled || id != active()) { "Switch profiles before disabling the active profile." }
        val next = if (enabled) disabledIds() - id else disabledIds() + id
        check(preferences.edit().putStringSet("disabled", next).commit()) { "Couldn’t update this profile." }
    }

    fun active(): String = preferences.getString("active", PRIMARY_PROFILE)?.takeIf { it in ids() } ?: PRIMARY_PROFILE

    fun select(id: String?): String {
        val profiles = ids()
        val next = id ?: newId()
        require(id == null || isEnabled(next)) { "Enable this profile before switching to it." }
        check(preferences.edit().putStringSet("profiles", (profiles + next) - PRIMARY_PROFILE)
            .putString("active", next).commit()) { "Couldn’t save the selected profile." }
        return next
    }
}

internal data class RelayProfileOption(val id: String, val name: String, val enabled: Boolean)

internal class RelayProfiles(private val application: Application) {
    private val selection = ProfileSelection(application)
    private val incomingCalls = ProfileCallInbox(application)
    private fun newModel(id: String, background: Boolean = false) =
        P2pModel(application, id, background, ::onCallEvent)
    private val active = MutableStateFlow(newModel(selection.active()))
    val model = active.asStateFlow()
    private val changing = MutableStateFlow(false)
    val switching = changing.asStateFlow()
    private var foreground = false
    private val workerLock = Mutex()
    private val workers = mutableMapOf<String, P2pModel>()
    private var backgroundSessions = 0

    fun acceptsAlerts(id: String) = selection.isEnabled(id)

    private fun onCallEvent(profile: String, event: ChatmailCallEvent, caller: ChatmailCallContact?) {
        if (event.kind == "CallEnded" || event.kind == "IncomingCallAccepted") {
            clearIncomingCall(incomingCalls.withdraw(profile, event.id))
            return
        }
        if (event.kind != "IncomingCall" || profile == active.value.profileId ||
            caller == null || !selection.isEnabled(profile)) return
        val call = incomingCalls.offer(profile, event, System.currentTimeMillis()) ?: return
        val profileName = workers[profile]?.items?.value?.firstOrNull { it.kind == "profile" }
            ?.data()?.optString("name").orEmpty()
        val type = if (event.video) "Incoming video call" else "Incoming Talk call"
        LocalAlerts(application, profile).show(AlertArea.TALK, call.token, caller.name,
            if (profileName.isBlank()) type else "$type · $profileName", expiresAt = call.expiresAt)
    }

    private fun clearIncomingCall(call: ProfileIncomingCall?) {
        if (call != null) LocalAlerts(application, call.profile).clear(AlertArea.TALK, call.token)
    }

    @MainThread
    fun hasIncomingCall(profile: String, token: String): Boolean =
        selection.isEnabled(profile) && incomingCalls.find(profile, token, System.currentTimeMillis()) != null

    @MainThread
    suspend fun openIncomingCall(profile: String, token: String): String =
        requireNotNull(switchProfile(profile, token)) { "The incoming call is no longer ringing." }

    @MainThread
    suspend fun declineIncomingCall(profile: String, token: String) {
        workerLock.withLock {
            val call = incomingCalls.find(profile, token, System.currentTimeMillis()) ?: return
            if (!selection.isEnabled(profile)) return
            val owner = if (active.value.profileId == profile) active.value else workers[profile]
            if (owner != null) owner.declineProfileCall(call)
            else withContext(Dispatchers.IO) {
                val path = ProfileStorage(application, profile).noBackup("chatmail")
                if (path.isDirectory) Chatmail(path).use { core ->
                    if (core.isRingingCall(call.message)) core.endCall(call.message)
                }
            }
            clearIncomingCall(incomingCalls.withdraw(profile, call.message))
        }
        BackgroundMessages.start(application, catchUp = true)
    }

    private suspend fun reconcileWorkers() {
        val wanted = if (backgroundSessions > 0) selection.ids().filterTo(mutableSetOf()) { id ->
            id != active.value.profileId && selection.isEnabled(id) &&
                ProfileStorage(application, id).noBackup("chatmail").isDirectory
        } else emptySet()
        for (id in workers.keys.toList()) {
            if (id !in wanted) workers.remove(id)?.retireForProfileSwitch()
        }
        for (id in wanted - workers.keys) {
            workers[id] = newModel(id, background = true).also { it.enableBackgroundSync() }
        }
    }

    suspend fun withBackgroundSync(action: suspend () -> Unit) = coroutineScope {
        var acquired = false
        val selected = launch {
            model.collectLatest { it.withActiveSync { awaitCancellation() } }
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                workerLock.withLock {
                    backgroundSessions++
                    acquired = true
                    reconcileWorkers()
                }
            }
            action()
        } finally {
            selected.cancel()
            if (acquired) withContext(Dispatchers.Main.immediate + NonCancellable) {
                workerLock.withLock {
                    backgroundSessions--
                    reconcileWorkers()
                }
            }
        }
    }

    @MainThread
    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(NonCancellable) {
        workerLock.withLock {
            selection.setEnabled(id, enabled)
            reconcileWorkers()
            if (!enabled) {
                incomingCalls.withdraw(id)
                runCatching { LocalAlerts(application, id).clearProfileAlerts() }
            }
        }
    }

    fun hasOtherProfiles() = selection.ids().size > 1

    suspend fun options(): List<RelayProfileOption> = withContext(Dispatchers.IO) {
        selection.ids().map { id ->
            val storage = ProfileStorage(application, id)
            val databaseName = storage.scoped("p2p.db")
            val name = try {
                if (application.getDatabasePath(databaseName).isFile) {
                    val store = LocalStore.open(application, id)
                    try {
                        store.records().get("profile")?.data()?.optString("name").orEmpty()
                    } finally {
                        store.close()
                    }
                } else ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ""
            }
            RelayProfileOption(id, name.ifBlank {
                if (id == PRIMARY_PROFILE) "Original profile" else "New profile"
            }, selection.isEnabled(id))
        }.sortedWith(compareBy({ it.id != active.value.profileId }, { it.name.lowercase() }))
    }

    @MainThread
    fun setForeground(value: Boolean) {
        foreground = value
        if (!changing.value) active.value.setForeground(value)
    }

    @MainThread
    suspend fun switchTo(target: String?) {
        switchProfile(target, null)
    }

    private suspend fun switchProfile(target: String?, token: String?): String? {
        if (target == active.value.profileId && token == null) return null
        check(changing.compareAndSet(false, true)) { "Relay Lab is already switching profiles." }
        try {
            return withContext(NonCancellable) {
                workerLock.withLock {
                    val previous = active.value
                    val incoming = if (token == null) null else {
                        val profile = requireNotNull(target)
                        if (!selection.isEnabled(profile)) return@withLock null
                        incomingCalls.find(profile, token, System.currentTimeMillis()) ?: return@withLock null
                    }
                    if (target != previous.profileId) {
                        previous.checkCanSwitchProfiles()
                        val next = selection.select(target)
                        workers.remove(next)?.retireForProfileSwitch()
                        previous.retireForProfileSwitch()
                        active.value = newModel(next).also { it.setForeground(foreground) }
                        reconcileWorkers()
                    }
                    if (incoming == null) return@withLock null
                    try {
                        active.value.resumeProfileCall(incoming)
                    } finally {
                        clearIncomingCall(incomingCalls.withdraw(incoming.profile, incoming.message))
                    }
                }
            }
        } finally {
            changing.value = false
            active.value.setForeground(foreground)
        }
    }
}
