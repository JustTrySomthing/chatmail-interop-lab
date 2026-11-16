package app.p2p

import android.app.Application
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryReleaseTest {
    @Test fun voicemailAndDeletedMessagesStayOutOfConversations() {
        for (kind in listOf("message", "file-sent", "file-received")) {
            assertTrue(Record("id", kind, obj().toString()).isThreadMessage())
            assertFalse(Record("id", kind, obj("voicemail" to true).toString()).isThreadMessage())
            assertFalse(Record("id", kind, obj("locallyDeleted" to true).toString()).isThreadMessage())
        }
    }
    @Test fun paletteMaintainsReadableBubbleTextAndRequestedDefaults() {
        assertEquals(18, Accent.entries.size)
        Accent.entries.forEach { assertTrue(it.name, contrastRatio(it.swatch, inkOnAccent(it.swatch)) >= 4.5f) }
        val defaults = Appearance.read(null)
        assertEquals(ThemeMode.DARK, defaults.mode)
        assertEquals(Accent.CLASSIC_VIOLET, defaults.accent)
        assertEquals("COSMIC_DRIFT", defaults.wallpaper)
        assertEquals(10, defaults.overlay)
        assertEquals(0, Appearance.read(obj("overlay" to -20)).overlay)
        assertEquals(100, Appearance.read(obj("overlay" to 130)).overlay)
        assertEquals(defaults, Appearance.read(defaults.json()))
    }
    @Test fun updaterFindsPreviewNamedApk() {
        val version = UpdateVersion.parse("0.7.23-rc28")!!
        val release = UpdateRelease(version, "P2P-$version-ARM64-Preview.apk", 90_000_000, "a".repeat(64), true)
        assertTrue(release.verifiedMetadata())
        val rows = JSONArray().put(obj("tag_name" to "v$version", "prerelease" to true, "assets" to JSONArray().put(
            obj("name" to release.filename, "state" to "uploaded", "size" to release.bytes,
                "digest" to "sha256:${release.digest}", "browser_download_url" to release.url))))
        assertEquals(release, UpdateFeed.select(rows, UpdateVersion.parse("0.7.23-rc27")!!, true, listOf("arm64-v8a"), true))
    }
}
