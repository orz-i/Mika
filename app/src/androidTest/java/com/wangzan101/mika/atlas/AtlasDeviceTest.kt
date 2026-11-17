package com.wangzan101.mika.atlas

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.cap.phone.a11y.SecureTestActivity
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.atlas.AppAtlas
import com.wangzan101.mika.core.atlas.AtlasControl
import com.wangzan101.mika.core.atlas.AtlasEdge
import com.wangzan101.mika.core.atlas.AtlasHintPolicy
import com.wangzan101.mika.core.atlas.AtlasLearner
import com.wangzan101.mika.core.atlas.AtlasNavigator
import com.wangzan101.mika.core.atlas.AtlasScreen
import com.wangzan101.mika.core.atlas.AtlasShare
import com.wangzan101.mika.core.atlas.AtlasShortcut
import com.wangzan101.mika.core.atlas.AtlasShortcutRunner
import com.wangzan101.mika.core.atlas.ShortcutCandidate
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.Collections

/**
 * 真机端到端设备测试（在独立虚拟屏 + 隔离临时目录运行，绝不修改主屏状态或用户 `files/atlas`）：
 * 1. 验证真实 AAU `ScreenSignatureExtractor` + `ScreenReader` 正确填充系统设置与同包多页的 `activity`/`screenSig`，并验证受保护页面跳过建图；
 * 2. 验证真实 `AndroidKeyStore` 支持下的 `FileAtlasStore` 分包加密落盘与冷缓存重读；
 * 3. 验证真实 `AtlasLearner -> AtlasHintPolicy -> AtlasNavigator / AtlasShortcutRunner` 经 `ActionExecutor` 在虚拟屏同包多页流程执行且主屏状态不变。
 */
@RunWith(AndroidJUnit4::class)
class AtlasDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var activeAgent: DeviceSupport.Agent? = null
    private val tempDirs = mutableListOf<File>()
    private val testKeyAliases = mutableListOf<String>()

    @After
    fun tearDown() {
        activeAgent?.release()
        activeAgent = null
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
        val ks = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }.getOrNull()
        testKeyAliases.forEach { alias ->
            check(alias != FileAtlasStore.KEY_ALIAS) { "must never delete production atlas key alias" }
            runCatching { ks?.deleteEntry(alias) }
        }
        testKeyAliases.clear()
    }

    private fun newAgent(): DeviceSupport.Agent = DeviceSupport.newAgent().also { activeAgent = it }

    private fun createIsolatedStore(tag: String): Pair<File, String> {
        val dir = File(context.cacheDir, "atlas-dev-$tag-${System.nanoTime()}").apply { mkdirs() }
        val alias = "mika-atlas-devtest-$tag-${System.nanoTime()}"
        tempDirs += dir
        testKeyAliases += alias
        return dir to alias
    }

    private suspend fun snapUntil(
        agent: DeviceSupport.Agent,
        timeoutMs: Long = 6_000L,
        predicate: (ScreenSnapshot) -> Boolean,
    ): ScreenSnapshot {
        val end = System.currentTimeMillis() + timeoutMs
        var s = agent.screen.snapshot()
        while (!predicate(s) && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            s = agent.screen.snapshot()
        }
        assertTrue("screen never matched (${s.packageName}/${s.activity}, elements=${s.elements.map { it.name }})", predicate(s))
        return s
    }

    private suspend fun openSettingsHome(agent: DeviceSupport.Agent): ScreenSnapshot {
        DeviceSupport.sh("am force-stop com.oplus.battery")
        DeviceSupport.sh("am force-stop com.oplus.wirelesssettings")
        DeviceSupport.sh("am force-stop $SETTINGS")
        val openRes = agent.screen.openApp(SETTINGS, cold = true)
        assertTrue("openApp failed: $openRes", openRes is ActionResult.Ok)
        return snapUntil(agent) { s -> s.packageName == SETTINGS && s.elements.isNotEmpty() }
    }

    private fun stableMainFocus(shell: IMikaShell): String =
        DeviceSupport.mainScreenState(shell).replace(Regex("""\bfocusedApp=\S+\s+"""), "")

    private suspend fun openAtlasTestHome(agent: DeviceSupport.Agent): ScreenSnapshot {
        agent.screen.ensureDisplay()
        DeviceSupport.sh(
            "am start -W -f 0x10008000 --display ${agent.display.displayId} " +
                "-n $TEST_PACKAGE/${AtlasHomeTestActivity::class.java.name}",
        )
        snapUntil(agent) { s ->
            s.packageName == TEST_PACKAGE && s.elements.any { it.clickable && it.name == "进入详情页" }
        }
        // ColorOS 启动闪屏退出动画约在首帧后 300-600ms 销毁窗口，等待稳定避免后续读屏/点击间隙窗口列表变动导致 ref_expired
        delay(650L)
        agent.screen.waitStable(2_000L)
        return snapUntil(agent) { s ->
            s.packageName == TEST_PACKAGE && s.elements.any { it.clickable && it.name == "进入详情页" }
        }
    }

    @Test
    fun screenReaderPopulatesActivityAndSignatureOnRealWindows() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val mainBefore = stableMainFocus(shell)
        val agent = newAgent()

        // 1. 系统设置真实窗口：连续两次读屏签名一致且 activity 规范化
        val settingsHome1 = openSettingsHome(agent)
        val settingsSig1 = settingsHome1.resolvedScreenSig()
        assertNotNull("settings screenSig missing", settingsSig1)
        assertFalse(settingsHome1.protected)
        assertTrue("unexpected settings activity=${settingsHome1.activity}", settingsHome1.activity?.startsWith("$SETTINGS/") == true)
        assertFalse(settingsSig1!!.isBlank)
        assertTrue("expected non-empty anchors", settingsSig1.anchors.isNotEmpty())

        val settingsHome2 = agent.screen.snapshot()
        val settingsSig2 = settingsHome2.resolvedScreenSig()
        assertNotNull(settingsSig2)
        assertTrue(
            "same Settings page failed to match (sim=${settingsSig1.similarity(settingsSig2!!)})",
            settingsSig1.matches(settingsSig2),
        )

        // 2. OEM 无关的测试包首页 <-> 详情页：验证同页匹配、跨包不匹配、同包异页不匹配
        val testHome = openAtlasTestHome(agent)
        val testHomeSig = testHome.resolvedScreenSig()
        assertNotNull("AtlasHomeTestActivity screenSig missing", testHomeSig)
        assertTrue("unexpected home activity=${testHome.activity}", testHome.activity?.contains("AtlasHomeTestActivity") == true)
        assertFalse("Settings matched TEST_PACKAGE home", settingsSig1.matches(testHomeSig!!))

        val nextBtn = testHome.elements.first { it.clickable && it.name == "进入详情页" }
        val tapRes = agent.screen.tapRef(nextBtn.ref, testHome.snapshotId)
        assertTrue("tap 进入详情页 failed: $tapRes", tapRes is ActionResult.Ok)
        val testDetail = snapUntil(agent) { s ->
            s.packageName == TEST_PACKAGE && s.elements.any { it.name == "图谱详情" }
        }
        val testDetailSig = testDetail.resolvedScreenSig()
        assertNotNull("AtlasDetailTestActivity screenSig missing", testDetailSig)
        assertTrue("unexpected detail activity=${testDetail.activity}", testDetail.activity?.contains("AtlasDetailTestActivity") == true)
        assertNotEquals(testHomeSig.sigId, testDetailSig!!.sigId)
        assertFalse("AtlasHomeTestActivity unexpectedly matched AtlasDetailTestActivity", testHomeSig.matches(testDetailSig))

        // 3. 受保护窗口（FLAG_SECURE）：受既有 VD AndroidScreen.isProtected() 默认 false 限制，
        // 这里显式校验 ScreenSnapshot(protected=true).resolvedScreenSig() == null 且 AtlasLearner 绝不记录受保护页面/边
        DeviceSupport.startTestActivity(agent.display.displayId, SecureTestActivity::class.java)
        val secure = snapUntil(agent) { s ->
            s.packageName == TEST_PACKAGE && s.elements.any { it.name == "secure page" }
        }
        assertNull("protected snapshot must drop screenSig", secure.copy(protected = true).resolvedScreenSig())
        val (protDir, protAlias) = createIsolatedStore("protected-skip")
        val protStore = FileAtlasStore(protDir, KeystoreKeyCipher(protAlias))
        AtlasLearner.learn(
            events = listOf(
                RunEvent.ActionTarget(
                    atMs = 1_000L,
                    step = 1,
                    tool = "phone_tap",
                    argsJson = """{"element":"secure page"}""",
                    target = Selector("TextView", "secure page", null, listOf(0, 0, 200, 100), 0, false),
                    before = Anchors(TEST_PACKAGE, listOf("secure page")),
                    after = Anchors(TEST_PACKAGE, listOf("secure page")),
                    effective = true,
                    protected = true,
                    beforeActivity = secure.activity,
                    beforeSig = secure.screenSig,
                    afterActivity = secure.activity,
                    afterSig = secure.screenSig,
                ),
            ),
            end = TaskState.Succeeded("ok"),
            outcome = RunOutcome.Done,
            store = protStore,
            selfPackage = ISOLATED_SELF_PKG,
        )
        assertNull("protected ActionTarget must not write to AtlasStore", protStore.get(TEST_PACKAGE))

        assertEquals("main screen changed during VD signature test", mainBefore, stableMainFocus(shell))
    }

    @Test
    fun fileAtlasStoreEncryptsAndRoundTripsWithRealAndroidKeystore() {
        val (dir, alias) = createIsolatedStore("keystore")
        val cipher = KeystoreKeyCipher(alias)
        val writer = FileAtlasStore(dir, cipher) { 1_762_000_000_000L }

        val home = AtlasScreen(
            sigId = "MainActivity#0011223344556677",
            activity = "com.example.shop/com.example.shop.MainActivity",
            shortActivity = "MainActivity",
            fingerprint = 0x0011223344556677L,
            anchors = listOf("首页", "购物车"),
            resourceIds = listOf("tab_home", "tab_cart"),
            title = "商城首页",
            titleSource = AtlasScreen.TITLE_LLM,
            appVersion = "2.1.0",
            hits = 4,
            lastSeenMs = 500L,
            controls = listOf(
                AtlasControl(Selector("TextView", "购物车", "tab_cart", listOf(500, 900, 700, 980), 0, true), "打开购物车", used = 2, lastUsedMs = 500L),
            ),
        )
        val cart = AtlasScreen(
            sigId = "CartActivity#1122334455667788",
            activity = "com.example.shop/com.example.shop.CartActivity",
            shortActivity = "CartActivity",
            fingerprint = 0x1122334455667788L,
            anchors = listOf("购物车", "结算"),
            resourceIds = listOf("btn_checkout"),
            title = "购物车页",
            titleSource = AtlasScreen.TITLE_LLM,
            appVersion = "2.1.0",
            hits = 2,
            lastSeenMs = 500L,
        )
        val step = RouteStep("phone_tap", """{"element":"购物车"}""", home.controls.first().selector, Anchors("com.example.shop", listOf("首页")), Anchors("com.example.shop", listOf("购物车")))
        val atlas = AppAtlas(
            packageName = "com.example.shop",
            appLabel = "示例商城",
            versionsSeen = listOf("2.1.0"),
            screens = listOf(home, cart),
            edges = listOf(AtlasEdge(home.sigId, cart.sigId, "phone_tap", home.controls.first().selector, ok = 2, lastOkMs = 500L, lastAttemptMs = 500L, appVersion = "2.1.0")),
            lastUsedMs = 500L,
            shortcuts = listOf(
                AtlasShortcut(
                    id = "sc-cart",
                    name = "快速进购物车",
                    description = "点击底部购物车标签",
                    slots = 0,
                    entrySigId = home.sigId,
                    steps = listOf(step, step.copy(tool = "phone_back", argsJson = "{}", target = null)),
                    successes = 2,
                    lastUsedMs = 500L,
                ),
            ),
            candidatePatterns = listOf(
                ShortcutCandidate("cand-1", home.sigId, listOf(step, step.copy(tool = "phone_back", argsJson = "{}", target = null)), seenCount = 1, lastSeenMs = 500L),
            ),
        )

        writer.put(atlas)
        val encryptedFile = File(dir, "com.example.shop.json")
        assertTrue(encryptedFile.isFile)
        val diskBytes = encryptedFile.readBytes()
        for (secretText in listOf("商城首页", "快速进购物车")) {
            val needle = secretText.toByteArray(Charsets.UTF_8)
            val leaked = diskBytes.indices.any { i ->
                i + needle.size <= diskBytes.size && needle.indices.all { diskBytes[i + it] == needle[it] }
            }
            assertFalse("plaintext '$secretText' leaked in encrypted atlas file", leaked)
        }

        // 用全新实例（无内存缓存）通过真实 AndroidKeyStore 解密回读
        val coldReader = FileAtlasStore(dir, KeystoreKeyCipher(alias)) { 1_762_000_000_000L }
        assertEquals(atlas, coldReader.get("com.example.shop"))

        val exportedJson = coldReader.exportPackageJson("com.example.shop")!!
        val lint = AtlasShare.decodeAndLint(exportedJson) as AtlasShare.LintResult.Valid
        assertEquals(listOf("sc-cart"), lint.share.atlas.shortcuts.map { it.id })
        assertTrue(lint.share.atlas.candidatePatterns.isEmpty())
    }

    @Test
    fun learnerHintPolicyNavigatorAndShortcutRunnerExecuteOnVirtualDisplay() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val mainBefore = stableMainFocus(shell)
        val agent = newAgent()
        val (dir, alias) = createIsolatedStore("e2e")
        val atlasStore = FileAtlasStore(dir, KeystoreKeyCipher(alias))

        fun newRunnerHarness(runTag: String): Triple<ToolDispatcher, MutableList<RunEvent>, ActionExecutor> {
            val events = Collections.synchronizedList(mutableListOf<RunEvent>())
            val tools = toolDispatcher(
                screen = agent.screen,
                runSpec = RunSpec(RunId(runTag), "conv-$runTag", "查看图谱详情并返回"),
                stableRefs = true,
                inlineScreen = true,
                atlas = atlasStore,
            ).apply {
                captureTargets = true
            }
            val recorder = ActionRecorder(
                object : RunSink {
                    override fun event(e: RunEvent) {
                        events += e
                    }
                },
            )
            val executor = ActionExecutor(
                tools = tools,
                guards = OperationGuards(),
                confirmations = ConfirmationGate(),
                gate = {},
                recorder = recorder,
                selfPackage = ISOLATED_SELF_PKG,
            )
            return Triple(tools, events, executor)
        }

        suspend fun recordHomeDetailRoundTrip(runTag: String): List<RunEvent> {
            val homeReady = agent.screen.snapshot()
            if (homeReady.packageName != TEST_PACKAGE || homeReady.elements.none { it.clickable && it.name == "进入详情页" }) {
                openAtlasTestHome(agent)
            }
            val (tools, events, executor) = newRunnerHarness(runTag)

            val readOut = executor.run(Part.ToolCall("$runTag.r0", "phone_list_elements", "{}"), Origin.Model(1))
            assertTrue("[$runTag] phone_list_elements failed: $readOut", readOut is ActionOutcome.Ran)
            val readContent = (readOut as ActionOutcome.Ran).exec.content
            assertEquals("[$runTag] phone_list_elements not ok: $readContent", true, (readContent["ok"] as? JsonPrimitive)?.booleanOrNull)
            val sid = (readContent["snapshotId"] as JsonPrimitive).content
            val elements = readContent["elements"] as JsonArray
            val nextEl = elements.map { it as JsonObject }.first { el ->
                (el["name"] as? JsonPrimitive)?.contentOrNull == "进入详情页"
            }
            val nextRef = (nextEl["ref"] as JsonPrimitive).content

            val tapOut = executor.run(
                Part.ToolCall("$runTag.tap", "phone_tap", """{"ref":"$nextRef","snapshotId":"$sid"}"""),
                Origin.Model(2),
            )
            assertTrue("[$runTag] phone_tap not Ran: $tapOut", tapOut is ActionOutcome.Ran)
            val tapContent = (tapOut as ActionOutcome.Ran).exec.content
            assertEquals("[$runTag] phone_tap failed: $tapContent", true, (tapContent["ok"] as? JsonPrimitive)?.booleanOrNull)
            snapUntil(agent) { s -> s.packageName == TEST_PACKAGE && s.elements.any { it.name == "图谱详情" } }
            agent.screen.waitStable(1_500L)

            val backOut = executor.run(Part.ToolCall("$runTag.back", "phone_back", "{}"), Origin.Model(3))
            assertTrue("[$runTag] phone_back not Ran: $backOut", backOut is ActionOutcome.Ran)
            val backContent = (backOut as ActionOutcome.Ran).exec.content
            assertEquals("[$runTag] phone_back failed: $backContent", true, (backContent["ok"] as? JsonPrimitive)?.booleanOrNull)
            snapUntil(agent) { s -> s.packageName == TEST_PACKAGE && s.elements.any { it.clickable && it.name == "进入详情页" } }
            agent.screen.waitStable(1_500L)

            AtlasLearner.learn(
                events = events.toList(),
                end = TaskState.Succeeded("已查看图谱详情并返回"),
                outcome = RunOutcome.Done,
                store = atlasStore,
                selfPackage = ISOLATED_SELF_PKG,
                goal = "查看图谱详情并返回",
                tools = tools.index,
            )
            return events.toList()
        }

        // 第 1 轮学习页面、跳转边与候选子流程；第 2 轮共现后晋升为正式 AtlasShortcut
        recordHomeDetailRoundTrip("run1")
        val afterRun1 = atlasStore.get(TEST_PACKAGE)
        assertNotNull("expected learned atlas for $TEST_PACKAGE", afterRun1)
        assertTrue("expected at least 2 screens after run1, got ${afterRun1!!.screens.size}", afterRun1.screens.size >= 2)
        assertTrue("expected positive edge after run1", afterRun1.edges.any { it.ok >= 1 })

        val hintAfter1 = AtlasHintPolicy().hintFor(agent.screen.peek(), atlasStore)
        assertNotNull("AtlasHintPolicy should return hint on learned AtlasHomeTestActivity", hintAfter1)
        assertEquals(TEST_PACKAGE, hintAfter1!!.packageName)
        assertTrue(hintAfter1.text.isNotBlank())

        recordHomeDetailRoundTrip("run2")
        val learned = atlasStore.get(TEST_PACKAGE)!!
        assertTrue("expected shortcut promoted after 2 runs", learned.shortcuts.isNotEmpty())
        val homeScreenId = hintAfter1.screenSigId
        val forwardEdge = learned.outgoingEdges(homeScreenId).first { it.ok > 0 && it.miss <= it.ok }
        val detailScreenId = forwardEdge.toSigId
        val shortcut = learned.activeShortcutsFor(homeScreenId).first()

        // L1 提示在第 2 轮后仍能命中且携带活跃 shortcutId
        val hintAfter2 = AtlasHintPolicy().hintFor(agent.screen.peek(), atlasStore)
        assertNotNull(hintAfter2)
        assertTrue("expected shortcut ${shortcut.id} surfaced in hint: ${hintAfter2!!.text}", shortcut.id in hintAfter2.text)

        // L3 自动寻路：从测试首页 -> 详情页，再自动返回首页（均经 ActionExecutor Origin.Replay）
        val (navTools, navEvents, navExec) = newRunnerHarness("nav")
        val navigator = AtlasNavigator.forExecutor(navTools, navExec)
        val navForward = navigator.navigate(
            atlas = learned,
            targetQuery = detailScreenId,
            store = atlasStore,
            callIdPrefix = "devnav.fwd",
        )
        assertTrue("expected AtlasNavigator Arrived at Detail, got $navForward", navForward is AtlasNavigator.Outcome.Arrived)
        assertEquals(1, (navForward as AtlasNavigator.Outcome.Arrived).hops)
        snapUntil(agent) { s -> s.packageName == TEST_PACKAGE && s.elements.any { it.name == "图谱详情" } }
        agent.screen.waitStable(1_500L)
        assertTrue(navEvents.any { it is RunEvent.ToolResult && it.source == "replay" })

        val navBack = navigator.navigate(
            atlas = atlasStore.get(TEST_PACKAGE)!!,
            targetQuery = homeScreenId,
            store = atlasStore,
            callIdPrefix = "devnav.back",
        )
        assertTrue("expected AtlasNavigator Arrived back at Home, got $navBack", navBack is AtlasNavigator.Outcome.Arrived)
        snapUntil(agent) { s -> s.packageName == TEST_PACKAGE && s.elements.any { it.clickable && it.name == "进入详情页" } }
        agent.screen.waitStable(1_500L)

        // L2 快捷流程执行：经 ActionExecutor Origin.Replay 连跑 [点击 进入详情页 -> 返回]
        val (scTools, scEvents, scExec) = newRunnerHarness("sc")
        val runner = AtlasShortcutRunner.forExecutor(scTools, scExec)
        val scOutcome = runner.run(shortcut = shortcut, atlas = atlasStore.get(TEST_PACKAGE), callIdPrefix = "devsc")
        assertTrue("expected AtlasShortcutRunner Completed, got $scOutcome", scOutcome is AtlasShortcutRunner.Outcome.Completed)
        val completed = scOutcome as AtlasShortcutRunner.Outcome.Completed
        assertEquals(completed.total, completed.done)
        assertTrue(scEvents.any { it is RunEvent.ToolResult && it.source == "replay" })
        snapUntil(agent) { s -> s.packageName == TEST_PACKAGE && s.elements.any { it.clickable && it.name == "进入详情页" } }

        assertEquals("main display state changed during AtlasDeviceTest", mainBefore, stableMainFocus(shell))
    }

    private companion object {
        /** 避免把测试包自身（com.wangzan101.mika.test）当作 Mika 本体而被 AtlasSanitizer 过滤。 */
        const val ISOLATED_SELF_PKG = "com.wangzan101.mika.isolated-test"
    }
}
