package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.agent.TokenEstimate
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.PromptCaching
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.agent.use.ui.Selector
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.agent.use.ui.ScreenSig
import com.agent.use.ui.ScreenSnapshot
import com.agent.use.ui.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

class AtlasHintPolicyTest {
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { it.shutdown() }
    }

    private val pkg = "com.example.settings"
    private val homeSig = ScreenSig(
        sigId = "MainSettingsActivity#1111000011110000",
        activity = "$pkg/.MainSettingsActivity",
        fingerprint = 0x1111000011110000L,
        anchors = listOf("设置", "通用", "显示与亮度", "实验室"),
        resourceIds = listOf("entry_general", "entry_display", "entry_lab"),
        packageName = pkg,
        shortActivity = "MainSettingsActivity",
    )
    private val labSig = ScreenSig(
        sigId = "LabActivity#2222000022220000",
        activity = "$pkg/.LabActivity",
        fingerprint = 0x2222000022220000L,
        anchors = listOf("实验功能", "隐私实验", "开启省流量模式"),
        resourceIds = listOf("switch_privacy", "switch_data_saver"),
        packageName = pkg,
        shortActivity = "LabActivity",
    )
    private val labDoneSig = ScreenSig(
        sigId = "LabActivity#3333000033330000",
        activity = "$pkg/.LabActivity",
        fingerprint = 0x3333000033330000L,
        anchors = listOf("实验功能", "省流量模式已开启"),
        resourceIds = listOf("status_data_saver"),
        packageName = pkg,
        shortActivity = "LabActivity",
    )

    private fun snapshotFor(sig: ScreenSig, id: String = "s1", elements: List<UiElement> = emptyList(), protected: Boolean = false) =
        ScreenSnapshot(
            snapshotId = id,
            packageName = sig.packageName,
            elements = elements,
            width = 1080,
            height = 2400,
            protected = protected,
            activity = sig.activity,
            screenSig = sig,
        )

    @Test
    fun filtersUnreliableEdgesRespectsBudgetSanitizesAndDeduplicates() {
        val store = InMemoryAtlasStore()
        val reliableEdge = AtlasEdge(
            fromSigId = homeSig.sigId,
            toSigId = labSig.sigId,
            tool = "phone_tap",
            target = Selector("button", "实验室", "com.example.settings:id/entry_lab", listOf(0, 400, 1000, 500), 0),
            ok = 5,
            miss = 1,
            lastOkMs = 5000L,
        )
        val zeroOkEdge = AtlasEdge(
            fromSigId = homeSig.sigId,
            toSigId = labDoneSig.sigId,
            tool = "phone_tap",
            target = Selector("button", "未知入口", "btn_zero", emptyList(), 0),
            ok = 0,
            miss = 2,
            lastOkMs = 9000L,
        )
        val highMissEdge = AtlasEdge(
            fromSigId = homeSig.sigId,
            toSigId = labDoneSig.sigId,
            tool = "phone_tap",
            target = Selector("button", "旧入口", "btn_old", emptyList(), 0),
            ok = 2,
            miss = 3,
            lastOkMs = 9000L,
        )
        val extraReliableEdge = AtlasEdge(
            fromSigId = homeSig.sigId,
            toSigId = labDoneSig.sigId,
            tool = "phone_tap",
            target = Selector("button", "验证码 123456 入口", "btn_second", emptyList(), 1),
            ok = 2,
            miss = 0,
            lastOkMs = 3000L,
        )
        val atlas = AppAtlas(
            packageName = pkg,
            appLabel = "设置",
            screens = listOf(
                AtlasScreen(
                    sigId = homeSig.sigId,
                    activity = homeSig.activity,
                    shortActivity = homeSig.shortActivity,
                    fingerprint = homeSig.fingerprint,
                    anchors = homeSig.anchors,
                    resourceIds = homeSig.resourceIds,
                    title = "设置主页",
                    titleSource = AtlasScreen.TITLE_LLM,
                    hits = 6,
                    unverifiedAfterUpgrade = true,
                    controls = listOf(
                        AtlasControl(reliableEdge.target!!, "进入实验室功能页", used = 5, lastUsedMs = 5000L),
                    ),
                ),
                AtlasScreen(
                    sigId = labSig.sigId,
                    activity = labSig.activity,
                    shortActivity = labSig.shortActivity,
                    fingerprint = labSig.fingerprint,
                    anchors = labSig.anchors,
                    resourceIds = labSig.resourceIds,
                    title = "实验功能",
                    titleSource = AtlasScreen.TITLE_LLM,
                    hits = 4,
                    controls = listOf(
                        AtlasControl(Selector("button", "隐私实验", "switch_privacy", emptyList(), 0), "打开隐私实验", used = 1, noEffect = 2),
                        AtlasControl(Selector("button", "开启省流量模式", "switch_data_saver", emptyList(), 1), "开启省流量开关", used = 4, noEffect = 0),
                    ),
                ),
                AtlasScreen(
                    sigId = labDoneSig.sigId,
                    activity = labDoneSig.activity,
                    shortActivity = labDoneSig.shortActivity,
                    fingerprint = labDoneSig.fingerprint,
                    title = "省流量已开启",
                    titleSource = AtlasScreen.TITLE_USER,
                    hits = 2,
                ),
            ),
            edges = listOf(zeroOkEdge, highMissEdge, reliableEdge, extraReliableEdge),
        )
        store.put(atlas)

        val policy = AtlasHintPolicy()
        val seen = linkedSetOf<String>()

        // 1. protected 或空白快照不注入
        assertNull(policy.hintFor(snapshotFor(homeSig, protected = true), store, seen))
        assertNull(policy.hintFor(ScreenSnapshot("blank", null, emptyList(), 1080, 2400), store, seen))
        assertTrue(seen.isEmpty())

        // 2. SimHash 轻微漂移（翻转 1 位）仍能命中主页，过滤不可靠边，保留升级标记与脱敏输出
        val fuzzyHomeSig = homeSig.copy(sigId = "MainSettingsActivity#fuzzy", fingerprint = homeSig.fingerprint xor 1L)
        val firstHint = policy.hintFor(snapshotFor(fuzzyHomeSig), store, seen)
        assertNotNull(firstHint)
        val text = firstHint!!.text
        assertTrue(text.startsWith(AtlasHintPolicy.HEADER))
        assertTrue(text.contains("[unverified after update]"))
        assertTrue(text.contains("'实验室'"))
        assertTrue(text.contains("实验功能"))
        assertFalse(text.contains("未知入口"))
        assertFalse(text.contains("旧入口"))
        assertFalse(text.contains("123456"))
        assertTrue(text.lines().size <= AtlasHintPolicy.DEFAULT_MAX_LINES)
        assertTrue(text.length <= AtlasHintPolicy.DEFAULT_MAX_CHARS)
        assertTrue(firstHint.unverifiedAfterUpgrade)

        // 3. 同一页第二次不再重复注入
        assertNull(policy.hintFor(snapshotFor(homeSig), store, seen))

        // 4. 无出边但有高可靠控件时，回退展示控件含义，并排除不可靠控件
        val labHint = policy.hintFor(snapshotFor(labSig), store, seen)
        assertNotNull(labHint)
        assertTrue(labHint!!.text.contains("开启省流量模式"))
        assertTrue(labHint.text.contains("开启省流量开关"))
        assertFalse(labHint.text.contains("隐私实验"))

        // 5. L2 快捷流程命中当前 entrySigId 时应在 L1 提示中简明展示（忽略已停用/敏感名称，遵守 3 行与 350 字符上限）
        val step = com.wangzan101.mika.core.replay.RouteStep(
            tool = "phone_tap",
            argsJson = "{}",
            target = Selector("button", "实验室", "entry_lab", emptyList(), 0),
            before = com.wangzan101.mika.core.replay.Anchors(pkg, listOf("设置")),
            after = com.wangzan101.mika.core.replay.Anchors(pkg, listOf("实验功能")),
        )
        val atlasWithShortcuts = atlas.copy(
            shortcuts = listOf(
                AtlasShortcut("sc_disabled", "停用快捷", "", 0, homeSig.sigId, listOf(step, step), disabled = true, successes = 99),
                AtlasShortcut("sc_secret", "验证码 123456 快捷", "", 0, homeSig.sigId, listOf(step, step), successes = 50),
                AtlasShortcut("sc_lab_saver", "一键开省流量", "", 1, homeSig.sigId, listOf(step, step), successes = 6),
            ),
        )
        store.put(atlasWithShortcuts)
        val scSeen = linkedSetOf<String>()
        val scHint = policy.hintFor(snapshotFor(homeSig), store, scSeen)
        assertNotNull(scHint)
        val scText = scHint!!.text
        assertTrue("应包含匹配入口的快捷流程 id 与槽位数: $scText", scText.contains("sc_lab_saver") && scText.contains("slots:1") && scText.contains("steps:2"))
        assertFalse(scText.contains("sc_disabled"))
        assertFalse(scText.contains("123456"))
        assertTrue(scText.lines().size <= AtlasHintPolicy.DEFAULT_MAX_LINES)
        assertTrue(scText.length <= AtlasHintPolicy.DEFAULT_MAX_CHARS)
    }

    @Test
    fun phase2AtlasHintsReduceStepsTokensAndPreservePrefixStability() {
        val atlasStore = InMemoryAtlasStore()
        val labEntrySel = Selector("button", "实验室", "com.example.settings:id/entry_lab", listOf(0, 700, 1080, 820), 2)
        val dataSaverSel = Selector("button", "开启省流量模式", "com.example.settings:id/switch_data_saver", listOf(0, 500, 1080, 620), 1)
        atlasStore.put(
            AppAtlas(
                packageName = pkg,
                appLabel = "设置",
                versionsSeen = listOf("2.0"),
                screens = listOf(
                    AtlasScreen(
                        sigId = homeSig.sigId,
                        activity = homeSig.activity,
                        shortActivity = homeSig.shortActivity,
                        fingerprint = homeSig.fingerprint,
                        anchors = homeSig.anchors,
                        resourceIds = homeSig.resourceIds,
                        title = "设置主页",
                        titleSource = AtlasScreen.TITLE_LLM,
                        hits = 5,
                        controls = listOf(AtlasControl(labEntrySel, "进入实验功能页", used = 5)),
                    ),
                    AtlasScreen(
                        sigId = labSig.sigId,
                        activity = labSig.activity,
                        shortActivity = labSig.shortActivity,
                        fingerprint = labSig.fingerprint,
                        anchors = labSig.anchors,
                        resourceIds = labSig.resourceIds,
                        title = "实验功能",
                        titleSource = AtlasScreen.TITLE_LLM,
                        hits = 4,
                        controls = listOf(AtlasControl(dataSaverSel, "开启省流量模式", used = 4)),
                    ),
                    AtlasScreen(
                        sigId = labDoneSig.sigId,
                        activity = labDoneSig.activity,
                        shortActivity = labDoneSig.shortActivity,
                        fingerprint = labDoneSig.fingerprint,
                        anchors = labDoneSig.anchors,
                        resourceIds = labDoneSig.resourceIds,
                        title = "省流量模式已开启",
                        titleSource = AtlasScreen.TITLE_LLM,
                        hits = 3,
                    ),
                ),
                edges = listOf(
                    AtlasEdge(homeSig.sigId, labSig.sigId, "phone_tap", labEntrySel, ok = 5, miss = 0, appVersion = "2.0"),
                    AtlasEdge(labSig.sigId, labDoneSig.sigId, "phone_tap", dataSaverSel, ok = 4, miss = 0, appVersion = "2.0"),
                ),
            ),
        )

        val baseline = runAbScenario(store = null)
        val withAtlas = runAbScenario(store = atlasStore)

        assertTrue("baseline 应成功: ${baseline.outcome}; actions=${baseline.screenActions}", baseline.outcome is TaskState.Succeeded)
        assertTrue("atlas 应成功: ${withAtlas.outcome}; actions=${withAtlas.screenActions}", withAtlas.outcome is TaskState.Succeeded)

        // A/B 指标：L1 Atlas 提示大幅减少 LLM 轮数、手机动作次数与累计 Token 消耗
        assertTrue(
            "Atlas 应减少 LLM 往返轮数: atlas=${withAtlas.requests.size}, baseline=${baseline.requests.size}",
            withAtlas.requests.size < baseline.requests.size,
        )
        assertTrue("Atlas 应控制在 <= 6 轮内: ${withAtlas.requests.size}", withAtlas.requests.size <= 6)
        assertTrue("Baseline 试错应显著多于 Atlas: ${baseline.requests.size}", baseline.requests.size >= 10)
        assertTrue(
            "Atlas 应减少手机点击/返回次数: atlas=${withAtlas.screenActions.size}, baseline=${baseline.screenActions.size}",
            withAtlas.screenActions.size < baseline.screenActions.size,
        )
        assertTrue(
            "Atlas 应减少累计请求 Token: atlas=${withAtlas.totalPromptTokens}, baseline=${baseline.totalPromptTokens}",
            withAtlas.totalPromptTokens < baseline.totalPromptTokens,
        )

        // L1 Hint 事件验证：仅为主页与实验页各自注入 1 次，不重复注入
        val atlasHintEvents = withAtlas.events.filterIsInstance<RunEvent.State>().filter { it.name == "atlas_hint" }
        assertEquals(listOf("$pkg/${homeSig.sigId}", "$pkg/${labSig.sigId}"), atlasHintEvents.map { it.detail })
        assertTrue(baseline.events.none { it is RunEvent.State && it.name == "atlas_hint" })

        // Zone 0 与 Zone 1/2 前缀缓存稳定性校验：
        // 1. Zone 0（system + tools）仅在首次读屏展开手机工具组时追加扩展，绝不改写已有前缀，且与 baseline 完全一致（不含任何 atlas_hint）
        val sysTexts = withAtlas.requests.map {
            (it["messages"]!!.jsonArray.first().jsonObject["content"] as JsonPrimitive).content
        }
        val baselineSysTexts = baseline.requests.map {
            (it["messages"]!!.jsonArray.first().jsonObject["content"] as JsonPrimitive).content
        }
        assertEquals(baselineSysTexts.distinct(), sysTexts.distinct())
        assertTrue("system 至多在开组时扩展一次: ${sysTexts.distinct().size}", sysTexts.distinct().size <= 2)
        for (i in 1 until sysTexts.size) {
            assertTrue("第 ${i + 1} 次请求 system 必须保持已有前缀不变", sysTexts[i].startsWith(sysTexts[i - 1]))
        }
        assertTrue(sysTexts.none { it.contains(AtlasHintPolicy.HEADER) || it.contains("atlas_hint") })

        val toolArrays = withAtlas.requests.map { it["tools"]!!.jsonArray.map { t -> t.toString() } }
        assertTrue("tools 至多在展开组时变动: distinct=${toolArrays.distinct().size}", toolArrays.distinct().size <= 2)
        for (i in 1 until toolArrays.size) {
            val prev = toolArrays[i - 1]
            val curr = toolArrays[i]
            assertTrue("第 ${i + 1} 次请求 tools 必须以此前列表为精确前缀", curr.size >= prev.size && curr.take(prev.size) == prev)
        }
        assertTrue(toolArrays.flatten().none { it.contains(AtlasHintPolicy.HEADER) || it.contains("atlas_hint") })

        // 2. 历史消息严格只增不改（每次请求的 messages 均以此前请求 messages 为精确前缀）
        val messageLists = withAtlas.requests.map(::nonSystemMessages)
        for (i in 1 until messageLists.size) {
            val prev = messageLists[i - 1]
            val curr = messageLists[i]
            assertTrue("第 ${i + 1} 次请求消息数应增加", curr.size > prev.size)
            assertEquals("第 ${i + 1} 次请求不得改写已有消息前缀（破坏 Zone 1/2 缓存）", prev, curr.take(prev.size))
        }

        // 3. 首次请求（读屏前）不含 atlas_hint；atlas_hint 仅以 Zone 3 notice 形式追加在观测之后的新消息里
        assertFalse(messageLists.first().any { it.contains("atlas_hint") })
        val noticeMessages = messageLists.last().filter {
            (it.contains("atlas_hint")) && it.contains(AtlasHintPolicy.HEADER)
        }
        assertEquals(2, noticeMessages.size)
    }

    private enum class ScreenNode { HOME, GENERAL, DISPLAY, LAB, LAB_DONE }

    private class AbRunResult(
        val outcome: TaskState,
        val requests: List<JsonObject>,
        val totalPromptTokens: Int,
        val screenActions: List<String>,
        val events: List<RunEvent>,
    )

    private fun runAbScenario(store: AtlasStore?): AbRunResult {
        val screenPort = SettingsAbScreenPort()
        val bodies = CopyOnWriteArrayList<JsonObject>()
        var totalPromptTokens = 0
        var stepCounter = 0

        val server = MockWebServer().also { servers += it }.apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val raw = request.body.readUtf8()
                    val body = Json.parseToJsonElement(raw).jsonObject
                    bodies += body
                    val tokens = TokenEstimate.of(raw)
                    totalPromptTokens += tokens
                    stepCounter += 1
                    val replyJson = decideNextReply(stepCounter, body, tokens)
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(replyJson)
                }
            }
            start()
        }

        val model = OpenAiCompatModel(
            baseUrl = server.url("/").toString().trimEnd('/') + "/v1",
            apiKey = "k",
            model = "m",
            capabilities = Capabilities(
                toolCalls = true,
                vision = false,
                contextTokens = 128_000,
                reasoning = false,
                promptCaching = PromptCaching.Implicit,
            ),
            http = OkHttpClient(),
        )
        val recorded = mutableListOf<RunEvent>()
        val sink = object : RunSink {
            override fun event(e: RunEvent) {
                recorded += e
            }

            override fun media(mediaId: Int, png: ByteArray) {}
        }

        var outcome: TaskState? = null
        runTest {
            outcome = AgentLoop(
                model = model,
                tools = toolDispatcher(screenPort),
                basePrompt = SystemPrompt.identity(),
                maxSteps = 30,
                sink = sink,
                confirmations = null,
                flags = SpeedFlags(replay = false),
                clock = { 0L },
                zone = ZoneOffset.UTC,
                atlas = store,
            ).run("打开设置里的省流量模式")
        }

        return AbRunResult(
            outcome = outcome!!,
            requests = bodies.toList(),
            totalPromptTokens = totalPromptTokens,
            screenActions = screenPort.actions.toList(),
            events = recorded.toList(),
        )
    }

    /** 模拟真实模型决策：读到 Zone 3 Atlas 历史提示时直奔目标控件，否则按屏幕逐项试错再返回。 */
    private fun decideNextReply(step: Int, body: JsonObject, promptTokens: Int): String {
        val msgs = body["messages"]!!.jsonArray
        val toolContentsAfterSystem = msgs.drop(1)
            .filter { (it.jsonObject["role"] as? JsonPrimitive)?.contentOrNull == "tool" }
            .mapNotNull { (it.jsonObject["content"] as? JsonPrimitive)?.contentOrNull }
        val joinedToolHistory = toolContentsAfterSystem.joinToString("\n")
        val latestTool = toolContentsAfterSystem.lastOrNull().orEmpty()
        val latestTrailingUser = msgs.lastOrNull()?.toString().orEmpty()

        val lastObsSnapshot = SNAPSHOT_RE.findAll(joinedToolHistory).lastOrNull()?.groupValues?.get(1)
        val latestToolObj = latestTool.takeIf { it.startsWith("{") }?.let { Json.parseToJsonElement(it).jsonObject }
        val isObserve = latestToolObj != null && "snapshotId" in latestToolObj && "effect" !in latestToolObj

        // 1. 刚打开或操作改变画面后，先读屏获取当前界面元素
        if (!isObserve) {
            return toolCallReply(step, "phone_observe", buildJsonObject {}, promptTokens)
        }

        // 只看当前屏可见元素（elements / added / changed），忽略 diff 里的 removed 列表
        val visibleText = listOfNotNull(latestToolObj["elements"], latestToolObj["added"], latestToolObj["changed"])
            .joinToString("\n") { it.toString() }

        // 2. 在目标完成屏看到“省流量模式已开启”，结束任务
        if (visibleText.contains("省流量模式已开启")) {
            return answerReplyJson("省流量模式已开启", promptTokens)
        }

        // 3. 在主页：若有来自 Atlas 的历史提示指向“实验室”，直击 @e3；否则逐一试错 @e1 -> @e2 -> @e3
        if (visibleText.contains("entry_lab") || visibleText.contains("显示与亮度")) {
            val tapRef = when {
                latestTrailingUser.contains("entry_lab") || latestTrailingUser.contains("实验室") -> "@e3"
                !joinedToolHistory.contains("时间与日期") -> "@e1"
                !joinedToolHistory.contains("自动亮度") -> "@e2"
                else -> "@e3"
            }
            return toolCallReply(
                step,
                "phone_tap",
                buildJsonObject {
                    put("ref", tapRef)
                    put("snapshotId", lastObsSnapshot ?: "s1")
                },
                promptTokens,
            )
        }

        // 4. 在错误子页：点击返回回到主页
        if (visibleText.contains("时间与日期") || visibleText.contains("自动亮度")) {
            return toolCallReply(step, "phone_back", buildJsonObject {}, promptTokens)
        }

        // 5. 在实验页：点击“开启省流量模式”（@e7）
        if (visibleText.contains("switch_data_saver") || visibleText.contains("开启省流量模式")) {
            return toolCallReply(
                step,
                "phone_tap",
                buildJsonObject {
                    put("ref", "@e7")
                    put("snapshotId", lastObsSnapshot ?: "s1")
                },
                promptTokens,
            )
        }

        error("unexpected observation in mock model at step=$step: $latestTool")
    }

    private fun toolCallReply(step: Int, name: String, args: JsonObject, promptTokens: Int): String =
        """{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"call_$step","type":"function",""" +
            """"function":{"name":"$name","arguments":${JsonPrimitive(args.toString())}}}]}}],""" +
            """"usage":{"prompt_tokens":$promptTokens,"completion_tokens":10}}"""

    private fun answerReplyJson(answer: String, promptTokens: Int): String =
        """{"choices":[{"finish_reason":"stop","message":{"content":${JsonPrimitive(answer)}}}],"usage":{"prompt_tokens":$promptTokens,"completion_tokens":10}}"""

    private fun nonSystemMessages(body: JsonObject): List<String> =
        body["messages"]!!.jsonArray.drop(1).map { m ->
            val obj = m.jsonObject
            val filtered = obj.filterKeys { it != "cache_control" }
            JsonObject(filtered).toString()
        }

    private inner class SettingsAbScreenPort : ScreenPort by FakeScreen() {
        private var current = ScreenNode.HOME
        private var reads = 0
        private var peeks = 0
        val actions = mutableListOf<String>()

        private val generalSig = ScreenSig(
            sigId = "GeneralActivity#4aaa0000aaaa0000",
            activity = "$pkg/.GeneralActivity",
            fingerprint = 0x4aaa0000aaaa0000L,
            anchors = listOf("通用设置", "时间与日期"),
            resourceIds = listOf("btn_datetime"),
            packageName = pkg,
            shortActivity = "GeneralActivity",
        )
        private val displaySig = ScreenSig(
            sigId = "DisplayActivity#5bbb0000bbbb0000",
            activity = "$pkg/.DisplayActivity",
            fingerprint = 0x5bbb0000bbbb0000L,
            anchors = listOf("显示与亮度", "自动亮度"),
            resourceIds = listOf("btn_auto_brightness"),
            packageName = pkg,
            shortActivity = "DisplayActivity",
        )

        private fun makeSnapshot(id: String): ScreenSnapshot = when (current) {
            ScreenNode.HOME -> ScreenSnapshot(
                snapshotId = id,
                packageName = pkg,
                elements = listOf(
                    UiElement("@e1", "button", "通用", 540, 300, listOf(0, 200, 1080, 350), "$pkg:id/entry_general", clickable = true, editable = false, scrollable = false),
                    UiElement("@e2", "button", "显示与亮度", 540, 500, listOf(0, 400, 1080, 550), "$pkg:id/entry_display", clickable = true, editable = false, scrollable = false),
                    UiElement("@e3", "button", "实验室", 540, 760, listOf(0, 700, 1080, 820), "$pkg:id/entry_lab", clickable = true, editable = false, scrollable = false),
                ),
                width = 1080,
                height = 2400,
                activity = homeSig.activity,
                screenSig = homeSig,
            )
            ScreenNode.GENERAL -> ScreenSnapshot(
                snapshotId = id,
                packageName = pkg,
                elements = listOf(
                    UiElement("@e4", "button", "时间与日期", 540, 400, listOf(0, 350, 1080, 450), "$pkg:id/btn_datetime", clickable = true, editable = false, scrollable = false),
                ),
                width = 1080,
                height = 2400,
                activity = generalSig.activity,
                screenSig = generalSig,
            )
            ScreenNode.DISPLAY -> ScreenSnapshot(
                snapshotId = id,
                packageName = pkg,
                elements = listOf(
                    UiElement("@e5", "button", "自动亮度", 540, 400, listOf(0, 350, 1080, 450), "$pkg:id/btn_auto_brightness", clickable = true, editable = false, scrollable = false),
                ),
                width = 1080,
                height = 2400,
                activity = displaySig.activity,
                screenSig = displaySig,
            )
            ScreenNode.LAB -> ScreenSnapshot(
                snapshotId = id,
                packageName = pkg,
                elements = listOf(
                    UiElement("@e6", "button", "隐私实验", 540, 350, listOf(0, 300, 1080, 400), "$pkg:id/switch_privacy", clickable = true, editable = false, scrollable = false),
                    UiElement("@e7", "button", "开启省流量模式", 540, 560, listOf(0, 500, 1080, 620), "$pkg:id/switch_data_saver", clickable = true, editable = false, scrollable = false),
                ),
                width = 1080,
                height = 2400,
                activity = labSig.activity,
                screenSig = labSig,
            )
            ScreenNode.LAB_DONE -> ScreenSnapshot(
                snapshotId = id,
                packageName = pkg,
                elements = listOf(
                    UiElement("@e8", "text", "省流量模式已开启", 540, 560, listOf(0, 500, 1080, 620), "$pkg:id/status_data_saver", clickable = false, editable = false, scrollable = false),
                ),
                width = 1080,
                height = 2400,
                activity = labDoneSig.activity,
                screenSig = labDoneSig,
            )
        }

        override suspend fun snapshot(): ScreenSnapshot = makeSnapshot("s${++reads}")

        override suspend fun peek(): ScreenSnapshot = makeSnapshot("p${++peeks}")

        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            actions += "tap:$current:$ref"
            current = when (current) {
                ScreenNode.HOME -> when (ref) {
                    "@e1" -> ScreenNode.GENERAL
                    "@e2" -> ScreenNode.DISPLAY
                    "@e3" -> ScreenNode.LAB
                    else -> current
                }
                ScreenNode.LAB -> if (ref == "@e7") ScreenNode.LAB_DONE else current
                else -> current
            }
            return ActionResult.Ok(changed = true)
        }

        override suspend fun back(): ActionResult {
            actions += "back:$current"
            if (current == ScreenNode.GENERAL || current == ScreenNode.DISPLAY || current == ScreenNode.LAB) {
                current = ScreenNode.HOME
            }
            return ActionResult.Ok(changed = true)
        }
    }

    private companion object {
        val SNAPSHOT_RE = Regex("snapshotId\\\\?\"?:\\\\?\"?(s\\d+)")
    }
}
