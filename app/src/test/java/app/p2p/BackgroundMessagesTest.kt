package app.p2p

import android.app.Application
import android.app.job.JobScheduler
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackgroundMessagesTest {
    @Test
    fun bootAndUpgradeScheduleSilentNetworkChecksWithoutDuplicates() {
        val app = RuntimeEnvironment.getApplication()
        val scheduler = app.getSystemService(JobScheduler::class.java)
        val receiver = BackgroundMessagesReceiver()

        assertTrue(BackgroundMessages.enabled(app))
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val jobs = scheduler.allPendingJobs
        assertEquals(2, jobs.size)
        val periodic = jobs.single { it.isPeriodic }
        assertTrue(periodic.isPersisted)
        assertEquals(15 * 60 * 1_000L, periodic.intervalMillis)
        jobs.forEach {
            assertEquals(BackgroundMessageJob::class.java.name, it.service.className)
            assertNotNull(it.requiredNetwork)
        }
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun bootStartsTheChatmailConnectionOnlyAfterBatteryPermissionIsGranted() {
        val app = RuntimeEnvironment.getApplication()
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        val receiver = BackgroundMessagesReceiver()
        val request = BackgroundMessages.batteryPermissionIntent(app)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, request.action)
        assertEquals("package:${app.packageName}", request.dataString)

        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
        shadowOf(app).nextStoppedService
        power.setIgnoringBatteryOptimizations(app.packageName, true)
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(ChatmailConnectionService::class.java.name, shadowOf(app).nextStartedService.component?.className)

        BackgroundMessages.setEnabled(app, false)
        assertFalse(BackgroundMessages.keepConnection(app))
        assertEquals(ChatmailConnectionService::class.java.name, shadowOf(app).nextStoppedService.component?.className)
        receiver.onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun disablingBackgroundChecksCancelsJobsAndPersistsAcrossBoot() {
        val app = RuntimeEnvironment.getApplication()
        val scheduler = app.getSystemService(JobScheduler::class.java)
        val receiver = BackgroundMessagesReceiver()

        assertTrue(BackgroundMessages.setEnabled(app, true))
        assertEquals(2, scheduler.allPendingJobs.size)
        assertTrue(BackgroundMessages.setEnabled(app, false))
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(BackgroundMessages.enabled(app))
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
}
