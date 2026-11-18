package app.p2p

import android.content.Context
import java.io.File
import java.security.KeyStore

/** Called only after all models/Core handles for this profile have stopped. */
internal fun deleteProfileFiles(context: Context, id: String) {
    val storage = ProfileStorage(context, id)
    fun remove(file: File) {
        check(!file.exists() || file.deleteRecursively()) { "Couldn’t remove all profile files. Retry deletion." }
    }
    if (id == PRIMARY_PROFILE) {
        remove(storage.noBackup("chatmail"))
        remove(storage.noBackup("restore-attachments"))
    } else remove(File(context.noBackupFilesDir, "relay-profiles/$id"))
    for (name in listOf("sent", "received", "partial")) {
        val directory = storage.files(name)
        if (id == PRIMARY_PROFILE) {
            // Additional profiles are nested below the original media directories.
            directory.listFiles()?.filter { it.name != "relay-profiles" }?.forEach(::remove)
        } else remove(directory)
    }
    val database = context.getDatabasePath(storage.scoped("p2p.db"))
    check(!database.exists() || context.deleteDatabase(storage.scoped("p2p.db"))) { "Couldn’t delete the profile database." }
}

internal fun deleteProfileStorage(context: Context, id: String) {
    deleteProfileFiles(context, id)
    val storage = ProfileStorage(context, id)
    for (name in listOf("identity", "first-run-setup", "webxdc-sites")) {
        check(storage.preferences(name).edit().clear().commit()) { "Couldn’t clear profile settings." }
        context.deleteSharedPreferences(storage.scoped(name))
    }
    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(storage.scoped("app.p2p.identity.v1"))
}
