package com.wangzan101.mika.settings

import com.wangzan101.mika.core.action.ActionRisk
import com.wangzan101.mika.core.action.Risk
import com.wangzan101.mika.core.policy.ApprovalScope
import com.wangzan101.mika.core.policy.ArgMatcher
import com.wangzan101.mika.core.policy.NameMatcher
import com.wangzan101.mika.core.policy.PermissionContext
import com.wangzan101.mika.core.policy.PermissionDecision
import com.wangzan101.mika.core.policy.PermissionEffect
import com.wangzan101.mika.core.policy.PermissionRule
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.tool.EffectKind
import com.wangzan101.mika.core.tool.ToolTraits
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentPermissionRuleStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundTripsComplexRulesAcrossStoreInstancesAtomically() {
        val file = tmp.root.resolve("permission-rules.json")
        val store1 = PersistentPermissionRuleStore(file)
        assertTrue(store1.list().isEmpty())

        val rule1 = PermissionRule(
            id = "allow-mcp-github",
            tool = NameMatcher.Glob("mcp_github__*"),
            capability = NameMatcher.Exact("mcp:github"),
            targetApp = NameMatcher.Any,
            effects = setOf(EffectKind.ReadOnly, EffectKind.External),
            origins = setOf(RunOrigin.Ui),
            unattendedOnly = false,
            argPatterns = mapOf(
                "repo" to ArgMatcher.Prefix("org/"),
                "action" to ArgMatcher.AnyOf(setOf("comment", "label")),
            ),
            risks = setOf(ActionRisk.External, ActionRisk.Change),
            paymentOnly = false,
            effect = PermissionEffect.Allow,
            scope = ApprovalScope.Persistent,
            hard = false,
            priority = 10,
            reason = "trusted github repo",
        )
        val rule2 = PermissionRule.allow(
            id = "allow-shop-app",
            targetApp = NameMatcher.Exact("com.shop"),
            scope = ApprovalScope.Persistent,
        )

        store1.save(rule1)
        store1.save(rule2)
        assertFalse(tmp.root.resolve("permission-rules.json.tmp").exists())

        val store2 = PersistentPermissionRuleStore(file)
        val loaded = store2.list()
        assertEquals(2, loaded.size)
        val loadedRule1 = loaded.first { it.id == "allow-mcp-github" }
        assertEquals(PermissionEffect.Allow, loadedRule1.effect)
        assertEquals(10, loadedRule1.priority)
        assertEquals("trusted github repo", loadedRule1.reason)
        assertTrue(
            loadedRule1.matches(
                PermissionContext(
                    tool = "mcp_github__add_comment",
                    capability = "mcp:github",
                    traits = ToolTraits(effect = EffectKind.External),
                    provenance = RunProvenance(RunOrigin.Ui, Attendance.Present),
                    risk = Risk(ActionRisk.External),
                    args = buildJsonObject {
                        put("repo", "org/mika")
                        put("action", "comment")
                    },
                ),
            ),
        )

        assertTrue(store2.remove("allow-shop-app"))
        assertFalse(store2.remove("missing"))
        val store3 = PersistentPermissionRuleStore(file)
        assertEquals(listOf("allow-mcp-github"), store3.list().map { it.id })

        store3.clear()
        assertTrue(PersistentPermissionRuleStore(file).list().isEmpty())
    }

    @Test fun corruptedFileDegradesGracefullyWithoutCrashing() {
        val file = tmp.root.resolve("permission-rules.json").apply { writeText("{corrupted-json") }
        val store = PersistentPermissionRuleStore(file)
        assertTrue(store.list().isEmpty())
        store.save(PermissionRule.allow("recovered", tool = NameMatcher.Exact("read_file"), scope = ApprovalScope.Persistent))
        assertEquals(1, PersistentPermissionRuleStore(file).list().size)
    }

    @Test fun approvalControlPersistsRulesAcrossRunsAndEnforcesSafetyBaseline() {
        val dir = tmp.newFolder("app-files")
        val control1 = ApprovalControl(ApprovalStore(dir.resolve("approvals.json")))
        val run1 = control1.start(storageDir = dir)
        val policy1 = run1.permissionPolicy()

        assertTrue(
            policy1.grant(
                PermissionRule.allow(
                    id = "user.persistent.tool.sandbox_exec.sandbox",
                    tool = NameMatcher.Exact("sandbox_exec"),
                    capability = NameMatcher.Exact("sandbox"),
                    scope = ApprovalScope.Persistent,
                ),
            ),
        )

        // New ApprovalControl (simulating app restart) loads the same persistent rule from storageDir
        val control2 = ApprovalControl(ApprovalStore(dir.resolve("approvals.json")))
        val run2 = control2.start(storageDir = dir)
        val policy2 = run2.permissionPolicy()

        val allowed = policy2.evaluate(
            PermissionContext(
                tool = "sandbox_exec",
                capability = "sandbox",
                args = buildJsonObject { put("command", "ls -la") },
            ),
        )
        assertTrue(allowed is PermissionDecision.Allow && allowed.scope == ApprovalScope.Persistent)

        // Safety baseline hard-deny still overrides persistent Allow on destructive shell commands
        val blocked = policy2.evaluate(
            PermissionContext(
                tool = "sandbox_exec",
                capability = "sandbox",
                args = buildJsonObject { put("command", "rm -rf /") },
            ),
        )
        assertTrue(blocked is PermissionDecision.Deny && blocked.hard)
    }
}
