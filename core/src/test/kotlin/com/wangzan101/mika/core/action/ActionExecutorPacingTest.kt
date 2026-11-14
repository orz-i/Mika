package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.gate.DeclareResult
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.policy.NameMatcher
import com.wangzan101.mika.core.policy.PacingContext
import com.wangzan101.mika.core.policy.PacingDirective
import com.wangzan101.mika.core.policy.PacingPolicy
import com.wangzan101.mika.core.policy.PermissionRule
import com.wangzan101.mika.core.policy.RuleBasedPermissionPolicy
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunInput
import com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.ToolTraits
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorPacingTest {
    private class CollectingSink : RunSink {
        val events = mutableListOf<RunEvent>()
        override fun event(e: RunEvent) {
            events += e
        }
    }

    @Test
    fun pacingPolicyBeforeAndAfterHooksDriveWaitIdleScrollSiteLoadAndUserAnswer() = runTest {
        val sink = CollectingSink()
        val now = 1_000L
        val beforeCalls = mutableListOf<PacingContext>()
        val afterCalls = mutableListOf<Pair<PacingContext, Boolean>>()
        val sitesLoaded = mutableListOf<String>()
        var userAnsweredCount = 0

        val policy = object : PacingPolicy {
            override val enabled = true
            override fun beforeAction(ctx: PacingContext): PacingDirective {
                beforeCalls += ctx
                return when (ctx.tool) {
                    "web_navigate" -> PacingDirective(waitMs = 5L, idleScroll = true)
                    else -> PacingDirective.NONE
                }
            }

            override fun afterAction(ctx: PacingContext, succeeded: Boolean) {
                afterCalls += ctx to succeeded
            }

            override fun onSiteLoaded(site: String, nowMs: Long) {
                sitesLoaded += site
            }

            override fun onUserAnswered() {
                userAnsweredCount++
            }
        }

        val tools = toolDispatcher(FakeScreen(), web = FakeWebPort())
        val executor = ActionExecutor(
            tools = tools,
            guards = OperationGuards(),
            confirmations = null,
            gate = {},
            recorder = ActionRecorder(sink, clock = { now }),
            autonomous = true,
            pacingPolicy = policy,
        )

        val navOut = executor.run(Part.ToolCall("n1", "web_navigate", """{"url":"https://shop.example.com/cart"}"""), Origin.Model(1))
        assertTrue("$navOut", navOut is ActionOutcome.Ran)
        assertEquals(1, beforeCalls.size)
        assertEquals("web_navigate", beforeCalls.single().tool)
        assertEquals("web", beforeCalls.single().capability)
        assertTrue(beforeCalls.single().writesScreen)
        assertEquals(1, afterCalls.size)
        assertTrue(afterCalls.single().second)
        assertEquals(listOf("shop.example.com"), sitesLoaded)

        val resultEvent = sink.events.filterIsInstance<RunEvent.ToolResult>().single()
        val parsed = Json.parseToJsonElement(resultEvent.resultJson).jsonObject
        assertEquals("1", parsed["idleScrolls"]?.jsonPrimitive?.content)
        assertTrue("expected waitMs >= 5", resultEvent.waitMs >= 5L)

        executor.onUserAnswered()
        assertEquals(1, userAnsweredCount)
    }

    @Test
    fun pacingPolicyStopReasonHaltsActionBeforeExecutionWithoutAfterHook() = runTest {
        val sink = CollectingSink()
        var tapCalled = 0
        var afterCalled = false
        val fake = FakeScreen()
        val screen = object : ScreenPort by fake {
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                tapCalled++
                return fake.tapXY(x, y)
            }
        }
        val policy = object : PacingPolicy {
            override val enabled = true
            override fun beforeAction(ctx: PacingContext) = PacingDirective(stopReason = "anti_crawl_limit")
            override fun afterAction(ctx: PacingContext, succeeded: Boolean) {
                afterCalled = true
            }
        }
        val executor = ActionExecutor(
            tools = toolDispatcher(screen),
            guards = OperationGuards(),
            confirmations = null,
            gate = {},
            recorder = ActionRecorder(sink),
            autonomous = true,
            pacingPolicy = policy,
        )

        val out = executor.run(Part.ToolCall("t1", "phone_tap", """{"x":10,"y":20}"""), Origin.Model(1))
        assertTrue("$out", out is ActionOutcome.Ran)
        val exec = (out as ActionOutcome.Ran).exec
        assertEquals(JsonPrimitive(false), exec.content["ok"])
        assertEquals(JsonPrimitive("paced_stop"), exec.content["error"])
        assertEquals(JsonPrimitive(false), exec.content["recoverable"])
        assertEquals(0, tapCalled)
        assertFalse(afterCalled)
    }

    @Test
    fun pacingPolicyWaitCanBeInterruptedByUserSteer() = runTest {
        var tapCalled = 0
        val fake = FakeScreen()
        val screen = object : ScreenPort by fake {
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                tapCalled++
                return fake.tapXY(x, y)
            }
        }
        val policy = object : PacingPolicy {
            override val enabled = true
            override fun beforeAction(ctx: PacingContext) = PacingDirective(waitMs = 5_000L)
        }
        val executor = ActionExecutor(
            tools = toolDispatcher(screen),
            guards = OperationGuards(),
            confirmations = null,
            gate = {},
            recorder = ActionRecorder(RunSink.NONE),
            autonomous = true,
            pacingPolicy = policy,
        )

        val inbox = RunInbox()
        withContext(inbox.signal) {
            val job = launch {
                delay(20L)
                inbox.send(RunInput.Steer("s1", "stop"))
            }
            val out = executor.run(Part.ToolCall("t1", "phone_tap", """{"x":10,"y":20}"""), Origin.Model(1))
            job.join()
            assertTrue("$out", out is ActionOutcome.Ran)
            val exec = (out as ActionOutcome.Ran).exec
            assertEquals(JsonPrimitive(SKIPPED_FOR_NEW_MESSAGE), exec.content["error"])
            assertEquals(0, tapCalled)
        }
    }

    @Test
    fun recorderAndActivityRecordUseTraitsWithoutVerbHardcoding() {
        val sink = CollectingSink()
        val recorder = ActionRecorder(sink)
        recorder.result(
            step = 1,
            tool = "custom_ask",
            verb = Verb.Query,
            content = buildJsonObject {
                put("ok", true)
                put("answer", "123456")
            },
            extra = JsonObject(emptyMap()),
            changed = null,
            shotId = null,
            durationMs = 10L,
            source = "model",
            callId = "c1",
            traits = ToolTraits(hidesAnswerInRecord = true),
        )
        val recorded = sink.events.filterIsInstance<RunEvent.ToolResult>().single().resultJson
        assertFalse("answer must be hidden when hidesAnswerInRecord trait is true", recorded.contains("123456"))

        val tools = toolDispatcher(FakeScreen())
        assertNull(
            ActivityRecord.of(
                tools = tools,
                tool = "wait",
                args = JsonObject(emptyMap()),
                exec = ToolExecution(buildJsonObject { put("ok", true) }),
                step = 1,
                source = "model",
                callId = "w1",
                atMs = 100L,
            ),
        )
    }

    @Test
    fun offScreenToolAskedByPolicyRejudgesViaExactAskAfterOnceApproval() = runTest {
        val confirmations = ConfirmationGate()
        val policy = RuleBasedPermissionPolicy(
            staticRules = listOf(PermissionRule.ask("ask_schedule", tool = NameMatcher.Exact("schedule_task"))),
        )
        val tools = toolDispatcher(FakeScreen(), scheduleTrigger = { Scheduled(onTime = true) })
        val executor = ActionExecutor(
            tools = tools,
            guards = OperationGuards(),
            confirmations = confirmations,
            approvals = ApprovalSettings.of(autonomous = false, policy = policy),
            gate = {},
            recorder = ActionRecorder(RunSink.NONE),
        )

        var approvedCount = 0
        val out = executor.run(
            Part.ToolCall("s1", "schedule_task", """{"goal":"提醒喝水","at":"2026-11-14T09:00:00+08:00"}"""),
            Origin.Model(1),
            object : FollowUp {
                override suspend fun approve(request: ConfirmationRequest): ToolExecution {
                    approvedCount++
                    assertEquals(DeclareResult.Pending, confirmations.declare(request, request.app))
                    confirmations.decide(ConfirmChoice.Once, step = 1)
                    return ToolExecution(buildJsonObject { put("ok", true) })
                }
            },
        )

        assertEquals(1, approvedCount)
        assertTrue("$out", out is ActionOutcome.Ran)
        assertEquals(JsonPrimitive(true), (out as ActionOutcome.Ran).exec.content["ok"])
    }
}
