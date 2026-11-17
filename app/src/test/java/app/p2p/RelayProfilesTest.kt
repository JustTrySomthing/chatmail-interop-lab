package app.p2p

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RelayProfilesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun existingInstallationKeepsOriginalProfileAndRemembersNewSelection() {
        val selection = ProfileSelection(context)
        assertEquals(PRIMARY_PROFILE, selection.active())
        val alternate = selection.select(null)
        assertTrue(isId(alternate))
        assertEquals(setOf(PRIMARY_PROFILE, alternate), ProfileSelection(context).ids())
        assertEquals(alternate, ProfileSelection(context).active())
        assertThrows(IllegalArgumentException::class.java) { selection.select(newId()) }
        assertEquals(alternate, selection.active())
        selection.select(PRIMARY_PROFILE)
        assertEquals(PRIMARY_PROFILE, ProfileSelection(context).active())
        assertTrue(alternate in ProfileSelection(context).ids())
    }

    @Test fun disablingAnInactiveProfilePersistsAndPreventsSwitchingUntilReenabled() {
        val registry = ProfileSelection(context)
        val alternate = registry.select(null)
        assertThrows(IllegalStateException::class.java) { registry.setEnabled(alternate, false) }
        registry.select(PRIMARY_PROFILE)
        registry.setEnabled(alternate, false)
        val reloaded = ProfileSelection(context)
        assertFalse(reloaded.isEnabled(alternate))
        assertTrue(reloaded.isEnabled(PRIMARY_PROFILE))
        assertTrue(alternate in reloaded.ids())
        assertThrows(IllegalArgumentException::class.java) { reloaded.select(alternate) }
        reloaded.setEnabled(alternate, true)
        assertEquals(alternate, reloaded.select(alternate))
    }

    @Test fun profilesKeepSameRecordIdsAndSensitiveFilesSeparate() {
        val primary = ProfileStorage(context, PRIMARY_PROFILE)
        val alternateId = newId()
        val alternate = ProfileStorage(context, alternateId)
        assertEquals("p2p.db", primary.scoped("p2p.db"))
        assertEquals(context.filesDir.resolve("received"), primary.files("received"))
        assertEquals(context.noBackupFilesDir.resolve("chatmail"), primary.noBackup("chatmail"))

        val primaryBlob = primary.noBackup("chatmail").resolve("local-image")
        val alternateBlob = alternate.noBackup("chatmail").resolve("local-image")
        primaryBlob.parentFile?.mkdirs()
        alternateBlob.parentFile?.mkdirs()
        primaryBlob.writeText("first")
        alternateBlob.writeText("second")
        assertNull(primary.chatmailFile(alternateBlob.path))
        assertNull(alternate.chatmailFile(primaryBlob.path))
        assertEquals(alternateBlob.canonicalFile, alternate.chatmailFile(alternateBlob.path))
        primary.preferences("first-run-setup").edit().putBoolean("complete", true).commit()
        assertFalse(alternate.preferences("first-run-setup").getBoolean("complete", false))
        assertNotEquals(primary.files("received"), alternate.files("received"))

        val oldStore = LocalStore.open(context)
        val newStore = LocalStore.open(context, alternateId)
        try {
            runBlocking {
                val sameKey = nativeThreadKey(10)
                oldStore.records().put(Record(sameKey, "contact", obj("name" to "First contact").toString()))
                assertNull(newStore.records().get(sameKey))
                newStore.records().put(Record(sameKey, "contact", obj("name" to "Second contact").toString()))
                assertEquals("First contact", oldStore.records().get(sameKey)?.data()?.optString("name"))
                assertEquals("Second contact", newStore.records().get(sameKey)?.data()?.optString("name"))
            }
        } finally {
            newStore.close()
            oldStore.close()
        }
    }
}
