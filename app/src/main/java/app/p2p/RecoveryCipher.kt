package app.p2p

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object RecoveryCipher {
    const val MAX_BYTES = 16 * 1024 * 1024
    private val magic = byteArrayOf(0x50, 0x32, 0x50, 0x52, 1)
    private const val SALT = 16
    private const val IV = 12
    private const val TAG = 16
    private const val HEADER = 5 + SALT + IV

    const val MAX_PAYLOAD_BYTES = MAX_BYTES - HEADER - TAG

    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES)
        require(password.size in 12..1024)
        val random = SecureRandom()
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val header = magic + salt + iv
        return header + cipher(Cipher.ENCRYPT_MODE, password, salt, iv, header).doFinal(payload)
    }

    fun decrypt(backup: ByteArray, password: CharArray): ByteArray {
        require(backup.size in (HEADER + TAG)..MAX_BYTES && backup.copyOfRange(0, magic.size).contentEquals(magic))
        require(password.size in 12..1024)
        val header = backup.copyOfRange(0, HEADER)
        val salt = header.copyOfRange(magic.size, magic.size + SALT)
        val iv = header.copyOfRange(magic.size + SALT, HEADER)
        return cipher(Cipher.DECRYPT_MODE, password, salt, iv, header).doFinal(backup, HEADER, backup.size - HEADER)
    }

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray, header: ByteArray): Cipher {
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                    updateAAD(header)
                }
            } finally {
                key.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }
}
