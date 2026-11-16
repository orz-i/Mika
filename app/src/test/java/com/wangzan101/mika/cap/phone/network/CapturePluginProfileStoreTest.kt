package com.wangzan101.mika.cap.phone.network

import com.agent.use.common.network.plugin.EbpfCronetAbiRule
import com.agent.use.common.network.plugin.EbpfCronetOffsetRule
import com.agent.use.common.network.plugin.EbpfProbeProfile
import com.agent.use.common.network.plugin.EbpfSslOffsetRule
import com.agent.use.common.network.plugin.NetworkCapturePluginRegistry
import com.wangzan101.mika.ui.CapturePluginDraft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CapturePluginProfileStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        NetworkCapturePluginRegistry.clearJsonProfileOverrides()
    }

    @Test
    fun snapshotSaveAndResetXiaohongshuOffsetsRoundTrip() {
        val store = CapturePluginProfileStore(tempFolder.root)
        val initial = store.snapshotEntries()
        val xhs = initial.firstOrNull { it.packageName == "com.xingin.xhs" }
        assertNotNull(xhs)
        assertTrue(xhs!!.isBuiltin)
        assertFalse(xhs.isOverridden)
        assertEquals("libcronet.114.0.5735.38.so", xhs.profile.cronetOffsetRules.first().libSubstring)
        assertEquals(0x4ea720L, xhs.profile.sslOffsetRules.first().writeOffset)

        val updatedProfile = EbpfProbeProfile(
            sslOffsetRules = listOf(EbpfSslOffsetRule("libcronet.120.0.0.1.so", 0x11110L, 0x22220L, 0x33330L)),
            cronetOffsetRules = listOf(EbpfCronetOffsetRule("libcronet.120.0.0.1.so", 0x44440L, 0x55550L, 0x66660L, 0x77770L)),
            cronetAbiRule = EbpfCronetAbiRule(rawHeadersOffset = 0x28, ioBufferDataOffset = 0x18, stringLayoutMode = 1),
            replaceBuiltinOffsets = true,
        )
        store.saveProfile("com.xingin.xhs", updatedProfile)
        assertTrue(store.overrideFile!!.isFile)

        val reloadedStore = CapturePluginProfileStore(tempFolder.root)
        reloadedStore.loadIntoRegistry()
        val afterSave = reloadedStore.snapshotEntries().first { it.packageName == "com.xingin.xhs" }
        assertTrue(afterSave.isOverridden)
        assertEquals("libcronet.120.0.0.1.so", afterSave.profile.cronetOffsetRules.first().libSubstring)

        val cli = NetworkCapturePluginRegistry.resolveCliArgs("com.xingin.xhs")
        assertTrue(cli.contains("libcronet.120.0.0.1.so:0x11110:0x22220:0x33330"))
        assertFalse(cli.any { it.contains("114.0.5735.38") })

        reloadedStore.resetPackage("com.xingin.xhs")
        assertFalse(store.overrideFile!!.exists())
        val afterReset = reloadedStore.snapshotEntries().first { it.packageName == "com.xingin.xhs" }
        assertFalse(afterReset.isOverridden)
        assertEquals("libcronet.114.0.5735.38.so", afterReset.profile.cronetOffsetRules.first().libSubstring)
    }

    @Test
    fun draftValidationSupportsHexDisabledOffsetsAndRejectsMalformedInput() {
        val valid = CapturePluginDraft(
            isNew = false,
            isBuiltinEntry = true,
            packageName = "com.xingin.xhs",
            quickOffsetsEnabled = true,
            libSubstring = "libcronet.121.0.0.0.so",
            sslWrite = "0x1000",
            sslRead = "0x2000",
            sslSni = "",
            crCreate = "0x3000",
            crHeaders = "0x4000",
            crBody = "0x5000",
            crFinish = "0x6000",
        ).toProfile()
        assertNotNull(valid)
        assertTrue(valid!!.replaceBuiltinOffsets)
        assertEquals(0L, valid.sslOffsetRules.first().sniOffset)

        val disabledQuickOffsets = CapturePluginDraft(
            isNew = false,
            isBuiltinEntry = true,
            packageName = "com.xingin.xhs",
            quickOffsetsEnabled = false,
            libSubstring = "libcronet.114.0.5735.38.so",
            sslWrite = "0x4ea720",
        ).toProfile()
        assertNotNull(disabledQuickOffsets)
        assertTrue(disabledQuickOffsets!!.sslOffsetRules.isEmpty())
        assertTrue(disabledQuickOffsets.cronetOffsetRules.isEmpty())
        assertTrue(disabledQuickOffsets.replaceBuiltinOffsets)

        val invalidHex = CapturePluginDraft(
            isNew = true,
            packageName = "com.demo.app",
            quickOffsetsEnabled = true,
            libSubstring = "libcronet.so",
            sslWrite = "not_hex",
            sslRead = "0x100",
        ).toProfile()
        assertNull(invalidHex)
    }
}
