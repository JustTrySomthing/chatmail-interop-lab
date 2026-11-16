package app.onklav.home

// Keep OnKlav's JNI class name so the pinned native library can be reused byte for byte.
internal object NeuralSpeechNative {
    private val loaded by lazy {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("sherpa-onnx-c-api")
        System.loadLibrary("onklav_speech")
    }

    fun load() { loaded }

    external fun initialize(directory: String)
    external fun synthesize(text: String, speaker: Int, speed: Float): FloatArray
    external fun open(): Long
    external fun enqueue(handle: Long, pcm: FloatArray)
    external fun buffered(handle: Long): Long
    external fun cancel(handle: Long)
    external fun close(handle: Long)
    external fun unload()
}
