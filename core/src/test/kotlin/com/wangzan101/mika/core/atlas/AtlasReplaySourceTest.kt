package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.record.RunEvent
import com.agent.use.ui.Anchors
import com.agent.use.ui.Selector
import com.agent.use.ui.ScreenSig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回放、快捷流程、自动寻路都走 `Origin.Replay`，它们照着自己已经记下的路线重走一遍。
 *
 * 这些事件不能当成「新证据」写回图谱：否则同一条边每被回放一次就 ok+1，
 * 图谱等于在拿自己的旧结论给自己打分，用得越久越显得「验证过」。
 * 它唯一还能说明的是「这一页还是这个样子」，所以只推后已存在页面的最近出现时间。
 */
class AtlasReplaySourceTest {
    private val pkg = "com.example.shop"

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
    private val detailSig = ScreenSig(
        sigId = "DetailActivity#00ff00ff00ff00ff",
        activity = "$pkg/.DetailActivity",
        fingerprint = 0x00ff00ff00ff00ffL,
        anchors = listOf("商品详情", "立即购买"),
        resourceIds = listOf("detail_root"),
        packageName = pkg,
        shortActivity = "DetailActivity",
    )
    private val entrySel = Selector("button", "福利中心", "btn_welfare", listOf(100, 200, 300, 260), 0, uniqueAtRecord = true)
    private val buySel = Selector("button", "立即购买", "btn_buy", listOf(100, 900, 300, 960), 0, uniqueAtRecord = true)

    private fun action(
        atMs: Long,
        beforeSig: ScreenSig?,
        afterSig: ScreenSig?,
        target: Selector? = null,
        source: String = Origin.SOURCE_MODEL,
        tool: String = "phone_tap",
        callId: String = "c$atMs",
    ) = RunEvent.ActionTarget(
        atMs = atMs,
        step = 1,
        tool = tool,
        argsJson = """{"ref":"@e1"}""",
        target = target,
        before = Anchors(beforeSig?.packageName, beforeSig?.anchors.orEmpty()),
        after = Anchors(afterSig?.packageName, afterSig?.anchors.orEmpty()),
        effective = true,
        callId = callId,
        source = source,
        beforeActivity = beforeSig?.activity,
        beforeSig = beforeSig,
        afterActivity = afterSig?.activity,
        afterSig = afterSig,
    )

    private fun done(atMs: Long) = listOf(
        RunEvent.TaskEnded(atMs, "succeeded", "done"),
        RunEvent.OutcomeJudged(atMs + 1, "done"),
    )

    /** 先让图谱里有「首页 → 福利中心」这条边，作为后面回放的既有基础。 */
    private fun seed(store: AtlasStore) {
        AtlasLearner.learn(
            events = listOf(
                action(1000L, homeSig, welfareSig, target = entrySel, callId = "c1"),
                RunEvent.ToolResult(1001L, 1, "phone_tap", """{"ok":true,"changed":true}""", true, null, callId = "c1"),
            ) + done(1500L),
            store = store,
            appVersions = mapOf(pkg to "1.0"),
            nowMs = 1500L,
        )
    }

    @Test
    fun replayStepsDoNotBecomeEdges() {
        val store = InMemoryAtlasStore()
        seed(store)
        val before = store.get(pkg)!!
        val edgeCount = before.edges.size
        val welfareHits = before.findScreen(welfareSig.sigId)!!.hits

        AtlasLearner.learn(
            events = listOf(
                // 回放：福利中心 → 详情页，一条全新路径，不能因此长出边
                action(5000L, welfareSig, detailSig, target = buySel, source = Origin.SOURCE_REPLAY, callId = "r1"),
                action(5500L, welfareSig, detailSig, target = buySel, source = Origin.SOURCE_REPLAY, callId = "r2"),
            ) + done(6000L),
            store = store,
            appVersions = mapOf(pkg to "1.0"),
            nowMs = 6000L,
        )

        val atlas = store.get(pkg)!!
        assertEquals("回放不得新增边", edgeCount, atlas.edges.size)
        assertNull("回放不得为陌生页造节点", atlas.findScreen(detailSig.sigId))
        assertTrue("回放不得让 hits 虚涨", atlas.findScreen(welfareSig.sigId)!!.hits == welfareHits)
    }

    @Test
    fun replayKnocksExistingScreensForwardWithoutCountingAHit() {
        val store = InMemoryAtlasStore()
        seed(store)
        val before = store.get(pkg)!!.findScreen(welfareSig.sigId)!!

        AtlasLearner.learn(
            events = listOf(
                action(9000L, welfareSig, welfareSig, source = Origin.SOURCE_REPLAY, callId = "r1"),
            ) + done(9100L),
            store = store,
            appVersions = mapOf(pkg to "1.0"),
            nowMs = 9100L,
        )

        val after = store.get(pkg)!!.findScreen(welfareSig.sigId)!!
        assertEquals("回放走到的页面要把最近出现时间推后，否则会被 LRU 当陈旧数据淘汰", 9000L, after.lastSeenMs)
        assertEquals("回放不是发现，hits 不涨", before.hits, after.hits)
    }

    @Test
    fun replayAloneDoesNotLeaveAnEmptyAtlasRecord() {
        val store = InMemoryAtlasStore()

        val result = AtlasLearner.learn(
            events = listOf(
                action(1000L, homeSig, welfareSig, target = entrySel, source = Origin.SOURCE_REPLAY, callId = "r1"),
            ) + done(1500L),
            store = store,
            appVersions = mapOf(pkg to "1.0"),
            nowMs = 1500L,
        )

        assertNull("没有既有图谱时，回放无处可贴，不该留下一条空的包记录", store.get(pkg))
        assertTrue(result.updatedByPackage.isEmpty())
    }

    @Test
    fun replayKeepsTheExistingEdgeCount() {
        val store = InMemoryAtlasStore()
        seed(store)
        val edge = store.get(pkg)!!.edges.single()

        // 连着回放同一条边十次：ok 必须原地不动
        val replays = (1..10).map { i ->
            action(2000L + i * 100L, homeSig, welfareSig, target = entrySel, source = Origin.SOURCE_REPLAY, callId = "r$i")
        }
        AtlasLearner.learn(
            events = replays + done(4000L),
            store = store,
            appVersions = mapOf(pkg to "1.0"),
            nowMs = 4000L,
        )

        val after = store.get(pkg)!!.edges.single()
        assertEquals("回放不能给边记成功", edge.ok, after.ok)
        assertEquals(edge.weakOk, after.weakOk)
        assertNotNull(store.get(pkg)!!.findScreen(homeSig.sigId))
    }
}
