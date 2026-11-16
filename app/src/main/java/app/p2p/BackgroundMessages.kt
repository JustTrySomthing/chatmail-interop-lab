package app.p2p

import android.app.NotificationManager
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal object BackgroundMessages {
    private const val PERIODIC_JOB = 4194
    private const val CATCH_UP_JOB = 4195
    private const val CHECK_INTERVAL = 15 * 60 * 1_000L
    private const val LEGACY_CHANNEL = "p2p_background_messages"

    private fun prefs(context: Context) =
        context.getSharedPreferences("background-messages", Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)

    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun batteryPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))

    fun keepConnection(context: Context): Boolean = enabled(context) && batteryExempt(context)

    fun updateConnection(context: Context): Boolean {
        val intent = Intent(context, ChatmailConnectionService::class.java)
        if (!keepConnection(context)) {
            context.stopService(intent)
            return true
        }
        return runCatching { context.startService(intent) != null }.getOrDefault(false)
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (enabled) return start(context, catchUp = true)
        updateConnection(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(PERIODIC_JOB)
        scheduler.cancel(CATCH_UP_JOB)
        return true
    }

    fun start(context: Context, catchUp: Boolean = false): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (notifications.getNotificationChannel(LEGACY_CHANNEL) != null) {
            notifications.deleteNotificationChannel(LEGACY_CHANNEL)
        }
        if (!enabled(context)) return false
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val component = ComponentName(context, BackgroundMessageJob::class.java)
        fun schedule(id: Int, configure: JobInfo.Builder.() -> Unit): Boolean {
            if (scheduler.getPendingJob(id) != null) return true
            val job = JobInfo.Builder(id, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .apply(configure)
                .build()
            return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        }
        val connected = updateConnection(context)
        return runCatching {
            val periodic = schedule(PERIODIC_JOB) { setPeriodic(CHECK_INTERVAL).setPersisted(true) }
            val immediate = !catchUp || schedule(CATCH_UP_JOB) {}
            periodic && immediate && connected
        }.getOrDefault(false)
    }
}

class ChatmailConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connection: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BackgroundMessages.keepConnection(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (connection?.isActive != true) {
            connection = scope.launch {
                val model = (application as P2pApplication).model
                model.withActiveSync {
                    while (BackgroundMessages.keepConnection(this@ChatmailConnectionService)) delay(15_000L)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

class BackgroundMessageJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val active = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (!BackgroundMessages.enabled(this)) return false
        active.remove(params.jobId)?.cancel()
        active[params.jobId] = scope.launch {
            var retry = false
            try {
                val model = (application as P2pApplication).model
                model.withActiveSync { delay(SYNC_WINDOW) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("P2pBackgroundMessages", "Scheduled message check failed", error)
                retry = true
            } finally {
                if (isActive) {
                    active.remove(params.jobId)
                    jobFinished(params, retry && BackgroundMessages.enabled(this@BackgroundMessageJob))
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active.remove(params.jobId)?.cancel()
        return BackgroundMessages.enabled(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val SYNC_WINDOW = 45_000L
    }
}

class BackgroundMessagesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BackgroundMessages.start(context, catchUp = true)
        }
    }
}
