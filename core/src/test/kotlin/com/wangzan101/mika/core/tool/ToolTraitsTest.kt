package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.agent.ContextCompressor
import com.wangzan101.mika.core.agent.HistoryPruner
import com.wangzan101.mika.core.agent.ModelRouter
import com.wangzan101.mika.core.agent.StepTrack
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.mcp.McpAnnotations
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.cap.mcp.McpEndpoint
import com.wangzan101.mika.core.cap.mcp.McpResult
import com.wangzan101.mika.core.cap.mcp.McpToolDescriptor
import com.wangzan101.mika.core.cap.mcp.McpTrust
import com.wangzan101.mika.core.cap.mcp.mcpToolTraits
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.testing.TOOLS
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTraitsTest {
    @Test
    fun builtInToolsExposeConsistentOrthogonalTraits() {
        val observe = TOOLS.traits("phone_observe")!!
        assertEquals(EffectKind.ReadOnly, observe.effect)
        assertEquals(Concurrency.Serial, observe.concurrency)
        assertEquals(ContextRetention.InterfaceEphemeral, observe.retention)
        assertEquals(ModelTier.Default, observe.modelTier)
        assertEquals("phone", observe.surfaceKind)
        assertTrue(observe.fullScreenSnapshot)
        assertTrue(observe.diagnosticRead)
        assertTrue(observe.pageRead)
        assertFalse(observe.modifiesScreenState)

        val tap = TOOLS.traits("phone_tap")!!
        assertEquals(EffectKind.ScreenWrite, tap.effect)
        assertEquals(Concurrency.Serial, tap.concurrency)
        assertEquals(ContextRetention.ShrinkIfLarge, tap.retention)
        assertEquals(ModelTier.Default, tap.modelTier)
        assertEquals("phone", tap.surfaceKind)
        assertTrue(tap.modifiesScreenState)

        val launch = TOOLS.traits("phone_launch_app")!!
        assertEquals(EffectKind.ScreenWrite, launch.effect)
        assertTrue(launch.switchesPlace)
        assertFalse(launch.modifiesScreenState)

        val readFile = TOOLS.traits("read_file")!!
        assertEquals(EffectKind.ReadOnly, readFile.effect)
        assertEquals(Concurrency.ParallelSafe, readFile.concurrency)
        assertEquals(ContextRetention.ShrinkIfLarge, readFile.retention)
        assertEquals(ModelTier.MainModelOnly, readFile.modelTier)
        assertEquals("sandbox", readFile.surfaceKind)

        val writeFile = TOOLS.traits("write_file")!!
        assertEquals(EffectKind.WorkspaceWrite, writeFile.effect)
        assertEquals(Concurrency.Serial, writeFile.concurrency)
        assertEquals(ModelTier.MainModelOnly, writeFile.modelTier)
        assertFalse(writeFile.modifiesScreenState)

        val knowledgeRead = TOOLS.traits("knowledge_read")!!
        assertEquals(EffectKind.ReadOnly, knowledgeRead.effect)
        assertEquals(Concurrency.ParallelSafe, knowledgeRead.concurrency)
        assertEquals(ContextRetention.PinReference, knowledgeRead.retention)
        assertEquals(ModelTier.Default, knowledgeRead.modelTier)

        val recall = TOOLS.traits("view_past_screens")!!
        assertEquals(EffectKind.ReadOnly, recall.effect)
        assertEquals(Concurrency.ParallelSafe, recall.concurrency)
        assertEquals("screen", recall.surfaceKind)
        assertTrue(recall.recallsPastScreens)
        assertTrue(recall.diagnosticRead)

        val handOver = TOOLS.traits("hand_to_user")!!
        assertEquals(EffectKind.External, handOver.effect)
        assertEquals(ModelTier.MainModelOnly, handOver.modelTier)
        assertTrue(handOver.userInteraction)
    }

    @Test
    fun mcpAnnotationsMapToToolTraitsAndParallelSafety() = runTest {
        val trustedRead = mcpToolTraits(McpAnnotations(readOnlyHint = true), McpTrust.TrustAnnotations, "mcp:demo")
        assertEquals(EffectKind.ReadOnly, trustedRead.effect)
        assertEquals(Concurrency.ParallelSafe, trustedRead.concurrency)
        assertEquals(ContextRetention.ShrinkIfLarge, trustedRead.retention)
        assertEquals(ModelTier.Default, trustedRead.modelTier)
        assertEquals("mcp:demo", trustedRead.surfaceKind)
        assertFalse(trustedRead.modifiesScreenState)

        val restrictedRead = mcpToolTraits(McpAnnotations(readOnlyHint = true), McpTrust.Restricted, "mcp:demo")
        assertEquals(EffectKind.WorkspaceWrite, restrictedRead.effect)
        assertEquals(Concurrency.Serial, restrictedRead.concurrency)

        val openWorld = mcpToolTraits(McpAnnotations(openWorldHint = true), McpTrust.TrustAnnotations, "mcp:demo")
        assertEquals(EffectKind.External, openWorld.effect)
        assertEquals(Concurrency.Serial, openWorld.concurrency)
        assertEquals(ModelTier.Default, openWorld.modelTier)

        val destructive = mcpToolTraits(McpAnnotations(destructiveHint = true), McpTrust.TrustAnnotations, "mcp:demo")
        assertEquals(EffectKind.External, destructive.effect)
        assertEquals(Concurrency.Serial, destructive.concurrency)
        assertEquals(ModelTier.MainModelOnly, destructive.modelTier)

        val endpoint = object : McpEndpoint {
            override val serverId = "demo"
            override fun tools() = listOf(
                McpToolDescriptor("ro", "Read", buildJsonObject { put("type", "object") }, McpAnnotations(readOnlyHint = true)),
                McpToolDescriptor("rw", "Write", buildJsonObject { put("type", "object") }),
            )
            override suspend fun call(name: String, arguments: JsonObject) = McpResult("""{"ok":true}""")
        }
        val dispatcher = toolDispatcher(
            FakeScreen(),
            mcpServers = listOf(McpConnection(endpoint, trust = McpTrust.TrustAnnotations)),
        )
        assertTrue(dispatcher.isParallelSafe("mcp_demo__ro"))
        assertFalse(dispatcher.isParallelSafe("mcp_demo__rw"))

        val recorder = ActionRecorder(RunSink.NONE)
        val executor = ActionExecutor(dispatcher, OperationGuards(), null, gate = {}, recorder = recorder, autonomous = true)
        val out = executor.run(Part.ToolCall("m1", "mcp_demo__rw", "{}"), Origin.Model(1))
        assertTrue("$out", out is ActionOutcome.Ran)
        assertNull("MCP write tool must not mark screen unreadChange", recorder.lastChangeMark)
    }

    @Test
    fun prunerCompressorAndRouterOperateOnCustomToolTraitsWithoutVerbSets() {
        val customPinTool = object : ToolInfo {
            override val name = "custom_ref_pin"
            override val verb = Verb.Tap // deliberately a non-reference Verb to prove traits win
            override val capability: String? = null
            override val batchable = false
            override val routable = false
            override val label: String? = null
            override val traits = ToolTraits(
                effect = EffectKind.ReadOnly,
                concurrency = Concurrency.ParallelSafe,
                retention = ContextRetention.PinReference,
                modelTier = ModelTier.MainModelOnly,
            )
        }
        val customUserHold = object : ToolInfo {
            override val name = "custom_user_hold"
            override val verb = Verb.Tap // deliberately not HandToUser/AskUser
            override val capability: String? = null
            override val batchable = false
            override val routable = false
            override val label: String? = null
            override val traits = ToolTraits(
                effect = EffectKind.External,
                userInteraction = true,
                switchesPlace = true,
            )
        }
        val customIndex = ToolIndex(listOf(customPinTool, customUserHold))

        // 1. ModelRouter uses traits
        assertTrue(ModelRouter.mainOnly("custom_ref_pin", customIndex))
        assertFalse(ModelRouter.mainOnly("custom_user_hold", customIndex))
        val track = StepTrack()
        track.after(customIndex.traits("custom_user_hold")!!, ok = true, guard = false, changed = true, recovery = false, app = "app1")
        val ctx = track.context(step = 2, hasImages = false)
        assertTrue(ctx.lastWasRecovery)
        assertFalse(ctx.sameAppAsLast)

        // 2. HistoryPruner pins PinReference regardless of Verb.Tap and >BIG_RESULT_TOKENS
        val bigText = "参考文档内容".repeat(1_500)
        val history = listOf(
            Message(Role.User, listOf(Part.Text("task")), Author.User),
            Message(Role.Assistant, listOf(Part.ToolCall("c1", "custom_ref_pin", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c1", """{"ok":true,"content":"$bigText"}""", false)), Author.Mika),
            Message(Role.Assistant, listOf(Part.ToolCall("c2", "custom_user_hold", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c2", """{"ok":true}""", false)), Author.Mika),
            Message(Role.Assistant, listOf(Part.ToolCall("c3", "custom_ref_pin", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c3", """{"ok":true}""", false)), Author.Mika),
        )
        val pruned = HistoryPruner.prune(history, customIndex, HistoryPruner.Cut(1))
        val c1Content = pruned.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().first { it.callId == "c1" }.content
        assertTrue("PinReference trait must prevent big read shrinking", c1Content.contains(bigText))

        // 3. ContextCompressor keeps userInteraction round intact
        val rounds = listOf(Message(Role.User, listOf(Part.Text("task")), Author.User)) +
            listOf(
                Message(Role.Assistant, listOf(Part.ToolCall("u1", "custom_user_hold", "{}")), Author.Mika),
                Message(Role.Tool, listOf(Part.ToolResult("u1", """{"ok":true}""", false)), Author.Mika),
            ) +
            (1..10).flatMap { i ->
                listOf(
                    Message(Role.Assistant, listOf(Part.ToolCall("r$i", "custom_ref_pin", "{}")), Author.Mika),
                    Message(Role.Tool, listOf(Part.ToolResult("r$i", """{"ok":true}""", false)), Author.Mika),
                )
            }
        val compressed = ContextCompressor(customIndex).compress(rounds, contextTokens = 100)
        val keptCallIds = compressed.messages.flatMap { it.parts }.filterIsInstance<Part.ToolCall>().map { it.id }
        assertTrue("userInteraction trait must pin the round", "u1" in keptCallIds)
    }

    @Test
    fun wave3OrthogonalTraitsAndToolIndexNameWhereCoverAllRoles() {
        val observe = TOOLS.traits("phone_observe")!!
        assertTrue(observe.freshRead)
        assertTrue(observe.collectionOp)
        assertTrue(observe.siteEntry)
        assertFalse(observe.elementSnapshot)
        assertTrue(observe.recordedInActivity)

        val listElements = TOOLS.traits("phone_list_elements")!!
        assertTrue(listElements.elementSnapshot)
        assertTrue(listElements.collectionOp)
        assertTrue(listElements.siteEntry)

        val typeText = TOOLS.traits("phone_type_text")!!
        assertTrue(typeText.textInput)
        assertTrue(typeText.usesFocusedEditable)
        assertTrue(typeText.modifiesScreenState)

        val clearText = TOOLS.traits("phone_clear_text")!!
        assertFalse(clearText.textInput)
        assertTrue(clearText.usesFocusedEditable)

        val memSearch = TOOLS.traits("memory_search")!!
        assertEquals(MemoryAccess.Search, memSearch.memoryAccess)
        assertTrue(memSearch.memoryOp)
        assertTrue(memSearch.lookupRead)

        val memSave = TOOLS.traits("memory_save")!!
        assertEquals(MemoryAccess.Save, memSave.memoryAccess)
        assertTrue(memSave.memoryOp)
        assertFalse(memSave.lookupRead)

        val memEdit = TOOLS.traits("memory_edit")!!
        assertEquals(MemoryAccess.Edit, memEdit.memoryAccess)
        assertTrue(memEdit.memoryOp)

        val skillRead = TOOLS.traits("skill_read")!!
        assertTrue(skillRead.readsSkill)
        assertTrue(skillRead.lookupRead)

        val askUser = TOOLS.traits("ask_user")!!
        assertEquals(InteractionKind.AskUser, askUser.interactionKind)
        assertTrue(askUser.hidesAnswerInRecord)
        assertFalse(askUser.recordedInActivity)

        val handToUser = TOOLS.traits("hand_to_user")!!
        assertEquals(InteractionKind.HandToUser, handToUser.interactionKind)
        assertFalse(handToUser.recordedInActivity)

        val endNotDone = TOOLS.traits("end_not_done")!!
        assertEquals(InteractionKind.EndNotDone, endNotDone.interactionKind)
        assertTrue(endNotDone.terminatesRun)
        assertFalse(endNotDone.recordedInActivity)

        val wait = TOOLS.traits("wait")!!
        assertEquals(InteractionKind.None, wait.interactionKind)
        assertTrue(wait.freshRead)
        assertFalse(wait.recordedInActivity)

        assertTrue(TOOLS.traits("schedule_task")!!.schedulesTask)
        assertTrue(TOOLS.traits("spawn_agent")!!.delegatesTask)
        assertTrue(TOOLS.traits("profile_edit")!!.modifiesProfile)
        assertTrue(TOOLS.traits("skill_save")!!.writesSkill)

        val webDispatcher = toolDispatcher(FakeScreen(), web = com.wangzan101.mika.core.cap.web.FakeWebPort())
        assertTrue(webDispatcher.traits("web_navigate")!!.navigable)
        assertTrue(webDispatcher.traits("web_navigate")!!.siteEntry)
        assertEquals("phone_list_elements", webDispatcher.index.nameWhere("phone") { it.elementSnapshot })
        assertEquals("web_list_elements", webDispatcher.index.nameWhere("web") { it.elementSnapshot })
        assertEquals("hand_to_user", webDispatcher.index.nameWhere { it.interactionKind == InteractionKind.HandToUser })
        assertNull(webDispatcher.index.nameWhere("sandbox") { it.elementSnapshot })
    }
}
