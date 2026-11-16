package app.p2p

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** The caller serializes writes and saves the returned offset before acknowledging it. */
internal object TransferDisk {
    sealed interface Result {
        data class Committed(val offset: Long, val complete: Boolean = false) : Result
        data object Retry : Result
        data object Restart : Result
        data object IntegrityFailure : Result
    }

    fun commit(
        partial: File,
        finished: File,
        expected: Long,
        offset: Long,
        size: Long,
        bytes: ByteArray,
        chunkHash: String,
        fileHash: String
    ): Result {
        if (size < 0 || expected !in 0..size || offset !in 0..expected || bytes.size.toLong() > size - offset) {
            return Result.Retry
        }
        val next = offset + bytes.size
        if ((bytes.isEmpty() && offset != size) || digest(bytes) != chunkHash) return Result.Retry
        if (offset < expected) {
            if (next > expected) return Result.Retry
            if (expected == size && !partial.exists() && verify(finished, size, fileHash)) {
                return Result.Committed(expected, complete = true)
            }
            if (!partial.isFile || partial.length() < expected) return Result.Restart
            return Result.Committed(expected)
        }
        // The file may have been published just before process death, before Room recorded completion.
        if (!partial.exists() && next == size && verify(finished, size, fileHash)) {
            return Result.Committed(size, complete = true)
        }
        if (offset > 0L && (!partial.isFile || partial.length() < offset)) return Result.Restart
        RandomAccessFile(partial, "rw").use { output ->
            output.seek(offset)
            output.write(bytes)
            output.setLength(next)
            output.fd.sync()
        }
        if (next != size) return Result.Committed(next)
        if (!verify(partial, size, fileHash)) {
            partial.delete()
            return Result.IntegrityFailure
        }
        if (!partial.renameTo(finished)) return Result.Retry
        return Result.Committed(size, complete = true)
    }

    private fun verify(file: File, size: Long, hash: String): Boolean {
        if (!file.isFile || file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex() == hash
    }
}
