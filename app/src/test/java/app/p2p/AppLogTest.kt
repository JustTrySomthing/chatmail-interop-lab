package app.p2p

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLogTest {
    @Test fun clearRemovesOldBinaryAndPreservesCrashWatermarkAndNewLogging() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("app-log", 0)
        prefs.edit().putLong("last-exit", 1234).commit()
        AppLog.record(context, "old crash \u0001\u0002")
        AppLog.clear(context)
        assertFalse(AppLog.read(context).contains("old crash"))
        assertTrue(AppLog.read(context).contains("No activity recorded yet."))
        assertEquals(1234, prefs.getLong("last-exit", 0))
        AppLog.record(context, "new call")
        assertTrue(AppLog.read(context).contains("new call"))
    }
}
