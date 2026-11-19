package app.p2p

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal data class UpdateVersion(val major: Int, val minor: Int, val patch: Int, val candidate: Int?) : Comparable<UpdateVersion> {
    override fun toString() = "$major.$minor.$patch" + (candidate?.let { "-rc$it" } ?: "")

    override fun compareTo(other: UpdateVersion): Int = compareValuesBy(this, other,
        UpdateVersion::major, UpdateVersion::minor, UpdateVersion::patch, { it.candidate ?: Int.MAX_VALUE })

    companion object {
        private val pattern = Regex("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})(?:-rc([1-9][0-9]{0,5}))?")
        fun parse(value: String): UpdateVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val (major, minor, patch, candidate) = match.destructured
            return UpdateVersion(major.toInt(), minor.toInt(), patch.toInt(), candidate.toIntOrNull())
        }
    }
}

internal data class UpdateRelease(val version: UpdateVersion, val filename: String, val bytes: Long, val digest: String, val preview: Boolean) {
    val url get() = "https://github.com/${UpdateFeed.REPOSITORY}/releases/download/v$version/$filename"
    fun json() = obj("version" to version.toString(), "name" to filename, "bytes" to bytes, "digest" to digest, "preview" to preview)
    fun verifiedMetadata(): Boolean = (filename in UpdateFeed.assetNames(version, listOf("arm64-v8a", "x86_64")) ||
        (preview && filename in UpdateFeed.assetNames(version, listOf("arm64-v8a"), true))) &&
        bytes in 1..UpdateFeed.MAX_APK && digest.matches(Regex("[0-9a-f]{64}"))

    companion object {
        fun read(value: String?): UpdateRelease? = runCatching {
            val json = JSONObject(value ?: return null)
            val version = UpdateVersion.parse(json.getString("version")) ?: return null
            UpdateRelease(version, json.getString("name"), json.getLong("bytes"), json.getString("digest"),
                json.getBoolean("preview")).takeIf(UpdateRelease::verifiedMetadata)
        }.getOrNull()
    }
}

internal object UpdateFeed {
    const val REPOSITORY = "JustTrySomthing/chatmail-interop-lab"
    const val MAX_APK = 256L * 1024 * 1024
    private const val API = "https://api.github.com/repos/$REPOSITORY/releases?per_page=50"
    private const val MAX_FEED = 4 * 1024 * 1024
    private val downloadHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    fun testAssetName(version: UpdateVersion) = "P2P-$version-ARM64-Test.apk"

    fun assetNames(version: UpdateVersion, abis: List<String>, testBuild: Boolean = false): List<String> {
        if (testBuild) return if ("arm64-v8a" in abis) listOf("P2P-$version-ARM64-Preview.apk", testAssetName(version)) else emptyList()
        return abis.mapNotNull { abi ->
            when (abi) {
                "arm64-v8a" -> "P2P-$version-arm64.apk"
                "x86_64" -> "P2P-$version-x86_64.apk"
                else -> null
            }
        } + "P2P-$version.apk"
    }

    fun select(
        rows: JSONArray, installed: UpdateVersion, previews: Boolean, abis: List<String>, testBuild: Boolean = false
    ): UpdateRelease? {
        require(rows.length() <= 50) { "GitHub returned too many releases." }
        var invalidAsset = false
        val best = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val tag = row.optString("tag_name")
            val version = UpdateVersion.parse(tag.removePrefix("v")) ?: return@mapNotNull null
            if (tag != "v$version" || row.optBoolean("draft") || version <= installed) return@mapNotNull null
            val preview = row.optBoolean("prerelease") || version.candidate != null
            if ((preview && !previews) || (testBuild && !preview)) return@mapNotNull null
            val assets = row.optJSONArray("assets") ?: return@mapNotNull null
            val byName = (0 until assets.length()).mapNotNull(assets::optJSONObject)
                .filter { it.optString("state") == "uploaded" }.groupBy { it.optString("name") }
            assetNames(version, abis, testBuild).firstNotNullOfOrNull { name ->
                val matches = byName[name] ?: return@firstNotNullOfOrNull null
                val asset = matches.singleOrNull()
                if (asset == null) {
                    invalidAsset = true
                    return@firstNotNullOfOrNull null
                }
                val digest = asset.optString("digest")
                val release = UpdateRelease(version, name, asset.optLong("size"), digest.removePrefix("sha256:"), preview)
                if (digest.startsWith("sha256:") && release.verifiedMetadata() && asset.optString("browser_download_url") == release.url) {
                    release
                } else {
                    invalidAsset = true
                    null
                }
            }
        }.maxByOrNull(UpdateRelease::version)
        check(best != null || !invalidAsset) { "GitHub listed a newer APK without valid verification details." }
        return best
    }

    fun allowed(uri: URI, asset: Boolean): Boolean = uri.scheme == "https" && uri.rawUserInfo == null &&
        uri.port in setOf(-1, 443) && uri.fragment == null &&
        if (asset) uri.host in downloadHosts else uri.host == "api.github.com"

    suspend fun releases(route: UpdateRoute, manual: Boolean): JSONArray =
        request(route, API, asset = false, manual = manual) { connection ->
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    route.check()
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(out.size() + n <= MAX_FEED) { "The GitHub release list is too large." }
                    out.write(buffer, 0, n)
                }
                JSONArray(out.toString(Charsets.UTF_8.name()))
            }
        }

    suspend fun download(route: UpdateRoute, release: UpdateRelease, destination: File, progress: suspend (Long) -> Unit) =
        withTimeout(10 * 60_000L) {
            check(release.verifiedMetadata())
            request(route, release.url, asset = true, manual = false) { connection ->
                check(connection.contentLengthLong < 0 || connection.contentLengthLong == release.bytes) {
                    "The download size changed. Check for updates again."
                }
                connection.inputStream.use { input ->
                    copy(input, destination, release) { bytes -> route.check(); progress(bytes) }
                }
            }
        }

    private suspend fun <T> request(route: UpdateRoute, url: String, asset: Boolean, manual: Boolean,
                                    read: suspend (HttpURLConnection) -> T): T {
        var next = URI(url)
        repeat(6) {
            currentCoroutineContext().ensureActive()
            route.check()
            check(allowed(next, asset)) { "GitHub returned an unsupported address." }
            val connection = route.network.openConnection(next.toURL()) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", if (asset) "application/octet-stream" else "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "P2P/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (manual) connection.setRequestProperty("Cache-Control", "no-cache")
            try {
                val code = connection.responseCode
                if (code in redirectCodes) {
                    next = next.resolve(connection.getHeaderField("Location") ?: error("GitHub returned an incomplete redirect."))
                } else {
                    when (code) {
                        200 -> return read(connection)
                        401, 404 -> error("The public P2P update feed is unavailable.")
                        403, 429 -> error("GitHub is limiting update checks. Try again later.")
                        else -> error("GitHub couldn’t complete the request. Try again later.")
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        error("GitHub returned too many redirects.")
    }

    internal suspend fun copy(input: InputStream, file: File, release: UpdateRelease, progress: suspend (Long) -> Unit) {
        check(release.verifiedMetadata())
        val checksum = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                bytes += count
                check(bytes <= release.bytes) { "The update is larger than expected." }
                checksum.update(buffer, 0, count)
                output.write(buffer, 0, count)
                progress(bytes)
            }
            output.fd.sync()
        }
        check(bytes == release.bytes && checksum.digest().hex() == release.digest) {
            "The update was incomplete or failed verification. Try downloading again."
        }
    }
}

internal class UpdateRoute(private val context: Context, val network: Network, private val wifiOnly: Boolean) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    fun check() {
        val capabilities = manager.getNetworkCapabilities(network)
        check(capabilities != null && (!wifiOnly || unmeteredWifi(capabilities))) {
            "The network changed. Try again when the connection is ready."
        }
    }

    companion object {
        private fun unmeteredWifi(capabilities: NetworkCapabilities): Boolean =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        fun current(context: Context, requireValidated: Boolean = false, wifiOnly: Boolean = false,
                    jobNetwork: Network? = null): UpdateRoute? {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = jobNetwork ?: manager.activeNetwork ?: return null
            val capabilities = manager.getNetworkCapabilities(network) ?: return null
            if (requireValidated && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return null
            if (wifiOnly && !unmeteredWifi(capabilities)) return null
            return UpdateRoute(context, network, wifiOnly)
        }
    }
}

internal data class ApkIdentity(val packageName: String, val version: String, val code: Long, val signers: Set<String>, val minSdk: Int)

internal object UpdateApk {
    private fun identity(info: PackageInfo): ApkIdentity = ApkIdentity(info.packageName, info.versionName.orEmpty(),
        info.longVersionCode, info.signingInfo?.apkContentsSigners.orEmpty().map { digest(it.toByteArray()) }.toSet(),
        info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE)

    fun validate(installed: ApkIdentity, candidate: ApkIdentity, release: UpdateRelease, sdk: Int) {
        check(candidate.packageName == installed.packageName) { "This APK belongs to a different app." }
        check(candidate.signers.isNotEmpty() && candidate.signers == installed.signers) {
            "This update uses a different signing certificate."
        }
        check(candidate.code > installed.code && UpdateVersion.parse(candidate.version) == release.version &&
            UpdateVersion.parse(installed.version)?.let { release.version > it } == true) {
            "This APK is not a newer matching P2P version."
        }
        check(candidate.minSdk <= sdk) { "This update needs a newer version of Android." }
    }

    @Suppress("DEPRECATION")
    suspend fun verify(context: Context, file: File, release: UpdateRelease) {
        check(release.verifiedMetadata() && file.isFile && file.length() == release.bytes) {
            "Download the update again before installing."
        }
        val checksum = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                checksum.update(buffer, 0, count)
            }
        }
        check(checksum.digest().hex() == release.digest) { "The downloaded update failed verification. Download it again." }
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("Android couldn’t read the downloaded APK.")
        validate(identity(installed), identity(candidate), release, Build.VERSION.SDK_INT)
    }

    @Suppress("DEPRECATION")
    fun installer(context: Context, file: File) = Intent(Intent.ACTION_INSTALL_PACKAGE)
        .setDataAndType(FileProvider.getUriForFile(context, context.packageName + ".updates", file),
            "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
}

class P2pUpdateProvider : FileProvider()
