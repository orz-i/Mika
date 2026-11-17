package com.wangzan101.mika.cap.phone.a11y

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agent.use.a11y.DisplayWindowInspector
import com.agent.use.common.CustodyArrival
import com.agent.use.common.IAgentCustodyListener
import com.agent.use.common.IAgentShell
import com.agent.use.common.LaunchOrigin as ShellLaunchOrigin
import com.wangzan101.mika.core.access.AppAccessPolicy
import com.wangzan101.mika.core.custody.Arrival
import com.wangzan101.mika.core.custody.AttributionContext
import com.wangzan101.mika.core.custody.AttributionResult
import com.wangzan101.mika.core.custody.ConfirmButton
import com.wangzan101.mika.core.custody.ConfirmButtonRole
import com.wangzan101.mika.core.custody.ConfirmButtons
import com.wangzan101.mika.core.custody.CustodyAttribution
import com.wangzan101.mika.core.custody.InUseReason
import com.wangzan101.mika.core.custody.Interstitial
import com.wangzan101.mika.core.custody.InterstitialActor
import com.wangzan101.mika.core.custody.InterstitialChoice
import com.wangzan101.mika.core.custody.InterstitialKind
import com.wangzan101.mika.core.custody.InterstitialLedger
import com.wangzan101.mika.core.custody.JumpConfirmDecision
import com.wangzan101.mika.core.custody.JumpConfirmDegradeReason
import com.wangzan101.mika.core.custody.JumpConfirmOutcome
import com.wangzan101.mika.core.custody.JumpConfirmPolicy
import com.wangzan101.mika.core.custody.JumpConfirmProfilePolicy
import com.wangzan101.mika.core.custody.JumpConfirmRateLimiter
import com.wangzan101.mika.core.custody.JumpConfirmReport
import com.wangzan101.mika.core.custody.JumpConfirmRequest
import com.wangzan101.mika.core.custody.JumpRouteKey
import com.wangzan101.mika.core.custody.LaunchOrigin
import com.wangzan101.mika.core.custody.LedgerEntry
import com.wangzan101.mika.core.custody.LedgerState
import com.wangzan101.mika.core.custody.OriginConfidence
import com.wangzan101.mika.core.custody.SpillReason
import com.wangzan101.mika.core.custody.UserIntentEvidence
import com.wangzan101.mika.core.custody.UserIntentSource
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.uistate.Hant
import com.wangzan101.mika.core.uistate.LaunchChoice
import com.wangzan101.mika.core.uistate.OpenedElsewhere
import com.wangzan101.mika.settings.FeatureFlags
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App 侧跨屏任务常驻托管器（彻底替代旧版 6s 窗口轮询机制）。
 *
 * 职责：
 * 1. 向 Shell 注册常驻 [IAgentCustodyListener]，同时订阅主屏与托管屏无障碍事件及全局 [windowChanges]
 *    （覆盖无 `taskId` 的 `SYSTEM_ALERT_WINDOW` 悬浮确认框）；
 * 2. 区分桌面 / SystemUI / 最近任务用户意图点击与正在打开的中间页自身点击，维护 [InterstitialLedger]；
 * 3. 调用 [CustodyAttribution] 进行 R0–R6 跨屏归因与 [InterstitialClassifier] 中间页识别；
 * 4. 开关关闭（或策略降级）时挂起任务通过 [interstitialState] / [awaitInterstitial] 通知 UI，
 *    支持用户点击「到主屏确认」才把落在虚拟屏上的弹窗移到主屏，以及 >10s 延迟确认按 R2 回捞；
 * 5. 开关开启且 [JumpConfirmPolicy] 9 条护栏全过时，原地用无障碍 `ACTION_CLICK` 点唯一肯定项
 *    （绝不走主屏触摸注入）、留痕 [RunEvent.JumpConfirm]，若失败或 1s 未消失则降级并熔断记录。
 */
internal class DisplayCustodian(
    private val runtime: Runtime,
    private val classifier: InterstitialClassifier,
    private val selfPackage: String,
    private val launcherPackages: Set<String>,
    private val appAccess: AppAccessPolicy,
    private val flags: FeatureFlags? = null,
    private val appVersionCode: Int = 0,
    private val jumpConfirmProfiles: JumpConfirmProfilePolicy = JumpConfirmProfilePolicy.EMPTY,
    private val allowUserInteraction: Boolean = true,
    private val isHandedOver: () -> Boolean = { false },
    private val recordEvent: (RunEvent) -> Unit = {},
    private val targetAppProvider: () -> Pair<String, Int>? = { null },
    private val adoptedTasksProvider: () -> Set<Int> = { emptySet() },
    private val onTaskPulledBack: (taskId: Int, pkg: String?, userId: Int, displayId: Int) -> Unit = { _, _, _, _ -> },
    private val inProfileOf: suspend (String, Int) -> String? = { _, _ -> null },
    private val ledger: InterstitialLedger = InterstitialLedger(openTtlMs = DEFAULT_CUSTODY_OPEN_TTL_MS),
    private val rateLimiter: JumpConfirmRateLimiter = JumpConfirmRateLimiter(),
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val hostingEnabled: () -> Boolean = { flags?.jumpConfirmHosting() == true },
    private val isDeviceCircuitBroken: () -> Boolean = { flags?.jumpConfirmAutoBlocked(appVersionCode) == true },
    private val onCircuitBreak: () -> Unit = { flags?.markJumpConfirmAutoBlocked(appVersionCode) },
    parentScope: CoroutineScope? = null,
) : AutoCloseable {

    /** 向 UI / TaskRun 暴露的当前挂起中间页状态。 */
    data class PendingInterstitial(
        val id: String,
        val kind: InterstitialKind,
        val displayId: Int,
        val taskId: Int,
        val packageName: String,
        val appLabel: String?,
        val initiatorPackage: String?,
        val targetPackage: String?,
        val targetLabel: String?,
        val onVirtualDisplay: Boolean,
        val degradeReason: JumpConfirmDegradeReason? = null,
        val sinceMs: Long,
        val previousMainTopTaskId: Int,
    )

    /** 一次跨屏/弹窗事件的处理产出，供 [AndroidScreen] 构造 tool feedback 与 `OpenedElsewhere`。 */
    data class SpillReport(
        val opened: OpenedElsewhere,
        val chooser: LaunchChoice? = null,
    )

    /** [awaitInterstitial] 的等待结果。 */
    sealed interface WaitResult {
        data class Settled(val report: SpillReport? = null) : WaitResult
        data class NeedsUser(
            val taskId: Int,
            val packageName: String,
            val app: String?,
            val userId: Int,
        ) : WaitResult
        data class Unavailable(val report: SpillReport) : WaitResult
    }

    /** 供 [afterUserStep] 使用的用户步骤上下文。 */
    data class UserStepTask(
        val taskId: Int,
        val packageName: String,
        val app: String?,
        val userId: Int,
    )

    data class InspectedWindow(
        val inspection: InterstitialClassifier.WindowInspection,
        val performPositiveClick: (ConfirmButton) -> Boolean,
    )

    data class A11yEventInfo(
        val displayId: Int,
        val eventType: Int,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val atMs: Long,
    )

    /** 解耦 Android Shell / Accessibility 框架调用，便于设备端与 JVM 单测共享完整状态机。 */
    interface Runtime {
        fun managedDisplayId(): Int
        fun startShellCustody(
            displayId: Int,
            onArrived: (CustodyArrival) -> Unit,
            onVanished: (taskId: Int, lastDisplayId: Int, atMs: Long) -> Unit,
        ): Boolean
        fun stopShellCustody(displayId: Int)
        fun launchOrigin(taskId: Int): LaunchOrigin
        fun resolveJumpTarget(taskId: Int, interstitialPackage: String?, initiatorPackage: String?): String? = null
        fun moveTask(taskId: Int, displayId: Int): Boolean
        fun displayOfTask(taskId: Int): Int
        fun topTaskId(displayId: Int): Int
        fun topActivity(displayId: Int): String?
        fun packageOfTask(taskId: Int): String?
        fun userOfTask(taskId: Int): Int
        fun visibleOnMainScreen(pkg: String, userId: Int): Boolean
        fun uidOfPackage(pkg: String): Int
        fun appLabel(pkg: String): String?
        suspend fun inspectDisplayWindows(
            displayId: Int,
            componentHint: String? = null,
            checkSecureOnUnreadable: Boolean = false,
        ): List<InspectedWindow>
        fun displayEvents(displayId: Int): Flow<A11yEventInfo>
        fun windowChanges(): Flow<Unit>
    }

    private sealed interface Signal {
        data class Arrived(val arrival: CustodyArrival) : Signal
        data class Vanished(val taskId: Int, val lastDisplayId: Int, val atMs: Long) : Signal
        data class WindowScan(val displayId: Int, val atMs: Long) : Signal
        data class A11y(val event: A11yEventInfo) : Signal
        data class Drain(val ack: CompletableDeferred<Unit>) : Signal
    }

    private val scope: CoroutineScope = parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ownsScope: Boolean = parentScope == null
    private val stateMutex = Mutex()
    private val signals = Channel<Signal>(Channel.UNLIMITED)

    private val _interstitialState = MutableStateFlow<PendingInterstitial?>(null)
    val interstitialState: StateFlow<PendingInterstitial?> = _interstitialState.asStateFlow()

    private val pendingSpills = ArrayDeque<SpillReport>()
    private val userIntents = ArrayDeque<UserIntentEvidence>()
    private val managedUidsByDisplay = ConcurrentHashMap<Int, MutableSet<Int>>()
    private val vdPackages = ConcurrentHashMap.newKeySet<String>()
    private val existingMainUserTaskIds = ConcurrentHashMap.newKeySet<Int>()
    private val userActedEntries = ConcurrentHashMap.newKeySet<String>()
    private val learnedProfileEntries = ConcurrentHashMap.newKeySet<String>()
    private val recentNegativeTapAt = HashMap<Int, Pair<String, Long>>() // displayId -> (pkg, atMs)
    private val recentPositiveTapAt = HashMap<Int, Pair<String, Long>>() // displayId -> (pkg, atMs)
    private val waitersForResolution = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
    @Volatile private var pendingAccessDenial: com.wangzan101.mika.core.access.AppAccessDenied? = null
    @Volatile private var movedToMainGrace: Pair<String, Long>? = null

    @Volatile private var started = false
    @Volatile private var closed = false
    @Volatile private var shellRegisteredDisplayId: Int = -1
    @Volatile private var currentMainTopTaskId: Int = -1
    @Volatile private var previousMainTopTaskId: Int = -1
    @Volatile private var lastAgentActionAtMs: Long? = null
    @Volatile private var expectedTargetPackage: String? = null
    @Volatile private var expectedBeforePackage: String? = null
    @Volatile private var waitGateNeedsUserResult: WaitResult.NeedsUser? = null

    private var workerJob: Job? = null
    private var mainEventsJob: Job? = null
    private var windowChangesJob: Job? = null
    private var vdEventsJob: Job? = null
    private var observedVdId: Int = -1

    /** 启动长驻事件流与 Shell 托管绑定（幂等）。 */
    fun start() {
        if (closed) return
        if (!started) {
            started = true
            val initialMain = runtime.topTaskId(Display.DEFAULT_DISPLAY)
            currentMainTopTaskId = initialMain
            previousMainTopTaskId = initialMain
            if (initialMain >= 0 && initialMain !in adoptedTasksProvider()) {
                existingMainUserTaskIds += initialMain
            }
            workerJob = scope.launch { runEventLoop() }
            mainEventsJob = scope.launch {
                runtime.displayEvents(Display.DEFAULT_DISPLAY).collect { ev ->
                    signals.trySend(Signal.A11y(ev))
                }
            }
            windowChangesJob = scope.launch {
                runtime.windowChanges().collect {
                    val now = clock()
                    signals.trySend(Signal.WindowScan(Display.DEFAULT_DISPLAY, now))
                    val vd = runtime.managedDisplayId()
                    if (vd > Display.DEFAULT_DISPLAY) {
                        signals.trySend(Signal.WindowScan(vd, now))
                    }
                }
            }
        }
        ensureDisplayCustody()
    }

    /** 确保当虚拟屏已创建时立即接入 `startCustody` 与 VD 事件流。 */
    fun ensureDisplayCustody() {
        if (closed) return
        val vd = runtime.managedDisplayId()
        if (vd <= Display.DEFAULT_DISPLAY) return
        ensureShellAndEventStreams(vd)
        syncVdMetadata(vd)
    }

    private fun ensureShellAndEventStreams(vd: Int) {
        if (closed || vd <= Display.DEFAULT_DISPLAY) return
        if (shellRegisteredDisplayId != vd) {
            if (shellRegisteredDisplayId > Display.DEFAULT_DISPLAY) {
                runtime.stopShellCustody(shellRegisteredDisplayId)
            }
            val ok = runtime.startShellCustody(
                displayId = vd,
                onArrived = { arrival -> signals.trySend(Signal.Arrived(arrival)) },
                onVanished = { taskId, lastDisplayId, at -> signals.trySend(Signal.Vanished(taskId, lastDisplayId, at)) },
            )
            if (ok) {
                shellRegisteredDisplayId = vd
            }
        }
        if (observedVdId != vd) {
            observedVdId = vd
            vdEventsJob?.cancel()
            vdEventsJob = scope.launch {
                runtime.displayEvents(vd).collect { ev ->
                    signals.trySend(Signal.A11y(ev))
                }
            }
        }
    }

    /** Agent 即将在虚拟屏发起操作：刷新托管绑定、活跃动作时间戳与主屏基线。 */
    fun onAgentActionStarting(expectedTargetPkg: String? = null, beforePkg: String? = null) {
        if (closed) return
        start()
        lastAgentActionAtMs = clock()
        expectedTargetPkg?.let { expectedTargetPackage = it }
        beforePkg?.let { expectedBeforePackage = it }
        val mainTop = runtime.topTaskId(Display.DEFAULT_DISPLAY)
        if (mainTop >= 0) {
            currentMainTopTaskId = mainTop
            previousMainTopTaskId = mainTop
            if (mainTop !in adoptedTasksProvider()) {
                existingMainUserTaskIds += mainTop
            }
        }
    }

    /** 告知托管器 Agent 刚在虚拟屏启动或切入了指定包/任务，更新 `managed(D)` 归属映射。 */
    fun onTargetObserved(pkg: String?, taskId: Int = -1) {
        val vd = runtime.managedDisplayId()
        if (vd <= Display.DEFAULT_DISPLAY) return
        val uids = managedUidsByDisplay.getOrPut(vd) { ConcurrentHashMap.newKeySet() }
        if (!pkg.isNullOrBlank()) {
            vdPackages += pkg
            val uid = runtime.uidOfPackage(pkg)
            if (uid >= android.os.Process.FIRST_APPLICATION_UID) uids += uid
        }
        if (taskId >= 0) {
            existingMainUserTaskIds -= taskId
        }
        if (shellRegisteredDisplayId != vd) {
            ensureShellAndEventStreams(vd)
        }
    }

    /** 刷新队列中的积压事件后，弹出已消费的一批越屏报告（供动作结束后/下一次捕获前查看）。 */
    suspend fun drainAndTakeLatestSpill(settleMs: Long = 0L): SpillReport? {
        if (closed) return null
        ensureDisplayCustody()
        if (settleMs > 0L) delay(settleMs)
        if (!isHandedOver() &&
            runtime.managedDisplayId() > Display.DEFAULT_DISPLAY &&
            _interstitialState.value == null
        ) {
            signals.trySend(Signal.WindowScan(Display.DEFAULT_DISPLAY, clock()))
        }
        flushSignals()
        pendingAccessDenial?.let { pendingAccessDenial = null; throw it }
        return stateMutex.withLock {
            var latest: SpillReport? = null
            while (pendingSpills.isNotEmpty()) {
                latest = pendingSpills.removeFirst()
            }
            latest
        }
    }

    /** 在步前关卡拉平事件并扫描主屏系统确认弹窗，确保不会漏过滞后到达或重用的跳转框。 */
    suspend fun refreshAndCurrentInterstitial(): PendingInterstitial? {
        if (closed) return _interstitialState.value
        ensureDisplayCustody()
        if (!isHandedOver() &&
            runtime.managedDisplayId() > Display.DEFAULT_DISPLAY &&
            _interstitialState.value == null
        ) {
            signals.trySend(Signal.WindowScan(Display.DEFAULT_DISPLAY, clock()))
        }
        flushSignals()
        return _interstitialState.value
    }

    /** 仅取出目前已累积的待报告越屏结果（不等待队列排空时可用）。 */
    suspend fun takePendingSpill(): SpillReport? {
        pendingAccessDenial?.let { pendingAccessDenial = null; throw it }
        return stateMutex.withLock {
            var latest: SpillReport? = null
            while (pendingSpills.isNotEmpty()) {
                latest = pendingSpills.removeFirst()
            }
            latest
        }
    }

    /**
     * 当确认框落在虚拟屏且开关关闭时，用户在胶囊/卡片点击「到主屏确认」后才显式将其 `moveTask` 到主屏。
     */
    suspend fun moveInterstitialToMain(): Boolean {
        val pending = _interstitialState.value ?: return false
        if (!pending.onVirtualDisplay) return true
        val vd = runtime.managedDisplayId()
        val taskToMove = if (pending.taskId >= 0) {
            pending.taskId
        } else {
            val top = if (vd > Display.DEFAULT_DISPLAY) runtime.topTaskId(vd) else -1
            if (top >= 0 && runtime.packageOfTask(top) == pending.packageName) top else -1
        }
        if (taskToMove < 0) return false
        val moved = runtime.moveTask(taskToMove, Display.DEFAULT_DISPLAY)
        if (moved) {
            lastAgentActionAtMs = clock()
            movedToMainGrace = pending.id to (clock() + MAIN_MOVE_SETTLE_GRACE_MS)
            stateMutex.withLock {
                val cur = _interstitialState.value ?: return@withLock
                if (cur.id == pending.id) {
                    _interstitialState.value = cur.copy(onVirtualDisplay = false, displayId = Display.DEFAULT_DISPLAY)
                    // 账本条目保持绑定托管虚拟屏 vd 以维持 R2 归因窗口，仅更新物理 taskId
                    val ledgerDisplayId = if (vd > Display.DEFAULT_DISPLAY) vd else cur.displayId
                    ledger.open(
                        Interstitial.JumpConfirm(
                            id = cur.id,
                            displayId = ledgerDisplayId,
                            taskId = taskToMove,
                            packageName = cur.packageName,
                            componentName = null,
                            openedAtMs = cur.sinceMs,
                            initiatorPackage = cur.initiatorPackage,
                            targetPackage = cur.targetPackage,
                            targetLabel = cur.targetLabel,
                        ),
                        nowMs = clock(),
                    )
                }
            }
        }
        return moved
    }

    /**
     * 挂起等待当前活跃中间页（选择器 / 跳转确认弹窗）由用户处理完：
     * - 不再受旧 6s 硬超时限制：只要确认框仍可见，即使用户延迟 >10s 再确认，也会保持挂起；
     * - 确认框关闭后短暂等待派生目标任务启动并完成 R2 回捞；
     * - 若处于无人值守模式（`!allowUserInteraction`），立即返回 [WaitResult.Unavailable] 不无限挂起。
     */
    suspend fun awaitInterstitial(): WaitResult? {
        flushSignals()
        val active = _interstitialState.value ?: return null
        if (!allowUserInteraction) {
            val report = SpillReport(
                OpenedElsewhere(
                    packageName = active.targetPackage ?: active.packageName,
                    app = active.targetLabel ?: active.appLabel,
                    pulledBack = false,
                    needsUser = false,
                    jumpConfirm = JumpConfirmReport(
                        initiator = active.initiatorPackage,
                        target = active.targetPackage,
                        by = null,
                        choice = InterstitialChoice.UNKNOWN,
                        outcome = JumpConfirmOutcome.UNAVAILABLE,
                        degradeReason = active.degradeReason,
                    ),
                ),
            )
            recordJumpConfirmEvent(
                initiator = active.initiatorPackage,
                target = active.targetPackage,
                component = null,
                result = "unavailable",
                degraded = true,
                reason = active.degradeReason?.name ?: "unattended",
                by = null,
                displayId = active.displayId,
            )
            stateMutex.withLock {
                enqueueSpill(report)
                ledger.resolve(active.id, InterstitialActor.AGENT, InterstitialChoice.UNKNOWN, clock())
                _interstitialState.value = null
            }
            return WaitResult.Unavailable(report)
        }

        // 无超时地持续挂起，直到中间页消失或关闭（可覆盖 >10s 延迟确认）
        while (!closed) {
            val current = _interstitialState.value
            if (current == null || current.id != active.id) break
            val waiter = CompletableDeferred<Unit>()
            waitersForResolution += waiter
            // 启动兜底轻轮询检查窗口是否已消失（防止部分 ROM 关闭悬浮窗时不重发 a11y 事件）
            val checkJob = scope.launch {
                while (!waiter.isCompleted && !closed) {
                    delay(WINDOW_RECHECK_MS)
                    signals.trySend(Signal.WindowScan(current.displayId, clock()))
                }
            }
            try {
                waiter.await()
            } finally {
                checkJob.cancel()
                waitersForResolution.remove(waiter)
            }
        }

        // 等待刚消散的中间页派生的目标任务完成落地与 R2 回捞
        delay(POST_RESOLVE_SETTLE_MS)
        flushSignals()

        val needsUser = stateMutex.withLock {
            waitGateNeedsUserResult?.also { waitGateNeedsUserResult = null }
        }
        if (needsUser != null) return needsUser

        val report = takePendingSpill()
        return WaitResult.Settled(report)
    }

    /** 用户处理完 [WaitResult.NeedsUser] 并点击继续：若仍在主屏，手动挪回虚拟屏并记入待报告越屏状态。 */
    suspend fun afterUserStep(step: UserStepTask): SpillReport {
        flushSignals()
        val vd = runtime.managedDisplayId()
        val stillOnMain = runtime.displayOfTask(step.taskId) == Display.DEFAULT_DISPLAY
        val moved = stillOnMain && vd > Display.DEFAULT_DISPLAY && runtime.moveTask(step.taskId, vd)
        if (moved) {
            onTargetObserved(step.packageName, step.taskId)
            onTaskPulledBack(step.taskId, step.packageName, step.userId, vd)
        }
        val report = SpillReport(
            OpenedElsewhere(
                packageName = step.packageName,
                app = step.app ?: runtime.appLabel(step.packageName),
                pulledBack = moved,
                inProfile = inProfileOf(step.packageName, step.userId),
                byUser = true,
            ),
        )
        stateMutex.withLock {
            enqueueSpill(report)
        }
        return report
    }

    /** 在关闭当前 [AndroidScreen] 前注销常驻托管监听并释放协程。 */
    override fun close() {
        if (closed) return
        closed = true
        mainEventsJob?.cancel()
        windowChangesJob?.cancel()
        vdEventsJob?.cancel()
        val vd = shellRegisteredDisplayId
        if (vd > Display.DEFAULT_DISPLAY) {
            runtime.stopShellCustody(vd)
            shellRegisteredDisplayId = -1
        }
        for (w in waitersForResolution) {
            w.complete(Unit)
        }
        waitersForResolution.clear()
        workerJob?.cancel()
        signals.close()
        if (ownsScope) {
            scope.cancel()
        }
    }

    private suspend fun flushSignals() {
        if (closed) return
        val ack = CompletableDeferred<Unit>()
        if (signals.trySend(Signal.Drain(ack)).isSuccess) {
            withTimeoutOrNull(2_000L) { ack.await() }
        }
    }

    private suspend fun runEventLoop() {
        for (signal in signals) {
            if (closed) break
            try {
                when (signal) {
                    is Signal.Arrived -> handleArrival(signal.arrival)
                    is Signal.Vanished -> handleVanished(signal.taskId, signal.lastDisplayId, signal.atMs)
                    is Signal.WindowScan -> handleWindowScan(signal.displayId, signal.atMs)
                    is Signal.A11y -> handleA11yEvent(signal.event)
                    is Signal.Drain -> signal.ack.complete(Unit)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "event loop error on $signal", t)
            }
        }
    }

    private suspend fun handleA11yEvent(ev: A11yEventInfo) {
        val now = ev.atMs
        when (ev.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val pkg = ev.packageName?.takeIf { it.isNotBlank() }
                    ?: runtime.packageOfTask(runtime.topTaskId(ev.displayId))
                if (ev.displayId == Display.DEFAULT_DISPLAY && !pkg.isNullOrBlank()) {
                    var hitOpenInterstitial = false
                    stateMutex.withLock {
                        val pendingOnMain = _interstitialState.value?.takeIf { !it.onVirtualDisplay }
                        val openEntries = ledger.activeOrRecent(now).filter { it.isOpen }
                        val matching = openEntries.firstOrNull { entry ->
                            entry.packageName == pkg && (pendingOnMain == null || pendingOnMain.id == entry.interstitial.id)
                        } ?: if (ev.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START &&
                            pendingOnMain != null && pkg !in launcherPackages && pkg != SYSTEM_UI_PACKAGE
                        ) {
                            openEntries.firstOrNull { it.interstitial.id == pendingOnMain.id }
                        } else {
                            null
                        }
                        if (matching != null) {
                            hitOpenInterstitial = true
                            userActedEntries += matching.interstitial.id
                            if (ev.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && !ev.text.isNullOrBlank()) {
                                val role = ConfirmButtons.classify(ev.text)
                                if (role == ConfirmButtonRole.NEGATIVE) {
                                    recentNegativeTapAt[Display.DEFAULT_DISPLAY] = pkg to now
                                } else if (role == ConfirmButtonRole.POSITIVE) {
                                    recentPositiveTapAt[Display.DEFAULT_DISPLAY] = pkg to now
                                }
                            }
                        } else {
                            val source = when {
                                pkg == SYSTEM_UI_PACKAGE -> UserIntentSource.SYSTEM_UI
                                isRecentsActivity(ev.className) -> UserIntentSource.RECENTS
                                pkg in launcherPackages -> UserIntentSource.LAUNCHER
                                else -> null
                            }
                            if (source != null) {
                                pruneUserIntents(now)
                                userIntents.addLast(UserIntentEvidence(source = source, packageName = pkg, at = now))
                            }
                        }
                    }
                    if (hitOpenInterstitial && ev.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                        signals.trySend(Signal.WindowScan(Display.DEFAULT_DISPLAY, now + 80L))
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (ev.displayId == Display.DEFAULT_DISPLAY || ev.displayId == runtime.managedDisplayId()) {
                    handleWindowScan(ev.displayId, now, componentHint = ev.className)
                }
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                handleWindowScan(ev.displayId, now)
            }
        }
    }

    private suspend fun handleArrival(arrival: CustodyArrival) {
        if (isHandedOver()) return
        val vd = runtime.managedDisplayId()
        val now = if (arrival.at > 0L) arrival.at else clock()
        syncVdMetadata(vd)

        if (vd > Display.DEFAULT_DISPLAY &&
            (arrival.displayId == vd ||
                (arrival.taskId >= 0 && arrival.packageName in vdPackages && runtime.displayOfTask(arrival.taskId) == vd))
        ) {
            recordVdArrival(vd, arrival)
            return
        }

        if (arrival.displayId != Display.DEFAULT_DISPLAY) return
        if (arrival.managedDisplayId == vd &&
            arrival.originUid >= android.os.Process.FIRST_APPLICATION_UID &&
            vd > Display.DEFAULT_DISPLAY
        ) {
            managedUidsByDisplay.getOrPut(vd) { ConcurrentHashMap.newKeySet() } += arrival.originUid
        }

        val resolvedOrigin = resolveOrigin(arrival)
        val coreArrival = Arrival(
            taskId = arrival.taskId,
            pkg = arrival.packageName,
            userId = arrival.userId,
            uid = arrival.uid,
            displayId = arrival.displayId,
            fromDisplay = arrival.fromDisplayId,
            newTask = arrival.newTask,
            sameTask = arrival.sameTask,
            topActivity = arrival.activity,
            at = now,
            standardTask = arrival.standard,
        )

        val ctx = buildAttributionContext(vd, now)
        val result = CustodyAttribution.attribute(coreArrival, resolvedOrigin, ctx)
        Log.i(TAG, "arrival task=${arrival.taskId} pkg=${arrival.packageName} disp=${arrival.displayId} origin=${resolvedOrigin.pkg}/${resolvedOrigin.uid} -> $result")

        when (result) {
            is AttributionResult.Ignore -> {
                updateMainTopTracker(arrival.taskId, arrival.packageName)
            }

            is AttributionResult.InUse -> {
                updateMainTopTracker(arrival.taskId, arrival.packageName)
                stateMutex.withLock {
                    if (arrival.taskId >= 0) existingMainUserTaskIds += arrival.taskId
                    if (result.reason == InUseReason.SAME_TASK_IN_EXISTING_MAIN_TASK) {
                        val pkg = arrival.packageName
                        if (pkg !in launcherPackages && pkg != selfPackage) {
                            enqueueSpill(
                                SpillReport(
                                    OpenedElsewhere(
                                        packageName = pkg,
                                        app = runtime.appLabel(pkg),
                                        pulledBack = false,
                                        inProfile = inProfileOf(pkg, arrival.userId),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }

            is AttributionResult.Unattributed -> {
                if (_interstitialState.value?.let { it.taskId >= 0 && it.taskId == arrival.taskId && !it.onVirtualDisplay } == true) {
                    return
                }
                // 即使 Shell Origin 无法完全归因，若在近期 Agent 动作窗内落到主屏：
                // 1）若为系统授权弹窗/选择器，交由分类器代点或挂起等待；
                // 2）若为无授权弹窗的普通第三方 App 直跳，直接拉回虚拟屏，且拉回前不污染 currentMainTopTaskId。
                val handled = if (arrival.packageName !in launcherPackages && arrival.packageName != selfPackage) {
                    tryClassifyMainWindowForArrival(
                        arrival = arrival,
                        origin = resolvedOrigin,
                        vd = vd,
                        now = now,
                        unattributed = true,
                        recentAgentAction = result.recentAgentAction,
                    )
                } else {
                    false
                }
                if (!handled) {
                    updateMainTopTracker(arrival.taskId, arrival.packageName)
                }
            }

            is AttributionResult.Spill -> {
                handleMainSpill(arrival, resolvedOrigin, result, vd, now)
            }
        }
    }

    private suspend fun recordVdArrival(vd: Int, arrival: CustodyArrival) {
        val now = if (arrival.at > 0L) arrival.at else clock()
        val isInterstitialPkg = arrival.packageName.isNotBlank() &&
            classifier.isSystemApp(arrival.packageName) &&
            (_interstitialState.value?.packageName == arrival.packageName ||
                ledger.activeOrRecent(now).any { it.packageName == arrival.packageName })
        val uids = managedUidsByDisplay.getOrPut(vd) { ConcurrentHashMap.newKeySet() }
        if (arrival.uid >= android.os.Process.FIRST_APPLICATION_UID) uids += arrival.uid
        if (arrival.packageName.isNotBlank() && !isInterstitialPkg) {
            vdPackages += arrival.packageName
            val pmUid = runtime.uidOfPackage(arrival.packageName)
            if (pmUid >= android.os.Process.FIRST_APPLICATION_UID) uids += pmUid
        }
        stateMutex.withLock {
            existingMainUserTaskIds -= arrival.taskId
            val active = _interstitialState.value
            val matchesTarget = active != null &&
                active.kind == InterstitialKind.JUMP_CONFIRM &&
                (active.targetPackage == arrival.packageName ||
                    (active.targetPackage == null &&
                        arrival.packageName.isNotBlank() &&
                        arrival.packageName != active.packageName &&
                        arrival.packageName != active.initiatorPackage))
            if (matchesTarget) {
                val resolvedTarget = active.targetPackage ?: arrival.packageName
                ledger.resolve(active.id, InterstitialActor.USER, InterstitialChoice.ALLOW, now)
                _interstitialState.value = null
                maybeRecordLearnedProfile(
                    entryId = active.id,
                    interstitialPackage = active.packageName,
                    initiatorPackage = active.initiatorPackage,
                    originalTargetPackage = active.targetPackage,
                    targetLabel = active.targetLabel,
                    resolvedTargetPackage = arrival.packageName,
                )
                enqueueSpill(
                    SpillReport(
                        OpenedElsewhere(
                            packageName = arrival.packageName,
                            app = runtime.appLabel(arrival.packageName) ?: active.targetLabel,
                            pulledBack = true,
                            jumpConfirm = JumpConfirmReport(
                                initiator = active.initiatorPackage,
                                target = resolvedTarget,
                                by = InterstitialActor.USER,
                                choice = InterstitialChoice.ALLOW,
                                outcome = JumpConfirmOutcome.USER_ALLOWED,
                            ),
                        ),
                    ),
                )
                notifyResolutionWaiters()
            } else {
                // 若确认弹窗刚关闭（尚未落为 ALLOW/DENY）目标就直接开回 VD，立即补齐放行并唤醒等待者
                val unresolvedEntry = ledger.activeOrRecentForDisplay(vd, now).firstOrNull { entry ->
                    val jc = entry.interstitial as? Interstitial.JumpConfirm ?: return@firstOrNull false
                    val choice = (entry.state as? LedgerState.Resolved)?.choice
                    (entry.isOpen || choice == InterstitialChoice.UNKNOWN) &&
                        arrival.packageName.isNotBlank() &&
                        arrival.packageName != entry.packageName &&
                        arrival.packageName != jc.initiatorPackage &&
                        (jc.targetPackage == null || jc.targetPackage == arrival.packageName)
                }
                if (unresolvedEntry != null) {
                    val jc = unresolvedEntry.interstitial as Interstitial.JumpConfirm
                    val resolvedTarget = jc.targetPackage ?: arrival.packageName
                    ledger.resolve(unresolvedEntry.interstitial.id, InterstitialActor.USER, InterstitialChoice.ALLOW, now)
                    maybeRecordLearnedProfile(
                        entryId = jc.id,
                        interstitialPackage = jc.packageName,
                        initiatorPackage = jc.initiatorPackage,
                        originalTargetPackage = jc.targetPackage,
                        targetLabel = jc.targetLabel,
                        resolvedTargetPackage = arrival.packageName,
                    )
                    enqueueSpill(
                        SpillReport(
                            OpenedElsewhere(
                                packageName = arrival.packageName,
                                app = runtime.appLabel(arrival.packageName) ?: jc.targetLabel,
                                pulledBack = true,
                                jumpConfirm = JumpConfirmReport(
                                    initiator = jc.initiatorPackage,
                                    target = resolvedTarget,
                                    by = InterstitialActor.USER,
                                    choice = InterstitialChoice.ALLOW,
                                    outcome = JumpConfirmOutcome.USER_ALLOWED,
                                ),
                            ),
                        ),
                    )
                    notifyResolutionWaiters()
                }
            }
        }
    }

    private suspend fun handleMainSpill(
        arrival: CustodyArrival,
        origin: LaunchOrigin,
        spill: AttributionResult.Spill,
        vd: Int,
        now: Long,
    ) {
        val pkg = arrival.packageName
        if (vd <= Display.DEFAULT_DISPLAY || pkg.isBlank()) return
        if (_interstitialState.value?.let { it.taskId >= 0 && it.taskId == arrival.taskId && !it.onVirtualDisplay } == true) {
            return
        }

        // 如果是由 R2（来自已打开或刚解决的选择器 / 跳转确认弹窗）触发的新任务到达：
        if (spill.reason == SpillReason.INTERSTITIAL_LEDGER && spill.ledgerEntryId != null) {
            val handledAsChooserFollowup = handleFollowupAfterInterstitial(arrival, spill.ledgerEntryId!!, vd, now)
            if (handledAsChooserFollowup) return
        }

        // 抓取窗口并对主屏新出现的任务进行中间页分类（若窗口尚未渲染完毕，短等至多 ~300ms）
        val outcomeWithInvoker = classifyDisplayWindowWithSettle(
            displayId = Display.DEFAULT_DISPLAY,
            expectedPackage = pkg,
            expectedTaskId = arrival.taskId,
            componentHint = arrival.activity,
            attributedDisplayId = vd,
            origin = origin,
            nowMs = now,
            checkSecure = true,
        )

        if (outcomeWithInvoker != null) {
            val (outcome, inspected) = outcomeWithInvoker
            Log.i(TAG, "mainSpill classify task=${arrival.taskId} pkg=$pkg outcome=$outcome")
            if (outcome is InterstitialClassifier.ClassificationOutcome.Classified) {
                processClassifiedInterstitial(
                    outcome = outcome,
                    inspected = inspected,
                    displayId = Display.DEFAULT_DISPLAY,
                    taskId = arrival.taskId,
                    userId = arrival.userId,
                    attributionSpill = spill,
                    vd = vd,
                    nowMs = now,
                )
                return
            }
        } else {
            Log.i(TAG, "mainSpill classify returned null for task=${arrival.taskId} pkg=$pkg")
        }

        // 普通第三方 App / 非中间页越屏：直接按 R1/R2/R5/R6 挪回虚拟屏
        pullTaskBackToVd(
            taskId = arrival.taskId,
            pkg = pkg,
            userId = arrival.userId,
            vd = vd,
            fromJumpConfirm = null,
        )
    }

    private suspend fun tryClassifyMainWindowForArrival(
        arrival: CustodyArrival,
        origin: LaunchOrigin,
        vd: Int,
        now: Long,
        unattributed: Boolean,
        recentAgentAction: Boolean = false,
    ): Boolean {
        if (vd <= Display.DEFAULT_DISPLAY) return false
        if (_interstitialState.value?.let { it.taskId >= 0 && it.taskId == arrival.taskId && !it.onVirtualDisplay } == true) {
            return false
        }
        val activePending = _interstitialState.value
        val activeAge = activePending?.let { now - it.sinceMs } ?: Long.MAX_VALUE
        if (!recentAgentAction && (activePending == null || activeAge >= 15_000L)) {
            return false
        }
        val isSysPkg = classifier.isSystemApp(arrival.packageName)
        // 若正处于挂起的用户中间页期间（Chooser/JumpConfirm），先看是否命中 R2 补救，否则不把普通未归因切屏误抓进来；
        // 若处于近期 Agent 动作窗内，系统包按中间页核验，普通第三方应用直跳则在确认非敏感认证弹窗后直接拉回 VD。
        val pair = classifyDisplayWindowWithSettle(
            displayId = Display.DEFAULT_DISPLAY,
            expectedPackage = arrival.packageName,
            expectedTaskId = arrival.taskId,
            componentHint = arrival.activity,
            attributedDisplayId = if (unattributed) Display.DEFAULT_DISPLAY else vd,
            origin = origin,
            nowMs = now,
            checkSecure = recentAgentAction && !isSysPkg,
            requireMatchedJumpToAct = !recentAgentAction || isSysPkg,
        )

        if (pair != null) {
            val (outcome, inspected) = pair
            if (outcome is InterstitialClassifier.ClassificationOutcome.Classified) {
                when (val interstitial = outcome.interstitial) {
                    is Interstitial.JumpConfirm -> {
                        val fixedInterstitial = resolveJumpConfirmForVd(
                            jc = interstitial,
                            onSameDisplayAsVd = false,
                            vd = vd,
                            nowMs = now,
                        ) ?: return false
                        processClassifiedInterstitial(
                            outcome = outcome.copy(interstitial = fixedInterstitial),
                            inspected = inspected,
                            displayId = Display.DEFAULT_DISPLAY,
                            taskId = arrival.taskId,
                            userId = arrival.userId,
                            attributionSpill = AttributionResult.Spill(vd, SpillReason.MANAGED_ORIGIN_NO_USER_INTENT),
                            vd = vd,
                            nowMs = now,
                        )
                        return true
                    }
                    is Interstitial.Chooser -> if (recentAgentAction) {
                        processClassifiedInterstitial(
                            outcome = outcome,
                            inspected = inspected,
                            displayId = Display.DEFAULT_DISPLAY,
                            taskId = arrival.taskId,
                            userId = arrival.userId,
                            attributionSpill = AttributionResult.Spill(vd, SpillReason.MANAGED_ORIGIN_NO_USER_INTENT),
                            vd = vd,
                            nowMs = now,
                        )
                        return true
                    }
                    is Interstitial.NeedsUser -> {
                        val keepOnMainForUser = isSysPkg ||
                            interstitial.reason == "secure" ||
                            interstitial.reason == "password" ||
                            outcome.safety.hasPasswordField ||
                            outcome.safety.isAuthenticator ||
                            outcome.safety.isFlagSecure ||
                            outcome.safety.isRuntimePermission
                        if (recentAgentAction && keepOnMainForUser) {
                            processClassifiedInterstitial(
                                outcome = outcome,
                                inspected = inspected,
                                displayId = Display.DEFAULT_DISPLAY,
                                taskId = arrival.taskId,
                                userId = arrival.userId,
                                attributionSpill = AttributionResult.Spill(vd, SpillReason.MANAGED_ORIGIN_NO_USER_INTENT),
                                vd = vd,
                                nowMs = now,
                            )
                            return true
                        }
                    }
                }
            }
        }

        if (recentAgentAction && !isSysPkg && arrival.taskId >= 0) {
            pullTaskBackToVd(
                taskId = arrival.taskId,
                pkg = arrival.packageName,
                userId = arrival.userId,
                vd = vd,
                fromJumpConfirm = null,
            )
            return true
        }
        return false
    }

    private suspend fun handleFollowupAfterInterstitial(
        arrival: CustodyArrival,
        entryId: String,
        vd: Int,
        now: Long,
    ): Boolean {
        val entry = ledger.get(entryId, now) ?: return false
        val interstitial = entry.interstitial
        val pkg = arrival.packageName

        if (interstitial is Interstitial.JumpConfirm) {
            // 若原 JumpConfirm 仍记为 Open（arrival 比 vanish 早到几毫秒），此时补齐确认解决
            val actor = if ((entry.state as? LedgerState.Resolved)?.by == InterstitialActor.AGENT) {
                InterstitialActor.AGENT
            } else {
                InterstitialActor.USER
            }
            ledger.resolve(entryId, actor, InterstitialChoice.ALLOW, now)
            val hadActivePending = stateMutex.withLock {
                val cur = _interstitialState.value
                if (cur?.id == entryId) {
                    _interstitialState.value = null
                    true
                } else {
                    false
                }
            }
            // 先检查派生落地的应用是否要用户亲手处理（密码/认证器/防截屏），否则按 R2 挪回 VD
            val classifiedLanding = classifyDisplayWindowWithSettle(
                displayId = Display.DEFAULT_DISPLAY,
                expectedPackage = pkg,
                expectedTaskId = arrival.taskId,
                componentHint = arrival.activity,
                attributedDisplayId = vd,
                origin = LaunchOrigin(uid = arrival.originUid, pkg = arrival.originPackage),
                nowMs = now,
                checkSecure = true,
            )
            if (classifiedLanding != null &&
                classifiedLanding.first is InterstitialClassifier.ClassificationOutcome.Classified &&
                (classifiedLanding.first as InterstitialClassifier.ClassificationOutcome.Classified).interstitial is Interstitial.NeedsUser
            ) {
                stateMutex.withLock {
                    if (hadActivePending) {
                        waitGateNeedsUserResult = WaitResult.NeedsUser(
                            taskId = arrival.taskId,
                            packageName = pkg,
                            app = runtime.appLabel(pkg),
                            userId = arrival.userId,
                        )
                    } else {
                        enqueueSpill(
                            SpillReport(
                                OpenedElsewhere(
                                    packageName = pkg,
                                    app = runtime.appLabel(pkg),
                                    pulledBack = false,
                                    inProfile = inProfileOf(pkg, arrival.userId),
                                    needsUser = true,
                                    jumpConfirm = JumpConfirmReport(
                                        initiator = interstitial.initiatorPackage,
                                        target = pkg,
                                        by = actor,
                                        choice = InterstitialChoice.ALLOW,
                                        outcome = if (actor == InterstitialActor.AGENT) JumpConfirmOutcome.AUTO_ALLOWED else JumpConfirmOutcome.USER_ALLOWED,
                                    ),
                                ),
                            ),
                        )
                    }
                }
                if (hadActivePending) notifyResolutionWaiters()
                return true
            }

            if (actor == InterstitialActor.USER) {
                maybeRecordLearnedProfile(
                    entryId = entryId,
                    interstitialPackage = interstitial.packageName,
                    initiatorPackage = interstitial.initiatorPackage,
                    originalTargetPackage = interstitial.targetPackage,
                    targetLabel = interstitial.targetLabel,
                    resolvedTargetPackage = pkg,
                )
            }
            pullTaskBackToVd(
                taskId = arrival.taskId,
                pkg = pkg,
                userId = arrival.userId,
                vd = vd,
                fromJumpConfirm = JumpConfirmReport(
                    initiator = interstitial.initiatorPackage,
                    target = interstitial.targetPackage ?: pkg,
                    by = actor,
                    choice = InterstitialChoice.ALLOW,
                    outcome = if (actor == InterstitialActor.AGENT) {
                        JumpConfirmOutcome.AUTO_ALLOWED
                    } else {
                        JumpConfirmOutcome.USER_ALLOWED
                    },
                ),
            )
            notifyResolutionWaiters()
            return true
        }

        if (interstitial is Interstitial.Chooser) {
            ledger.resolve(entryId, InterstitialActor.USER, InterstitialChoice.ALLOW, now)
            val activeChooser = stateMutex.withLock {
                val cur = _interstitialState.value
                val same = cur?.id == entryId
                if (same) _interstitialState.value = null
                same
            }
            val classifiedLanding = classifyDisplayWindowWithSettle(
                displayId = Display.DEFAULT_DISPLAY,
                expectedPackage = pkg,
                expectedTaskId = arrival.taskId,
                componentHint = arrival.activity,
                attributedDisplayId = vd,
                origin = LaunchOrigin(uid = arrival.originUid, pkg = arrival.originPackage),
                nowMs = now,
                checkSecure = true,
            )
            if (classifiedLanding != null &&
                classifiedLanding.first is InterstitialClassifier.ClassificationOutcome.Classified &&
                (classifiedLanding.first as InterstitialClassifier.ClassificationOutcome.Classified).interstitial is Interstitial.NeedsUser
            ) {
                stateMutex.withLock {
                    waitGateNeedsUserResult = WaitResult.NeedsUser(
                        taskId = arrival.taskId,
                        packageName = pkg,
                        app = runtime.appLabel(pkg),
                        userId = arrival.userId,
                    )
                }
                notifyResolutionWaiters()
                return true
            }
            pullTaskBackToVd(arrival.taskId, pkg, arrival.userId, vd, fromJumpConfirm = null)
            notifyResolutionWaiters()
            return true
        }

        return false
    }

    /** 处理 `windowChanges()` 触发的主屏或虚拟屏窗口扫描（涵盖无 `taskId` 的悬浮确认窗及确认框消失监测）。 */
    private suspend fun handleWindowScan(displayId: Int, nowMs: Long, componentHint: String? = null) {
        if (isHandedOver()) return
        val vd = runtime.managedDisplayId()
        val windows = runtime.inspectDisplayWindows(
            displayId = displayId,
            componentHint = componentHint,
            checkSecureOnUnreadable = false,
        )

        // 1. 先检查当前正在等待用户处理的挂起中间页是否已经消失
        val active = _interstitialState.value
        if (active != null && active.displayId == displayId) {
            val graceActive = movedToMainGrace?.let { (id, until) -> id == active.id && nowMs < until } == true
            val stillPresent = windows.any { w ->
                w.inspection.packageName == active.packageName &&
                    (active.taskId < 0 || w.inspection.taskId < 0 || w.inspection.taskId == active.taskId)
            }
            if (!stillPresent && !graceActive) {
                Log.i(TAG, "windowScan($displayId) active=${active.packageName}/${active.taskId} not present in ${windows.map { "${it.inspection.packageName}/${it.inspection.taskId}/${it.inspection.windowType}" }}")
                resolveDismissedInterstitial(active, nowMs)
            }
        }

        if (vd <= Display.DEFAULT_DISPLAY) return
        if (displayId != Display.DEFAULT_DISPLAY && displayId != vd) return

        val hasPendingAfterDismissCheck = _interstitialState.value != null
        val recentUserIntent = stateMutex.withLock {
            pruneUserIntents(nowMs)
            userIntents.any { nowMs - it.at in 0L..AttributionContext.DEFAULT_USER_INTENT_WINDOW_MS }
        }
        val recentLedger = ledger.activeOrRecent(nowMs)

        // 2. 扫描悬浮窗（TYPE_SYSTEM）、虚拟屏上的确认窗，以及主屏系统跳转弹窗（覆盖重启复用 task / 延迟渲染场景）
        for (w in windows) {
            val pkg = w.inspection.packageName
            if (pkg.isBlank() || pkg == selfPackage || pkg in launcherPackages) continue
            val winTaskId = w.inspection.taskId
            val alreadyHandled = recentLedger.any { entry ->
                when {
                    entry.isOpen && entry.packageName == pkg -> true
                    winTaskId >= 0 && entry.interstitial.taskId == winTaskId && entry.packageName == pkg -> {
                        val resolved = entry.state as? LedgerState.Resolved ?: return@any false
                        val lastAction = lastAgentActionAtMs
                        (nowMs - resolved.at in 0L..RECENT_RESOLVED_SCAN_SUPPRESS_MS) ||
                            lastAction == null ||
                            resolved.at >= lastAction
                    }
                    else -> false
                }
            }
            if (alreadyHandled) continue

            val isOverlay = w.inspection.windowType == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
            if (displayId == Display.DEFAULT_DISPLAY && !isOverlay) {
                if (hasPendingAfterDismissCheck || recentUserIntent) continue
                if (winTaskId >= 0 && winTaskId in existingMainUserTaskIds) continue
                if (!classifier.isSystemApp(pkg)) continue
            }

            val ctx = buildClassificationContext(
                window = w.inspection,
                attributedDisplayId = vd,
                origin = if (displayId == vd) LaunchOrigin(pkg = pkg, confidence = OriginConfidence.HIGH) else LaunchOrigin.UNKNOWN,
                nowMs = nowMs,
            )
            val outcome = classifier.classify(w.inspection, ctx)
            if (outcome is InterstitialClassifier.ClassificationOutcome.Classified &&
                outcome.interstitial is Interstitial.JumpConfirm
            ) {
                val jcCandidate = outcome.interstitial as Interstitial.JumpConfirm
                val actualInterstitial = resolveJumpConfirmForVd(
                    jc = jcCandidate,
                    onSameDisplayAsVd = displayId == vd,
                    vd = vd,
                    nowMs = nowMs,
                )
                if (actualInterstitial == null) {
                    Log.i(
                        TAG,
                        "windowScan($displayId) resolveJumpConfirmForVd null for $pkg " +
                            "init=${jcCandidate.initiatorPackage} target=${jcCandidate.targetPackage} " +
                            "vdPkgs=$vdPackages lastAct=$lastAgentActionAtMs now=$nowMs",
                    )
                    continue
                }
                processClassifiedInterstitial(
                    outcome = outcome.copy(interstitial = actualInterstitial),
                    inspected = w,
                    displayId = displayId,
                    taskId = winTaskId,
                    userId = if (winTaskId >= 0) runtime.userOfTask(winTaskId) else 0,
                    attributionSpill = AttributionResult.Spill(vd, SpillReason.MANAGED_ORIGIN_NO_USER_INTENT),
                    vd = vd,
                    nowMs = nowMs,
                )
                return
            }
        }
    }

    private suspend fun handleVanished(taskId: Int, lastDisplayId: Int, atMs: Long) {
        stateMutex.withLock {
            existingMainUserTaskIds -= taskId
            if (currentMainTopTaskId == taskId) {
                currentMainTopTaskId = runtime.topTaskId(Display.DEFAULT_DISPLAY)
            }
        }
        val active = _interstitialState.value
        if (active != null && active.taskId == taskId && active.displayId == lastDisplayId) {
            resolveDismissedInterstitial(active, atMs)
        }
    }

    private suspend fun resolveDismissedInterstitial(active: PendingInterstitial, nowMs: Long) {
        val (recentNeg, recentPos) = stateMutex.withLock {
            recentNegativeTapAt[active.displayId] to recentPositiveTapAt[active.displayId]
        }
        val explicitDeny = recentNeg != null &&
            recentNeg.first == active.packageName &&
            nowMs - recentNeg.second in 0L..2_500L
        val explicitAllow = !explicitDeny && recentPos != null &&
            recentPos.first == active.packageName &&
            nowMs - recentPos.second in 0L..2_500L

        val initialChoice = if (explicitDeny) InterstitialChoice.DENY else InterstitialChoice.UNKNOWN
        if (movedToMainGrace?.first == active.id) {
            movedToMainGrace = null
        }
        stateMutex.withLock {
            ledger.resolve(active.id, InterstitialActor.USER, initialChoice, nowMs)
            _interstitialState.value = null
            if (active.kind == InterstitialKind.JUMP_CONFIRM && explicitDeny) {
                enqueueSpill(
                    SpillReport(
                        OpenedElsewhere(
                            packageName = active.targetPackage ?: active.packageName,
                            app = active.targetLabel ?: active.appLabel,
                            pulledBack = false,
                            closed = true,
                            jumpConfirm = JumpConfirmReport(
                                initiator = active.initiatorPackage,
                                target = active.targetPackage,
                                by = InterstitialActor.USER,
                                choice = InterstitialChoice.DENY,
                                outcome = JumpConfirmOutcome.DENIED,
                            ),
                        ),
                    ),
                )
            } else if (active.kind == InterstitialKind.CHOOSER) {
                enqueueSpill(
                    SpillReport(
                        OpenedElsewhere(
                            packageName = active.packageName,
                            app = active.appLabel,
                            pulledBack = false,
                            picker = true,
                            closed = true,
                        ),
                    ),
                )
            }
        }

        if (active.kind == InterstitialKind.JUMP_CONFIRM && explicitDeny) {
            recordJumpConfirmEvent(
                initiator = active.initiatorPackage,
                target = active.targetPackage,
                component = null,
                result = "denied",
                degraded = false,
                reason = null,
                by = "user",
                displayId = active.displayId,
            )
            notifyResolutionWaiters()
        } else if (active.kind == InterstitialKind.JUMP_CONFIRM && !explicitDeny) {
            // 若用户未触发明显拒绝且稍后没有派生新任务进来，记录拒绝提示，避免模型无限等待
            scope.launch {
                delay(DENY_SETTLE_WAIT_MS)
                val entry = ledger.get(active.id, clock())
                val resolvedChoice = (entry?.state as? LedgerState.Resolved)?.choice
                if (resolvedChoice == InterstitialChoice.UNKNOWN) {
                    val vd = runtime.managedDisplayId()
                    val vdTopPkg = if (vd > Display.DEFAULT_DISPLAY) runtime.packageOfTask(runtime.topTaskId(vd)) else null
                    val targetOnVd = vdTopPkg != null && (
                        (active.targetPackage != null && vdTopPkg == active.targetPackage) ||
                            (active.targetPackage == null && vdTopPkg != active.initiatorPackage && vdTopPkg != active.packageName)
                        )
                    if (targetOnVd) {
                        val resolvedTarget = active.targetPackage ?: vdTopPkg
                        ledger.resolve(active.id, InterstitialActor.USER, InterstitialChoice.ALLOW, clock())
                        maybeRecordLearnedProfile(
                            entryId = active.id,
                            interstitialPackage = active.packageName,
                            initiatorPackage = active.initiatorPackage,
                            originalTargetPackage = active.targetPackage,
                            targetLabel = active.targetLabel,
                            resolvedTargetPackage = vdTopPkg,
                        )
                        stateMutex.withLock {
                            enqueueSpill(
                                SpillReport(
                                    OpenedElsewhere(
                                        packageName = resolvedTarget ?: active.packageName,
                                        app = runtime.appLabel(resolvedTarget ?: active.packageName) ?: active.targetLabel ?: active.appLabel,
                                        pulledBack = true,
                                        jumpConfirm = JumpConfirmReport(
                                            initiator = active.initiatorPackage,
                                            target = resolvedTarget,
                                            by = InterstitialActor.USER,
                                            choice = InterstitialChoice.ALLOW,
                                            outcome = JumpConfirmOutcome.USER_ALLOWED,
                                        ),
                                    ),
                                ),
                            )
                        }
                    } else if (explicitAllow) {
                        // 用户已明确点击正向按钮（如“打开”），目标 App 可能正在冷启动：保留 ALLOW 以便 R2 继续生效
                        ledger.resolve(active.id, InterstitialActor.USER, InterstitialChoice.ALLOW, clock())
                    } else {
                        ledger.resolve(active.id, InterstitialActor.USER, InterstitialChoice.DENY, clock())
                        recordJumpConfirmEvent(
                            initiator = active.initiatorPackage,
                            target = active.targetPackage,
                            component = null,
                            result = "denied",
                            degraded = false,
                            reason = "dismissed_without_target",
                            by = "user",
                            displayId = active.displayId,
                        )
                        stateMutex.withLock {
                            enqueueSpill(
                                SpillReport(
                                    OpenedElsewhere(
                                        packageName = active.targetPackage ?: active.packageName,
                                        app = active.targetLabel ?: active.appLabel,
                                        pulledBack = false,
                                        closed = true,
                                        jumpConfirm = JumpConfirmReport(
                                            initiator = active.initiatorPackage,
                                            target = active.targetPackage,
                                            by = InterstitialActor.USER,
                                            choice = InterstitialChoice.DENY,
                                            outcome = JumpConfirmOutcome.DENIED,
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                    notifyResolutionWaiters()
                }
            }
        } else {
            notifyResolutionWaiters()
        }
    }

    private suspend fun processClassifiedInterstitial(
        outcome: InterstitialClassifier.ClassificationOutcome.Classified,
        inspected: InspectedWindow,
        displayId: Int,
        taskId: Int,
        userId: Int,
        attributionSpill: AttributionResult.Spill,
        vd: Int,
        nowMs: Long,
    ) {
        val candidate = outcome.interstitial
        if (candidate is Interstitial.JumpConfirm || candidate is Interstitial.Chooser) {
            val active = _interstitialState.value
            val duplicatePending = active != null &&
                active.packageName == candidate.packageName &&
                (taskId < 0 || active.taskId < 0 || active.taskId == taskId)
            val duplicateOpen = ledger.activeOrRecent(nowMs).any { entry ->
                entry.isOpen &&
                    entry.packageName == candidate.packageName &&
                    (taskId < 0 || entry.interstitial.taskId < 0 || entry.interstitial.taskId == taskId)
            }
            if (duplicatePending || duplicateOpen) {
                Log.i(TAG, "processClassified skip dupPending=$duplicatePending dupOpen=$duplicateOpen pkg=${candidate.packageName} task=$taskId")
                return
            }
        }

        when (val interstitial = candidate) {
            is Interstitial.NeedsUser -> {
                ledger.open(interstitial.copy(displayId = attributionSpill.displayId), nowMs)
                stateMutex.withLock {
                    enqueueSpill(
                        SpillReport(
                            OpenedElsewhere(
                                packageName = interstitial.packageName,
                                app = runtime.appLabel(interstitial.packageName),
                                pulledBack = false,
                                inProfile = inProfileOf(interstitial.packageName, userId),
                                needsUser = true,
                            ),
                        ),
                    )
                }
            }

            is Interstitial.Chooser -> {
                val entryInterstitial = interstitial.copy(displayId = attributionSpill.displayId)
                ledger.open(entryInterstitial, nowMs)
                val label = outcome.launchChoice?.let { null } ?: runtime.appLabel(interstitial.packageName)
                val targetLabel = outcome.launchChoice?.let { expectedTargetPackage?.let(runtime::appLabel) } ?: label
                stateMutex.withLock {
                    _interstitialState.value = PendingInterstitial(
                        id = entryInterstitial.id,
                        kind = InterstitialKind.CHOOSER,
                        displayId = displayId,
                        taskId = taskId,
                        packageName = entryInterstitial.packageName,
                        appLabel = targetLabel,
                        initiatorPackage = entryInterstitial.initiatorPackage,
                        targetPackage = expectedTargetPackage,
                        targetLabel = targetLabel,
                        onVirtualDisplay = displayId == vd,
                        sinceMs = nowMs,
                        previousMainTopTaskId = previousMainTopTaskId,
                    )
                    enqueueSpill(
                        SpillReport(
                            OpenedElsewhere(
                                packageName = entryInterstitial.packageName,
                                app = targetLabel,
                                pulledBack = false,
                                picker = true,
                            ),
                            outcome.launchChoice,
                        ),
                    )
                }
            }

            is Interstitial.JumpConfirm -> {
                handleJumpConfirmInterstitial(
                    outcome = outcome,
                    interstitial = interstitial.copy(displayId = attributionSpill.displayId),
                    inspected = inspected,
                    displayId = displayId,
                    taskId = taskId,
                    attributionDisplayId = attributionSpill.displayId,
                    vd = vd,
                    nowMs = nowMs,
                )
            }
        }
    }

    private suspend fun handleJumpConfirmInterstitial(
        outcome: InterstitialClassifier.ClassificationOutcome.Classified,
        interstitial: Interstitial.JumpConfirm,
        inspected: InspectedWindow,
        displayId: Int,
        taskId: Int,
        attributionDisplayId: Int,
        vd: Int,
        nowMs: Long,
    ) {
        val routeKey = JumpRouteKey(
            taskId = taskId.takeIf { it >= 0 } ?: (runtime.topTaskId(vd)),
            initiatorPackage = interstitial.initiatorPackage ?: interstitial.packageName,
            targetPackage = interstitial.targetPackage ?: "",
        )
        val entry = ledger.open(interstitial, nowMs)
        val hosting = hostingEnabled()
        val movedToVd = hosting &&
            displayId == Display.DEFAULT_DISPLAY &&
            taskId >= 0 &&
            vd > Display.DEFAULT_DISPLAY &&
            runtime.moveTask(taskId, vd)
        if (movedToVd) {
            stateMutex.withLock {
                existingMainUserTaskIds -= taskId
                if (currentMainTopTaskId == taskId) {
                    currentMainTopTaskId = runtime.topTaskId(Display.DEFAULT_DISPLAY)
                }
            }
        }
        val activeDisplayId = if (movedToVd) vd else displayId
        val userTouched = userActedEntries.contains(entry.interstitial.id)
        val policyRequest = JumpConfirmRequest(
            hostingEnabled = hosting,
            attributedDisplayId = attributionDisplayId,
            lastAgentActionAtMs = lastAgentActionAtMs,
            nowMs = nowMs,
            initiatorPackage = interstitial.initiatorPackage,
            targetPackage = interstitial.targetPackage,
            selfPackage = selfPackage,
            appAccess = appAccess.current(),
            buttons = outcome.buttons,
            checkboxes = outcome.checkboxes,
            alwaysAllowCheckboxChecked = outcome.alwaysAllowCheckboxChecked,
            windowSafety = outcome.safety,
            userActedOnInterstitial = userTouched,
            autoClickCountForRoute = rateLimiter.count(routeKey),
            deviceCircuitBroken = isDeviceCircuitBroken(),
            interstitialPackage = interstitial.packageName,
            componentName = interstitial.componentName,
            dialogTexts = outcome.dialogTexts,
        )

        val decision = JumpConfirmPolicy.evaluate(policyRequest)
        Log.i(TAG, "jumpConfirm decision=$decision pkg=${interstitial.packageName} target=${interstitial.targetPackage} task=$taskId activeDisp=$activeDisplayId movedToVd=$movedToVd allowUser=$allowUserInteraction")
        if (decision is JumpConfirmDecision.Allow) {
            if (rateLimiter.tryAcquire(routeKey)) {
                val okClick = performPositiveClickOnCustodyDialog(
                    inspected = inspected,
                    displayId = activeDisplayId,
                    taskId = taskId,
                    interstitial = interstitial,
                    button = decision.buttonToClick,
                )
                if (okClick) {
                    val vanished = awaitDialogDismissed(
                        displayId = activeDisplayId,
                        packageName = interstitial.packageName,
                        taskId = taskId,
                        timeoutMs = AUTO_CLICK_DISAPPEAR_TIMEOUT_MS,
                    )
                    if (vanished) {
                        ledger.resolve(interstitial.id, InterstitialActor.AGENT, InterstitialChoice.ALLOW, clock())
                        recordJumpConfirmEvent(
                            initiator = interstitial.initiatorPackage,
                            target = interstitial.targetPackage,
                            component = interstitial.componentName,
                            result = "auto_allowed",
                            degraded = false,
                            reason = null,
                            by = "agent",
                            displayId = activeDisplayId,
                        )
                        val pulled = activeDisplayId == vd
                        stateMutex.withLock {
                            enqueueSpill(
                                SpillReport(
                                    OpenedElsewhere(
                                        packageName = interstitial.targetPackage ?: interstitial.packageName,
                                        app = interstitial.targetLabel ?: runtime.appLabel(interstitial.targetPackage ?: interstitial.packageName),
                                        pulledBack = pulled,
                                        jumpConfirm = JumpConfirmReport(
                                            initiator = interstitial.initiatorPackage,
                                            target = interstitial.targetPackage,
                                            by = InterstitialActor.AGENT,
                                            choice = InterstitialChoice.ALLOW,
                                            outcome = JumpConfirmOutcome.AUTO_ALLOWED,
                                        ),
                                    ),
                                ),
                            )
                        }
                        return
                    }
                    // 1s 未消失：本机拦截或无效点击，立即熔断并降级为手动流程（需要人时移回主屏）
                    onCircuitBreak()
                    recordJumpConfirmEvent(
                        initiator = interstitial.initiatorPackage,
                        target = interstitial.targetPackage,
                        component = interstitial.componentName,
                        result = "degraded",
                        degraded = true,
                        reason = "not_dismissed_1s",
                        by = "agent",
                        displayId = activeDisplayId,
                    )
                    parkJumpConfirmForUserOrFail(
                        interstitial = interstitial,
                        displayId = activeDisplayId,
                        taskId = taskId,
                        vd = vd,
                        degradeReason = JumpConfirmDegradeReason.DEVICE_CIRCUIT_BROKEN,
                        nowMs = clock(),
                        moveBackToMainForUser = true,
                    )
                    return
                } else {
                    // ACTION_CLICK 直接失败：熔断并降级（需要人时移回主屏）
                    onCircuitBreak()
                    recordJumpConfirmEvent(
                        initiator = interstitial.initiatorPackage,
                        target = interstitial.targetPackage,
                        component = interstitial.componentName,
                        result = "degraded",
                        degraded = true,
                        reason = "action_click_failed",
                        by = "agent",
                        displayId = activeDisplayId,
                    )
                    parkJumpConfirmForUserOrFail(
                        interstitial = interstitial,
                        displayId = activeDisplayId,
                        taskId = taskId,
                        vd = vd,
                        degradeReason = JumpConfirmDegradeReason.DEVICE_CIRCUIT_BROKEN,
                        nowMs = clock(),
                        moveBackToMainForUser = true,
                    )
                    return
                }
            }
        }

        val degradeReason = (decision as? JumpConfirmDecision.Degrade)?.reason
            ?: JumpConfirmDegradeReason.RATE_LIMIT_EXCEEDED
        if (degradeReason != JumpConfirmDegradeReason.HOSTING_DISABLED) {
            recordJumpConfirmEvent(
                initiator = interstitial.initiatorPackage,
                target = interstitial.targetPackage,
                component = interstitial.componentName,
                result = "degraded",
                degraded = true,
                reason = degradeReason.name.lowercase(),
                by = null,
                displayId = activeDisplayId,
            )
        }
        parkJumpConfirmForUserOrFail(
            interstitial = interstitial,
            displayId = activeDisplayId,
            taskId = taskId,
            vd = vd,
            degradeReason = degradeReason,
            nowMs = nowMs,
            moveBackToMainForUser = movedToVd || (hosting && degradeReason != JumpConfirmDegradeReason.HOSTING_DISABLED),
        )
    }

    private suspend fun performPositiveClickOnCustodyDialog(
        inspected: InspectedWindow,
        displayId: Int,
        taskId: Int,
        interstitial: Interstitial.JumpConfirm,
        button: ConfirmButton,
    ): Boolean {
        if (inspected.performPositiveClick(button)) {
            return true
        }
        if (displayId == Display.DEFAULT_DISPLAY || displayId != runtime.managedDisplayId()) {
            return false
        }
        for (attempt in 0 until WINDOW_SETTLE_ATTEMPTS) {
            delay(WINDOW_SETTLE_STEP_MS)
            val windows = runtime.inspectDisplayWindows(
                displayId = displayId,
                componentHint = interstitial.componentName,
                checkSecureOnUnreadable = false,
            )
            val matched = windows.firstOrNull { w ->
                w.inspection.packageName == interstitial.packageName &&
                    (taskId < 0 || w.inspection.taskId < 0 || w.inspection.taskId == taskId)
            }
            if (matched != null && matched.performPositiveClick(button)) {
                return true
            }
        }
        return false
    }

    private suspend fun parkJumpConfirmForUserOrFail(
        interstitial: Interstitial.JumpConfirm,
        displayId: Int,
        taskId: Int,
        vd: Int,
        degradeReason: JumpConfirmDegradeReason?,
        nowMs: Long,
        moveBackToMainForUser: Boolean = false,
    ) {
        val targetLabel = interstitial.targetLabel
            ?: interstitial.targetPackage?.let(runtime::appLabel)
            ?: runtime.appLabel(interstitial.packageName)

        if (!allowUserInteraction) {
            ledger.resolve(interstitial.id, InterstitialActor.AGENT, InterstitialChoice.UNKNOWN, nowMs)
            recordJumpConfirmEvent(
                initiator = interstitial.initiatorPackage,
                target = interstitial.targetPackage,
                component = interstitial.componentName,
                result = "unavailable",
                degraded = true,
                reason = degradeReason?.name?.lowercase() ?: "unattended",
                by = null,
                displayId = displayId,
            )
            stateMutex.withLock {
                enqueueSpill(
                    SpillReport(
                        OpenedElsewhere(
                            packageName = interstitial.targetPackage ?: interstitial.packageName,
                            app = targetLabel,
                            pulledBack = false,
                            needsUser = false,
                            jumpConfirm = JumpConfirmReport(
                                initiator = interstitial.initiatorPackage,
                                target = interstitial.targetPackage,
                                by = null,
                                choice = InterstitialChoice.UNKNOWN,
                                outcome = JumpConfirmOutcome.UNAVAILABLE,
                                degradeReason = degradeReason,
                            ),
                        ),
                    ),
                )
            }
            return
        }

        val movedToMain = moveBackToMainForUser &&
            displayId != Display.DEFAULT_DISPLAY &&
            taskId >= 0 &&
            runtime.moveTask(taskId, Display.DEFAULT_DISPLAY)
        val effectiveDisplayId = if (movedToMain) Display.DEFAULT_DISPLAY else displayId
        val parkAtMs = if (movedToMain) clock() else nowMs
        if (movedToMain) {
            lastAgentActionAtMs = parkAtMs
            movedToMainGrace = interstitial.id to (parkAtMs + MAIN_MOVE_SETTLE_GRACE_MS)
        }
        val onVd = effectiveDisplayId != Display.DEFAULT_DISPLAY
        val ledgerDisplayId = if (vd > Display.DEFAULT_DISPLAY) vd else interstitial.displayId
        Log.i(TAG, "parkJumpConfirm setting state disp=$effectiveDisplayId movedToMain=$movedToMain task=$taskId pkg=${interstitial.packageName} reason=$degradeReason")
        stateMutex.withLock {
            if (ledgerDisplayId != interstitial.displayId) {
                ledger.open(interstitial.copy(displayId = ledgerDisplayId), parkAtMs)
            }
            _interstitialState.value = PendingInterstitial(
                id = interstitial.id,
                kind = InterstitialKind.JUMP_CONFIRM,
                displayId = effectiveDisplayId,
                taskId = taskId,
                packageName = interstitial.packageName,
                appLabel = runtime.appLabel(interstitial.packageName),
                initiatorPackage = interstitial.initiatorPackage,
                targetPackage = interstitial.targetPackage,
                targetLabel = targetLabel,
                onVirtualDisplay = onVd,
                degradeReason = degradeReason,
                sinceMs = parkAtMs,
                previousMainTopTaskId = previousMainTopTaskId,
            )
            enqueueSpill(
                SpillReport(
                    OpenedElsewhere(
                        packageName = interstitial.targetPackage ?: interstitial.packageName,
                        app = targetLabel,
                        pulledBack = false,
                        needsUser = false,
                        jumpConfirm = JumpConfirmReport(
                            initiator = interstitial.initiatorPackage,
                            target = interstitial.targetPackage,
                            by = null,
                            choice = InterstitialChoice.UNKNOWN,
                            outcome = JumpConfirmOutcome.WAITING_USER,
                            degradeReason = degradeReason,
                        ),
                    ),
                ),
            )
        }
    }

    private suspend fun awaitDialogDismissed(
        displayId: Int,
        packageName: String,
        taskId: Int,
        timeoutMs: Long,
    ): Boolean {
        val deadline = clock() + timeoutMs
        while (clock() < deadline) {
            delay(DISMISS_POLL_INTERVAL_MS)
            val activeWindows = runtime.inspectDisplayWindows(displayId)
            val stillOnActive = activeWindows.any { w ->
                w.inspection.packageName == packageName &&
                    (taskId < 0 || w.inspection.taskId < 0 || w.inspection.taskId == taskId)
            }
            val stillOnDefault = if (!stillOnActive && displayId != Display.DEFAULT_DISPLAY && taskId >= 0) {
                runtime.inspectDisplayWindows(Display.DEFAULT_DISPLAY).any { w ->
                    w.inspection.packageName == packageName && w.inspection.taskId == taskId
                }
            } else {
                false
            }
            if (!stillOnActive && !stillOnDefault) return true
        }
        return false
    }

    private suspend fun classifyDisplayWindowWithSettle(
        displayId: Int,
        expectedPackage: String,
        expectedTaskId: Int,
        componentHint: String?,
        attributedDisplayId: Int,
        origin: LaunchOrigin,
        nowMs: Long,
        checkSecure: Boolean,
        requireMatchedJumpToAct: Boolean = false,
    ): Pair<InterstitialClassifier.ClassificationOutcome, InspectedWindow>? {
        val isSysPkg = classifier.isSystemApp(expectedPackage)
        for (attempt in 0 until WINDOW_SETTLE_ATTEMPTS) {
            val isLastAttempt = attempt == WINDOW_SETTLE_ATTEMPTS - 1
            val windows = runtime.inspectDisplayWindows(
                displayId = displayId,
                componentHint = componentHint,
                checkSecureOnUnreadable = checkSecure && isLastAttempt,
            )
            val matched = windows.firstOrNull { w ->
                w.inspection.packageName == expectedPackage &&
                    (expectedTaskId < 0 || w.inspection.taskId < 0 || w.inspection.taskId == expectedTaskId)
            } ?: if (attempt == 0 && windows.isEmpty()) null else windows.firstOrNull { w ->
                w.inspection.packageName == expectedPackage
            }

            if (matched == null &&
                expectedTaskId >= 0 &&
                runtime.displayOfTask(expectedTaskId) != displayId &&
                !runtime.visibleOnMainScreen(expectedPackage, 0)
            ) {
                return null
            }

            if (!isSysPkg &&
                matched != null &&
                matched.inspection.isExcludeFromRecents &&
                !matched.inspection.hasPasswordField &&
                !matched.inspection.isAuthenticator
            ) {
                return InterstitialClassifier.ClassificationOutcome.None to matched
            }

            if (matched != null && (matched.inspection.readable || isLastAttempt)) {
                val ctx = buildClassificationContext(
                    window = matched.inspection,
                    attributedDisplayId = attributedDisplayId,
                    origin = origin,
                    nowMs = nowMs,
                )
                val outcome = classifier.classify(matched.inspection, ctx)
                val transientDialog = outcome is InterstitialClassifier.ClassificationOutcome.Classified &&
                    (outcome.interstitial as? Interstitial.NeedsUser)?.reason == "dialog"
                if (outcome is InterstitialClassifier.ClassificationOutcome.Classified && (!transientDialog || isLastAttempt)) {
                    return outcome to matched
                }
                if (outcome !is InterstitialClassifier.ClassificationOutcome.Classified) {
                    if (requireMatchedJumpToAct) return null
                    // 系统选择器或系统包刚起窗时第一帧可能尚未渲染出列表或文案，留一次 settle 机会
                    if (!isSysPkg || matched.inspection.texts.isNotEmpty() || isLastAttempt) {
                        return outcome to matched
                    }
                }
            }
            if (!isLastAttempt) {
                delay(WINDOW_SETTLE_STEP_MS)
            }
        }
        return null
    }

    private suspend fun pullTaskBackToVd(
        taskId: Int,
        pkg: String,
        userId: Int,
        vd: Int,
        fromJumpConfirm: JumpConfirmReport?,
    ) {
        if (taskId < 0 || vd <= Display.DEFAULT_DISPLAY) return
        appAccess.current().denial(pkg)?.let { denied ->
            pendingAccessDenial = denied
            return
        }
        val initialDisplay = runtime.displayOfTask(taskId)
        var effectiveTaskId = taskId
        var pulled = when (initialDisplay) {
            vd -> true
            Display.DEFAULT_DISPLAY -> runtime.moveTask(taskId, vd)
            else -> false
        }
        if (!pulled) {
            val mainTopTask = runtime.topTaskId(Display.DEFAULT_DISPLAY)
            val canPullMainTop = mainTopTask >= 0 &&
                mainTopTask != taskId &&
                runtime.packageOfTask(mainTopTask) == pkg &&
                stateMutex.withLock { mainTopTask !in existingMainUserTaskIds }
            if (canPullMainTop && runtime.moveTask(mainTopTask, vd)) {
                effectiveTaskId = mainTopTask
                pulled = true
            }
        }
        if (!pulled) {
            val vdTopTask = runtime.topTaskId(vd)
            val targetAlreadyOnVd = (vdTopTask >= 0 && runtime.packageOfTask(vdTopTask) == pkg) ||
                runtime.displayOfTask(taskId) == vd
            if (targetAlreadyOnVd && !runtime.visibleOnMainScreen(pkg, userId)) {
                effectiveTaskId = if (vdTopTask >= 0 && runtime.packageOfTask(vdTopTask) == pkg) vdTopTask else taskId
                pulled = true
            }
        }
        if (pulled) {
            onTargetObserved(pkg, effectiveTaskId)
            onTaskPulledBack(effectiveTaskId, pkg, userId, vd)
            stateMutex.withLock {
                existingMainUserTaskIds -= taskId
                existingMainUserTaskIds -= effectiveTaskId
                if (currentMainTopTaskId == taskId || currentMainTopTaskId == effectiveTaskId) {
                    currentMainTopTaskId = runtime.topTaskId(Display.DEFAULT_DISPLAY)
                }
                enqueueSpill(
                    SpillReport(
                        OpenedElsewhere(
                            packageName = pkg,
                            app = runtime.appLabel(pkg),
                            pulledBack = true,
                            inProfile = inProfileOf(pkg, userId),
                            jumpConfirm = fromJumpConfirm,
                        ),
                    ),
                )
            }
            return
        }
        if (fromJumpConfirm == null &&
            runtime.displayOfTask(taskId) != Display.DEFAULT_DISPLAY &&
            !runtime.visibleOnMainScreen(pkg, userId)
        ) {
            stateMutex.withLock {
                existingMainUserTaskIds -= taskId
            }
            return
        }
        stateMutex.withLock {
            enqueueSpill(
                SpillReport(
                    OpenedElsewhere(
                        packageName = pkg,
                        app = runtime.appLabel(pkg),
                        pulledBack = false,
                        inProfile = inProfileOf(pkg, userId),
                        jumpConfirm = fromJumpConfirm,
                    ),
                ),
            )
        }
    }

    private fun resolveOrigin(arrival: CustodyArrival): LaunchOrigin {
        if (arrival.origin.isKnown) {
            return LaunchOrigin(
                uid = arrival.originUid,
                pkg = arrival.originPackage,
                confidence = OriginConfidence.fromLevel(arrival.originConfidence),
            )
        }
        return runtime.launchOrigin(arrival.taskId)
    }

    private suspend fun buildAttributionContext(vd: Int, nowMs: Long): AttributionContext = stateMutex.withLock {
        pruneUserIntents(nowMs)
        val managedMap = if (vd > Display.DEFAULT_DISPLAY) {
            mapOf(vd to managedUidsByDisplay[vd].orEmpty().toSet())
        } else {
            emptyMap()
        }
        val lastAt = lastAgentActionAtMs
        val recentAction = lastAt != null && nowMs - lastAt in 0L..AGENT_ACTION_WINDOW_MS
        AttributionContext(
            selfPackage = selfPackage,
            launcherPackages = launcherPackages,
            managedDisplays = managedMap,
            mainDisplayId = Display.DEFAULT_DISPLAY,
            previousMainTopTaskId = previousMainTopTaskId.takeIf { it >= 0 },
            existingMainUserTaskIds = existingMainUserTaskIds.toSet(),
            ledgerEntries = ledger.snapshot(nowMs),
            userIntents = userIntents.toList(),
            recentAgentAction = recentAction,
            lastAgentActionAtMs = lastAt,
        )
    }

    private suspend fun buildClassificationContext(
        window: InterstitialClassifier.WindowInspection,
        attributedDisplayId: Int,
        origin: LaunchOrigin,
        nowMs: Long,
    ): InterstitialClassifier.ClassificationContext = stateMutex.withLock {
        pruneUserIntents(nowMs)
        val vd = runtime.managedDisplayId()
        val hasRecentUserIntentWithin2s = userIntents.any { nowMs - it.at in 0L..USER_INTENT_CLASSIFIER_WINDOW_MS }
        val lastAt = lastAgentActionAtMs
        val recentAgentAction = lastAt != null && nowMs - lastAt in 0L..JumpConfirmPolicy.ACTIVE_AGENT_ACTION_WINDOW_MS
        val originUid = origin.uid.takeIf { it >= 0 }
        val managedUids = if (vd > Display.DEFAULT_DISPLAY) managedUidsByDisplay[vd].orEmpty() else emptySet()
        val hasManagedOrigin = originUid != null && originUid in managedUids
        val originPkg = origin.pkg ?: originUid?.let { findPackageByUid(it) }
        val profileState = jumpConfirmProfiles.current()
        val fallbackInitiator = targetAppProvider()?.first ?: expectedBeforePackage
        InterstitialClassifier.ClassificationContext(
            interstitialId = "${window.displayId}:${window.taskId}:${window.windowId}:${window.packageName}:$nowMs",
            attributedDisplayId = attributedDisplayId,
            nowMs = nowMs,
            launcherPackages = launcherPackages,
            originPackage = originPkg,
            originUid = originUid,
            hasManagedOrigin = hasManagedOrigin,
            hasRecentUserIntentWithin2s = hasRecentUserIntentWithin2s,
            hasActiveCustodyDisplay = vd > Display.DEFAULT_DISPLAY,
            recentAgentAction = recentAgentAction,
            expectedTargetPackage = expectedTargetPackage,
            vdForegroundPackages = vdPackages.toSet(),
            fallbackInitiatorPackage = fallbackInitiator,
            learnedTargetProfiles = if (profileState.available) profileState.profiles else emptyList(),
            resolveSystemTarget = { tid, interstitialPkg, initPkg ->
                runtime.resolveJumpTarget(tid, interstitialPkg, initPkg)
            },
        )
    }

    private fun maybeRecordLearnedProfile(
        entryId: String,
        interstitialPackage: String,
        initiatorPackage: String?,
        originalTargetPackage: String?,
        targetLabel: String?,
        resolvedTargetPackage: String?,
    ) {
        if (originalTargetPackage != null) return
        val initiator = initiatorPackage?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val label = targetLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val target = resolvedTargetPackage?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (target == selfPackage ||
            target == interstitialPackage ||
            target == initiator ||
            target == "android"
        ) {
            return
        }
        if (appAccess.current().denial(target) != null) return
        if (!learnedProfileEntries.add(entryId)) return
        jumpConfirmProfiles.recordLearned(
            initiatorPackage = initiator,
            targetLabel = label,
            targetPackage = target,
        )
    }

    private fun syncVdMetadata(vd: Int) {
        if (vd <= Display.DEFAULT_DISPLAY) return
        val topTask = runtime.topTaskId(vd)
        if (topTask >= 0) {
            val pkg = runtime.packageOfTask(topTask)
            if (!pkg.isNullOrBlank()) onTargetObserved(pkg, topTask)
        }
        targetAppProvider()?.let { (pkg, tid) -> onTargetObserved(pkg, tid) }
    }

    private fun updateMainTopTracker(newTaskId: Int, newPkg: String) {
        if (newTaskId >= 0 && newTaskId != currentMainTopTaskId) {
            previousMainTopTaskId = currentMainTopTaskId
            currentMainTopTaskId = newTaskId
            if (newPkg in launcherPackages || newPkg == selfPackage) {
                existingMainUserTaskIds += newTaskId
            }
        }
    }

    private fun enqueueSpill(report: SpillReport) {
        pendingSpills.addLast(report)
        while (pendingSpills.size > MAX_PENDING_SPILLS) {
            pendingSpills.removeFirst()
        }
    }

    private fun pruneUserIntents(nowMs: Long) {
        while (userIntents.isNotEmpty() && nowMs - userIntents.first().at > USER_INTENT_RETENTION_MS) {
            userIntents.removeFirst()
        }
    }

    private fun notifyResolutionWaiters() {
        for (waiter in waitersForResolution) {
            waiter.complete(Unit)
        }
    }

    private fun findPackageByUid(uid: Int): String? {
        for (pkg in vdPackages) {
            if (runtime.uidOfPackage(pkg) == uid) return pkg
        }
        return null
    }

    private fun resolveJumpConfirmForVd(
        jc: Interstitial.JumpConfirm,
        onSameDisplayAsVd: Boolean,
        vd: Int,
        nowMs: Long,
    ): Interstitial.JumpConfirm? {
        val initiatorPkg = jc.initiatorPackage?.takeIf { it != "android" }
        val targetTopPkg = targetAppProvider()?.first
        val initiatorFromVd = initiatorPkg != null && (
            initiatorPkg in vdPackages ||
                initiatorPkg == targetTopPkg ||
                initiatorPkg == expectedBeforePackage
            )
        val activePending = _interstitialState.value
        val activeAge = activePending?.let { nowMs - it.sinceMs } ?: Long.MAX_VALUE
        val recentAction = lastAgentActionAtMs?.let { nowMs - it in 0L..AGENT_ACTION_WINDOW_MS } == true
        val attributed = onSameDisplayAsVd ||
            initiatorFromVd ||
            (initiatorPkg == null && recentAction) ||
            (activePending != null && activeAge < 15_000L)
        if (!attributed) return null
        val resolvedInitiator = initiatorPkg ?: targetTopPkg ?: expectedBeforePackage
        return jc.copy(displayId = vd, initiatorPackage = resolvedInitiator)
    }

    private fun isRecentsActivity(className: String?): Boolean {
        val lower = className?.lowercase() ?: return false
        return "recents" in lower || "overview" in lower || "taskswitcher" in lower
    }

    private fun recordJumpConfirmEvent(
        initiator: String?,
        target: String?,
        component: String?,
        result: String,
        degraded: Boolean,
        reason: String?,
        by: String?,
        displayId: Int?,
    ) {
        recordEvent(
            RunEvent.JumpConfirm(
                atMs = clock(),
                initiator = initiator,
                target = target,
                component = component,
                result = result,
                degraded = degraded,
                reason = reason,
                by = by,
                displayId = displayId,
            ),
        )
    }

    companion object {
        private const val TAG = "MikaCustody"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val AGENT_ACTION_WINDOW_MS = 3_000L
        private const val USER_INTENT_CLASSIFIER_WINDOW_MS = 2_000L
        private const val USER_INTENT_RETENTION_MS = 10_000L
        private const val AUTO_CLICK_DISAPPEAR_TIMEOUT_MS = 1_000L
        private const val DISMISS_POLL_INTERVAL_MS = 80L
        private const val WINDOW_SETTLE_ATTEMPTS = 6
        private const val WINDOW_SETTLE_STEP_MS = 85L
        private const val WINDOW_RECHECK_MS = 350L
        private const val POST_RESOLVE_SETTLE_MS = 750L
        private const val DENY_SETTLE_WAIT_MS = 3_500L
        private const val MAIN_MOVE_SETTLE_GRACE_MS = 400L
        private const val DEFAULT_CUSTODY_OPEN_TTL_MS = 30 * 60_000L
        private const val RECENT_RESOLVED_SCAN_SUPPRESS_MS = 2_500L
        private const val MAX_PENDING_SPILLS = 8

        /** 构造接入真实 Android Shell 与 Accessibility 服务的默认运行时实现。 */
        fun createAndroidRuntime(
            a11y: ScreenAccessibility,
            shell: IAgentShell,
            displayIdProvider: () -> Int,
            metricsProvider: () -> DisplayMetrics,
            systemPickers: Set<String>,
        ): Runtime = AndroidCustodyRuntime(a11y, shell, displayIdProvider, metricsProvider, systemPickers)
    }

    private class AndroidCustodyRuntime(
        private val a11y: ScreenAccessibility,
        private val shell: IAgentShell,
        private val displayIdProvider: () -> Int,
        private val metricsProvider: () -> DisplayMetrics,
        private val systemPickers: Set<String>,
    ) : Runtime {
        private val pm: PackageManager = a11y.packageManager
        private var binderStub: IAgentCustodyListener.Stub? = null

        override fun managedDisplayId(): Int = displayIdProvider()

        override fun startShellCustody(
            displayId: Int,
            onArrived: (CustodyArrival) -> Unit,
            onVanished: (taskId: Int, lastDisplayId: Int, atMs: Long) -> Unit,
        ): Boolean {
            val stub = object : IAgentCustodyListener.Stub() {
                override fun onTaskArrived(e: Bundle?) {
                    CustodyArrival.fromBundle(e)?.let(onArrived)
                }

                override fun onTaskVanished(taskId: Int, lastDisplayId: Int, at: Long) {
                    onVanished(taskId, lastDisplayId, at)
                }
            }
            binderStub = stub
            return runCatching { shell.startCustody(displayId, stub) }.getOrDefault(false)
        }

        override fun stopShellCustody(displayId: Int) {
            runCatching { shell.stopCustody(displayId) }
            binderStub = null
        }

        override fun launchOrigin(taskId: Int): LaunchOrigin {
            val b = runCatching { shell.launchOrigin(taskId) }.getOrNull() ?: return LaunchOrigin.UNKNOWN
            val raw = ShellLaunchOrigin.fromBundle(b)
            return LaunchOrigin(
                uid = raw.uid,
                pkg = raw.packageName,
                confidence = OriginConfidence.fromLevel(raw.confidence),
            )
        }

        override fun resolveJumpTarget(
            taskId: Int,
            interstitialPackage: String?,
            initiatorPackage: String?,
        ): String? = runCatching {
            shell.resolveJumpTarget(taskId, interstitialPackage, initiatorPackage)
        }.getOrNull()

        override fun moveTask(taskId: Int, displayId: Int): Boolean =
            runCatching { shell.moveTask(taskId, displayId) }.getOrDefault(false)

        override fun displayOfTask(taskId: Int): Int =
            if (taskId < 0) -1 else runCatching { shell.displayOfTask(taskId) }.getOrDefault(-1)

        override fun topTaskId(displayId: Int): Int =
            runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)

        override fun topActivity(displayId: Int): String? =
            runCatching { shell.topActivity(displayId) }.getOrNull()

        override fun packageOfTask(taskId: Int): String? =
            if (taskId < 0) null else runCatching { shell.packageOfTask(taskId) }.getOrNull()

        override fun userOfTask(taskId: Int): Int =
            if (taskId < 0) 0 else runCatching { shell.userOfTask(taskId) }.getOrDefault(0)

        override fun visibleOnMainScreen(pkg: String, userId: Int): Boolean =
            runCatching { shell.visibleOnMainScreen(pkg, userId) }.getOrDefault(false)

        override fun uidOfPackage(pkg: String): Int =
            runCatching { pm.getApplicationInfo(pkg, 0).uid }.getOrDefault(-1)

        override fun appLabel(pkg: String): String? = runCatching {
            val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim()
            label.takeIf { it.isNotEmpty() }
        }.getOrNull()

        override suspend fun inspectDisplayWindows(
            displayId: Int,
            componentHint: String?,
            checkSecureOnUnreadable: Boolean,
        ): List<InspectedWindow> {
            val content = DisplayWindowInspector.contentWindows(a11y.windowsOn(displayId))
            if (content.isEmpty()) return emptyList()
            val m = metricsProvider()
            val topTask = topTaskId(displayId)
            val topTaskPkg = packageOfTask(topTask)
            val topAct = topActivity(displayId)
            // shell.topActivity 返回 bare className（不含包名斜杠）时，回落到 topTask 所属包名
            val topActPkg = topAct?.let { ComponentName.unflattenFromString(it)?.packageName ?: topTaskPkg }
            var secureChecked = false

            val out = ArrayList<InspectedWindow>(content.size)
            for (win in content) {
                val root = win.root ?: continue
                val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val isAppWin = win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                val taskId = if (isAppWin && topTask >= 0 && (pkg == topTaskPkg || pkg == topActPkg)) topTask else -1
                val comp = when {
                    isAppWin && pkg == topActPkg && !topAct.isNullOrBlank() -> topAct
                    else -> componentHint?.takeIf { hint ->
                        val parsedPkg = ComponentName.unflattenFromString(hint)?.packageName
                        if (parsedPkg != null) parsedPkg == pkg
                        else isAppWin && (pkg == topTaskPkg || hint.startsWith("$pkg."))
                    }
                }
                val excludeFromRecents = InterstitialClassifier.isExcludeFromRecents(pm, pkg, comp)
                val initialSnap = InterstitialClassifier.inspectAccessibilityWindow(
                    window = win,
                    displayId = displayId,
                    taskId = taskId,
                    componentName = comp,
                    isFlagSecure = false,
                    isExcludeFromRecents = excludeFromRecents,
                    screenWidth = m.widthPixels,
                    screenHeight = m.heightPixels,
                ) ?: continue

                var finalSnap = initialSnap
                if (checkSecureOnUnreadable &&
                    !secureChecked &&
                    displayId == Display.DEFAULT_DISPLAY &&
                    !initialSnap.inspection.readable &&
                    pkg !in systemPickers &&
                    pkg != SYSTEM_UI_PACKAGE
                ) {
                    secureChecked = true
                    val shot = runCatching { a11y.takeMainScreenshot() }.getOrNull()
                    (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
                    if (shot is ScreenAccessibility.MainShot.Secure) {
                        finalSnap = initialSnap.copy(
                            inspection = initialSnap.inspection.copy(isFlagSecure = true),
                        )
                    }
                }

                val clickAction: (ConfirmButton) -> Boolean = { btn ->
                    performStrictA11yClick(finalSnap.clickableButtons, btn)
                }
                out += InspectedWindow(finalSnap.inspection, clickAction)
            }
            return out
        }

        override fun displayEvents(displayId: Int): Flow<A11yEventInfo> =
            a11y.eventsFor(displayId).mapNotNull { ev ->
                val type = ev.eventType
                if (type != AccessibilityEvent.TYPE_TOUCH_INTERACTION_START &&
                    type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
                    type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                ) {
                    return@mapNotNull null
                }
                A11yEventInfo(
                    displayId = displayId,
                    eventType = type,
                    packageName = ev.packageName?.toString(),
                    className = ev.className?.toString(),
                    text = ev.text?.joinToString(" ")?.takeIf { it.isNotBlank() },
                    atMs = SystemClock.uptimeMillis(),
                )
            }

        override fun windowChanges(): Flow<Unit> = a11y.windowChanges()

        /**
         * 严格原地无障碍节点点击：仅允许调用 [AccessibilityNodeInfo.ACTION_CLICK]，
         * 绝不降级为主屏触摸或手势注入。
         */
        private fun performStrictA11yClick(
            buttons: List<InterstitialClassifier.ClickableButtonNode>,
            target: ConfirmButton,
        ): Boolean {
            val candidate = buttons.firstOrNull { item ->
                val ridMatches = target.id == null || item.input.id == target.id
                val textMatches = Hant.fold(item.input.text).trim().equals(Hant.fold(target.rawText).trim(), ignoreCase = true) ||
                    ConfirmButtons.normalize(item.input.text) == target.foldedText
                ridMatches && textMatches && item.node.isVisibleToUser && item.node.isEnabled
            } ?: buttons.firstOrNull { item ->
                ConfirmButtons.normalize(item.input.text) == target.foldedText &&
                    item.node.isVisibleToUser &&
                    item.node.isEnabled
            } ?: return false

            runCatching { candidate.node.refresh() }
            if (!candidate.node.isVisibleToUser || !candidate.node.isEnabled) return false
            return runCatching {
                candidate.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
        }
    }
}

internal fun Context.applicationPackagesForCustody(launcherPackages: Set<String>): Map<String, String> =
    InterstitialClassifier.installedAppLabels(packageManager, packageName, launcherPackages)
