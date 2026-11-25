package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import com.wangzan101.mika.atlas.AtlasRuntime
import com.agent.use.ui.AppAtlas
import com.agent.use.ui.AtlasControl
import com.agent.use.ui.AtlasEdge
import com.agent.use.ui.AtlasScreen
import com.wangzan101.mika.core.atlas.AtlasShare
import com.agent.use.ui.AtlasShortcut
import com.agent.use.ui.InMemoryAtlasStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeDraft
import com.wangzan101.mika.core.knowledge.AppKnowledgeDraftStore
import com.wangzan101.mika.core.knowledge.AppKnowledgeStage
import com.wangzan101.mika.core.knowledge.LearnedNote
import com.agent.use.ui.Anchors
import com.agent.use.ui.Route
import com.agent.use.ui.RouteStep
import com.wangzan101.mika.core.replay.RouteStore
import com.agent.use.ui.Selector
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.settings.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 「App 经验」列表页与单 App 详情页 Compose 仪器测试：聚合、搜索、展开页面控件/跳转、路线/快捷启停删除、导入与清空确认。 */
@RunWith(AndroidJUnit4::class)
class AppExperienceScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

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

    private class FakeSkills(vararg s: PersonalSkill) : PersonalSkills {
        private val active = linkedMapOf<String, PersonalSkill>().apply { s.forEach { put(it.name, it) } }
        private val drafts = linkedMapOf<String, PersonalSkill>()
        override fun list() = active.values.toList()
        override fun get(name: String) = active[name]
        override fun put(s: PersonalSkill) { active[s.name] = s }
        override fun remove(name: String) { active.remove(name) }
        override fun update(name: String, transform: (PersonalSkill) -> PersonalSkill?): PersonalSkill? =
            active[name]?.let(transform)?.also { active[it.name] = it }
        override fun pending() = drafts.values.toList()
        override fun drafts(): Map<String, PersonalSkill> = drafts.toMap()
        override fun putPending(id: String, s: PersonalSkill) { drafts[id] = s.copy(status = SkillStatus.pending) }
        override fun approve(id: String, editedMarkdown: String?): PersonalSkill? = null
        override fun reject(id: String) { drafts.remove(id) }
    }

    private fun sampleStep(pkg: String, element: String = "提交", beforePage: String = "首页", afterPage: String = "结果页"): RouteStep {
        val sel = Selector("button", element, "btn_submit", listOf(10, 20, 100, 80), 0, true)
        return RouteStep("phone_tap", """{"element":"$element"}""", sel, Anchors(pkg, listOf(beforePage)), Anchors(pkg, listOf(afterPage)))
    }

    private fun sampleRoute(
        id: String,
        pkg: String,
        template: String,
        lastUsedMs: Long = 2000L,
        disabled: Boolean = false,
        consecutiveMisses: Int = 0,
        missStep: Int? = null,
    ): Route = Route(
        id = id,
        template = template,
        slots = 0,
        packages = listOf(pkg),
        appVersions = mapOf(pkg to "1.0.0"),
        steps = listOf(sampleStep(pkg)),
        successes = 3,
        consecutiveMisses = consecutiveMisses,
        missStep = missStep,
        disabled = disabled,
        lastUsedMs = lastUsedMs,
    )

    private fun show(
        atlasStore: InMemoryAtlasStore = InMemoryAtlasStore(),
        routeStore: FakeRouteStore = FakeRouteStore(),
        skills: FakeSkills = FakeSkills(),
        drafts: FakeDraftStore = FakeDraftStore(),
        labels: Map<String, String> = emptyMap(),
    ): AppExperienceViewModel {
        val dispatcher = Dispatchers.Main.immediate
        val runtime = AtlasRuntime(
            atlasStore = atlasStore,
            routeStore = routeStore,
            selfPackage = "com.wangzan101.mika",
            appLabelOf = { labels[it] },
            clock = { 5000L },
        )
        val vm = AppExperienceViewModel(
            runtime = runtime,
            skills = skills,
            appDrafts = drafts,
            bundledApps = emptyList(),
            toolLabels = { tool -> if (tool == "phone_tap") "点击" else tool },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            ioDispatcher = dispatcher,
        )
        compose.setContent {
            AppExperienceScreen(
                vm = vm,
                onPrepareShareUri = { null },
                onBack = {},
            )
        }
        compose.waitForIdle()
        return vm
    }

    @Test
    fun useAndRecordSwitchesToggleIndependently() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val flags = FeatureFlags.get(context)
        val oldHints = flags.atlasHintsEnabled()
        val oldLearn = flags.atlasLearnEnabled()
        try {
            flags.setAtlasHintsEnabled(true)
            flags.setAtlasLearnEnabled(true)
            show()

            compose.onNodeWithTag("app-exp-learn-switch").assertIsOn().performClick()
            compose.onNodeWithTag("app-exp-learn-switch").assertIsOff()
            assertFalse(flags.atlasLearnEnabled())
            assertTrue(flags.atlasHintsEnabled())

            compose.onNodeWithTag("app-exp-hints-switch").assertIsOn().performClick()
            compose.onNodeWithTag("app-exp-hints-switch").assertIsOff()
            assertFalse(flags.atlasHintsEnabled())
            assertFalse(flags.atlasLearnEnabled())
        } finally {
            flags.setAtlasHintsEnabled(oldHints)
            flags.setAtlasLearnEnabled(oldLearn)
        }
    }

    @Test
    fun listShowsAggregatedAppsAndFiltersBySearch() {
        val shopPkg = "com.example.shop"
        val notesPkg = "com.example.notes"
        val atlasStore = InMemoryAtlasStore().apply {
            put(
                AppAtlas(
                    packageName = shopPkg,
                    appLabel = "购物助手",
                    screens = listOf(
                        AtlasScreen("ShopHome#1111", shortActivity = "ShopHome", title = "首页", titleSource = AtlasScreen.TITLE_LLM, hits = 3, lastSeenMs = 2000L),
                    ),
                    shortcuts = listOf(
                        AtlasShortcut(
                            id = "sc_shop",
                            name = "快速搜索",
                            entrySigId = "ShopHome#1111",
                            steps = listOf(sampleStep(shopPkg, "搜索"), sampleStep(shopPkg, "确定")),
                            lastUsedMs = 2500L,
                        ),
                    ),
                    lastUsedMs = 2000L,
                ),
            )
        }
        val routeStore = FakeRouteStore().apply {
            put(sampleRoute("r_shop", shopPkg, "查看订单", lastUsedMs = 2000L))
        }
        val drafts = FakeDraftStore(
            AppKnowledgeDraft(
                id = "d1",
                appName = "shop",
                packageName = shopPkg,
                markdown = "# 购物说明",
                sourceRun = "run1",
                stage = AppKnowledgeStage.Verified,
                createdAt = 1500L,
                display = "购物助手",
            ),
        )
        val skills = FakeSkills(
            PersonalSkill(
                name = "note_tpl",
                markdown = "---\ndisplay: 记录模板\napps:\n  - $notesPkg\n---\n点击右下角加号新建笔记。",
                status = SkillStatus.active,
                learnedFrom = "run2",
                replacesDraftOf = null,
                createdAt = 1000L,
                outcomes = emptyList(),
            ),
        )

        show(
            atlasStore = atlasStore,
            routeStore = routeStore,
            skills = skills,
            drafts = drafts,
            labels = mapOf(notesPkg to "随手笔记"),
        )

        compose.onNodeWithText("App 经验").assertIsDisplayed()
        compose.onNodeWithText("2 个 App").assertIsDisplayed()
        compose.onNodeWithText("购物助手").assertIsDisplayed()
        compose.onNodeWithText("1 个页面 · 1 条路线 · 1 个快捷 · 1 项知识/技能", substring = true).assertIsDisplayed()
        compose.onNodeWithText("随手笔记").assertIsDisplayed()
        compose.onNodeWithText("0 个页面 · 0 条路线 · 1 项知识/技能", substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription(appString(R.string.search)).performClick()
        compose.onNodeWithTag("app-exp-search").performTextInput("notes")
        compose.waitForIdle()

        compose.onNodeWithText("1 个 App").assertIsDisplayed()
        compose.onNodeWithText("随手笔记").assertIsDisplayed()
        compose.onNodeWithText("购物助手").assertDoesNotExist()
    }

    @Test
    fun detailExpandsScreenControlsEdgesAndKnowledge() {
        val pkg = "com.example.app"
        val s1 = AtlasScreen(
            sigId = "MainActivity#1111",
            shortActivity = "MainActivity",
            title = "首页",
            titleSource = AtlasScreen.TITLE_LLM,
            hits = 6,
            appVersion = "1.2.0",
            lastSeenMs = 2000L,
            controls = listOf(
                AtlasControl(Selector("edit", "搜索框", "et_search", emptyList(), 0, true), meaning = "搜索入口", used = 5, noEffect = 1, lastUsedMs = 1800L),
            ),
        )
        val s2 = AtlasScreen(
            sigId = "ResultActivity#2222",
            shortActivity = "ResultActivity",
            title = "结果页",
            titleSource = AtlasScreen.TITLE_LLM,
            hits = 2,
            unverifiedAfterUpgrade = true,
            lastSeenMs = 2100L,
        )
        val edge = AtlasEdge(
            fromSigId = s1.sigId,
            tool = "phone_tap",
            target = Selector("button", "搜一下", "btn_search", emptyList(), 1, true),
            toSigId = s2.sigId,
            ok = 4,
            miss = 1,
            lastAttemptMs = 2100L,
        )
        val atlasStore = InMemoryAtlasStore().apply {
            put(
                AppAtlas(
                    packageName = pkg,
                    appLabel = "测试应用",
                    versionsSeen = listOf("1.2.0"),
                    screens = listOf(s1, s2),
                    edges = listOf(edge),
                    lastUsedMs = 2100L,
                ),
            )
        }
        val skills = FakeSkills(
            PersonalSkill(
                name = "coupon_tip",
                markdown = "---\ndisplay: 领券技巧\ndescription: 先领券再结算\napps:\n  - $pkg\n---\n进入领券中心后点立即领取。",
                status = SkillStatus.active,
                learnedFrom = "run3",
                replacesDraftOf = null,
                createdAt = 1000L,
                outcomes = emptyList(),
            ),
        )

        show(atlasStore = atlasStore, skills = skills)
        compose.onNodeWithText("测试应用").performClick()
        compose.waitForIdle()

        compose.onAllNodesWithText("测试应用").assertCountEquals(2)
        compose.onNodeWithText(pkg).assertIsDisplayed()
        compose.onNodeWithText("见过的版本：1.2.0", substring = true).assertIsDisplayed()
        compose.onNodeWithText("页面与跳转 · 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("待核实").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关键控件").assertDoesNotExist()

        compose.onNodeWithText("首页").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("关键控件").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("搜索入口 (搜索框 · edit)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("页面跳转").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("点击 「搜一下 · button」 → 结果页").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("成功 4 · 失效 1").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("领券技巧").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("进入领券中心后点立即领取。", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun detailTogglesAndDeletesRoutesAndShortcutsAndClearsApp() {
        val pkg = "com.example.app"
        val atlasStore = InMemoryAtlasStore().apply {
            put(
                AppAtlas(
                    packageName = pkg,
                    appLabel = "测试应用",
                    screens = listOf(
                        AtlasScreen("MainActivity#1111", shortActivity = "MainActivity", title = "首页", titleSource = AtlasScreen.TITLE_LLM, hits = 3, lastSeenMs = 2000L),
                    ),
                    shortcuts = listOf(
                        AtlasShortcut(
                            id = "sc1",
                            name = "一键签到",
                            description = "从首页进入签到页",
                            slots = 1,
                            entrySigId = "MainActivity#1111",
                            steps = listOf(sampleStep(pkg, "签到"), sampleStep(pkg, "领取")),
                            successes = 4,
                            consecutiveMisses = 2,
                            missStep = 1,
                            disabled = false,
                            appVersion = "1.2.0",
                            lastUsedMs = 2500L,
                        ),
                    ),
                    lastUsedMs = 2500L,
                ),
            )
        }
        val routeStore = FakeRouteStore().apply {
            put(
                sampleRoute(
                    id = "r1",
                    pkg = pkg,
                    template = "打开首页查看通知",
                    lastUsedMs = 2000L,
                    consecutiveMisses = 1,
                    missStep = 0,
                ),
            )
        }

        show(atlasStore = atlasStore, routeStore = routeStore)
        compose.onNodeWithText("测试应用").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("打开首页查看通知").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("连续失误 1 次 · 第 1 步").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("一键签到").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 个参数槽", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("入口：首页", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("连续失误 2 次 · 第 2 步").performScrollTo().assertIsDisplayed()

        // 停用并验证状态更新
        compose.onNodeWithTag("shortcut-sc1-switch").performScrollTo().assertIsOn().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("shortcut-sc1-switch").assertIsOff()
        assertTrue(atlasStore.get(pkg)!!.findShortcut("sc1")!!.disabled)

        compose.onNodeWithTag("route-r1-switch").performScrollTo().assertIsOn().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("route-r1-switch").assertIsOff()
        assertTrue(routeStore.all().single().disabled)

        // 删除快捷先取消再确认
        compose.onNodeWithTag("shortcut-sc1-delete").performScrollTo().performClick()
        compose.awaitOne("删除这条子流程快捷？", inDialog = true).assertIsDisplayed()
        compose.clickInDialog(appString(R.string.cancel))
        compose.onNodeWithText("一键签到").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("shortcut-sc1-delete").performScrollTo().performClick()
        compose.clickInDialog(appString(R.string.confirm_delete))
        assertTrue(atlasStore.get(pkg)!!.shortcuts.isEmpty())
        compose.onNodeWithText("还没有学到这个 App 的子流程快捷").performScrollTo().assertIsDisplayed()

        // 删除操作路线
        compose.onNodeWithTag("route-r1-delete").performScrollTo().performClick()
        compose.awaitOne("删除这条操作路线？", inDialog = true).assertIsDisplayed()
        compose.clickInDialog(appString(R.string.confirm_delete))
        assertEquals(0, routeStore.count())
        compose.onNodeWithText("还没有这个 App 的操作路线").performScrollTo().assertIsDisplayed()

        // 清空此 App 并返回空列表
        compose.onNodeWithTag("app-exp-clear-app").performScrollTo().performClick()
        compose.awaitOne("清空「测试应用」的经验？", inDialog = true).assertIsDisplayed()
        compose.clickInDialog(appString(R.string.clear))
        assertNull(atlasStore.get(pkg))
        compose.onNodeWithText("还没有学到 App 经验").assertIsDisplayed()
    }

    @Test
    fun importDialogPreviewsReplacesAndClearAllResetsList() {
        val pkg = "com.example.app"
        val atlasStore = InMemoryAtlasStore().apply {
            put(
                AppAtlas(
                    packageName = pkg,
                    appLabel = "测试应用",
                    screens = listOf(
                        AtlasScreen("OldActivity#1111", shortActivity = "OldActivity", title = "旧页面", titleSource = AtlasScreen.TITLE_LLM, hits = 1, lastSeenMs = 100L),
                    ),
                    lastUsedMs = 100L,
                ),
            )
        }
        val routeStore = FakeRouteStore()

        val bundle = AtlasShare.export(
            AppAtlas(
                packageName = pkg,
                appLabel = "测试应用",
                screens = listOf(
                    AtlasScreen("NewActivity#2222", shortActivity = "NewActivity", title = "新页面", titleSource = AtlasScreen.TITLE_LLM, hits = 4, lastSeenMs = 300L),
                ),
                shortcuts = listOf(
                    AtlasShortcut(
                        id = "sc_imp",
                        name = "导入快捷",
                        entrySigId = "NewActivity#2222",
                        steps = listOf(sampleStep(pkg, "点开"), sampleStep(pkg, "确认")),
                        successes = 2,
                        lastUsedMs = 300L,
                    ),
                ),
            ),
            routes = listOf(sampleRoute("r_imp", pkg, "导入路线", lastUsedMs = 300L)),
            exportedAtMs = 400L,
        ).encode()

        show(atlasStore = atlasStore, routeStore = routeStore)

        compose.onNodeWithTag("app-exp-import").performClick()
        compose.awaitOne("导入 App 经验", inDialog = true).assertIsDisplayed()
        compose.onNodeWithTag("app-exp-import-text").performTextReplacement(bundle)
        compose.waitForIdle()

        compose.awaitOne("识别到：测试应用（com.example.app）· 1 个页面 · 1 条路线 · 1 个快捷", inDialog = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.awaitOne("覆盖替换", inDialog = true).performScrollTo().performClick()
        compose.clickInDialog(appString(R.string.app_exp_import_confirm))

        compose.awaitOne("已导入「测试应用」（1 个页面，1 条路线 · 1 个快捷）。", inDialog = true).assertIsDisplayed()
        compose.clickInDialog(appString(R.string.ok_got_it))

        compose.onNodeWithText("测试应用").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("新页面").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("导入路线").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("导入快捷").performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription(appString(R.string.back)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("app-exp-clear-all").performClick()
        compose.awaitOne("清空全部 App 经验？", inDialog = true).assertIsDisplayed()
        compose.clickInDialog(appString(R.string.clear))
        assertTrue(atlasStore.all().isEmpty())
        assertEquals(0, routeStore.count())
        compose.onNodeWithText("还没有学到 App 经验").assertIsDisplayed()
    }
}
