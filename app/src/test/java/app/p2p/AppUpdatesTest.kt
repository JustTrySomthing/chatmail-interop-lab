package app.p2p

import android.app.Application
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppUpdatesTest {
    private val now = 1_800_000_000_000L
    private val installed = requireNotNull(UpdateVersion.parse("0.6.0"))
    private val content = "APK test bytes".toByteArray()

    private fun release(value: String, suffix: String = "", preview: Boolean = false) =
        UpdateRelease(requireNotNull(UpdateVersion.parse(value)), "P2P-$value$suffix.apk",
            content.size.toLong(), digest(content), preview)

    private fun row(value: UpdateRelease, draft: Boolean = false, url: String = value.url, digest: String = "sha256:${value.digest}") =
        obj("tag_name" to "v${value.version}", "draft" to draft, "prerelease" to value.preview,
            "assets" to JSONArray().put(obj("name" to value.filename, "state" to "uploaded",
                "browser_download_url" to url, "size" to value.bytes, "digest" to digest)))

    @Test
    fun releaseCandidatesAreIncludedUnlessTheUserOptsOut() {
        val prefs = UpdateChecks.prefs(RuntimeEnvironment.getApplication())
        assertTrue(UpdateChecks.previews(prefs))
        prefs.edit { putBoolean("previews", false) }
        assertFalse(UpdateChecks.previews(prefs))
        prefs.edit { putBoolean("previews", true) }
        assertTrue(UpdateChecks.previews(prefs))
    }

    @Test
    fun freshRelayLabInstallEnablesDailyChecksAndReleaseCandidates() {
        val prefs = UpdateChecks.prefs(RuntimeEnvironment.getApplication())
        assertTrue(UpdateChecks.automatic(prefs))
        assertTrue(UpdateChecks.previews(prefs))
        assertTrue(UpdateChecks.due(prefs, 1, true, now))
        assertTrue(UpdateChecks.shouldPrompt(prefs, release("9.0.0"), now))
        prefs.edit { putBoolean("automatic", false); putBoolean("previews", false) }
        assertFalse(UpdateChecks.automatic(prefs))
        assertFalse(UpdateChecks.previews(prefs))
        assertFalse(UpdateChecks.due(prefs, 1, true, now))
    }

    @Test
    fun backgroundInstallationRequiresSeparateOptInAndDailyChecks() {
        val prefs = UpdateChecks.prefs(RuntimeEnvironment.getApplication())
        val candidate = release("9.0.0")
        assertFalse(UpdateAutoInstall.eligible(prefs, candidate))
        prefs.edit { putBoolean(UpdateAutoInstall.ENABLED, true) }
        assertTrue(UpdateAutoInstall.eligible(prefs, candidate))
        prefs.edit { putBoolean("automatic", false) }
        assertFalse(UpdateAutoInstall.eligible(prefs, candidate))
    }

    @Test
    fun autoCheckIsDailyButRunsOnceOnNextOpenAfterReboot() {
        val prefs = UpdateChecks.prefs(RuntimeEnvironment.getApplication())
        prefs.edit {
            putBoolean("automatic", true)
            putInt("openedBoot", 12)
            putInt("attemptBoot", 12)
            putLong("checked", now)
            putLong("attempted", now)
        }
        assertFalse(UpdateChecks.due(prefs, 12, true, now + UpdateChecks.DAY - 1))
        assertTrue(UpdateChecks.due(prefs, 12, true, now + UpdateChecks.DAY))
        assertFalse(UpdateChecks.due(prefs, 13, false, now + 1))
        assertTrue(UpdateChecks.due(prefs, 13, true, now + 1))
        prefs.edit { putInt("attemptBoot", 13) }
        assertFalse(UpdateChecks.due(prefs, 13, true, now + UpdateChecks.HOUR - 1))
        assertTrue(UpdateChecks.due(prefs, 13, true, now + UpdateChecks.HOUR))
        prefs.edit { putBoolean("automatic", false) }
        assertFalse(UpdateChecks.due(prefs, 14, true, now + UpdateChecks.DAY))
    }

    @Test
    fun dismissingOneReleaseDoesNotHideTheNext() {
        val prefs = UpdateChecks.prefs(RuntimeEnvironment.getApplication())
        val previous = release("0.6.1")
        prefs.edit {
            putBoolean("automatic", true)
            putString("dismissed", previous.version.toString())
            putLong("dismissedAt", now)
        }
        assertFalse(UpdateChecks.shouldPrompt(prefs, previous, now + 1))
        assertTrue(UpdateChecks.shouldPrompt(prefs, release("0.6.2"), now + 1))
        assertTrue(UpdateChecks.shouldPrompt(prefs, previous, now + UpdateChecks.DAY))
    }

    @Test
    fun selectsByVersionChannelAndDeviceArchitecture() {
        val old = release("0.6.9")
        val stable = release("0.6.10", "-arm64")
        val preview = release("0.7.0-rc1", preview = true)
        val rows = JSONArray().put(row(old)).put(row(stable)).put(row(preview))
            .put(row(release("9.9.9"), draft = true))
        assertEquals(stable, UpdateFeed.select(rows, installed, false, listOf("arm64-v8a")))
        assertEquals(old, UpdateFeed.select(rows, installed, false, listOf("x86_64")))
        assertEquals(preview, UpdateFeed.select(rows, installed, true, listOf("arm64-v8a")))
        assertTrue(requireNotNull(UpdateVersion.parse("0.7.0")) > preview.version)
    }

    @Test
    fun testBuildsAndRegularBuildsReadOnlyTheirOwnAssets() {
        val test = release("0.7.9", "-ARM64-Test", preview = true)
        val stable = release("0.7.10", "-arm64")
        val rows = JSONArray().put(row(test)).put(row(stable))
        assertEquals(stable, UpdateFeed.select(rows, installed, true, listOf("arm64-v8a")))
        assertEquals(test, UpdateFeed.select(rows, installed, true, listOf("arm64-v8a"), testBuild = true))
        assertNull(UpdateFeed.select(rows, installed, true, listOf("x86_64"), testBuild = true))
        assertNull(UpdateFeed.select(rows, installed, false, listOf("arm64-v8a"), testBuild = true))
        assertEquals(test, UpdateRelease.read(test.json().toString()))
        assertNull(UpdateRelease.read(test.copy(preview = false).json().toString()))
    }

    @Test
    fun rejectsUnverifiedMetadataAndUnexpectedDownloadHosts() {
        val candidate = release("0.6.1")
        assertThrows(IllegalStateException::class.java) {
            UpdateFeed.select(JSONArray().put(row(candidate, digest = "")), installed, false, listOf("arm64-v8a"))
        }
        assertThrows(IllegalStateException::class.java) {
            UpdateFeed.select(JSONArray().put(row(candidate, url = "https://example.org/P2P.apk")),
                installed, false, listOf("arm64-v8a"))
        }
        assertFalse(UpdateFeed.allowed(URI("https://release-assets.githubusercontent.com.example.org/update"), true))
        assertFalse(UpdateFeed.allowed(URI("https://user@github.com/update"), true))
        assertFalse(UpdateFeed.allowed(URI("https://github.com/update"), false))
        assertTrue(UpdateFeed.allowed(URI(candidate.url), true))
    }

    @Test
    fun onlyAcceptsMatchingNewerApksSignedByTheInstalledCertificate() {
        val candidate = release("0.6.1")
        val old = ApkIdentity("app.p2p", "0.6.0", 6, setOf("existing"), 31)
        val new = old.copy(version = "0.6.1", code = 7)
        UpdateApk.validate(old, new, candidate, 34)
        for (bad in listOf(new.copy(signers = setOf("other")), new.copy(signers = emptySet()),
            new.copy(packageName = "other.app"), new.copy(version = "0.6.2"), new.copy(code = 6), new.copy(minSdk = 35))) {
            assertThrows(IllegalStateException::class.java) { UpdateApk.validate(old, bad, candidate, 34) }
        }
    }

    @Test
    fun rejectsTruncatedTamperedOversizedAndCanceledDownloads() {
        val candidate = release("0.6.1")
        val file = File.createTempFile("p2p-test", ".part", RuntimeEnvironment.getApplication().cacheDir)
        try {
            runBlocking { UpdateFeed.copy(ByteArrayInputStream(content), file, candidate) {} }
            assertArrayEquals(content, file.readBytes())
            for (bad in listOf(content.copyOf(content.size - 1), ByteArray(content.size), content + byteArrayOf(0))) {
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { UpdateFeed.copy(ByteArrayInputStream(bad), file, candidate) {} }
                }
            }
            assertThrows(CancellationException::class.java) {
                runBlocking { UpdateFeed.copy(ByteArrayInputStream(content), file, candidate) { throw CancellationException() } }
            }
        } finally {
            file.delete()
        }
    }
}
