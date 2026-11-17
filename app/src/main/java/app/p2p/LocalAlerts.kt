package app.p2p

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.net.Uri
import androidx.core.app.Person
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal enum class AlertArea(val channel: String, val label: String) {
    CHATS("p2p_chats", "Chat messages"),
    GROUPS("p2p_groups", "Group messages"),
    CONTACTS("p2p_contacts", "Contact requests"),
    SOCIAL("p2p_social", "Social activity"),
    FILES("p2p_files", "New files"),
    TALK("p2p_talk", "Incoming Talk calls"),
    MISSED_CALLS("p2p_missed_calls", "Missed Talk calls")
}

internal object AppAlertMute {
    private fun preferences(context: Context) =
        context.getSharedPreferences("p2p-alerts", Context.MODE_PRIVATE)

    fun isMuted(context: Context) = preferences(context).getBoolean("muted", false)

    @Synchronized
    fun set(context: Context, muted: Boolean) {
        preferences(context).edit { putBoolean("muted", muted) }
        if (!muted) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { alert -> AlertArea.entries.any { it.channel == alert.notification.channelId } }
            .forEach { manager.cancel(it.tag, it.id) }
        UpdateChecks.cancelNotice(context)
    }

    @Synchronized
    fun deliver(context: Context, post: () -> Unit) {
        if (!isMuted(context)) post()
    }
}

internal data class AlertRoute(val area: AlertArea, val target: String, val answer: Boolean = false, val replyTo: String? = null)

internal class LocalAlerts(private val context: Context, private val profileId: String = PRIMARY_PROFILE) {
    private val tag = profileId.takeUnless { it == PRIMARY_PROFILE }
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun enabled() = manager.areNotificationsEnabled() && permitted()

    fun registerChannels() {
        manager.createNotificationChannels(AlertArea.entries.map { area ->
            NotificationChannel(area.channel, area.label,
                if (area == AlertArea.TALK) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT)
                .apply {
                    if (area == AlertArea.TALK) {
                        setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                        enableVibration(true)
                    }
                }
        })
    }

    fun show(area: AlertArea, target: String, title: String, body: String, expiresAt: Long? = null, replyTo: String? = null) {
        val remaining = expiresAt?.minus(System.currentTimeMillis())
        if (remaining != null && remaining <= 0L || !enabled() || AppAlertMute.isMuted(context)) return
        val quiet = QuietHours.read(context).active()
        registerChannels()
        val id = id(area, if (replyTo != null) "reply:$replyTo" else target)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .putExtra(EXTRA_PROFILE, profileId)
            .putExtra(EXTRA_AREA, area.name)
            .putExtra(EXTRA_TARGET, target)
            .putExtra(EXTRA_REPLY_TO, replyTo)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = Notification.Builder(context, area.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Relay Lab")
            .setContentText(area.label)
            .build()
        val notification = NotificationCompat.Builder(context, area.channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setSilent(quiet)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .apply {
                if (area == AlertArea.TALK) {
                    val answer = PendingIntent.getActivity(context, id,
                        Intent(intent).setAction(ACTION_ANSWER).setData(Uri.fromParts("p2p-talk", target, null)),
                        PendingIntent.FLAG_IMMUTABLE)
                    val decline = PendingIntent.getBroadcast(context, id,
                        Intent(context, TalkDeclineReceiver::class.java).setAction(ACTION_DECLINE)
                            .setData(Uri.fromParts("p2p-talk", target, null)).putExtra(EXTRA_TARGET, target)
                            .putExtra(EXTRA_PROFILE, profileId),
                        PendingIntent.FLAG_IMMUTABLE)
                    setStyle(NotificationCompat.CallStyle.forIncomingCall(
                        Person.Builder().setName(title).setImportant(true).build(), decline, answer))
                    setCategory(NotificationCompat.CATEGORY_CALL)
                    setTimeoutAfter(remaining ?: TALK_RING_MS)
                } else if (area == AlertArea.MISSED_CALLS) {
                    setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                }
            }
            .build()
        if (area == AlertArea.TALK && !quiet) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        if (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            AppAlertMute.deliver(context) {
                if (ProfileSelection(context).isEnabled(profileId)) manager.notify(tag, id, notification)
            }
        }
    }

    fun clear(area: AlertArea, target: String) = manager.cancel(tag, id(area, target))

    fun clearProfileAlerts() {
        manager.activeNotifications.filter { notification ->
            notification.tag == tag && AlertArea.entries.any { it.channel == notification.notification.channelId }
        }.forEach { manager.cancel(it.tag, it.id) }
    }

    private fun permitted() = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun id(area: AlertArea, target: String) = (area.channel + ":" + target + (tag?.let { ":$it" } ?: "")).hashCode()

    companion object {
        const val ACTION_OPEN = "app.p2p.OPEN_ALERT"
        const val ACTION_ANSWER = "app.p2p.ANSWER_TALK"
        const val ACTION_DECLINE = "app.p2p.DECLINE_TALK"
        const val EXTRA_AREA = "p2p_alert_area"
        const val EXTRA_TARGET = "p2p_alert_target"
        private const val EXTRA_REPLY_TO = "p2p_alert_reply_to"

        fun read(intent: Intent?): AlertRoute? {
            val action = intent?.action ?: return null
            if (action != ACTION_OPEN && action != ACTION_ANSWER) return null
            val area = AlertArea.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_AREA) } ?: return null
            val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
            if (target.isNotEmpty() && !isPeer(target) && !isId(target)) return null
            if (action == ACTION_ANSWER && (area != AlertArea.TALK || !isId(target))) return null
            val replyTo = intent.getStringExtra(EXTRA_REPLY_TO)?.takeIf { isId(it) || isPeer(it) }
            return AlertRoute(area, target, answer = action == ACTION_ANSWER, replyTo = replyTo)
        }
    }
}


class TalkDeclineReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(LocalAlerts.ACTION_DECLINE, TalkCallService.ACTION_END)) return
        val call = intent.getStringExtra(LocalAlerts.EXTRA_TARGET)?.takeIf(::isId) ?: return
        val app = context.applicationContext as P2pApplication
        val model = app.model
        val owner = intent.getStringExtra(EXTRA_PROFILE) ?: PRIMARY_PROFILE
        val hasLiveCall = owner == model.profileId && !model.retiring && model.talk.state.value?.id == call
        if (intent.action == LocalAlerts.ACTION_DECLINE && !hasLiveCall && app.profiles.hasIncomingCall(owner, call)) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.Main.immediate).launch {
                try {
                    withTimeout(8_000L) { app.profiles.declineIncomingCall(owner, call) }
                } catch (failure: Exception) {
                    AppLog.record(app, "Couldn’t decline the incoming profile call", failure)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        if (owner != model.profileId || model.retiring) return
        LocalAlerts(context, owner).clear(AlertArea.TALK, call)
        model.talk.let { talk ->
            if (intent.action == TalkCallService.ACTION_END && talk.state.value?.id == call) talk.end() else talk.decline(call)
        }
    }
}
