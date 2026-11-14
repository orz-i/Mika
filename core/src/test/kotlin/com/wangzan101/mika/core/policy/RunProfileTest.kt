package com.wangzan101.mika.core.policy

import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.core.tool.EffectKind
import com.wangzan101.mika.core.tool.ToolTraits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RunProfileTest {
    private val uiWriteTraits = ToolTraits(effect = EffectKind.ScreenWrite, modifiesScreenState = true)
    private val uiReadTraits = ToolTraits(effect = EffectKind.ReadOnly, pageRead = true)
    private val sandboxWriteTraits = ToolTraits(effect = EffectKind.WorkspaceWrite)

    @Test fun generalProfileHasNoAntiCrawlOrIdleScrollPacing() {
        val general = RunProfile.GENERAL
        assertEquals("general", general.name)
        assertFalse(general.executionPolicy.enableAntiCrawlPacing)
        assertFalse(general.executionPolicy.enableIdleScroll)
        assertFalse(general.executionPolicy.pacing.enabled)
        assertEquals(
            PacingDirective.NONE,
            general.executionPolicy.pacing.beforeAction(
                PacingContext(tool = "web_tap", capability = "web", traits = uiWriteTraits, site = "example.com", outward = true, nowMs = 1000L),
            ),
        )
    }

    @Test fun deviceOperatorProfileEnablesPacingFlagsAndAcceptsAdapters() {
        val defaults = RunProfile.DEVICE_OPERATOR
        assertEquals("device_operator", defaults.name)
        assertTrue(defaults.executionPolicy.enableAntiCrawlPacing)
        assertTrue(defaults.executionPolicy.enableIdleScroll)

        val outward = OutwardPacing.human(Random(42))
        val adapter = outward.asPacingPolicy()
        assertTrue(adapter.enabled)
        val ctx1 = PacingContext(tool = "phone_tap", capability = "phone", traits = uiWriteTraits, site = "com.chat", outward = true, nowMs = 1_000L)
        assertEquals(0L, adapter.beforeAction(ctx1).waitMs)
        adapter.afterAction(ctx1, succeeded = true)
        val next = adapter.beforeAction(ctx1.copy(nowMs = 2_000L))
        assertTrue(next.shouldWait && next.waitMs > 0L)
    }

    @Test fun resolveSelectsGeneralUnlessExplicitUiTargetOrUiCapabilitiesPresent() {
        val sandboxOnly = RunProfile.resolve(capabilities = setOf("sandbox"))
        assertFalse(sandboxOnly.isDeviceOperator)
        assertFalse(sandboxOnly.enableAntiCrawlPacing)
        assertFalse(sandboxOnly.enableIdleScroll)

        val mcpOnly = RunProfile.resolve(capabilities = setOf("mcp", "sandbox"))
        assertFalse(mcpOnly.isDeviceOperator)

        val coderWithWeb = RunProfile.resolve(capabilities = setOf("web", "sandbox"), role = "coder")
        assertFalse(coderWithWeb.isDeviceOperator)
        assertFalse(coderWithWeb.enableAntiCrawlPacing)

        val readOnlyWeb = RunProfile.resolve(capabilities = setOf("web", "sandbox"), readOnly = true)
        assertFalse(readOnlyWeb.isDeviceOperator)

        val remoteRun = RunProfile.resolve(capabilities = null, explicitUiTarget = true, remote = true)
        assertFalse(remoteRun.isDeviceOperator)

        val fastPace = RunProfile.resolve(capabilities = setOf("phone", "web"), paceEnabled = false)
        assertFalse(fastPace.isDeviceOperator)

        val selective = SensitiveUiPacingPolicy(outwardPacing = OutwardPacing.human(Random(7)), uiActionGapMs = 500L)
        val generalMixed = RunProfile.resolve(
            capabilities = null,
            paceEnabled = true,
            explicitUiTarget = false,
            pacing = selective,
        )
        assertFalse(generalMixed.isDeviceOperator)
        assertFalse(generalMixed.enableAntiCrawlPacing)
        assertFalse(generalMixed.enableIdleScroll)
        assertTrue(generalMixed.executionPolicy.pacing.enabled)

        val operator = RunProfile.resolve(
            capabilities = null,
            paceEnabled = true,
            explicitUiTarget = true,
            pacing = selective,
            guardMode = GuardMode.WARN_ONLY,
        )
        assertTrue(operator.isDeviceOperator)
        assertTrue(operator.enableAntiCrawlPacing)
        assertTrue(operator.enableIdleScroll)
        assertTrue(operator.executionPolicy.pacing.enabled)
        assertEquals(GuardMode.WARN_ONLY, operator.guardMode)
    }

    @Test fun sensitiveUiPacingPolicyZeroDelaysGeneralOpsAndPacesUiWritesAndOutward() {
        val policy = SensitiveUiPacingPolicy(
            outwardPacing = OutwardPacing.human(Random(99)),
            uiActionGapMs = 600L,
        )
        assertTrue(policy.enabled)

        // Sandbox, MCP, memory, read-only, and workspace writes always execute at 0ms delay
        val sandboxCtx = PacingContext(tool = "run_command", capability = "sandbox", traits = sandboxWriteTraits, writesScreen = false, nowMs = 1_000L)
        assertEquals(PacingDirective.NONE, policy.beforeAction(sandboxCtx))
        policy.afterAction(sandboxCtx, succeeded = true)
        assertEquals(PacingDirective.NONE, policy.beforeAction(sandboxCtx.copy(nowMs = 1_050L)))

        val mcpCtx = PacingContext(tool = "mcp_demo__search", capability = "mcp", traits = uiReadTraits, nowMs = 1_100L)
        assertEquals(PacingDirective.NONE, policy.beforeAction(mcpCtx))

        val webReadCtx = PacingContext(tool = "web_read_page", capability = "web", traits = uiReadTraits, site = "example.com", nowMs = 1_200L)
        assertEquals(PacingDirective.NONE, policy.beforeAction(webReadCtx))

        // Consecutive sensitive UI ScreenWrites on same site receive uiActionGapMs dwell
        val uiTap1 = PacingContext(tool = "phone_tap", capability = "phone", traits = uiWriteTraits, site = "com.shop", writesScreen = true, nowMs = 2_000L)
        assertEquals(PacingDirective.NONE, policy.beforeAction(uiTap1))
        policy.afterAction(uiTap1, succeeded = true)
        val uiTap2 = policy.beforeAction(uiTap1.copy(nowMs = 2_150L))
        assertEquals(450L, uiTap2.waitMs)
        assertFalse(uiTap2.idleScroll)

        // Outward send/submit uses OutwardPacing cooldown
        val send1 = PacingContext(tool = "phone_tap", capability = "phone", traits = ToolTraits(effect = EffectKind.External), site = "com.chat", outward = true, writesScreen = true, nowMs = 5_000L)
        assertEquals(0L, policy.beforeAction(send1).waitMs)
        policy.afterAction(send1, succeeded = true)
        val send2 = policy.beforeAction(send1.copy(nowMs = 5_500L))
        assertTrue(send2.waitMs > 0L)
    }

    @Test fun resolveForChildAttenuatesToGeneralForSubagentWorkloads() {
        val parent = RunProfile.resolve(
            capabilities = null,
            paceEnabled = true,
            explicitUiTarget = true,
            pacing = OutwardPacing.human(Random(11)).asPacingPolicy(),
        )
        assertTrue(parent.isDeviceOperator)

        val coderChild = RunProfile.resolveForChild(
            parentProfile = parent,
            capabilities = setOf("sandbox", "web"),
            role = "coder",
            readOnly = false,
        )
        assertFalse(coderChild.isDeviceOperator)
        assertFalse(coderChild.enableAntiCrawlPacing)
        assertFalse(coderChild.enableIdleScroll)
        assertFalse(coderChild.executionPolicy.pacing.enabled)

        val sandboxChild = RunProfile.resolveForChild(
            parentProfile = parent,
            capabilities = setOf("sandbox"),
            role = null,
            readOnly = false,
        )
        assertFalse(sandboxChild.isDeviceOperator)
        assertFalse(sandboxChild.executionPolicy.pacing.enabled)

        val readOnlyChild = RunProfile.resolveForChild(
            parentProfile = parent,
            capabilities = setOf("web", "sandbox"),
            role = null,
            readOnly = true,
        )
        assertFalse(readOnlyChild.isDeviceOperator)
        assertFalse(readOnlyChild.executionPolicy.pacing.enabled)

        val uiChild = RunProfile.resolveForChild(
            parentProfile = parent,
            capabilities = setOf("web", "sandbox"),
            role = null,
            readOnly = false,
        )
        assertTrue(uiChild.isDeviceOperator)
        assertTrue(uiChild.enableAntiCrawlPacing)

        val generalParent = RunProfile.GENERAL.withGuardMode(GuardMode.WARN_ONLY)
        val underGeneralParent = RunProfile.resolveForChild(
            parentProfile = generalParent,
            capabilities = setOf("web", "phone"),
            role = null,
            readOnly = false,
        )
        assertFalse(underGeneralParent.isDeviceOperator)
        assertEquals(GuardMode.WARN_ONLY, underGeneralParent.guardMode)
    }
}
