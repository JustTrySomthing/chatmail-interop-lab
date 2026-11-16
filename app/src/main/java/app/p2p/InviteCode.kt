package app.p2p

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

internal const val CHATMAIL_INVITE_PREFIX = "p2p3:"
private const val MAX_CODE = 16_384
private const val MAX_SIGNED = 12_286
private val inviteAlphabet = Regex("[A-Za-z0-9_-]+")

internal fun chatmailInviteBytes(code: String): ByteArray {
    require(code.length < MAX_CODE * 2) { "This invite code is too long." }
    val normalized = code.filterNot(Char::isWhitespace)
    require(normalized.startsWith(CHATMAIL_INVITE_PREFIX)) {
        "Use a standard Chatmail invite link or ask the sender for a fresh invite."
    }
    val payload = normalized.removePrefix(CHATMAIL_INVITE_PREFIX)
    require(normalized.length < MAX_CODE && inviteAlphabet.matches(payload)) {
        "The invite code is incomplete or damaged. Copy the full code again."
    }
    return runCatching {
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(decode(payload))).use { input ->
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
    }.getOrElse {
        throw IllegalArgumentException("The invite code is incomplete or damaged. Copy the full code again.")
    }
}
