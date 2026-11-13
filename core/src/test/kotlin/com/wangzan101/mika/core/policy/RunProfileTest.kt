package com.wangzan101.mika.core.policy

import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.core.tool.Verb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RunProfileTest {
    @Test fun generalProfileHasNoAntiCrawlOrIdleScrollPacing() {
        val general = RunProfile.GENERAL
        assertEquals("general", general.name)
        assertFalse(general.executionPolicy.enableAntiCrawlPacing)
        assertFalse(general.executionPolicy.enableIdleScroll)
        assertFalse(general.executionPolicy.pacing.enabled)
        assertEquals(
            PacingDirective.NONE,
            general.executionPolicy.pacing.beforeAction(PacingContext(tool = "web_tap", verb = Verb.Tap, site = "example.com", outward = true, nowMs = 1000L)),
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
        val ctx1 = PacingContext(tool = "phone_tap", verb = Verb.Tap, site = "com.chat", outward = true, nowMs = 1_000L)
        assertEquals(0L, adapter.beforeAction(ctx1).waitMs)
        adapter.afterAction(ctx1, succeeded = true)
        val next = adapter.beforeAction(ctx1.copy(nowMs = 2_000L))
        assertTrue(next.shouldWait && next.waitMs > 0L)
    }

    @Test fun resolveSelectsGeneralForNonUiOrFastOrReadOnlyOrSubagentRoles() {
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

        val remoteRun = RunProfile.resolve(capabilities = null, remote = true)
        assertFalse(remoteRun.isDeviceOperator)

        val fastPace = RunProfile.resolve(capabilities = setOf("phone", "web"), paceEnabled = false)
        assertFalse(fastPace.isDeviceOperator)

        val operator = RunProfile.resolve(
            capabilities = null,
            paceEnabled = true,
            pacing = OutwardPacing.human(Random(7)).asPacingPolicy(),
            guardMode = GuardMode.WARN_ONLY,
        )
        assertTrue(operator.isDeviceOperator)
        assertTrue(operator.enableAntiCrawlPacing)
        assertTrue(operator.enableIdleScroll)
        assertTrue(operator.executionPolicy.pacing.enabled)
        assertEquals(GuardMode.WARN_ONLY, operator.guardMode)
    }

    @Test fun resolveForChildAttenuatesToGeneralForSubagentWorkloads() {
        val parent = RunProfile.resolve(
            capabilities = null,
            paceEnabled = true,
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

        val sandboxChild = RunProfile.resolveForChild(
            parentProfile = parent,
            capabilities = setOf("sandbox"),
            role = null,
            readOnly = false,
        )
        assertFalse(sandboxChild.isDeviceOperator)

        val readOnlyChild = RunProfile.resolveForChild(
            parentProfile = parent,
            capabilities = setOf("web", "sandbox"),
            role = null,
            readOnly = true,
        )
        assertFalse(readOnlyChild.isDeviceOperator)

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
