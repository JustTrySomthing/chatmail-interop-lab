package app.p2p

import org.junit.Assert.*
import org.junit.Test

class NativeCrashTraceTest {
    private fun v(value: Int): ByteArray {
        var n = value
        val out = java.io.ByteArrayOutputStream()
        do { out.write((n and 127) or if (n > 127) 128 else 0); n = n ushr 7 } while (n != 0)
        return out.toByteArray()
    }
    private fun number(id: Int, value: Int) = v(id * 8) + v(value)
    private fun bytes(id: Int, value: ByteArray) = v(id * 8 + 2) + v(value.size) + value
    private fun text(id: Int, value: String) = bytes(id, value.toByteArray())

    @Test fun reportsCrashingThreadInsteadOfUnrelatedWorker() {
        val frame = number(1, 1234) + text(4, "JNI_OnLoad") + text(6, "/lib/libjingle_peerconnection_so.so") + text(8, "abc")
        val worker = number(1, 2) + text(2, "worker")
        val main = number(1, 1) + text(2, "main") + bytes(4, frame)
        val report = number(6, 1) + bytes(10, text(2, "SIGTRAP")) +
            bytes(16, number(1, 2) + bytes(2, worker)) + bytes(16, number(1, 1) + bytes(2, main))
        val result = nativeCrashTrace(report)
        assertTrue(result.contains("SIGTRAP"))
        assertTrue(result.contains("thread 1 main"))
        assertTrue(result.contains("libjingle_peerconnection_so.so + 0x4d2 JNI_OnLoad"))
        assertFalse(result.contains("worker"))
    }
    @Test fun malformedBinaryNeverLeaksToCopiedLog() {
        assertTrue(nativeCrashTrace(byteArrayOf(0x82.toByte(), 0x7f, 0xff.toByte())).contains("binary data omitted"))
    }
    @Test fun nativeBootstrapClassesAndMethodsAreAvailable() {
        val type = Class.forName("org.jni_zero.JniInit")
        assertNotNull(type.getDeclaredMethod("init"))
        assertNotNull(type.getDeclaredMethod("crashIfMultiplexingMisaligned", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType))
        assertNotNull(Class.forName("org.jni_zero.JniUtil").getDeclaredMethod("arrayToMap", Array<Any>::class.java))
    }
}
