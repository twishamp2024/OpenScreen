package io.github.openscreen.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import javax.net.SocketFactory

/** What the diagnostics panel shows about the phone's Wi-Fi. No SSID (that would need location permission). */
data class WifiStatus(
    val connected: Boolean,
    val ipv4: String? = null,
    val prefixLength: Int? = null,
    val hasInternet: Boolean = false,
)

/**
 * Tracks the Wi-Fi network independently of Android's default network.
 *
 * When Wi-Fi has no Internet (offline router, phone hotspot), Android may make
 * cellular the default and route LAN traffic over it. Binding sockets to
 * this network's [socketFactory] keeps signaling on Wi-Fi.
 */
class WifiNetworkTracker(context: Context, private val onChanged: () -> Unit = {}) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow(WifiStatus(connected = false))
    val status: StateFlow<WifiStatus> = _status.asStateFlow()

    @Volatile
    private var network: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            network = n
            update(n, cm.getLinkProperties(n), cm.getNetworkCapabilities(n))
            onChanged()
        }

        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
            if (n == network) update(n, lp, cm.getNetworkCapabilities(n))
        }

        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            if (n == network) update(n, cm.getLinkProperties(n), caps)
        }

        override fun onLost(n: Network) {
            if (n == network) {
                network = null
                _status.value = WifiStatus(connected = false)
            }
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // LAN-only Wi-Fi is fine
            .build()
        cm.registerNetworkCallback(request, callback)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    fun socketFactory(): SocketFactory? = network?.socketFactory

    private fun update(n: Network, lp: LinkProperties?, caps: NetworkCapabilities?) {
        if (n != network) return
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        _status.value = WifiStatus(
            connected = true,
            ipv4 = v4?.address?.hostAddress,
            prefixLength = v4?.prefixLength,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }
}
