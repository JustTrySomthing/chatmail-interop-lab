package app.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudTest {
    @Test
    fun chunksLongMessagesWithoutDroppingTextOrSplittingSurrogatePairs() {
        val text = "a".repeat(109) + "𐐀" + "b".repeat(400)
        val chunks = speechChunks(text).toList()
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.first().length <= 110)
        assertTrue(chunks.all { it.length <= 220 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }

    @Test
    fun keepsShortMessagesAndLimitsSavedSpeed() {
        assertEquals(listOf("See you soon."), speechChunks("See you soon.").toList())
        assertEquals(1f, readingSpeed(Float.NaN))
        assertEquals(0.75f, readingSpeed(0.5f))
        assertEquals(1.5f, readingSpeed(2f))
        assertEquals(2, ReadAloudVoice.Male.speaker)
        assertEquals(0, ReadAloudVoice.Female.speaker)
    }
}
