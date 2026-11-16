package app.p2p

import android.Manifest
import android.content.pm.PackageManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

internal object UpdateChecks {
    const val OPEN = "app.p2p.OPEN_UPDATES"
    const val DAY = 24 * 60 * 60_000L
    const val HOUR = DAY / 24
    private const val JOB_ID = 4193
    private const val CHANNEL = "p2p-updates"
    private val mutex = Mutex()
    val installed = requireNotNull(UpdateVersion.parse(BuildConfig.VERSION_NAME))
    private val testBuild = BuildConfig.APPLICATION_ID == "app.p2p.interop"
    @Volatile var foreground = false

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    fun boot(context: Context) = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    fun automatic(prefs: SharedPreferences) = prefs.getBoolean("automatic", false)

    fun previews(prefs: SharedPreferences) = prefs.getBoolean("previews", true)

    fun elapsed(last: Long, interval: Long, now: Long): Boolean =
        last <= 0L || now < last || now - last >= interval

    fun due(prefs: SharedPreferences, boot: Int, foreground: Boolean, now: Long): Boolean {
        if (!automatic(prefs)) return false
        if (boot >= 0 && prefs.getInt("openedBoot", -1) != boot) {
            return foreground && (prefs.getInt("attemptBoot", -1) != boot ||
                elapsed(prefs.getLong("attempted", 0), HOUR, now))
        }
        return elapsed(prefs.getLong("checked", 0), DAY, now) &&
            elapsed(prefs.getLong("attempted", 0), HOUR, now)
    }

    fun available(prefs: SharedPreferences): UpdateRelease? =
        UpdateRelease.read(prefs.getString("available", null))?.takeIf {
            it.version > installed && (!it.preview || previews(prefs)) &&
                it.filename in UpdateFeed.assetNames(it.version, Build.SUPPORTED_ABIS.toList(), testBuild)
        }

    fun shouldPrompt(prefs: SharedPreferences, release: UpdateRelease, now: Long = System.currentTimeMillis()) =
        automatic(prefs) &&
            (prefs.getString("dismissed", null) != release.version.toString() ||
                elapsed(prefs.getLong("dismissedAt", 0), DAY, now))

    data class Result(val release: UpdateRelease?)

    suspend fun check(context: Context, manual: Boolean, foreground: Boolean): Result? = mutex.withLock {
        val prefs = prefs(context)
        val boot = boot(context)
        val now = System.currentTimeMillis()
        if (!manual && !due(prefs, boot, foreground, now)) return@withLock null
        val route = UpdateRoute.current(context, requireValidated = !manual)
        if (route == null && !manual) return@withLock null
        checkNotNull(route) { "Connect to the internet to check for updates." }
        prefs.edit {
            putLong("attempted", now)
            if (foreground && boot >= 0) putInt("attemptBoot", boot)
        }
        val release = withContext(Dispatchers.IO) {
            withTimeout(60_000L) {
                UpdateFeed.select(UpdateFeed.releases(route, manual), installed, previews(prefs),
                    Build.SUPPORTED_ABIS.toList(), testBuild)
            }
        }
        prefs.edit {
            putLong("checked", System.currentTimeMillis())
            if (foreground && boot >= 0) putInt("openedBoot", boot)
            if (release == null) remove("available") else putString("available", release.json().toString())
        }
        if (release == null) cancelNotice(context)
        Result(release)
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!automatic(prefs(context))) {
            scheduler.cancel(JOB_ID)
            cancelNotice(context)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, P2pUpdateJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(DAY).setPersisted(true).build())
    }

    fun cancelNotice(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(CHANNEL, JOB_ID)

    fun notifyAvailable(context: Context, release: UpdateRelease) {
        if (foreground || AppAlertMute.isMuted(context) || !shouldPrompt(prefs(context), release) ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Relay Lab updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, JOB_ID, Intent(context, MainActivity::class.java)
            .setAction(OPEN).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        AppAlertMute.deliver(context) {
            manager.notify(CHANNEL, JOB_ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setSilent(QuietHours.read(context).active())
                .setContentTitle("Relay Lab update available")
                .setContentText("Version ${release.version} is available.")
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build())
        }
    }
}

class P2pUpdateJob : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = CoroutineScope(Dispatchers.Main).launch {
            try {
                UpdateChecks.check(applicationContext, manual = false, foreground = false)?.release?.let {
                    UpdateChecks.notifyAvailable(applicationContext, it)
                }
            } catch (error: TimeoutCancellationException) {
                android.util.Log.w("P2pUpdates", "Scheduled check timed out")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("P2pUpdates", "Scheduled check failed", error)
            } finally {
                if (isActive) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return false
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}

internal data class UpdateState(
    val release: UpdateRelease? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val preparingInstall: Boolean = false,
    val progress: Long = 0,
    val ready: Boolean = false,
    val prompt: Boolean = false,
    val requestInstall: Boolean = false,
    val checked: Long = 0L,
    val message: String? = null,
    val error: String? = null
) {
    val busy get() = checking || downloading || preparingInstall
}

class P2pUpdateModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val prefs = UpdateChecks.prefs(context)
    private val directory = File(context.cacheDir, "p2p-updates")
    private val verified = File(directory, "verified/P2P-update.apk")
    private val partial = File(directory, "update.part")
    private var action: Job? = null
    private var foregroundChecks: Job? = null
    private var installAttempt: UpdateVersion? = null

    private val cachedAtLaunch = UpdateChecks.available(prefs)
    internal var state by mutableStateOf(UpdateState(
        release = cachedAtLaunch,
        checked = prefs.getLong("checked", 0),
        prompt = cachedAtLaunch?.let { UpdateChecks.shouldPrompt(prefs, it) } == true
    ))
        private set
    internal var automatic by mutableStateOf(UpdateChecks.automatic(prefs))
        private set
    internal var previews by mutableStateOf(UpdateChecks.previews(prefs))
        private set

    init {
        UpdateChecks.schedule(context)
        // Clear orphaned files left by a process exit before any new action can use them.
        partial.delete()
        if (prefs.contains("ready")) {
            start(state.copy(preparingInstall = true)) { refreshCached() }
        } else {
            verified.delete()
        }
    }

    private fun start(initial: UpdateState, silent: Boolean = false, operation: suspend () -> Unit) {
        if (state.busy) return
        state = initial.copy(error = null)
        action = viewModelScope.launch {
            try {
                operation()
            } catch (error: TimeoutCancellationException) {
                if (!silent) state = state.copy(error = "The update request timed out. Try again.")
            } catch (error: CancellationException) {
                if (state.downloading) state = state.copy(message = "Download canceled.")
                throw error
            } catch (error: IOException) {
                if (!silent) state = state.copy(error = "Couldn’t reach GitHub. Try again.")
            } catch (error: Exception) {
                AppLog.record(context, "Update operation failed", error)
                if (!silent) state = state.copy(error = error.message ?: "Couldn’t complete the update.")
            } finally {
                state = state.copy(checking = false, downloading = false, preparingInstall = false)
            }
        }
    }

    private suspend fun clearDownload() {
        prefs.edit { remove("ready") }
        state = state.copy(ready = false, requestInstall = false)
        withContext(Dispatchers.IO) { verified.delete() }
    }

    private suspend fun refreshCached() {
        val release = UpdateChecks.available(prefs)
        val saved = UpdateRelease.read(prefs.getString("ready", null))
        val ready = release != null && saved == release && withContext(Dispatchers.IO) {
            try {
                UpdateApk.verify(context, verified, release)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }
        if (!ready && (saved != null || state.ready || verified.exists())) clearDownload()
        state = state.copy(release = release, ready = ready, checked = prefs.getLong("checked", 0),
            prompt = release?.let { it.version != installAttempt && UpdateChecks.shouldPrompt(prefs, it) } == true)
    }

    internal fun setForeground(visible: Boolean) {
        UpdateChecks.foreground = visible
        foregroundChecks?.cancel()
        foregroundChecks = if (visible && automatic) viewModelScope.launch {
            while (isActive) {
                snapshotFlow { state.busy }.first { !it }
                automaticCheck()
                delay(60_000L)
            }
        } else null
    }

    internal fun automaticCheck() {
        if (state.busy || !automatic) return
        val now = System.currentTimeMillis()
        val due = UpdateChecks.due(prefs, UpdateChecks.boot(context), foreground = true, now = now) &&
            UpdateRoute.current(context, requireValidated = true) != null
        if (due) checkNow(manual = false) else refreshPrompt(now)
    }

    internal fun refreshPrompt(now: Long = System.currentTimeMillis()) {
        if (state.busy || state.requestInstall) return
        val cached = UpdateChecks.available(prefs)
        if (cached != state.release || prefs.getLong("checked", 0) != state.checked) {
            start(state.copy(preparingInstall = true), silent = true) { refreshCached() }
        } else {
            val release = state.release
            state = state.copy(prompt = release != null && release.version != installAttempt &&
                UpdateChecks.shouldPrompt(prefs, release, now))
        }
    }

    internal fun checkNow(manual: Boolean = true) = start(state.copy(checking = true, message = null, prompt = false),
        silent = !manual) {
        runCheck(manual)
    }

    private suspend fun runCheck(manual: Boolean) {
        val result = UpdateChecks.check(context, manual, foreground = true) ?: return
        if (result.release != state.release) clearDownload()
        state = state.copy(release = result.release, checked = prefs.getLong("checked", 0),
            message = if (manual && result.release == null) "You’re up to date." else null,
            prompt = !manual && result.release?.let {
                it.version != installAttempt && UpdateChecks.shouldPrompt(prefs, it)
            } == true)
    }

    internal fun changeAutomatic(enabled: Boolean) {
        automatic = enabled
        prefs.edit { putBoolean("automatic", enabled) }
        UpdateChecks.schedule(context)
        setForeground(UpdateChecks.foreground)
        if (!enabled) state = state.copy(prompt = false)
    }

    internal fun changePreviews(enabled: Boolean) {
        if (state.busy) return
        previews = enabled
        prefs.edit { putBoolean("previews", enabled) }
        start(state.copy(checking = true, message = null, prompt = false)) {
            refreshCached()
            runCheck(manual = true)
        }
    }

    internal fun dismissPrompt() {
        state.release?.let { release ->
            prefs.edit { putString("dismissed", release.version.toString()); putLong("dismissedAt", System.currentTimeMillis()) }
        }
        UpdateChecks.cancelNotice(context)
        state = state.copy(prompt = false)
    }

    internal fun updateNow() {
        val release = state.release ?: return
        if (state.busy) return
        dismissPrompt()
        if (state.ready) {
            state = state.copy(requestInstall = true, error = null, message = null)
            return
        }
        start(state.copy(downloading = true, progress = 0, message = null)) {
            clearDownload()
            val route = checkNotNull(UpdateRoute.current(context)) { "Connect to the internet to download the update." }
            try {
                withContext(Dispatchers.IO) {
                    verified.parentFile?.mkdirs()
                    check(directory.usableSpace >= release.bytes + 16 * 1024 * 1024) { "Free some space and try again." }
                    var last = 0L
                    UpdateFeed.download(route, release, partial) { bytes ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - last >= 250 || bytes == release.bytes) {
                            last = now
                            withContext(Dispatchers.Main) { state = state.copy(progress = bytes) }
                        }
                    }
                    UpdateApk.verify(context, partial, release)
                    currentCoroutineContext().ensureActive()
                    check(partial.renameTo(verified)) { "Couldn’t save the verified APK." }
                }
                prefs.edit { putString("ready", release.json().toString()) }
                state = state.copy(ready = true, progress = release.bytes)
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { partial.delete() }
            }
        }
    }

    internal fun cancelDownload() {
        if (state.downloading) action?.cancel()
    }

    internal fun consumeInstallRequest() {
        state = state.copy(requestInstall = false)
    }

    internal fun install(launch: (Intent) -> Boolean) {
        val release = state.release ?: return
        if (!state.ready) return
        start(state.copy(preparingInstall = true, requestInstall = false)) {
            check(context.packageManager.canRequestPackageInstalls()) { "Allow Relay Lab to install updates in Android settings." }
            try {
                withContext(Dispatchers.IO) { UpdateApk.verify(context, verified, release) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                clearDownload()
                throw error
            }
            if (launch(UpdateApk.installer(context, verified))) {
                installAttempt = release.version
            } else {
                state = state.copy(error = "Relay Lab couldn’t open the Android installer. Return to this screen and tap Install update again.")
            }
        }
    }

    internal fun installPermissionReturned() {
        if (context.packageManager.canRequestPackageInstalls()) {
            state = state.copy(requestInstall = state.ready, message = null, error = null)
        } else installPermissionDenied()
    }

    internal fun installReturned() {
        state = state.copy(message = "Android returned without updating Relay Lab. You can try again.")
    }

    internal fun installPermissionDenied() {
        state = state.copy(error = "Allow Relay Lab to install updates in Android settings, then try again.")
    }
}
