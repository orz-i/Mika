package com.wangzan101.mika.core.policy

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.action.ConfirmDecision
import com.wangzan101.mika.core.action.Decision
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.Risk
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test fun matchesByToolCapabilityVerbAndArgPatterns() {
        val policy = RuleBasedPermissionPolicy(
            staticRules = listOf(
                PermissionRule.deny(
                    id = "deny-rm-rf",
                    tool = NameMatcher.Exact("run_command"),
                    capability = NameMatcher.Exact("sandbox"),
                    argPatterns = mapOf("command" to ArgMatcher.Contains("rm -rf")),
                    hard = true,
                    reason = "destructive delete",
                ),
                PermissionRule.ask(
                    id = "ask-mcp-glob",
                    tool = NameMatcher.Glob("mcp_*"),
                    reason = "confirm external mcp",
                ),
                PermissionRule.allow(
                    id = "allow-read-file",
                    verb = Verb.ReadFile,
                ),
            ),
        )

        val rmRf = policy.evaluate(
            PermissionContext(
                tool = "run_command",
                capability = "sandbox",
                verb = Verb.RunCommand,
                args = buildJsonObject { put("command", "rm -rf /workspace/data") },
            ),
        )
        assertTrue(rmRf is PermissionDecision.Deny && rmRf.hard && rmRf.reason == "destructive delete")

        val safeCmd = policy.evaluate(
            PermissionContext(
                tool = "run_command",
                capability = "sandbox",
                verb = Verb.RunCommand,
                args = buildJsonObject { put("command", "ls -la") },
            ),
        )
        assertEquals(PermissionDecision.Abstain, safeCmd)

        val mcp = policy.evaluate(PermissionContext(tool = "mcp_github__create_issue", capability = "mcp_github"))
        assertTrue(mcp is PermissionDecision.Ask && mcp.ruleId == "ask-mcp-glob")

        val read = policy.evaluate(PermissionContext(tool = "read_file", capability = "sandbox", verb = Verb.ReadFile))
        assertTrue(read is PermissionDecision.Allow && read.ruleId == "allow-read-file")
    }

    @Test fun supportsOnceSessionAndPersistentScopes() {
        val store = InMemoryPermissionStore()
        val policy1 = RuleBasedPermissionPolicy(
            staticRules = listOf(PermissionRule.ask(id = "ask-root", tool = NameMatcher.Exact("root_run_command"))),
            persistentStore = store,
        )
        val ctx = PermissionContext(tool = "root_run_command", capability = "root", risk = Risk(ActionRisk.External))

        // 默认命中静态 Ask
        assertTrue(policy1.evaluate(ctx) is PermissionDecision.Ask)

        // Once 放行一次后失效
        policy1.grant(PermissionRule.allow("once-root", tool = NameMatcher.Exact("root_run_command"), scope = ApprovalScope.Once))
        assertEquals(PermissionDecision.Allow(ApprovalScope.Once, "once-root"), policy1.evaluate(ctx))
        assertTrue(policy1.evaluate(ctx) is PermissionDecision.Ask)

        // Session 放行在本会话内持久生效，清理会话后失效
        policy1.grant(PermissionRule.allow("sess-root", tool = NameMatcher.Exact("root_run_command"), scope = ApprovalScope.Session))
        assertEquals(PermissionDecision.Allow(ApprovalScope.Session, "sess-root"), policy1.evaluate(ctx))
        assertEquals(PermissionDecision.Allow(ApprovalScope.Session, "sess-root"), policy1.evaluate(ctx))
        policy1.clearSession()
        assertTrue(policy1.evaluate(ctx) is PermissionDecision.Ask)

        // Persistent 存入 store 后跨策略实例仍然生效
        policy1.grant(PermissionRule.allow("persist-root", tool = NameMatcher.Exact("root_run_command"), scope = ApprovalScope.Persistent))
        val policy2 = RuleBasedPermissionPolicy(
            staticRules = listOf(PermissionRule.ask(id = "ask-root", tool = NameMatcher.Exact("root_run_command"))),
            persistentStore = store,
        )
        assertEquals(PermissionDecision.Allow(ApprovalScope.Persistent, "persist-root"), policy2.evaluate(ctx))
        assertEquals(ApprovalScope.Once, ConfirmChoice.Once.scope)
        assertEquals(ApprovalScope.Session, ConfirmChoice.Session.scope)
        assertEquals(ApprovalScope.Persistent, ConfirmChoice.Persistent.scope)
    }

    @Test fun autonomousNeverBypassesOwnAppOrHardDeny() = runTest {
        val mikaScreen = object : ScreenPort by FakeScreen() {
            private val snap = ScreenSnapshot(
                "s1", "com.mika",
                listOf(UiElement("@e1", "button", "确定", 500, 500, listOf(100, 100, 900, 200), null, true, false, false)),
                1080, 2400,
            )
            override suspend fun snapshot() = snap
            override suspend fun peek() = snap
        }
        val otherScreen = object : ScreenPort by FakeScreen() {
            private val snap = ScreenSnapshot(
                "s1", "com.shop",
                listOf(UiElement("@e1", "button", "提交订单", 500, 500, listOf(100, 100, 900, 200), null, true, false, false)),
                1080, 2400,
            )
            override suspend fun snapshot() = snap
            override suspend fun peek() = snap
        }

        // 1. autonomous = true 时，ownApp 仍然拦截
        val toolsSelf = toolDispatcher(mikaScreen)
        val selfDecision = ConfirmDecision(
            toolsSelf,
            confirmations = null,
            approvals = ApprovalSettings { true },
            autonomous = true,
            selfPackage = "com.mika",
        )
        assertEquals(
            Decision.OwnApp,
            selfDecision.judge("phone_launch_app", buildJsonObject { put("package", "com.mika") }, Origin.Model(1)),
        )
        assertEquals(
            Decision.OwnApp,
            selfDecision.judge("phone_tap", buildJsonObject { put("x", 500); put("y", 150) }, Origin.Model(2)),
        )

        // 2. autonomous = true 时，HardDeny 规则仍然拦截，即使有 Session Allow 也不放行
        val hardPolicy = RuleBasedPermissionPolicy(
            staticRules = listOf(
                PermissionRule.hardDeny(
                    id = "hard-block-checkout",
                    tool = NameMatcher.Exact("phone_tap"),
                    risks = setOf(ActionRisk.Irreversible),
                    paymentOnly = true,
                    reason = "hard payment lock",
                ),
            ),
        )
        hardPolicy.grant(PermissionRule.allow("try-allow", tool = NameMatcher.Exact("phone_tap"), scope = ApprovalScope.Session))

        val toolsShop = toolDispatcher(otherScreen)
        toolsShop.execute(Part.ToolCall("obs", "phone_observe", "{}"))
        val paymentCheck = ConfirmDecision(
            toolsShop,
            confirmations = ConfirmationGate(),
            approvals = ApprovalSettings.of(autonomous = true, policy = hardPolicy),
            autonomous = true,
            selfPackage = "com.mika",
        )
        val hardPayBlocked = paymentCheck.judge(
            "phone_tap",
            buildJsonObject { put("ref", "@e1"); put("snapshotId", "s1") },
            Origin.Model(1),
        )
        assertTrue(hardPayBlocked is Decision.Denied && hardPayBlocked.hit.matched == "提交订单")
        assertTrue(paymentCheck.judge("phone_back", buildJsonObject {}, Origin.Model(2)) == Decision.Clear)

        // 用 sandbox 工具测试 argPatterns HardDeny 在 autonomous 下拦截
        val sandboxHardPolicy = RuleBasedPermissionPolicy(
            staticRules = listOf(
                PermissionRule.hardDeny(
                    id = "block-format",
                    tool = NameMatcher.Exact("run_command"),
                    argPatterns = mapOf("command" to ArgMatcher.Prefix("mkfs")),
                    reason = "mkfs forbidden",
                ),
            ),
        )
        val cmdDecision = ConfirmDecision(
            toolsShop,
            confirmations = null,
            approvals = ApprovalSettings.of(autonomous = true, policy = sandboxHardPolicy),
            autonomous = true,
        )
        val blocked = cmdDecision.judge("run_command", buildJsonObject { put("command", "mkfs.ext4 /dev/sda") }, Origin.Model(1))
        assertTrue(blocked is Decision.Denied && blocked.hit.matched == "mkfs forbidden")
        assertEquals(
            Decision.Clear,
            cmdDecision.judge("run_command", buildJsonObject { put("command", "echo hello") }, Origin.Model(2)),
        )
    }

    @Test fun confirmDecisionAppliesOnceSessionAndPersistentRules() = runTest {
        val store = InMemoryPermissionStore()
        val policy = RuleBasedPermissionPolicy(persistentStore = store)
        val screen = object : ScreenPort by FakeScreen() {
            private val snap = ScreenSnapshot(
                "s1", "com.shop",
                listOf(UiElement("@e1", "button", "提交订单", 500, 500, listOf(100, 100, 900, 200), null, true, false, false)),
                1080, 2400,
            )
            override suspend fun snapshot() = snap
            override suspend fun peek() = snap
        }
        val tools = toolDispatcher(screen)
        tools.execute(Part.ToolCall("obs", "phone_observe", "{}"))
        val decision = ConfirmDecision(tools, ConfirmationGate(), ApprovalSettings.of(autonomous = false, policy = policy))
        val tapOrder = buildJsonObject { put("ref", "@e1"); put("snapshotId", "s1") }

        // 无规则时走确认弹窗
        assertTrue(decision.judge("phone_tap", tapOrder, Origin.Model(1)) is Decision.Required)

        // Once 规则放行一次后失效
        policy.grant(PermissionRule.allow("once-order", tool = NameMatcher.Exact("phone_tap"), scope = ApprovalScope.Once))
        assertEquals(Decision.Clear, decision.judge("phone_tap", tapOrder, Origin.Model(2)))
        assertTrue(decision.judge("phone_tap", tapOrder, Origin.Model(3)) is Decision.Required)

        // Persistent 规则跨 ConfirmDecision 实例依然放行
        policy.grant(PermissionRule.allow("persist-order", tool = NameMatcher.Exact("phone_tap"), scope = ApprovalScope.Persistent))
        val nextRunPolicy = RuleBasedPermissionPolicy(persistentStore = store)
        val nextRunDecision = ConfirmDecision(tools, ConfirmationGate(), ApprovalSettings.of(autonomous = false, policy = nextRunPolicy))
        assertEquals(Decision.Clear, nextRunDecision.judge("phone_tap", tapOrder, Origin.Model(1)))
    }
}
