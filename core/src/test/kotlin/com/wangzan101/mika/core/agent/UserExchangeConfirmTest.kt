package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.action.Risk
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.policy.ApprovalScope
import com.wangzan101.mika.core.policy.InMemoryPermissionStore
import com.wangzan101.mika.core.policy.PermissionContext
import com.wangzan101.mika.core.policy.PermissionDecision
import com.wangzan101.mika.core.policy.PermissionPolicy
import com.wangzan101.mika.core.policy.RuleBasedPermissionPolicy
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.DeliveryState
import com.wangzan101.mika.core.run.Interaction
import com.wangzan101.mika.core.run.LocalInteraction
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Outcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class UserExchangeConfirmTest {
    private fun exchange(
        gate: ConfirmationGate? = ConfirmationGate(),
        policy: PermissionPolicy = PermissionPolicy.DEFAULT,
        currentState: () -> TaskState = { TaskState.Running(1, "running") },
        onState: (TaskState) -> Unit = {},
        events: MutableList<RunEvent> = mutableListOf(),
    ): UserExchange = UserExchange(
        tools = toolDispatcher(FakeScreen()),
        confirmations = gate,
        confirmTimeoutMs = 5_000L,
        inputTimeoutMs = 5_000L,
        utterances = { null },
        setState = onState,
        emit = { events += it },
        now = { 1_000L },
        permissionPolicy = { policy },
        currentState = currentState,
    )

    @Test fun sessionAndPersistentConfirmGrantsPolicyAndClearsGateWithoutDanglingGrant() = runTest {
        val store = InMemoryPermissionStore()
        val policy = RuleBasedPermissionPolicy(persistentStore = store)
        val gate = ConfirmationGate()
        val states = mutableListOf<TaskState>()
        val ex = exchange(gate = gate, policy = policy, onState = { states += it })

        lateinit var sessionEx: UserExchange
        sessionEx = ex
        val interaction = object : Interaction {
            override suspend fun publish(ask: Delivery.Ask): DeliveryState {
                sessionEx.answerConfirmation(ConfirmChoice.Session, ask.askId)
                return DeliveryState.Delivered
            }
            override fun resolved(askId: String) = Unit
        }
        sessionEx.begin("t1", RunProvenance(RunOrigin.Ui, Attendance.Present), interaction)

        val appReq = ConfirmationRequest(IrrevAction.submit, "提交表单", "确认提交", null, "com.shop")
        val appRes = sessionEx.confirm(Outcome.Confirm(appReq, "com.shop"), step = 1, running = TaskState.Running(1, "step"))
        assertEquals(JsonPrimitive(true), appRes.content["ok"])
        assertFalse("Session grant must not leave single-use gate grant blocking do_steps", gate.hasGrant)

        val evalApp = policy.evaluate(
            PermissionContext(
                tool = "phone_tap",
                capability = "phone",
                targetApp = "com.shop",
                risk = Risk(ActionRisk.Irreversible, payment = false),
            ),
        )
        assertTrue(evalApp is PermissionDecision.Allow && evalApp.scope == ApprovalScope.Session)

        // Tool/capability-scoped Persistent approval persists into store and clears pending
        val persistentInteraction = object : Interaction {
            override suspend fun publish(ask: Delivery.Ask): DeliveryState {
                sessionEx.answerConfirmation(ConfirmChoice.Persistent, ask.askId)
                return DeliveryState.Delivered
            }
            override fun resolved(askId: String) = Unit
        }
        sessionEx.begin("t1", RunProvenance(RunOrigin.Ui, Attendance.Present), persistentInteraction)

        val toolReq = ConfirmationRequest(IrrevAction.other, "mcp", "create issue", null, "mcp_github")
        val toolRes = sessionEx.confirm(
            Outcome.Confirm(toolReq, "mcp_github"),
            step = 2,
            running = TaskState.Running(2, "step"),
            tool = "mcp_github__create_issue",
            capability = "mcp_github",
        )
        assertEquals(JsonPrimitive(true), toolRes.content["ok"])
        assertFalse(gate.hasGrant)

        val reloaded = RuleBasedPermissionPolicy(persistentStore = store)
        val evalTool = reloaded.evaluate(PermissionContext(tool = "mcp_github__create_issue", capability = "mcp_github"))
        assertTrue(evalTool is PermissionDecision.Allow && evalTool.scope == ApprovalScope.Persistent)

        val evalPayment = reloaded.evaluate(
            PermissionContext(
                tool = "mcp_github__create_issue",
                capability = "mcp_github",
                risk = Risk(ActionRisk.Irreversible, payment = true),
            ),
        )
        assertEquals(PermissionDecision.Abstain, evalPayment)
        assertTrue(states.any { it is TaskState.WaitingConfirmation })
    }

    @Test fun childConfirmEscalatesThroughParentMutexAndFallsBackToDenyWithoutEscalator() = runTest {
        val store = InMemoryPermissionStore()
        val policy = RuleBasedPermissionPolicy(persistentStore = store)
        val activePrompts = AtomicInteger(0)
        val maxConcurrentPrompts = AtomicInteger(0)

        lateinit var parent: UserExchange
        val parentStates = mutableListOf<TaskState>()
        parent = exchange(
            gate = ConfirmationGate(),
            policy = policy,
            currentState = { TaskState.Running(3, "spawning") },
            onState = { parentStates += it },
        )
        val parentInteraction = object : Interaction {
            override suspend fun publish(ask: Delivery.Ask): DeliveryState {
                val now = activePrompts.incrementAndGet()
                maxConcurrentPrompts.updateAndGet { prev -> maxOf(prev, now) }
                delay(20)
                activePrompts.decrementAndGet()
                val choice = if ("allow_once" in ask.text) ConfirmChoice.Once else ConfirmChoice.Session
                parent.answerConfirmation(choice, ask.askId)
                return DeliveryState.Delivered
            }
            override fun resolved(askId: String) = Unit
        }
        parent.begin("parent", RunProvenance(RunOrigin.Ui, Attendance.Present), parentInteraction)

        // Two parallel children escalate through parent.escalateConfirm -> serialized cleanly by confirmMutex
        val childGate1 = ConfirmationGate()
        val childGate2 = ConfirmationGate()
        val child1 = exchange(gate = childGate1, policy = policy)
        val child2 = exchange(gate = childGate2, policy = policy)
        child1.begin("c1", RunProvenance(RunOrigin.Child, Attendance.Unattended), LocalInteraction) { req ->
            parent.escalateConfirm(req)
        }
        child2.begin("c2", RunProvenance(RunOrigin.Child, Attendance.Unattended), LocalInteraction) { req ->
            parent.escalateConfirm(req)
        }

        val req1 = ConfirmationRequest(IrrevAction.other, "allow_once", "echo hi", null, "sandbox")
        val req2 = ConfirmationRequest(IrrevAction.other, "session_tool", "git push", null, "sandbox")
        val (r1, r2) = listOf(
            async { child1.confirm(Outcome.Confirm(req1, "sandbox"), step = 1, running = TaskState.Running(1, "c1"), tool = "sandbox_exec", capability = "sandbox") },
            async { child2.confirm(Outcome.Confirm(req2, "sandbox"), step = 1, running = TaskState.Running(1, "c2"), tool = "sandbox_exec", capability = "sandbox") },
        ).awaitAll()

        assertEquals(JsonPrimitive(true), r1.content["ok"])
        assertEquals(JsonPrimitive(true), r2.content["ok"])
        assertEquals(1, maxConcurrentPrompts.get())
        assertTrue(parentStates.lastOrNull() is TaskState.Running)
        assertTrue(
            policy.evaluate(PermissionContext(tool = "sandbox_exec", capability = "sandbox")) is PermissionDecision.Allow,
        )

        // Child without confirmEscalator (unattended) still strictly denies
        val unattendedChild = exchange(gate = ConfirmationGate(), policy = policy)
        unattendedChild.begin("c3", RunProvenance(RunOrigin.Child, Attendance.Unattended), LocalInteraction, confirmEscalator = null)
        val denied = unattendedChild.confirm(Outcome.Confirm(req1, "sandbox"), step = 1, running = TaskState.Running(1, "c3"))
        assertEquals(JsonPrimitive(false), denied.content["ok"])
        assertEquals(JsonPrimitive("confirmation_declined"), denied.content["error"])
    }
}
