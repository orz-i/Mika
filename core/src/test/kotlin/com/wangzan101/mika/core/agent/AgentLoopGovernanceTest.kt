package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.mcp.McpAnnotations
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.cap.mcp.McpEndpoint
import com.wangzan101.mika.core.cap.mcp.McpResult
import com.wangzan101.mika.core.cap.mcp.McpToolDescriptor
import com.wangzan101.mika.core.cap.mcp.McpTrust
import com.wangzan101.mika.core.cap.mcp.mcpToolNames
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.InputReceipt
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.wangzan101.mika.core.settings.SelfConfig
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ModelTier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 调度、路由和收尾共用的不变量；不调用真实模型、设置或 MCP 服务。 */
class AgentLoopGovernanceTest {
    private fun answer(text: String = "完成") = ModelReply(listOf(Part.Text(text)), StopReason.EndTurn, null)
    private fun call(id: String, name: String, args: String = "{}") = Part.ToolCall(id, name, args)
    private fun calls(vararg calls: Part.ToolCall) = ModelReply(calls.toList(), StopReason.ToolUse, null)
    private fun spec() = RunSpec(RunId("governance"), "完成当前任务", "thread")
    private fun List<Message>.results() = flatMap { it.parts }.filterIsInstance<Part.ToolResult>()
    private fun Part.ToolResult.error() = (Json.parseToJsonElement(content) as JsonObject)["error"]

    private class Model(override val name: String = "main", private val script: suspend (Int, List<Message>, List<ToolSpec>) -> ModelReply) : ChatModel {
        override val capabilities = Capabilities(true, false, 128_000, false)
        val requests = mutableListOf<List<Message>>()
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            requests += messages.toList()
            return script(requests.size, messages, tools)
        }
    }

    private class Events : RunSink {
        val items = mutableListOf<RunEvent>()
        override fun event(e: RunEvent) { items += e }
    }

    private fun assertPaired(history: List<Message>) {
        val pending = linkedSetOf<String>()
        for (message in history) {
            for (part in message.parts) when (part) {
                is Part.ToolCall -> assertTrue("调用重复或未收口: ${part.id}", pending.add(part.id))
                is Part.ToolResult -> assertTrue("结果没有对应调用: ${part.callId}", pending.remove(part.callId))
                else -> Unit
            }
            if (message.author == Author.User) assertTrue("插话前调用必须收口: $pending", pending.isEmpty())
        }
        assertTrue("请求含悬空调用: $pending", pending.isEmpty())
    }

    @Test fun closeoutSteerPairsEveryUnexecutedCallBeforeReasking() = runTest {
        val inbox = RunInbox()
        val screen = FakeScreen()
        val model = Model { n, history, _ ->
            when (n) {
                1 -> calls(call("read", "phone_list_apps"))
                2 -> {
                    inbox.steer("delta", "按补充要求收尾")
                    calls(call("final-1", "end_not_done", """{"message":"暂未完成"}"""), call("invalid-final", "phone_back"),
                        call("final-2", "end_not_done", """{"message":"另一条提议"}"""))
                }
                else -> { assertPaired(history); answer("新的收尾") }
            }
        }
        val end = AgentLoop(model, toolDispatcher(screen), "BASE", maxSteps = 1).run(spec(), inbox)
        assertTrue(end is TaskState.Succeeded && end.answer == "新的收尾")
        assertEquals(3, model.requests.size)
        assertEquals(3, model.requests.last().results().count { it.error() == JsonPrimitive(SKIPPED_FOR_NEW_MESSAGE) })
        assertTrue(screen.calls.isEmpty())
    }

    @Test fun lightProposalIsRejectedAsAWholeWhenLaterCallNeedsMain() = runTest {
        val main = Model { n, _, _ -> if (n <= 2) calls(call("main-$n", "phone_observe")) else answer("主模型收尾") }
        val light = Model("light") { _, _, _ -> calls(call("light-safe", "phone_observe"),
            call("light-stop", "end_not_done", """{"message":"不应执行"}""")) }
        val events = Events()
        val end = AgentLoop(main, toolDispatcher(FakeScreen()), "BASE", maxSteps = 6, flags = SpeedFlags(lightSteps = true),
            router = ModelRouter(main, light), sink = events).run("完成任务")
        assertTrue(end is TaskState.Succeeded && end.answer == "主模型收尾")
        assertEquals(3, main.requests.size)
        assertEquals(1, light.requests.size)
        assertTrue(events.items.filterIsInstance<RunEvent.ToolResult>().none { it.callId?.startsWith("light-") == true })
        assertTrue(events.items.filterIsInstance<RunEvent.State>().any { it.name == "light_to_main" && it.detail == "end_not_done" })
    }

    @Test fun customMainOnlyTraitsApplyEvenForAutonomousMcpCalls() = runTest {
        var changes = 0
        val endpoint = object : McpEndpoint {
            override val serverId = "tier"
            override fun tools() = listOf(McpToolDescriptor("danger", "Fake change", schema(), McpAnnotations(destructiveHint = true)))
            override suspend fun call(name: String, arguments: JsonObject): McpResult { changes++; return McpResult("changed") }
        }
        val dispatcher = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint)))
        val tool = mcpToolNames("tier", listOf("danger")).single()
        assertEquals(ModelTier.MainModelOnly, dispatcher.traits(tool)!!.modelTier)
        val main = Model { n, _, _ -> if (n <= 2) calls(call("main-$n", "phone_observe")) else answer() }
        val light = Model("light") { _, _, _ -> calls(call("danger", tool)) }
        val end = AgentLoop(main, dispatcher, "BASE", autonomous = true, flags = SpeedFlags(lightSteps = true), router = ModelRouter(main, light)).run("模拟任务")
        assertTrue(end is TaskState.Succeeded)
        assertEquals(0, changes)
        assertEquals(3, main.requests.size)
    }

    @Test fun steerSkipsTheNextSerialWriteButKeepsItsResultPaired() = runTest {
        val inbox = RunInbox()
        val writes = mutableListOf<String>()
        val config = object : SelfConfig {
            override fun options() = listOf(SelfConfig.Option("pref", "模拟偏好", "zero", listOf("one", "two")))
            override fun set(key: String, value: String): String? {
                writes += value
                if (value == "one") inbox.steer("stop", "先停止后续修改")
                return null
            }
        }
        val model = Model { n, history, _ -> if (n == 1) calls(
            call("first", "settings_set", """{"key":"pref","value":"one"}"""),
            call("second", "settings_set", """{"key":"pref","value":"two"}"""),
        ) else { assertPaired(history); answer() } }
        AgentLoop(model, toolDispatcher(FakeScreen(), selfConfig = config), "BASE").run(spec(), inbox)
        assertEquals(listOf("one"), writes)
        val skipped = model.requests.last().results().single { it.callId == "second" }
        assertEquals(JsonPrimitive(SKIPPED_FOR_NEW_MESSAGE), skipped.error())
        assertEquals(JsonPrimitive(false), (Json.parseToJsonElement(skipped.content) as JsonObject)["executed"])
    }

    @Test fun outerCancellationPublishesTheSameTerminalAsTheEvent() = runTest {
        val entered = CompletableDeferred<Unit>()
        val events = Events()
        val agent = AgentLoop(Model { _, _, _ -> entered.complete(Unit); awaitCancellation() }, toolDispatcher(FakeScreen()), "BASE", sink = events)
        val run = async { agent.run(spec(), RunInbox()) }
        entered.await()
        run.cancelAndJoin()
        assertEquals(TaskState.Cancelled, agent.state.value)
        assertEquals("cancelled", events.items.filterIsInstance<RunEvent.TaskEnded>().single().outcome)
    }

    @Test fun gateFailurePublishesFailureButStillPropagatesTheOriginalException() = runTest {
        val events = Events()
        val problem = IllegalStateException("模拟 gate 故障")
        val agent = AgentLoop(Model { _, _, _ -> calls(call("back", "phone_back")) }, toolDispatcher(FakeScreen()), "BASE", gate = { throw problem }, sink = events)
        val thrown = runCatching { agent.run(spec(), RunInbox()) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(problem.message, thrown!!.message)
        assertTrue(agent.state.value is TaskState.Failed && (agent.state.value as TaskState.Failed).kind == FailKind.Environment)
        assertEquals("failed", events.items.filterIsInstance<RunEvent.TaskEnded>().single().outcome)
    }

    private fun schema() = JsonObject(mapOf("type" to JsonPrimitive("object")))

    @Test fun parallelCallsAreBoundedAndResultsRemainInModelOrder() = runTest {
        var active = 0
        var peak = 0
        val endpoint = object : McpEndpoint {
            override val serverId = "bounded"
            override fun tools() = listOf(McpToolDescriptor("read", "Fake read", schema(), McpAnnotations(readOnlyHint = true)))
            override suspend fun call(name: String, arguments: JsonObject): McpResult {
                active++; peak = maxOf(peak, active)
                try { delay(10) } finally { active-- }
                return McpResult("read")
            }
        }
        val tool = mcpToolNames(endpoint.serverId, listOf("read")).single()
        val model = Model { n, history, _ -> if (n == 1) calls(*(1..12).map { call("p$it", tool) }.toTypedArray()) else { assertPaired(history); answer() } }
        val dispatcher = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint, trust = McpTrust.TrustAnnotations)))
        assertTrue(AgentLoop(model, dispatcher, "BASE", maxSteps = 2).run("读取") is TaskState.Succeeded)
        assertTrue("同一端点最多两次在途: $peak", peak in 1..2)
        assertEquals((1..12).map { "p$it" }, model.requests.last().results().map { it.callId })
    }

    @Test fun steerAlsoSkipsParallelCallsWaitingForAnAdmissionSlot() = runTest {
        val inbox = RunInbox()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var started = 0
        val endpoint = object : McpEndpoint {
            override val serverId = "pending"
            override fun tools() = listOf(McpToolDescriptor("read", "Fake read", schema(), McpAnnotations(readOnlyHint = true)))
            override suspend fun call(name: String, arguments: JsonObject): McpResult {
                if (++started == 2) entered.complete(Unit)
                release.await()
                return McpResult("read")
            }
        }
        val tool = mcpToolNames(endpoint.serverId, listOf("read")).single()
        val model = Model { n, history, _ -> if (n == 1) calls(*(1..12).map { call("p$it", tool) }.toTypedArray()) else { assertPaired(history); answer() } }
        val dispatcher = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint, trust = McpTrust.TrustAnnotations)))
        val run = async { AgentLoop(model, dispatcher, "BASE").run(spec(), inbox) }
        entered.await()
        inbox.steer("delta", "换个方向")
        release.complete(Unit)
        assertTrue(run.await() is TaskState.Succeeded)
        assertEquals(2, started)
        assertEquals(10, model.requests.last().results().count { it.error() == JsonPrimitive(SKIPPED_FOR_NEW_MESSAGE) })
    }

    @Test fun oversizedReplyIsRejectedWithoutExecutingAPartialPlan() = runTest {
        var changes = 0
        val config = object : SelfConfig {
            override fun options() = listOf(SelfConfig.Option("pref", "模拟", "zero", listOf("one")))
            override fun set(key: String, value: String): String? { changes++; return null }
        }
        val model = Model { n, history, _ -> if (n == 1) calls(*(1..65).map {
            call("w$it", "settings_set", """{"key":"pref","value":"one"}""")
        }.toTypedArray()) else { assertPaired(history); answer() } }
        AgentLoop(model, toolDispatcher(FakeScreen(), selfConfig = config), "BASE").run("修改")
        assertEquals(0, changes)
        assertEquals(65, model.requests.last().results().count { it.error() == JsonPrimitive("tool_call_limit") })
    }

    @Test fun discussionCannotExecuteCallsReturnedAgainstTheEmptyToolOffer() = runTest {
        var changes = 0
        val config = object : SelfConfig {
            override fun options() = listOf(SelfConfig.Option("pref", "模拟", "zero", listOf("one")))
            override fun set(key: String, value: String): String? { changes++; return null }
        }
        var emptyOfferSeen = false
        val main = Model { n, _, tools ->
            if (n == 2) emptyOfferSeen = tools.isEmpty()
            calls(call("w$n", "settings_set", """{"key":"pref","value":"one"}"""))
        }
        val judge = Model("judge") { _, _, _ -> answer("DISCUSS") }
        val end = AgentLoop(main, toolDispatcher(FakeScreen(), selfConfig = config), "BASE", flags = SpeedFlags(strictIntentGate = true), intentJudge = judge).run("只讨论")
        assertTrue(emptyOfferSeen)
        assertEquals(0, changes)
        assertTrue(end is TaskState.Failed && end.kind == FailKind.Model)
    }

    @Test fun multipleEndpointsStillRespectTheGlobalWorkerLimit() = runTest {
        var active = 0
        var peak = 0
        val activeByServer = mutableMapOf<String, Int>()
        val peaks = mutableMapOf<String, Int>()
        fun endpoint(id: String) = object : McpEndpoint {
            override val serverId = id
            override fun tools() = listOf(McpToolDescriptor("read", "Fake read", schema(), McpAnnotations(readOnlyHint = true)))
            override suspend fun call(name: String, arguments: JsonObject): McpResult {
                active++; peak = maxOf(peak, active)
                activeByServer[id] = activeByServer.getOrDefault(id, 0) + 1
                peaks[id] = maxOf(peaks.getOrDefault(id, 0), activeByServer.getValue(id))
                try { delay(10) } finally { active--; activeByServer[id] = activeByServer.getValue(id) - 1 }
                return McpResult("read")
            }
        }
        val endpoints = listOf("alpha", "beta", "gamma").map(::endpoint)
        val names = endpoints.map { mcpToolNames(it.serverId, listOf("read")).single() }
        val main = Model { n, history, _ ->
            if (n == 1) calls(*(0..11).map { call("p$it", names[it % names.size]) }.toTypedArray())
            else { assertPaired(history); answer() }
        }
        val dispatcher = toolDispatcher(FakeScreen(), mcpServers = endpoints.map { McpConnection(it, trust = McpTrust.TrustAnnotations) })
        assertTrue(AgentLoop(main, dispatcher, "BASE").run("读数据") is TaskState.Succeeded)
        assertEquals(4, peak)
        assertTrue(peaks.values.all { it <= 2 })
        assertEquals(0, active)
    }

    @Test fun cancellationDoesNotStartCallsThatAreQueuedBehindPermits() = runTest {
        val entered = CompletableDeferred<Unit>()
        var started = 0
        var active = 0
        val endpoint = object : McpEndpoint {
            override val serverId = "cancel"
            override fun tools() = listOf(McpToolDescriptor("read", "Fake read", schema(), McpAnnotations(readOnlyHint = true)))
            override suspend fun call(name: String, arguments: JsonObject): McpResult {
                active++
                if (++started == 2) entered.complete(Unit)
                try { awaitCancellation() } finally { active-- }
            }
        }
        val tool = mcpToolNames(endpoint.serverId, listOf("read")).single()
        val main = Model { _, _, _ -> calls(*(1..12).map { call("p$it", tool) }.toTypedArray()) }
        val inbox = RunInbox()
        val dispatcher = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint, trust = McpTrust.TrustAnnotations)))
        val agent = AgentLoop(main, dispatcher, "BASE")
        val run = async { agent.run(spec(), inbox) }
        entered.await()
        inbox.cancel()
        assertEquals(TaskState.Cancelled, run.await())
        assertEquals(TaskState.Cancelled, agent.state.value)
        assertEquals(2, started)
        assertEquals(0, active)
    }

    @Test fun aLookupFirstCannotHideAWritingCallFromTheDiscussionCheck() = runTest {
        var changes = 0
        val config = object : SelfConfig {
            override fun options() = listOf(SelfConfig.Option("pref", "模拟", "zero", listOf("one")))
            override fun set(key: String, value: String): String? { changes++; return null }
        }
        val main = Model { n, _, offered ->
            if (n == 1) calls(call("lookup", "knowledge_read", """{"id":"app:wechat"}"""),
                call("write", "settings_set", """{"key":"pref","value":"one"}"""))
            else { assertTrue(offered.isEmpty()); answer("只讨论") }
        }
        val judge = Model("judge") { _, _, _ -> answer("DISCUSS") }
        assertTrue(AgentLoop(main, toolDispatcher(FakeScreen(), selfConfig = config), "BASE", flags = SpeedFlags(strictIntentGate = true), intentJudge = judge).run("只讨论") is TaskState.Succeeded)
        assertEquals(0, changes)
        assertEquals(1, judge.requests.size)
    }

    @Test fun controlInboxIsClosedBeforeAnExceptionalEndedEventIsPublished() = runTest {
        val inbox = RunInbox()
        var late: InputReceipt? = null
        val sink = object : RunSink {
            override fun event(e: RunEvent) {
                if (e is RunEvent.TaskEnded) late = inbox.steerCurrent("late", "不要误收为当前任务")
            }
        }
        val agent = AgentLoop(Model { _, _, _ -> calls(call("back", "phone_back")) }, toolDispatcher(FakeScreen()), "BASE",
            gate = { error("模拟环境故障") }, sink = sink)
        assertTrue(runCatching { agent.run(spec(), inbox) }.isFailure)
        assertEquals(InputReceipt.Closed, late)
        assertTrue(inbox.handoffUnconsumed().isEmpty())
    }

    @Test fun closeoutSteerAfterExecutionKeepsActualAndSkippedResultsSeparate() = runTest {
        val inbox = RunInbox()
        val sink = object : RunSink {
            override fun event(e: RunEvent) {
                if (e is RunEvent.ToolResult && e.callId == "final") inbox.steer("delta", "补充后重新整理")
            }
        }
        val main = Model { n, history, _ -> when (n) {
            1 -> calls(call("read", "phone_list_apps"))
            2 -> calls(call("final", "end_not_done", """{"message":"只完成一部分"}"""), call("unexecuted", "phone_back"))
            else -> {
                assertPaired(history)
                val results = history.results()
                assertTrue(results.single { it.callId == "final" }.content.contains("\"reported\":true"))
                assertEquals(JsonPrimitive("closeout_tool_not_allowed"), results.single { it.callId == "unexecuted" }.error())
                answer("重新整理后的回答")
            }
        } }
        val screen = FakeScreen()
        val end = AgentLoop(main, toolDispatcher(screen), "BASE", maxSteps = 1, sink = sink).run(spec(), inbox)
        assertTrue(end is TaskState.Succeeded && end.answer == "重新整理后的回答")
        assertEquals(3, main.requests.size)
        assertTrue(screen.calls.isEmpty())
    }
}
