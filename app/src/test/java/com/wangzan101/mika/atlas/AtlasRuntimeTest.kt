package com.wangzan101.mika.atlas

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.agent.use.ui.AppAtlas
import com.agent.use.ui.AtlasEdge
import com.agent.use.ui.AtlasScreen
import com.wangzan101.mika.core.atlas.AtlasShare
import com.agent.use.ui.AtlasShortcut
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.RunEvent
import com.agent.use.ui.Anchors
import com.agent.use.ui.Route
import com.agent.use.ui.RouteStep
import com.agent.use.ui.Selector
import com.agent.use.ui.ScreenSig
import com.wangzan101.mika.replay.FileRouteStore
import com.wangzan101.mika.settings.AesGcmKeyCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.KeyGenerator

class AtlasRuntimeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var now = 1_762_000_000_000L

    private fun newRuntime(
        yieldHook: suspend () -> Unit = {},
        labels: Map<String, String> = mapOf("com.example.app" to "示例应用"),
        versions: Map<String, String> = mapOf("com.example.app" to "3.2.1"),
    ): Triple<AtlasRuntime, FileAtlasStore, FileRouteStore> {
        val atlasStore = FileAtlasStore(File(tmp.root, "atlas"), AesGcmKeyCipher { key }) { now }
        val routeStore = FileRouteStore(File(tmp.root, "routes"))
        val runtime = AtlasRuntime(
            atlasStore = atlasStore,
            routeStore = routeStore,
            selfPackage = "com.wangzan101.mika",
            appLabelOf = { labels[it] },
            appVersionOf = { versions[it] },
            yieldToForeground = yieldHook,
            clock = { now },
        )
        return Triple(runtime, atlasStore, routeStore)
    }

    private fun sig(pkg: String, shortAct: String, fp: Long, anchors: List<String>, rids: List<String> = emptyList()) = ScreenSig(
        sigId = ScreenSig.formatSigId(shortAct, fp),
        activity = "$pkg/$pkg.$shortAct",
        fingerprint = fp,
        anchors = anchors,
        resourceIds = rids,
        packageName = pkg,
        shortActivity = shortAct,
    )

    private fun sampleEvents(pkg: String = "com.example.app", effective: Boolean = true): List<RunEvent> {
        val s1 = sig(pkg, "HomeActivity", 0x0102030405060708L, listOf("首页", "消息"), listOf("tab_home"))
        val s2 = sig(pkg, "SettingsActivity", 0x1122334455667788L, listOf("设置", "关于"), listOf("btn_about"))
        return listOf(
            RunEvent.ActionTarget(
                atMs = 1000L,
                step = 1,
                tool = "phone_tap",
                argsJson = """{"element":"设置"}""",
                target = Selector("Button", "设置", "btn_settings", listOf(800, 100, 960, 180), 0, true),
                before = Anchors(pkg, listOf("首页")),
                after = Anchors(pkg, listOf("设置")),
                effective = effective,
                beforeActivity = s1.activity,
                beforeSig = s1,
                afterActivity = if (effective) s2.activity else s1.activity,
                afterSig = if (effective) s2 else s1,
            ),
        )
    }

    @Test fun learnAfterRunDoneNamesScreensResolvesMetaAndRespectsLearnGate() = runBlocking {
        var yielded = 0
        val (runtime, atlasStore, _) = newRuntime(yieldHook = { yielded++ })
        val events = sampleEvents()
        val s1Id = (events.first() as RunEvent.ActionTarget).beforeSig!!.sigId
        val s2Id = (events.first() as RunEvent.ActionTarget).afterSig!!.sigId

        val lightModel = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8192, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val json = """[
                    {"sigId":"$s1Id","title":"主界面","controls":[{"index":0,"meaning":"打开应用设置"}]},
                    {"sigId":"$s2Id","title":"系统设置"}
                ]"""
                return ModelReply(listOf(Part.Text(json)), StopReason.EndTurn, null)
            }
        }

        val res = runtime.learnAfterRun(
            events = events,
            end = TaskState.Succeeded("已打开设置"),
            outcome = RunOutcome.Done,
            lightModel = lightModel,
        )

        assertTrue(yielded >= 2)
        val saved = atlasStore.get("com.example.app")!!
        assertEquals("示例应用", saved.appLabel)
        assertEquals(listOf("3.2.1"), saved.versionsSeen)
        assertEquals("主界面", saved.findScreen(s1Id)?.title)
        assertEquals(AtlasScreen.TITLE_LLM, saved.findScreen(s1Id)?.titleSource)
        assertEquals("打开应用设置", saved.findScreen(s1Id)?.controls?.first()?.meaning)
        assertEquals("系统设置", saved.findScreen(s2Id)?.title)
        assertEquals(1, saved.edges.single().ok)
        assertEquals(0, saved.edges.single().miss)
        assertEquals(saved, res.updatedByPackage["com.example.app"])
    }

    @Test fun failedRunAndBrokenLlmFallbackToActivityNameWithoutPositiveEdge() = runBlocking {
        val (runtime, atlasStore, _) = newRuntime()
        val events = sampleEvents(effective = true)
        val failingModel = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8192, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                error("network unavailable")
            }
        }

        runtime.learnAfterRun(
            events = events,
            end = TaskState.Failed("未完成", FailKind.Environment),
            outcome = null,
            lightModel = failingModel,
        )

        val saved = atlasStore.get("com.example.app")!!
        assertEquals(2, saved.screens.size)
        assertTrue(saved.edges.isEmpty())
        val s1Id = (events.first() as RunEvent.ActionTarget).beforeSig!!.sigId
        assertEquals("HomeActivity", saved.findScreen(s1Id)?.displayTitle())
        assertEquals(AtlasScreen.TITLE_ACTIVITY, saved.findScreen(s1Id)?.titleSource)

        // 再经历一次无效果操作：记录控件 noEffect 与已有边 miss（先经 LearnGate.done 建一条边）
        runtime.learnAfterRun(events = sampleEvents(effective = true), end = TaskState.Succeeded("ok"), outcome = RunOutcome.Done)
        runtime.learnAfterRun(events = sampleEvents(effective = false), end = TaskState.Failed("err", FailKind.Environment), outcome = null)
        val afterMiss = atlasStore.get("com.example.app")!!
        assertEquals(1, afterMiss.edges.single().ok)
        assertEquals(1, afterMiss.edges.single().miss)
        assertEquals(1, afterMiss.findScreen(s1Id)!!.controls.single().noEffect)
    }

    @Test fun summariesRouteActionsAndBundleExportImportWorkEndToEnd() = runBlocking {
        val (runtime, atlasStore, routeStore) = newRuntime(
            labels = mapOf("com.example.app" to "示例应用", "com.routes.only" to "纯路线应用"),
        )
        runtime.learnAfterRun(events = sampleEvents(), end = TaskState.Succeeded("ok"), outcome = RunOutcome.Done)

        routeStore.put(
            Route(
                id = "route-1",
                template = "打开设置",
                slots = 0,
                packages = listOf("com.example.app"),
                appVersions = mapOf("com.example.app" to "3.2.1"),
                steps = listOf(RouteStep("phone_back", "{}", null, Anchors("com.example.app", listOf("首页")), Anchors("com.example.app", listOf("设置")))),
                lastUsedMs = now + 50,
            ),
        )
        routeStore.put(
            Route(
                id = "route-2",
                template = "查看通知",
                slots = 0,
                packages = listOf("com.routes.only"),
                appVersions = emptyMap(),
                steps = listOf(RouteStep("phone_back", "{}", null, Anchors("com.routes.only", listOf("通知")), Anchors("com.routes.only", listOf("主页")))),
                lastUsedMs = now + 100,
            ),
        )

        val ov = runtime.overview()
        assertEquals(2, ov.appCount)
        assertEquals(2, ov.screenCount)
        assertEquals(2, ov.routeCount)

        val summaries = runtime.appSummaries()
        assertEquals(listOf("com.routes.only", "com.example.app"), summaries.map { it.packageName })
        assertEquals("纯路线应用", summaries.first().appLabel)
        assertEquals(0, summaries.first().screenCount)
        assertEquals(1, summaries.first().routeCount)

        // 停用路线
        assertEquals(true, runtime.toggleRouteDisabled("route-1", true)?.disabled)
        assertEquals(0, runtime.appSummaries().first { it.packageName == "com.example.app" }.enabledRouteCount)
        assertEquals(true, runtime.toggleRouteDisabled("route-1", false)?.disabled == false)

        // 导出 -> 清空 -> 预检 -> 导入（含路线与地图）
        val bundleJson = runtime.exportPackageJson("com.example.app")!!
        val routesOnlyJson = runtime.exportPackageJson("com.routes.only")!!
        runtime.clearApp("com.example.app")
        assertNull(atlasStore.get("com.example.app"))
        assertTrue(runtime.routesForPackage("com.example.app").isEmpty())

        assertTrue(runtime.inspectImportJson(bundleJson) is AtlasShare.LintResult.Valid)
        val imported = runtime.importFromJson(bundleJson, AtlasShare.ConflictMode.Merge)
        assertTrue(imported is AtlasShare.ImportResult.Imported)
        assertEquals(2, atlasStore.get("com.example.app")?.screens?.size)
        assertEquals(1, runtime.routesForPackage("com.example.app").size)

        // 纯路线 App 也可以打包导出并重新导入
        runtime.clearAll()
        assertEquals(0, runtime.overview().appCount)
        assertTrue(runtime.importFromJson(routesOnlyJson, AtlasShare.ConflictMode.Replace) is AtlasShare.ImportResult.Imported)
        assertEquals(1, runtime.routesForPackage("com.routes.only").size)
    }

    @Test fun streamExportImportSizeLimitAndStageShareCleanup() = runBlocking {
        val (runtime, atlasStore, _) = newRuntime()
        runtime.learnAfterRun(events = sampleEvents(), end = TaskState.Succeeded("ok"), outcome = RunOutcome.Done)

        val out = ByteArrayOutputStream()
        assertTrue(runtime.exportToStream(out, "com.example.app").isSuccess)
        val exportedBytes = out.toByteArray()

        // 流导入超限（> 1 MiB）时立即拒绝
        val oversized = ByteArrayInputStream(ByteArray(AtlasShare.MAX_SHARE_BYTES + 10))
        assertTrue(runtime.importFromStream(oversized) is AtlasShare.ImportResult.Rejected)

        // 暂存分享文件并清理超过 24h 的旧文件
        val cacheDir = tmp.newFolder("cache")
        val staleDir = File(cacheDir, "library-viewing/atlas-share").apply { mkdirs() }
        val staleFile = File(staleDir, "old-atlas.json").apply {
            writeText("{}")
            setLastModified(now - 25 * 60 * 60_000L)
        }
        val staged = runtime.stageShareFile(cacheDir, "com.example.app")!!
        assertTrue(staged.isFile)
        assertEquals("com.example.app-atlas.json", staged.name)
        assertFalse(staleFile.exists())

        runtime.clearApp("com.example.app")
        assertTrue(runtime.inspectImportStream(ByteArrayInputStream(exportedBytes)) is AtlasShare.LintResult.Valid)
        assertTrue(runtime.importFromStream(ByteArrayInputStream(exportedBytes)) is AtlasShare.ImportResult.Imported)
        assertEquals(2, atlasStore.get("com.example.app")?.screens?.size)

        // learnRun 读事件抛错时安全返回空结果，不崩
        val failedLoad = runtime.learnRun("bad-run", { error("disk io") })
        assertTrue(failedLoad.updatedByPackage.isEmpty())
    }

    private fun twoStepSearchEvents(pkg: String, query: String, baseMs: Long): List<RunEvent> {
        val home = sig(pkg, "HomeActivity", 0x0102030405060708L, listOf("首页", "分类"), listOf("tab_home"))
        val search = sig(pkg, "SearchActivity", 0x2122232425262728L, listOf("搜索页", "取消"), listOf("edit_query"))
        val results = sig(pkg, "ResultActivity", 0x3132333435363738L, listOf("筛选", "综合"), listOf("list_results"))
        return listOf(
            RunEvent.ActionTarget(
                atMs = baseMs,
                step = 1,
                callId = "c1-$baseMs",
                tool = "phone_tap",
                argsJson = """{"element":"搜索框"}""",
                target = Selector("TextView", "搜索框", "search_bar", listOf(100, 100, 800, 180), 0, true),
                before = Anchors(pkg, listOf("首页", "分类")),
                after = Anchors(pkg, listOf("搜索页", "取消")),
                effective = true,
                beforeActivity = home.activity,
                beforeSig = home,
                afterActivity = search.activity,
                afterSig = search,
            ),
            RunEvent.ToolResult(atMs = baseMs + 20, step = 1, tool = "phone_tap", resultJson = """{"ok":true}""", changed = true, screenshotId = null, callId = "c1-$baseMs"),
            RunEvent.ActionTarget(
                atMs = baseMs + 50,
                step = 2,
                callId = "c2-$baseMs",
                tool = "phone_type_text",
                argsJson = """{"element":"输入关键词","text":"$query"}""",
                target = Selector("EditText", "输入关键词", "edit_query", listOf(100, 120, 700, 200), 0, true),
                before = Anchors(pkg, listOf("搜索页", "取消")),
                after = Anchors(pkg, listOf("筛选", "综合")),
                effective = true,
                beforeActivity = search.activity,
                beforeSig = search,
                afterActivity = results.activity,
                afterSig = results,
            ),
            RunEvent.ToolResult(
                atMs = baseMs + 80,
                step = 2,
                tool = "phone_type_text",
                resultJson = """{"ok":true,"secret":false}""",
                changed = true,
                screenshotId = null,
                callId = "c2-$baseMs",
            ),
        )
    }

    @Test fun learnAfterRunMinesShortcutsSupportsToggleRemoveAndShareRoundTrip() = runBlocking {
        val (runtime, atlasStore, _) = newRuntime()
        val pkg = "com.example.app"

        // 失败运行不触发子流程挖掘或晋升
        runtime.learnAfterRun(
            events = twoStepSearchEvents(pkg, "蓝牙耳机", baseMs = 1000L),
            end = TaskState.Failed("err", FailKind.Environment),
            outcome = null,
            goal = "搜索蓝牙耳机",
        )
        assertTrue(atlasStore.get(pkg)?.shortcuts.orEmpty().isEmpty())
        assertTrue(atlasStore.get(pkg)?.candidatePatterns.orEmpty().isEmpty())

        // 第 1 次成功运行：记下候选模式
        runtime.learnAfterRun(
            events = twoStepSearchEvents(pkg, "降噪耳机", baseMs = 2000L),
            end = TaskState.Succeeded("完成"),
            outcome = RunOutcome.Done,
            goal = "搜索降噪耳机",
        )
        assertTrue(runtime.shortcutsForPackage(pkg).isEmpty())
        assertFalse(atlasStore.get(pkg)?.candidatePatterns.orEmpty().isEmpty())

        // 第 2 次成功运行：共现 >= 2 次且输入参数化为 {1}，晋升为快捷流程
        runtime.learnAfterRun(
            events = twoStepSearchEvents(pkg, "无线鼠标", baseMs = 3000L),
            end = TaskState.Succeeded("完成"),
            outcome = RunOutcome.Done,
            goal = "搜索无线鼠标",
        )
        val shortcuts = runtime.shortcutsForPackage(pkg)
        assertEquals(1, shortcuts.size)
        val sc: AtlasShortcut = shortcuts.single()
        assertEquals(1, sc.slots)
        assertEquals(2, sc.steps.size)
        assertEquals(1, runtime.overview().shortcutCount)
        val summary = runtime.appSummaries().single { it.packageName == pkg }
        assertEquals(1, summary.shortcutCount)
        assertEquals(1, summary.enabledShortcutCount)

        // 记录一次偏离，然后测试停用 -> 导出不含停用项 -> 启用清零偏离 -> 导出导入保留
        atlasStore.update(pkg) { it?.recordShortcutOutcome(sc.id, succeeded = false, missStep = 1, nowMs = now) }
        val disabled = runtime.toggleShortcutDisabled(pkg, sc.id, true)!!
        assertTrue(disabled.disabled)
        assertEquals(0, runtime.appSummaries().single { it.packageName == pkg }.enabledShortcutCount)
        assertTrue(runtime.exportPackageBundle(pkg)?.atlas?.shortcuts.orEmpty().isEmpty())

        val reEnabled = runtime.toggleShortcutDisabled(pkg, sc.id, false)!!
        assertFalse(reEnabled.disabled)
        assertEquals(0, reEnabled.consecutiveMisses)
        assertNull(reEnabled.missStep)

        val bundleJson = runtime.exportPackageJson(pkg)!!
        runtime.clearApp(pkg)
        assertTrue(runtime.shortcutsForPackage(pkg).isEmpty())
        val imported = runtime.importFromJson(bundleJson, AtlasShare.ConflictMode.Merge)
        assertTrue(imported is AtlasShare.ImportResult.Imported)
        assertEquals(1, runtime.shortcutsForPackage(pkg).size)

        assertTrue(runtime.removeShortcut(pkg, sc.id))
        assertFalse(runtime.removeShortcut(pkg, sc.id))
        assertTrue(runtime.shortcutsForPackage(pkg).isEmpty())
    }

    @Test fun learnAfterRunMinesShortcutsFromStoredRoutes() = runBlocking {
        val (runtime, atlasStore, routeStore) = newRuntime()
        val pkg = "com.example.app"

        val step1 = RouteStep(
            tool = "phone_tap",
            argsJson = """{"element":"发现"}""",
            target = Selector("TextView", "发现", "tab_discover", listOf(200, 900, 400, 980), 0, true),
            before = Anchors(pkg, listOf("首页", "消息")),
            after = Anchors(pkg, listOf("发现", "附近")),
        )
        val step2 = RouteStep(
            tool = "phone_tap",
            argsJson = """{"element":"设置"}""",
            target = Selector("Button", "设置", "btn_about", listOf(800, 100, 960, 180), 0, true),
            before = Anchors(pkg, listOf("发现", "附近")),
            after = Anchors(pkg, listOf("设置", "关于")),
        )
        routeStore.put(
            Route(
                id = "r-a",
                template = "从发现打开设置A",
                slots = 0,
                packages = listOf(pkg),
                appVersions = emptyMap(),
                steps = listOf(step1, step2),
                lastUsedMs = now + 10,
            ),
        )
        routeStore.put(
            Route(
                id = "r-b",
                template = "从发现打开设置B",
                slots = 0,
                packages = listOf(pkg),
                appVersions = emptyMap(),
                steps = listOf(step1, step2),
                lastUsedMs = now + 20,
            ),
        )

        runtime.learnAfterRun(
            events = sampleEvents(pkg, effective = true),
            end = TaskState.Succeeded("ok"),
            outcome = RunOutcome.Done,
        )

        val mined = atlasStore.get(pkg)?.shortcuts.orEmpty()
        assertFalse(mined.isEmpty())
        assertEquals(2, mined.first().steps.size)
        assertEquals(0, runtime.activeLearnCount.value)
        assertTrue(runtime.awaitLearningIdle(1_000L))
    }

    @Test
    fun renameAndRemoveScreenThroughRuntime() {
        val (runtime, atlasStore, _) = newRuntime()
        val pkg = "com.example.app"
        val home = sig(pkg, "HomeActivity", 0x000000000000AA11L, listOf("首页"))
        val search = sig(pkg, "SearchActivity", 0x000000000000BB22L, listOf("搜索"))
        atlasStore.put(
            AppAtlas(
                packageName = pkg,
                screens = listOf(
                    AtlasScreen(
                        sigId = home.sigId,
                        activity = home.activity,
                        shortActivity = home.shortActivity,
                        fingerprint = home.fingerprint,
                        anchors = home.anchors,
                        resourceIds = home.resourceIds,
                        title = "HomeActivity",
                    ),
                    AtlasScreen(
                        sigId = search.sigId,
                        activity = search.activity,
                        shortActivity = search.shortActivity,
                        fingerprint = search.fingerprint,
                        anchors = search.anchors,
                        resourceIds = search.resourceIds,
                        title = "SearchActivity",
                    ),
                ),
                edges = listOf(AtlasEdge(home.sigId, search.sigId, "phone_tap", null, ok = 2)),
            ),
        )

        assertTrue(runtime.renameScreen(pkg, home.sigId, "首页"))
        val renamed = atlasStore.get(pkg)!!.findScreen(home.sigId)!!
        assertEquals("首页", renamed.title)
        assertEquals(AtlasScreen.TITLE_USER, renamed.titleSource)

        assertTrue(runtime.removeScreen(pkg, search.sigId))
        val after = atlasStore.get(pkg)!!
        assertEquals(1, after.screens.size)
        assertTrue(after.edges.isEmpty())

        assertFalse(runtime.renameScreen(pkg, "missing#0000", "不存在"))
    }
}
