package com.wangzan101.mika.ui

import com.wangzan101.mika.atlas.AtlasRuntime
import com.agent.use.ui.AppAtlas
import com.agent.use.ui.AtlasControl
import com.agent.use.ui.AtlasEdge
import com.agent.use.ui.AtlasScreen
import com.wangzan101.mika.core.atlas.AtlasShare
import com.agent.use.ui.AtlasShortcut
import com.agent.use.ui.InMemoryAtlasStore
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.core.knowledge.AppKnowledgeDraft
import com.wangzan101.mika.core.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.knowledge.LearnedNote
import com.wangzan101.mika.core.knowledge.Surface
import com.agent.use.ui.Anchors
import com.agent.use.ui.Route
import com.agent.use.ui.RouteStep
import com.wangzan101.mika.core.replay.RouteStore
import com.agent.use.ui.Selector
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.SkillStatus
import com.agent.use.ui.ScreenSig
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.task.MapSkills
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppExperienceViewModelTest {
    @get:Rule val texts = ChineseTexts()

    private class FakeRouteStore : RouteStore {
        private val map = linkedMapOf<String, Route>()
        override fun all(): List<Route> = map.values.toList()
        override fun put(r: Route) { map[r.id] = r }
        override fun remove(id: String) { map.remove(id) }
        override fun clear() { map.clear() }
        override fun count(): Int = map.size
    }

    private class FakeDraftStore(vararg drafts: AppKnowledgeDraft) : AppKnowledgeDraftStore {
        private val items = drafts.toMutableList()
        override fun all(): List<AppKnowledgeDraft> = items.toList()
        override fun putPending(draft: AppKnowledgeDraft): Boolean = items.add(draft)
        override fun approve(id: String): Boolean = true
        override fun reject(id: String): Boolean = true
        override fun recordTrial(appName: String, good: Boolean): AppKnowledgeDraft? = null
        override fun notes(): Map<String, LearnedNote> = emptyMap()
    }

    private fun sampleStep(pkg: String, element: String = "提交", beforePage: String = "首页", afterPage: String = "结果"): RouteStep {
        val sel = Selector("button", element, "btn_submit", listOf(10, 20, 100, 80), 0, true)
        return RouteStep("phone_tap", """{"element":"$element"}""", sel, Anchors(pkg, listOf(beforePage)), Anchors(pkg, listOf(afterPage)))
    }

    private fun route(id: String, pkg: String, template: String, lastUsedMs: Long, disabled: Boolean = false): Route {
        return Route(
            id = id,
            template = template,
            slots = 0,
            packages = listOf(pkg),
            appVersions = mapOf(pkg to "1.0.0"),
            steps = listOf(sampleStep(pkg)),
            successes = 3,
            consecutiveMisses = 1,
            missStep = 0,
            disabled = disabled,
            lastUsedMs = lastUsedMs,
        )
    }

    private fun TestScope.createVm(
        atlasStore: InMemoryAtlasStore = InMemoryAtlasStore(),
        routeStore: FakeRouteStore = FakeRouteStore(),
        skills: MapSkills = MapSkills(),
        drafts: FakeDraftStore = FakeDraftStore(),
        labels: Map<String, String> = emptyMap(),
    ): AppExperienceViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val runtime = AtlasRuntime(
            atlasStore = atlasStore,
            routeStore = routeStore,
            selfPackage = "com.wangzan101.mika",
            appLabelOf = { labels[it] },
            clock = { 5000L },
        )
        return AppExperienceViewModel(
            runtime = runtime,
            skills = skills,
            appDrafts = drafts,
            bundledApps = KnowledgeStore.loadBundled().filter { it.surface == Surface.Android },
            toolLabels = { tool -> if (tool == "phone_tap") "点击" else tool },
            scope = backgroundScope,
            ioDispatcher = dispatcher,
        ).also { runCurrent() }
    }

    @Test
    fun aggregatesAppsAcrossAtlasRoutesKnowledgeAndSkillsAndFilters() = runTest {
        val atlasStore = InMemoryAtlasStore().apply {
            put(
                AppAtlas(
                    packageName = "com.example.shop",
                    appLabel = "示例商城",
                    versionsSeen = listOf("2.0.0"),
                    screens = listOf(
                        AtlasScreen("HomeActivity#1111", shortActivity = "HomeActivity", title = "首页", titleSource = AtlasScreen.TITLE_LLM, hits = 5, lastSeenMs = 4000L),
                    ),
                    lastUsedMs = 4000L,
                ),
            )
        }
        val routeStore = FakeRouteStore().apply {
            put(route("r1", "com.example.shop", "打开购物车", lastUsedMs = 4500L))
        }
        val skills = MapSkills(
            PersonalSkill(
                name = "wechat-pay-bill",
                markdown = "---\nname: wechat-pay-bill\ndisplay: 查微信账单\ndescription: 查看最近账单\napps: [\"wechat\"]\n---\n## 入口\n打开微信",
                status = SkillStatus.active,
                learnedFrom = "run1",
                replacesDraftOf = null,
                createdAt = 3000L,
                outcomes = listOf("verified"),
            ),
            PersonalSkill(
                name = "direct-pkg-skill",
                markdown = "---\nname: direct-pkg-skill\ndisplay: 直接包名技能\ndescription: 描述\napps: [\"com.example.direct\"]\n---\n## 入口\n直接打开",
                status = SkillStatus.trial,
                learnedFrom = "run2",
                replacesDraftOf = null,
                createdAt = 2000L,
                outcomes = emptyList(),
            ),
        )
        val drafts = FakeDraftStore(
            AppKnowledgeDraft(
                id = "d1",
                appName = "wechat",
                packageName = "com.tencent.mm",
                markdown = "- 账单在钱包里",
                sourceRun = "run3",
                stage = AppKnowledgeStage.Verified,
                createdAt = 3500L,
                display = "微信",
            ),
        )

        val vm = createVm(atlasStore, routeStore, skills, drafts)
        val ui = vm.ui.value
        assertFalse(ui.loading)
        assertEquals(3, ui.overview.appCount)
        assertEquals(1, ui.overview.screenCount)
        assertEquals(1, ui.overview.routeCount)

        val shop = ui.apps.first { it.packageName == "com.example.shop" }
        assertEquals("示例商城", shop.appLabel)
        assertEquals(1, shop.screenCount)
        assertEquals(1, shop.routeCount)
        assertEquals(0, shop.knowledgeCount)

        val wechat = ui.apps.first { it.packageName == "com.tencent.mm" }
        assertEquals("微信", wechat.appLabel)
        assertEquals(2, wechat.knowledgeCount)
        assertEquals(3500L, wechat.lastUsedMs)

        assertTrue(ui.apps.any { it.packageName == "com.example.direct" && it.knowledgeCount == 1 })

        vm.search("wechat")
        assertEquals(listOf("com.tencent.mm"), vm.ui.value.filteredApps.map { it.packageName })

        vm.search("tencent")
        assertEquals(listOf("com.tencent.mm"), vm.ui.value.filteredApps.map { it.packageName })

        vm.search("商城")
        assertEquals(listOf("com.example.shop"), vm.ui.value.filteredApps.map { it.packageName })
    }

    @Test
    fun detailExposesAtlasScreensRoutesControlsEdgesAndSupportsRouteActions() = runTest {
        val s1 = AtlasScreen(
            sigId = "HomeActivity#aaaa",
            shortActivity = "HomeActivity",
            title = "首页",
            titleSource = AtlasScreen.TITLE_LLM,
            appVersion = "1.2.0",
            hits = 10,
            lastSeenMs = 2000L,
            unverifiedAfterUpgrade = false,
            controls = listOf(
                AtlasControl(Selector("button", "搜索", "btn_search", listOf(0, 0, 100, 100), 0, true), meaning = "搜索入口", used = 6, noEffect = 1),
                AtlasControl(Selector("icon", "", "iv_more", listOf(0, 0, 50, 50), 0, true), meaning = "", used = 2, noEffect = 0),
            ),
        )
        val s2 = AtlasScreen(
            sigId = "DetailActivity#bbbb",
            shortActivity = "DetailActivity",
            title = "",
            titleSource = AtlasScreen.TITLE_ACTIVITY,
            appVersion = "1.1.0",
            hits = 3,
            lastSeenMs = 1000L,
            unverifiedAfterUpgrade = true,
        )
        val edge = AtlasEdge(
            fromSigId = s1.sigId,
            toSigId = s2.sigId,
            tool = "phone_tap",
            target = Selector("button", "搜索", "btn_search", listOf(0, 0, 100, 100), 0, true),
            ok = 4,
            miss = 1,
        )
        val atlasStore = InMemoryAtlasStore().apply {
            put(
                AppAtlas(
                    packageName = "com.example.app",
                    appLabel = "测试应用",
                    versionsSeen = listOf("1.1.0", "1.2.0"),
                    screens = listOf(s1, s2),
                    edges = listOf(edge),
                    lastUsedMs = 2000L,
                ),
            )
        }
        val routeStore = FakeRouteStore().apply {
            put(route("r1", "com.example.app", "搜一下天气", lastUsedMs = 2000L))
        }

        val vm = createVm(atlasStore, routeStore)
        vm.openApp("com.example.app")
        runCurrent()

        val detail = vm.ui.value.selectedDetail
        assertNotNull(detail)
        assertEquals(2, detail!!.screens.size)
        assertEquals("首页", detail.screens[0].displayTitle)
        assertEquals("DetailActivity", detail.screens[1].displayTitle)
        assertTrue(detail.screens[1].unverifiedAfterUpgrade)

        assertEquals("搜索入口", detail.screens[0].controls[0].label)
        assertEquals("icon · iv_more", detail.screens[0].controls[1].label)
        assertEquals(1, detail.screens[0].outgoingEdges.size)
        assertEquals("点击", detail.screens[0].outgoingEdges[0].toolLabel)
        assertEquals("DetailActivity", detail.screens[0].outgoingEdges[0].toScreenTitle)
        assertEquals(4, detail.screens[0].outgoingEdges[0].ok)
        assertEquals(1, detail.screens[0].outgoingEdges[0].miss)

        vm.toggleRouteDisabled("r1", true)
        runCurrent()
        assertTrue(vm.ui.value.selectedDetail!!.routes.single().disabled)

        vm.removeRoute("r1")
        runCurrent()
        assertTrue(vm.ui.value.selectedDetail!!.routes.isEmpty())
    }

    @Test
    fun importValidatesAndAppliesMergeOrReplaceAndClearUpdatesState() = runTest {
        val atlasStore = InMemoryAtlasStore().apply {
            put(
                AppAtlas(
                    packageName = "com.example.app",
                    appLabel = "测试应用",
                    screens = listOf(
                        AtlasScreen("OldActivity#1111", shortActivity = "OldActivity", title = "旧页面", titleSource = AtlasScreen.TITLE_LLM, hits = 1, lastSeenMs = 100L),
                    ),
                    lastUsedMs = 100L,
                ),
            )
        }
        val routeStore = FakeRouteStore()
        val vm = createVm(atlasStore, routeStore)

        vm.openImportDialog()
        vm.updateImportText("{bad json")
        runCurrent()
        assertNull(vm.ui.value.importState?.preview)
        assertNotNull(vm.ui.value.importState?.error)

        val bundle = AtlasShare.export(
            AppAtlas(
                packageName = "com.example.app",
                appLabel = "测试应用",
                screens = listOf(
                    AtlasScreen("NewActivity#2222", shortActivity = "NewActivity", title = "新页面", titleSource = AtlasScreen.TITLE_LLM, hits = 4, lastSeenMs = 200L),
                ),
            ),
            routes = listOf(route("r_imp", "com.example.app", "导入路线", lastUsedMs = 200L)),
            exportedAtMs = 300L,
        ).encode()

        vm.updateImportText(bundle)
        runCurrent()
        val preview = vm.ui.value.importState?.preview
        assertNotNull(preview)
        assertTrue(preview!!.hasLocalConflict)
        assertEquals(1, preview.screenCount)
        assertEquals(1, preview.routeCount)

        // 剪贴板为空等提示不应清空已填写的 JSON 与预览
        vm.setImportError("剪贴板里没有文字。")
        assertEquals(bundle, vm.ui.value.importState?.text)
        assertNotNull(vm.ui.value.importState?.preview)
        assertEquals("剪贴板里没有文字。", vm.ui.value.importState?.error)

        vm.setImportConflictMode(AtlasShare.ConflictMode.Replace)
        vm.confirmImport()
        runCurrent()

        assertNull(vm.ui.value.importState)
        assertTrue(vm.ui.value.message!!.contains("测试应用"))
        val updatedAtlas = atlasStore.get("com.example.app")!!
        assertEquals(listOf("NewActivity#2222"), updatedAtlas.screens.map { it.sigId })
        assertEquals(1, routeStore.count())

        var clearedCallback = false
        vm.clearApp("com.example.app") { clearedCallback = true }
        runCurrent()
        assertTrue(clearedCallback)
        assertTrue(vm.ui.value.apps.isEmpty())
    }

    @Test
    fun shortcutsAggregateToggleRemoveAndImportPreview() = runTest {
        val pkg = "com.example.app"
        val steps = listOf(
            sampleStep(pkg, "搜索", "首页", "搜索页"),
            sampleStep(pkg, "确认", "搜索页", "结果页"),
        )
        val scActive = AtlasShortcut(
            id = "sc_active",
            name = "搜索下单",
            description = "搜索 {1} 并进入下单页",
            slots = 1,
            entrySigId = "MainActivity#1111",
            steps = steps,
            successes = 4,
            consecutiveMisses = 0,
            missStep = null,
            disabled = false,
            appVersion = "1.2.0",
            lastUsedMs = 3000L,
        )
        val scDisabled = AtlasShortcut(
            id = "sc_disabled",
            name = "旧快捷",
            description = "",
            slots = 0,
            entrySigId = "MissingActivity#dead",
            steps = steps,
            successes = 2,
            consecutiveMisses = 2,
            missStep = 1,
            disabled = true,
            appVersion = "1.0.0",
            lastUsedMs = 1500L,
        )
        val atlasStore = InMemoryAtlasStore().apply {
            put(
                AppAtlas(
                    packageName = pkg,
                    appLabel = "测试应用",
                    versionsSeen = listOf("1.2.0"),
                    screens = listOf(
                        AtlasScreen("MainActivity#1111", shortActivity = "MainActivity", title = "首页", titleSource = AtlasScreen.TITLE_LLM, hits = 5, lastSeenMs = 1000L),
                    ),
                    shortcuts = listOf(scActive, scDisabled),
                    lastUsedMs = 2000L,
                ),
            )
        }
        val vm = createVm(atlasStore, FakeRouteStore())

        assertEquals(2, vm.ui.value.overview.shortcutCount)
        val appSummary = vm.ui.value.apps.single()
        assertEquals(2, appSummary.shortcutCount)
        assertEquals(1, appSummary.enabledShortcutCount)

        vm.openApp(pkg)
        runCurrent()
        val detail = vm.ui.value.selectedDetail!!
        assertEquals(listOf("sc_active", "sc_disabled"), detail.shortcuts.map { it.id })
        assertEquals("首页", detail.shortcuts[0].entryScreenTitle)
        assertEquals(1, detail.shortcuts[0].slotCount)
        assertEquals(2, detail.shortcuts[0].stepCount)
        assertEquals("1.2.0", detail.shortcuts[0].appVersion)
        assertEquals(ScreenSig.shortHashTag("MissingActivity#dead"), detail.shortcuts[1].entryScreenTitle)
        assertEquals(2, detail.shortcuts[1].consecutiveMisses)
        assertEquals(1, detail.shortcuts[1].missStep)

        vm.toggleShortcutDisabled(pkg, "sc_active", true)
        runCurrent()
        assertTrue(vm.ui.value.selectedDetail!!.shortcuts.first { it.id == "sc_active" }.disabled)
        assertEquals(0, vm.ui.value.apps.single().enabledShortcutCount)

        vm.toggleShortcutDisabled(pkg, "sc_disabled", false)
        runCurrent()
        val reEnabled = vm.ui.value.selectedDetail!!.shortcuts.first { it.id == "sc_disabled" }
        assertFalse(reEnabled.disabled)
        assertEquals(0, reEnabled.consecutiveMisses)
        assertNull(reEnabled.missStep)

        vm.removeShortcut(pkg, "sc_active")
        runCurrent()
        assertEquals(listOf("sc_disabled"), vm.ui.value.selectedDetail!!.shortcuts.map { it.id })
        assertEquals(1, vm.ui.value.overview.shortcutCount)

        val otherPkg = "com.example.other"
        val otherSteps = listOf(
            sampleStep(otherPkg, "打开分类", "首页", "列表页"),
            sampleStep(otherPkg, "筛选", "列表页", "结果页"),
        )
        val encoded = AtlasShare.export(
            AppAtlas(
                packageName = otherPkg,
                appLabel = "其他应用",
                screens = listOf(
                    AtlasScreen("OtherHome#2222", shortActivity = "OtherHome", title = "其他首页", titleSource = AtlasScreen.TITLE_LLM, hits = 3, lastSeenMs = 4000L),
                ),
                shortcuts = listOf(
                    AtlasShortcut(
                        id = "sc_exp_ok",
                        name = "导出可用快捷",
                        entrySigId = "OtherHome#2222",
                        steps = otherSteps,
                        successes = 3,
                        lastUsedMs = 4000L,
                    ),
                    AtlasShortcut(
                        id = "sc_exp_disabled",
                        name = "停用不导出",
                        entrySigId = "OtherHome#2222",
                        steps = otherSteps,
                        disabled = true,
                        lastUsedMs = 1000L,
                    ),
                ),
            ),
            exportedAtMs = 4500L,
        ).encode()

        vm.openImportDialog()
        vm.updateImportText(encoded)
        runCurrent()
        assertEquals(1, vm.ui.value.importState?.preview?.shortcutCount)

        vm.confirmImport()
        runCurrent()
        assertNull(vm.ui.value.importState)
        assertEquals(listOf("sc_exp_ok"), atlasStore.get(otherPkg)!!.shortcuts.map { it.id })
        assertTrue(vm.ui.value.message!!.contains("1 个快捷"))
    }

    @Test
    fun phase3ToolLabelsResolveInChineseAndEnglish() {
        val tools = knownTools()
        val labels = ToolLabels(tools)
        assertTrue("执行快捷流程" in ToolLabels.TOOL_LABELS)
        assertTrue("自动寻路导航" in ToolLabels.TOOL_LABELS)
        assertEquals("执行快捷流程", labels.label("phone_run_shortcut"))
        assertEquals("自动寻路导航", labels.label("phone_navigate_to"))

        val prev = UiText.source
        try {
            UiText.source = ResFileTexts.english
            assertEquals("Run shortcut", labels.label("phone_run_shortcut"))
            assertEquals("Auto-navigate", labels.label("phone_navigate_to"))
        } finally {
            UiText.source = prev
        }
    }
}
