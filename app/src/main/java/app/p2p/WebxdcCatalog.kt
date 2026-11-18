package app.p2p

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal const val WEBXDC_MIME = "application/webxdc+zip"
private const val CATALOG_HOST = "https://apps.testrun.org"
private const val INDEX_LIMIT = 2 * 1024 * 1024
private val catalogCacheLock = Any()
private val catalogFilename = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,220}")

internal data class CatalogApp(
    val id: String, val title: String, val description: String,
    val category: String, val file: String, val icon: String
)

internal fun parseWebxdcCatalog(value: String): List<CatalogApp> {
    val rows = JSONArray(value)
    require(rows.length() <= 2_000) { "The app catalog is too large." }
    return (0 until rows.length()).mapNotNull { i ->
        val row = rows.optJSONObject(i) ?: return@mapNotNull null
        val id = row.optString("app_id")
        val title = row.optString("name").take(120)
        val file = row.optString("cache_relname")
        val icon = row.optString("icon_relname").takeIf(catalogFilename::matches).orEmpty()
        val size = row.optLong("size", -1)
        val category = row.optString("category")
        if (id.isBlank() || title.isBlank() || !catalogFilename.matches(file) || !file.endsWith(".xdc") ||
            size !in 1..NATIVE_ATTACHMENT_FILE_LIMIT || category !in setOf("tool", "game")) null
        else CatalogApp(id, title, row.optString("description").take(8_000), category, file, icon)
    }.distinctBy(CatalogApp::id)
}

internal fun copyCatalogDownload(input: InputStream, output: OutputStream, length: Long, limit: Long) {
    check(length <= limit) { "The download exceeds the size limit." }
    val bytes = input.copyBoundedTo(output, limit, "The download exceeds the size limit.")
    check(length < 0 || bytes == length) { "The download is incomplete. Try again." }
}

private fun downloadCatalogFile(filename: String, limit: Long, output: OutputStream) {
    require(catalogFilename.matches(filename))
    val connection = URL("$CATALOG_HOST/$filename").openConnection() as HttpsURLConnection
    connection.instanceFollowRedirects = false
    connection.connectTimeout = 8_000
    connection.readTimeout = 12_000
    try {
        check(connection.responseCode == 200) { "The app catalog couldn’t be reached." }
        val length = connection.contentLengthLong
        check(length <= limit) { "The download exceeds the size limit." }
        connection.inputStream.use { input -> copyCatalogDownload(input, output, length, limit) }
    } finally {
        connection.disconnect()
    }
}

private fun readCatalogFile(filename: String, limit: Int): ByteArray =
    ByteArrayOutputStream().use { output ->
        downloadCatalogFile(filename, limit.toLong(), output)
        output.toByteArray()
    }

internal data class CatalogIndex(val entries: List<CatalogApp>, val cached: Boolean)

internal fun loadCatalogIndex(file: File, fetch: () -> String): CatalogIndex {
    val cache = AtomicFile(file)
    fun parse(json: String) = parseWebxdcCatalog(json).also {
        check(it.isNotEmpty()) { "The app catalog is empty." }
    }
    try {
        val json = fetch()
        val entries = parse(json)
        synchronized(catalogCacheLock) {
            runCatching {
                val output = cache.startWrite()
                try {
                    output.write(json.toByteArray(Charsets.UTF_8))
                    cache.finishWrite(output)
                } catch (failure: Exception) {
                    cache.failWrite(output)
                    throw failure
                }
            }
        }
        return CatalogIndex(entries, cached = false)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        val saved = synchronized(catalogCacheLock) {
            runCatching {
                cache.openRead().use { input ->
                    ByteArrayOutputStream().use { output ->
                        copyCatalogDownload(input, output, length = -1, limit = INDEX_LIMIT.toLong())
                        parse(output.toString(Charsets.UTF_8.name()))
                    }
                }
            }.getOrNull()
        } ?: throw failure
        return CatalogIndex(saved, cached = true)
    }
}

private suspend fun loadCatalog(context: Context): CatalogIndex = withContext(Dispatchers.IO) {
    loadCatalogIndex(File(context.cacheDir, "webxdc-catalog.json")) {
        readCatalogFile("xdcget-lock.json", INDEX_LIMIT).toString(Charsets.UTF_8)
    }
}

internal fun stageCatalogApp(
    cache: File, app: CatalogApp, download: (String, Long, OutputStream) -> Unit = ::downloadCatalogFile
): AttachmentDraft {
    val folder = File(cache, "captures").apply { mkdirs() }
    val file = File.createTempFile("webxdc-", ".xdc", folder)
    try {
        // The catalog lists the upstream package size. Its served archive can have a different size.
        file.outputStream().use { download(app.file, NATIVE_ATTACHMENT_FILE_LIMIT, it) }
        inspectWebxdc(file)
        val name = app.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").trim().take(90)
        return AttachmentDraft(Uri.fromFile(file), "${name.ifEmpty { "App" }}.xdc", WEBXDC_MIME, asFile = false, temporary = true)
    } catch (failure: Throwable) {
        file.delete()
        throw failure
    }
}

private suspend fun attachCatalogApp(context: Context, app: CatalogApp, attach: (AttachmentDraft) -> Unit) {
    var staged: AttachmentDraft? = null
    try {
        val draft = withContext(Dispatchers.IO) {
            stageCatalogApp(context.cacheDir, app).also { staged = it }
        }
        attach(draft)
        staged = null
    } finally {
        staged?.uri?.path?.let { File(it).delete() }
    }
}

@Composable
internal fun WebxdcCatalog(close: () -> Unit, actionLabel: String = "Add to chat", externalBusy: Boolean = false, attach: (AttachmentDraft) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf("Home") }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var index by remember { mutableStateOf<CatalogIndex?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        try {
            error = chatAction { index = loadCatalog(context) }
        } finally {
            loading = false
        }
    }
    val entries = index?.entries
    val selection = entries?.firstOrNull { it.id == selectedId }
    WebxdcPage(title = "Apps", close = { if (!busy && !externalBusy) close() }) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null || index?.cached == true || downloadError != null) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (index?.cached == true) Text("Showing the saved app catalog.")
                P2pTextButton(enabled = !loading && !busy, onClick = {
                    error = null
                    downloadError = null
                    loading = true
                    attempt++
                }) { Text("Refresh catalog") }
            }
        }
        if (selection == null) {
            Segments(listOf("Home", "Tools", "Games"), tab) { tab = it }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                SearchField(query, { query = it }, label = "Search apps")
            }
            val matches = entries.orEmpty().filter {
                (tab == "Home" || it.category == if (tab == "Games") "game" else "tool") &&
                    (it.title.contains(query.trim(), true) || it.description.contains(query.trim(), true))
            }
            if (entries != null && matches.isEmpty()) EmptyTabMessage("No matching apps")
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(matches, key = CatalogApp::id) { app ->
                    val colors = MaterialTheme.colorScheme
                    Surface(onClick = { downloadError = null; selectedId = app.id },
                        shape = MaterialTheme.shapes.medium, color = colors.surfaceVariant, contentColor = colors.onSurface) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CatalogIcon(app)
                            Column(Modifier.weight(1f)) {
                                Text(app.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(app.description.lineSequence().first(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CatalogIcon(selection)
                        Text(selection.title, style = MaterialTheme.typography.headlineSmall)
                    }
                }
                item { Text(selection.description, style = contentTextStyle(MaterialTheme.typography.bodyLarge)) }
                item {
                    downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (busy || externalBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        P2pOutlinedButton(enabled = !busy && !externalBusy, onClick = {
                            busy = true
                            downloadError = null
                            scope.launch {
                                try {
                                    downloadError = chatAction { attachCatalogApp(context, selection, attach) }
                                } finally { busy = false }
                            }
                        }) { Text(actionLabel) }
                        P2pTextButton(enabled = !busy && !externalBusy, onClick = { selectedId = null }) { Text("Close") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogIcon(app: CatalogApp) {
    val icon by produceState<Bitmap?>(null, app.icon) {
        value = null
        if (app.icon.isNotEmpty()) withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readCatalogFile(app.icon, 512 * 1024)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth in 1..1024 && bounds.outHeight in 1..1024) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 96) })
                } else null
            }.getOrNull()
        }.let { value = it }
    }
    if (icon == null) Avatar(app.title, Icons.Outlined.GridView)
    else Image(checkNotNull(icon).asImageBitmap(), null, Modifier.size(43.dp))
}
