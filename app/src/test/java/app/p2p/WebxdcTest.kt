package app.p2p

import android.net.Uri
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
    @Test fun acceptsOnlyCatalogAssetsAndKeepsGamesAndTools() {
        val rows = """[
          {"app_id":"calculator","name":"Calculator","category":"tool","cache_relname":"calc.xdc","size":500},
          {"app_id":"pong","name":"Pong","category":"game","cache_relname":"pong.xdc","size":500},
          {"app_id":"external","name":"External","category":"game","cache_relname":"https://example.org/pong.xdc","size":500},
          {"app_id":"escaped","name":"Escaped","category":"tool","cache_relname":"../app.xdc","size":500}
        ]"""
        assertEquals(listOf("calculator", "pong"), parseWebxdcCatalog(rows).map(CatalogApp::id))
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
