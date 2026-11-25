package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.record.RunEvent
import com.agent.use.ui.Anchors
import com.agent.use.ui.Selector
import com.agent.use.ui.ScreenSig
import com.agent.use.ui.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.agent.use.ui.MAX_ATLAS_SCREENS
import com.agent.use.ui.ENTRY_SIG_ID
import com.agent.use.ui.ATLAS_ENTRY_TOOL

/** P0 改造的回归测试：弱成功边、入口边、页面重命名与删除、从入口寻路、弱边提示。 */
class AtlasP0Test {
    private val pkg = "com.example.shop"
    private val launcherPkg = "com.android.launcher"

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
    private val launcherSig = ScreenSig(
        sigId = "Launcher#1111111111111111",
        activity = "com.android.launcher/.Launcher",
        fingerprint = 0x1111111111111111L,
        anchors = listOf("时钟", "相机"),
        resourceIds = listOf("workspace"),
        packageName = launcherPkg,
        shortActivity = "Launcher",
    )
    private val searchSel = Selector("button", "搜索", "btn_search", listOf(100, 100, 300, 180), 0, uniqueAtRecord = true)

    private fun action(
        atMs: Long,
        tool: String,
        target: Selector?,
        beforeSig: ScreenSig?,
        afterSig: ScreenSig?,
        effective: Boolean = true,
        callId: String = "c1",
    ) = RunEvent.ActionTarget(
        atMs = atMs,
        step = 1,
        tool = tool,
        argsJson = if (tool == ATLAS_ENTRY_TOOL) """{"package":"${afterSig?.packageName}"}""" else """{"ref":"@e1"}""",
        target = target,
        before = Anchors(beforeSig?.packageName, beforeSig?.anchors.orEmpty()),
        after = Anchors(afterSig?.packageName, afterSig?.anchors.orEmpty()),
        effective = effective,
        callId = callId,
        beforeActivity = beforeSig?.activity,
        beforeSig = beforeSig,
        afterActivity = afterSig?.activity,
        afterSig = afterSig,
    )

    @Test
    fun recordsWeakEdgesWhenRunSucceededButNotDone() {
        val store = InMemoryAtlasStore()
        val events = listOf(action(1000L, "phone_tap", searchSel, homeSig, searchSig))

        AtlasLearner.learn(events = events, store = store, learnDone = false, succeeded = true, nowMs = 1000L)

        val atlas = store.get(pkg)!!
        val edge = atlas.edges.single()
        assertEquals(0, edge.ok)
        assertEquals(1, edge.weakOk)
        assertTrue(edge.isReliable())
    }

    @Test
    fun failedRunDoesNotCreateEdges() {
        val store = InMemoryAtlasStore()
        val events = listOf(action(1000L, "phone_tap", searchSel, homeSig, searchSig))

        AtlasLearner.learn(events = events, store = store, learnDone = false, succeeded = false, nowMs = 1000L)

        val atlas = store.get(pkg)!!
        assertEquals(0, atlas.edges.size)
        assertEquals(2, atlas.screens.size)
    }

    @Test
    fun learnsEntryEdgeWhenAnotherPackageOpensTheApp() {
        val store = InMemoryAtlasStore()
        val events = listOf(action(1000L, "phone_launch_app", null, launcherSig, homeSig))

        AtlasLearner.learn(events = events, store = store, learnDone = false, succeeded = true, nowMs = 1000L)

        val atlas = store.get(pkg)!!
        val entry = atlas.entryEdges().single()
        assertEquals(ENTRY_SIG_ID, entry.fromSigId)
        assertEquals(homeSig.sigId, entry.toSigId)
        assertEquals(ATLAS_ENTRY_TOOL, entry.tool)
        assertNull(entry.target)
        assertEquals(1, entry.weakOk)
    }

    @Test
    fun learnsEntryEdgeWhenLaunchHasNoBeforeScreen() {
        // 真机上启动 App 时读不到桌面：前屏为空，但启动本身就是从外部进入
        val store = InMemoryAtlasStore()
        val events = listOf(action(1000L, "phone_launch_app", null, null, homeSig))

        AtlasLearner.learn(events = events, store = store, learnDone = false, succeeded = true, nowMs = 1000L)

        val atlas = store.get(pkg)!!
        val entry = atlas.entryEdges().single()
        assertEquals(ENTRY_SIG_ID, entry.fromSigId)
        assertEquals(homeSig.sigId, entry.toSigId)
        assertEquals(1, entry.weakOk)
    }

    @Test
    fun compactionKeepsEntryEdge() {
        val atlas = AppAtlas(
            packageName = pkg,
            screens = (1..MAX_ATLAS_SCREENS + 5).map { i ->
                AtlasScreen(
                    sigId = "S$i#${i.toString(16).padStart(16, '0')}",
                    title = "页面 $i",
                    hits = if (i == 1) 9999 else i,
                    lastSeenMs = i.toLong(),
                )
            },
            edges = listOf(
                AtlasEdge(ENTRY_SIG_ID, "S1#0000000000000001", ATLAS_ENTRY_TOOL, weakOk = 3),
                AtlasEdge("S1#0000000000000001", "S2#0000000000000002", "phone_tap", searchSel, ok = 2),
            ),
        )

        val compacted = atlas.compacted()

        assertTrue(compacted.screens.size <= MAX_ATLAS_SCREENS)
        assertEquals(1, compacted.entryEdges().size)
    }

    @Test
    fun planFromEntryFindsPathAndNeedsLaunch() {
        val atlas = AppAtlas(
            packageName = pkg,
            screens = listOf(
                screen(homeSig, "首页"),
                screen(searchSig, "搜索页"),
            ),
            edges = listOf(
                AtlasEdge(ENTRY_SIG_ID, homeSig.sigId, ATLAS_ENTRY_TOOL, weakOk = 3, launchObserved = true),
                AtlasEdge(homeSig.sigId, searchSig.sigId, "phone_tap", searchSel, weakOk = 2),
            ),
        )

        val plan = AtlasNavigator.planFromEntry(atlas, "搜索页")

        assertTrue("应能从入口规划: $plan", plan is AtlasNavigator.Plan.Path)
        val path = plan as AtlasNavigator.Plan.Path
        assertTrue(path.needsLaunch)
        assertNull(path.fromScreen)
        assertEquals(searchSig.sigId, path.targetScreen.sigId)
        assertEquals(listOf(ENTRY_SIG_ID, homeSig.sigId), path.edges.map { it.fromSigId })
    }

    @Test
    fun planFromEntryFailsWithoutEntryEdge() {
        val atlas = AppAtlas(
            packageName = pkg,
            screens = listOf(screen(homeSig, "首页")),
            edges = emptyList(),
        )

        assertTrue(AtlasNavigator.planFromEntry(atlas, "首页") is AtlasNavigator.Plan.NoMatchingStart)
    }

    @Test
    fun renameMarksTitleAsUserAndSurvivesModelNaming() {
        val atlas = AppAtlas(packageName = pkg, screens = listOf(screen(homeSig, "MainActivity")))
        val renamed = atlas.withScreenTitle(homeSig.sigId, "首页")

        val screen = renamed.findScreen(homeSig.sigId)!!
        assertEquals("首页", screen.title)
        assertEquals(AtlasScreen.TITLE_USER, screen.titleSource)
        assertFalse(screen.needsNaming())

        val blank = renamed.withScreenTitle(homeSig.sigId, "   ")
        assertEquals(renamed, blank)
    }

    @Test
    fun removeScreenClearsEdgesAndShortcuts() {
        val atlas = AppAtlas(
            packageName = pkg,
            screens = listOf(screen(homeSig, "首页"), screen(searchSig, "搜索页")),
            edges = listOf(
                AtlasEdge(ENTRY_SIG_ID, homeSig.sigId, ATLAS_ENTRY_TOOL, weakOk = 1),
                AtlasEdge(homeSig.sigId, searchSig.sigId, "phone_tap", searchSel, weakOk = 1),
            ),
            shortcuts = listOf(
                AtlasShortcut(
                    id = "sc1",
                    name = "打开搜索",
                    entrySigId = homeSig.sigId,
                    steps = emptyList(),
                ),
            ),
        )

        val removed = atlas.withoutScreen(homeSig.sigId)

        assertEquals(listOf(searchSig.sigId), removed.screens.map { it.sigId })
        assertTrue(removed.edges.isEmpty())
        assertTrue(removed.shortcuts.isEmpty())
    }

    @Test
    fun hintUsesWeakEdgesAndChineseHeader() {
        val store = InMemoryAtlasStore()
        store.put(
            AppAtlas(
                packageName = pkg,
                screens = listOf(screen(homeSig, "首页"), screen(searchSig, "搜索页")),
                edges = listOf(AtlasEdge(homeSig.sigId, searchSig.sigId, "phone_tap", searchSel, weakOk = 2)),
            ),
        )

        val hint = AtlasHintPolicy.Default.hintFor(
            ScreenSnapshot("s1", pkg, emptyList(), 1080, 2400, screenSig = homeSig),
            store,
        )

        assertNotNull(hint)
        val text = hint!!.text
        assertTrue(text.startsWith(AtlasHintPolicy.HEADER))
        assertTrue("弱成功边也要给出提示: $text", text.contains("搜索页"))
        assertTrue(text.contains("成功"))
        assertTrue(text.lines().size <= AtlasHintPolicy.DEFAULT_MAX_LINES)
    }

    private fun screen(sig: ScreenSig, title: String) = AtlasScreen(
        sigId = sig.sigId,
        activity = sig.activity,
        shortActivity = sig.shortActivity,
        fingerprint = sig.fingerprint,
        anchors = sig.anchors,
        resourceIds = sig.resourceIds,
        title = title,
        titleSource = AtlasScreen.TITLE_LLM,
        appVersion = "1.0.0",
    )
}
