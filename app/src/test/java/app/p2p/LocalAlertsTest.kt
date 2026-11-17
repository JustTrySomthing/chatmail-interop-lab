package app.p2p

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalAlertsTest {
    @Test
    fun postsPrivateAlertsAfterPermissionAndKeepsOnePerConversation() {
        val app = RuntimeEnvironment.getApplication()
        val alerts = LocalAlerts(app)
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        alerts.show(AlertArea.CHATS, "contact-id", "Pat", "New message")
        alerts.show(AlertArea.CHATS, "contact-id", "Pat", "New reaction")
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("New reaction", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Chat messages", notification.publicVersion.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Chat messages", manager.getNotificationChannel(AlertArea.CHATS.channel).name)
        alerts.clear(AlertArea.CHATS, "contact-id")
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun notificationPreviewControlsHidePreviouslyPostedAndNewMessageDetails() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        val alerts = LocalAlerts(app)
        fun post() = alerts.show(AlertArea.CHATS, "contact-id", "Pat", "Private message")
        post()
        NotificationPreferences.setPreview(app, AlertPreview.NAME)
        assertEquals(0, manager.allNotifications.size)
        post()
        assertEquals("Pat", manager.allNotifications.single().extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Chat messages", manager.allNotifications.single().extras.getString(Notification.EXTRA_TEXT))
        NotificationPreferences.setPreview(app, AlertPreview.PRIVATE)
        post()
        assertEquals("Relay Lab", manager.allNotifications.single().extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Chat messages", manager.allNotifications.single().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun overnightHoursUseTheDayTheScheduleStarted() {
        val mondayNight = java.util.GregorianCalendar(2026, 10, 9, 22, 0)
        val tuesdayMorning = java.util.GregorianCalendar(2026, 10, 10, 7, 59)
        val end = java.util.GregorianCalendar(2026, 10, 10, 8, 0)
        val schedule = QuietHours(enabled = true, days = 1)
        org.junit.Assert.assertTrue(schedule.active(mondayNight))
        org.junit.Assert.assertTrue(schedule.active(tuesdayMorning))
        org.junit.Assert.assertFalse(schedule.active(end))
        org.junit.Assert.assertFalse(schedule.copy(days = 2).active(tuesdayMorning))
        org.junit.Assert.assertFalse(schedule.copy(enabled = false).active(mondayNight))
    }

    @Test
    fun quietHoursStillPostAlertsInASilentGroup() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val clock = java.util.Calendar.getInstance()
        val minute = clock.get(java.util.Calendar.HOUR_OF_DAY) * 60 + clock.get(java.util.Calendar.MINUTE)
        QuietHours(enabled = true, start = (minute + 1435) % 1440, end = (minute + 5) % 1440).save(app)
        LocalAlerts(app).show(AlertArea.CHATS, "contact-id", "Pat", "New message")
        val notification = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
        assertEquals("New message", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun incomingCallAlertRingsOnlyDuringItsRemainingWindow() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val notifications = shadowOf(manager)
        val alerts = LocalAlerts(app)
        val call = newId()
        alerts.show(AlertArea.TALK, call, "Pat", "Incoming Talk call", System.currentTimeMillis() - 1)
        assertEquals(0, notifications.allNotifications.size)

        alerts.show(AlertArea.TALK, call, "Pat", "Incoming Talk call", System.currentTimeMillis() + 10_000L)
        val alert = notifications.allNotifications.single()
        org.junit.Assert.assertTrue(alert.timeoutAfter in 1L..10_000L)
        org.junit.Assert.assertTrue(alert.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(2, alert.actions.size)
        val answer = alert.actions.single { it.actionIntent.isActivity }.actionIntent
        assertEquals(AlertRoute(AlertArea.TALK, call, answer = true), LocalAlerts.read(shadowOf(answer).savedIntent))
        val decline = alert.actions.single { it.actionIntent.isBroadcast }.actionIntent
        assertEquals(TalkDeclineReceiver::class.java.name, shadowOf(decline).savedIntent.component?.className)
        assertEquals(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            manager.getNotificationChannel(AlertArea.TALK.channel).audioAttributes.usage)
        alerts.clear(AlertArea.TALK, call)
        assertEquals(0, notifications.allNotifications.size)
    }

    @Test
    fun globalMuteClearsCurrentAlertsAndSuppressesNewOnesIncludingUpdates() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        val alerts = LocalAlerts(app)
        UpdateChecks.prefs(app).edit().putBoolean("automatic", true).apply()
        val update = UpdateRelease(UpdateVersion(0, 7, 23, 99), "preview.apk", 120, "0".repeat(64), true)
        fun postAll() {
            for (area in AlertArea.entries) alerts.show(area, newId(), "Pat", area.label)
            UpdateChecks.notifyAvailable(app, update)
        }
        postAll()
        assertEquals(AlertArea.entries.size + 1, notifications.allNotifications.size)
        AppAlertMute.set(app, true)
        assertEquals(true, AppAlertMute.isMuted(app))
        assertEquals(0, notifications.allNotifications.size)
        postAll()
        assertEquals(0, notifications.allNotifications.size)
        AppAlertMute.set(app, false)
        postAll()
        assertEquals(AlertArea.entries.size + 1, notifications.allNotifications.size)
    }

    @Test
    fun rejectsInvalidNotificationDestinations() {
        val intent = Intent(LocalAlerts.ACTION_OPEN).putExtra(LocalAlerts.EXTRA_AREA, "FILES")
            .putExtra(LocalAlerts.EXTRA_TARGET, "invalid")
        assertNull(LocalAlerts.read(intent))
    }
}
