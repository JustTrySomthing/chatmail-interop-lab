package app.p2p

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class ReplyReminder(val area: AlertArea, val target: String, val item: String) {
    fun valid() = when (area) {
        AlertArea.CHATS -> (isPeer(target) || isId(target)) && (item == target || isId(item))
        AlertArea.GROUPS, AlertArea.SOCIAL -> isId(target) && isId(item)
        else -> false
    }

    companion object {
        fun read(extras: PersistableBundle): ReplyReminder? {
            val area = AlertArea.entries.firstOrNull { it.name == extras.getString("area") } ?: return null
            return ReplyReminder(area, extras.getString("target").orEmpty(), extras.getString("item").orEmpty())
                .takeIf(ReplyReminder::valid)
        }
    }
}

class ReplyReminderJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val active = mutableMapOf<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val reminder = ReplyReminder.read(params.extras) ?: return false
        val profile = params.extras.getString(EXTRA_PROFILE) ?: PRIMARY_PROFILE
        if (!ProfileSelection(applicationContext).isEnabled(profile)) return false
        active.remove(params.jobId)?.cancel()
        active[params.jobId] = scope.launch {
            try {
                val store = LocalStore.open(applicationContext, profile)
                try {
                    val records = store.records()
                    val target = records.get(reminder.target)
                    val item = if (reminder.item == reminder.target) target else records.get(reminder.item)
                    if (target == null || item == null || item.data().optBoolean("locallyDeleted")) return@launch
                    val author = item.data().optString("author")
                    if (target.data().optBoolean("blocked") ||
                        records.get(author)?.data()?.optBoolean("blocked") == true ||
                        records.get("thread-pref:${target.key}")?.data()?.optBoolean("blocked") == true) return@launch
                    LocalAlerts(applicationContext, profile).show(reminder.area, reminder.target, "Reminder to reply",
                        target.data().optString("name").ifBlank { "Open P2P to reply" }, replyTo = reminder.item)
                } finally {
                    store.close()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("P2pReminder", "Could not open reply reminder", error)
            } finally {
                if (isActive) {
                    active.remove(params.jobId)
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        active.clear()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Synchronized
        internal fun schedule(context: Context, reminder: ReplyReminder, delayMs: Long, profile: String): Boolean {
            if (!reminder.valid() || delayMs <= 0) return false
            val service = ComponentName(context, ReplyReminderJob::class.java)
            val scheduler = context.getSystemService(JobScheduler::class.java)
            return runCatching {
                val pending = scheduler.allPendingJobs
                val used = pending.mapTo(mutableSetOf(), JobInfo::getId)
                val existing = pending.firstOrNull {
                    it.service == service && ReplyReminder.read(it.extras) == reminder &&
                        (it.extras.getString(EXTRA_PROFILE) ?: PRIMARY_PROFILE) == profile
                }
                val id = existing?.id ?: (60_000..60_999).firstOrNull { it !in used } ?: return false
                val extras = PersistableBundle().apply {
                    putString(EXTRA_PROFILE, profile)
                    putString("area", reminder.area.name)
                    putString("target", reminder.target)
                    putString("item", reminder.item)
                }
                val job = JobInfo.Builder(id, service).setMinimumLatency(delayMs).setPersisted(true).setExtras(extras).build()
                scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)
        }
    }
}
