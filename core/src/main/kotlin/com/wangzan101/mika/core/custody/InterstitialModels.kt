package com.wangzan101.mika.core.custody

/** 中间页类型：系统选择器、应用间跳转授权弹窗、需用户介入的页面。 */
enum class InterstitialKind {
    CHOOSER,
    JUMP_CONFIRM,
    NEEDS_USER,
}

/** 解决或响应中间页的主体。 */
enum class InterstitialActor {
    USER,
    AGENT,
}

/** 中间页最终给出的选择结果。 */
enum class InterstitialChoice {
    ALLOW,
    DENY,
    UNKNOWN,
}

/**
 * 托管屏动作引发、落在主屏上的中间页统一模型。
 *
 * 账本（[InterstitialLedger]）与跨屏归因（[CustodyAttribution]）共同基于此模型追踪中间页生命周期。
 */
sealed interface Interstitial {
    val id: String

    /** 触发或拥有该中间页归属权的托管虚拟屏 ID。 */
    val displayId: Int

    /** 中间页所在任务 ID，未知时为 -1。 */
    val taskId: Int

    /** 中间页宿主窗口包名（即 `I.pkg`）。 */
    val packageName: String

    /** 中间页顶层组件名（如有）。 */
    val componentName: String?

    /** 中间页打开时刻（与归因链路统一的毫秒时间戳，如 `uptimeMillis`）。 */
    val openedAtMs: Long

    val kind: InterstitialKind

    /** 系统应用选择器 / 分发页（Resolver / Chooser）。 */
    data class Chooser(
        override val id: String,
        override val displayId: Int,
        override val taskId: Int = -1,
        override val packageName: String,
        override val componentName: String? = null,
        override val openedAtMs: Long,
        val initiatorPackage: String? = null,
        val options: List<String> = emptyList(),
    ) : Interstitial {
        override val kind: InterstitialKind get() = InterstitialKind.CHOOSER
    }

    /** 应用间跳转确认弹窗（如「A 想要打开 B，是否允许」）。 */
    data class JumpConfirm(
        override val id: String,
        override val displayId: Int,
        override val taskId: Int = -1,
        override val packageName: String,
        override val componentName: String? = null,
        override val openedAtMs: Long,
        val initiatorPackage: String? = null,
        val initiatorUid: Int? = null,
        val targetPackage: String? = null,
        val targetLabel: String? = null,
        val positiveButtonText: String? = null,
        val negativeButtonText: String? = null,
    ) : Interstitial {
        override val kind: InterstitialKind get() = InterstitialKind.JUMP_CONFIRM
    }

    /** 需要用户亲自处理的页面（登录、支付、验证码、系统授权等）。 */
    data class NeedsUser(
        override val id: String,
        override val displayId: Int,
        override val taskId: Int = -1,
        override val packageName: String,
        override val componentName: String? = null,
        override val openedAtMs: Long,
        val initiatorPackage: String? = null,
        val reason: String? = null,
    ) : Interstitial {
        override val kind: InterstitialKind get() = InterstitialKind.NEEDS_USER
    }
}

/** 中间页账本条目状态：未决（[Open]）或已解决（[Resolved]）。 */
sealed interface LedgerState {
    data object Open : LedgerState

    data class Resolved(
        val by: InterstitialActor,
        val choice: InterstitialChoice,
        val at: Long,
    ) : LedgerState
}

/** 账本中记录的一条中间页及其当前状态。 */
data class LedgerEntry(
    val interstitial: Interstitial,
    val state: LedgerState = LedgerState.Open,
    val updatedAtMs: Long = interstitial.openedAtMs,
) {
    val displayId: Int get() = interstitial.displayId
    val packageName: String get() = interstitial.packageName
    val isOpen: Boolean get() = state is LedgerState.Open
    val isResolved: Boolean get() = state is LedgerState.Resolved

    /**
     * 判断该条目在 [nowMs] 时刻是否仍处于归因窗口内：
     * 仅统计打开时间不晚于 [nowMs] 的条目；处于 [LedgerState.Open]，或处于 [LedgerState.Resolved] 且距解决时刻 ≤ [resolvedWindowMs]。
     */
    fun matchesAttributionWindow(
        nowMs: Long,
        resolvedWindowMs: Long = DEFAULT_RESOLVED_WINDOW_MS,
    ): Boolean {
        if (interstitial.openedAtMs > nowMs) return false
        return when (val s = state) {
            LedgerState.Open -> true
            is LedgerState.Resolved -> nowMs >= s.at && (nowMs - s.at) <= resolvedWindowMs
        }
    }

    /** 判断该条目在 [nowMs] 时刻是否已超出 TTL，可被清理。 */
    fun isExpired(
        nowMs: Long,
        openTtlMs: Long = DEFAULT_OPEN_TTL_MS,
        resolvedTtlMs: Long = DEFAULT_RESOLVED_TTL_MS,
    ): Boolean = when (val s = state) {
        LedgerState.Open -> openTtlMs > 0 && (nowMs - interstitial.openedAtMs) >= openTtlMs
        is LedgerState.Resolved -> resolvedTtlMs > 0 && (nowMs - s.at) >= resolvedTtlMs
    }

    companion object {
        /** 规则 R2 允许的已解决中间页溯源窗口（10s）。 */
        const val DEFAULT_RESOLVED_WINDOW_MS: Long = 10_000L

        /** 未解决中间页默认驻留 TTL（2 分钟）。 */
        const val DEFAULT_OPEN_TTL_MS: Long = 120_000L

        /** 已解决中间页默认保留 TTL（30 秒，大于 R2 的 10s 窗口以便审计）。 */
        const val DEFAULT_RESOLVED_TTL_MS: Long = 30_000L
    }
}

/** 跳转确认托管处理的对外结局，用于工具结果向模型反馈语义。 */
enum class JumpConfirmOutcome {
    /** 正在等待用户在主屏确认（未开启托管或降级交由用户处理）。 */
    WAITING_USER,

    /** Mika 已通过全部护栏并自动点击非持久允许按钮放行。 */
    AUTO_ALLOWED,

    /** 用户已在主屏手动点击确认放行。 */
    USER_ALLOWED,

    /** 跳转被拒绝或取消。 */
    DENIED,

    /** 自动托管不可用（如 ROM 拦截/熔断、目标受限、安全窗口或点击失效）。 */
    UNAVAILABLE,
}

/** 跳转授权托管 9 条护栏不通过时的明确降级原因。 */
enum class JumpConfirmDegradeReason(val guardrail: Int) {
    /** 护栏 1：跳转授权托管开关未开启。 */
    HOSTING_DISABLED(1),

    /** 护栏 2：未归因到托管屏 D。 */
    NOT_ATTRIBUTED_TO_MANAGED_DISPLAY(2),

    /** 护栏 2：最近一次 Agent 动作超出 3s 窗口（空闲期自发跳转不代点）。 */
    IDLE_OR_STALE_ACTION(2),

    /** 护栏 3：未识别出明确的目标包名 B，或权限状态无法验证。 */
    TARGET_UNKNOWN(3),

    /** 护栏 3：目标 B 是 Mika 自身。 */
    TARGET_SELF(3),

    /** 护栏 3：目标 B 被 AppAccessState 禁用。 */
    TARGET_BLOCKED(3),

    /** 护栏 3：AppAccessState 不可用，无法确认目标合法性。 */
    TARGET_ACCESS_UNAVAILABLE(3),

    /** 护栏 4：按钮结构不满足「恰好一个非持久肯定项且至少一个否定项」。 */
    BUTTONS_NOT_CLEAR(4),

    /** 护栏 4：仅存在持久授权按钮（如「始终允许」）或禁止点击的持久词。 */
    BUTTONS_PERSISTENT_FORBIDDEN(4),

    /** 护栏 4：存在持久授权勾选框（如「始终允许」）且处于勾选状态。 */
    ALWAYS_ALLOW_CHECKBOX_CHECKED(4),

    /** 护栏 5：窗口含防截屏标记（FLAG_SECURE）。 */
    WINDOW_PROTECTED(5),

    /** 护栏 5：窗口包含密码输入框。 */
    WINDOW_HAS_PASSWORD(5),

    /** 护栏 5：窗口为认证器（Authenticator / 生物或凭据认证）。 */
    WINDOW_IS_AUTHENTICATOR(5),

    /** 护栏 5：窗口为系统运行时权限弹窗。 */
    WINDOW_IS_RUNTIME_PERMISSION(5),

    /** 护栏 6：用户在代点前已点击该中间页，让权给用户。 */
    USER_ALREADY_ACTED(6),

    /** 护栏 7：同一任务内同一 `A -> B` 跳转自动代点已达上限（3 次）。 */
    RATE_LIMIT_EXCEEDED(7),

    /** 护栏 8：本机已因 ROM 拦截 a11y 点击而熔断降级。 */
    DEVICE_CIRCUIT_BROKEN(8),

    /** 护栏 9：命中敏感/安全例外页面（支付、登录、安装、系统权限设置等）。 */
    SENSITIVE_OR_SECURITY_EXCEPTION(9),
}

/** 附着在 [com.wangzan101.mika.core.uistate.OpenedElsewhere] 上的跳转确认摘要。 */
data class JumpConfirmReport(
    val initiator: String?,
    val target: String?,
    val by: InterstitialActor? = null,
    val choice: InterstitialChoice = InterstitialChoice.UNKNOWN,
    val outcome: JumpConfirmOutcome = inferOutcome(by, choice, degradeReason = null),
    val degradeReason: JumpConfirmDegradeReason? = null,
) {
    companion object {
        fun inferOutcome(
            by: InterstitialActor?,
            choice: InterstitialChoice,
            degradeReason: JumpConfirmDegradeReason? = null,
        ): JumpConfirmOutcome = when {
            choice == InterstitialChoice.DENY -> JumpConfirmOutcome.DENIED
            choice == InterstitialChoice.ALLOW && by == InterstitialActor.AGENT -> JumpConfirmOutcome.AUTO_ALLOWED
            choice == InterstitialChoice.ALLOW && by == InterstitialActor.USER -> JumpConfirmOutcome.USER_ALLOWED
            degradeReason == JumpConfirmDegradeReason.DEVICE_CIRCUIT_BROKEN ||
                degradeReason == JumpConfirmDegradeReason.TARGET_BLOCKED ||
                degradeReason == JumpConfirmDegradeReason.TARGET_ACCESS_UNAVAILABLE -> JumpConfirmOutcome.UNAVAILABLE
            else -> JumpConfirmOutcome.WAITING_USER
        }
    }
}
