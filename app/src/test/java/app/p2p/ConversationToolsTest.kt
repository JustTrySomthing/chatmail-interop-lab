package app.p2p

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class ConversationToolsTest {
    @Test
    fun switchesFromTimeToMonthAndDayAt24Hours() {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val created = 1_794_057_300_000L
            assertEquals(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(created)),
                inboxTimestamp(created, created + 86_399_999L))
            assertEquals("Nov 7", inboxTimestamp(created, created + 86_400_000L))
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun matchesConversationTitlesAndEarlierMessageText() {
        assertTrue(threadSearchMatches("family receipt", "Family", listOf("Old PDF receipt")))
        assertTrue(threadSearchMatches("old PDF", "Group", listOf("Old PDF receipt")))
        assertFalse(threadSearchMatches("invoice", "Family", listOf("Old PDF receipt")))
    }

    @Test
    fun recognizesYoutubeWithoutTrustingLookalikeHosts() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, parsePreviewLink("https://youtu.be/$id?t=4")?.videoId)
        assertEquals(id, parsePreviewLink("https://www.youtube.com/shorts/$id")?.videoId)
        assertNull(parsePreviewLink("https://www.youtube.com.example.org/watch?v=$id")?.videoId)
        assertNull(parsePreviewLink("https://youtube.com@private.example/watch?v=$id"))
        assertNull(parsePreviewLink("http://127.0.0.1/"))
        assertNull(parsePreviewLink("file:///data/private"))
        assertEquals(1, previewLinks("Look at (https://example.com/test). https://example.com/test").size)
    }

    @Test
    fun upgradesHttpLinksOnOpenAndRejectsCleartextNavigations() {
        val secure = "https://example.com/a%2Fb?q=one%20two#part"
        val legacy = "http://example.com:80/a%2Fb?q=one%20two#part"
        assertEquals(secure, inAppBrowserUrl(secure))
        assertEquals(secure, inAppBrowserUrl(legacy, upgradeHttp = true))
        assertEquals("https://example.com:8443", inAppBrowserUrl("http://example.com:8443", upgradeHttp = true))
        assertNull(inAppBrowserUrl(legacy))
        assertNull(inAppBrowserUrl("http://user@example.com", upgradeHttp = true))
        assertNull(inAppBrowserUrl("http://127.0.0.1", upgradeHttp = true))
        assertNull(inAppBrowserUrl("file:///data/private", upgradeHttp = true))
    }

    @Test
    fun formatsInlineStylesWithoutChangingUnformattedText() {
        val text = formatMessageMarkdown("**bold** *italic* ~~strike~~ sample_name")
        assertEquals("bold italic strike sample_name", text.text)
        assertEquals(3, text.spanStyles.size)
        assertEquals("sample_name", formatMessageMarkdown("sample_name").text)
    }

    @Test
    fun appliesQualityFloorsAndPreservesContactCardLines() {
        assertTrue(ratesForMedia(8_000, true).video > ratesForMedia(180_000, true).video)
        assertEquals(48_000, ratesForMedia(600_000, false).audio)
        assertEquals("A\\, B\\nC", escapeVCard("A, B\nC"))
    }
}
