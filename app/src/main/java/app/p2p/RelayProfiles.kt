package app.p2p

import android.app.Application
import android.content.Context
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun active(): String = preferences.getString("active", PRIMARY_PROFILE)?.takeIf { it in ids() } ?: PRIMARY_PROFILE

    fun select(id: String?): String {
        val profiles = ids()
        val next = id ?: newId()
        require(id == null || next in profiles) { "This profile is unavailable." }
        check(preferences.edit().putStringSet("profiles", (profiles + next) - PRIMARY_PROFILE)
            .putString("active", next).commit()) { "Couldn’t save the selected profile." }
        return next
    }
}

internal data class RelayProfileOption(val id: String, val name: String)

internal class RelayProfiles(private val application: Application) {
    private val selection = ProfileSelection(application)
    private val active = MutableStateFlow(P2pModel(application, selection.active()))
    val model = active.asStateFlow()
    private val changing = MutableStateFlow(false)
    val switching = changing.asStateFlow()
    private var foreground = false

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
            })
        }.sortedWith(compareBy({ it.id != active.value.profileId }, { it.name.lowercase() }))
    }

    @MainThread
    fun setForeground(value: Boolean) {
        foreground = value
        if (!changing.value) active.value.setForeground(value)
    }

    @MainThread
    suspend fun switchTo(target: String?) {
        val previous = active.value
        if (target == previous.profileId) return
        check(changing.compareAndSet(false, true)) { "Relay Lab is already switching profiles." }
        try {
            withContext(NonCancellable) {
                previous.checkCanSwitchProfiles()
                val next = selection.select(target)
                previous.retireForProfileSwitch()
                runCatching { LocalAlerts(application, previous.profileId).clearProfileAlerts() }
                active.value = P2pModel(application, next).also { it.setForeground(foreground) }
            }
        } finally {
            changing.value = false
        }
    }
}
