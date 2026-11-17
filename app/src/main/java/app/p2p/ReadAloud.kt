package app.p2p

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.onklav.home.NeuralSpeechNative
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

internal enum class ReadAloudVoice(val speaker: Int, val label: String) {
    Male(2, "Male"), Female(0, "Female")
}

internal fun readingSpeed(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.75f, 1.5f) else 1f

internal fun speechChunks(text: String): Sequence<String> = sequence {
    var start = 0
    while (start < text.length) {
        val limit = if (start == 0 && text.length > 220) 110 else 220
        var end = (start + limit).coerceAtMost(text.length)
        if (end < text.length) {
            if (text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
            val clause = (end downTo start + 45).firstOrNull { text[it - 1] in ".!?。！？;:\n" }
            val word = (end downTo start + 1).firstOrNull { text[it - 1].isWhitespace() }
            end = clause ?: word ?: end
        }
        text.substring(start, end).takeIf(String::isNotBlank)?.let { yield(it) }
        start = end
    }
}

private object IncludedSpeech {
    val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "P2P speech synthesis").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private var ready = false

    // The worker owns the process-wide model and serializes initialization and synthesis.
    fun prepare(context: Context) {
        if (ready) return
        NeuralSpeechNative.load()
        NeuralSpeechNative.initialize(installVoicePack(context).absolutePath)
        ready = true
    }

    private fun installVoicePack(context: Context): File {
        val manifest = JSONObject(context.assets.open("speech/english.json").bufferedReader().use { it.readText() })
        val entries = manifest.getJSONObject("files")
        val version = manifest.getString("archive_sha256")
        val root = File(context.noBackupFilesDir, "speech/$version")
        val marker = File(root, ".verified")
        if (marker.isFile && marker.readText() == version && entries.keys().asSequence().all { name ->
                File(root, name).let { it.isFile && it.length() == entries.getJSONObject(name).getLong("bytes") }
            }) return root

        val staging = File(root.parentFile, "installing")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Couldn’t prepare storage for the included voices." }
        try {
            val seen = mutableSetOf<String>()
            ZipInputStream(context.assets.open("speech/english.voicepack")).use { archive ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val name = entry.name
                    check(!entry.isDirectory && entries.has(name) && seen.add(name))
                    val file = File(staging, name)
                    check(file.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                    val expected = entries.getJSONObject(name)
                    val bytes = expected.getLong("bytes")
                    val hash = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        while (true) {
                            val count = archive.read(buffer)
                            if (count < 0) break
                            written += count
                            check(written <= bytes)
                            hash.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                    check(written == bytes && hash.digest().hex() == expected.getString("sha256"))
                }
            }
            check(seen.size == entries.length())
            File(staging, ".verified").writeText(version)
            check(root.deleteRecursively() && staging.renameTo(root))
            return root
        } finally {
            staging.deleteRecursively()
        }
    }
}

internal class ReadAloud(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("text-to-speech", Context.MODE_PRIVATE)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())
        .setOnAudioFocusChangeListener { if (it <= 0) stop() }
        .build()

    private val playbackLock = Any()
    private var handle = 0L
    private var playback: Job? = null
    private var warmup: Job? = null
    val supported = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
    var active by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var voice by mutableStateOf(ReadAloudVoice.entries.firstOrNull { it.name == preferences.getString("voice", null) }
        ?: ReadAloudVoice.Male)
        private set
    var speed by mutableStateOf(readingSpeed(preferences.getFloat("speed", 1f)))
        private set

    fun prepare() {
        if (!supported || warmup?.isActive == true) return
        warmup = scope.launch(IncludedSpeech.worker) {
            try {
                IncludedSpeech.prepare(app)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w("P2pSpeech", "Could not preload voices", failure)
            } catch (failure: LinkageError) {
                android.util.Log.w("P2pSpeech", "Could not load speech runtime", failure)
            }
        }
    }

    fun toggle(key: String, text: String) {
        if (active == key) {
            stop()
            return
        }
        stop()
        error = null
        if (text.isBlank()) return
        if (!supported) {
            error = "The included voices are available on ARM64 Android devices."
            return
        }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error = "Audio is busy. Try again."
            return
        }
        // JNI accepts upstream voice IDs and maps them to the compact voice bank.
        val speaker = voice.speaker
        val rate = speed
        active = key
        playback = scope.launch {
            try {
                withContext(IncludedSpeech.worker) {
                    IncludedSpeech.prepare(app)
                    val spoken = SpeechReading.prepare(text.replace('\u0000', ' ')).text
                    if (spoken.isBlank()) return@withContext
                    ensureActive()
                    val opened = NeuralSpeechNative.open()
                    try {
                        synchronized(playbackLock) {
                            ensureActive()
                            handle = opened
                        }
                        for (chunk in speechChunks(spoken)) {
                            ensureActive()
                            val samples = NeuralSpeechNative.synthesize(chunk, speaker, rate)
                            ensureActive()
                            NeuralSpeechNative.enqueue(opened, samples)
                        }
                        var remaining = Long.MAX_VALUE
                        var progressAt = SystemClock.elapsedRealtime()
                        while (true) {
                            ensureActive()
                            val buffered = NeuralSpeechNative.buffered(opened)
                            if (buffered == 0L) break
                            val now = SystemClock.elapsedRealtime()
                            if (buffered < remaining) progressAt = now
                            check(now - progressAt < 5_000L) { "Speech playback stalled." }
                            remaining = buffered
                            Thread.sleep(12)
                        }
                    } finally {
                        synchronized(playbackLock) { handle = 0L }
                        NeuralSpeechNative.close(opened)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w("P2pSpeech", "Reading failed", failure)
                error = "Couldn’t read this text. Tap the speaker to try again."
            } catch (failure: LinkageError) {
                android.util.Log.w("P2pSpeech", "Could not load speech runtime", failure)
                error = "Couldn’t load the included voices."
            }
            stop()
        }
    }

    fun stop() {
        playback?.cancel()
        playback = null
        synchronized(playbackLock) {
            if (handle != 0L) NeuralSpeechNative.cancel(handle)
        }
        if (active != null) audio.abandonAudioFocusRequest(focus)
        active = null
    }

    fun clearError(message: String) {
        if (error == message) error = null
    }

    fun choose(value: ReadAloudVoice) {
        stop()
        voice = value
        preferences.edit().putString("voice", value.name).apply()
    }

    fun changeSpeed(value: Float) {
        if (active == "voice-preview") stop()
        speed = readingSpeed(value)
    }

    fun saveSpeed() {
        preferences.edit().putFloat("speed", speed).apply()
    }
}
