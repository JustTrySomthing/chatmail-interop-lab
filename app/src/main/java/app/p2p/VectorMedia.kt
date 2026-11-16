package app.p2p

import android.graphics.Color
import android.view.MotionEvent
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_SVG_BYTES = 2 * 1024 * 1024
private const val MAX_STICKER_BYTES = 64 * 1024
private const val MAX_STICKER_JSON_BYTES = 1024 * 1024

private fun InputStream.readVectorBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val length = read(buffer)
        if (length < 0) break
        require(length <= limit - output.size()) { "Vector media exceeds the preview limit." }
        output.write(buffer, 0, length)
    }
    return output.toByteArray()
}

internal fun isTgsSticker(mime: String, name: String): Boolean =
    mime.equals("application/x-tgsticker", ignoreCase = true) || name.endsWith(".tgs", ignoreCase = true)

@Composable
internal fun SvgImage(file: File, title: String) {
    var rendererGone by remember(file) { mutableStateOf(false) }
    val content by produceState<Result<String>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = file.inputStream().use { it.readVectorBytes(MAX_SVG_BYTES) }
                require(bytes.isNotEmpty()) { "SVG file is empty." }
                val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                // Keep the SVG in image mode and restrict the document to the embedded data.
                """
                    <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
                    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'">
                    <style>html,body{height:100%;margin:0;}body{display:grid;place-items:center;}img{width:100%;height:100%;object-fit:contain;}</style>
                    </head><body><img src="data:image/svg+xml;base64,$encoded" alt="SVG image"></body></html>
                """.trimIndent()
            }
        }
    }
    val html = content?.getOrNull()
    when {
        rendererGone || content?.isFailure == true -> Text("Couldn’t preview this SVG.")
        html != null -> AndroidView(
            factory = { context ->
                object : WebView(context) {
                    // The enclosing gallery handles swipes and long presses.
                    override fun onTouchEvent(event: MotionEvent): Boolean = false
                }.apply {
                    contentDescription = title
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.apply {
                        javaScriptEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        blockNetworkLoads = true
                        domStorageEnabled = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            rendererGone = true
                            return true
                        }
                    }
                    loadDataWithBaseURL("https://relay-lab.invalid/", html, "text/html", "UTF-8", null)
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.destroy() }
        )
    }
}

internal fun decodeAnimatedSticker(file: File): LottieComposition {
    require(file.length() in 1L..MAX_STICKER_BYTES.toLong())
    val json = file.inputStream().use { input ->
        GZIPInputStream(input).use { it.readVectorBytes(MAX_STICKER_JSON_BYTES) }
    }
    require(json.isNotEmpty())
    val result = LottieCompositionFactory.fromJsonStringSync(json.decodeToString(), null)
    val sticker = requireNotNull(result.value) { "Invalid sticker animation." }
    require(!sticker.hasImages() && sticker.bounds.width() in 1..4096 &&
        sticker.bounds.height() in 1..4096 && sticker.duration in 1f..3100f &&
        sticker.frameRate in 1f..60f) { "Unsupported sticker animation." }
    return sticker
}

@Composable
internal fun AnimatedSticker(file: File, title: String, fullScreen: Boolean = false) {
    val parsed by produceState<Result<LottieComposition>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                decodeAnimatedSticker(file)
            }
        }
    }
    val composition = parsed?.getOrNull()
    when {
        parsed?.isFailure == true -> Text("Couldn’t display this animated sticker.")
        composition != null -> AndroidView(
            factory = { context ->
                LottieAnimationView(context).apply {
                    contentDescription = title
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    repeatCount = LottieDrawable.INFINITE
                    setComposition(composition)
                    playAnimation()
                }
            },
            modifier = if (fullScreen) Modifier.fillMaxSize() else Modifier.size(152.dp),
            onRelease = { it.cancelAnimation() }
        )
    }
}
