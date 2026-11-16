package app.p2p

import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferDiskTest {
    @get:Rule val root = TemporaryFolder()

    @Test
    fun resumesAfterAcknowledgementLossAndPublishesOnlyVerifiedBytes() {
        val partial = root.root.resolve("partial")
        val finished = root.root.resolve("finished")
        val first = "hello ".toByteArray()
        val last = "P2P".toByteArray()
        val content = first + last
        val size = content.size.toLong()
        val position = first.size.toLong()
        val hash = digest(content)

        assertEquals(TransferDisk.Result.Committed(position),
            TransferDisk.commit(partial, finished, 0, 0, size, first, digest(first), hash))
        assertFalse(finished.exists())
        assertEquals(TransferDisk.Result.Committed(position),
            TransferDisk.commit(partial, finished, position, 0, size, first, digest(first), hash))
        assertEquals(TransferDisk.Result.Committed(size, true),
            TransferDisk.commit(partial, finished, position, position, size, last, digest(last), hash))
        assertArrayEquals(content, finished.readBytes())
        assertFalse(partial.exists())

        // A crash after the rename may leave the last committed database offset behind.
        assertEquals(TransferDisk.Result.Committed(size, true),
            TransferDisk.commit(partial, finished, position, position, size, last, digest(last), hash))
    }

    @Test
    fun rejectsCorruptedChunksAndWholeFiles() {
        val partial = root.root.resolve("partial")
        val finished = root.root.resolve("finished")
        val bytes = byteArrayOf(1, 2, 3)
        val size = bytes.size.toLong()
        assertEquals(TransferDisk.Result.Retry,
            TransferDisk.commit(partial, finished, 0, 0, size, bytes, "wrong", digest(bytes)))
        assertFalse(partial.exists())
        assertEquals(TransferDisk.Result.IntegrityFailure,
            TransferDisk.commit(partial, finished, 0, 0, size, bytes, digest(bytes), "wrong"))
        assertFalse(partial.exists())
        assertFalse(finished.exists())
    }

    @Test
    fun keepsOffsetsAboveFourGiBWithoutNarrowing() {
        val partial = root.root.resolve("partial")
        val finished = root.root.resolve("finished")
        val offset = 5_000_000_000L
        val data = byteArrayOf(21, 42)
        RandomAccessFile(partial, "rw").use { it.setLength(offset) }
        assertEquals(TransferDisk.Result.Committed(offset + data.size),
            TransferDisk.commit(partial, finished, offset, offset, offset + 20, data, digest(data), "unused"))
        RandomAccessFile(partial, "r").use {
            it.seek(offset)
            val received = ByteArray(data.size).also(it::readFully)
            assertArrayEquals(data, received)
        }
    }

    @Test
    fun handlesAnEmptyFileAndDetectsMissingPartialData() {
        val partial = root.root.resolve("partial")
        val finished = root.root.resolve("finished")
        val empty = byteArrayOf()
        assertEquals(TransferDisk.Result.Committed(0, true),
            TransferDisk.commit(partial, finished, 0, 0, 0, empty, digest(empty), digest(empty)))
        val bytes = byteArrayOf(9)
        assertEquals(TransferDisk.Result.Restart,
            TransferDisk.commit(partial, finished, 4, 4, 6, bytes, digest(bytes), "unused"))
    }
}
