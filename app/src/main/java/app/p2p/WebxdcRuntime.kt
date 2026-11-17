package app.p2p

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
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

internal fun webxdcErrorCategory(message: String): String =
    when {
        message.contains("Content Security Policy", true) || message.contains("Content-Security-Policy", true) -> "Content security policy"
        message.contains("Failed to load resource", true) -> "Resource load failure"
        else -> listOf("SyntaxError", "ReferenceError", "TypeError", "RangeError", "SecurityError", "EvalError", "URIError")
        .firstOrNull { message.contains(it) } ?: "JavaScript error"
    }

internal val WEBXDC_EVENTS = setOf("WebxdcStatusUpdate", "WebxdcRealtimeData", "WebxdcInstanceDeleted")
private const val BOOTSTRAP_PATH = "/webxdc_bootstrap324567869.html"
private const val RTC_CHECK_PATH = "/sandboxed_iframe_rtcpeerconnection_check_5965668501706.html"
private const val MAX_REALTIME_BYTES = 128 * 1024
private val sandboxHeaders = mapOf(
    "Content-Security-Policy" to ("default-src 'self'; " +
        "style-src 'self' 'unsafe-inline' blob:; font-src 'self' data: blob:; " +
        "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; connect-src 'self' data: blob:; " +
        "img-src 'self' data: blob:; media-src 'self' data: blob:; frame-src 'self' data: blob:; " +
        "worker-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'none'; webrtc 'block';"),
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
    require(file.length() in 1..NATIVE_ATTACHMENT_FILE_LIMIT) { "This app exceeds the 1 GiB attachment limit." }
    ZipFile(file).use { zip ->
        require(zip.getEntry("index.html")?.isDirectory == false) { "This Webxdc package is invalid." }
        val names = HashSet<String>()
        for (entry in zip.entries()) {
            require(names.add(entry.name)) { "The app contains duplicate assets." }
        }
    }
}

internal fun openWebxdcAsset(file: File, path: String): InputStream {
    val archive = ZipFile(file)
    try {
        val entry = checkNotNull(archive.getEntry(path.removePrefix("/")))
        check(!entry.isDirectory)
        return object : FilterInputStream(archive.getInputStream(entry)) {
            override fun close() {
                try { super.close() } finally { archive.close() }
            }
        }
    } catch (failure: Throwable) {
        archive.close()
        throw failure
    }
}

internal fun openWebxdcExport(encoded: String): InputStream {
    val ascii = object : InputStream() {
        private var position = 0
        override fun read(): Int =
            if (position < encoded.length) encoded[position++].code else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == encoded.length) return -1
            val count = minOf(length, encoded.length - position)
            repeat(count) { i ->
                val character = encoded[position++].code
                require(character <= 127) { "Invalid file data." }
                buffer[offset + i] = character.toByte()
            }
            return count
        }
    }
    return android.util.Base64InputStream(ascii, Base64.DEFAULT)
}

internal fun webxdcRequestPath(uri: Uri, host: String): String? {
    if (uri.scheme != "https" || uri.encodedAuthority != host) return null
    val path = uri.path?.takeIf { it.startsWith("/") && it.length <= 1024 } ?: return null
    if (path.any { it == '\\' || it.isISOControl() } || path.split('/').any { it == "." || it == ".." }) return null
    return path
}

internal fun webxdcPickedFiles(resultCode: Int, data: Intent?): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK || data == null) return null
    val files = data.clipData?.let { clip ->
        (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }.orEmpty().ifEmpty { listOfNotNull(data.data) }
    return files.filter { it.scheme == "content" }.distinct().takeIf { it.isNotEmpty() }?.toTypedArray()
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
    switchApps: (() -> Unit)? = null, visible: Boolean = true, pin: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
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
                    if (pin != null) Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "App options") }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Add to home screen") }, onClick = {
                                menuOpen = false
                                pin()
                            })
                        }
                    }
                    if (close != null) IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Close $title") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface)
            )
            content()
        }
    }
}

internal data class WebxdcSession(val thread: String, val id: Int, val file: File, val href: String, val title: String) {
    fun matches(record: Record?, local: File?): Boolean =
        record?.webxdcMessageId(thread) == id && local == file
}
internal const val MAX_OPEN_WEBXDC_APPS = 3

internal fun Record.webxdcMessageId(thread: String): Int? {
    val data = data()
    if (data.optString("thread") != thread || !data.optBoolean("webxdc") ||
        data.optBoolean("locallyDeleted")) return null
    return data.optInt("nativeMessage").takeIf { it > 9 }
}

internal fun List<Record>.webxdcMessages(thread: String?): List<Record> =
    if (thread == null) emptyList() else filter { it.webxdcMessageId(thread) != null }

internal data class WebxdcShortcut(val profile: String, val thread: String, val id: Int) {
    fun intent(context: Context) = Intent(ACTION).setClass(context, MainActivity::class.java)
        .putExtra(EXTRA_PROFILE, profile).putExtra("thread", thread).putExtra("message", id)

    fun pin(context: Context, title: String): Boolean {
        val shortcut = ShortcutInfoCompat.Builder(context, "webxdc-$profile-$id")
            .setShortLabel(title.ifBlank { "Chat app" }.take(40))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_p2p))
            .setIntent(intent(context))
            .build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }.getOrDefault(false)
    }

    companion object {
        private const val ACTION = "app.p2p.OPEN_WEBXDC"

        fun read(intent: Intent?): WebxdcShortcut? {
            if (intent?.action != ACTION) return null
            val profile = intent.getStringExtra(EXTRA_PROFILE)?.takeIf { it == PRIMARY_PROFILE || isId(it) } ?: return null
            val thread = intent.getStringExtra("thread")?.takeIf { isId(it) || isPeer(it) } ?: return null
            val id = intent.getIntExtra("message", 0).takeIf { it > 9 } ?: return null
            return WebxdcShortcut(profile, thread, id)
        }
    }
}

@Composable
internal fun WebxdcRunner(
    model: P2pModel, session: WebxdcSession, visible: Boolean, hasGuard: Boolean?, minimize: () -> Unit,
    switchApps: () -> Unit, close: () -> Unit, sendDraft: (String, AttachmentDraft?) -> Unit
) {
    val (thread, id, file, href, title) = session
    BackHandler(enabled = visible, onBack = minimize)
    val context = LocalContext.current
    var info by remember(id) { mutableStateOf<WebxdcInfo?>(null) }
    var loadError by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id, file) {
        loadError = chatAction {
            withContext(Dispatchers.IO) { inspectWebxdc(file) }
            info = model.openWebxdc(thread, id)
        }
    }
    val bounds = if (visible) Modifier.fillMaxSize() else Modifier.size(0.dp).clearAndSetSemantics {}
    val pin = info?.let { app ->
        {
            if (!WebxdcShortcut(model.profileId, thread, id).pin(context, app.name)) {
                android.widget.Toast.makeText(context, "This launcher couldn’t add the shortcut.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    WebxdcFrame(info?.name ?: title, bounds, minimize, close, switchApps, visible, pin) {
        val current = info
        when {
            loadError != null -> Text(loadError.orEmpty(), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            current == null || hasGuard == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> SandboxedWebxdc(model, thread, id, file, current, href, visible, hasGuard, close, sendDraft)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ColumnScope.SandboxedWebxdc(
    model: P2pModel, thread: String, id: Int, file: File, info: WebxdcInfo, href: String,
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
        val address = if (model.profileId == PRIMARY_PROFILE) info.selfAddr else "${model.profileId}:${info.selfAddr}"
        val profile = java.security.MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
            .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
        "rl-$profile-msg$id.localhost"
    }
    var view by remember(id) { mutableStateOf<WebView?>(null) }
    val diagnostics = remember(id) { mutableStateListOf<String>() }
    fun note(code: String) {
        if (diagnostics.size < 20 && code !in diagnostics) {
            diagnostics.add(code)
            AppLog.record(context, "Webxdc: $code")
        }
    }
    var rendererFailed by remember(id) { mutableStateOf(false) }
    var ready by remember(id) { mutableStateOf(false) }
    var startupAttempt by remember(id) { mutableIntStateOf(0) }
    val entryServed = remember(id) { AtomicBoolean(false) }
    var startError by remember(id) { mutableStateOf(false) }
    var fileCallback by remember(id) { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var filePickerPending by remember(id) { mutableStateOf(false) }
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
        filePickerPending = false
        val callback = fileCallback
        fileCallback = null
        val selected = if (live.get() && isVisible && !rendererFailed) {
            webxdcPickedFiles(result.resultCode, result.data)
        } else null
        callback?.onReceiveValue(selected)
    }
    LaunchedEffect(href, ready, view) {
        if (ready && href.startsWith('#') && href.length <= 1_024) {
            val fragment = JSONObject.quote(href)
            view?.evaluateJavascript(
                "(()=>{const app=document.getElementById('frame')?.contentWindow;if(app)app.location.hash=$fragment})()", null
            )
        }
    }
    LaunchedEffect(id, startupAttempt) {
        delay(35_000)
        if (!ready) {
            note("Startup timeout; entry served=${entryServed.get()}")
            startError = true
        }
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
        Text("The app couldn’t start. Copy app diagnostics below, then close and reopen it.",
            Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
    } else {
        if (!ready && visible) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(
        modifier = Modifier.fillMaxWidth().weight(1f),
        factory = { owner ->
            WebView(owner).also { web ->
                view = web
                web.post { note("WebView ${WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"}; guard=$hasGuard") }
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
                                openWebxdcAsset(file, path)
                            }
                            val mime = webxdcMime(path)
                            if (path == "/index.html") {
                                entryServed.set(true)
                                web.post { note("App entry served") }
                            }
                            if (path == "/webxdc.js") web.post { note("Webxdc API served") }
                            WebResourceResponse(mime, if (mime.startsWith("text/")) "UTF-8" else null,
                                200, "OK", if (permissions.motionAllowed) webxdcMotionHeaders else webxdcHeaders, stream)
                        } catch (_: Exception) {
                            val kind = if (path == "/index.html") "entry" else "asset"
                            web.post { note("Missing or unreadable app $kind (${if (path == "/favicon.ico") "favicon" else webxdcMime(path)})") }
                            blocked()
                        }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        note("Renderer stopped; crashed=${detail.didCrash()}")
                        rendererFailed = true
                        return true
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        note("HTTP ${response.statusCode}; main=${request.isForMainFrame}")
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        note("WebView load error ${error.errorCode}; main=${request.isForMainFrame}")
                        if (!ready && (request.isForMainFrame || webxdcRequestPath(request.url, host) == "/index.html")) {
                            startError = true
                        }
                    }
                }
                web.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            note("${webxdcErrorCategory(message.message())}; line ${message.lineNumber()}")
                        }
                        return true
                    }
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
                        if (!permissionsInForeground() || filePickerPending) {
                            callback.onReceiveValue(null)
                            return true
                        }
                        filePickerPending = true
                        fileCallback = callback
                        if (runCatching { picker.launch(params.createIntent()) }.isFailure) {
                            filePickerPending = false
                            fileCallback = null
                            callback.onReceiveValue(null)
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
                    live, joined, dispatch, diagnostic = { message -> web.post { note(message) } }, startup = { ok ->
                        web.post {
                            if (live.get() && !ready) {
                                note("Frame load callback=$ok; entry served=${entryServed.get()}")
                                ready = ok && entryServed.get()
                                startError = !ready
                            }
                        }
                    }, speak = { text ->
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
                                        entryServed.set(false)
                                        ready = false
                                        startupAttempt++
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
                web.loadUrl("https://$host$BOOTSTRAP_PATH?h=$guard&href=$entry")
            }
        },
        update = { web ->
            if (!destroyed.get()) {
                web.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                // A session can become visible without an Activity lifecycle transition.
                if (visible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    web.onResume()
                    web.invalidate()
                }
            }
        },
        onRelease = ::releaseBrowser
        )
    }
    if (visible) {
        TextButton(onClick = {
            val browser = view
            fun copyReport(page: String, notify: Boolean = true) {
            val summary = buildString {
                appendLine("Relay Lab ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Android ${android.os.Build.VERSION.SDK_INT}; Webxdc diagnostics")
                appendLine("Frame loaded=$ready; startup error=$startError; renderer failed=$rendererFailed")
                appendLine("View size=${browser?.width ?: 0}x${browser?.height ?: 0}; visible=${browser?.visibility}")
                appendLine("Attached=${browser?.isAttachedToWindow}; window visibility=${browser?.windowVisibility}")
                appendLine("Page state: $page")
                diagnostics.forEach { appendLine(it) }
            }
            context.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("App diagnostics", summary))
            if (notify) android.widget.Toast.makeText(context, "App diagnostics copied", android.widget.Toast.LENGTH_SHORT).show()
            }
            copyReport("JavaScript snapshot pending", false)
            if (browser == null || rendererFailed) copyReport("unavailable")
            else browser.evaluateJavascript("""(()=>{try {
                const f=document.getElementById('frame'); const w=f?.contentWindow; const d=w?.document;
                const b=d?.body; const r=f?.getBoundingClientRect();
                return JSON.stringify({ready:d?.readyState,api:!!w?.webxdc,
                    bodyVisibility:b?w.getComputedStyle(b).visibility:null,
                    bodyDisplay:b?w.getComputedStyle(b).display:null,
                    frameWidth:r?.width,frameHeight:r?.height,scripts:d?.scripts.length});
            } catch(e) {return 'unavailable';}})()""") { result -> copyReport(result ?: "unavailable") }
        }) { Text("Copy app diagnostics") }
    }
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
    "html", "htm" -> "text/html"
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
    private val diagnostic: (String) -> Unit,
    private val startup: (Boolean) -> Unit,
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
    @JavascriptInterface fun startupFinished(ok: Boolean) = startup(ok)
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

    @JavascriptInterface fun getStatusUpdates(after: Int): String {
        if (!live.get()) return "[]"
        diagnostic("Status updates requested")
        return runCatching {
            runBlocking { withTimeout(8_000) { model.webxdcUpdates(id, after.coerceAtLeast(0)) } }
        }.onSuccess { diagnostic("Status updates returned") }
            .onFailure { diagnostic("Status updates unavailable (${it.javaClass.simpleName})") }
            .getOrDefault("[]")
    }

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
        if (!live.get() || draftPrepared) return "The draft is unavailable."
        return try {
            val message = JSONObject(json)
            val text = message.optString("text")
            require(text.length <= INTEROP_TEXT_LIMIT) { "The draft text is too long." }
            val attachment = if (message.has("base64")) {
                val name = message.optString("name").replace('\\', '/').substringAfterLast('/').trim().take(120)
                require(name.isNotEmpty() && name !in setOf(".", "..") && name.none { it.isISOControl() })
                val folder = File(cache, "captures").apply { mkdirs() }
                val file = File.createTempFile("webxdc-draft-", ".tmp", folder)
                try {
                    openWebxdcExport(message.getString("base64")).use { input ->
                        file.outputStream().buffered().use { output ->
                            input.copyBoundedTo(output, NATIVE_ATTACHMENT_FILE_LIMIT, "This attachment is too large.")
                        }
                    }
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
