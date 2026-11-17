package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSig
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasNavigatorTest {
    private val pkg = "com.example.settings"

    private fun sig(id: String, act: String, fp: Long, anchors: List<String>) = ScreenSig(
        sigId = id,
        activity = "$pkg/.$act",
        fingerprint = fp,
        anchors = anchors,
        resourceIds = emptyList(),
        packageName = pkg,
        shortActivity = act,
    )

    private val homeSig = sig("Home#1", "HomeActivity", 0x1001L, listOf("设置主页", "网络", "实验室"))
    private val netSig = sig("Net#2", "NetActivity", 0x2002L, listOf("网络设置", "高级网络"))
    private val labSig = sig("Lab#3", "LabActivity", 0x3003L, listOf("实验功能", "省流量开关"))
    private val badDirectSig = sig("Bad#4", "BadActivity", 0x4004L, listOf("不可靠页"))

    private val selNet = Selector("button", "网络", "btn_net", emptyList(), 0, uniqueAtRecord = true)
    private val selLabFromNet = Selector("button", "高级网络", "btn_adv_lab", emptyList(), 0, uniqueAtRecord = true)
    private val selLabDirectUnreliable = Selector("button", "直达入口", "btn_bad_direct", emptyList(), 0, uniqueAtRecord = true)
    private val selZeroOk = Selector("button", "零成功入口", "btn_zero", emptyList(), 0, uniqueAtRecord = true)
    private val selPay = Selector("button", "确认支付", "btn_pay", emptyList(), 0, uniqueAtRecord = true)

    private fun screenNode(sig: ScreenSig, title: String, unverified: Boolean = false) = AtlasScreen(
        sigId = sig.sigId,
        activity = sig.activity,
        shortActivity = sig.shortActivity,
        fingerprint = sig.fingerprint,
        anchors = sig.anchors,
        title = title,
        titleSource = AtlasScreen.TITLE_LLM,
        hits = 5,
        unverifiedAfterUpgrade = unverified,
    )

    private class NavScreenPort(
        private val pkg: String,
        private val homeSig: ScreenSig,
        private val netSig: ScreenSig,
        private val labSig: ScreenSig,
        private val badSig: ScreenSig,
        private val divergeAfterFirstHop: Boolean = false,
        private val includePayOnHop: Boolean = false,
        private val protectedAfterHop: Boolean = false,
    ) : ScreenPort by FakeScreen() {
        var current = "home"
        var reads = 0
        val taps = mutableListOf<String>()

        private fun snap(id: String): ScreenSnapshot = when (current) {
            "home" -> ScreenSnapshot(
                id, pkg,
                listOf(
                    UiElement("@e1", "button", "网络", 200, 300, listOf(0, 250, 500, 350), "btn_net", true, false, false),
                    UiElement("@e2", "button", if (includePayOnHop) "确认支付" else "直达入口", 200, 500, listOf(0, 450, 500, 550), if (includePayOnHop) "btn_pay" else "btn_bad_direct", true, false, false),
                ),
                1080, 2400, activity = homeSig.activity, screenSig = homeSig,
            )
            "net" -> ScreenSnapshot(
                id, pkg,
                listOf(UiElement("@e3", "button", "高级网络", 200, 400, listOf(0, 350, 600, 450), "btn_adv_lab", true, false, false)),
                1080, 2400, activity = netSig.activity, screenSig = netSig,
            )
            "lab" -> ScreenSnapshot(
                id, pkg,
                listOf(UiElement("@e4", "text", "实验功能", 200, 200, listOf(0, 150, 600, 250), null, false, false, false)),
                1080, 2400, activity = labSig.activity, screenSig = labSig,
            )
            else -> ScreenSnapshot(
                id, pkg,
                if (protectedAfterHop) emptyList() else listOf(UiElement("@e9", "text", "不可靠页", 200, 200, listOf(0, 150, 600, 250), null, false, false, false)),
                1080, 2400, protected = protectedAfterHop, activity = badSig.activity, screenSig = badSig,
            )
        }

        override suspend fun snapshot() = snap("n${++reads}")
        override suspend fun peek() = snap("p$reads")

        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            taps += "$current:$ref"
            current = when {
                current == "home" && ref == "@e1" -> if (divergeAfterFirstHop) "bad" else "net"
                current == "net" && ref == "@e3" -> "lab"
                else -> current
            }
            return ActionResult.Ok(changed = true)
        }
    }

    @Test
    fun dijkstraIgnoresUnreliableEdgesAndPrefersHighSuccessRatePath() {
        val atlas = AppAtlas(
            packageName = pkg,
            screens = listOf(
                screenNode(homeSig, "设置主页"),
                screenNode(netSig, "网络设置"),
                screenNode(labSig, "实验功能"),
                screenNode(badDirectSig, "不可靠页"),
            ),
            edges = listOf(
                // 0 成功或 miss > ok 边一律忽略
                AtlasEdge(homeSig.sigId, labSig.sigId, "phone_tap", selZeroOk, ok = 0, miss = 1),
                AtlasEdge(homeSig.sigId, labSig.sigId, "phone_tap", selLabDirectUnreliable, ok = 2, miss = 3),
                // 两跳高可靠路径：home -> net -> lab
                AtlasEdge(homeSig.sigId, netSig.sigId, "phone_tap", selNet, ok = 8, miss = 0),
                AtlasEdge(netSig.sigId, labSig.sigId, "phone_tap", selLabFromNet, ok = 7, miss = 0),
                // 带较多失误（ok=4, miss=4）的一跳边权重极高，劣于两跳无失误稳定路径（weight 2.24 vs 3.20）
                AtlasEdge(homeSig.sigId, badDirectSig.sigId, "phone_tap", selLabDirectUnreliable, ok = 4, miss = 4),
                AtlasEdge(badDirectSig.sigId, labSig.sigId, "phone_tap", selLabDirectUnreliable, ok = 4, miss = 4),
            ),
        )

        val plan = AtlasNavigator.planPath(atlas, homeSig, "实验功能")
        assertTrue("应规划出可靠路径: $plan", plan is AtlasNavigator.Plan.Path)
        val path = plan as AtlasNavigator.Plan.Path
        assertEquals(listOf(selNet, selLabFromNet), path.edges.map { it.target })

        // 已在目标页直接返回 AlreadyAtTarget
        assertTrue(AtlasNavigator.planPath(atlas, labSig, "LabActivity") is AtlasNavigator.Plan.AlreadyAtTarget)

        // 不可达目标返回 Unreachable
        val disconnected = atlas.copy(edges = emptyList())
        assertTrue(AtlasNavigator.planPath(disconnected, homeSig, "实验功能") is AtlasNavigator.Plan.Unreachable)
    }

    @Test
    fun phoneNavigateToExecutesMultiHopPathAndVerifiesEachTransition() = runTest {
        val store = InMemoryAtlasStore()
        store.put(
            AppAtlas(
                packageName = pkg,
                screens = listOf(
                    screenNode(homeSig, "设置主页"),
                    screenNode(netSig, "网络设置"),
                    screenNode(labSig, "实验功能"),
                ),
                edges = listOf(
                    AtlasEdge(homeSig.sigId, netSig.sigId, "phone_tap", selNet, ok = 8, miss = 0),
                    AtlasEdge(netSig.sigId, labSig.sigId, "phone_tap", selLabFromNet, ok = 7, miss = 0),
                ),
            ),
        )
        val screen = NavScreenPort(pkg, homeSig, netSig, labSig, badDirectSig)
        val events = mutableListOf<RunEvent>()
        val tools = toolDispatcher(screen, atlas = store)
        assertFalse(tools.index["phone_navigate_to"]!!.routable)
        assertTrue(toolDispatcher(screen, atlas = null).definitions().none { it.name == "phone_navigate_to" })

        val exec = ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }))
        val res = exec.run(Part.ToolCall("nav1", "phone_navigate_to", """{"target":"实验功能"}"""), Origin.Model(1))
        val content = (res as ActionOutcome.Ran).exec.content
        assertEquals(true, (content["ok"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals(2, (content["hops"] as? JsonPrimitive)?.intOrNull)
        assertEquals(listOf("home:@e1", "net:@e3"), screen.taps)
        assertTrue(events.filterIsInstance<RunEvent.ToolResult>().any { it.source == "replay" && it.tool == "phone_tap" })
    }

    @Test
    fun stopsCleanlyOnDivergenceProtectedPageAndPaymentWithoutInflatingOk() = runTest {
        val store = InMemoryAtlasStore()
        val edge1 = AtlasEdge(homeSig.sigId, netSig.sigId, "phone_tap", selNet, ok = 5, miss = 0)
        val edgePay = AtlasEdge(homeSig.sigId, labSig.sigId, "phone_tap", selPay, ok = 4, miss = 0)
        store.put(
            AppAtlas(
                packageName = pkg,
                screens = listOf(screenNode(homeSig, "设置主页"), screenNode(netSig, "网络设置"), screenNode(labSig, "实验功能")),
                edges = listOf(edge1),
            ),
        )

        // 1. 跳后画面偏离（期望 Net#2 但落到了 Bad#4）-> 立即停止、交还模型、记一次 miss、不虚增 ok
        val divScreen = NavScreenPort(pkg, homeSig, netSig, labSig, badDirectSig, divergeAfterFirstHop = true)
        val divTools = toolDispatcher(divScreen, atlas = store)
        val divExec = ActionExecutor(divTools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(object : RunSink {
            override fun event(e: RunEvent) {}
        }))
        val divRes = divExec.run(Part.ToolCall("nav2", "phone_navigate_to", """{"target":"网络设置"}"""), Origin.Model(1))
        val divContent = (divRes as ActionOutcome.Ran).exec.content
        assertEquals(false, (divContent["ok"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals("navigation_diverged", (divContent["error"] as? JsonPrimitive)?.content)
        val afterEdge = store.get(pkg)!!.edges.single()
        assertEquals(5, afterEdge.ok)
        assertEquals(1, afterEdge.miss)

        // 2. 落入受保护页面 -> 立即中止
        val protScreen = NavScreenPort(pkg, homeSig, netSig, labSig, badDirectSig, divergeAfterFirstHop = true, protectedAfterHop = true)
        val protTools = toolDispatcher(protScreen, atlas = store)
        val protExec = ActionExecutor(protTools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(object : RunSink {
            override fun event(e: RunEvent) {}
        }))
        val protRes = protExec.run(Part.ToolCall("nav3", "phone_navigate_to", """{"target":"网络设置"}"""), Origin.Model(1))
        val protContent = (protRes as ActionOutcome.Ran).exec.content
        assertEquals(false, (protContent["ok"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals("protected_page", (protContent["stepError"] as? JsonPrimitive)?.content)

        // 3. 路径包含支付操作 -> Origin.Replay 在执行前直接拦停（不点付款按钮）
        store.put(AppAtlas(pkg, screens = listOf(screenNode(homeSig, "设置主页"), screenNode(labSig, "实验功能")), edges = listOf(edgePay)))
        val payScreen = NavScreenPort(pkg, homeSig, netSig, labSig, badDirectSig, includePayOnHop = true)
        val payTools = toolDispatcher(payScreen, atlas = store)
        val payExec = ActionExecutor(payTools, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(object : RunSink {
            override fun event(e: RunEvent) {}
        }))
        val payRes = payExec.run(Part.ToolCall("nav4", "phone_navigate_to", """{"target":"实验功能"}"""), Origin.Model(1))
        val payContent = (payRes as ActionOutcome.Ran).exec.content
        assertEquals(false, (payContent["ok"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals("confirmation_required", (payContent["error"] as? JsonPrimitive)?.content)
        assertTrue(payScreen.taps.isEmpty())
    }
}
