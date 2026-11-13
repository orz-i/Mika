package com.wangzan101.mika.core.child

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.general.SpawnAgent
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ParallelSpawnAgentTest {
    @Test fun spawnAgentIsMarkedParallelSafe() {
        assertEquals(Concurrency.ParallelSafe, SpawnAgent.concurrency)
    }

    @Test fun multipleSpawnAgentCallsInOneTurnRunConcurrentlyAndPreserveOrder() = runTest {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val bothEntered = CompletableDeferred<Unit>()
        val seenSpecs = mutableListOf<RunSpec>()
        val host = ChildRunHost { spec, _ ->
            synchronized(seenSpecs) { seenSpecs += spec }
            val cur = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, cur) }
            if (cur >= 2) bothEntered.complete(Unit) else withTimeout(3_000) { bothEntered.await() }
            inFlight.decrementAndGet()
            ChildResult(spec.id, ChildPhase.Completed, "result:${spec.goal}")
        }
        val parentSpec = RunSpec(
            id = RunId("parent"),
            goal = "parallel subagents",
            threadId = "thread",
            capabilities = setOf("phone", "web", "sandbox"),
        )
        val c1 = Part.ToolCall("sp1", "spawn_agent", """{"goal":"task-A","role":"researcher"}""")
        val c2 = Part.ToolCall("sp2", "spawn_agent", """{"goal":"task-B","role":"coder","workspaceScope":"workers/b"}""")
        val model = FakeModel(
            ModelReply(listOf(c1, c2), StopReason.ToolUse, null),
            answerReply("both-done"),
        )
        val end = AgentLoop(
            model = model,
            tools = toolDispatcher(FakeScreen(), runSpec = parentSpec, childHost = host),
            basePrompt = "BASE",
            confirmations = null,
        ).run(parentSpec, com.wangzan101.mika.core.run.RunInbox())

        assertTrue(end is TaskState.Succeeded)
        assertEquals("both-done", (end as TaskState.Succeeded).answer)
        assertEquals(2, maxInFlight.get())
        val toolResults = model.requests[1]
            .flatMap { it.parts }
            .filterIsInstance<Part.ToolResult>()
        assertEquals(listOf("sp1", "sp2"), toolResults.map { it.callId })
        assertTrue(toolResults.none { it.isError })
        val json1 = Json.parseToJsonElement(toolResults[0].content).jsonObject
        val json2 = Json.parseToJsonElement(toolResults[1].content).jsonObject
        assertTrue("task-A" in json1["result"]!!.jsonPrimitive.content)
        assertTrue("task-B" in json2["result"]!!.jsonPrimitive.content)
        assertEquals("researcher", json1["role"]!!.jsonPrimitive.content)
        assertEquals("coder", json2["role"]!!.jsonPrimitive.content)
        assertEquals("workers/b", json2["workspaceScope"]!!.jsonPrimitive.content)
        assertEquals(true, json1["readOnly"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(false, json2["readOnly"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test fun coderAndReviewerRolePresetsConfigureCapabilitiesAndDefaultWorkspaceScope() = runTest {
        val captured = mutableListOf<RunSpec>()
        val host = ChildRunHost { spec, _ ->
            captured += spec
            ChildResult(spec.id, ChildPhase.Completed, "ok")
        }
        val parentSpec = RunSpec(
            id = RunId("parent"),
            goal = "delegate",
            threadId = "thread",
            capabilities = setOf("web", "sandbox", "mcp:github"),
        )
        val tools = toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = parentSpec, childHost = host)

        val coderRes = tools.execute(
            Part.ToolCall("c1", "spawn_agent", """{"goal":"write module","role":"coder","capabilities":["sandbox","mcp"]}"""),
            ActionPass(),
        ).content
        val coderSpec = captured[0]
        assertEquals("coder", coderSpec.role)
        assertFalse(coderSpec.readOnly)
        assertEquals(setOf("sandbox", "mcp:github"), coderSpec.capabilities)
        assertTrue(coderSpec.workspaceScope!!.startsWith("subagents/"))
        assertEquals(coderSpec.workspaceScope, coderRes["workspaceScope"]!!.jsonPrimitive.content)

        val reviewerRes = tools.execute(
            Part.ToolCall("c2", "spawn_agent", """{"goal":"review module","role":"reviewer"}"""),
            ActionPass(),
        ).content
        val reviewerSpec = captured[1]
        assertEquals("reviewer", reviewerSpec.role)
        assertTrue(reviewerSpec.readOnly)
        assertEquals(setOf("sandbox", "web"), reviewerSpec.capabilities)
        assertEquals(null, reviewerSpec.workspaceScope)
        assertEquals("true", reviewerRes["readOnly"].toString())
    }
}
