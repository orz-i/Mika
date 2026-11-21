package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.uistate.ScreenSig
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组合工具的子步（`Origin.Script`）在图谱里的分工：
 * 不能建节点、不能建边（否则图谱里全是「福利中心 --gesture--> 广告页」这种一步一变的东西），
 * 但要把「这一页上整段用过哪个工具」记下来，下次到这一页才有整段做法可用。
 */
class AtlasCapabilityTest {
    private val pkg = "com.example.shop"
    private val tool = "phone_watch_reward_videos"

    private val homeSig = ScreenSig(
        sigId = "MainActivity#00000000000000ff",
        activity = "$pkg/.MainActivity",
        fingerprint = 0x00000000000000ffL,
        anchors = listOf("首页", "福利中心", "我的"),
        resourceIds = listOf("tab_home", "btn_welfare"),
        packageName = pkg,
        shortActivity = "MainActivity",
    )
    private val welfareSig = ScreenSig(
        sigId = "WelfareActivity#0000ff000000ff00",
        activity = "$pkg/.WelfareActivity",
        fingerprint = 0x0000ff000000ff00L,
        anchors = listOf("福利中心", "激励任务", "去完成"),
        resourceIds = listOf("welfare_root", "btn_go"),
        packageName = pkg,
        shortActivity = "WelfareActivity",
    )
    private val unknownSig = ScreenSig(
        sigId = "AdActivity#00ff00ff00ff00ff",
        activity = "$pkg/.AdActivity",
        fingerprint = 0x00ff00ff00ff00ffL,
        anchors = listOf("广告", "跳过"),
        resourceIds = listOf("ad_root"),
        packageName = pkg,
        shortActivity = "AdActivity",
    )
    private val entrySel = Selector("button", "去完成", "btn_go", listOf(100, 200, 300, 260), 0, uniqueAtRecord = true)

    private fun action(
        atMs: Long,
        tool: String,
        beforeSig: ScreenSig?,
        afterSig: ScreenSig?,
        effective: Boolean = true,
        target: Selector? = null,
        source: String = "model",
        parentTool: String? = null,
        callId: String = "c$atMs",
    ) = RunEvent.ActionTarget(
        atMs = atMs,
        step = 1,
        tool = tool,
        argsJson = "{}",
        target = target,
        before = Anchors(beforeSig?.packageName, beforeSig?.anchors.orEmpty()),
        after = Anchors(afterSig?.packageName, afterSig?.anchors.orEmpty()),
        effective = effective,
        callId = callId,
        source = source,
        parentTool = parentTool,
        beforeActivity = beforeSig?.activity,
        beforeSig = beforeSig,
        afterActivity = afterSig?.activity,
        afterSig = afterSig,
    )

    private fun done(atMs: Long) = listOf(
        RunEvent.TaskEnded(atMs, "succeeded", "done"),
        RunEvent.OutcomeJudged(atMs + 1, "done"),
    )

    @Test
    fun keepsSubStepsOutOfTheGraphButRecordsTheCapability() {
        val store = InMemoryAtlasStore()
        val events = listOf(
            // 模型自己走的一步：首页 → 福利中心，这一条该进图谱
            action(1000L, "phone_tap", homeSig, welfareSig, target = entrySel, callId = "c1"),
            RunEvent.ToolResult(1001L, 1, "phone_tap", """{"ok":true,"changed":true}""", true, null, callId = "c1"),
            // 到了福利中心，模型调了整段工具；工具内部点了一次「去完成」，这是子步
            action(2000L, "phone_tap", welfareSig, unknownSig, source = "script", parentTool = tool, callId = "c2"),
            action(2500L, "phone_gesture", welfareSig, unknownSig, source = "script", parentTool = tool, callId = "c3"),
        ) + done(3000L)

        AtlasLearner.learn(
            events = events,
            store = store,
            appVersions = mapOf(pkg to "1.0.0"),
            appLabels = mapOf(pkg to "商城"),
            nowMs = 3001L,
        )

        val atlas = store.get(pkg)!!
        // 子步不进图谱：只有模型走的那两步对应的两页、一条边
        assertEquals(2, atlas.screens.size)
        assertEquals(1, atlas.edges.size)
        assertEquals(homeSig.sigId, atlas.edges[0].fromSigId)
        assertEquals(welfareSig.sigId, atlas.edges[0].toSigId)
        assertNull(atlas.findScreen(unknownSig.sigId))
        assertTrue(atlas.edges.none { it.fromSigId == welfareSig.sigId })

        // 但「这一页能整段干这件事」记下来了
        val welfare = atlas.findScreen(welfareSig.sigId)!!
        assertEquals(listOf(tool), welfare.capabilities)
        assertTrue(atlas.findScreen(homeSig.sigId)!!.capabilities.isEmpty())
    }

    @Test
    fun doesNotInventAPageForASubStepOnAnUnknownScreen() {
        val store = InMemoryAtlasStore()
        val events = listOf(
            action(1000L, "phone_tap", homeSig, welfareSig, target = entrySel, callId = "c1"),
            RunEvent.ToolResult(1001L, 1, "phone_tap", """{"ok":true,"changed":true}""", true, null, callId = "c1"),
            // 子步落在一屏从没见过的页面上：认不出就不贴，也不为它造节点
            action(2000L, "phone_tap", unknownSig, null, source = "script", parentTool = tool, callId = "c2"),
        ) + done(3000L)

        AtlasLearner.learn(events = events, store = store, nowMs = 3001L)

        val atlas = store.get(pkg)!!
        assertNull(atlas.findScreen(unknownSig.sigId))
        assertEquals(2, atlas.screens.size)
        assertTrue(atlas.screens.all { it.capabilities.isEmpty() })
    }

    @Test
    fun ignoresSubStepsWithoutAParentTool() {
        val store = InMemoryAtlasStore()
        // 网页脚本里的动作也是 source=script，但没有 parentTool：它不是「这一页有整段工具」
        val events = listOf(
            action(1000L, "phone_tap", homeSig, welfareSig, target = entrySel, callId = "c1"),
            RunEvent.ToolResult(1001L, 1, "phone_tap", """{"ok":true,"changed":true}""", true, null, callId = "c1"),
            action(2000L, "phone_tap", welfareSig, null, source = "script", parentTool = null, callId = "c2"),
        ) + done(3000L)

        AtlasLearner.learn(events = events, store = store, nowMs = 3001L)

        val atlas = store.get(pkg)!!
        assertTrue(atlas.screens.all { it.capabilities.isEmpty() })
        assertEquals(1, atlas.edges.size)
    }

    @Test
    fun keepsCapabilityListCleanAndBounded() {
        val screen = AtlasScreen(sigId = welfareSig.sigId)
        // 工具名之外的字符串一律不收：这里可能被塞进用户原文
        assertTrue(screen.withCapability("惊喜礼包 / 去完成").capabilities.isEmpty())
        assertTrue(screen.withCapability("").capabilities.isEmpty())
        assertTrue(screen.withCapability("Phone_Tap").capabilities.isEmpty())

        var s = screen
        repeat(MAX_ATLAS_CAPABILITIES + 3) { i -> s = s.withCapability("phone_tool_$i") }
        assertEquals(MAX_ATLAS_CAPABILITIES, s.capabilities.size)
        // 留最新的那几个
        assertEquals("phone_tool_${MAX_ATLAS_CAPABILITIES + 2}", s.capabilities.last())
        assertFalse(s.capabilities.contains("phone_tool_0"))

        // 重复记同一个只刷时间，不重复入列
        val once = AtlasScreen(sigId = "x").withCapability(tool, atMs = 1000L)
        val twice = once.withCapability(tool, atMs = 2000L)
        assertEquals(listOf(tool), twice.capabilities)
        assertEquals(2000L, twice.lastSeenMs)
    }

    @Test
    fun mentionsTheCapabilityInThePageHint() {
        val store = InMemoryAtlasStore()
        store.put(
            AppAtlas(
                packageName = pkg,
                screens = listOf(
                    AtlasScreen(
                        sigId = welfareSig.sigId,
                        activity = welfareSig.activity,
                        shortActivity = welfareSig.shortActivity,
                        fingerprint = welfareSig.fingerprint,
                        anchors = welfareSig.anchors,
                        resourceIds = welfareSig.resourceIds,
                        title = "福利中心",
                        titleSource = AtlasScreen.TITLE_USER,
                        capabilities = listOf(tool),
                    ),
                ),
            ),
        )
        val snapshot = ScreenSnapshot(
            snapshotId = "s1",
            packageName = pkg,
            elements = emptyList(),
            width = 1080,
            height = 2400,
            activity = welfareSig.activity,
            screenSig = welfareSig,
        )

        val hint = AtlasHintPolicy().hintFor(snapshot, store)

        assertNotNull(hint)
        assertTrue(hint!!.text.contains(tool))
        assertTrue(hint.text.contains("福利中心"))
    }

    @Test
    fun sharesDoNotCarryCapabilitiesFromAnotherDevice() {
        val source = AppAtlas(
            packageName = pkg,
            appLabel = "商城",
            screens = listOf(
                AtlasScreen(
                    sigId = welfareSig.sigId,
                    activity = welfareSig.activity,
                    shortActivity = welfareSig.shortActivity,
                    fingerprint = welfareSig.fingerprint,
                    anchors = welfareSig.anchors,
                    resourceIds = welfareSig.resourceIds,
                    title = "福利中心",
                    titleSource = AtlasScreen.TITLE_USER,
                    capabilities = listOf(tool),
                ),
            ),
        )
        val raw = AtlasShare.export(source).encode()

        val store = InMemoryAtlasStore()
        val result = AtlasShare.importInto(raw, store, nowMs = 5000L)

        assertTrue("import rejected: $result", result is AtlasShare.ImportResult.Imported)
        val screen = store.get(pkg)!!.findScreen(welfareSig.sigId)!!
        // 页面和标题留着，「这页能整段干这件事」的说法丢掉
        assertEquals("福利中心", screen.title)
        assertTrue(screen.capabilities.isEmpty())
    }
}
