package com.wangzan101.mika.core.custody

/**
 * 归因置信度，与 AAU `LaunchOrigin` Bundle 契约保持 0..3 对齐。
 */
enum class OriginConfidence(val level: Int) {
    UNKNOWN(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    ;

    companion object {
        fun fromLevel(level: Int): OriginConfidence =
            entries.firstOrNull { it.level == level } ?: UNKNOWN
    }
}

/**
 * 到达主屏的任务快照（对应底层任务到达事件）。
 */
data class Arrival(
    val taskId: Int,
    val pkg: String?,
    val userId: Int = 0,
    val uid: Int = -1,
    val displayId: Int,
    val fromDisplay: Int = UNKNOWN_DISPLAY,
    val newTask: Boolean,
    val sameTask: Boolean = false,
    val topActivity: String? = null,
    val at: Long,
    val standardTask: Boolean = true,
) {
    companion object {
        const val UNKNOWN_DISPLAY: Int = -1
    }
}

/**
 * 任务启动来源归因结果（由 AAU/系统事件解析出的发起方 UID 与包名）。
 */
data class LaunchOrigin(
    val uid: Int = UNKNOWN_UID,
    val pkg: String? = null,
    val confidence: OriginConfidence = OriginConfidence.UNKNOWN,
) {
    val isUnknown: Boolean
        get() = confidence == OriginConfidence.UNKNOWN || (uid < 0 && pkg.isNullOrBlank())

    companion object {
        const val UNKNOWN_UID: Int = -1
        val UNKNOWN = LaunchOrigin(uid = UNKNOWN_UID, pkg = null, confidence = OriginConfidence.UNKNOWN)
    }
}

/** 用户在系统外围入口产生主动意图点击的来源。 */
enum class UserIntentSource {
    LAUNCHER,
    SYSTEM_UI,
    RECENTS,
}

/**
 * 用户意图点击证据（如点击桌面图标、SystemUI 通知、最近任务卡片的时刻与包名）。
 */
data class UserIntentEvidence(
    val source: UserIntentSource,
    val packageName: String? = null,
    val at: Long,
    val targetPackage: String? = null,
)

enum class IgnoreReason {
    NOT_MAIN_DISPLAY,
    NON_STANDARD_TASK,
    SELF_PACKAGE,
    LAUNCHER_PACKAGE,
    USER_INTENT_EVIDENCE,
    OTHER,
}

enum class InUseReason {
    WAS_MAIN_TOP_TASK,
    SAME_TASK_IN_EXISTING_MAIN_TASK,
}

enum class SpillReason {
    INTERSTITIAL_LEDGER,
    MANAGED_ORIGIN_NO_USER_INTENT,
}

/**
 * 跨屏任务到达的纯归因判定结果（R0–R6）。
 */
sealed interface AttributionResult {
    data class Ignore(val reason: IgnoreReason) : AttributionResult

    data class InUse(val reason: InUseReason) : AttributionResult

    data class Spill(
        val displayId: Int,
        val reason: SpillReason,
        val ledgerEntryId: String? = null,
    ) : AttributionResult

    data class Unattributed(val recentAgentAction: Boolean) : AttributionResult
}

/**
 * [CustodyAttribution] 所需的全部只读上下文快照。
 *
 * - [managedDisplays]：键为当前处于托管中的虚拟屏 ID，值为该屏托管的 UID 集合；
 * - [previousMainTopTaskId]：本次到达前主屏的顶部任务 ID；
 * - [existingMainUserTaskIds]：用户主屏上原本就存在的任务 ID 集合（用于判定 `sameTask` 是否落入用户已有任务）。
 */
data class AttributionContext(
    val selfPackage: String,
    val launcherPackages: Set<String> = emptySet(),
    val managedDisplays: Map<Int, Set<Int>> = emptyMap(),
    val mainDisplayId: Int = 0,
    val previousMainTopTaskId: Int? = null,
    val existingMainUserTaskIds: Set<Int> = emptySet(),
    val ledgerEntries: List<LedgerEntry> = emptyList(),
    val userIntents: List<UserIntentEvidence> = emptyList(),
    val recentAgentAction: Boolean = false,
    val lastAgentActionAtMs: Long? = null,
    val ledgerResolvedWindowMs: Long = LedgerEntry.DEFAULT_RESOLVED_WINDOW_MS,
    val userIntentWindowMs: Long = DEFAULT_USER_INTENT_WINDOW_MS,
    val agentActionWindowMs: Long = DEFAULT_AGENT_ACTION_WINDOW_MS,
) {
    /** 判断在 [atMs] 时刻是否存在最近一次 Agent 操作（供 R5 使用）。 */
    fun hasRecentAgentAction(atMs: Long): Boolean {
        if (recentAgentAction) return true
        val last = lastAgentActionAtMs ?: return false
        return atMs >= last && (atMs - last) <= agentActionWindowMs
    }

    companion object {
        /** 规则 R3/R4 判定用户意图证据的时间窗（1.5s）。 */
        const val DEFAULT_USER_INTENT_WINDOW_MS: Long = 1_500L

        /** 规则 R5 判定最近 Agent 操作的默认时间窗（3s）。 */
        const val DEFAULT_AGENT_ACTION_WINDOW_MS: Long = 3_000L
    }
}

/**
 * 主屏任务到达的纯逻辑归因器。
 *
 * 严格按优先级 **R0 -> R1 -> R2 -> R3 -> R4 -> R5 -> R6** 判定：
 * - **R0**：非主屏到达 / 桌面 / Mika 自身 / 非标准任务 -> [AttributionResult.Ignore]
 * - **R1**：到达前就是主屏顶部任务，或 `sameTask` 落在用户主屏原有任务里 -> [AttributionResult.InUse]
 * - **R2**：账本中存在托管屏 D 的未拒绝中间页 I（Open，或未拒绝且 Resolved ≤ 10s），且满足下列任一条件 -> [AttributionResult.Spill]（忽略用户在 I 上的点击）：
 *   1. `origin.pkg == I.pkg` 或 `origin.uid ∈ managed(D)`；
 *   2. 针对 `JumpConfirm` / `Chooser` 的派生态：`origin` 为系统代转（如 `uid=1000` / `pkg="android"`）或 `UNKNOWN`、到达包名不是 I 自身、1.5s 内无桌面/SystemUI/最近任务的用户意图证据（且若为 `UNKNOWN` origin 且 `JumpConfirm` 已明确 `targetPackage`，到达包需匹配该目标或处于 `Open` 状态）。
 * - **R3**：`origin.uid ∈ managed(D)` 且 `arrival.at` 前 1.5s 内无桌面/SystemUI/最近任务的用户意图证据 -> [AttributionResult.Spill]
 * - **R4**：`origin.uid ∈ managed(D)` 但存在上述用户意图证据 -> [AttributionResult.Ignore]
 * - **R5**：`origin == UNKNOWN`，或存在托管屏时 `origin` 为系统框架代转（`uid=1000` / `pkg="android"` 等 `< 10000` 系统 UID 且无近期用户意图证据；若有近期用户意图则返回 [AttributionResult.Ignore]）-> [AttributionResult.Unattributed]
 * - **R6**：其它 -> [AttributionResult.Ignore]
 */
object CustodyAttribution {
    private const val ROOT_UID = 0
    private const val SYSTEM_UID = 1_000
    private const val SHELL_UID = 2_000
    private const val FIRST_APPLICATION_UID = 10_000
    private const val PER_USER_RANGE = 100_000
    private const val ANDROID_SYSTEM_PACKAGE = "android"

    fun attribute(
        arrival: Arrival,
        origin: LaunchOrigin,
        context: AttributionContext,
    ): AttributionResult {
        // R0: 桌面 / Mika 自身 / 非标准任务（及非主屏到达）-> Ignore
        r0IgnoreReason(arrival, context)?.let { return AttributionResult.Ignore(it) }

        // R1: 到达前就是主屏顶部任务，或 sameTask 落在用户主屏原有任务里 -> InUse
        r1InUseReason(arrival, context)?.let { return AttributionResult.InUse(it) }

        // R2: 账本中存在屏 D 的中间页 I（Open 或未拒绝且 Resolved ≤ 10s），含系统代转派生态 -> Spill(D)
        matchR2LedgerEntry(arrival, origin, context)?.let { entry ->
            return AttributionResult.Spill(
                displayId = entry.displayId,
                reason = SpillReason.INTERSTITIAL_LEDGER,
                ledgerEntryId = entry.interstitial.id,
            )
        }

        // R3 / R4: origin.uid ∈ managed(D) 时根据 1.5s 内是否有桌面/SystemUI/最近任务的用户意图证据分流
        val managedDisplayForOrigin = findManagedDisplayForUid(origin.uid, context.managedDisplays)
        if (!origin.isUnknown && managedDisplayForOrigin != null) {
            return if (hasRecentUserIntent(arrival.at, context)) {
                AttributionResult.Ignore(IgnoreReason.USER_INTENT_EVIDENCE)
            } else {
                AttributionResult.Spill(
                    displayId = managedDisplayForOrigin,
                    reason = SpillReason.MANAGED_ORIGIN_NO_USER_INTENT,
                )
            }
        }

        // R5: origin == UNKNOWN -> Unattributed(recentAgentAction)
        if (origin.isUnknown) {
            return AttributionResult.Unattributed(
                recentAgentAction = context.hasRecentAgentAction(arrival.at),
            )
        }

        // R5b: 存在托管屏时，系统框架代转（如 ColorOS uid=1000 / pkg=android 弹出跳转确认框）不能直接丢进 R6；
        // 若近期有桌面/SystemUI/最近任务用户点击则放行，否则交给上层按系统跳转弹窗分类核验。
        if (context.managedDisplays.isNotEmpty() && isSystemMediatedOrigin(origin)) {
            if (hasRecentUserIntent(arrival.at, context)) {
                return AttributionResult.Ignore(IgnoreReason.USER_INTENT_EVIDENCE)
            }
            return AttributionResult.Unattributed(
                recentAgentAction = context.hasRecentAgentAction(arrival.at),
            )
        }

        // R6: 其它 -> Ignore
        return AttributionResult.Ignore(IgnoreReason.OTHER)
    }

    private fun r0IgnoreReason(arrival: Arrival, context: AttributionContext): IgnoreReason? = when {
        arrival.displayId != context.mainDisplayId -> IgnoreReason.NOT_MAIN_DISPLAY
        !arrival.standardTask -> IgnoreReason.NON_STANDARD_TASK
        arrival.pkg == context.selfPackage -> IgnoreReason.SELF_PACKAGE
        arrival.pkg != null && arrival.pkg in context.launcherPackages -> IgnoreReason.LAUNCHER_PACKAGE
        else -> null
    }

    private fun r1InUseReason(arrival: Arrival, context: AttributionContext): InUseReason? {
        if (arrival.taskId >= 0 && context.previousMainTopTaskId != null && arrival.taskId == context.previousMainTopTaskId) {
            return InUseReason.WAS_MAIN_TOP_TASK
        }
        if (arrival.sameTask && arrival.taskId >= 0 && arrival.taskId in context.existingMainUserTaskIds) {
            return InUseReason.SAME_TASK_IN_EXISTING_MAIN_TASK
        }
        return null
    }

    private fun matchR2LedgerEntry(
        arrival: Arrival,
        origin: LaunchOrigin,
        context: AttributionContext,
    ): LedgerEntry? {
        val systemMediated = isSystemMediatedOrigin(origin)
        if (origin.isUnknown && context.managedDisplays.isEmpty()) return null
        val recentUserIntent = hasRecentUserIntent(arrival.at, context)

        return context.ledgerEntries
            .asSequence()
            .filter { entry ->
                val displayUids = context.managedDisplays[entry.displayId] ?: return@filter false
                if (!entry.matchesAttributionWindow(arrival.at, context.ledgerResolvedWindowMs)) {
                    return@filter false
                }
                if (entry.isDenied()) return@filter false

                val directMatch = !origin.isUnknown && (
                    (!origin.pkg.isNullOrBlank() && origin.pkg == entry.packageName) ||
                        (origin.uid >= 0 && origin.uid in displayUids)
                    )
                if (directMatch) return@filter true

                // 系统代转 / 目标自转派（ColorOS 等确认后以目标自身 UID/pkg 续发）/ 未知来源下的跳转确认或选择器派生态：
                // 注意排除同包（例如确认弹窗自身重启），避免把等待中的确认框误判成目标应用已放行
                val expectsFollowupTarget =
                    entry.interstitial is Interstitial.JumpConfirm || entry.interstitial is Interstitial.Chooser
                if (!expectsFollowupTarget || recentUserIntent || arrival.pkg == entry.packageName) {
                    return@filter false
                }
                val jc = entry.interstitial as? Interstitial.JumpConfirm
                val chooser = entry.interstitial as? Interstitial.Chooser
                val targetPkg = jc?.targetPackage
                val initiatorPkg = jc?.initiatorPackage ?: chooser?.initiatorPackage
                val pendingUserDismiss = (entry.state as? LedgerState.Resolved)?.let {
                    it.by == InterstitialActor.USER && it.choice == InterstitialChoice.UNKNOWN
                } == true
                val selfOriginatedTarget = !origin.isUnknown && when {
                    !origin.pkg.isNullOrBlank() && !arrival.pkg.isNullOrBlank() -> origin.pkg == arrival.pkg
                    origin.uid >= FIRST_APPLICATION_UID && arrival.uid >= FIRST_APPLICATION_UID -> origin.uid == arrival.uid
                    else -> false
                }
                when {
                    systemMediated -> targetPkg == null || arrival.pkg == null || arrival.pkg == targetPkg
                    selfOriginatedTarget -> {
                        if (targetPkg != null) {
                            arrival.pkg == targetPkg
                        } else {
                            val notInitiator = initiatorPkg == null || arrival.pkg != initiatorPkg
                            notInitiator && (entry.isOpen || entry.isAllowed() || pendingUserDismiss)
                        }
                    }
                    origin.isUnknown -> {
                        if (targetPkg != null) arrival.pkg == targetPkg else entry.isOpen || entry.isAllowed() || pendingUserDismiss
                    }
                    else -> false
                }
            }
            .sortedWith(R2_PREFERENCE)
            .firstOrNull()
    }

    private fun isSystemMediatedOrigin(origin: LaunchOrigin): Boolean {
        if (origin.isUnknown) return false
        if (origin.pkg == ANDROID_SYSTEM_PACKAGE) return true
        val uid = origin.uid
        if (uid < 0) return false
        val appId = uid % PER_USER_RANGE
        return (appId in SYSTEM_UID until FIRST_APPLICATION_UID && appId != SHELL_UID) ||
            (appId == ROOT_UID && origin.pkg == ANDROID_SYSTEM_PACKAGE)
    }

    private fun LedgerEntry.isDenied(): Boolean =
        (state as? LedgerState.Resolved)?.choice == InterstitialChoice.DENY

    private fun LedgerEntry.isAllowed(): Boolean =
        (state as? LedgerState.Resolved)?.choice == InterstitialChoice.ALLOW

    private fun findManagedDisplayForUid(
        uid: Int,
        managedDisplays: Map<Int, Set<Int>>,
    ): Int? {
        if (uid < 0) return null
        return managedDisplays.entries
            .asSequence()
            .filter { (_, uids) -> uid in uids }
            .minByOrNull { it.key }
            ?.key
    }

    private fun hasRecentUserIntent(arrivalAt: Long, context: AttributionContext): Boolean =
        context.userIntents.any { evidence ->
            arrivalAt >= evidence.at && (arrivalAt - evidence.at) <= context.userIntentWindowMs
        }

    private val R2_PREFERENCE =
        compareByDescending<LedgerEntry> { it.isOpen }
            .thenByDescending { it.updatedAtMs }
            .thenByDescending { it.interstitial.openedAtMs }
            .thenBy { it.displayId }
}
