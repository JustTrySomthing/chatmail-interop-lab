package app.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private const val GUARD_URL = "https://relay-webxdc-guard.localhost/index.html"

internal class WebxdcNetworkGuard(private val context: Context) {
    private var holder: WebView? = null
    private val ready = CompletableDeferred<Boolean>()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun start(rendererGone: () -> Unit): Boolean {
        val web = WebView(context.applicationContext)
        holder = web
        web.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                if (request.isForMainFrame && request.method == "GET" && request.url.toString() == GUARD_URL) {
                    WebResourceResponse("text/html", "UTF-8", context.assets.open("webxdc/webrtc_guard.html"))
                } else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(),
                    ByteArrayInputStream(ByteArray(0)))

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val wasReady = !ready.complete(false)
                close()
                if (wasReady) rendererGone()
                return true
            }
        }
        web.addJavascriptInterface(GuardResult(ready), "RelayGuard")
        web.loadUrl(GUARD_URL)
        val confirmed = withTimeoutOrNull(18_000L) { ready.await() } == true
        if (!confirmed) close()
        return confirmed
    }

    fun close() {
        val web = holder ?: return
        holder = null
        web.destroy()
    }

    private class GuardResult(private val ready: CompletableDeferred<Boolean>) {
        @JavascriptInterface
        fun complete(confirmed: Boolean) {
            ready.complete(confirmed)
        }
    }
}
