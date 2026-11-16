package app.p2p

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.RenderProcessGoneDetail
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal data class PreviewLink(val url: String, val host: String, val videoId: String?)

private val webLink = Regex("""(?i)https?://[^\s<>"“”]+""")
private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

internal fun previewLinks(text: String): List<PreviewLink> = webLink.findAll(text)
    .map { it.value.trimEnd('.', ',', '!', '?', ')', ']', ';', ':') }
    .mapNotNull(::parsePreviewLink).distinctBy { it.url }.take(3).toList()

internal fun parsePreviewLink(value: String): PreviewLink? = runCatching {
    if (value.length > 2_048) return null
    val url = URI(value)
    val host = url.host?.lowercase()?.trimEnd('.') ?: return null
    if (url.scheme?.lowercase() !in listOf("http", "https") || url.userInfo != null || url.port !in -1..65535) return null
    if ('.' !in host || host.endsWith(".local") || host.endsWith(".localhost") ||
        host.all { it.isDigit() || it == '.' } || ':' in host) return null
    val pieces = url.path.orEmpty().split('/').filter { it.isNotBlank() }
    val id = when {
        host == "youtu.be" -> pieces.firstOrNull()
        host in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com") ->
            if (pieces.firstOrNull() in listOf("shorts", "live", "embed")) pieces.getOrNull(1)
            else if (pieces.firstOrNull() == "watch") url.rawQuery.orEmpty().split('&')
                .firstOrNull { it.startsWith("v=") }?.substringAfter('=')
            else null
        else -> null
    }?.takeIf(videoIdPattern::matches)
    PreviewLink(url.toASCIIString(), host, id)
}.getOrNull()

internal fun inAppBrowserUrl(value: String, upgradeHttp: Boolean = false): String? {
    val link = parsePreviewLink(value) ?: return null
    val url = URI(link.url)
    if (url.scheme.equals("https", ignoreCase = true)) return link.url
    if (!upgradeHttp) return null
    val authority = if (url.port == 80) url.host else url.rawAuthority
    val suffix = link.url.substring(url.scheme.length + "://".length + url.rawAuthority.length)
    return "https://$authority$suffix"
}

@Composable
internal fun LinkedMessageText(
    text: String, open: (PreviewLink) -> Unit,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    background: Color = MaterialTheme.colorScheme.surface
) {
    val accent = readableAccent(background, LocalContentColor.current)
    val openLink by rememberUpdatedState(open)
    val annotated = remember(text, accent) {
        buildAnnotatedString {
            append(text)
            for (match in webLink.findAll(text)) {
                val raw = match.value.trimEnd('.', ',', '!', '?', ')', ']', ';', ':')
                val link = parsePreviewLink(raw) ?: continue
                addLink(LinkAnnotation.Clickable(
                    tag = link.url,
                    styles = TextLinkStyles(style = SpanStyle(color = accent, textDecoration = TextDecoration.Underline)),
                    linkInteractionListener = { openLink(link) }
                ), match.range.first, match.range.first + raw.length)
            }
        }
    }
    Text(annotated, style = contentTextStyle(style))
}

@Composable
internal fun MessageBodyWithPreviews(
    body: String, preview: (PreviewLink) -> Unit, open: (PreviewLink) -> Unit,
    background: Color = MaterialTheme.colorScheme.surface
) {
    val links = remember(body) { previewLinks(body) }
    val caption = remember(body, links) {
        val rendered = links.mapTo(mutableSetOf(), PreviewLink::url)
        webLink.replace(body) { match ->
            val raw = match.value.trimEnd('.', ',', '!', '?', ')', ']', ';', ':')
            if (parsePreviewLink(raw)?.url?.let(rendered::contains) == true) "" else match.value
        }.trim()
    }
    if (caption.isNotBlank()) LinkedMessageText(caption, open, background = background)
    links.forEach { link -> MessageLinkCard(link, { preview(link) }, { open(link) }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageLinkCard(link: PreviewLink, preview: () -> Unit, open: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (link.videoId != null) YoutubeThumbnail(link.videoId, Modifier.fillMaxWidth().height(105.dp), preview)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (link.videoId == null) Icons.Outlined.Language else Icons.Outlined.PlayCircle, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (link.videoId == null) link.host else "YouTube video", style = MaterialTheme.typography.titleSmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (link.videoId != null) TextButton(onClick = preview) { Text("Play in P2P") }
                TextButton(onClick = open) { Text("Open in P2P browser") }
            }
        }
    }
}

@Composable
private fun YoutubeThumbnail(id: String, modifier: Modifier, open: () -> Unit) {
    var requested by rememberSaveable(id) { mutableStateOf(false) }
    if (!requested) {
        TextButton(onClick = { requested = true }) { Text("Load YouTube thumbnail") }
        return
    }
    val download = LocalMediaDownload.current
    val result by produceState<Result<Bitmap>?>(null, id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = fetchYoutubeThumbnail(id)
                checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
        }
    }
    val image = result?.getOrNull()
    when {
        image != null -> Image(image.asImageBitmap(), "YouTube thumbnail", modifier.combinedClickable(onClick = open,
            onLongClickLabel = "Download image",
            onLongClick = download?.let { save -> { save(MediaDownload("YouTube-$id.jpg", "image/jpeg", thumbnail = id)) } }),
            contentScale = ContentScale.Fit)
        result != null -> Text("YouTube thumbnail unavailable")
        else -> LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

internal fun fetchYoutubeThumbnail(id: String): ByteArray {
    require(videoIdPattern.matches(id))
    val connection = URL("https://i.ytimg.com/vi/$id/hqdefault.jpg").openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 6_000
        connection.readTimeout = 6_000
        connection.instanceFollowRedirects = false
        check(connection.responseCode == 200) { "Couldn’t load this image." }
        return connection.inputStream.use { stream ->
            val limit = 512 * 1024
            val buffer = ByteArray(8192)
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                check(output.size() + count <= limit) { "This image could not be saved." }
                output.write(buffer, 0, count)
            }
            check(output.size() > 0) { "This image could not be saved." }
            output.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun InAppBrowser(link: PreviewLink, close: () -> Unit) {
    val destination = remember(link.url) { inAppBrowserUrl(link.url, upgradeHttp = true) } ?: return
    val context = LocalContext.current
    Dialog(onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false,
            dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        var browser by remember(link.url) { mutableStateOf<WebView?>(null) }
        var address by remember(link.url) { mutableStateOf(destination) }
        var failed by remember(link.url) { mutableStateOf(false) }
        var insecureRedirect by remember(link.url) { mutableStateOf<String?>(null) }
        var noExternalBrowser by remember(link.url) { mutableStateOf(false) }
        fun back() {
            val view = browser
            if (view?.canGoBack() == true) view.goBack() else close()
        }
        BackHandler { back() }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Browser back") }
                    Text(address, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Close browser") }
                }
                if (failed) {
                    val fallback = insecureRedirect ?: link.url.takeIf { inAppBrowserUrl(it) == null }
                    Column(Modifier.padding(16.dp)) {
                        Text(if (fallback != null) "A secure connection to this page couldn’t be opened."
                            else "This page couldn’t load.")
                        if (fallback != null) TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback))
                                    .addCategory(Intent.CATEGORY_BROWSABLE))
                            } catch (_: ActivityNotFoundException) {
                                noExternalBrowser = true
                            }
                        }) { Text("Open HTTP link in external browser") }
                        if (noExternalBrowser) Text("No browser is available to open this link.")
                    }
                }
                else AndroidView(factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.domStorageEnabled = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.setSupportMultipleWindows(false)
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                if (inAppBrowserUrl(url) != null) return false
                                if (request.isForMainFrame && request.url.scheme.equals("http", ignoreCase = true)) {
                                    insecureRedirect = parsePreviewLink(url)?.url
                                    failed = true
                                }
                                return true
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                address = url
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) failed = true
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                failed = true
                                return true
                            }
                        }
                        browser = this
                        loadUrl(destination)
                    }
                }, modifier = Modifier.fillMaxWidth().weight(1f), onRelease = { view ->
                    browser = null
                    if (!failed) view.stopLoading()
                    view.destroy()
                })
            }
        }
    }
}

@Composable
internal fun LinkPreviewContent(link: PreviewLink, open: () -> Unit) {
    link.videoId?.let { YoutubePlayer(it) }
    Button(onClick = open) { Text("Open in P2P browser") }
}

internal fun youtubeEmbedUrl(id: String): String? = id.takeIf(videoIdPattern::matches)
    ?.let { "https://www.youtube-nocookie.com/embed/$it?playsinline=1&fs=0" }

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YoutubePlayer(id: String) {
    val destination = youtubeEmbedUrl(id) ?: return
    val state by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (!state.isAtLeast(Lifecycle.State.STARTED)) return
    var failed by remember(id) { mutableStateOf(false) }
    if (failed) {
        Text("YouTube couldn’t load this video.")
        return
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame || request.url.scheme != "https" ||
                            request.url.host != "www.youtube-nocookie.com" ||
                            request.url.path != "/embed/$id"

                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        failed = true
                        return true
                    }
                }
                val html = """
                    <!doctype html>
                    <html><head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <meta name="referrer" content="strict-origin-when-cross-origin">
                    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; frame-src https://www.youtube-nocookie.com">
                    <style>html,body,iframe{width:100%;height:100%;margin:0;border:0;background:black;display:block}</style>
                    </head><body><iframe src="$destination" title="YouTube video" allow="encrypted-media"></iframe></body></html>
                """.trimIndent()
                loadDataWithBaseURL("https://${context.packageName}/", html, "text/html", "UTF-8", null)
            }
        },
        modifier = Modifier.fillMaxWidth().height(246.dp),
        onRelease = { view -> view.destroy() }
    )
}
