package app.p2p

import android.app.Application
import android.app.Notification
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
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
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

class P2pApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            AppLog.record(this, "Unhandled app error", error)
            previous?.uncaughtException(thread, error)
        }
        AppLog.collectPreviousCrashes(this)
        AppLog.record(this, "App started")
    }
    val model by lazy { P2pModel(this) }
}

class TransferJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val active = mutableMapOf<Int, Job>()

    override fun onStartJob(parameters: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val fileId = parameters.extras.getString(FILE_ID) ?: return false
        if (!isId(fileId)) return false
        val notificationId = parameters.jobId
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW))
        setNotification(parameters, notificationId, notification(fileId, notificationId, 0L, 0L), JOB_END_NOTIFICATION_POLICY_REMOVE)
        active[notificationId]?.cancel()
        active[notificationId] = scope.launch {
            try {
                val model = (application as P2pApplication).model
                model.withActiveSync {
                    var lastOffset = -1L
                    var progressAt = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - progressAt < IDLE_TIMEOUT) {
                        val items = model.items.value
                        if (items != null) {
                            val file = items.firstOrNull { it.key == fileId && it.kind == "file-received" }?.data() ?: break
                            if (downloadState(file) !in activeStates) break
                            val offset = file.optLong("offset")
                            if (offset != lastOffset) {
                                lastOffset = offset
                                progressAt = SystemClock.elapsedRealtime()
                                setNotification(parameters, notificationId,
                                    notification(fileId, notificationId, offset, file.optLong("size")), JOB_END_NOTIFICATION_POLICY_REMOVE)
                            }
                        }
                        delay(1_000)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("P2pTransfer", "Transfer job failed", error)
            } finally {
                if (isActive) {
                    active.remove(notificationId)
                    jobFinished(parameters, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(parameters: JobParameters): Boolean {
        active.remove(parameters.jobId)?.cancel()
        // The durable transfer is resumed by the app if Android allows it to run again.
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(fileId: String, notificationId: Int, offset: Long, size: Long): Notification {
        val destination = Intent(this, MainActivity::class.java)
            .setAction(LocalAlerts.ACTION_OPEN)
            .setData(Uri.parse("p2p-transfer:$fileId"))
            .putExtra(LocalAlerts.EXTRA_AREA, AlertArea.FILES.name)
            .putExtra(LocalAlerts.EXTRA_TARGET, fileId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(this, notificationId, destination,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val percent = if (size > 0) ((offset.toDouble() / size) * 100).toInt().coerceIn(0, 100) else 0
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Relay Lab file transfer")
            .setContentText(if (size > 0) "$percent% downloaded" else "Waiting to connect")
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setProgress(100, percent, size <= 0)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "p2p_transfers"
        private const val FILE_ID = "file_id"
        private const val IDLE_TIMEOUT = 120_000L
        private val activeStates = setOf("Waiting to connect", "Downloading")

        @Synchronized
        fun cancelLegacyConstraint(context: Context, file: String) {
            if (Build.VERSION.SDK_INT < 34) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val service = ComponentName(context, TransferJob::class.java)
            scheduler.allPendingJobs.filter {
                it.service == service && it.extras.getString(FILE_ID) == file &&
                    it.requiredNetwork?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            }.forEach { scheduler.cancel(it.id) }
        }

        @Synchronized
        fun schedule(context: Context, file: String, bytes: Long) {
            if (Build.VERSION.SDK_INT < 34 || !isId(file)) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val component = ComponentName(context, TransferJob::class.java)
            val pending = scheduler.allPendingJobs
            val previous = pending.firstOrNull { it.service == component && it.extras.getString(FILE_ID) == file }
            val taken = pending.mapTo(mutableSetOf(), JobInfo::getId)
            var id = previous?.id ?: (0x40000000 or (file.hashCode() and 0x3fffffff))
            if (previous == null) {
                while (id in taken) id = if (id == Int.MAX_VALUE) 0x40000000 else id + 1
            }
            val extras = PersistableBundle().apply { putString(FILE_ID, file) }
            val job = JobInfo.Builder(id, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(bytes.coerceAtLeast(0), JobInfo.NETWORK_BYTES_UNKNOWN.toLong())
                .setExtras(extras)
                .setUserInitiated(true)
                .build()
            // A fast activity exit can make the OS reject the request. The local queue remains valid.
            runCatching { scheduler.schedule(job) }
        }
    }
}

internal fun downloadState(data: org.json.JSONObject): String =
    data.optString("state").let { if (it == "Waiting for Wi-Fi") "Waiting to connect" else it }
