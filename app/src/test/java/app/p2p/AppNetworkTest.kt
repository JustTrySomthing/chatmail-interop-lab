package app.p2p

import android.app.Application
import android.net.ConnectivityManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppNetworkTest {
    @Test
    fun inviteIncludesIpv4EvenWhenIpv6RoutesWouldFillTheInvite() {
        val ipv6 = listOf("2001:db8::1", "2001:db8::2", "fd00::1")
        val routes = advertisedRoutes(ipv6 + "192.168.1.10", "peer", limit = 6)
        assertEquals(6, routes.size)
        assertEquals(
            listOf("/ip4/192.168.1.10/udp/$PORT/quic-v1/p2p/peer", "/ip4/192.168.1.10/tcp/$PORT/p2p/peer"),
            routes.take(2)
        )
        assertTrue(routes.drop(2).all { it.startsWith("/ip6/") })
        assertEquals(6, advertisedRoutes(ipv6, "peer", limit = 6).size)
    }

    @Test
    fun usesAndroidsActiveNetworkEvenWithALegacyMobileOnlyPreference() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("network", 0).edit().putBoolean("mobileOnly", true).commit()
        val manager = app.getSystemService(ConnectivityManager::class.java)
        assertNotNull(manager.activeNetwork)
        assertEquals(manager.activeNetwork, AppNetwork(app).select())
        assertEquals(manager.activeNetwork, UpdateRoute.current(app)?.network)
    }
}
