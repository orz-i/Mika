package com.wangzan101.mika.core.custody

import com.wangzan101.mika.core.access.AppAccessState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpConfirmPolicyTest {

    private val selfPkg = "com.wangzan101.mika"
    private val validButtons = listOf(
        ConfirmButtonInput("拒绝", id = "btn_deny"),
        ConfirmButtonInput("允许", id = "btn_allow"),
    )

    private inline fun <reified T> assertDecisionType(decision: JumpConfirmDecision): T {
        assertTrue("Expected ${T::class.simpleName} but got $decision", decision is T)
        return decision as T
    }

    private fun validRequest(
        hostingEnabled: Boolean = true,
        attributedDisplayId: Int? = 2,
        lastAgentActionAtMs: Long? = 9_000L,
        nowMs: Long = 10_000L,
        initiatorPackage: String? = "tv.danmaku.bili",
        targetPackage: String? = "com.taobao.taobao",
        appAccess: AppAccessState = AppAccessState(blocked = setOf("com.bank.app"), available = true),
        buttons: List<ConfirmButtonInput> = validButtons,
        checkboxes: List<ConfirmCheckbox> = listOf(ConfirmCheckbox("始终允许", checked = false)),
        alwaysAllowCheckboxChecked: Boolean = false,
        windowSafety: WindowSafetySignals = WindowSafetySignals(),
        userActedOnInterstitial: Boolean = false,
        autoClickCountForRoute: Int = 0,
        deviceCircuitBroken: Boolean = false,
        interstitialPackage: String? = "com.miui.securitycenter",
        componentName: String? = "com.miui.securitycenter/.JumpActivity",
        dialogTexts: List<String> = listOf("哔哩哔哩 想要打开 淘宝"),
        isSensitiveOrSecurityException: Boolean = false,
        sensitiveExceptionReason: String? = null,
    ) = JumpConfirmRequest(
        hostingEnabled = hostingEnabled,
        attributedDisplayId = attributedDisplayId,
        lastAgentActionAtMs = lastAgentActionAtMs,
        nowMs = nowMs,
        initiatorPackage = initiatorPackage,
        targetPackage = targetPackage,
        selfPackage = selfPkg,
        appAccess = appAccess,
        buttons = buttons,
        checkboxes = checkboxes,
        alwaysAllowCheckboxChecked = alwaysAllowCheckboxChecked,
        windowSafety = windowSafety,
        userActedOnInterstitial = userActedOnInterstitial,
        autoClickCountForRoute = autoClickCountForRoute,
        deviceCircuitBroken = deviceCircuitBroken,
        interstitialPackage = interstitialPackage,
        componentName = componentName,
        dialogTexts = dialogTexts,
        isSensitiveOrSecurityException = isSensitiveOrSecurityException,
        sensitiveExceptionReason = sensitiveExceptionReason,
    )

    @Test
    fun `allows auto click when all 9 guardrails pass including unchecked persistent checkbox`() {
        val decision = JumpConfirmPolicy.evaluate(validRequest(nowMs = 12_000L, lastAgentActionAtMs = 9_000L))
        val allow = assertDecisionType<JumpConfirmDecision.Allow>(decision)
        assertTrue(allow.allowed)
        assertEquals("btn_allow", allow.buttonToClick.id)
        assertEquals("允许", allow.buttonToClick.rawText)
    }

    @Test
    fun `guardrail 1 degrades when hosting switch is disabled`() {
        val d = assertDecisionType<JumpConfirmDecision.Degrade>(JumpConfirmPolicy.evaluate(validRequest(hostingEnabled = false)))
        assertFalse(d.allowed)
        assertEquals(JumpConfirmDegradeReason.HOSTING_DISABLED, d.reason)
    }

    @Test
    fun `guardrail 2 degrades when unattributed or idle beyond 3s`() {
        val unattributed = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(attributedDisplayId = null)),
        )
        assertEquals(JumpConfirmDegradeReason.NOT_ATTRIBUTED_TO_MANAGED_DISPLAY, unattributed.reason)

        val stale = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(lastAgentActionAtMs = 6_999L, nowMs = 10_000L)),
        )
        assertEquals(JumpConfirmDegradeReason.IDLE_OR_STALE_ACTION, stale.reason)

        val missingAction = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(lastAgentActionAtMs = null)),
        )
        assertEquals(JumpConfirmDegradeReason.IDLE_OR_STALE_ACTION, missingAction.reason)
    }

    @Test
    fun `guardrail 3 degrades when target is unknown self blocked or access unavailable`() {
        val unknown = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(targetPackage = null)),
        )
        assertEquals(JumpConfirmDegradeReason.TARGET_UNKNOWN, unknown.reason)

        val self = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(targetPackage = selfPkg)),
        )
        assertEquals(JumpConfirmDegradeReason.TARGET_SELF, self.reason)

        val blocked = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(targetPackage = "com.bank.app")),
        )
        assertEquals(JumpConfirmDegradeReason.TARGET_BLOCKED, blocked.reason)

        val unavailable = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(appAccess = AppAccessState(available = false))),
        )
        assertEquals(JumpConfirmDegradeReason.TARGET_ACCESS_UNAVAILABLE, unavailable.reason)
    }

    @Test
    fun `guardrail 4 degrades on checked persistent checkbox persistent button or unclear buttons`() {
        val checkedBox = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(
                validRequest(checkboxes = listOf(ConfirmCheckbox("始終允許", checked = true))),
            ),
        )
        assertEquals(JumpConfirmDegradeReason.ALWAYS_ALLOW_CHECKBOX_CHECKED, checkedBox.reason)

        val persistentBtn = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(
                validRequest(buttons = listOf(ConfirmButtonInput("拒绝"), ConfirmButtonInput("始终允许"))),
            ),
        )
        assertEquals(JumpConfirmDegradeReason.BUTTONS_PERSISTENT_FORBIDDEN, persistentBtn.reason)

        val unclearBtn = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(
                validRequest(buttons = listOf(ConfirmButtonInput("允许"), ConfirmButtonInput("打开"), ConfirmButtonInput("拒绝"))),
            ),
        )
        assertEquals(JumpConfirmDegradeReason.BUTTONS_NOT_CLEAR, unclearBtn.reason)
    }

    @Test
    fun `guardrail 5 degrades on secure password authenticator or runtime permission windows`() {
        assertEquals(
            JumpConfirmDegradeReason.WINDOW_PROTECTED,
            assertDecisionType<JumpConfirmDecision.Degrade>(
                JumpConfirmPolicy.evaluate(validRequest(windowSafety = WindowSafetySignals(isFlagSecure = true))),
            ).reason,
        )
        assertEquals(
            JumpConfirmDegradeReason.WINDOW_HAS_PASSWORD,
            assertDecisionType<JumpConfirmDecision.Degrade>(
                JumpConfirmPolicy.evaluate(validRequest(windowSafety = WindowSafetySignals(hasPasswordField = true))),
            ).reason,
        )
        assertEquals(
            JumpConfirmDegradeReason.WINDOW_IS_AUTHENTICATOR,
            assertDecisionType<JumpConfirmDecision.Degrade>(
                JumpConfirmPolicy.evaluate(validRequest(windowSafety = WindowSafetySignals(isAuthenticator = true))),
            ).reason,
        )
        assertEquals(
            JumpConfirmDegradeReason.WINDOW_IS_RUNTIME_PERMISSION,
            assertDecisionType<JumpConfirmDecision.Degrade>(
                JumpConfirmPolicy.evaluate(validRequest(windowSafety = WindowSafetySignals(isRuntimePermission = true))),
            ).reason,
        )
    }

    @Test
    fun `guardrail 6 degrades when user already interacted with interstitial`() {
        val d = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(userActedOnInterstitial = true)),
        )
        assertEquals(JumpConfirmDegradeReason.USER_ALREADY_ACTED, d.reason)
    }

    @Test
    fun `guardrail 7 enforces max 3 auto clicks per task and route`() {
        assertDecisionType<JumpConfirmDecision.Allow>(JumpConfirmPolicy.evaluate(validRequest(autoClickCountForRoute = 2)))
        val limited = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(autoClickCountForRoute = 3)),
        )
        assertEquals(JumpConfirmDegradeReason.RATE_LIMIT_EXCEEDED, limited.reason)

        val limiter = JumpConfirmRateLimiter()
        val k1 = JumpRouteKey(taskId = 1, initiatorPackage = "a", targetPackage = "b")
        val k2 = JumpRouteKey(taskId = 2, initiatorPackage = "a", targetPackage = "b")
        assertTrue(limiter.tryAcquire(k1))
        assertTrue(limiter.tryAcquire(k1))
        assertTrue(limiter.tryAcquire(k1))
        assertFalse(limiter.tryAcquire(k1))
        assertTrue(limiter.canAutoClick(k2))
        limiter.clearTask(1)
        assertTrue(limiter.canAutoClick(k1))
    }

    @Test
    fun `guardrail 8 degrades when device circuit breaker tripped`() {
        val d = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(deviceCircuitBroken = true)),
        )
        assertEquals(JumpConfirmDegradeReason.DEVICE_CIRCUIT_BROKEN, d.reason)
    }

    @Test
    fun `guardrail 9 degrades on sensitive or security exception pages`() {
        val explicit = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(isSensitiveOrSecurityException = true)),
        )
        assertEquals(JumpConfirmDegradeReason.SENSITIVE_OR_SECURITY_EXCEPTION, explicit.reason)

        val paymentText = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(dialogTexts = listOf("即将前往收银台付款"))),
        )
        assertEquals(JumpConfirmDegradeReason.SENSITIVE_OR_SECURITY_EXCEPTION, paymentText.reason)

        val tradAccessibility = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(dialogTexts = listOf("是否開啟無障礙服務"))),
        )
        assertEquals(JumpConfirmDegradeReason.SENSITIVE_OR_SECURITY_EXCEPTION, tradAccessibility.reason)

        val tradScanLogin = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(dialogTexts = listOf("掃碼登錄系統"))),
        )
        assertEquals(JumpConfirmDegradeReason.SENSITIVE_OR_SECURITY_EXCEPTION, tradScanLogin.reason)

        val installer = assertDecisionType<JumpConfirmDecision.Degrade>(
            JumpConfirmPolicy.evaluate(validRequest(targetPackage = "com.android.packageinstaller")),
        )
        assertEquals(JumpConfirmDegradeReason.SENSITIVE_OR_SECURITY_EXCEPTION, installer.reason)
    }
}
