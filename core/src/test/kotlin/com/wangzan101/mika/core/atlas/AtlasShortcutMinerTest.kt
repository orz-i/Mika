package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.uistate.ScreenSig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasShortcutMinerTest {
    private val pkg = "com.example.shop"
    private val homeSig = ScreenSig(
        sigId = "HomeActivity#1111111111111111",
        activity = "$pkg/.HomeActivity",
        fingerprint = 0x1111111111111111L,
        anchors = listOf("商城首页", "分类", "搜索商品"),
        resourceIds = listOf("tab_home", "btn_search"),
        packageName = pkg,
        shortActivity = "HomeActivity",
    )
    private val searchInputSig = ScreenSig(
        sigId = "SearchActivity#2222222222222222",
        activity = "$pkg/.SearchActivity",
        fingerprint = 0x2222222222222222L,
        anchors = listOf("搜索页", "热搜", "历史搜索"),
        resourceIds = listOf("et_query", "btn_go"),
        packageName = pkg,
        shortActivity = "SearchActivity",
    )
    private val resultSig = ScreenSig(
        sigId = "ResultActivity#3333333333333333",
        activity = "$pkg/.ResultActivity",
        fingerprint = 0x3333333333333333L,
        anchors = listOf("搜索结果", "综合排序"),
        resourceIds = listOf("rv_results"),
        packageName = pkg,
        shortActivity = "ResultActivity",
    )

    private val tapSearchEntry = Selector("button", "搜索商品", "btn_search", listOf(100, 100, 900, 180), 0, uniqueAtRecord = true)
    private val editQuery = Selector("edit", "请输入关键词", "et_query", listOf(80, 120, 780, 200), 0, uniqueAtRecord = true)
    private val tapSubmit = Selector("button", "搜索", "btn_go", listOf(800, 120, 980, 200), 0, uniqueAtRecord = true)

    private fun buildRunEvents(query: String, secretFalse: Boolean = true): List<RunEvent> = listOf(
        RunEvent.ActionTarget(
            atMs = 1000L, step = 1, tool = "phone_tap", argsJson = """{"ref":"@e1"}""", target = tapSearchEntry,
            before = Anchors(pkg, homeSig.anchors), after = Anchors(pkg, searchInputSig.anchors),
            effective = true, callId = "c1", beforeActivity = homeSig.activity, beforeSig = homeSig,
            afterActivity = searchInputSig.activity, afterSig = searchInputSig,
        ),
        RunEvent.ToolResult(1001L, 1, "phone_tap", """{"ok":true,"changed":true}""", true, null, callId = "c1"),
        RunEvent.ActionTarget(
            atMs = 1100L, step = 2, tool = "phone_type_text", argsJson = """{"text":"$query"}""", target = editQuery,
            before = Anchors(pkg, searchInputSig.anchors), after = Anchors(pkg, searchInputSig.anchors),
            effective = true, callId = "c2", beforeActivity = searchInputSig.activity, beforeSig = searchInputSig,
            afterActivity = searchInputSig.activity, afterSig = searchInputSig,
        ),
        RunEvent.ToolResult(
            1101L, 2, "phone_type_text",
            if (secretFalse) """{"ok":true,"secret":false,"verification":"match"}""" else """{"ok":true,"secret":true}""",
            false, null, callId = "c2",
        ),
        RunEvent.ActionTarget(
            atMs = 1200L, step = 3, tool = "phone_tap", argsJson = """{"ref":"@e3"}""", target = tapSubmit,
            before = Anchors(pkg, searchInputSig.anchors), after = Anchors(pkg, resultSig.anchors),
            effective = true, callId = "c3", beforeActivity = searchInputSig.activity, beforeSig = searchInputSig,
            afterActivity = resultSig.activity, afterSig = resultSig,
        ),
        RunEvent.ToolResult(1201L, 3, "phone_tap", """{"ok":true,"changed":true}""", true, null, callId = "c3"),
        RunEvent.TaskEnded(2000L, "succeeded", "done"),
        RunEvent.OutcomeJudged(2001L, "done"),
    )

    @Test
    fun minesParameterizedShortcutAcrossTwoSuccessfulRunsAndDeduplicatesSubWindows() {
        val store = InMemoryAtlasStore()

        // 第一次运行：记录为候选子序列，尚不晋升为快捷流程
        AtlasLearner.learn(
            events = buildRunEvents("蓝牙耳机"),
            goal = "查找蓝牙耳机",
            store = store,
            appVersions = mapOf(pkg to "1.0"),
            tools = TOOLS,
            nowMs = 2000L,
        )
        val afterFirst = store.get(pkg)!!
        assertTrue(afterFirst.shortcuts.isEmpty())
        assertTrue(afterFirst.candidatePatterns.isNotEmpty())

        // 候选首次就已参数化为 {1}，第二次共现后晋升，并抑制被包含的短窗口。
        AtlasLearner.learn(
            events = buildRunEvents("机械键盘"),
            goal = "查找机械键盘",
            store = store,
            appVersions = mapOf(pkg to "1.0"),
            tools = TOOLS,
            nowMs = 3000L,
        )
        val afterSecond = store.get(pkg)!!
        val threeStep = afterSecond.shortcuts.firstOrNull { it.steps.size == 3 && it.entrySigId == homeSig.sigId }
        assertTrue("应晋升 3 步快捷流程: ${afterSecond.shortcuts}", threeStep != null)
        assertEquals(1, threeStep!!.slots)
        assertTrue(threeStep.steps[1].argsJson.contains("{1}"))
        assertFalse(threeStep.steps[1].argsJson.contains("蓝牙耳机"))
        assertFalse(threeStep.steps[1].argsJson.contains("机械键盘"))
        assertFalse(threeStep.disabled)
        // 已被包含的子窗口（如 [tap 搜索, type] 或 [type, tap 提交]）不得重复晋升
        assertFalse(afterSecond.shortcuts.any { it.steps.size == 2 && it.entrySigId == homeSig.sigId })
    }

    @Test
    fun enforcesRouteRecorderSafetyRejectingSpoiledSecretAndProtectedRuns() {
        val store = InMemoryAtlasStore()

        // 1. 含 AskUser / RequestConfirmation / HandToUser 的运行不得挖掘
        val spoiledByAsk = buildRunEvents("耳机").dropLast(2) + listOf(
            RunEvent.ToolResult(1500L, 4, "ask_user", """{"ok":true}""", null, null, callId = "c4"),
            RunEvent.TaskEnded(2000L, "succeeded", "done"),
            RunEvent.OutcomeJudged(2001L, "done"),
        )
        val spoiledByPay = buildRunEvents("耳机").dropLast(2) + listOf(
            RunEvent.Confirmation(1500L, "pay", "确认支付", "99", null, "approved"),
            RunEvent.TaskEnded(2000L, "succeeded", "done"),
            RunEvent.OutcomeJudged(2001L, "done"),
        )
        val secretInput = buildRunEvents("123456", secretFalse = false)
        val protectedEvents = buildRunEvents("耳机").map { e ->
            if (e is RunEvent.ActionTarget && e.step == 2) e.copy(protected = true) else e
        }
        val maskedEvents = buildRunEvents(Redactor.MASK, secretFalse = true)

        for (bad in listOf(spoiledByAsk, spoiledByPay, secretInput, protectedEvents, maskedEvents)) {
            AtlasLearner.learn(events = bad, store = store, tools = TOOLS, nowMs = 2000L)
            AtlasLearner.learn(events = bad, store = store, tools = TOOLS, nowMs = 3000L)
        }
        val atlas = store.get(pkg)
        assertTrue("高风险/敏感/受保护序列不得产出任何快捷流程: ${atlas?.shortcuts}", atlas?.shortcuts.orEmpty().isEmpty())
    }

    @Test
    fun minesRepeatedSubSequencesDirectlyFromRoutes() {
        val baseAtlas = AppAtlas(
            packageName = pkg,
            screens = listOf(
                AtlasScreen(homeSig.sigId, homeSig.activity, homeSig.shortActivity, homeSig.fingerprint, homeSig.anchors, homeSig.resourceIds, title = "商城首页", titleSource = AtlasScreen.TITLE_LLM),
                AtlasScreen(searchInputSig.sigId, searchInputSig.activity, searchInputSig.shortActivity, searchInputSig.fingerprint, searchInputSig.anchors, searchInputSig.resourceIds, title = "搜索页", titleSource = AtlasScreen.TITLE_LLM),
            ),
        )
        val step1 = RouteStep("phone_tap", "{}", tapSearchEntry, Anchors(pkg, homeSig.anchors), Anchors(pkg, searchInputSig.anchors))
        val step2 = RouteStep("phone_type_text", """{"text":"{1}"}""", editQuery, Anchors(pkg, searchInputSig.anchors), Anchors(pkg, searchInputSig.anchors))
        val r1 = Route("r1", "搜索{1}并加入购物车", 1, listOf(pkg), emptyMap(), listOf(step1, step2), lastUsedMs = 0L)
        val r2 = Route("r2", "搜索{1}并看评价", 1, listOf(pkg), emptyMap(), listOf(step1, step2), lastUsedMs = 0L)

        val mined = AtlasShortcutMiner.mineFromRoutes(baseAtlas, listOf(r1, r2), tools = TOOLS, nowMs = 5000L)
        assertEquals(1, mined.shortcuts.size)
        val sc = mined.shortcuts.single()
        assertEquals(homeSig.sigId, sc.entrySigId)
        assertEquals(1, sc.slots)
        assertEquals(2, sc.steps.size)
    }
}
