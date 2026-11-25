package com.wangzan101.mika.core.atlas

import com.agent.use.ui.Anchors
import com.wangzan101.mika.core.record.RunEvent
import com.agent.use.ui.Selector
import com.agent.use.ui.ScreenSig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入框里的文字不该进图谱：新观测要洗掉，已经存下来的旧边也要洗掉。 */
class AtlasLearnerTypeTextTest {
    private val pkg = "com.example.search"
    private val bounds = listOf(0, 0, 100, 40)
    private val homeSig = ScreenSig(
        sigId = "MainActivity#aaaabbbbccccdddd",
        activity = "com.example.search/.MainActivity",
        fingerprint = 0xaaaabbbbccccdddduL.toLong(),
        anchors = listOf("首页", "搜索"),
        resourceIds = listOf("et_search"),
        packageName = pkg,
        shortActivity = "MainActivity",
    )
    private val typedSig = ScreenSig(
        sigId = "SearchActivity#1111222233334444",
        activity = "com.example.search/.SearchActivity",
        fingerprint = 0x1111222233334444L,
        anchors = listOf("搜索结果", "筛选"),
        resourceIds = listOf("rv_result"),
        packageName = pkg,
        shortActivity = "SearchActivity",
    )

    private fun action(tool: String, target: Selector?, before: ScreenSig, after: ScreenSig, step: Int) =
        RunEvent.ActionTarget(
            atMs = 1000L + step,
            step = step,
            tool = tool,
            argsJson = """{"ref":"@e1"}""",
            target = target,
            before = Anchors(before.packageName, before.anchors),
            after = Anchors(after.packageName, after.anchors),
            effective = true,
            callId = "c$step",
            protected = false,
            beforeActivity = before.activity,
            beforeSig = before,
            afterActivity = after.activity,
            afterSig = after,
        )

    @Test
    fun `输入动作存下来的目标不带输入的文字`() {
        val store = InMemoryAtlasStore()
        val typed = Selector("EditText", "安卓开发", "et_search", bounds, 0, uniqueAtRecord = true)
        val events = listOf(
            action("phone_type_text", typed, homeSig, typedSig, 1),
            RunEvent.ToolResult(1002L, 1, "phone_type_text", """{"ok":true}""", true, null, callId = "c1"),
            RunEvent.TaskEnded(2000L, "succeeded", "done"),
            RunEvent.OutcomeJudged(2001L, "done"),
        )

        AtlasLearner.learn(events = events, store = store, nowMs = 2001L)

        val atlas = store.get(pkg)!!
        val edge = atlas.edges.single { it.tool == "phone_type_text" }
        assertEquals("", edge.target?.name.orEmpty())
        assertEquals("et_search", edge.target?.resourceId)
        assertTrue(atlas.edges.none { it.target?.name == "安卓开发" })
    }

    @Test
    fun `已经存下来的旧输入边文字被洗掉`() {
        val store = InMemoryAtlasStore()
        store.put(
            AppAtlas(
                packageName = pkg,
                screens = listOf(
                    AtlasScreen(sigId = homeSig.sigId, shortActivity = "MainActivity", title = "首页"),
                    AtlasScreen(sigId = typedSig.sigId, shortActivity = "SearchActivity", title = "搜索结果页"),
                ),
                edges = listOf(
                    AtlasEdge(
                        fromSigId = homeSig.sigId,
                        toSigId = typedSig.sigId,
                        tool = "phone_type_text",
                        target = Selector("EditText", "安卓开发", "et_search", bounds, 0),
                        ok = 3,
                    ),
                ),
            ),
        )
        val tap = Selector("button", "搜索", "btn_search", bounds, 0, uniqueAtRecord = true)
        val events = listOf(
            action("phone_tap", tap, homeSig, typedSig, 1),
            RunEvent.ToolResult(1002L, 1, "phone_tap", """{"ok":true}""", true, null, callId = "c1"),
            RunEvent.TaskEnded(2000L, "succeeded", "done"),
            RunEvent.OutcomeJudged(2001L, "done"),
        )

        AtlasLearner.learn(events = events, store = store, nowMs = 2001L)

        val atlas = store.get(pkg)!!
        val old = atlas.edges.single { it.tool == "phone_type_text" }
        assertEquals("", old.target?.name.orEmpty())
        assertEquals("et_search", old.target?.resourceId)
        assertEquals(3, old.ok)
    }

    @Test
    fun `同一页的输入边并成一条`() {
        val store = InMemoryAtlasStore()
        store.put(
            AppAtlas(
                packageName = pkg,
                screens = listOf(
                    AtlasScreen(sigId = homeSig.sigId, shortActivity = "MainActivity", title = "首页"),
                    AtlasScreen(sigId = typedSig.sigId, shortActivity = "SearchActivity", title = "搜索结果页"),
                ),
                edges = listOf(
                    AtlasEdge(
                        fromSigId = homeSig.sigId,
                        toSigId = typedSig.sigId,
                        tool = "phone_type_text",
                        target = Selector("EditText", "安卓开发", "et_search", listOf(0, 0, 100, 40), 0),
                        ok = 3,
                    ),
                    AtlasEdge(
                        fromSigId = homeSig.sigId,
                        toSigId = typedSig.sigId,
                        tool = "phone_type_text",
                        target = Selector("EditText", "全栈开发", "et_search", listOf(0, 2, 100, 42), 0),
                        ok = 2,
                    ),
                ),
            ),
        )
        val tap = Selector("button", "搜索", "btn_search", bounds, 0, uniqueAtRecord = true)
        val events = listOf(
            action("phone_tap", tap, homeSig, typedSig, 1),
            RunEvent.ToolResult(1002L, 1, "phone_tap", """{"ok":true}""", true, null, callId = "c1"),
            RunEvent.TaskEnded(2000L, "succeeded", "done"),
            RunEvent.OutcomeJudged(2001L, "done"),
        )

        AtlasLearner.learn(events = events, store = store, nowMs = 2001L)

        val typed = store.get(pkg)!!.edges.filter { it.tool == "phone_type_text" }
        assertEquals(1, typed.size)
        assertEquals(5, typed.single().ok)
        assertEquals("", typed.single().target?.name.orEmpty())
    }

    @Test
    fun `点按动作的元素文字照旧保留`() {
        val store = InMemoryAtlasStore()
        val tap = Selector("button", "搜索", "btn_search", bounds, 0, uniqueAtRecord = true)
        val events = listOf(
            action("phone_tap", tap, homeSig, typedSig, 1),
            RunEvent.ToolResult(1002L, 1, "phone_tap", """{"ok":true}""", true, null, callId = "c1"),
            RunEvent.TaskEnded(2000L, "succeeded", "done"),
            RunEvent.OutcomeJudged(2001L, "done"),
        )

        AtlasLearner.learn(events = events, store = store, nowMs = 2001L)

        val atlas = store.get(pkg)!!
        val edge = atlas.edges.single { it.tool == "phone_tap" }
        assertEquals("搜索", edge.target?.name)
    }
}
