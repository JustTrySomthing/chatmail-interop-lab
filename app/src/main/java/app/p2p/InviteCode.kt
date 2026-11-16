package app.p2p

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object InviteCode {
    private const val LEGACY = "p2p1:"
    private const val COMPACT = "p2p2:"
    private const val MAX_CODE = 16_384
    private const val MAX_SIGNED = 12_286
    private val alphabet = Regex("[A-Za-z0-9_-]+")

    fun hasPrefix(text: String): Boolean = text.startsWith(LEGACY) || text.startsWith(COMPACT) ||
        text.startsWith(CHATMAIL_INVITE_PREFIX)

    private fun normalize(text: String): String {
        require(text.length < MAX_CODE * 2) { "This invite code is too long." }
        val value = text.filterNot { it.isWhitespace() }
        require(hasPrefix(value)) { "Paste the full code from the other device’s Copy code button." }
        require(value.length < MAX_CODE && alphabet.matches(value.substringAfter(':'))) {
            "The invite code is incomplete or damaged. Copy the full code again."
        }
        return value
    }

    fun compact(legacy: String): String {
        val value = normalize(legacy)
        if (value.startsWith(COMPACT) || value.startsWith(CHATMAIL_INVITE_PREFIX)) return value
        val bytes = decode(value.substringAfter(':'))
        require(bytes.size in 1..MAX_SIGNED)
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(bytes) }
        return COMPACT + encode(buffer.toByteArray())
    }

    fun signedBytes(text: String): ByteArray {
        val value = normalize(text)
        return runCatching {
            val bytes = decode(value.substringAfter(':'))
            if (value.startsWith(LEGACY)) {
                require(bytes.size in 1..MAX_SIGNED)
                return@runCatching bytes
            }
            val output = ByteArrayOutputStream()
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_SIGNED)
                    output.write(buffer, 0, count)
                }
            }
            require(output.size() > 0)
            output.toByteArray()
        }.getOrElse { throw IllegalArgumentException("The invite code is incomplete or damaged. Copy the full code again.") }
    }
}
