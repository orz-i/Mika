package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.agent.use.ui.Anchors
import com.agent.use.ui.RouteStep
import com.agent.use.ui.Selector
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.agent.use.ui.ScreenSig
import com.agent.use.ui.ScreenSnapshot
import com.agent.use.ui.UiElement
import com.agent.use.ui.Verification
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.agent.use.ui.MAX_SHORTCUT_CONSECUTIVE_MISSES

class AtlasShortcutRunnerTest {
    private val pkg = "com.example.shop"
    private val homeSig = ScreenSig(
        sigId = "HomeActivity#1111000011110000",
        activity = "$pkg/.HomeActivity",
        fingerprint = 0x1111000011110000L,
        anchors = listOf("首页", "分类", "搜索"),
        resourceIds = listOf("btn_search"),
        packageName = pkg,
        shortActivity = "HomeActivity",
    )
    private val searchSig = ScreenSig(
        sigId = "SearchActivity#2222000022220000",
        activity = "$pkg/.SearchActivity",
        fingerprint = 0x2222000022220000L,
        anchors = listOf("搜索页", "输入框"),
        resourceIds = listOf("et_query", "btn_go"),
        packageName = pkg,
        shortActivity = "SearchActivity",
    )

    private val searchSel = Selector("button", "搜索", "btn_search", listOf(100, 100, 900, 180), 0, uniqueAtRecord = true)
    private val querySel = Selector("edit", "请输入关键词", "et_query", listOf(100, 120, 700, 200), 0, uniqueAtRecord = true)
    private val paySel = Selector("button", "确认支付", "btn_pay", listOf(100, 800, 900, 900), 0, uniqueAtRecord = true)

    private class FlowScreen(
        private val pkg: String,
        private val homeSig: ScreenSig,
        private val searchSig: ScreenSig,
        var includePayOnStep2: Boolean = false,
        var protectedOnSearch: Boolean = false,
        var wrongApp: Boolean = false,
    ) : ScreenPort by FakeScreen() {
        var page = 0
        var reads = 0
        val taps = mutableListOf<String>()
        val typed = mutableListOf<String>()

        private fun snap(id: String): ScreenSnapshot = when (page) {
            0 -> ScreenSnapshot(
                snapshotId = id,
                packageName = if (wrongApp) "com.other.app" else pkg,
                elements = listOf(
                    UiElement("@e1", "text", "首页", 200, 100, listOf(0, 0, 200, 80), null, false, false, false),
                    UiElement("@e2", "text", "分类", 400, 100, listOf(220, 0, 400, 80), null, false, false, false),
                    UiElement("@e3", "button", "搜索", 500, 150, listOf(100, 100, 900, 180), "btn_search", true, false, false),
                ),
                width = 1080,
                height = 2400,
                activity = homeSig.activity,
                screenSig = homeSig,
            )
            1 -> ScreenSnapshot(
                snapshotId = id,
                packageName = pkg,
                elements = if (protectedOnSearch) emptyList() else listOf(
                    UiElement("@e1", "text", "搜索页", 200, 100, listOf(0, 0, 300, 80), null, false, false, false),
                    UiElement("@e2", "edit", "请输入关键词", 400, 160, listOf(100, 120, 700, 200), "et_query", true, true, false),
                    UiElement("@e3", "button", if (includePayOnStep2) "确认支付" else "去结算", 500, 850, listOf(100, 800, 900, 900), "btn_pay", true, false, false),
                ),
                width = 1080,
                height = 2400,
                protected = protectedOnSearch,
                activity = searchSig.activity,
                screenSig = searchSig,
            )
            else -> ScreenSnapshot(
                snapshotId = id,
                packageName = pkg,
                elements = listOf(UiElement("@e1", "text", "搜索结果", 300, 200, listOf(0, 0, 400, 100), null, false, false, false)),
                width = 1080,
                height = 2400,
            )
        }

        override suspend fun snapshot() = snap("p${++reads}")
        override suspend fun peek() = snap("peek${reads}")

        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            taps += ref
            if (page == 0 && ref == "@e3") page = 1 else if (page == 1 && ref == "@e3") page = 2
            return ActionResult.Ok(changed = true)
        }

        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
            typed += text
            return ActionResult.Ok(changed = false, verification = Verification.MATCH)
        }
    }

    @Test
    fun phoneRunShortcutExecutesWithSlotsUpdatesSuccessAndIsNonRoutable() = runTest {
        val store = InMemoryAtlasStore()
        val sc = AtlasShortcut(
            id = "sc_search",
            name = "首页搜索{1}",
            slots = 1,
            entrySigId = homeSig.sigId,
            steps = listOf(
                RouteStep("phone_tap", "{}", searchSel, Anchors(pkg, listOf("首页", "分类")), Anchors(pkg, listOf("搜索页"))),
                RouteStep("phone_type_text", """{"text":"{1}"}""", querySel, Anchors(pkg, listOf("搜索页")), Anchors(pkg, listOf("搜索页"))),
            ),
        )
        store.put(
            AppAtlas(
                packageName = pkg,
                screens = listOf(
                    AtlasScreen(homeSig.sigId, homeSig.activity, homeSig.shortActivity, homeSig.fingerprint, homeSig.anchors),
                    AtlasScreen(searchSig.sigId, searchSig.activity, searchSig.shortActivity, searchSig.fingerprint, searchSig.anchors),
                ),
                shortcuts = listOf(sc),
            ),
        )

        val screen = FlowScreen(pkg, homeSig, searchSig)
        val events = mutableListOf<RunEvent>()
        val tools = toolDispatcher(screen, atlas = store)
        assertFalse(tools.index["phone_run_shortcut"]!!.routable)
        assertTrue(tools.index["phone_run_shortcut"] in tools.definitions().map { tools.index[it.name] })
        // 未传 atlas 时不开放给模型
        assertTrue(toolDispatcher(screen, atlas = null).definitions().none { it.name == "phone_run_shortcut" })

        val executor = ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }))
        val call = Part.ToolCall("sc1", "phone_run_shortcut", """{"shortcutId":"sc_search","slots":["蓝牙耳机"]}""")
        val out = executor.run(call, com.wangzan101.mika.core.action.Origin.Model(1))
        assertTrue("$out", out is com.wangzan101.mika.core.action.ActionOutcome.Ran)
        val content = (out as com.wangzan101.mika.core.action.ActionOutcome.Ran).exec.content
        assertEquals(true, (content["ok"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals(2, (content["completed"] as? JsonPrimitive)?.intOrNull)
        assertEquals(listOf("@e3"), screen.taps)
        assertEquals(listOf("蓝牙耳机"), screen.typed)

        val updated = store.get(pkg)!!.findShortcut("sc_search")!!
        assertEquals(1, updated.successes)
        assertEquals(0, updated.consecutiveMisses)
        // 子步骤通过 Origin.Replay 记入运行记录
        assertTrue(events.filterIsInstance<RunEvent.ToolResult>().any { it.source == "replay" && it.tool == "phone_tap" })
    }

    @Test
    fun stopsImmediatelyOnPaymentGuardWarningAndDivergenceWithoutOverPenalizingConfirmation() = runTest {
        val store = InMemoryAtlasStore()
        val payStep = RouteStep("phone_tap", "{}", paySel, Anchors(pkg, listOf("搜索页")), Anchors(pkg, listOf("搜索结果")))
        val step1 = RouteStep("phone_tap", "{}", searchSel, Anchors(pkg, listOf("首页", "分类")), Anchors(pkg, listOf("搜索页")))
        val scPay = AtlasShortcut("sc_pay", "快捷下单", "", 0, homeSig.sigId, listOf(step1, payStep))
        store.put(
            AppAtlas(
                packageName = pkg,
                screens = listOf(AtlasScreen(homeSig.sigId, homeSig.activity, homeSig.shortActivity, homeSig.fingerprint, homeSig.anchors)),
                shortcuts = listOf(scPay),
            ),
        )

        // 1. 不可逆/支付步：第 1 步执行，第 2 步「确认支付」在 Origin.Replay 下被立即拦停交还模型（且不累计 consecutiveMisses）
        val payScreen = FlowScreen(pkg, homeSig, searchSig, includePayOnStep2 = true)
        val payTools = toolDispatcher(payScreen, atlas = store)
        val payExec = ActionExecutor(payTools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(object : RunSink {
            override fun event(e: RunEvent) {}
        }))
        val payOut = payExec.run(Part.ToolCall("c1", "phone_run_shortcut", """{"shortcutId":"sc_pay"}"""), com.wangzan101.mika.core.action.Origin.Model(1))
        val payContent = (payOut as com.wangzan101.mika.core.action.ActionOutcome.Ran).exec.content
        assertEquals(false, (payContent["ok"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals("confirmation_required", (payContent["error"] as? JsonPrimitive)?.content)
        assertEquals(1, (payContent["completed"] as? JsonPrimitive)?.intOrNull)
        assertEquals(listOf("@e3"), payScreen.taps) // 仅第 1 步执行，第 2 步付款按钮绝未点击
        assertEquals(0, store.get(pkg)!!.findShortcut("sc_pay")!!.consecutiveMisses)

        // 2. 守卫告警场景（Origin.Replay stopOnGuardWarning = true）：触发告警时立即中止快捷流程
        val guardScreen = FlowScreen(pkg, homeSig, searchSig)
        val guards = OperationGuards(stepBudget = 10)
        // 连续同屏无变化观察多次在 OperationGuards 触发 read_loop Warn
        val guardTools = toolDispatcher(guardScreen, atlas = store)
        repeat(5) {
            guardTools.execute(Part.ToolCall("o$it", "phone_observe", "{}"))
            guards.read("phone_observe", fresh = false)
        }
        val guardExec = ActionExecutor(guardTools, guards, ConfirmationGate(), gate = {}, recorder = ActionRecorder(object : RunSink {
            override fun event(e: RunEvent) {}
        }))
        val guardRunner = AtlasShortcutRunner.forExecutor(guardTools, guardExec)
        // 第 1 次 read_loop warn 会在 readScreen() 内部的 ActionExecutor 触发 stopOnGuardWarning
        val guardOutcome = guardRunner.run(scPay, atlas = store.get(pkg))
        assertTrue("守卫警告应阻断快捷流程: $guardOutcome", guardOutcome is AtlasShortcutRunner.Outcome.Diverged)

        // 3. 切错前台应用不误伤 shortcut 失败计数；同包内连续 3 次页面结构/锚点偏离则自动停用
        val otherAppScreen = FlowScreen(pkg, homeSig, searchSig, wrongApp = true)
        val otherTools = toolDispatcher(otherAppScreen, atlas = store)
        val otherExec = ActionExecutor(otherTools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(object : RunSink {
            override fun event(e: RunEvent) {}
        }))
        val otherRes = (otherExec.run(Part.ToolCall("w_app", "phone_run_shortcut", """{"shortcutId":"sc_pay","package":"$pkg"}"""), com.wangzan101.mika.core.action.Origin.Model(1)) as com.wangzan101.mika.core.action.ActionOutcome.Ran).exec.content
        assertEquals(false, (otherRes["ok"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals(0, store.get(pkg)!!.findShortcut("sc_pay")!!.consecutiveMisses)

        val divergedScreen = FlowScreen(pkg, homeSig, searchSig).apply { page = 2 }
        val divTools = toolDispatcher(divergedScreen, atlas = store)
        val divExec = ActionExecutor(divTools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(object : RunSink {
            override fun event(e: RunEvent) {}
        }))
        repeat(MAX_SHORTCUT_CONSECUTIVE_MISSES) { idx ->
            val r = divExec.run(Part.ToolCall("w$idx", "phone_run_shortcut", """{"shortcutId":"sc_pay","package":"$pkg"}"""), com.wangzan101.mika.core.action.Origin.Model(idx + 1))
            val c = (r as com.wangzan101.mika.core.action.ActionOutcome.Ran).exec.content
            assertEquals(false, (c["ok"] as? JsonPrimitive)?.booleanOrNull)
        }
        assertTrue(store.get(pkg)!!.findShortcut("sc_pay")!!.disabled)
    }
}
