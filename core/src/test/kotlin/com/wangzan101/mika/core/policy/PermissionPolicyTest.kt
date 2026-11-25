package com.wangzan101.mika.core.policy

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Approval
import com.wangzan101.mika.core.action.ApprovalPolicy
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.action.ConfirmDecision
import com.wangzan101.mika.core.action.Decision
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.Risk
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.tool.EffectKind
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolTraits
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ScreenPort
import com.agent.use.ui.ScreenSnapshot
import com.agent.use.ui.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    private val readTraits = ToolTraits(effect = EffectKind.ReadOnly)
    private val workspaceWriteTraits = ToolTraits(effect = EffectKind.WorkspaceWrite)
    private val externalTraits = ToolTraits(effect = EffectKind.External)

    @Test fun matchesByToolCapabilityEffectsTargetAppAndArgPatterns() {
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
                    id = "allow-read-effects",
                    effects = setOf(EffectKind.ReadOnly),
                ),
                PermissionRule.allow(
                    id = "allow-shop-app",
                    targetApp = NameMatcher.Exact("com.shop"),
                ),
            ),
        )

        val rmRf = policy.evaluate(
            PermissionContext(
                tool = "run_command",
                capability = "sandbox",
                traits = workspaceWriteTraits,
                args = buildJsonObject { put("command", "rm -rf /workspace/data") },
            ),
        )
        assertTrue(rmRf is PermissionDecision.Deny && rmRf.hard && rmRf.reason == "destructive delete")

        val safeCmd = policy.evaluate(
            PermissionContext(
                tool = "run_command",
                capability = "sandbox",
                traits = workspaceWriteTraits,
                args = buildJsonObject { put("command", "ls -la") },
            ),
        )
        assertEquals(PermissionDecision.Abstain, safeCmd)

        val mcp = policy.evaluate(PermissionContext(tool = "mcp_github__create_issue", capability = "mcp_github"))
        assertTrue(mcp is PermissionDecision.Ask && mcp.ruleId == "ask-mcp-glob")

        val read = policy.evaluate(PermissionContext(tool = "read_file", capability = "sandbox", traits = readTraits))
        assertTrue(read is PermissionDecision.Allow && read.ruleId == "allow-read-effects")

        val shop = policy.evaluate(PermissionContext(tool = "phone_tap", capability = "phone", targetApp = "com.shop"))
        assertTrue(shop is PermissionDecision.Allow && shop.ruleId == "allow-shop-app")
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

    @Test fun defaultPolicyIsImmutableAndEnforcesDestructiveShellBaseline() {
        val granted = PermissionPolicy.DEFAULT.grant(
            PermissionRule.allow("try-mutate-default", tool = NameMatcher.Exact("sandbox_exec"), scope = ApprovalScope.Session),
        )
        assertFalse(granted)

        val rmRoot = PermissionPolicy.DEFAULT.evaluate(
            PermissionContext(
                tool = "sandbox_exec",
                capability = "sandbox",
                traits = workspaceWriteTraits,
                autonomous = true,
                args = buildJsonObject { put("command", "rm -rf /") },
            ),
        )
        assertTrue(rmRoot is PermissionDecision.Deny && rmRoot.hard)

        val mkfsDev = PermissionPolicy.DEFAULT.evaluate(
            PermissionContext(
                tool = "root_run_command",
                capability = "root",
                autonomous = true,
                args = buildJsonObject { put("command", "mkfs.ext4 /dev/sda1") },
            ),
        )
        assertTrue(mkfsDev is PermissionDecision.Deny && mkfsDev.hard)

        val ddDev = PermissionPolicy.DEFAULT.evaluate(
            PermissionContext(
                tool = "sandbox_exec",
                capability = "sandbox",
                autonomous = true,
                args = buildJsonObject { put("command", "dd if=/dev/zero of=/dev/nvme0n1 bs=1M") },
            ),
        )
        assertTrue(ddDev is PermissionDecision.Deny && ddDev.hard)

        val safeWorkspaceRm = PermissionPolicy.DEFAULT.evaluate(
            PermissionContext(
                tool = "sandbox_exec",
                capability = "sandbox",
                traits = workspaceWriteTraits,
                args = buildJsonObject { put("command", "rm -rf /workspace/tmp") },
            ),
        )
        assertEquals(PermissionDecision.Abstain, safeWorkspaceRm)
    }

    @Test fun strictSafetyBaselineGuardsUnauthorizedExternalWritesUnlessExplicitlyAllowed() {
        val strict = PermissionPolicy.createDefault(safetyBaseline = SafetyBaselineConfig.STRICT)

        val unattendedMcp = strict.evaluate(
            PermissionContext(
                tool = "mcp_slack__post_message",
                capability = "mcp:slack",
                traits = externalTraits,
                provenance = RunProvenance(RunOrigin.Channel, Attendance.Unattended),
            ),
        )
        assertTrue(unattendedMcp is PermissionDecision.Deny && !unattendedMcp.hard)

        val interactiveMcp = strict.evaluate(
            PermissionContext(
                tool = "mcp_slack__post_message",
                capability = "mcp:slack",
                traits = externalTraits,
                provenance = RunProvenance(RunOrigin.Ui, Attendance.Present),
            ),
        )
        assertTrue(interactiveMcp is PermissionDecision.Ask)

        assertTrue(
            strict.grant(
                PermissionRule.allow(
                    id = "allow-slack-post",
                    tool = NameMatcher.Exact("mcp_slack__post_message"),
                    scope = ApprovalScope.Session,
                ),
            ),
        )
        val allowedAfterGrant = strict.evaluate(
            PermissionContext(
                tool = "mcp_slack__post_message",
                capability = "mcp:slack",
                traits = externalTraits,
                provenance = RunProvenance(RunOrigin.Ui, Attendance.Present),
            ),
        )
        assertTrue(allowedAfterGrant is PermissionDecision.Allow && allowedAfterGrant.ruleId == "allow-slack-post")
    }

    @Test fun approvalPolicyAllowsChildEscalationForNonPaymentButDeniesPaymentAndPlainUnattendedChild() {
        val plainChild = RunProvenance(RunOrigin.Child, Attendance.Unattended, canEscalateApproval = false)
        val escalatingChild = RunProvenance(RunOrigin.Child, Attendance.Unattended, canEscalateApproval = true)
        val nonPayAskRisk = Risk(
            ActionRisk.External,
            payment = false,
            ask = ConfirmationRequest(IrrevAction.other, "sandbox", "run command", null, "sandbox"),
        )
        val payRisk = Risk(ActionRisk.Irreversible, payment = true)

        assertEquals(Approval.Deny, ApprovalPolicy.decide(PermissionDecision.Ask("confirm"), Risk(ActionRisk.External), plainChild))
        assertEquals(Approval.Ask, ApprovalPolicy.decide(PermissionDecision.Ask("confirm"), Risk(ActionRisk.External), escalatingChild))
        assertEquals(Approval.Deny, ApprovalPolicy.decide(nonPayAskRisk, plainChild))
        assertEquals(Approval.Ask, ApprovalPolicy.decide(nonPayAskRisk, escalatingChild))

        // Payments are strictly denied in child runs even with canEscalateApproval = true
        assertEquals(Approval.Deny, ApprovalPolicy.decide(payRisk, escalatingChild))
        assertEquals(Approval.Deny, ApprovalPolicy.decide(PermissionDecision.Ask("pay"), payRisk, escalatingChild))
    }
}
