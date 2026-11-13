package com.wangzan101.mika.settings

import com.agent.use.common.network.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 端侧网络抓包设置读写与未知值回退。 */
class FeatureFlagsNetworkCaptureTest {
    @Test fun defaultsToEnabledAndAutoMode() {
        val flags = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())
        assertTrue(flags.networkCaptureEnabled())
        assertEquals(CaptureMode.AUTO, flags.networkCaptureMode())
    }

    @Test fun togglesAndPersistsModeWithUnknownFallback() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)

        flags.setNetworkCaptureEnabled(false)
        assertFalse(flags.networkCaptureEnabled())

        flags.setNetworkCaptureMode(CaptureMode.EBPF)
        assertEquals(CaptureMode.EBPF, flags.networkCaptureMode())

        flags.setNetworkCaptureMode(CaptureMode.VPN_SERVICE)
        assertEquals(CaptureMode.VPN_SERVICE, flags.networkCaptureMode())

        prefs.edit().putString("network_capture_mode", "UNKNOWN_MODE").commit()
        assertEquals(CaptureMode.AUTO, flags.networkCaptureMode())
    }
}
