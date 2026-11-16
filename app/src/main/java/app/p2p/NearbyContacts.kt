package app.p2p

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal const val NEARBY_WINDOW = 300_000L
private const val SERVICE_TYPE = "_p2pchat._tcp."
private const val TXT_CHUNK = 180
private const val MAX_CHUNKS = 7
private const val MAX_SIGNED_INVITE = 3_072
private val inviteCodePattern = Regex("p2p1:[A-Za-z0-9_-]+")

internal data class OutgoingInvite(val key: String, val code: String)
internal data class NearbyContact(val code: String, val name: String, val addresses: List<InetAddress>, val port: Int)

internal fun nearbyRoute(address: InetAddress, port: Int, device: String): String? {
    if (port !in 1..65535 || address.isAnyLocalAddress || address.isLoopbackAddress ||
        address.isLinkLocalAddress || address.isMulticastAddress) return null
    val host = address.hostAddress?.substringBefore('%') ?: return null
    val family = if (':' in host) "ip6" else "ip4"
    return "/$family/$host/tcp/$port/p2p/$device"
}

internal fun nearbyTxt(code: String): Map<String, String> {
    require(code.length <= MAX_SIGNED_INVITE * 2 && inviteCodePattern.matches(code))
    val signed = decode(code.substringAfter(':'))
    require(signed.size <= MAX_SIGNED_INVITE)
    val compressed = ByteArrayOutputStream()
    GZIPOutputStream(compressed).use { it.write(signed) }
    val parts = encode(compressed.toByteArray()).chunked(TXT_CHUNK)
    require(parts.size <= MAX_CHUNKS)
    return parts.mapIndexed { index, part -> "i$index" to part }.toMap() + ("n" to parts.size.toString())
}

internal fun nearbyCode(attributes: Map<String, ByteArray>): String? {
    val count = attributes["n"]?.toString(Charsets.US_ASCII)?.toIntOrNull()?.takeIf { it in 1..MAX_CHUNKS } ?: return null
    val compressed = buildString {
        repeat(count) { index ->
            val part = attributes["i$index"]?.takeIf {
                it.isNotEmpty() && it.size <= TXT_CHUNK && (index == count - 1 || it.size == TXT_CHUNK)
            } ?: return null
            append(part.toString(Charsets.US_ASCII))
        }
    }
    return runCatching {
        val signed = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(decode(compressed))).use { input ->
            val buffer = ByteArray(512)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(signed.size() + read <= MAX_SIGNED_INVITE)
                signed.write(buffer, 0, read)
            }
        }
        if (signed.size() == 0) null else "p2p1:" + encode(signed.toByteArray())
    }.getOrNull()
}

@Suppress("DEPRECATION")
private class NearbySession(
    context: Context,
    private val offer: OutgoingInvite,
    private val verifyName: (String) -> String?,
    private val update: (List<NearbyContact>) -> Unit,
    private val error: (String) -> Unit
) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val name = "P2P-" + offer.key.substringAfter(':').take(12)
    private var registeredName = name
    private var closed = false
    private val available = linkedMapOf<String, NsdServiceInfo>()
    private val resolved = linkedMapOf<String, NearbyContact>()
    private val pending = linkedSetOf<String>()
    private var resolving = false

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = post {
            registeredName = info.serviceName
            available.remove(registeredName)
            resolved.remove(registeredName)
            update(resolved.values.toList())
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) =
            post { error("This phone couldn’t advertise on the local network.") }
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
    }

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) =
            post { error("Nearby discovery couldn’t start. Check your Wi-Fi connection.") }
        override fun onServiceFound(info: NsdServiceInfo) = post {
            val key = info.serviceName
            if (key != registeredName && key !in available && available.size < 32) {
                available[key] = info
                pending.add(key)
                resolveNext()
            }
        }
        override fun onServiceLost(info: NsdServiceInfo) = post {
            val key = info.serviceName
            available.remove(key)
            pending.remove(key)
            if (resolved.remove(key) != null) update(resolved.values.toList())
        }
    }

    private fun post(action: () -> Unit) {
        main.post { if (!closed) action() }
    }

    fun start() {
        runCatching {
            val service = NsdServiceInfo().apply {
                serviceName = name
                serviceType = SERVICE_TYPE
                port = PORT
                nearbyTxt(offer.code).forEach(::setAttribute)
            }
            manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration)
        }.onFailure { error("This phone couldn’t advertise on the local network.") }
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        }.onFailure { error("Nearby discovery couldn’t start. Check your Wi-Fi connection.") }
    }

    private fun resolveNext() {
        if (resolving) return
        val key = pending.firstOrNull() ?: return
        pending.remove(key)
        val service = available[key] ?: return resolveNext()
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = post { finish() }

            override fun onServiceResolved(info: NsdServiceInfo) = post {
                val code = nearbyCode(info.attributes)
                if (available[key] === service && code != null && code != offer.code) {
                    val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                    val reachable = addresses.filter { nearbyRoute(it, info.port, "") != null }
                    val label = if (reachable.isEmpty()) null else verifyName(code)
                    if (label != null) {
                        resolved[key] = NearbyContact(code, label, reachable, info.port)
                        update(resolved.values.toList())
                    }
                }
                finish()
            }

            private fun finish() {
                resolving = false
                resolveNext()
            }
        }
        runCatching { manager.resolveService(service, listener) }.onFailure {
            resolving = false
            resolveNext()
        }
    }

    fun close() {
        closed = true
        runCatching { manager.stopServiceDiscovery(discovery) }
        runCatching { manager.unregisterService(registration) }
    }
}

@Composable
internal fun NearbyDiscovery(model: P2pModel, content: @Composable (List<NearbyContact>) -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val peer by model.peer.collectAsState()
    var contacts by remember { mutableStateOf<List<NearbyContact>>(emptyList()) }
    var problem by remember { mutableStateOf("") }
    LaunchedEffect(lifecycle, model, peer) {
        if (peer.isBlank()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                problem = ""
                var offer: OutgoingInvite? = null
                var session: NearbySession? = null
                var active = true
                model.createNearbyInvite { created ->
                    if (active) {
                        offer = created
                        session = NearbySession(context, created, model::nearbyInviteName, { contacts = it }, { problem = it })
                            .also(NearbySession::start)
                    } else model.cancelInvite(created.key, keepPending = true)
                }
                try {
                    delay(NEARBY_WINDOW - 15_000L)
                } finally {
                    active = false
                    session?.close()
                    contacts = emptyList()
                    offer?.let { model.cancelInvite(it.key, keepPending = true) }
                }
            }
        }
    }
    Text("Open Nearby devices on both phones while they’re on the same Wi-Fi network.")
    if (problem.isNotEmpty()) Text(problem)
    else if (contacts.isEmpty()) Text(if (peer.isBlank()) "Starting P2P…" else "Looking for nearby devices…")
    content(contacts)
}
