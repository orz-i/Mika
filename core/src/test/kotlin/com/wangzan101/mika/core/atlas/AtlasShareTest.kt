package com.wangzan101.mika.core.atlas

import com.agent.use.ui.Anchors
import com.agent.use.ui.Route
import com.wangzan101.mika.core.replay.RouteShare
import com.agent.use.ui.RouteStep
import com.wangzan101.mika.core.replay.RouteStore
import com.agent.use.ui.Selector
import com.wangzan101.mika.core.testing.TOOLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.agent.use.ui.MAX_ATLAS_SCREENS

class AtlasShareTest {
    private class MemRouteStore : RouteStore {
        private val list = mutableListOf<Route>()
        override fun all(): List<Route> = list.toList()
        override fun put(r: Route) {
            list.removeAll { it.id == r.id }
            list += r
        }
        override fun remove(id: String) { list.removeAll { it.id == id } }
        override fun clear() { list.clear() }
        override fun count(): Int = list.size
    }

    private val pkg = "com.example.notes"
    private val s1 = AtlasScreen(
        sigId = "Home#1111111111111111",
        activity = "com.example.notes/.HomeActivity",
        shortActivity = "HomeActivity",
        fingerprint = 0x1111111111111111L,
        anchors = listOf("备忘录", "全部"),
        resourceIds = listOf("btn_new"),
        title = "笔记列表",
        titleSource = AtlasScreen.TITLE_LLM,
        hits = 5,
        lastSeenMs = 99999L,
        unverifiedAfterUpgrade = true,
        controls = listOf(AtlasControl(Selector("button", "新建", "btn_new", listOf(800, 900, 999, 999), 0), "新建笔记", used = 5, lastUsedMs = 99999L)),
    )
    private val s2 = AtlasScreen(
        sigId = "Editor#2222222222222222",
        activity = "com.example.notes/.EditorActivity",
        shortActivity = "EditorActivity",
        fingerprint = 0x2222222222222222L,
        anchors = listOf("新笔记"),
        title = "编辑页",
        titleSource = AtlasScreen.TITLE_LLM,
        hits = 3,
        lastSeenMs = 88888L,
    )
    private val edge = AtlasEdge(s1.sigId, s2.sigId, "phone_tap", s1.controls[0].selector, ok = 4, miss = 1, lastOkMs = 99999L, lastAttemptMs = 99999L, appVersion = "1.2")
    private val sampleRoute = Route(
        id = "r1",
        template = "新建一条笔记{1}",
        slots = 1,
        packages = listOf(pkg),
        appVersions = mapOf(pkg to "1.2"),
        steps = listOf(RouteStep("phone_tap", "{}", s1.controls[0].selector, Anchors(pkg, listOf("备忘录")), Anchors(pkg, listOf("新笔记")))),
        successes = 7,
        consecutiveMisses = 2,
        missStep = 1,
        disabled = false,
        lastUsedMs = 99999L,
    )

    @Test
    fun exportStripsLocalTimestampsAndSensitiveContentAndImportsRoundtrip() {
        val dirtyAtlas = AppAtlas(
            packageName = pkg,
            appLabel = "备忘录",
            versionsSeen = listOf("1.2"),
            screens = listOf(
                s1.copy(anchors = listOf("备忘录", "密码 123456")),
                s2.copy(title = "验证码 654321"),
            ),
            edges = listOf(edge),
            lastUsedMs = 99999L,
        )
        val disabledRoute = sampleRoute.copy(id = "r_dis", disabled = true)
        val otherPkgRoute = sampleRoute.copy(id = "r_other", packages = listOf("com.other.app"), steps = emptyList())

        val share = AtlasShare.export(dirtyAtlas, routes = listOf(sampleRoute, disabledRoute, otherPkgRoute), exportedAtMs = 1234L)
        assertEquals(0L, share.atlas.lastUsedMs)
        assertFalse(share.atlas.screens.any { it.unverifiedAfterUpgrade })
        assertEquals(listOf("备忘录"), share.atlas.findScreen(s1.sigId)!!.anchors)
        // 含敏感信息的标题已被清洗回落到 Activity 短名
        assertEquals("EditorActivity", share.atlas.findScreen(s2.sigId)!!.title)
        assertEquals(1, share.routes.size)
        assertEquals(0, share.routes[0].route.successes)
        assertEquals(0L, share.routes[0].route.lastUsedMs)

        val atlasStore = InMemoryAtlasStore()
        val routeStore = MemRouteStore()
        val res = AtlasShare.importInto(share.encode(), atlasStore, routeStore, tools = TOOLS, nowMs = 5000L)
        assertTrue(res is AtlasShare.ImportResult.Imported)
        val imported = res as AtlasShare.ImportResult.Imported
        assertEquals(2, imported.atlas.screens.size)
        assertEquals(1, imported.atlas.edges.size)
        assertEquals(1, imported.routesImported)
        assertEquals(1, routeStore.forPackage(pkg).size)
    }

    @Test
    fun lintRejectsTamperedMalformedSensitiveAndOversizedShares() {
        val atlas = AppAtlas(pkg, "备忘录", screens = listOf(s1, s2), edges = listOf(edge))
        val valid = AtlasShare.export(atlas, listOf(sampleRoute))

        // 指纹被篡改
        val tampered = valid.copy(atlas = valid.atlas.copy(appLabel = "假冒"))
        assertTrue(AtlasShare.lint(tampered, TOOLS).any { "fingerprint" in it })

        // 包名非法 / 自家包名
        val selfShare = AtlasShare.export(atlas.copy(packageName = AtlasLearner.DEFAULT_SELF_PACKAGE))
        assertTrue(AtlasShare.lint(selfShare).any { "self package" in it })
        val badPkg = AtlasShare.export(atlas.copy(packageName = "invalid"))
        assertTrue(AtlasShare.lint(badPkg).any { "invalid packageName" in it })

        // 悬空边（手造）
        val danglingAtlas = AppAtlas(pkg, screens = listOf(s1), edges = listOf(edge))
        val danglingShare = AtlasShare(
            atlas = danglingAtlas,
            fingerprint = AtlasFingerprint.of(danglingAtlas),
        )
        assertTrue(AtlasShare.lint(danglingShare).any { "dangling edge" in it })

        // 超过屏幕数量上限
        val tooManyScreens = (1..(MAX_ATLAS_SCREENS + 1)).map { i -> s1.copy(sigId = "P#$i", fingerprint = i.toLong()) }
        val overAtlas = AppAtlas(pkg, screens = tooManyScreens)
        val overShare = AtlasShare(atlas = overAtlas, fingerprint = AtlasFingerprint.of(overAtlas))
        assertTrue(AtlasShare.lint(overShare).any { "too many screens" in it })

        // 路由含未知工具
        val badRoute = sampleRoute.copy(steps = listOf(RouteStep("unknown_tool", "{}", null, Anchors(pkg, emptyList()), Anchors(pkg, emptyList()))))
        val badRouteShare = RouteShare.of(badRoute, "bad")
        val withBadRoute = AtlasShare(
            atlas = valid.atlas,
            routes = listOf(badRouteShare),
            fingerprint = AtlasFingerprint.of(valid.atlas, listOf(badRouteShare)),
        )
        assertTrue(AtlasShare.lint(withBadRoute, TOOLS).any { "unknown tool" in it })

        // JSON 损坏 / schema 不匹配
        assertTrue(AtlasShare.decodeAndLint("{not valid") is AtlasShare.LintResult.Rejected)
        assertTrue(AtlasShare.decodeAndLint("""{"schema":99}""") is AtlasShare.LintResult.Rejected)
    }

    @Test
    fun mergeModeRemapsMatchedScreensPreservesUserTitlesAndReplaceClearsOld() {
        val atlasStore = InMemoryAtlasStore()
        val routeStore = MemRouteStore()

        val localS1 = s1.copy(
            sigId = "Home#1111111111111110",
            fingerprint = s1.fingerprint xor 1L,
            title = "我的手改首页",
            titleSource = AtlasScreen.TITLE_USER,
            hits = 10,
        )
        atlasStore.put(AppAtlas(pkg, "本地备忘录", screens = listOf(localS1)))
        routeStore.put(sampleRoute.copy(id = "old_r", template = "旧模板"))

        val share = AtlasShare.export(AppAtlas(pkg, "备忘录", versionsSeen = listOf("2.0"), screens = listOf(s1, s2), edges = listOf(edge)), listOf(sampleRoute))

        // Merge 模式：s1 通过 SimHash 合并进 localS1（保留用户标题），并自动把边的 fromSigId 重映射到 localS1.sigId，同时保留旧路线
        val merged = AtlasShare.importInto(share.encode(), atlasStore, routeStore, TOOLS, conflictMode = AtlasShare.ConflictMode.Merge, nowMs = 6000L)
        assertTrue(merged is AtlasShare.ImportResult.Imported)
        val mergedAtlas = atlasStore.get(pkg)!!
        assertEquals(2, mergedAtlas.screens.size)
        assertEquals("我的手改首页", mergedAtlas.findScreen(localS1.sigId)!!.title)
        assertEquals(AtlasScreen.TITLE_USER, mergedAtlas.findScreen(localS1.sigId)!!.titleSource)
        assertEquals(localS1.sigId, mergedAtlas.edges.single().fromSigId)
        assertEquals(s2.sigId, mergedAtlas.edges.single().toSigId)
        assertEquals(2, routeStore.forPackage(pkg).size)

        // Replace 模式：覆盖图谱与该包路线
        val replaced = AtlasShare.importInto(share.encode(), atlasStore, routeStore, TOOLS, conflictMode = AtlasShare.ConflictMode.Replace, nowMs = 7000L)
        assertTrue(replaced is AtlasShare.ImportResult.Imported)
        val replacedAtlas = atlasStore.get(pkg)!!
        assertEquals("笔记列表", replacedAtlas.findScreen(s1.sigId)!!.title)
        assertEquals(1, routeStore.forPackage(pkg).size)
        assertEquals("新建一条笔记{1}", routeStore.forPackage(pkg).single().template)

        // RouteStore 辅助方法测试
        val rid = routeStore.forPackage(pkg).single().id
        routeStore.setDisabled(rid, true)
        assertTrue(routeStore.forPackage(pkg).single().disabled)
        routeStore.removePackage(pkg)
        assertTrue(routeStore.forPackage(pkg).isEmpty())
    }

    @Test
    fun fingerprintOrderIsDeterministicWhenEdgesShareEndpointsAndTool() {
        // Phase 1 复审修正：fromSigId / toSigId / tool 相同时按 target 稳定排序，确保指纹与输入顺序无关
        val targetA = Selector("button", "按钮A", "btn_a", listOf(10, 10, 100, 100), 0)
        val targetB = Selector("button", "按钮B", "btn_b", listOf(10, 120, 100, 210), 1)
        val eA = AtlasEdge(s1.sigId, s2.sigId, "phone_tap", targetA, ok = 2, miss = 0)
        val eB = AtlasEdge(s1.sigId, s2.sigId, "phone_tap", targetB, ok = 3, miss = 1)
        val forward = AppAtlas(pkg, "备忘录", screens = listOf(s1, s2), edges = listOf(eA, eB))
        val reversed = AppAtlas(pkg, "备忘录", screens = listOf(s2, s1), edges = listOf(eB, eA))

        val fpForward = AtlasShare.export(forward).fingerprint
        val fpReversed = AtlasShare.export(reversed).fingerprint
        assertEquals(fpForward, fpReversed)
        assertTrue(fpForward.isNotBlank())
    }

    @Test
    fun shortcutExportLintFingerprintAndMergePreserveSafetyAndRemapEntries() {
        val scStep1 = RouteStep("phone_tap", "{}", s1.controls[0].selector, Anchors(pkg, listOf("备忘录")), Anchors(pkg, listOf("新笔记")))
        val scStep2 = RouteStep("phone_type_text", """{"text":"{1}"}""", Selector("edit", "正文", "et_body", emptyList(), 0), Anchors(pkg, listOf("新笔记")), Anchors(pkg, emptyList()))
        val cleanSc = AtlasShortcut(
            id = "sc_new_note",
            name = "新建笔记写{1}",
            description = "tap 新建 -> type {1}",
            slots = 1,
            entrySigId = s1.sigId,
            steps = listOf(scStep1, scStep2),
            successes = 9,
            consecutiveMisses = 1,
            lastUsedMs = 99999L,
        )
        val disabledSc = cleanSc.copy(id = "sc_disabled", disabled = true)
        val dirtyAtlas = AppAtlas(
            packageName = pkg,
            appLabel = "备忘录",
            screens = listOf(s1, s2),
            edges = listOf(edge),
            shortcuts = listOf(cleanSc, disabledSc),
            candidatePatterns = listOf(ShortcutCandidate("k", s1.sigId, listOf(scStep1, scStep2), slots = 1)),
        )

        // 导出：剥离 disabled shortcut、candidatePatterns 与运行次数/时间戳
        val share = AtlasShare.export(dirtyAtlas)
        assertEquals(1, share.atlas.shortcuts.size)
        assertTrue(share.atlas.candidatePatterns.isEmpty())
        val exportedSc = share.atlas.shortcuts.single()
        assertEquals(0, exportedSc.successes)
        assertEquals(0L, exportedSc.lastUsedMs)
        assertFalse(exportedSc.disabled)

        // lint 校验拦截非法 shortcut：悬空 entrySigId / 槽位越界 / 未知工具 / 含 candidatePatterns
        val danglingSc = cleanSc.copy(entrySigId = "Missing#0000")
        val danglingAtlas = share.atlas.copy(shortcuts = listOf(danglingSc))
        assertTrue(
            AtlasShare.lint(AtlasShare(atlas = danglingAtlas, fingerprint = AtlasFingerprint.of(danglingAtlas)), TOOLS)
                .any { "dangling entrySigId" in it },
        )
        val badSlotAtlas = share.atlas.copy(shortcuts = listOf(cleanSc.copy(slots = 0)))
        assertTrue(
            AtlasShare.lint(AtlasShare(atlas = badSlotAtlas, fingerprint = AtlasFingerprint.of(badSlotAtlas)), TOOLS)
                .any { "out-of-range slot" in it },
        )
        val badToolAtlas = share.atlas.copy(shortcuts = listOf(cleanSc.copy(steps = listOf(scStep1.copy(tool = "phone_fly")))))
        assertTrue(
            AtlasShare.lint(AtlasShare(atlas = badToolAtlas, fingerprint = AtlasFingerprint.of(badToolAtlas)), TOOLS)
                .any { "unknown tool" in it },
        )

        // Merge 模式：s1 重映射到 localS1 后，shortcut 的 entrySigId 也一并重映射
        val atlasStore = InMemoryAtlasStore()
        val localS1 = s1.copy(sigId = "Home#1111111111111110", fingerprint = s1.fingerprint xor 1L, title = "本地首页", titleSource = AtlasScreen.TITLE_USER)
        atlasStore.put(AppAtlas(pkg, "本地备忘录", screens = listOf(localS1)))
        val res = AtlasShare.importInto(share.encode(), atlasStore, null, TOOLS, conflictMode = AtlasShare.ConflictMode.Merge, nowMs = 8000L)
        assertTrue(res is AtlasShare.ImportResult.Imported)
        val merged = atlasStore.get(pkg)!!
        assertEquals(1, merged.shortcuts.size)
        assertEquals(localS1.sigId, merged.shortcuts.single().entrySigId)
    }
}
