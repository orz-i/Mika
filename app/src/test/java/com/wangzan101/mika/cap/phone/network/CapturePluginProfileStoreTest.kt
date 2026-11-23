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
import java.io.File

class CapturePluginProfileStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        NetworkCapturePluginRegistry.clearJsonProfileOverrides()
    }

    /**
     * 内置 App 规则已经是随 APK 分发的插件包，不再是编译期对象。测试从 assets 源目录读同一份
     * `plugin.json`，装的、跑的都是真正会发出去的那份配置。
     */
    private fun newStore(dir: File = tempFolder.root): CapturePluginProfileStore =
        CapturePluginProfileStore.forDirectory(dir, builtinAssetRoot()).also {
            // 注册表启动时为空，内置包要先装再加载，snapshot 才有东西可看。
            it.loadIntoRegistry()
        }

    private fun builtinAssetRoot(): File {
        val candidates = listOf(
            File("../aau/aau-common/src/main/assets/aau_capture_plugins"),
            File("aau/aau-common/src/main/assets/aau_capture_plugins"),
            File("../aau-common/src/main/assets/aau_capture_plugins"),
            File("src/main/assets/aau_capture_plugins"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到内置插件包目录: " + candidates.joinToString { it.absolutePath })
    }

    @Test
    fun snapshotSaveAndResetXiaohongshuOffsetsRoundTrip() {
        val store = newStore()
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

        val reloadedStore = newStore()
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

    @Test
    fun draftKeepsTheContainerMagicWhenOnlyAMethodNameChanges() {
        // 界面只编辑「库 + 方法名」，但 BOSS 那条规则还带 magic=BZPBlock。改一个方法名再保存，
        // 魔数不能跟着变成 null —— 否则带容器的返回会被判成误读丢掉。
        val builtin = newStore().snapshotEntries()
            .first { it.packageName == "com.hpbr.bosszhipin" }
        val renamed = CapturePluginDraft.fromEntry(builtin).copy(
            jniRules = "libyzwg.so | nativeDecodeContentV2"
        ).toProfile()

        assertNotNull(renamed)
        val rule = renamed!!.jniHookRules.single()
        assertEquals("libyzwg.so", rule.libSubstring)
        assertEquals(listOf("nativeDecodeContentV2"), rule.methodNames)
        assertEquals("BZPBlock", rule.magic)
        assertEquals(4 * 1024 * 1024, rule.maxBytes)
    }

    @Test
    fun draftWithOnlyDecoderEntriesDoesNotReplaceBuiltinRules() {
        // 只补进程内入口、没碰任何偏移：不能把内置规则整个替换掉，
        // 否则为了拿明文反而把抓包本身（SSL 偏移）拆了。
        val builtin = newStore().snapshotEntries()
            .first { it.packageName == "com.hpbr.bosszhipin" }
        val profile = CapturePluginDraft.fromEntry(builtin).copy(
            quickOffsetsEnabled = false,
            sslWrite = "",
            sslRead = "",
            crCreate = "",
            crHeaders = "",
            crBody = "",
            crFinish = "",
            abiRawHeaders = "",
            abiIoBufferData = "",
            abiStringLayoutMode = "",
            abiVtableSlots = "",
            abiJniSignatures = "",
        ).toProfile()

        assertNotNull(profile)
        assertFalse(profile!!.replaceBuiltinOffsets)
        assertEquals(1, profile.jniHookRules.size)
    }

    @Test
    fun draftRejectsMalformedDecoderEntry() {
        val missingSeparator = CapturePluginDraft(
            isNew = true,
            packageName = "com.demo.app",
            quickOffsetsEnabled = false,
            jniRules = "libyzwg.so nativeDecodeContent",
        ).toProfile()
        assertNull(missingSeparator)

        val missingMethods = CapturePluginDraft(
            isNew = true,
            packageName = "com.demo.app",
            quickOffsetsEnabled = false,
            jniRules = "libyzwg.so |",
        ).toProfile()
        assertNull(missingMethods)

        // 只有注释 = 没有任何入口 = 清空，跟「一个字段都不填」同义，不算格式错误。
        val onlyComment = CapturePluginDraft(
            isNew = true,
            packageName = "com.demo.app",
            quickOffsetsEnabled = false,
            jniRules = "# 这个 App 不需要进程内通道",
        ).toProfile()
        assertNotNull(onlyComment)
        assertTrue(onlyComment!!.jniHookRules.isEmpty())
    }

    @Test
    fun declarativeDecoderSurvivesSaveAndIsRegistered() {
        // L2：不写一行 Kotlin，只靠一段 JSON 就能给新 App 配解码管道。
        val draft = CapturePluginDraft(
            isNew = true,
            packageName = "com.acme.market",
            quickOffsetsEnabled = false,
            decoderJson = """
                {
                  "hostFix": { "headerPriority": ["x-acme-host"], "defaultHost": "api.acme.com" },
                  "responseCodecs": [
                    { "id": "acme", "steps": [{ "op": "base64" }] }
                  ]
                }
            """.trimIndent(),
        )
        val profile = draft.toProfile()
        assertNotNull(profile)
        assertEquals("api.acme.com", profile!!.declarative.hostFix?.defaultHost)
        assertEquals(1, profile.declarative.responseCodecs.size)

        newStore().saveProfile("com.acme.market", profile)

        val reloaded = newStore()
        reloaded.loadIntoRegistry()
        val entry = reloaded.snapshotEntries().first { it.packageName == "com.acme.market" }
        assertEquals(profile.declarative, entry.profile.declarative)

        val middleware = NetworkCapturePluginRegistry.resolveMiddlewares("com.acme.market")
            .firstOrNull { it.id.startsWith("declarative:") }
        assertNotNull("保存后的解码管道必须真的挂到中间件列表里", middleware)
    }

    @Test
    fun draftRejectsBrokenDecoderJson() {
        val broken = CapturePluginDraft(
            isNew = true,
            packageName = "com.acme.market",
            quickOffsetsEnabled = false,
            decoderJson = "{ \"responseCodecs\": [ { \"steps\": [ { \"op\": ",
        ).toProfile()
        assertNull("坏 JSON 必须拒绝保存，不能静默当成空配置", broken)
    }

    @Test
    fun externalPluginPackageShowsUpAsExternal() {
        val pkgDir = File(tempFolder.root, "capture_plugins/acme-ext").apply { mkdirs() }
        File(pkgDir, "plugin.json").writeText(
            """
            {
              "id": "acme.ext",
              "apiVersion": 1,
              "packages": ["com.acme.ext"],
              "responseCodecs": [{ "steps": [{ "op": "base64" }] }]
            }
            """.trimIndent()
        )

        val store = newStore()
        val report = store.loadExternalPlugins()
        assertEquals(1, report.loaded.size)
        val entry = store.snapshotEntries().first { it.packageName == "com.acme.ext" }
        assertTrue(entry.isExternal)
        assertEquals(1, entry.profile.declarative.responseCodecs.size)

        NetworkCapturePluginRegistry.unregister("external.acme.ext")
    }
}
