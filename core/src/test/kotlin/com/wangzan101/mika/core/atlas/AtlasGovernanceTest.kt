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

    @Test fun unsafeLegacyShortcutStopsBeforeReadingOrExecuting() = runTest {
        val bridge = LaunchBridge(home, form)
        val step = RouteStep("phone_type_text", """{"text":"$query"}""", target(), Anchors(pkg, emptyList()), Anchors(pkg, emptyList()))
        val shortcut = AtlasShortcut("sc", "搜索", entrySigId = home.sigId, steps = listOf(step))
        val outcome = AtlasShortcutRunner.forBridge(bridge).run(shortcut)
        assertTrue(outcome is AtlasShortcutRunner.Outcome.Diverged)
        assertEquals("unsafe_shortcut", (outcome as AtlasShortcutRunner.Outcome.Diverged).error)
        assertTrue(bridge.calls.isEmpty())
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
