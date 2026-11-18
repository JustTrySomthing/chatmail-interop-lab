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

    @Test fun appStorageTrackingIsScopedToItsProfile() {
        val original = ProfileStorage(context, PRIMARY_PROFILE)
        val other = ProfileStorage(context, newId())
        original.rememberWebxdcSite(25, "original.localhost")
        other.rememberWebxdcSite(25, "other.localhost")
        assertEquals(listOf(25 to "original.localhost"), original.webxdcSites())
        assertEquals(listOf(25 to "other.localhost"), other.webxdcSites())
    }

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
    @Test fun removingOriginalProfileDoesNotResurrectItAfterRestart() {
        val registry = ProfileSelection(context)
        val alternate = registry.select(null)
        registry.remove(PRIMARY_PROFILE)
        val restarted = ProfileSelection(context)
        assertEquals(setOf(alternate), restarted.ids())
        assertEquals(alternate, restarted.active())
        assertThrows(IllegalStateException::class.java) { restarted.remove(alternate) }
        assertThrows(IllegalArgumentException::class.java) { restarted.select(PRIMARY_PROFILE) }
    }

    @Test fun deletingOriginalFilesPreservesOtherProfileMediaAndDatabases() {
        val otherId = newId()
        val original = ProfileStorage(context, PRIMARY_PROFILE)
        val other = ProfileStorage(context, otherId)
        for (name in listOf("sent", "received", "partial")) {
            original.files(name).resolve("original").apply { parentFile!!.mkdirs(); writeText("original") }
            other.files(name).resolve("other").apply { parentFile!!.mkdirs(); writeText("other") }
        }
        original.noBackup("chatmail").resolve("db").apply { parentFile!!.mkdirs(); writeText("original") }
        other.noBackup("chatmail").resolve("db").apply { parentFile!!.mkdirs(); writeText("other") }
        context.getDatabasePath(other.scoped("p2p.db")).apply { parentFile!!.mkdirs(); writeText("other database") }
        deleteProfileFiles(context, PRIMARY_PROFILE)
        for (name in listOf("sent", "received", "partial")) {
            assertFalse(original.files(name).resolve("original").exists())
            assertEquals("other", other.files(name).resolve("other").readText())
        }
        assertFalse(original.noBackup("chatmail").exists())
        assertEquals("other", other.noBackup("chatmail").resolve("db").readText())
        assertEquals("other database", context.getDatabasePath(other.scoped("p2p.db")).readText())
        deleteProfileFiles(context, otherId)
        assertFalse(other.noBackup("chatmail").exists())
        assertFalse(other.files("received").exists())
        assertFalse(context.getDatabasePath(other.scoped("p2p.db")).exists())
    }

}
