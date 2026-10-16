package com.miui.debughelper.root

import com.miui.debughelper.data.DebugState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessStatusParserTest {
    @Test fun `uses the actual dynamically assigned TLS port`() {
        assertEquals(37291, WirelessStatusParser.parsePort("37291\n"))
        assertEquals(42803, WirelessStatusParser.parsePort("42803"))
    }

    @Test fun `unset error and invalid ports never become connection endpoints`() {
        listOf(null, "", "-1", "0", "65536", "port=37291", "unavailable").forEach {
            assertNull(WirelessStatusParser.parsePort(it))
        }
    }

    @Test fun `stale positive port alone cannot claim wireless debugging is running`() {
        assertFalse(WirelessStatusParser.isRunning(false, 37291, true, true))
        assertFalse(WirelessStatusParser.isRunning(true, 37291, false, true))
        assertFalse(WirelessStatusParser.isRunning(true, 37291, true, false))
        assertFalse(WirelessStatusParser.isRunning(true, null, true, true))
        assertFalse(WirelessStatusParser.isRunning(true, -1, true, true))
        assertTrue(WirelessStatusParser.isRunning(true, 37291, true, true))
    }

    @Test fun `requested native switch can be turned off before TLS startup completes`() {
        val pending = DebugState(requestedEnabled = true, debuggingEnabled = false)
        assertTrue(pending.starting)
        assertTrue(pending.switchEnabled)
        assertNull(pending.activePort)
    }

    @Test fun `running TLS remains enabled while native switch is being turned off`() {
        val stopping = DebugState(requestedEnabled = false, debuggingEnabled = true, activePort = 42803)
        assertTrue(stopping.switchEnabled)
        assertFalse(stopping.starting)
    }
}
