package app.p2p

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal val WEBXDC_EVENTS = setOf("WebxdcStatusUpdate", "WebxdcRealtimeData", "WebxdcInstanceDeleted")
private const val BOOTSTRAP_PATH = "/webxdc_bootstrap324567869.html"
private const val RTC_CHECK_PATH = "/sandboxed_iframe_rtcpeerconnection_check_5965668501706.html"
private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024
private const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
private const val MAX_REALTIME_BYTES = 128 * 1024
private val sandboxHeaders = mapOf(
    "Content-Security-Policy" to ("default-src 'self'; " +
        "style-src 'self' 'unsafe-inline' blob:; font-src 'self' data: blob:; " +
        "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; connect-src 'self' data: blob:; " +
        "img-src 'self' data: blob:; media-src 'self' data: blob:; frame-src 'self' data: blob:; " +
        "worker-src blob:; object-src 'none'; base-uri 'none'; form-action 'none'; webrtc 'block';"),
    "X-DNS-Prefetch-Control" to "off",
    "X-Content-Type-Options" to "nosniff",
    "Cache-Control" to "no-store"
)

private val webxdcHeaders = sandboxHeaders + ("Permissions-Policy" to webxdcPermissionsPolicy(false))
private val webxdcMotionHeaders = sandboxHeaders + ("Permissions-Policy" to webxdcPermissionsPolicy(true))

internal data class WebxdcInfo(
    val name: String, val selfAddr: String, val selfName: String, val sender: Boolean,
    val broadcast: Boolean, val interval: Int, val maxSize: Int
) {
    companion object {
        fun from(info: JSONObject) = WebxdcInfo(info.optString("name", "App").take(180),
            info.getString("selfAddr"), info.optString("selfName").ifBlank { "Unnamed" },
            info.optBoolean("isAppSender"), info.optBoolean("isBroadcast"),
            info.optInt("sendUpdateInterval"), info.optInt("sendUpdateMaxSize"))
    }
}

internal fun inspectWebxdc(file: File) {
    require(file.length() in 1..CHATMAIL_FILE_LIMIT) { "This app exceeds the message attachment limit." }
    ZipFile(file).use { zip ->
        require(zip.size() <= 4096 && zip.getEntry("index.html")?.isDirectory == false) { "This Webxdc package is invalid." }
        val names = HashSet<String>()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        for (entry in zip.entries()) {
            check(names.add(entry.name) && entry.size in 0..MAX_ENTRY_BYTES.toLong()) { "Invalid or oversized app asset." }
            if (entry.isDirectory) continue
            zip.getInputStream(entry).use { stream ->
                var bytes = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    bytes += count
                    total += count
                    check(bytes <= entry.size && total <= MAX_ARCHIVE_BYTES) { "The unpacked app is too large." }
                }
                check(bytes == entry.size) { "The app package is incomplete." }
            }
        }
    }
}

internal fun webxdcRequestPath(uri: Uri, host: String): String? {
    if (uri.scheme != "https" || uri.encodedAuthority != host) return null
    val path = uri.path?.takeIf { it.startsWith("/") && it.length <= 1024 } ?: return null
    if (path.any { it == '\\' || it.isISOControl() } || path.split('/').any { it == "." || it == ".." }) return null
    return path
}

@Composable
internal fun WebxdcPage(title: String, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        WebxdcFrame(title, Modifier.fillMaxSize(), close, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebxdcFrame(
    title: String, modifier: Modifier, back: () -> Unit, close: (() -> Unit)? = null,
    switchApps: (() -> Unit)? = null, visible: Boolean = true, content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, color = colors.background, contentColor = colors.onBackground) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (visible) TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    val label = if (close == null) "Close $title" else "Minimize $title"
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, label) }
                },
                actions = {
                    if (switchApps != null) IconButton(onClick = switchApps) { Icon(Icons.Outlined.Dashboard, "Open apps") }
                    if (close != null) IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Close $title") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
            content()
        }
    }
}

internal data class WebxdcSession(val thread: String, val id: Int, val file: File, val href: String, val title: String)
internal const val MAX_OPEN_WEBXDC_APPS = 3

@Composable
internal fun WebxdcRunner(
    model: P2pModel, session: WebxdcSession, visible: Boolean, hasGuard: Boolean?, minimize: () -> Unit,
    switchApps: () -> Unit, close: () -> Unit, sendDraft: (String, AttachmentDraft?) -> Unit
) {
    val (thread, id, file, href, title) = session
    BackHandler(enabled = visible, onBack = minimize)
    var info by remember(id) { mutableStateOf<WebxdcInfo?>(null) }
    var loadError by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id, file) {
        loadError = chatAction {
            withContext(Dispatchers.IO) { inspectWebxdc(file) }
            info = model.openWebxdc(thread, id)
        }
    }
    val bounds = if (visible) Modifier.fillMaxSize() else Modifier.size(0.dp).clearAndSetSemantics {}
    WebxdcFrame(info?.name ?: title, bounds, minimize, close, switchApps, visible) {
        val current = info
        when {
            loadError != null -> Text(loadError.orEmpty(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            current == null || hasGuard == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> SandboxedWebxdc(model, thread, id, current, href, visible, hasGuard, close, sendDraft)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ColumnScope.SandboxedWebxdc(
    model: P2pModel, thread: String, id: Int, info: WebxdcInfo, href: String,
    visible: Boolean, hasGuard: Boolean, close: () -> Unit, sendDraft: (String, AttachmentDraft?) -> Unit
) {
    val context = LocalContext.current
    val closeAction by rememberUpdatedState(close)
    val isVisible by rememberUpdatedState(visible)
    val draftAction by rememberUpdatedState(sendDraft)
    val speech = LocalReadAloud.current
    val speechCount = remember(id) { AtomicInteger() }
    val speechPrefix = "webxdc:$id:"
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val live = remember(id) { AtomicBoolean(true) }
    val joined = remember(id) { AtomicBoolean(false) }
    val host = remember(id, info.selfAddr) {
        val profile = java.security.MessageDigest.getInstance("SHA-256").digest(info.selfAddr.toByteArray())
            .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
        "rl-$profile-msg$id.localhost"
    }
    var view by remember(id) { mutableStateOf<WebView?>(null) }
    var rendererFailed by remember(id) { mutableStateOf(false) }
    var ready by remember(id) { mutableStateOf(false) }
    var startError by remember(id) { mutableStateOf(false) }
    var fileCallback by remember(id) { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val permissions = remember(id, host) { WebxdcPermissions(context, host) }
    val destroyed = remember(id) { AtomicBoolean(false) }
    fun permissionsInForeground(): Boolean = live.get() && !rendererFailed && isVisible &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    val permissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissions.androidResult(permissionsInForeground())
    }
    fun releaseBrowser(web: WebView) {
        if (!destroyed.compareAndSet(false, true)) return
        live.set(false)
        permissions.deny()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        if (!rendererFailed) {
            web.removeJavascriptInterface("InternalJSApi")
            web.stopLoading()
        }
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        view = null
    }
    fun closeSensitiveSession(browser: WebView?) {
        browser?.let(::releaseBrowser)
        closeAction()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            ?.filter { it.scheme == "content" }?.toTypedArray()
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }
    LaunchedEffect(href, ready, view) {
        if (ready && href.startsWith('#') && href.length <= 1_024) {
            val fragment = JSONObject.quote(href)
            view?.evaluateJavascript(
                "(()=>{const app=document.getElementById('frame')?.contentWindow;if(app)app.location.hash=$fragment})()", null
            )
        }
    }
    LaunchedEffect(id) {
        delay(35_000)
        if (!ready) startError = true
    }
    LaunchedEffect(id) {
        model.webxdcEvents.collect { event ->
            if (event.optInt("msgId") != id || !live.get()) return@collect
            when (event.optString("kind")) {
                "WebxdcStatusUpdate" -> view?.evaluateJavascript("document.getElementById('frame')?.contentWindow?.__webxdcUpdate?.()", null)
                "WebxdcRealtimeData" -> {
                    val raw = event.optJSONArray("data") ?: return@collect
                    if (raw.length() <= MAX_REALTIME_BYTES) {
                        val bytes = JSONArray((0 until raw.length()).map { raw.getInt(it) and 255 })
                        view?.evaluateJavascript("document.getElementById('frame')?.contentWindow?.__webxdcRealtimeData?.($bytes)", null)
                    }
                }
                "WebxdcInstanceDeleted" -> closeAction()
            }
        }
    }
    // Status updates are durable; catch up after resume or when rapid events fill the buffer.
    LaunchedEffect(id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (ready && !rendererFailed && !startError) view?.evaluateJavascript("document.getElementById('frame')?.contentWindow?.__webxdcUpdate?.()", null)
                delay(5_000)
            }
        }
    }
    DisposableEffect(view, lifecycle, visible) {
        val browser = view
        val observer = LifecycleEventObserver { _, event ->
            if (live.get() && visible && !rendererFailed) when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    browser?.onResume()
                    permissions.settle(permissionsInForeground())
                }
                Lifecycle.Event.ON_PAUSE -> {
                    if (permissions.authorized && !permissions.androidPromptOpen) closeSensitiveSession(browser)
                    else browser?.onPause()
                }
                Lifecycle.Event.ON_STOP -> {
                    if (permissions.authorized) closeSensitiveSession(browser)
                    else permissions.deny()
                }
                else -> Unit
            }
        }
        if (visible) lifecycle.addObserver(observer)
        else {
            permissions.deny()
            if (permissions.authorized) closeSensitiveSession(browser)
            else {
                browser?.onPause()
                browser?.clearFocus()
            }
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            if (speech?.active?.startsWith(speechPrefix) == true) speech.stop()
        }
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (rendererFailed || startError) {
        Text("The app couldn’t start in the isolated WebView. Close it and try again.",
            Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
    } else AndroidView(
        modifier = Modifier.fillMaxWidth().weight(1f),
        factory = { owner ->
            WebView(owner).also { web ->
                view = web
                web.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                    javaScriptCanOpenWindowsAutomatically = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    setGeolocationEnabled(true)
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
                web.setNetworkAvailable(false)
                web.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame || webxdcRequestPath(request.url, host) == null

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val path = webxdcRequestPath(request.url, host)
                        if (!live.get() || path == null || request.method != "GET" ||
                            request.requestHeaders.keys.any { it.equals("Service-Worker", true) }) return blocked()
                        if (request.isForMainFrame != (path == BOOTSTRAP_PATH)) return blocked()
                        val builtIn = when (path) {
                            BOOTSTRAP_PATH -> "webxdc_wrapper.html"
                            RTC_CHECK_PATH -> "sandboxed_iframe_rtcpeerconnection_check.html"
                            "/webxdc.js" -> "webxdc.js"
                            else -> null
                        }
                        return try {
                            val stream = if (builtIn != null) context.assets.open("webxdc/$builtIn") else {
                                val encoded = runBlocking { model.webxdcBlob(id, path) }
                                check(encoded.length <= MAX_ENTRY_BYTES * 4 / 3 + 4)
                                ByteArrayInputStream(Base64.decode(encoded, Base64.DEFAULT))
                            }
                            val mime = webxdcMime(path)
                            if (path == "/index.html") web.post { ready = true }
                            WebResourceResponse(mime, if (mime.startsWith("text/")) "UTF-8" else null,
                                200, "OK", if (permissions.motionAllowed) webxdcMotionHeaders else webxdcHeaders, stream)
                        } catch (_: Exception) { blocked() }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        rendererFailed = true
                        return true
                    }
                }
                web.webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        permissions.receiveCapture(request, permissionsInForeground())
                    }

                    override fun onPermissionRequestCanceled(request: PermissionRequest) {
                        permissions.captureCanceled(request)
                    }
                    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                        permissions.receiveLocation(origin, callback, permissionsInForeground())
                    }

                    override fun onGeolocationPermissionsHidePrompt() {
                        permissions.locationCanceled()
                    }

                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                                                   params: FileChooserParams): Boolean {
                        if (!isVisible) {
                            callback.onReceiveValue(null)
                            return true
                        }
                        fileCallback?.onReceiveValue(null)
                        fileCallback = callback
                        if (runCatching { picker.launch(params.createIntent()) }.isFailure) {
                            callback.onReceiveValue(null)
                            fileCallback = null
                        }
                        return true
                    }
                }
                val dispatch: (String, AttachmentDraft?) -> Unit = { text, attachment ->
                    web.post {
                        if (live.get() && isVisible) draftAction(text, attachment)
                        else attachment?.uri?.path?.let { File(it).delete() }
                    }
                }
                web.addJavascriptInterface(WebxdcBridge(model, thread, id, info, context.cacheDir,
                    live, joined, dispatch, speak = { text ->
                        web.post {
                            if (live.get() && isVisible) speech?.toggle(speechPrefix + speechCount.incrementAndGet(), text)
                        }
                    }, sensorPermissionState = {
                        if (!live.get()) "denied" else if (permissions.motionAllowed) "granted" else "ask"
                    }, requestSensors = {
                        web.post {
                            if (live.get() && !rendererFailed) {
                                permissions.receiveMotion(permissionsInForeground()) { allowed ->
                                    if (allowed) {
                                        // Reload the trusted wrapper so the whole frame tree gets the new policy.
                                        web.reload()
                                    } else if (live.get() && !rendererFailed) {
                                        web.evaluateJavascript(
                                            "document.getElementById('frame')?.contentWindow?.__webxdcSensorPermission?.(false)",
                                            null
                                        )
                                    }
                                }
                            }
                        }
                    }), "InternalJSApi")
                val destination = href.takeIf { it.startsWith('#') && it.length <= 1_024 }.orEmpty()
                val entry = URLEncoder.encode("https://$host/index.html$destination", Charsets.UTF_8.name())
                val guard = if (hasGuard) 1 else 0
                web.loadUrl("https://$host$BOOTSTRAP_PATH?i=0&h=$guard&href=$entry")
            }
        },
        update = { web ->
            if (!destroyed.get()) web.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        },
        onRelease = ::releaseBrowser
    )
    if (visible && !rendererFailed) permissions.promptCapabilities?.let { capabilities ->
        WebxdcPermissionPrompt(info.name, capabilities,
            approve = { permissions.approve(permissionsInForeground()) { permissionsLauncher.launch(it) } },
            deny = permissions::deny)
    }
    DisposableEffect(id) {
        onDispose {
            live.set(false)
            if (joined.getAndSet(false)) model.leaveWebxdc(id)
            if (speech?.active?.startsWith(speechPrefix) == true) speech.stop()
        }
    }
}

private fun webxdcMime(path: String): String = when (val ext = path.substringAfterLast('.', "").lowercase()) {
    "js", "mjs" -> "text/javascript"
    "wasm" -> "application/wasm"
    "svg" -> "image/svg+xml"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
    webxdcHeaders, ByteArrayInputStream(ByteArray(0)))

private class WebxdcBridge(
    private val model: P2pModel, private val thread: String, private val id: Int, private val info: WebxdcInfo,
    private val cache: File, private val live: AtomicBoolean, private val joined: AtomicBoolean,
    private val draft: (String, AttachmentDraft?) -> Unit,
    private val speak: (String) -> Unit,
    private val sensorPermissionState: () -> String,
    private val requestSensors: () -> Unit
) {
    private var draftPrepared = false

    @JavascriptInterface fun selfAddr() = info.selfAddr
    @JavascriptInterface fun selfName() = info.selfName
    @JavascriptInterface fun isAppSender() = info.sender
    @JavascriptInterface fun isBroadcast() = info.broadcast
    @JavascriptInterface fun sendUpdateInterval() = info.interval
    @JavascriptInterface fun sendUpdateMaxSize() = info.maxSize
    @JavascriptInterface fun getSensorPermissionState() = sensorPermissionState()
    @JavascriptInterface fun requestSensorPermission() {
        if (live.get()) requestSensors()
    }
    @JavascriptInterface fun ttsSpeak(text: String, @Suppress("UNUSED_PARAMETER") language: String) {
        if (live.get() && text.isNotBlank()) speak(text.take(1_000))
    }
    @JavascriptInterface fun log(@Suppress("UNUSED_PARAMETER") text: String) = Unit

    @JavascriptInterface fun sendStatusUpdate(update: String): Boolean {
        if (update.length > info.maxSize || update.toByteArray().size > info.maxSize) return false
        return write { it.webxdcUpdate(id, update) }
    }

    @JavascriptInterface fun getStatusUpdates(after: Int): String =
        if (live.get()) runCatching { runBlocking { model.webxdcUpdates(id, after.coerceAtLeast(0)) } }.getOrDefault("[]") else "[]"

    @JavascriptInterface fun sendRealtimeAdvertisement() {
        if (joined.compareAndSet(false, true) && !write { it.webxdcAdvertise(id) }) joined.set(false)
    }

    @JavascriptInterface fun sendRealtimeData(json: String) {
        if (!joined.get() || json.length > MAX_REALTIME_BYTES * 4) return
        val bytes = runCatching { JSONArray(json) }.getOrNull() ?: return
        if (bytes.length() <= MAX_REALTIME_BYTES) write { it.webxdcSendRealtime(id, bytes) }
    }

    @JavascriptInterface fun leaveRealtimeChannel() {
        if (joined.getAndSet(false)) model.leaveWebxdc(id)
    }

    @JavascriptInterface fun sendToChat(json: String): String? {
        if (!live.get() || draftPrepared || json.length > CHATMAIL_FILE_LIMIT * 2) return "The draft is unavailable or too large."
        return try {
            val message = JSONObject(json)
            val text = message.optString("text")
            require(text.length <= INTEROP_TEXT_LIMIT) { "The draft text is too long." }
            val attachment = if (message.has("base64")) {
                val name = message.optString("name").replace('\\', '/').substringAfterLast('/').trim().take(120)
                require(name.isNotEmpty() && name !in setOf(".", "..") && name.none { it.isISOControl() })
                val bytes = Base64.decode(message.getString("base64"), Base64.DEFAULT)
                require(bytes.size <= CHATMAIL_FILE_LIMIT)
                val folder = File(cache, "captures").apply { mkdirs() }
                val file = File.createTempFile("webxdc-draft-", ".tmp", folder)
                try {
                    file.writeBytes(bytes)
                    AttachmentDraft(Uri.fromFile(file), name, webxdcMime(name), asFile = true, temporary = true)
                } catch (failure: Exception) {
                    file.delete()
                    throw failure
                }
            } else null
            require(text.isNotBlank() || attachment != null)
            draftPrepared = true
            draft(text, attachment)
            null
        } catch (_: Exception) { "The app couldn’t prepare this draft." }
    }

    private fun write(action: (Chatmail) -> Unit): Boolean =
        live.get() && runCatching { runBlocking { model.webxdcWrite(thread, id, action) } }.isSuccess
}
