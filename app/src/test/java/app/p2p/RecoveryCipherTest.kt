package app.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

class RecoveryCipherTest {
    @Test
    fun encryptedBackupCanBeOpenedOnlyWithThePasswordAndUnmodifiedBytes() {
        val payload = "local identity fixture".toByteArray()
        val password = "one distinct recovery passphrase".toCharArray()
        val first = RecoveryCipher.encrypt(payload, password)
        val second = RecoveryCipher.encrypt(payload, password)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(payload, RecoveryCipher.decrypt(first, password))
        assertThrows(AEADBadTagException::class.java) {
            RecoveryCipher.decrypt(first, "incorrect recovery password".toCharArray())
        }
        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertThrows(AEADBadTagException::class.java) { RecoveryCipher.decrypt(first, password) }
    }
}
