package app.p2p

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import kotlinx.coroutines.*

/** Keeps an accepted or outgoing call alive while the user leaves the call screen. */
class TalkCallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var callId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(LocalAlerts.EXTRA_TARGET)
        if (id == null || pending?.first != id) { stopSelf(startId); return START_NOT_STICKY }
        val model = (application as P2pApplication).model
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Ongoing Talk calls", NotificationManager.IMPORTANCE_LOW))
            val open = Intent(this, MainActivity::class.java).setAction(LocalAlerts.ACTION_OPEN)
                .setData(Uri.fromParts("p2p-talk-ongoing", id, null))
                .putExtra(EXTRA_PROFILE, model.profileId)
                .putExtra(LocalAlerts.EXTRA_AREA, AlertArea.TALK.name).putExtra(LocalAlerts.EXTRA_TARGET, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val end = Intent(this, TalkDeclineReceiver::class.java).setAction(ACTION_END)
                .setData(Uri.fromParts("p2p-talk-ongoing", id, null)).putExtra(LocalAlerts.EXTRA_TARGET, id)
                .putExtra(EXTRA_PROFILE, model.profileId)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Relay Lab call").setContentText("Tap to return to the call")
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true).setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(Notification.Action.Builder(null, "End Call",
                    PendingIntent.getBroadcast(this, 0, end, PendingIntent.FLAG_IMMUTABLE)).build()).build()
            startForeground(4201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            callId = id
            pending?.second?.complete(Unit)
            scope.launch { model.withActiveSync { awaitCancellation() } }
        } catch (error: Exception) {
            pending?.second?.completeExceptionally(error)
            AppLog.record(this, "Talk: audio service failed", error)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        val id = callId
        if (id != null && pending?.first == id) {
            pending = null
            val talk = (application as P2pApplication).model.talk
            if (talk.state.value?.id == id) talk.end()
        }
        super.onDestroy()
    }
    companion object {
        const val ACTION_END = "app.p2p.END_TALK"
        private const val CHANNEL = "p2p_ongoing_talk"
        private var pending: Pair<String, CompletableDeferred<Unit>>? = null
        internal val isRunning get() = pending != null
        internal suspend fun ensure(context: Context, id: String) {
            val ready = CompletableDeferred<Unit>()
            pending = id to ready
            checkNotNull(context.startForegroundService(Intent(context, TalkCallService::class.java).putExtra(LocalAlerts.EXTRA_TARGET, id)))
            withTimeout(5_000) { ready.await() }
        }
        internal fun stop(context: Context) {
            pending = null
            context.stopService(Intent(context, TalkCallService::class.java))
        }
    }
}
