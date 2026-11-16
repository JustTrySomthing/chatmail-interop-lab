package app.p2p

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object BackupArchive {
    const val KEY_BYTES = 32
    private const val CHUNK_BYTES = 256 * 1024
    private const val TAG_BYTES = 16
    private const val NONCE_BYTES = 12
    private val magic = byteArrayOf(0x50, 0x32, 0x50, 0x42, 1)
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    fun writeHeader(output: DataOutputStream, metadata: ByteArray, password: CharArray) {
        val encrypted = RecoveryCipher.encrypt(metadata, password)
        output.write(magic)
        output.writeInt(encrypted.size)
        output.write(encrypted)
    }

    fun readHeader(input: DataInputStream, password: CharArray): ByteArray {
        input.mark(magic.size)
        val prefix = ByteArray(magic.size).also(input::readFully)
        val encrypted = if (prefix.contentEquals(magic)) {
            val size = input.readInt()
            require(size in 1..RecoveryCipher.MAX_BYTES)
            ByteArray(size).also(input::readFully)
        } else {
            input.reset()
            // One extra byte detects an oversized legacy file without reading it into memory.
            readLegacy(input)
        }
        return RecoveryCipher.decrypt(encrypted, password)
    }

    private fun readLegacy(input: InputStream): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return bytes.toByteArray()
            require(bytes.size() + count <= RecoveryCipher.MAX_BYTES)
            bytes.write(buffer, 0, count)
        }
    }

    fun writeMedia(output: OutputStream, input: InputStream, key: ByteArray, id: String, size: Long) {
        val source = DataInputStream(input)
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            chunks(size) { index, count ->
                source.readFully(buffer, 0, count)
                val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
                output.write(nonce)
                output.write(cipher(Cipher.ENCRYPT_MODE, key, id, index, nonce).doFinal(buffer, 0, count))
            }
            check(source.read() == -1) { "An attachment changed while the backup was saving. Try again." }
        } finally {
            buffer.fill(0)
        }
    }

    fun readMedia(input: DataInputStream, output: OutputStream, key: ByteArray, id: String, size: Long) {
        chunks(size) { index, count ->
            val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
            val encrypted = ByteArray(count + TAG_BYTES).also(input::readFully)
            val plain = cipher(Cipher.DECRYPT_MODE, key, id, index, nonce).doFinal(encrypted)
            try {
                output.write(plain)
            } finally {
                plain.fill(0)
            }
        }
    }

    private inline fun chunks(size: Long, process: (Long, Int) -> Unit) {
        var offset = 0L
        while (offset < size) {
            val count = minOf(CHUNK_BYTES.toLong(), size - offset).toInt()
            process(offset / CHUNK_BYTES, count)
            offset += count
        }
    }

    private fun cipher(mode: Int, key: ByteArray, id: String, index: Long, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(id.toByteArray(Charsets.US_ASCII))
            updateAAD(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(index).array())
        }
}
