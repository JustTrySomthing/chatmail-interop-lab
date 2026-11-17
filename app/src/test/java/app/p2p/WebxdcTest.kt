package app.p2p

import android.Manifest
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebxdcTest {
    @Test fun chatAppListKeepsSeparateSharedInstancesAndExcludesDeletedMessages() {
        val thread = nativeThreadKey(12)
        fun app(id: Int, owner: String = thread, deleted: Boolean = false) =
            Record(nativeMessageKey(id), "file-sent",
                obj("thread" to owner, "nativeMessage" to id, "webxdc" to true,
                    "name" to "Checklist.xdc", "locallyDeleted" to deleted).toString())
        assertEquals(listOf(nativeMessageKey(20), nativeMessageKey(21)),
            listOf(app(20), app(21), app(22, deleted = true), app(23, nativeThreadKey(13)))
                .webxdcMessages(thread).map(Record::key))
    }

    @Test fun aHomeShortcutCarriesTheExactProfileChatAndInstance() {
        val shortcut = WebxdcShortcut(newId(), nativeThreadKey(12), 24)
        val launch = shortcut.intent(RuntimeEnvironment.getApplication())
        assertEquals(shortcut, WebxdcShortcut.read(launch))
        assertEquals(MainActivity::class.java.name, launch.component?.className)
        assertNull(WebxdcShortcut.read(launch.putExtra("message", 1)))
    }

    @Test fun acceptsOnlyCatalogAssetsAndKeepsGamesAndTools() {
        val rows = """[
          {"app_id":"calculator","name":"Calculator","category":"tool","cache_relname":"calc.xdc","size":500},
          {"app_id":"pong","name":"Pong","category":"game","cache_relname":"pong.xdc","size":500},
          {"app_id":"external","name":"External","category":"game","cache_relname":"https://example.org/pong.xdc","size":500},
          {"app_id":"escaped","name":"Escaped","category":"tool","cache_relname":"../app.xdc","size":500}
        ]"""
        assertEquals(listOf("calculator", "pong"), parseWebxdcCatalog(rows).map(CatalogApp::id))
    }

    @Test fun catalogKeepsLastGoodIndexAcrossAnInterruptedWriteAndInvalidResponses() {
        val directory = java.nio.file.Files.createTempDirectory("webxdc-index").toFile()
        try {
            val file = File(directory, "index.json")
            val json = """[
                {"app_id":"pixel","name":"Pixel","category":"tool","cache_relname":"pixel.xdc","size":400}
            ]"""
            val fetched = loadCatalogIndex(file) { json }
            assertFalse(fetched.cached)
            android.util.AtomicFile(file).startWrite().use { it.write("[".toByteArray()) }
            val cached = loadCatalogIndex(file) { "[]" }
            assertTrue(cached.cached)
            assertEquals(fetched.entries, cached.entries)
            assertEquals(fetched.entries, loadCatalogIndex(file) { error("Offline") }.entries)
        } finally { directory.deleteRecursively() }
    }

    @Test fun stagesCatalogArchivesLargerAndSmallerThanTheirListedSizes() {
        val cache = java.nio.file.Files.createTempDirectory("catalog-test").toFile()
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("index.html"))
                    zip.write("<html>App</html>".toByteArray())
                    zip.closeEntry()
                }
                output.toByteArray()
            }
            for (listedSize in listOf(bytes.size - 20, bytes.size + 20)) {
                val app = parseWebxdcCatalog("""[
                    {"app_id":"pixel","name":"Pixel","category":"tool","cache_relname":"pixel.xdc","size":$listedSize}
                ]""").single()
                val draft = stageCatalogApp(cache, app) { filename, limit, output ->
                    assertEquals("pixel.xdc", filename)
                    assertEquals(NATIVE_ATTACHMENT_FILE_LIMIT, limit)
                    copyCatalogDownload(bytes.inputStream(), output, bytes.size.toLong(), limit)
                }
                assertArrayEquals(bytes, File(checkNotNull(draft.uri.path)).readBytes())
                assertTrue(draft.temporary)
            }
            val app = CatalogApp("pixel", "Pixel", "", "tool", "pixel.xdc", "")
            val captures = File(cache, "captures")
            val accepted = captures.list().orEmpty().toSet()
            assertThrows(java.util.zip.ZipException::class.java) {
                stageCatalogApp(cache, app) { _, _, output -> output.write("not an archive".toByteArray()) }
            }
            assertEquals(accepted, captures.list().orEmpty().toSet())
        } finally { cache.deleteRecursively() }
    }

    @Test fun rejectsTruncatedAndOversizedCatalogTransfers() {
        val bytes = byteArrayOf(1, 2, 3)
        assertThrows(IllegalStateException::class.java) {
            copyCatalogDownload(bytes.inputStream(), ByteArrayOutputStream(), length = 4, limit = 5)
        }
        assertThrows(IllegalStateException::class.java) {
            copyCatalogDownload(bytes.inputStream(), ByteArrayOutputStream(), length = 3, limit = 2)
        }
        assertThrows(IllegalStateException::class.java) {
            copyCatalogDownload(bytes.inputStream(), ByteArrayOutputStream(), length = -1, limit = 2)
        }
        val output = ByteArrayOutputStream()
        copyCatalogDownload(bytes.inputStream(), output, length = -1, limit = 3)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun nativeSizeGuardAcceptsFilesAboveTheLegacyLimit() {
        val input = object : java.io.InputStream() {
            private var remaining = 20_000_001L
            override fun read() = error("Expected buffered reads")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                remaining -= count
                return count
            }
        }
        var written = 0L
        val output = object : java.io.OutputStream() {
            override fun write(value: Int) = error("Expected buffered writes")
            override fun write(buffer: ByteArray, offset: Int, length: Int) { written += length }
        }
        assertEquals(20_000_001L, input.copyBoundedTo(output, NATIVE_ATTACHMENT_FILE_LIMIT, "Too large"))
        assertEquals(20_000_001L, written)
    }

    @Test fun streamsLargeWebxdcAssetsAndDecodedExports() {
        val file = File.createTempFile("webxdc-large-", ".xdc")
        try {
            val sample = ByteArray(1024 * 1024) { (it % 251).toByte() }
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("index.html"))
                zip.write("<html></html>".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("large.bin"))
                repeat(33) { zip.write(sample) }
                zip.closeEntry()
            }
            inspectWebxdc(file)
            openWebxdcAsset(file, "/large.bin").use { input ->
                val first = input.readNBytes(sample.size)
                assertArrayEquals(sample, first)
                assertEquals(32L * sample.size, input.transferTo(java.io.OutputStream.nullOutputStream()))
            }
            val encoded = java.util.Base64.getEncoder().encodeToString(sample)
            openWebxdcExport(encoded).use { input -> assertArrayEquals(sample, input.readBytes()) }
        } finally { file.delete() }
    }

    @Test fun resourceRequestsStayOnTheExactInstanceOrigin() {
        val host = "rl-test-msg12.localhost"
        fun path(url: String) = webxdcRequestPath(Uri.parse(url), host)
        assertEquals("/index.html", path("https://$host/index.html"))
        assertNull(path("http://$host/index.html"))
        assertNull(path("https://$host:443/index.html"))
        assertNull(path("https://$host.example.org/index.html"))
        assertNull(path("https://$host/%2e%2e/index.html"))
        assertNull(path("https://rl-test-msg13.localhost/index.html"))
        assertNull(path("file:///index.html"))
    }

    @Test fun cameraAndMicrophoneAreTheOnlyWebxdcCaptureResources() {
        val host = "rl-test-msg12.localhost"
        fun permissions(url: String, vararg resources: String) =
            webxdcCaptureCapabilities(Uri.parse(url), host, arrayOf(*resources))
        val video = PermissionRequest.RESOURCE_VIDEO_CAPTURE
        val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
        assertEquals(listOf(WebxdcCapability.CAMERA, WebxdcCapability.MICROPHONE),
            permissions("https://$host", video, audio))
        assertNull(permissions("http://$host", video))
        assertNull(permissions("https://rl-test-msg13.localhost", video))
        assertNull(permissions("https://$host:443", video))
        assertNull(permissions("https://$host", video, "new-unknown-resource"))
        assertNull(permissions("https://$host", PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
    }

    @Test fun webxdcCaptureRequiresConsentAndRejectsHiddenOrCanceledRequests() {
        val host = "rl-test-msg12.localhost"
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        val access = WebxdcPermissions(application, host)
        class Request : PermissionRequest() {
            var granted: List<String>? = null
            var denied = false
            override fun getOrigin() = Uri.parse("https://$host")
            override fun getResources() = arrayOf(RESOURCE_VIDEO_CAPTURE)
            override fun grant(resources: Array<String>) { granted = resources.toList() }
            override fun deny() { denied = true }
        }
        val hidden = Request()
        access.receiveCapture(hidden, foreground = false)
        assertTrue(hidden.denied)
        val canceled = Request()
        access.receiveCapture(canceled, foreground = true)
        assertNull(canceled.granted)
        access.captureCanceled(canceled)
        access.approve(foreground = true) { fail("No pending request") }
        assertNull(canceled.granted)
        val allowed = Request()
        access.receiveCapture(allowed, foreground = true)
        assertEquals(listOf(WebxdcCapability.CAMERA), access.promptCapabilities)
        assertNull(allowed.granted)
        access.approve(foreground = true) { fail("Android camera access is already granted") }
        assertEquals(listOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE), allowed.granted)
        assertTrue(access.authorized)
    }

    @Test fun webxdcLocationRequiresExactOriginConsentAndAndroidPermission() {
        val host = "rl-test-msg12.localhost"
        val origin = "https://$host"
        val application = RuntimeEnvironment.getApplication()
        val access = WebxdcPermissions(application, host)
        val answers = mutableListOf<Triple<String, Boolean, Boolean>>()
        val callback = GeolocationPermissions.Callback { requestOrigin, allow, retain ->
            answers += Triple(requestOrigin, allow, retain)
        }

        access.receiveLocation("$origin:443", callback, foreground = true)
        access.receiveLocation(origin, callback, foreground = false)
        assertEquals(listOf(Triple("$origin:443", false, false), Triple(origin, false, false)), answers)
        access.receiveLocation(origin, callback, foreground = true)
        assertEquals(listOf(WebxdcCapability.LOCATION), access.promptCapabilities)
        access.locationCanceled()
        assertNull(access.promptCapabilities)
        assertEquals(2, answers.size)

        access.receiveLocation(origin, callback, foreground = true)
        access.approve(foreground = true) { requested ->
            assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                requested.toList())
        }
        assertEquals(2, answers.size)
        assertFalse(access.authorized)
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        access.androidResult(foreground = false)
        assertEquals(2, answers.size)
        access.settle(foreground = true)
        assertEquals(Triple(origin, true, false), answers.last())
        assertTrue(access.authorized)
    }

    @Test fun webxdcMotionPolicyIsScopedToAnApprovedSession() {
        val application = RuntimeEnvironment.getApplication()
        val access = WebxdcPermissions(application, "rl-test-msg12.localhost")
        val other = WebxdcPermissions(application, "rl-test-msg13.localhost")
        val answers = mutableListOf<Boolean>()
        access.receiveMotion(foreground = false) { answers.add(it) }
        access.receiveMotion(foreground = true) { answers.add(it) }
        assertEquals(listOf(WebxdcCapability.MOTION), access.promptCapabilities)
        assertTrue(webxdcPermissionsPolicy(access.motionAllowed).contains("accelerometer=()"))
        access.deny()
        assertEquals(listOf(false, false), answers)
        access.receiveMotion(foreground = true) { answers.add(it) }
        access.approve(foreground = true) { fail("Motion requires no Android runtime permission") }
        assertEquals(listOf(false, false, true), answers)
        assertTrue(access.authorized)
        assertTrue(webxdcPermissionsPolicy(access.motionAllowed).contains("accelerometer=(self)"))
        assertTrue(webxdcPermissionsPolicy(access.motionAllowed).contains("ambient-light-sensor=()"))
        assertFalse(other.motionAllowed)
    }

    @Test fun preservesNativeWebxdcTypeEvenForGenericFilePicker() {
        assertEquals("Webxdc", chatmailAttachmentViewType("application/octet-stream", true, name = "Pong.XDC"))
        val item = readChatmailAttachment(obj("viewType" to "Webxdc", "fileName" to "Pong.xdc",
            "downloadState" to "Available"))
        assertNotNull(item)
        assertTrue(checkNotNull(item).displayData(null).getBoolean("webxdc"))
    }

    @Test fun appInfoRequiresAVerifiedParentInTheSameChat() {
        val row = obj("id" to 14, "chatId" to 12, "kind" to "message", "fromId" to 1,
            "isInfo" to true, "systemMessageType" to "WebxdcInfoMessage", "parentId" to 13,
            "text" to "New turn", "webxdcHref" to "#turn")
        assertNull(readNativeMessage(row, 12))
        assertNull(readNativeMessage(row, 12, webxdcParent = 15))
        assertNull(readNativeMessage(row, 20, webxdcParent = 13))
        assertEquals("#turn", readNativeMessage(row, 12, webxdcParent = 13)?.webxdcHref)
    }

    @Test fun archiveNeedsAnIndexAtItsRoot() {
        val file = File.createTempFile("webxdc-test-", ".xdc")
        try {
            fun writeEntry(name: String) = ZipOutputStream(file.outputStream()).use {
                it.putNextEntry(ZipEntry(name))
                it.write("<html>test</html>".toByteArray())
                it.closeEntry()
            }
            writeEntry("app/index.html")
            assertThrows(IllegalArgumentException::class.java) { inspectWebxdc(file) }
            writeEntry("index.html")
            inspectWebxdc(file)
        } finally { file.delete() }
    }
}
