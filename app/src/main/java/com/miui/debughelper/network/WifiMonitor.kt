package com.miui.debughelper.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.miui.debughelper.data.WifiSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/** Uses the actual Wi-Fi transport, so a VPN's default network is never trusted as Wi-Fi. */
internal class WifiMonitor(context: Context) {
    private val context = context.applicationContext
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = this.context.getSystemService(WifiManager::class.java)
    private val locationManager = this.context.getSystemService(LocationManager::class.java)
    private val capabilities = mutableMapOf<Network, NetworkCapabilities>()
    private val links = mutableMapOf<Network, LinkProperties>()
    private val identities = mutableMapOf<Network, String>()
    private val mutableState = MutableStateFlow(WifiSnapshot())
    val state: StateFlow<WifiSnapshot> = mutableState.asStateFlow()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var generation = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    init {
        ContextCompat.registerReceiver(
            this.context,
            receiver,
            IntentFilter().apply {
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    /** Re-register after a permission change so Android sends unredacted capabilities. */
    @Synchronized
    fun refresh() {
        generation++
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        capabilities.clear()
        links.clear()
        runCatching {
            for (network in connectivity.allNetworks) {
                val caps = connectivity.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) {
                    capabilities[network] = caps
                    connectivity.getLinkProperties(network)?.let { links[network] = it }
                }
            }
        }
        // getNetworkCapabilities() redacts SSID even when a flagged callback had supplied it.
        // Keep that callback identity only for the exact Network that still exists.
        identities.keys.retainAll(capabilities.keys)
        publish()
        val newCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO, generation)
        } else createCallback(0, generation)
        callback = newCallback
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                newCallback,
            )
        }.onFailure { callback = null }
    }

    private fun createCallback(flags: Int, callbackGeneration: Int): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(flags) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(network, caps, callbackGeneration)
                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update(network, properties, callbackGeneration)
                override fun onLost(network: Network) = lost(network, callbackGeneration)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(network, caps, callbackGeneration)
                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = update(network, properties, callbackGeneration)
                override fun onLost(network: Network) = lost(network, callbackGeneration)
            }
        }

    @Synchronized
    private fun update(network: Network, value: NetworkCapabilities, callbackGeneration: Int) {
        if (callbackGeneration != generation) return
        capabilities[network] = value
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (value.transportInfo as? WifiInfo)?.ssid?.let(::normalizeSsid)
        } else null
        if (ssid == null) identities.remove(network) else identities[network] = ssid
        publish()
    }

    @Synchronized
    private fun update(network: Network, value: LinkProperties, callbackGeneration: Int) {
        if (callbackGeneration != generation) return
        links[network] = value
        publish()
    }

    @Synchronized
    private fun lost(network: Network, callbackGeneration: Int) {
        if (callbackGeneration != generation) return
        capabilities.remove(network)
        links.remove(network)
        identities.remove(network)
        publish()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun publish() {
        val locationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager.isLocationEnabled
            else locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val candidates = capabilities.entries.filter {
            it.value.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !it.value.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val active = connectivity.activeNetwork
        val entry = candidates.firstOrNull { it.key == active } ?: candidates.firstOrNull()
        val ssid = if (entry != null && locationGranted && locationEnabled) {
            val transportSsid = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                entry.value.transportInfo as? WifiInfo
            } else null)?.ssid?.let(::normalizeSsid)
            transportSsid ?: identities[entry.key] ?: if (candidates.size == 1) {
                // This deprecated API is still useful for the initial permission-granted read.
                runCatching { wifiManager.connectionInfo.ssid }.getOrNull()?.let(::normalizeSsid)
            } else null
        } else null
        if (!locationGranted || !locationEnabled) identities.clear()
        val address = entry?.let { links[it.key] }?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address?.hostAddress
        mutableState.value = WifiSnapshot(
            isWifiConnected = entry != null,
            ssid = ssid,
            ipv4Address = address,
            identityAvailable = ssid != null,
            hasLocationPermission = locationGranted,
            hasBackgroundLocationPermission = backgroundGranted,
            locationEnabled = locationEnabled,
            isValidated = entry?.value?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            isCaptivePortal = entry?.value?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            connectionId = entry?.key?.networkHandle,
        )
    }

    companion object {
        internal fun normalizeSsid(raw: String): String? {
            if (raw == UNKNOWN_SSID || raw.isBlank()) return null
            val value = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
                raw.substring(1, raw.length - 1)
            } else raw
            return value.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
        }
        private const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
