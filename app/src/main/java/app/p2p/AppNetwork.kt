package app.p2p

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

internal fun advertisedRoutes(addresses: List<String>, device: String, limit: Int): List<String> =
    // Keep the LAN IPv4 route in the invite when the phone has several IPv6 addresses.
    addresses.sortedBy { ':' in it }.flatMap { address ->
        val family = if (':' in address) "ip6" else "ip4"
        val prefix = "/$family/$address"
        listOf("$prefix/udp/$PORT/quic-v1/p2p/$device", "$prefix/tcp/$PORT/p2p/$device")
    }.take(limit)

internal class AppNetwork(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    fun select(): Network? = manager.activeNetwork

    fun observeDefaultNetwork(onChanged: () -> Unit): AutoCloseable {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChanged()
            override fun onLost(network: Network) = onChanged()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = onChanged()
        }
        manager.registerDefaultNetworkCallback(callback)
        return AutoCloseable { manager.unregisterNetworkCallback(callback) }
    }

    fun addresses(network: Network?): List<String> = network?.let(manager::getLinkProperties)
        ?.linkAddresses.orEmpty().map { it.address }
        .filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        .mapNotNull { it.hostAddress?.substringBefore('%') }.distinct().sorted().take(8)
}
