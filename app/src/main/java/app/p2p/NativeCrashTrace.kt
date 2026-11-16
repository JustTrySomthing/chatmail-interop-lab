package app.p2p

/** Bounded reader for Android debuggerd's tombstone.proto; never decode binary as text. */
internal fun nativeCrashTrace(bytes: ByteArray): String = runCatching {
    val root = CrashProto(bytes).fields()
    val tid = root.number(6)
    val signal = root.message(10)
    val thread = root.filter { it.id == 16 }.map { CrashProto(it.bytes).fields() }
        .firstOrNull { it.number(1) == tid }?.message(2).orEmpty()
    buildString {
        appendLine("Native crash · ${signal.text(2)} ${signal.text(4)} · thread $tid ${thread.text(2)}")
        root.text(14).takeIf { it.isNotBlank() }?.let { appendLine(it) }
        thread.filter { it.id == 4 }.take(30).forEach {
            val frame = CrashProto(it.bytes).fields()
            appendLine("  ${frame.text(6).substringAfterLast('/')} + 0x${frame.number(1).toString(16)} ${frame.text(4)} (build ${frame.text(8)})")
        }
    }.take(12_000)
}.getOrElse { "Native crash report could not be decoded (binary data omitted)." }

private data class CrashField(val id: Int, val value: Long = 0, val bytes: ByteArray = byteArrayOf())
private fun List<CrashField>.number(id: Int) = firstOrNull { it.id == id }?.value ?: 0L
private fun List<CrashField>.text(id: Int) = firstOrNull { it.id == id }?.bytes?.toString(Charsets.UTF_8)
    ?.filter { it == '\n' || !it.isISOControl() }?.take(2_000).orEmpty()
private fun List<CrashField>.message(id: Int) = firstOrNull { it.id == id }?.let { CrashProto(it.bytes).fields() }.orEmpty()
private class CrashProto(private val data: ByteArray) {
    private var offset = 0
    private fun varint(): Long {
        var value = 0L
        for (shift in 0..63 step 7) {
            require(offset < data.size)
            val b = data[offset++].toInt() and 255
            value = value or ((b and 127).toLong() shl shift)
            if (b and 128 == 0) return value
        }
        error("Invalid varint")
    }
    fun fields(): List<CrashField> = buildList {
        while (offset < data.size) {
            require(size < 100_000)
            val tag = varint().toInt()
            require(tag ushr 3 > 0)
            when (tag and 7) {
                0 -> add(CrashField(tag ushr 3, varint()))
                1, 5 -> { offset += if (tag and 7 == 1) 8 else 4; require(offset <= data.size) }
                2 -> {
                    val length = varint()
                    require(length >= 0 && length <= data.size - offset)
                    val end = offset + length.toInt()
                    add(CrashField(tag ushr 3, bytes = data.copyOfRange(offset, end)))
                    offset = end
                }
                else -> error("Unsupported wire type")
            }
        }
    }
}
