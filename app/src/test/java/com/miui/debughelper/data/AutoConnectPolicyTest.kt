package com.miui.debughelper.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectPolicyTest {
    private val settings = DebugSettings(autoConnect = true, trustedSsids = setOf("Home Wi-Fi"))
    private val trusted = WifiSnapshot(
        isWifiConnected = true,
        ssid = "Home Wi-Fi",
        ipv4Address = "192.168.1.20",
        identityAvailable = true,
        hasLocationPermission = true,
        hasBackgroundLocationPermission = true,
        locationEnabled = true,
    )

    @Test fun `trusted LAN works without internet validation`() {
        assertTrue(AutoConnectPolicy.shouldEnable(settings, trusted.copy(isValidated = false)))
    }

    @Test fun `SSID match is exact including case and spaces`() {
        assertFalse(AutoConnectPolicy.shouldEnable(settings, trusted.copy(ssid = "home Wi-Fi")))
        assertFalse(AutoConnectPolicy.shouldEnable(settings, trusted.copy(ssid = " Home Wi-Fi")))
        assertFalse(AutoConnectPolicy.shouldEnable(settings, trusted.copy(ssid = "Public Wi-Fi")))
    }

    @Test fun `redacted identity never enables even if previous SSID remains`() {
        assertFalse(AutoConnectPolicy.shouldEnable(settings, trusted.copy(identityAvailable = false)))
        assertFalse(AutoConnectPolicy.shouldEnable(settings, trusted.copy(ssid = null)))
    }

    @Test fun `network must be connected Wi-Fi with an IPv4 address`() {
        assertFalse(AutoConnectPolicy.shouldEnable(settings, trusted.copy(isWifiConnected = false)))
        assertFalse(AutoConnectPolicy.shouldEnable(settings, trusted.copy(ipv4Address = null)))
    }

    @Test fun `captive portal never enables`() {
        assertFalse(AutoConnectPolicy.shouldEnable(settings, trusted.copy(isCaptivePortal = true)))
    }

    @Test fun `revoked permissions and disabled location fail closed`() {
        assertFalse(AutoConnectPolicy.shouldEnable(settings, trusted.copy(hasLocationPermission = false)))
        assertFalse(AutoConnectPolicy.shouldEnable(settings, trusted.copy(hasBackgroundLocationPermission = false)))
        assertFalse(AutoConnectPolicy.shouldEnable(settings, trusted.copy(locationEnabled = false)))
    }

    @Test fun `disabled automation or removed trust never enables`() {
        assertFalse(AutoConnectPolicy.shouldEnable(settings.copy(autoConnect = false), trusted))
        assertFalse(AutoConnectPolicy.shouldEnable(settings.copy(trustedSsids = emptySet()), trusted))
    }
}
