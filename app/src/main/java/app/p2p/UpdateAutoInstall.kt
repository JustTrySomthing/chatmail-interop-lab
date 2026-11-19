package app.p2p

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object UpdateAutoInstall {
    const val ENABLED = "autoInstallWifi"
    private const val ATTEMPTED = "autoInstallAttempted"
    private const val SESSION_ID = "autoInstallSession"
    private const val JOB_ID = 4194

    fun enabled(prefs: SharedPreferences) = prefs.getBoolean(ENABLED, false)

    private fun key(release: UpdateRelease) = "${release.version}:${release.digest}"

    fun eligible(prefs: SharedPreferences, release: UpdateRelease): Boolean =
        enabled(prefs) && UpdateChecks.automatic(prefs) && prefs.getString(ATTEMPTED, null) != key(release)

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val prefs = UpdateChecks.prefs(context)
        val release = UpdateChecks.available(prefs)
        if (!enabled(prefs)) {
            scheduler.cancel(JOB_ID)
        } else if (release != null && eligible(prefs, release) && scheduler.getPendingJob(JOB_ID) == null) {
            val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, P2pAutoUpdateJob::class.java))
                .setRequiredNetwork(wifi).setBackoffCriteria(30 * 60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true).build())
        }
    }

    private fun directory(context: Context) = File(context.cacheDir, "verified-auto-updates")
    private fun cached(context: Context, release: UpdateRelease) = File(directory(context), "${release.digest}.apk")

    suspend fun copyCached(context: Context, release: UpdateRelease, destination: File): Boolean {
        val source = cached(context, release)
        if (!source.isFile) return false
        return try {
            UpdateApk.verify(context, source, release)
            source.copyTo(destination, overwrite = true)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            source.delete()
            destination.delete()
            false
        }
    }

    private fun mayInterruptUser() =
        UpdateChecks.foreground || UpdateChecks.manualInstallerOpen || TalkCallService.isRunning

    fun installing(context: Context): Boolean {
        val id = UpdateChecks.prefs(context).getInt(SESSION_ID, -1)
        return id >= 0 && context.packageManager.packageInstaller.getSessionInfo(id) != null
    }

    /** Returns true when JobScheduler should try again. */
    suspend fun attempt(context: Context, network: Network?): Boolean {
        val prefs = UpdateChecks.prefs(context)
        val release = UpdateChecks.available(prefs)?.takeIf { eligible(prefs, it) } ?: return false
        if (mayInterruptUser() || installing(context)) return true
        if (!context.packageManager.canRequestPackageInstalls()) {
            UpdateChecks.notifyAvailable(context, release)
            return false
        }
        fun wifiRoute() = UpdateRoute.current(context, wifiOnly = true, jobNetwork = network)
        val route = wifiRoute() ?: return true
        val installer = context.packageManager.packageInstaller
        val archive = cached(context, release)
        var sessionId: Int? = null
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                val dir = directory(context)
                dir.mkdirs()
                val partial = File(dir, "update.part")
                dir.listFiles()?.filter { it != archive }?.forEach { it.delete() }
                if (!archive.isFile) {
                    check(dir.usableSpace >= release.bytes + 16 * 1024 * 1024) { "Not enough space for the update." }
                    try {
                        UpdateFeed.download(route, release, partial) {}
                        UpdateApk.verify(context, partial, release)
                        check(partial.renameTo(archive)) { "Couldn’t save the verified APK." }
                    } finally {
                        partial.delete()
                    }
                } else {
                    UpdateApk.verify(context, archive, release)
                }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(release.bytes)
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                sessionId = id
                installer.openSession(id).use { session ->
                    archive.inputStream().use { input ->
                        session.openWrite("base.apk", 0, release.bytes).use { output ->
                            val coroutine = currentCoroutineContext()
                            input.copyBoundedTo(output, release.bytes, "Update size changed.") { coroutine.ensureActive() }
                            session.fsync(output)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (mayInterruptUser() || wifiRoute()?.network != route.network ||
                UpdateChecks.available(prefs) != release) return true
            if (!eligible(prefs, release)) return false
            val id = checkNotNull(sessionId)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, P2pAutoUpdateReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            installer.openSession(id).use { session ->
                check(prefs.edit().putInt(SESSION_ID, id).putString(ATTEMPTED, key(release)).commit())
                session.commit(callback.intentSender)
                committed = true
            }
            return false
        } catch (_: TimeoutCancellationException) {
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.record(context, "Automatic update failed", error)
            if (error is IOException || wifiRoute()?.network != route.network) return true
            prefs.edit { putString(ATTEMPTED, key(release)) }
            UpdateChecks.notifyAvailable(context, release)
            return false
        } finally {
            if (!committed) sessionId?.let { id ->
                runCatching { installer.abandonSession(id) }
                if (prefs.getInt(SESSION_ID, -1) == id) prefs.edit { remove(SESSION_ID) }
            }
        }
    }

    fun onResult(context: Context, intent: Intent) {
        val prefs = UpdateChecks.prefs(context)
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (id < 0 || prefs.getInt(SESSION_ID, -1) != id) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        prefs.edit { remove(SESSION_ID) }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            UpdateChecks.cancelNotice(context)
        } else {
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Keep the verified APK for Update Now, which opens Android's confirmation from the app.
                runCatching { context.packageManager.packageInstaller.abandonSession(id) }
            }
            UpdateChecks.available(prefs)?.let {
                UpdateChecks.notifyAvailable(context, it, finishInstall = enabled(prefs))
            }
        }
    }
}

class P2pAutoUpdateJob : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = CoroutineScope(Dispatchers.Main).launch {
            var retry = false
            try {
                retry = UpdateAutoInstall.attempt(applicationContext, params.network)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.record(this@P2pAutoUpdateJob, "Couldn’t start the automatic update", error)
            } finally {
                if (isActive) jobFinished(params, retry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}

class P2pAutoUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = UpdateAutoInstall.onResult(context, intent)
}
