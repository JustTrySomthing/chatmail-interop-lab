package app.p2p

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupArchiveTest {
    @Test
    fun restoresMetadataAndMultipleAttachments() {
        val metadata = "private conversation".toByteArray()
        val password = "backup passphrase".toCharArray()
        val key = BackupArchive.newKey()
        val large = ByteArray(900_000) { it.toByte() }
        val small = byteArrayOf(4, 2)
        val bytes = ByteArrayOutputStream()
        val archive = DataOutputStream(bytes)
        BackupArchive.writeHeader(archive, metadata, password)
        BackupArchive.writeMedia(archive, large.inputStream(), key, "first", large.size.toLong())
        BackupArchive.writeMedia(archive, small.inputStream(), key, "second", small.size.toLong())

        val input = DataInputStream(bytes.toByteArray().inputStream())
        assertArrayEquals(metadata, BackupArchive.readHeader(input, password))
        for ((id, expected) in listOf("first" to large, "second" to small)) {
            val restored = ByteArrayOutputStream()
            BackupArchive.readMedia(input, restored, key, id, expected.size.toLong())
            assertArrayEquals(expected, restored.toByteArray())
        }
        assertEquals(-1, input.read())
    }

    @Test
    fun rejectsModifiedTruncatedOrSwappedAttachmentData() {
        val media = ByteArray(400_000)
        val key = BackupArchive.newKey()
        val bytes = ByteArrayOutputStream()
        BackupArchive.writeMedia(bytes, media.inputStream(), key, "original", media.size.toLong())
        val encrypted = bytes.toByteArray()

        fun restore(data: ByteArray, id: String) = BackupArchive.readMedia(
            DataInputStream(data.inputStream()), ByteArrayOutputStream(), key, id, media.size.toLong()
        )

        assertThrows(AEADBadTagException::class.java) { restore(encrypted, "different") }
        assertThrows(EOFException::class.java) { restore(encrypted.copyOf(encrypted.size - 1), "original") }
        encrypted[40] = (encrypted[40].toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { restore(encrypted, "original") }
    }

    @Test
    fun readsOldRecoveryFilesAndRejectsWrongPassword() {
        val metadata = "legacy identity".toByteArray()
        val password = "backup passphrase".toCharArray()
        val legacy = RecoveryCipher.encrypt(metadata, password)
        assertArrayEquals(metadata, BackupArchive.readHeader(DataInputStream(legacy.inputStream()), password))
        assertThrows(AEADBadTagException::class.java) {
            BackupArchive.readHeader(DataInputStream(legacy.inputStream()), "incorrect password".toCharArray())
        }
    }
}
