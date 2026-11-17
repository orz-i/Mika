package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSig
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasLearnerTest {
    private val pkg = "com.example.shop"
    private val homeSig = ScreenSig(
        sigId = "MainActivity#00000000000000ff",
        activity = "com.example.shop/.MainActivity",
        fingerprint = 0x00000000000000ffL,
        anchors = listOf("首页", "购物车", "我的"),
        resourceIds = listOf("tab_home", "tab_cart", "btn_search"),
        packageName = pkg,
        shortActivity = "MainActivity",
    )
    private val searchSig = ScreenSig(
        sigId = "SearchActivity#0f0f0f0f0f0f0f0f",
        activity = "com.example.shop/.SearchActivity",
        fingerprint = 0x0f0f0f0f0f0f0f0fL,
        anchors = listOf("搜索商品", "历史记录"),
        resourceIds = listOf("search_input", "btn_cancel"),
        packageName = pkg,
        shortActivity = "SearchActivity",
    )
    private val searchSel = Selector("button", "搜索", "btn_search", listOf(100, 100, 300, 180), 0, uniqueAtRecord = true)

    private fun action(
        atMs: Long,
        step: Int,
        tool: String,
        argsJson: String,
        target: Selector?,
        beforeSig: ScreenSig?,
        afterSig: ScreenSig?,
        effective: Boolean,
        callId: String = "c$step",
        protected: Boolean = false,
        beforePkg: String? = beforeSig?.packageName,
        afterPkg: String? = afterSig?.packageName,
    ) = RunEvent.ActionTarget(
        atMs = atMs,
        step = step,
        tool = tool,
        argsJson = argsJson,
        target = target,
        before = Anchors(beforePkg, beforeSig?.anchors.orEmpty()),
        after = Anchors(afterPkg, afterSig?.anchors.orEmpty()),
        effective = effective,
        callId = callId,
        protected = protected,
        beforeActivity = beforeSig?.activity,
        beforeSig = beforeSig,
        afterActivity = afterSig?.activity,
        afterSig = afterSig,
    )

    @Test
    fun learnsScreensControlsAndEdgesWhenLearnDone() {
        val store = InMemoryAtlasStore()
        val events = listOf(
            action(1000L, 1, "phone_tap", """{"ref":"@e1"}""", searchSel, homeSig, searchSig, effective = true, callId = "c1"),
            RunEvent.ToolResult(1001L, 1, "phone_tap", """{"ok":true,"changed":true}""", true, null, callId = "c1"),
            RunEvent.TaskEnded(2000L, "succeeded", "done"),
            RunEvent.OutcomeJudged(2001L, "done"),
        )
        val result = AtlasLearner.learn(
            events = events,
            store = store,
            appVersions = mapOf(pkg to "1.0.0"),
            appLabels = mapOf(pkg to "商城"),
            nowMs = 2001L,
        )

        val atlas = store.get(pkg)!!
        assertEquals("商城", atlas.appLabel)
        assertEquals(listOf("1.0.0"), atlas.versionsSeen)
        assertEquals(2, atlas.screens.size)
        assertEquals(1, atlas.edges.size)
        assertEquals(homeSig.sigId, atlas.edges[0].fromSigId)
        assertEquals(searchSig.sigId, atlas.edges[0].toSigId)
        assertEquals(1, atlas.edges[0].ok)
        assertEquals(0, atlas.edges[0].miss)
        assertEquals("1.0.0", atlas.edges[0].appVersion)

        val home = atlas.findScreen(homeSig.sigId)!!
        assertEquals("MainActivity", home.title)
        assertEquals(AtlasScreen.TITLE_ACTIVITY, home.titleSource)
        assertEquals(1, home.hits)
        assertEquals(1, home.controls.size)
        assertEquals(1, home.controls[0].used)
        assertEquals(0, home.controls[0].noEffect)
        assertEquals(setOf(homeSig.sigId, searchSig.sigId), result.unnamedScreenIds[pkg])
    }

    @Test
    fun mergesSimHashSimilarScreensAndKeepsDistinctTabsSeparate() {
        val store = InMemoryAtlasStore()
        // 先学入原始首页
        AtlasLearner.learn(
            events = listOf(
                action(1000L, 1, "phone_tap", "{}", searchSel, homeSig, searchSig, effective = true),
                RunEvent.TaskEnded(1100L, "succeeded", "ok"),
                RunEvent.OutcomeJudged(1101L, "done"),
            ),
            store = store,
        )

        // 同一个 Activity、SimHash 仅翻转 2 位（相似度 0.75*(62/64)+0.25*1.0 > 0.97）应合并到已有 homeSig
        val homeVariant = homeSig.copy(
            sigId = "MainActivity#00000000000000fc",
            fingerprint = homeSig.fingerprint xor 0x3L,
        )
        // 同一个 Activity 的另一个标签页（SimHash 差异大且锚点完全不同）不应合并
        val discoverTab = ScreenSig(
            sigId = "MainActivity#f0f0f0f0f0f0f000",
            activity = "com.example.shop/.MainActivity",
            fingerprint = -0x0f0f0f0f0f0f1000L,
            anchors = listOf("发现", "朋友圈", "视频号"),
            resourceIds = listOf("tab_discover", "feed_entry"),
            packageName = pkg,
            shortActivity = "MainActivity",
        )
        AtlasLearner.learn(
            events = listOf(
                action(2000L, 1, "phone_tap", "{}", searchSel, homeVariant, discoverTab, effective = true),
                RunEvent.TaskEnded(2100L, "succeeded", "ok"),
                RunEvent.OutcomeJudged(2101L, "done"),
            ),
            store = store,
        )

        val atlas = store.get(pkg)!!
        assertEquals(3, atlas.screens.size)
        val mergedHome = atlas.findScreen(homeSig.sigId)!!
        assertEquals(2, mergedHome.hits)
        assertNull(atlas.findScreen(homeVariant.sigId))
        assertNotNull(atlas.findScreen(discoverTab.sigId))
        assertTrue(atlas.edges.any { it.fromSigId == homeSig.sigId && it.toSigId == discoverTab.sigId })
    }

    @Test
    fun tracksVersionUpgradeAndClearsUnverifiedWhenHitAgain() {
        val store = InMemoryAtlasStore()
        AtlasLearner.learn(
            events = listOf(
                action(1000L, 1, "phone_tap", "{}", searchSel, homeSig, searchSig, effective = true),
                RunEvent.TaskEnded(1100L, "succeeded", "ok"),
                RunEvent.OutcomeJudged(1101L, "done"),
            ),
            store = store,
            appVersions = mapOf(pkg to "1.0.0"),
        )
        assertTrue(store.get(pkg)!!.screens.none { it.unverifiedAfterUpgrade })

        // 升级到 2.0.0：只命中了 homeSig，未命中的 searchSig 应标为 unverifiedAfterUpgrade = true
        AtlasLearner.learn(
            events = listOf(
                action(2000L, 1, "phone_tap", "{}", searchSel, homeSig, null, effective = false),
                RunEvent.TaskEnded(2100L, "failed", "err"),
            ),
            store = store,
            appVersions = mapOf(pkg to "2.0.0"),
        )
        val afterUpgrade = store.get(pkg)!!
        assertEquals(listOf("1.0.0", "2.0.0"), afterUpgrade.versionsSeen)
        val homeV2 = afterUpgrade.findScreen(homeSig.sigId)!!
        val searchV1 = afterUpgrade.findScreen(searchSig.sigId)!!
        assertEquals("2.0.0", homeV2.appVersion)
        assertFalse(homeV2.unverifiedAfterUpgrade)
        assertEquals("1.0.0", searchV1.appVersion)
        assertTrue(searchV1.unverifiedAfterUpgrade)

        // 后续在 2.0.0 再次命中 searchSig，其待核实标记清除
        AtlasLearner.learn(
            events = listOf(
                action(3000L, 1, "phone_tap", "{}", searchSel, homeSig, searchSig, effective = true),
                RunEvent.TaskEnded(3100L, "succeeded", "ok"),
                RunEvent.OutcomeJudged(3101L, "done"),
            ),
            store = store,
            appVersions = mapOf(pkg to "2.0.0"),
        )
        val searchV2 = store.get(pkg)!!.findScreen(searchSig.sigId)!!
        assertEquals("2.0.0", searchV2.appVersion)
        assertFalse(searchV2.unverifiedAfterUpgrade)
    }

    @Test
    fun respectsLearnGateAndAccumulatesMissWithoutInflatingOk() {
        val store = InMemoryAtlasStore()
        // 首次未通过 LearnGate（例如 OutcomeJudged = partial，或 unconfirmed 非空）：
        // 即使 effective = true，也不得新增 ok 边或累加 ok
        val partialEvents = listOf(
            action(1000L, 1, "phone_tap", "{}", searchSel, homeSig, searchSig, effective = true, callId = "c1"),
            RunEvent.TaskEnded(1100L, "succeeded", "只完成一半"),
            RunEvent.OutcomeJudged(1101L, "partial"),
        )
        AtlasLearner.learn(partialEvents, store = store)
        assertTrue(store.get(pkg)!!.edges.isEmpty())

        // 真正完成后（learnDone = true），边 ok 记为 1
        AtlasLearner.learn(
            events = listOf(
                action(2000L, 1, "phone_tap", "{}", searchSel, homeSig, searchSig, effective = true, callId = "c1"),
                RunEvent.TaskEnded(2100L, "succeeded", "done"),
                RunEvent.OutcomeJudged(2101L, "done"),
            ),
            store = store,
        )
        assertEquals(1, store.get(pkg)!!.edges.single().ok)

        // 再次运行：有效但最终任务失败（TaskState.Failed + Outcome.Unknown）不得膨胀 ok；无效果动作累计 noEffect 与 edge.miss
        AtlasLearner.learn(
            events = listOf(
                action(3000L, 1, "phone_tap", "{}", searchSel, homeSig, searchSig, effective = true, callId = "c1"),
                action(3100L, 2, "phone_tap", "{}", searchSel, homeSig, null, effective = false, callId = "c2"),
            ),
            end = TaskState.Failed("timeout", FailKind.StepLimit),
            outcome = RunOutcome.Unknown,
            store = store,
        )
        val atlas = store.get(pkg)!!
        val edge = atlas.edges.single()
        assertEquals(1, edge.ok)
        assertEquals(1, edge.miss)
        val ctrl = atlas.findScreen(homeSig.sigId)!!.controls.single()
        assertEquals(4, ctrl.used)
        assertEquals(1, ctrl.noEffect)

        // 若 Succeeded 但存在未确认项或 doubtful，LearnGate.done 也为 false
        AtlasLearner.learn(
            events = listOf(action(4000L, 1, "phone_tap", "{}", searchSel, homeSig, searchSig, effective = true)),
            end = TaskState.Succeeded("ok", unconfirmed = listOf("价格")),
            outcome = RunOutcome.Done,
            store = store,
        )
        assertEquals(1, store.get(pkg)!!.edges.single().ok)
    }

    @Test
    fun skipsProtectedSecretInputsBlankSigsAndSelfPackage() {
        val store = InMemoryAtlasStore()
        val selfSig = homeSig.copy(packageName = AtlasLearner.DEFAULT_SELF_PACKAGE, sigId = "ChatActivity#1234")
        val blankSig = ScreenSig(sigId = "empty", fingerprint = 0L, packageName = pkg)
        val pwdSel = Selector("edit", "请输入密码", "pwd_input", listOf(0, 200, 999, 280), 0)

        val events = listOf(
            // protected 页面：跳过
            action(1000L, 1, "phone_tap", "{}", searchSel, homeSig, searchSig, effective = true, callId = "c1", protected = true),
            // 密码输入（argsJson 已打码 + ToolResult secret=true）：跳过
            action(1100L, 2, "phone_type_text", """{"text":"***"}""", pwdSel, homeSig, searchSig, effective = true, callId = "c2"),
            RunEvent.ToolResult(1101L, 2, "phone_type_text", """{"ok":true,"secret":true}""", true, null, callId = "c2"),
            // 含敏感关键词文本：跳过
            action(1200L, 3, "phone_type_text", """{"text":"验证码 482913"}""", pwdSel, homeSig, searchSig, effective = true, callId = "c3"),
            // Mika 自身包名：跳过
            action(1300L, 4, "phone_tap", "{}", searchSel, selfSig, selfSig, effective = true, callId = "c4"),
            // 空白签名：跳过
            action(1400L, 5, "phone_tap", "{}", searchSel, blankSig, blankSig, effective = true, callId = "c5"),
            RunEvent.TaskEnded(1500L, "succeeded", "ok"),
            RunEvent.OutcomeJudged(1501L, "done"),
        )

        AtlasLearner.learn(events, store = store)
        assertTrue(store.listPackages().isEmpty())
    }

    @Test
    fun legacyRunEventJsonDeserializesSafely() {
        val oldJson = """{"type":"action_target","atMs":100,"step":1,"tool":"phone_tap","argsJson":"{}","target":null,"before":{"packageName":"com.notes","texts":["备忘录"]},"after":{"packageName":"com.notes","texts":[]},"effective":true}"""
        val decoded = RunEvent.decode(oldJson) as RunEvent.ActionTarget
        assertNull(decoded.beforeActivity)
        assertNull(decoded.beforeSig)
        assertNull(decoded.afterActivity)
        assertNull(decoded.afterSig)

        val store = InMemoryAtlasStore()
        AtlasLearner.learn(listOf(decoded, RunEvent.TaskEnded(200L, "succeeded", "ok"), RunEvent.OutcomeJudged(201L, "done")), store = store)
        assertTrue(store.listPackages().isEmpty())
    }

    @Test
    fun actionExecutorRecordsBeforeAndAfterScreenSigsExceptWhenProtected() {
        var step = 0
        val s1 = ScreenSnapshot(
            snapshotId = "snap1",
            packageName = pkg,
            elements = listOf(UiElement("@e1", "button", "搜索", 100, 100, listOf(0, 0, 200, 100), "btn_search", clickable = true, editable = false, scrollable = false)),
            width = 1080,
            height = 2400,
            activity = "com.example.shop/.MainActivity",
            screenSig = homeSig,
        )
        val s2 = ScreenSnapshot(
            snapshotId = "snap2",
            packageName = pkg,
            elements = listOf(UiElement("@e1", "edit", "搜索商品", 100, 100, listOf(0, 0, 800, 100), "search_input", clickable = true, editable = true, scrollable = false)),
            width = 1080,
            height = 2400,
            activity = "com.example.shop/.SearchActivity",
            screenSig = searchSig,
        )
        val inner = FakeScreen().also { it.result = ActionResult.Ok(changed = true) }
        val port = object : ScreenPort by inner {
            override suspend fun snapshot() = if (step == 0) s1 else s2
            override suspend fun peek() = s2
            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                step = 1
                return ActionResult.Ok(changed = true)
            }
        }
        val model = FakeModel(
            ModelReply(listOf(Part.ToolCall("a1", "phone_observe", "{}")), StopReason.ToolUse, null),
            ModelReply(listOf(Part.ToolCall("a2", "phone_tap", """{"ref":"@e1","snapshotId":"snap1"}""")), StopReason.ToolUse, null),
            answerReply("好了"),
        )
        val recorded = mutableListOf<RunEvent>()
        val sink = object : RunSink {
            override fun event(e: RunEvent) { recorded += e }
            override fun media(mediaId: Int, png: ByteArray) {}
        }
        runBlocking {
            AgentLoop(model, toolDispatcher(port), "BASE", sink = sink, confirmations = null, flags = SpeedFlags(replay = true)).run("点搜索")
        }
        val targetEvent = recorded.filterIsInstance<RunEvent.ActionTarget>().single()
        assertEquals("com.example.shop/.MainActivity", targetEvent.beforeActivity)
        assertEquals(homeSig, targetEvent.beforeSig)
        assertEquals("com.example.shop/.SearchActivity", targetEvent.afterActivity)
        assertEquals(searchSig, targetEvent.afterSig)

        // 受保护页面：ActionTarget 不记录 beforeSig / afterSig
        val protPort = object : ScreenPort by inner {
            override suspend fun snapshot() = s1.copy(protected = true)
            override suspend fun peek() = s2.copy(protected = true)
        }
        val protModel = FakeModel(
            ModelReply(listOf(Part.ToolCall("p1", "phone_observe", "{}")), StopReason.ToolUse, null),
            ModelReply(listOf(Part.ToolCall("p2", "phone_tap", """{"ref":"@e1","snapshotId":"snap1"}""")), StopReason.ToolUse, null),
            answerReply("好了"),
        )
        val protRecorded = mutableListOf<RunEvent>()
        runBlocking {
            AgentLoop(
                protModel,
                toolDispatcher(protPort),
                "BASE",
                sink = object : RunSink {
                    override fun event(e: RunEvent) { protRecorded += e }
                    override fun media(mediaId: Int, png: ByteArray) {}
                },
                confirmations = null,
                flags = SpeedFlags(replay = true),
            ).run("点搜索")
        }
        val protTarget = protRecorded.filterIsInstance<RunEvent.ActionTarget>().single()
        assertTrue(protTarget.protected)
        assertNull(protTarget.beforeSig)
        assertNull(protTarget.afterSig)
    }

    @Test
    fun capturesLandingScreenWhenBeforeSigMissingOnSamePackage() {
        val store = InMemoryAtlasStore()
        // Phase 1 复审修正：同包内首个 ActionTarget 缺少 beforeSig 时，仍应捕获落点页 afterSig
        val events = listOf(
            action(
                atMs = 1000L,
                step = 1,
                tool = "phone_tap",
                argsJson = """{"ref":"@e1"}""",
                target = searchSel,
                beforeSig = null,
                afterSig = homeSig,
                effective = true,
                callId = "c1",
                beforePkg = pkg,
                afterPkg = pkg,
            ),
            RunEvent.ToolResult(1001L, 1, "phone_tap", """{"ok":true,"changed":true}""", true, null, callId = "c1"),
            RunEvent.TaskEnded(2000L, "succeeded", "done"),
            RunEvent.OutcomeJudged(2001L, "done"),
        )
        val result = AtlasLearner.learn(
            events = events,
            store = store,
            appLabels = mapOf(pkg to "购物"),
            appVersions = mapOf(pkg to "1.0"),
            nowMs = 3000L,
        )
        assertTrue(pkg in result.updatedByPackage)
        val atlas = store.get(pkg)!!
        val screen = atlas.findScreen(homeSig.sigId)
        assertNotNull(screen)
        assertEquals(1, screen!!.hits)
    }

    @Test
    fun emitsActionTargetsForAtlasLearningWhenReplayIsOff() {
        var step = 0
        val s1 = ScreenSnapshot(
            snapshotId = "snap1",
            packageName = pkg,
            elements = listOf(UiElement("@e1", "button", "搜索", 100, 100, listOf(0, 0, 200, 100), "btn_search", clickable = true, editable = false, scrollable = false)),
            width = 1080,
            height = 2400,
            activity = homeSig.activity,
            screenSig = homeSig,
        )
        val s2 = ScreenSnapshot(
            snapshotId = "snap2",
            packageName = pkg,
            elements = listOf(UiElement("@e1", "edit", "搜索商品", 100, 100, listOf(0, 0, 800, 100), "search_input", clickable = true, editable = true, scrollable = false)),
            width = 1080,
            height = 2400,
            activity = searchSig.activity,
            screenSig = searchSig,
        )
        val inner = FakeScreen().also { it.result = ActionResult.Ok(changed = true) }
        val port = object : ScreenPort by inner {
            override suspend fun snapshot() = if (step == 0) s1 else s2
            override suspend fun peek() = s2
            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                step = 1
                return ActionResult.Ok(changed = true)
            }
        }
        val atlasStore = InMemoryAtlasStore()
        val model = FakeModel(
            ModelReply(listOf(Part.ToolCall("a1", "phone_observe", "{}")), StopReason.ToolUse, null),
            ModelReply(listOf(Part.ToolCall("a2", "phone_tap", """{"ref":"@e1","snapshotId":"snap1"}""")), StopReason.ToolUse, null),
            answerReply("好了"),
        )
        val recorded = mutableListOf<RunEvent>()
        runBlocking {
            AgentLoop(
                model = model,
                tools = toolDispatcher(port, atlas = atlasStore),
                basePrompt = "BASE",
                sink = object : RunSink {
                    override fun event(e: RunEvent) { recorded += e }
                    override fun media(mediaId: Int, png: ByteArray) {}
                },
                confirmations = null,
                flags = SpeedFlags.OFF,
                atlas = atlasStore,
            ).run("点搜索")
        }
        val targetEvent = recorded.filterIsInstance<RunEvent.ActionTarget>().single()
        assertEquals(homeSig, targetEvent.beforeSig)
        assertEquals(searchSig, targetEvent.afterSig)

        // 当 replay == false 且 atlas == null 时，保持原有行为不发 ActionTarget
        step = 0
        val noAtlasModel = FakeModel(
            ModelReply(listOf(Part.ToolCall("b1", "phone_observe", "{}")), StopReason.ToolUse, null),
            ModelReply(listOf(Part.ToolCall("b2", "phone_tap", """{"ref":"@e1","snapshotId":"snap1"}""")), StopReason.ToolUse, null),
            answerReply("好了"),
        )
        val noAtlasRecorded = mutableListOf<RunEvent>()
        runBlocking {
            AgentLoop(
                model = noAtlasModel,
                tools = toolDispatcher(port, atlas = null),
                basePrompt = "BASE",
                sink = object : RunSink {
                    override fun event(e: RunEvent) { noAtlasRecorded += e }
                    override fun media(mediaId: Int, png: ByteArray) {}
                },
                confirmations = null,
                flags = SpeedFlags.OFF,
                atlas = null,
            ).run("点搜索")
        }
        assertTrue(noAtlasRecorded.none { it is RunEvent.ActionTarget })
    }
}
