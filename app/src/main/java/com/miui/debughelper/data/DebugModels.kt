package com.miui.debughelper.data

enum class RootStatus { UNKNOWN, CHECKING, AVAILABLE, DENIED }

data class DebugSettings(
    val autoConnect: Boolean = false,
    val trustedSsids: Set<String> = emptySet(),
)

data class WifiSnapshot(
    val isWifiConnected: Boolean = false,
    val ssid: String? = null,
    val ipv4Address: String? = null,
    val identityAvailable: Boolean = false,
    val locationEnabled: Boolean = true,
    val hasLocationPermission: Boolean = false,
    val hasBackgroundLocationPermission: Boolean = false,
    val isValidated: Boolean = false,
    val isCaptivePortal: Boolean = false,
    val connectionId: Long? = null,
)

data class DebugState(
    val rootStatus: RootStatus = RootStatus.UNKNOWN,
    val debuggingEnabled: Boolean = false,
    /** Native Android wireless-debugging switch; true can precede a running TLS endpoint. */
    val requestedEnabled: Boolean = false,
    val activePort: Int? = null,
    val busy: Boolean = false,
    val currentWifi: WifiSnapshot = WifiSnapshot(),
    val monitoringActive: Boolean = false,
    val lastAction: String? = null,
    val lastActionAt: Long? = null,
    val error: String? = null,
) {
    val starting: Boolean get() = requestedEnabled && !debuggingEnabled
    val switchEnabled: Boolean get() = requestedEnabled || debuggingEnabled
}

/** Deliberately fail closed when Android redacts the network identity. */
object AutoConnectPolicy {
    fun shouldEnable(settings: DebugSettings, wifi: WifiSnapshot): Boolean =
        settings.autoConnect && wifi.isWifiConnected && wifi.identityAvailable &&
            wifi.hasLocationPermission && wifi.hasBackgroundLocationPermission && wifi.locationEnabled &&
            wifi.ipv4Address != null && !wifi.isCaptivePortal &&
            wifi.ssid != null && wifi.ssid in settings.trustedSsids
}
