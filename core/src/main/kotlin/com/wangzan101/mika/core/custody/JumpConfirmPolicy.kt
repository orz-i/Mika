package com.wangzan101.mika.core.custody

import com.wangzan101.mika.core.access.AppAccessState
import com.agent.use.ui.Hant

/** 弹窗中发现的勾选框状态（如「始终允许」「不再询问」）。 */
data class ConfirmCheckbox(
    val text: String,
    val checked: Boolean,
)

/** 护栏 5 所需的窗口安全属性快照。 */
data class WindowSafetySignals(
    val isFlagSecure: Boolean = false,
    val hasPasswordField: Boolean = false,
    val isAuthenticator: Boolean = false,
    val isRuntimePermission: Boolean = false,
)

/** 护栏 7：同一任务内同一 `A -> B` 跳转频控键。 */
data class JumpRouteKey(
    val taskId: Int,
    val initiatorPackage: String,
    val targetPackage: String,
)

/**
 * 护栏 7 的线程安全频控计数器（同一任务内同一 `A -> B` 最多代点 3 次）。
 */
class JumpConfirmRateLimiter(
    val maxPerRoute: Int = JumpConfirmPolicy.MAX_AUTO_CLICKS_PER_TASK_ROUTE,
) {
    private val lock = Any()
    private val counts = HashMap<JumpRouteKey, Int>()

    fun count(key: JumpRouteKey): Int = synchronized(lock) { counts[key] ?: 0 }

    fun canAutoClick(key: JumpRouteKey): Boolean = count(key) < maxPerRoute

    /** 尝试记一次自动点击；若已达上限返回 false 且不递增。 */
    fun tryAcquire(key: JumpRouteKey): Boolean = synchronized(lock) {
        val current = counts[key] ?: 0
        if (current >= maxPerRoute) return@synchronized false
        counts[key] = current + 1
        true
    }

    /** 记录一次已发生的自动点击，返回最新计数。 */
    fun recordAutoClick(key: JumpRouteKey): Int = synchronized(lock) {
        val next = (counts[key] ?: 0) + 1
        counts[key] = next
        next
    }

    fun clearTask(taskId: Int) = synchronized(lock) {
        counts.keys.removeAll { it.taskId == taskId }
    }

    fun clear() = synchronized(lock) { counts.clear() }
}

/** 护栏 1–9 评估所需的完整只读输入。 */
data class JumpConfirmRequest(
    val hostingEnabled: Boolean,
    val attributedDisplayId: Int?,
    val lastAgentActionAtMs: Long?,
    val nowMs: Long,
    val initiatorPackage: String? = null,
    val targetPackage: String?,
    val selfPackage: String,
    val appAccess: AppAccessState = AppAccessState(),
    val buttons: List<ConfirmButtonInput>,
    val checkboxes: List<ConfirmCheckbox> = emptyList(),
    val alwaysAllowCheckboxChecked: Boolean = false,
    val windowSafety: WindowSafetySignals = WindowSafetySignals(),
    val userActedOnInterstitial: Boolean = false,
    val autoClickCountForRoute: Int = 0,
    val deviceCircuitBroken: Boolean = false,
    val interstitialPackage: String? = null,
    val componentName: String? = null,
    val dialogTexts: List<String> = emptyList(),
    val isSensitiveOrSecurityException: Boolean = false,
    val sensitiveExceptionReason: String? = null,
    val activeActionWindowMs: Long = JumpConfirmPolicy.ACTIVE_AGENT_ACTION_WINDOW_MS,
    val maxAutoClicksPerTaskRoute: Int = JumpConfirmPolicy.MAX_AUTO_CLICKS_PER_TASK_ROUTE,
)

/** [JumpConfirmPolicy.evaluate] 的决策结果。 */
sealed interface JumpConfirmDecision {
    val allowed: Boolean

    data class Allow(
        val buttonToClick: ConfirmButton,
        val buttonAnalysis: ConfirmButtonAnalysis,
    ) : JumpConfirmDecision {
        override val allowed: Boolean = true
    }

    data class Degrade(
        val reason: JumpConfirmDegradeReason,
        val detail: String,
    ) : JumpConfirmDecision {
        override val allowed: Boolean = false
    }
}

/**
 * 跳转授权托管全部 9 条安全护栏的纯逻辑判定器：
 * 1. 开关已开启（`hostingEnabled`）；
 * 2. 主动跳转（已归因到托管屏 D，且最近一次 Agent 动作在 3s 内，空闲期自发跳转不代点）；
 * 3. 目标明确（识别出目标 B、B 未被 [AppAccessState] 禁用、B 不是 Mika 自身）；
 * 4. 按钮明确（满足 [ConfirmButtons]，且若存在勾选框如「始终允许」，不勾选且只允许非持久按钮）；
 * 5. 窗口安全（非防截屏、无密码框、非认证器、非运行时权限框）；
 * 6. 用户优先（用户在代点前已点击该中间页则让给用户）；
 * 7. 频率限制（同一任务内同一 `A -> B` 最多代点 3 次）；
 * 8. 本机未熔断（未因 ROM 拦截 a11y 点击而被标记降级）；
 * 9. 非敏感/安全例外之外的页面。
 *
 * 任一条不通过即返回对应 [JumpConfirmDegradeReason]，按开关关闭流程降级交由用户处理。
 */
object JumpConfirmPolicy {
    /** 护栏 2：判定为主动跳转的最近 Agent 动作时间窗（3s）。 */
    const val ACTIVE_AGENT_ACTION_WINDOW_MS: Long = 3_000L

    /** 护栏 7：同一任务内同一 `A -> B` 最大允许自动代点次数。 */
    const val MAX_AUTO_CLICKS_PER_TASK_ROUTE: Int = 3

    fun evaluate(request: JumpConfirmRequest): JumpConfirmDecision {
        // 1. 开关已开启
        if (!request.hostingEnabled) {
            return degrade(JumpConfirmDegradeReason.HOSTING_DISABLED, "jump confirm hosting disabled")
        }

        // 2. 主动跳转（已归因到托管屏 D，且最近一次 Agent 动作在 3s 内）
        if (request.attributedDisplayId == null) {
            return degrade(
                JumpConfirmDegradeReason.NOT_ATTRIBUTED_TO_MANAGED_DISPLAY,
                "interstitial not attributed to any managed display",
            )
        }
        val lastActionAt = request.lastAgentActionAtMs
        if (lastActionAt == null ||
            request.nowMs < lastActionAt ||
            (request.nowMs - lastActionAt) > request.activeActionWindowMs
        ) {
            return degrade(
                JumpConfirmDegradeReason.IDLE_OR_STALE_ACTION,
                "no recent agent action within ${request.activeActionWindowMs}ms",
            )
        }

        // 3. 目标明确（识别出目标 B、未被 AppAccessState 禁用、非 Mika 自身）
        val target = request.targetPackage?.trim()?.takeIf { it.isNotEmpty() }
            ?: return degrade(JumpConfirmDegradeReason.TARGET_UNKNOWN, "target package unknown")
        if (target == request.selfPackage) {
            return degrade(JumpConfirmDegradeReason.TARGET_SELF, "target package is self ($target)")
        }
        request.appAccess.denial(target)?.let { denied ->
            val reason = when (denied.code) {
                "app_access_unavailable" -> JumpConfirmDegradeReason.TARGET_ACCESS_UNAVAILABLE
                "app_blocked" -> JumpConfirmDegradeReason.TARGET_BLOCKED
                else -> JumpConfirmDegradeReason.TARGET_UNKNOWN
            }
            return degrade(reason, "target denied by app access policy: ${denied.code}")
        }

        // 4. 按钮明确（满足 ConfirmButtons；存在始终允许类勾选框时必须未勾选且仅点击非持久按钮）
        if (request.alwaysAllowCheckboxChecked || hasCheckedPersistentCheckbox(request.checkboxes)) {
            return degrade(
                JumpConfirmDegradeReason.ALWAYS_ALLOW_CHECKBOX_CHECKED,
                "persistent checkbox is checked; must not auto-confirm",
            )
        }
        val buttonAnalysis = ConfirmButtons.analyze(request.buttons)
        if (!buttonAnalysis.valid || buttonAnalysis.positiveButton == null) {
            val reason = if (buttonAnalysis.rejectReason == ConfirmButtonRejectReason.PERSISTENT_BUTTON_FORBIDDEN) {
                JumpConfirmDegradeReason.BUTTONS_PERSISTENT_FORBIDDEN
            } else {
                JumpConfirmDegradeReason.BUTTONS_NOT_CLEAR
            }
            return degrade(reason, "button check failed: ${buttonAnalysis.rejectReason}")
        }

        // 5. 窗口安全（非防截屏、无密码框、非认证器、非运行时权限框）
        windowSafetyDegrade(request.windowSafety)?.let { return it }

        // 6. 用户优先（用户在代点前已点击该中间页则让给用户）
        if (request.userActedOnInterstitial) {
            return degrade(JumpConfirmDegradeReason.USER_ALREADY_ACTED, "user already interacted with interstitial")
        }

        // 7. 频率限制（同一任务内同一 A -> B 最多代点 3 次）
        if (request.autoClickCountForRoute >= request.maxAutoClicksPerTaskRoute) {
            return degrade(
                JumpConfirmDegradeReason.RATE_LIMIT_EXCEEDED,
                "auto-click limit reached (${request.autoClickCountForRoute}/${request.maxAutoClicksPerTaskRoute})",
            )
        }

        // 8. 本机未熔断
        if (request.deviceCircuitBroken) {
            return degrade(
                JumpConfirmDegradeReason.DEVICE_CIRCUIT_BROKEN,
                "device circuit breaker active due to ROM accessibility click interception",
            )
        }

        // 9. 非敏感/安全例外之外的页面
        if (request.isSensitiveOrSecurityException || !request.sensitiveExceptionReason.isNullOrBlank()) {
            return degrade(
                JumpConfirmDegradeReason.SENSITIVE_OR_SECURITY_EXCEPTION,
                request.sensitiveExceptionReason ?: "sensitive or security exception page",
            )
        }
        SensitiveJumpDetector.detect(
            interstitialPackage = request.interstitialPackage,
            componentName = request.componentName,
            dialogTexts = request.dialogTexts,
            targetPackage = target,
        )?.let { hit ->
            return degrade(JumpConfirmDegradeReason.SENSITIVE_OR_SECURITY_EXCEPTION, hit)
        }

        return JumpConfirmDecision.Allow(
            buttonToClick = buttonAnalysis.positiveButton,
            buttonAnalysis = buttonAnalysis,
        )
    }

    private fun hasCheckedPersistentCheckbox(checkboxes: List<ConfirmCheckbox>): Boolean =
        checkboxes.any { it.checked && ConfirmButtons.containsPersistentKeyword(it.text) }

    private fun windowSafetyDegrade(safety: WindowSafetySignals): JumpConfirmDecision.Degrade? = when {
        safety.isFlagSecure -> degrade(JumpConfirmDegradeReason.WINDOW_PROTECTED, "window has FLAG_SECURE")
        safety.hasPasswordField -> degrade(JumpConfirmDegradeReason.WINDOW_HAS_PASSWORD, "window contains password field")
        safety.isAuthenticator -> degrade(JumpConfirmDegradeReason.WINDOW_IS_AUTHENTICATOR, "window is authenticator")
        safety.isRuntimePermission -> degrade(JumpConfirmDegradeReason.WINDOW_IS_RUNTIME_PERMISSION, "window is runtime permission dialog")
        else -> null
    }

    private fun degrade(reason: JumpConfirmDegradeReason, detail: String): JumpConfirmDecision.Degrade =
        JumpConfirmDecision.Degrade(reason = reason, detail = detail)
}

/** 护栏 9：识别支付、登录、认证、应用安装、系统高危权限等敏感/安全例外页面。 */
object SensitiveJumpDetector {
    private val SENSITIVE_TARGET_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.android.certinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    private val SENSITIVE_COMPONENT_MARKERS = listOf(
        "grantpermissions",
        "packageinstaller",
        "installappprogress",
        "deviceadminadd",
        "certinstaller",
        "accessibilitysettings",
        "manageoverlaypermission",
        "requestappusage",
        "vpn",
        "biometric",
        "confirmdevicecredential",
    )

    private val SENSITIVE_TEXT_ZH = listOf(
        "支付",
        "付款",
        "转账",
        "收银台",
        "扣款",
        "实名认证",
        "验证码",
        "输入密码",
        "支付密码",
        "授权登录",
        "授权登入",
        "账号登录",
        "账号登入",
        "扫码登录",
        "扫码登入",
        "指纹识别",
        "人脸识别",
        "面容识别",
        "无障碍服务",
        "设备管理器",
        "设备管理员",
        "安装应用",
        "安装未知应用",
        "悬浮窗权限",
        "系统设置授权",
    )

    private val SENSITIVE_TEXT_EN = listOf(
        "payment",
        "checkout",
        "transfer money",
        "password",
        "passcode",
        "otp",
        "verification code",
        "biometric",
        "fingerprint",
        "face unlock",
        "oauth",
        "sign in",
        "log in",
        "install app",
        "unknown apps",
        "device admin",
        "accessibility service",
        "display over other apps",
        "vpn connection",
    )

    fun detect(
        interstitialPackage: String? = null,
        componentName: String? = null,
        dialogTexts: List<String> = emptyList(),
        targetPackage: String? = null,
    ): String? {
        if (targetPackage != null && targetPackage in SENSITIVE_TARGET_PACKAGES) {
            return "sensitive target package: $targetPackage"
        }
        if (interstitialPackage != null && interstitialPackage in SENSITIVE_TARGET_PACKAGES) {
            return "sensitive interstitial package: $interstitialPackage"
        }
        val compLower = componentName?.lowercase().orEmpty()
        if (compLower.isNotEmpty()) {
            SENSITIVE_COMPONENT_MARKERS.firstOrNull { it in compLower }?.let { marker ->
                return "sensitive component: $marker"
            }
        }
        for (raw in dialogTexts) {
            val folded = Hant.fold(raw)
            SENSITIVE_TEXT_ZH.firstOrNull { it in folded }?.let { kw ->
                return "sensitive text keyword: $kw"
            }
            val lower = folded.lowercase()
            SENSITIVE_TEXT_EN.firstOrNull { containsWordOrPhrase(lower, it) }?.let { kw ->
                return "sensitive text keyword: $kw"
            }
        }
        return null
    }

    private fun containsWordOrPhrase(lower: String, phrase: String): Boolean {
        val escaped = Regex.escape(phrase)
        val pattern = Regex("(?<![a-z0-9])" + escaped + "(?![a-z0-9])")
        return pattern.containsMatchIn(lower)
    }
}
