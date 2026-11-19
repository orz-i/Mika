package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.uistate.ScreenSig
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 调研中六类问题的最小回归，不依赖模型、手机或历史实验文件。 */
class AtlasGovernanceTest {
    private val pkg = "com.example.atlas"
    private val home = sig("Home")
    private val form = sig("Form")
    private val result = sig("Result")
    private val query = "蓝牙耳机"

    private fun sig(name: String, packageName: String = pkg) = ScreenSig(
        sigId = "$name#${name.hashCode()}", activity = "$packageName/.$name", fingerprint = name.hashCode().toLong(),
        anchors = listOf(name), packageName = packageName, shortActivity = name,
    )

    private fun screen(sig: ScreenSig, title: String = sig.shortActivity!!, anchors: List<String> = sig.anchors) = AtlasScreen(
        sigId = sig.sigId, activity = sig.activity, shortActivity = sig.shortActivity, fingerprint = sig.fingerprint,
        anchors = anchors, title = title, titleSource = AtlasScreen.TITLE_LLM,
    )

    private fun target(rid: String? = "input", y: Int = 100, ordinal: Int = 0) =
        Selector("edit", "", rid, listOf(10, y, 400, y + 50), ordinal, uniqueAtRecord = rid != null)

    private fun action(
        tool: String, before: ScreenSig?, after: ScreenSig, target: Selector? = null, args: String = "{}", id: String = tool,
    ) = RunEvent.ActionTarget(
        atMs = 1000L, step = 1, tool = tool, argsJson = args, target = target,
        before = Anchors(before?.packageName, before?.anchors.orEmpty()), after = Anchors(after.packageName, after.anchors),
        effective = true, callId = id, beforeSig = before, afterSig = after,
    )

    private fun searchEvents() = listOf(
        action("phone_tap", home, form, Selector("button", "搜索", "search", emptyList(), 0, true)),
        action("phone_type_text", form, form, target(), """{"text":"$query","reason":"搜索$query"}"""),
        RunEvent.ToolResult(1001L, 1, "phone_type_text", """{"ok":true,"secret":false,"verification":"match"}""", false, null, callId = "phone_type_text"),
        action("phone_tap", form, result, Selector("button", "提交", "submit", emptyList(), 0, true), id = "submit"),
        RunEvent.TaskEnded(2000L, "succeeded", "done"),
        RunEvent.OutcomeJudged(2001L, "done"),
    )

    @Test fun unboundInputIsNeverPersistedInCandidatesOrExportedShortcuts() {
        val store = InMemoryAtlasStore()
        repeat(2) {
            AtlasLearner.learn(searchEvents(), store, goal = "帮我找合适的耳机", tools = TOOLS)
            assertFalse("候选也不能先保存输入原文", AtlasCodec.encode(store.get(pkg)!!).contains(query))
        }
        assertFalse(AtlasShare.export(store.get(pkg)!!).encode().contains(query))
    }

    @Test fun boundInputDoesNotLeakThroughReasonMetadata() {
        val store = InMemoryAtlasStore()
        repeat(2) { AtlasLearner.learn(searchEvents(), store, goal = "搜索$query", tools = TOOLS) }
        val atlas = store.get(pkg)!!
        assertTrue("仍然可以学习参数化的输入流程", atlas.shortcuts.any { sc -> sc.steps.any { it.tool == "phone_type_text" } })
        assertFalse(AtlasCodec.encode(atlas).contains(query))
        assertFalse(AtlasShare.export(atlas).encode().contains(query))
    }

    @Test fun learningCleansLegacyInputsWithoutWipingTheGraph() {
        val literal = RouteStep("phone_type_text", """{"text":"$query"}""", target().copy(name = query), Anchors(pkg, emptyList()), Anchors(pkg, emptyList()))
        val store = InMemoryAtlasStore()
        store.put(AppAtlas(
            pkg, screens = listOf(screen(form).copy(controls = listOf(AtlasControl(target().copy(name = query), used = 3)))),
            edges = listOf(AtlasEdge(form.sigId, form.sigId, "phone_type_text", target().copy(name = query), ok = 3)),
            shortcuts = listOf(AtlasShortcut("old", "搜索", entrySigId = form.sigId, steps = listOf(literal))),
            candidatePatterns = listOf(ShortcutCandidate("old", form.sigId, listOf(literal))),
        ))
        AtlasLearner.learn(listOf(action("phone_tap", form, form)), store, learnDone = false)
        val atlas = store.get(pkg)!!
        assertEquals(1, atlas.screens.size)
        assertEquals(3, atlas.edges.single().ok)
        assertEquals(3, atlas.screens.single().controls.single().used)
        assertFalse(AtlasCodec.encode(atlas).contains(query))
        assertTrue(atlas.shortcuts.isEmpty())
        assertTrue(atlas.candidatePatterns.isEmpty())
    }

    @Test fun legacyInputEdgesKeepDistinctResourceIds() {
        val store = InMemoryAtlasStore()
        store.put(AppAtlas(pkg, screens = listOf(screen(form)), edges = listOf(
            AtlasEdge(form.sigId, form.sigId, "phone_type_text", target("origin"), ok = 2),
            AtlasEdge(form.sigId, form.sigId, "phone_type_text", target("destination"), ok = 3),
        )))
        AtlasLearner.learn(listOf(action("phone_tap", form, form)), store, learnDone = false)
        assertEquals(mapOf("origin" to 2, "destination" to 3), store.get(pkg)!!.edges.associate { it.target!!.resourceId to it.ok })
    }

    @Test fun newInputEdgesDoNotTreatMissingResourceIdAsWildcard() {
        val store = InMemoryAtlasStore()
        val events = listOf(
            action("phone_type_text", form, form, target("origin")),
            action("phone_type_text", form, form, target(null, y = 500), id = "second"),
        )
        AtlasLearner.learn(events, store, learnDone = true)
        assertEquals(2, store.get(pkg)!!.edges.size)
    }

    @Test fun repeatedInputWithStableIdentityMergesDespiteSmallPositionChanges() {
        val store = InMemoryAtlasStore()
        val events = listOf(
            action("phone_type_text", form, form, target("origin")),
            action("phone_type_text", form, form, target("origin", y = 102), id = "again"),
        )
        AtlasLearner.learn(events, store, learnDone = true)
        assertEquals(1, store.get(pkg)!!.edges.size)
        assertEquals(2, store.get(pkg)!!.edges.single().ok)
    }

    @Test fun crossPackageTapAndDeepLinkDoNotBecomeLaunchEdges() {
        for (tool in listOf("phone_tap", "phone_open_link")) {
            val store = InMemoryAtlasStore()
            val external = sig("External", "com.example.other")
            AtlasLearner.learn(listOf(action(tool, external, result)), store, learnDone = true)
            assertTrue("$tool 不是启动证据", store.get(pkg)!!.entryEdges().isEmpty())
            assertTrue(store.get(pkg)!!.screens.any { it.sigId == result.sigId })
        }
    }

    @Test fun launchForDifferentPackageDoesNotBecomeEntryEvidence() {
        val store = InMemoryAtlasStore()
        AtlasLearner.learn(listOf(action(ATLAS_ENTRY_TOOL, null, home, args = """{"package":"com.example.other"}""")), store, learnDone = true)
        assertTrue(store.get(pkg)!!.entryEdges().isEmpty())
    }

    @Test fun exactTargetCannotFallBackToReachableAnchorMatch() {
        val decoy = sig("Decoy")
        val atlas = AppAtlas(pkg, screens = listOf(screen(home), screen(result, "目标页面"), screen(decoy, "其他页面", listOf("目标页面"))),
            edges = listOf(AtlasEdge(home.sigId, decoy.sigId, "phone_back", ok = 10)))
        assertTrue(AtlasNavigator.planPath(atlas, home, "目标页面") is AtlasNavigator.Plan.Unreachable)
    }

    @Test fun equallyRankedTargetsAreAmbiguousInsteadOfPickingTheClosest() {
        val second = sig("Second")
        val atlas = AppAtlas(pkg, screens = listOf(screen(home), screen(result, "同名页面"), screen(second, "同名页面")),
            edges = listOf(AtlasEdge(home.sigId, result.sigId, "phone_back", ok = 10)))
        val plan = AtlasNavigator.planPath(atlas, home, "同名页面")
        assertFalse("同名页面需要明确选择", plan is AtlasNavigator.Plan.Path)
        assertTrue("sigId 仍能精确选择", AtlasNavigator.planPath(atlas, home, result.sigId) is AtlasNavigator.Plan.Path)
    }

    @Test fun hopLimitKeepsMoreExpensiveShorterPrefixes() {
        val x = sig("X")
        val y = sig("Y")
        val atlas = AppAtlas(pkg, screens = listOf(home, x, y, result).map { screen(it) }, edges = listOf(
            AtlasEdge(home.sigId, x.sigId, "phone_back", ok = 1, miss = 1),
            AtlasEdge(home.sigId, y.sigId, "phone_back", ok = 100),
            AtlasEdge(y.sigId, x.sigId, "phone_back", ok = 100),
            AtlasEdge(x.sigId, result.sigId, "phone_back", ok = 100),
        ))
        val plan = AtlasNavigator.planPath(atlas, home, result.sigId, maxHops = 2)
        assertTrue("两跳内有合法路线: $plan", plan is AtlasNavigator.Plan.Path)
        assertEquals(listOf(home.sigId, x.sigId), (plan as AtlasNavigator.Plan.Path).edges.map { it.fromSigId })
    }

    @Test fun legacyEntryNeedsFreshLocalLaunchEvidence() {
        val legacy = AppAtlas(pkg, screens = listOf(screen(home)), edges = listOf(AtlasEdge(ENTRY_SIG_ID, home.sigId, ATLAS_ENTRY_TOOL, ok = 20)))
        assertTrue(AtlasNavigator.planFromEntry(legacy, home.sigId) is AtlasNavigator.Plan.NoMatchingStart)
        val store = InMemoryAtlasStore().apply { put(legacy) }
        AtlasLearner.learn(listOf(action(ATLAS_ENTRY_TOOL, null, home, args = """{"package":"$pkg"}""")), store, learnDone = true)
        assertTrue(store.get(pkg)!!.entryEdges().single().launchObserved)
        assertEquals("旧入口次数不再作为启动证据", 1, store.get(pkg)!!.entryEdges().single().ok)
        assertTrue(AtlasNavigator.planFromEntry(store.get(pkg)!!, home.sigId) is AtlasNavigator.Plan.Path)
    }

    private inner class LaunchBridge(private val landing: ScreenSig, private val next: ScreenSig, private val omitSignature: Boolean = false) : AtlasReplayBridge {
        private val outside = sig("Outside", "com.example.other")
        var current = outside
        val calls = mutableListOf<Part.ToolCall>()
        private fun snapshot() = ScreenSnapshot(
            "snap", current.packageName, listOf(UiElement("@e1", "button", "继续", 50, 50, listOf(0, 0, 100, 100), "go", true, false, false)),
            100, 100, activity = current.activity, screenSig = current.takeUnless { omitSignature && calls.isNotEmpty() },
        )
        override suspend fun gate() {}
        override suspend fun readScreen() = snapshot()
        override suspend fun peekScreen() = snapshot()
        override fun toolInfo(name: String) = TOOLS[name]
        override fun linkAllowed(action: String?, uri: String?, pkg: String?) = false
        override suspend fun executeReplay(call: Part.ToolCall): AtlasReplayBridge.StepExecOutcome {
            calls += call
            current = if (call.name == ATLAS_ENTRY_TOOL) landing else next
            return AtlasReplayBridge.StepExecOutcome.Ran(buildJsonObject { put("ok", true); put("changed", true) }, snapshot(), true)
        }
    }

    private fun launchAtlas(alternate: ScreenSig, via: ScreenSig? = null): AppAtlas {
        val tap = Selector("button", "继续", "go", emptyList(), 0, true)
        return AppAtlas(pkg, screens = (listOf(home, form, result, alternate) + listOfNotNull(via)).distinct().map { screen(it) }, edges = listOf(
            AtlasEdge(ENTRY_SIG_ID, home.sigId, ATLAS_ENTRY_TOOL, ok = 5, launchObserved = true),
            AtlasEdge(home.sigId, result.sigId, "phone_tap", tap, ok = 5),
            AtlasEdge(alternate.sigId, (via ?: result).sigId, "phone_tap", tap, ok = 5),
        ) + listOfNotNull(via?.let { AtlasEdge(it.sigId, result.sigId, "phone_tap", tap, ok = 5) }))
    }

    @Test fun launchReplansFromActualResumedPageWithoutPenalizingEntry() = runTest {
        val alternate = sig("Resumed")
        val atlas = launchAtlas(alternate)
        val store = InMemoryAtlasStore().apply { put(atlas) }
        val bridge = LaunchBridge(alternate, result)
        val outcome = AtlasNavigator.forBridge(bridge).navigate(atlas, result.sigId, maxHops = 2, store = store)
        assertTrue("应从实际落点重新寻路: $outcome", outcome is AtlasNavigator.Outcome.Arrived)
        assertEquals(2, (outcome as AtlasNavigator.Outcome.Arrived).hops)
        assertEquals(listOf(ATLAS_ENTRY_TOOL, "phone_tap"), bridge.calls.map { it.name })
        assertEquals(bridge.calls.size, bridge.calls.map { it.id }.distinct().size)
        assertEquals(0, store.get(pkg)!!.entryEdges().single().miss)
    }

    @Test fun launchReplanCannotExceedOriginalHopBudgetOrRelaunch() = runTest {
        val alternate = sig("Resumed")
        val atlas = launchAtlas(alternate, sig("Middle"))
        val bridge = LaunchBridge(alternate, result)
        val outcome = AtlasNavigator.forBridge(bridge).navigate(atlas, result.sigId, maxHops = 2)
        assertTrue(outcome is AtlasNavigator.Outcome.Diverged)
        assertEquals(1, (outcome as AtlasNavigator.Outcome.Diverged).hopsCompleted)
        assertEquals(listOf(ATLAS_ENTRY_TOOL), bridge.calls.map { it.name })
    }

    @Test fun launchWithUnmappedOrUnreadableLandingStopsBeforeAnyTap() = runTest {
        for (omitSignature in listOf(false, true)) {
            val unknown = sig("Unknown")
            val bridge = LaunchBridge(unknown, result, omitSignature)
            val outcome = AtlasNavigator.forBridge(bridge).navigate(launchAtlas(sig("Resumed")), result.sigId)
            assertTrue(outcome is AtlasNavigator.Outcome.Diverged)
            assertEquals(listOf(ATLAS_ENTRY_TOOL), bridge.calls.map { it.name })
        }
    }

    @Test fun unsafeLegacyShortcutStopsBeforeReadingOrExecuting() = runTest {
        val bridge = LaunchBridge(home, form)
        val step = RouteStep("phone_type_text", """{"text":"$query"}""", target(), Anchors(pkg, emptyList()), Anchors(pkg, emptyList()))
        val shortcut = AtlasShortcut("sc", "搜索", entrySigId = home.sigId, steps = listOf(step))
        val outcome = AtlasShortcutRunner.forBridge(bridge).run(shortcut)
        assertTrue(outcome is AtlasShortcutRunner.Outcome.Diverged)
        assertEquals("unsafe_shortcut", (outcome as AtlasShortcutRunner.Outcome.Diverged).error)
        assertTrue(bridge.calls.isEmpty())
    }

    @Test fun exactFingerprintCannotMatchTheSameIdFromAnotherPackage() {
        val atlas = AppAtlas(pkg, screens = listOf(screen(home)))
        val foreign = home.copy(packageName = "com.example.other")
        assertTrue(AtlasNavigator.planPath(atlas, foreign, home.sigId) is AtlasNavigator.Plan.NoMatchingStart)
    }

    @Test fun orphanInputControlAlsoCleansKnownEchoesInNamesAndOtherSequences() {
        val step = RouteStep("phone_back", "{}", null, Anchors(pkg, listOf(query, "稳定地标")), Anchors(pkg, listOf("推荐$query", "返回")))
        val atlas = AppAtlas(
            pkg, screens = listOf(screen(form, "搜索$query", listOf(query, "稳定地标")).copy(
                controls = listOf(AtlasControl(target().copy(name = query), meaning = "填写$query", used = 7)),
            )),
            shortcuts = listOf(AtlasShortcut("sc", "返回", entrySigId = form.sigId, steps = listOf(step))),
            candidatePatterns = listOf(ShortcutCandidate("safe", form.sigId, listOf(step))),
        )
        val cleaned = atlas.privacyCleaned()
        assertFalse(AtlasCodec.encode(cleaned).contains(query))
        assertEquals("Form", cleaned.screens.single().title)
        assertEquals(7, cleaned.screens.single().controls.single().used)
        assertEquals(listOf("稳定地标"), cleaned.shortcuts.single().steps.single().before.texts)
        assertEquals(listOf("返回"), cleaned.candidatePatterns.single().steps.single().after.texts)
        assertEquals(cleaned, cleaned.privacyCleaned())
    }

    @Test fun nonEditableInputEchoIsRemovedBeforeLearningOrNaming() {
        val resultWithEcho = result.copy(anchors = listOf(query, "搜索结果"))
        val run = searchEvents()
        val events = run.dropLast(2).map { event ->
            if (event is RunEvent.ActionTarget && event.afterSig?.sigId == result.sigId) event.copy(afterSig = resultWithEcho) else event
        } + action("phone_tap", resultWithEcho, resultWithEcho, Selector("button", query, "query_bar", emptyList(), 0, true)) + run.takeLast(2)
        val store = InMemoryAtlasStore()
        AtlasLearner.learn(events, store, goal = "找合适的耳机", tools = TOOLS)
        val learned = store.get(pkg)!!
        assertTrue(learned.screens.any { "搜索结果" in it.anchors })
        assertFalse(AtlasCodec.encode(learned).contains(query))
    }

    @Test fun userChosenTitleIsNotOverwrittenJustBecauseItMatchesAnInput() {
        val atlas = AppAtlas(pkg, screens = listOf(screen(form, query).copy(
            titleSource = AtlasScreen.TITLE_USER, controls = listOf(AtlasControl(target().copy(name = query))),
        )))
        val clean = atlas.privacyCleaned()
        assertEquals(query, clean.screens.single().title)
        assertEquals(AtlasScreen.TITLE_USER, clean.screens.single().titleSource)
        assertEquals("", clean.screens.single().controls.single().selector.name)
    }

}
