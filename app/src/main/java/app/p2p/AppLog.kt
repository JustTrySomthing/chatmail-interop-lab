package app.p2p

import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant

internal object AppLog {
    @Synchronized fun record(context: Context, message: String, error: Throwable? = null) {
        runCatching {
            val file = File(context.filesDir, "app-log.txt")
            val previous = if (file.exists()) file.readText().take(100_000) else ""
            file.writeText("${Instant.now()} · ${BuildConfig.VERSION_NAME}\n$message\n${error?.stackTraceToString().orEmpty()}\n\n$previous")
        }
    }
    @Synchronized fun clear(context: Context) {
        // Keep the exit watermark so cleared historical crashes do not return.
        File(context.filesDir, "app-log.txt").writeText("")
    }
    fun collectPreviousCrashes(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences("app-log", Context.MODE_PRIVATE)
            val last = prefs.getLong("last-exit", 0L)
            val exits = context.getSystemService(android.app.ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .filter { it.timestamp > last }
            exits.sortedBy { it.timestamp }.forEach { exit ->
                if (exit.reason in setOf(android.app.ApplicationExitInfo.REASON_CRASH,
                        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, android.app.ApplicationExitInfo.REASON_ANR)) {
                    val trace = runCatching { exit.traceInputStream?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() < 4_000_000) {
                            val count = stream.read(buffer, 0, minOf(buffer.size, 4_000_000 - output.size()))
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                        }
                        val bytes = output.toByteArray()
                        if (exit.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) nativeCrashTrace(bytes)
                        else bytes.toString(Charsets.UTF_8).take(40_000)
                    } }.getOrNull().orEmpty()
                    record(context, "Previous process error at ${Instant.ofEpochMilli(exit.timestamp)} · reason ${exit.reason} · status ${exit.status}\n${exit.description.orEmpty()}\n$trace")
                }
            }
            exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("last-exit", it).apply() }
        }
    }
    @Synchronized fun read(context: Context): String =
        "P2P ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
            runCatching { File(context.filesDir, "app-log.txt").readText().ifBlank { "No activity recorded yet." } }.getOrDefault("No activity recorded yet.")
}
