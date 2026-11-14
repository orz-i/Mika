package com.wangzan101.mika.core.child

import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.policy.NameMatcher
import com.wangzan101.mika.core.policy.PermissionRule
import com.wangzan101.mika.core.policy.RuleBasedPermissionPolicy
import com.wangzan101.mika.core.policy.RunProfile
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Interaction
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildRunsTest {
    @Test fun spawnReturnsBoundedObservationAndPersistsChildFirst() = runBlocking {
        val parent = RunSpec(RunId("parent"), "goal", "thread")
        val store = MemoryChildRunStore()
        val runner = ChildAgentRunner({ spec ->
            AgentLoop(FakeModel(answerReply("子结论")),
                toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec), "BASE", confirmations = null)
        }, store, ownerAlive = { store.all().single().phase == ChildPhase.Completed })
        val tools = toolDispatcher(FakeScreen(), runSpec = parent, childHost = runner)
        val result = tools.execute(Part.ToolCall("s", "spawn_agent", """{"goal":"只查一条事实"}"""), ActionPass())
        val record = store.all().single()
        assertEquals(ChildPhase.Completed, record.phase)
        assertEquals("parent", record.parentId)
        assertEquals("子结论", record.result)
        assertEquals(Trust.Untrusted, result.observations.single().trust)
        assertEquals("子结论", result.observations.single().text)
    }

    @Test fun childRestrictionsCannotBeBypassedByRunSpecFlags() = runBlocking {
        val spec = RunSpec(RunId("child"), "research", "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Present),
            capabilities = null, readOnly = false, parent = RunId("parent"))
        val tools = toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec,
            childHost = ChildRunHost { _, _ -> error("must not spawn") }, scheduleTrigger = { Scheduled(true) })
        val names = tools.definitions().map { it.name }
        assertTrue("web_read_page" in names)
        assertFalse(names.any { it.startsWith("phone_") || it == "web_interact" || it == "write_file" ||
            it == "spawn_agent" || it == "memory_save" || it == "schedule_task" ||
            it == "ask_user" || it == "hand_to_user" || it == "request_confirmation" })
        assertEquals("unknown_tool", tools.execute(Part.ToolCall("w", "web_interact", "{}"), ActionPass())
            .content["error"].toString().trim('"'))
    }

    @Test fun absentOwnerLeavesUnknownResultWithoutRerun() = runBlocking {
        val store = MemoryChildRunStore()
        val spec = RunSpec(RunId("c"), "research", "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended), parent = RunId("p"))
        val runner = ChildAgentRunner({ s -> AgentLoop(FakeModel(answerReply("answer")),
            toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = s), "BASE", confirmations = null) }, store,
            ownerAlive = { false })
        assertEquals(ChildPhase.Unknown, runner.spawn(spec, null).phase)
        assertEquals(ChildPhase.Unknown, store.get("c")?.phase)
    }

    private fun spawnWithRunner(text: String, args: String, ownerAlive: Boolean = true): Pair<MemoryChildRunStore, kotlinx.serialization.json.JsonObject> = runBlocking {
        val store = MemoryChildRunStore()
        val runner = ChildAgentRunner({ spec ->
            AgentLoop(FakeModel(answerReply(text)),
                toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec), "BASE", confirmations = null)
        }, store, ownerAlive = { ownerAlive })
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "goal", "thread"), childHost = runner)
        store to tools.execute(Part.ToolCall("s", "spawn_agent", args), ActionPass()).content
    }

    @Test fun realRunnerReturnsResultsBeyondTheStoredPreview() {
        val text = "x".repeat(6000)
        val (store, content) = spawnWithRunner(text, """{"goal":"research","maxResultChars":8000}""")
        assertEquals(6000, resultLength(content))
        assertEquals(null, content["truncated"])
        assertEquals(null, content["fullLength"])
        assertEquals(text.take(ChildAgentRunner.MAX_RESULT_CHARS), store.all().single().result)
    }

    @Test fun realRunnerReportsOriginalLengthForDefaultAndMaximumLimits() {
        val text = "x".repeat(12_000)
        for (limit in listOf(null, 8000)) {
            val args = if (limit == null) """{"goal":"research"}"""
                else """{"goal":"research","maxResultChars":$limit}"""
            val (store, content) = spawnWithRunner(text, args)
            assertEquals(limit ?: 4000, resultLength(content))
            assertEquals("true", content["truncated"].toString())
            assertEquals("12000", content["fullLength"].toString())
            assertEquals(4000, store.all().single().result!!.length)
        }
    }

    @Test fun realRunnerValidatesJsonBeforeTruncatingTheStoredPreview() {
        val text = """{"data":"${"x".repeat(5000)}"}"""
        val (store, content) = spawnWithRunner(text, """{"goal":"research","format":"json","maxResultChars":8000}""")
        assertEquals(text.length, resultLength(content))
        assertEquals(text, (content["result"] as kotlinx.serialization.json.JsonPrimitive).content
            .removePrefix("<untrusted>\n").removeSuffix("\n</untrusted>"))
        assertEquals(null, content["truncated"])
        assertEquals(text.take(4000), store.all().single().result)
    }

    @Test fun realRunnerKeepsInvalidJsonWarningAndBoundsItsStoredPreview() {
        val text = "{\"data\":\"" + "x".repeat(5000)
        val (store, content) = spawnWithRunner(text, """{"goal":"research","format":"json","maxResultChars":8000}""")
        val result = (content["result"] as kotlinx.serialization.json.JsonPrimitive).content
        assertTrue(result.contains(text))
        assertTrue(result.contains("JSON did not pass validation"))
        assertEquals(null, content["truncated"])
        assertEquals(text.take(4000), store.all().single().result)
    }

    @Test fun absentOwnerAlsoKeepsOnlyABoundedPreview() {
        val (store, content) = spawnWithRunner("x".repeat(6000),
            """{"goal":"research","maxResultChars":8000}""", ownerAlive = false)
        assertEquals("false", content["ok"].toString())
        assertEquals("unknown", (content["status"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(6000, resultLength(content))
        assertEquals(ChildPhase.Unknown, store.all().single().phase)
        assertEquals(4000, store.all().single().result!!.length)
    }

    private fun spawnWith(args: String, text: String = "x".repeat(20_000)): Pair<RunSpec, kotlinx.serialization.json.JsonObject> = runBlocking {
        var seen: RunSpec? = null
        val host = ChildRunHost { spec, _ -> seen = spec; ChildResult(spec.id, ChildPhase.Completed, text) }
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "goal", "thread"), childHost = host)
        val r = tools.execute(Part.ToolCall("s", "spawn_agent", args), ActionPass())
        seen!! to r.content
    }

    private fun resultLength(content: kotlinx.serialization.json.JsonObject) =
        (content["result"] as kotlinx.serialization.json.JsonPrimitive).content.removePrefix("<untrusted>\n").removeSuffix("\n</untrusted>").length

    @Test fun defaultBudgetsStayAsBefore() {
        val (spec, content) = spawnWith("""{"goal":"查一条"}""")
        assertEquals(15, spec.stepBudget)
        assertTrue(spec.readOnly)
        assertEquals(setOf("web", "sandbox"), spec.capabilities)
        assertEquals(4000, resultLength(content))
        assertEquals("true", content["truncated"].toString())
    }

    @Test fun modelChosenBudgetsAreClampedToTheCaps() {
        val (spec, content) = spawnWith("""{"goal":"查三家","maxSteps":30,"maxResultChars":6000}""")
        assertEquals(30, spec.stepBudget)
        assertEquals(6000, resultLength(content))
        val (high, highContent) = spawnWith("""{"goal":"查三家","maxSteps":500,"maxResultChars":100000}""")
        assertEquals(40, high.stepBudget)
        assertEquals(8000, resultLength(highContent))
        val (low, lowContent) = spawnWith("""{"goal":"查三家","maxSteps":0,"maxResultChars":10}""")
        assertEquals(1, low.stepBudget)
        assertEquals(500, resultLength(lowContent))
        val (_, short) = spawnWith("""{"goal":"查三家"}""", text = "短结论")
        assertEquals(null, short["truncated"])
    }

    @Test fun nonIntegerBudgetIsRejected() = runBlocking {
        val tools = toolDispatcher(FakeScreen(), runSpec = RunSpec(RunId("parent"), "goal", "thread"),
            childHost = ChildRunHost { _, _ -> error("must not spawn") })
        val r = tools.execute(Part.ToolCall("s", "spawn_agent", """{"goal":"g","maxSteps":2.5}"""), ActionPass())
        assertEquals("invalid_arguments", r.content["error"].toString().trim('"'))
    }

    @Test fun interactiveParentBubblesChildConfirmationAndProceedsOnApproval() = runBlocking {
        val sb = FakeSandbox()
        val policy = RuleBasedPermissionPolicy(staticRules = listOf(PermissionRule.ask("ask_run", tool = NameMatcher.Exact("run_command"))))
        val parentProfile = RunProfile.GENERAL.withPermissions(policy)
        val parentInbox = RunInbox()
        val asked = CompletableDeferred<Delivery.Ask>()
        val interaction = object : Interaction {
            override suspend fun publish(ask: Delivery.Ask): DeliveryState {
                asked.complete(ask)
                return DeliveryState.Delivered
            }
            override fun resolved(askId: String) = Unit
        }
        val childModel = FakeModel(
            com.wangzan101.mika.core.model.ModelReply(
                listOf(Part.ToolCall("r1", "run_command", """{"command":"echo ok","cwd":"workers/a"}""")),
                com.wangzan101.mika.core.model.StopReason.ToolUse, null,
            ),
            answerReply("命令已执行"),
        )
        val parentModel = FakeModel(
            com.wangzan101.mika.core.model.ModelReply(
                listOf(Part.ToolCall("s1", "spawn_agent", """{"goal":"执行命令","role":"coder","capabilities":["sandbox"],"workspaceScope":"workers/a"}""")),
                com.wangzan101.mika.core.model.StopReason.ToolUse, null,
            ),
            answerReply("子任务已完成执行"),
        )
        val store = MemoryChildRunStore()
        val runner = ChildAgentRunner({ childSpec ->
            AgentLoop(
                childModel,
                toolDispatcher(FakeScreen(), sandbox = sb, runSpec = childSpec),
                "BASE",
                confirmations = ConfirmationGate(),
                runProfile = childSpec.profile,
            )
        }, store)
        val parentSpec = RunSpec(
            RunId("parent"), "执行命令", "thread",
            provenance = RunProvenance(RunOrigin.Ui, Attendance.Present),
            profile = parentProfile,
        )
        val parentTools = toolDispatcher(FakeScreen(), sandbox = sb, runSpec = parentSpec, childHost = runner)
        val parentLoop = AgentLoop(
            parentModel, parentTools, "BASE",
            confirmations = ConfirmationGate(),
            interaction = interaction,
            runProfile = parentProfile,
        )
        val running = async { parentLoop.run(parentSpec, parentInbox) }
        val card = withTimeout(10_000) { asked.await() }
        assertEquals(Delivery.Ask.Kind.Confirm, card.kind)
        assertTrue(card.text.contains("run_command"))
        parentInbox.send(RunInput.Confirm(ConfirmChoice.Once, card.askId))
        val end = withTimeout(10_000) { running.await() }
        assertTrue(end.toString(), end is com.wangzan101.mika.core.agent.TaskState.Succeeded)
        val childToolResults = childModel.requests.last().filter { it.role == com.wangzan101.mika.core.model.Role.Tool }
            .flatMap { it.parts.filterIsInstance<Part.ToolResult>() }
        assertTrue(childToolResults.toString(), childToolResults.any { "\"ok\":true" in it.content })
        assertEquals("命令已执行", store.all().single().result)
        assertEquals(1, sb.runs.size)
        assertEquals("workers/a", sb.runs.single().cwd)
    }

    @Test fun interactiveParentCanDenyChildConfirmation() = runBlocking {
        val sb = FakeSandbox()
        val policy = RuleBasedPermissionPolicy(staticRules = listOf(PermissionRule.ask("ask_run", tool = NameMatcher.Exact("run_command"))))
        val parentProfile = RunProfile.GENERAL.withPermissions(policy)
        val parentInbox = RunInbox()
        val asked = CompletableDeferred<Delivery.Ask>()
        val interaction = object : Interaction {
            override suspend fun publish(ask: Delivery.Ask): DeliveryState {
                asked.complete(ask)
                return DeliveryState.Delivered
            }
            override fun resolved(askId: String) = Unit
        }
        val childModel = FakeModel(
            com.wangzan101.mika.core.model.ModelReply(
                listOf(Part.ToolCall("r1", "run_command", """{"command":"echo no","cwd":"workers/a"}""")),
                com.wangzan101.mika.core.model.StopReason.ToolUse, null,
            ),
            answerReply("用户拒绝了执行"),
        )
        val parentModel = FakeModel(
            com.wangzan101.mika.core.model.ModelReply(
                listOf(Part.ToolCall("s1", "spawn_agent", """{"goal":"执行命令","role":"coder","capabilities":["sandbox"],"workspaceScope":"workers/a"}""")),
                com.wangzan101.mika.core.model.StopReason.ToolUse, null,
            ),
            answerReply("已转告拒绝"),
        )
        val store = MemoryChildRunStore()
        val runner = ChildAgentRunner({ childSpec ->
            AgentLoop(
                childModel,
                toolDispatcher(FakeScreen(), sandbox = sb, runSpec = childSpec),
                "BASE",
                confirmations = ConfirmationGate(),
                runProfile = childSpec.profile,
            )
        }, store)
        val parentSpec = RunSpec(
            RunId("parent-deny"), "执行命令", "thread",
            provenance = RunProvenance(RunOrigin.Ui, Attendance.Present),
            profile = parentProfile,
        )
        val parentTools = toolDispatcher(FakeScreen(), sandbox = sb, runSpec = parentSpec, childHost = runner)
        val parentLoop = AgentLoop(
            parentModel, parentTools, "BASE",
            confirmations = ConfirmationGate(),
            interaction = interaction,
            runProfile = parentProfile,
        )
        val running = async { parentLoop.run(parentSpec, parentInbox) }
        val card = withTimeout(10_000) { asked.await() }
        parentInbox.send(RunInput.Confirm(ConfirmChoice.Deny, card.askId))
        val end = withTimeout(10_000) { running.await() }
        assertTrue(end.toString(), end is com.wangzan101.mika.core.agent.TaskState.Succeeded)
        val childToolResults = childModel.requests.last().filter { it.role == com.wangzan101.mika.core.model.Role.Tool }
            .flatMap { it.parts.filterIsInstance<Part.ToolResult>() }
        assertTrue(childToolResults.toString(), childToolResults.any { "confirmation_declined" in it.content })
        assertTrue(sb.runs.isEmpty())
    }

    @Test fun unattendedParentDeniesChildConfirmationWithoutEscalation() = runBlocking {
        val sb = FakeSandbox()
        val policy = RuleBasedPermissionPolicy(staticRules = listOf(PermissionRule.ask("ask_run", tool = NameMatcher.Exact("run_command"))))
        val parentProfile = RunProfile.GENERAL.withPermissions(policy)
        var published = 0
        val interaction = object : Interaction {
            override suspend fun publish(ask: Delivery.Ask): DeliveryState {
                published++
                return DeliveryState.Delivered
            }
            override fun resolved(askId: String) = Unit
        }
        val childModel = FakeModel(
            com.wangzan101.mika.core.model.ModelReply(
                listOf(Part.ToolCall("r1", "run_command", """{"command":"echo no","cwd":"workers/a"}""")),
                com.wangzan101.mika.core.model.StopReason.ToolUse, null,
            ),
            answerReply("后台不允许执行"),
        )
        val parentModel = FakeModel(
            com.wangzan101.mika.core.model.ModelReply(
                listOf(Part.ToolCall("s1", "spawn_agent", """{"goal":"执行命令","role":"coder","capabilities":["sandbox"],"workspaceScope":"workers/a"}""")),
                com.wangzan101.mika.core.model.StopReason.ToolUse, null,
            ),
            answerReply("完成"),
        )
        val store = MemoryChildRunStore()
        val runner = ChildAgentRunner({ childSpec ->
            AgentLoop(
                childModel,
                toolDispatcher(FakeScreen(), sandbox = sb, runSpec = childSpec),
                "BASE",
                confirmations = ConfirmationGate(),
                runProfile = childSpec.profile,
            )
        }, store)
        val parentSpec = RunSpec(
            RunId("parent-bg"), "执行命令", "thread",
            provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended),
            profile = parentProfile,
        )
        val parentTools = toolDispatcher(FakeScreen(), sandbox = sb, runSpec = parentSpec, childHost = runner)
        val parentLoop = AgentLoop(
            parentModel, parentTools, "BASE",
            confirmations = ConfirmationGate(),
            interaction = interaction,
            runProfile = parentProfile,
        )
        val end = parentLoop.run(parentSpec, RunInbox())
        assertTrue(end.toString(), end is com.wangzan101.mika.core.agent.TaskState.Succeeded)
        assertEquals(0, published)
        val childToolResults = childModel.requests.last().filter { it.role == com.wangzan101.mika.core.model.Role.Tool }
            .flatMap { it.parts.filterIsInstance<Part.ToolResult>() }
        assertTrue(childToolResults.toString(), childToolResults.any { "approval_unavailable" in it.content })
        assertTrue(sb.runs.isEmpty())
    }
}
