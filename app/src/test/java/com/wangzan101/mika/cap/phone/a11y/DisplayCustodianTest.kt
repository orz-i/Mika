package com.wangzan101.mika.cap.phone.a11y

import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.agent.use.common.CustodyArrival
import com.agent.use.common.LaunchOrigin as ShellOrigin
import com.wangzan101.mika.core.access.AppAccessPolicy
import com.wangzan101.mika.core.custody.ConfirmButton
import com.wangzan101.mika.core.custody.JumpConfirmDegradeReason
import com.wangzan101.mika.core.custody.JumpConfirmOutcome
import com.wangzan101.mika.core.custody.LaunchOrigin
import com.wangzan101.mika.core.custody.OriginConfidence
import com.wangzan101.mika.core.record.RunEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DisplayCustodian] 常驻托管状态机 JVM 单测（覆盖开关关闭/开启、悬浮窗、>10s 延迟确认、InUse、自发跳转、熔断、无人值守）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DisplayCustodianTest {

    private companion object {
        const val SELF_PKG = "com.wangzan101.mika"
        const val LAUNCHER_PKG = "com.android.launcher"
        const val INITIATOR_PKG = "com.tencent.mm"
        const val TARGET_PKG = "com.taobao.taobao"
        const val SYSTEM_GUARD_PKG = "com.oplus.securitypermission"
        const val GUARD_COMPONENT = "com.oplus.securitypermission/.AppStartConfirmDialogActivity"
        const val VD_ID = 7
        const val INITIATOR_UID = 10123
        const val WEREAD_PKG = "com.tencent.weread"
        const val WEREAD_UID = 10410
        const val SYSTEM_UID = 1000
    }

    private class FakeRuntime(
        var vdId: Int = VD_ID,
    ) : DisplayCustodian.Runtime {
        val uids = ConcurrentHashMap<String, Int>().apply {
            put(INITIATOR_PKG, INITIATOR_UID)
            put(WEREAD_PKG, WEREAD_UID)
            put(TARGET_PKG, 10124)
            put(SYSTEM_GUARD_PKG, SYSTEM_UID)
            put("com.example.existing", 10125)
        }
        val labels = ConcurrentHashMap<String, String>().apply {
            put(INITIATOR_PKG, "微信")
            put(WEREAD_PKG, "微信读书")
            put(TARGET_PKG, "淘宝")
            put(SYSTEM_GUARD_PKG, "安全服务")
            put("com.example.existing", "ExistingApp")
            put(LAUNCHER_PKG, "桌面")
        }
        val taskDisplays = ConcurrentHashMap<Int, Int>().apply {
            put(1, Display.DEFAULT_DISPLAY)
            put(20, VD_ID)
        }
        val taskPackages = ConcurrentHashMap<Int, String>().apply {
            put(1, LAUNCHER_PKG)
            put(20, INITIATOR_PKG)
        }
        val topTaskByDisplay = ConcurrentHashMap<Int, Int>().apply {
            put(Display.DEFAULT_DISPLAY, 1)
            put(VD_ID, 20)
        }
        val windowsByDisplay = ConcurrentHashMap<Int, List<DisplayCustodian.InspectedWindow>>()
        val moveCalls = CopyOnWriteArrayList<Pair<Int, Int>>()
        var autoTickOnInspectMs: Long = 0L
        var clockAdvanceHook: (Long) -> Unit = {}

        private val eventChannels = ConcurrentHashMap<Int, Channel<DisplayCustodian.A11yEventInfo>>()
        private val windowChangesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
        private var arrivalSink: ((CustodyArrival) -> Unit)? = null
        private var vanishSink: ((Int, Int, Long) -> Unit)? = null

        override fun managedDisplayId(): Int = vdId

        override fun startShellCustody(
            displayId: Int,
            onArrived: (CustodyArrival) -> Unit,
            onVanished: (taskId: Int, lastDisplayId: Int, atMs: Long) -> Unit,
        ): Boolean {
            arrivalSink = onArrived
            vanishSink = onVanished
            return true
        }

        override fun stopShellCustody(displayId: Int) {
            arrivalSink = null
            vanishSink = null
        }

        override fun launchOrigin(taskId: Int): LaunchOrigin = LaunchOrigin.UNKNOWN

        override fun moveTask(taskId: Int, displayId: Int): Boolean {
            moveCalls += taskId to displayId
            taskDisplays[taskId] = displayId
            topTaskByDisplay[displayId] = taskId
            return true
        }

        override fun displayOfTask(taskId: Int): Int = taskDisplays[taskId] ?: -1

        override fun topTaskId(displayId: Int): Int = topTaskByDisplay[displayId] ?: -1

        override fun topActivity(displayId: Int): String? =
            topTaskByDisplay[displayId]?.let { taskPackages[it] }?.let { "$it/.MainActivity" }

        override fun packageOfTask(taskId: Int): String? = taskPackages[taskId]

        override fun userOfTask(taskId: Int): Int = 0

        override fun visibleOnMainScreen(pkg: String, userId: Int): Boolean =
            topTaskByDisplay[Display.DEFAULT_DISPLAY]?.let { taskPackages[it] == pkg } == true

        /** 与引擎一致：只有任务停在托管屏上才返回托管屏 ID，否则 -1。 */
        override fun displayOfPackage(pkg: String): Int =
            if (taskPackages.entries.any { it.value == pkg && taskDisplays[it.key] == vdId }) vdId else -1

        override fun uidOfPackage(pkg: String): Int = uids[pkg] ?: -1

        override fun appLabel(pkg: String): String? = labels[pkg]

        override suspend fun inspectDisplayWindows(
            displayId: Int,
            componentHint: String?,
            checkSecureOnUnreadable: Boolean,
        ): List<DisplayCustodian.InspectedWindow> {
            if (autoTickOnInspectMs > 0L) {
                clockAdvanceHook(autoTickOnInspectMs)
            }
            return windowsByDisplay[displayId].orEmpty()
        }

        override fun displayEvents(displayId: Int): Flow<DisplayCustodian.A11yEventInfo> =
            eventChannels.getOrPut(displayId) { Channel(Channel.UNLIMITED) }.receiveAsFlow()

        override fun windowChanges(): Flow<Unit> = windowChangesFlow

        fun emitArrival(arrival: CustodyArrival) {
            taskDisplays[arrival.taskId] = arrival.displayId
            taskPackages[arrival.taskId] = arrival.packageName
            topTaskByDisplay[arrival.displayId] = arrival.taskId
            arrivalSink?.invoke(arrival)
        }

        fun emitVanished(taskId: Int, lastDisplayId: Int, atMs: Long) {
            vanishSink?.invoke(taskId, lastDisplayId, atMs)
        }

        fun emitA11y(ev: DisplayCustodian.A11yEventInfo) {
            eventChannels.getOrPut(ev.displayId) { Channel(Channel.UNLIMITED) }.trySend(ev)
        }

        fun emitWindowChange() {
            windowChangesFlow.tryEmit(Unit)
        }
    }

    private fun classifier(): InterstitialClassifier = InterstitialClassifier(
        selfPackage = SELF_PKG,
        systemPickers = setOf("android"),
        isSystemPackage = { it == SYSTEM_GUARD_PKG || it == "android" },
        isAuthenticatorPackage = { false },
        appLabel = { pkg ->
            when (pkg) {
                INITIATOR_PKG -> "微信"
                WEREAD_PKG -> "微信读书"
                TARGET_PKG -> "淘宝"
                SYSTEM_GUARD_PKG -> "安全服务"
                else -> null
            }
        },
        installedApps = {
            mapOf(
                INITIATOR_PKG to "微信",
                WEREAD_PKG to "微信读书",
                TARGET_PKG to "淘宝",
            )
        },
    )

    private fun jumpConfirmWindow(
        displayId: Int = Display.DEFAULT_DISPLAY,
        taskId: Int = 55,
        windowType: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        titleText: String = "“微信”想要打开“淘宝”",
        clickAction: (ConfirmButton) -> Boolean = { true },
    ): DisplayCustodian.InspectedWindow {
        val inspection = InterstitialClassifier.WindowInspection(
            displayId = displayId,
            taskId = taskId,
            windowId = 900 + taskId,
            windowType = windowType,
            packageName = SYSTEM_GUARD_PKG,
            componentName = GUARD_COMPONENT,
            isDialogWindow = true,
            texts = listOf(titleText, "始终允许打开", "取消", "打开"),
            nodes = listOf(
                InterstitialClassifier.NodeInspection(text = titleText, role = "TextView"),
                InterstitialClassifier.NodeInspection(text = "始终允许打开", role = "CompoundButton", checkable = true, checked = false),
                InterstitialClassifier.NodeInspection(text = "取消", role = "Button", resourceId = "android:id/button2", clickable = true),
                InterstitialClassifier.NodeInspection(text = "打开", role = "Button", resourceId = "android:id/button1", clickable = true),
            ),
            isExcludeFromRecents = true,
        )
        return DisplayCustodian.InspectedWindow(inspection, clickAction)
    }

    private fun arrival(
        taskId: Int,
        pkg: String,
        displayId: Int = Display.DEFAULT_DISPLAY,
        originUid: Int = INITIATOR_UID,
        originPkg: String? = INITIATOR_PKG,
        confidence: Int = ShellOrigin.CONFIDENCE_HIGH,
        at: Long,
        sameTask: Boolean = false,
        fromDisplay: Int = CustodyArrival.FROM_NEW,
        managedDisplay: Int = if (originUid == INITIATOR_UID) VD_ID else CustodyArrival.NO_MANAGED_DISPLAY,
    ) = CustodyArrival(
        taskId = taskId,
        packageName = pkg,
        userId = 0,
        activity = "$pkg/.MainActivity",
        displayId = displayId,
        fromDisplayId = fromDisplay,
        newTask = fromDisplay == CustodyArrival.FROM_NEW,
        sameTask = sameTask,
        originUid = originUid,
        originPackage = originPkg,
        originConfidence = confidence,
        managedDisplayId = managedDisplay,
        at = at,
    )

    @Test
    fun switchOffActivityConfirmSupportsDelayedUserApprovalOver10sAndR2PullBack() = runTest {
        var nowMs = 10_000L
        val clock = { nowMs + testScheduler.currentTime }
        val rt = FakeRuntime()
        var autoClicks = 0
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { false },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            clock = clock,
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(INITIATOR_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = TARGET_PKG, beforePkg = INITIATOR_PKG)
        runCurrent()

        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(clickAction = { autoClicks++; true }),
        )
        rt.emitArrival(arrival(taskId = 55, pkg = SYSTEM_GUARD_PKG, at = clock()))

        val spill = custodian.drainAndTakeLatestSpill()
        assertEquals(0, autoClicks)
        assertNotNull("Switch OFF must park interstitial for user", custodian.interstitialState.value)
        assertEquals(JumpConfirmOutcome.WAITING_USER, spill?.opened?.jumpConfirm?.outcome)
        assertEquals(0, rt.moveCalls.size)

        // 挂起等待：用户延迟 15s（超过旧机制 6s / R2 10s）后才在主屏点允许
        val waitDeferred = async { custodian.awaitInterstitial() }
        runCurrent()
        advanceTimeBy(15_000L)
        runCurrent()
        assertFalse("Must stay suspended while confirm dialog is still open", waitDeferred.isCompleted)

        // 用户在 15s 后点击“打开”：弹窗消失，系统随后在主屏拉起目标应用
        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = emptyList()
        rt.emitVanished(taskId = 55, lastDisplayId = Display.DEFAULT_DISPLAY, atMs = clock())
        rt.emitArrival(
            arrival(
                taskId = 88,
                pkg = TARGET_PKG,
                originUid = SYSTEM_UID,
                originPkg = SYSTEM_GUARD_PKG,
                at = clock() + 50L,
            ),
        )
        runCurrent()
        val result = waitDeferred.await()
        assertTrue("Expected Settled after delayed user approval, got $result", result is DisplayCustodian.WaitResult.Settled)
        val settled = result as DisplayCustodian.WaitResult.Settled
        assertEquals(TARGET_PKG, settled.report?.opened?.packageName)
        assertTrue(settled.report?.opened?.pulledBack == true)
        assertEquals(JumpConfirmOutcome.USER_ALLOWED, settled.report?.opened?.jumpConfirm?.outcome)
        assertTrue(rt.moveCalls.contains(88 to VD_ID))
        custodian.close()
    }

    @Test
    fun switchOffOnVirtualDisplayMovesToMainOnlyWhenUserTapsConfirmOnMain() = runTest {
        val rt = FakeRuntime()
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { false },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            clock = { 20_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(INITIATOR_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = TARGET_PKG, beforePkg = INITIATOR_PKG)
        runCurrent()

        rt.taskDisplays[66] = VD_ID
        rt.taskPackages[66] = SYSTEM_GUARD_PKG
        rt.topTaskByDisplay[VD_ID] = 66
        rt.windowsByDisplay[VD_ID] = listOf(jumpConfirmWindow(displayId = VD_ID, taskId = 66))
        rt.emitWindowChange()
        custodian.drainAndTakeLatestSpill()

        val pending = custodian.interstitialState.value
        assertNotNull(pending)
        assertTrue("JumpConfirm landed on VD should stay on VD initially", pending!!.onVirtualDisplay)
        assertTrue("Must not auto-move to main screen before user action", rt.moveCalls.isEmpty())

        // 用户在胶囊/卡片点击「到主屏确认」后才显式挪回主屏
        assertTrue(custodian.moveInterstitialToMain())
        assertTrue(rt.moveCalls.contains(66 to Display.DEFAULT_DISPLAY))
        assertFalse(custodian.interstitialState.value!!.onVirtualDisplay)
        custodian.close()
    }

    @Test
    fun switchOffUserDeniesClosesInterstitialWithoutPullingUnrelatedMainTask() = runTest {
        val rt = FakeRuntime()
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { false },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            clock = { 30_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(INITIATOR_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = TARGET_PKG, beforePkg = INITIATOR_PKG)
        runCurrent()

        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(jumpConfirmWindow(taskId = 55))
        rt.emitArrival(arrival(taskId = 55, pkg = SYSTEM_GUARD_PKG, at = 30_000L))
        custodian.drainAndTakeLatestSpill()

        val waitDeferred = async { custodian.awaitInterstitial() }
        runCurrent()

        // 用户点击“取消”，弹窗关闭并恢复主屏任务 1（桌面）
        rt.emitA11y(
            DisplayCustodian.A11yEventInfo(
                displayId = Display.DEFAULT_DISPLAY,
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                packageName = SYSTEM_GUARD_PKG,
                className = "android.widget.Button",
                text = "取消",
                atMs = 30_200L + testScheduler.currentTime,
            ),
        )
        runCurrent()
        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = emptyList()
        rt.emitVanished(taskId = 55, lastDisplayId = Display.DEFAULT_DISPLAY, atMs = 30_250L + testScheduler.currentTime)
        val result = waitDeferred.await() as DisplayCustodian.WaitResult.Settled
        assertEquals(JumpConfirmOutcome.DENIED, result.report?.opened?.jumpConfirm?.outcome)
        assertTrue(result.report?.opened?.closed == true)
        assertTrue("No task should be pulled back on user denial", rt.moveCalls.isEmpty())
        custodian.close()
    }

    @Test
    fun switchOnGuardrailsPassAutoClicksOnceLogsEventAndPullsTargetBack() = runTest {
        val rt = FakeRuntime()
        val events = mutableListOf<RunEvent.JumpConfirm>()
        var clickCount = 0
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            recordEvent = { if (it is RunEvent.JumpConfirm) events += it },
            hostingEnabled = { true },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            clock = { 40_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(INITIATOR_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = TARGET_PKG, beforePkg = INITIATOR_PKG)
        runCurrent()

        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(
                taskId = 55,
                clickAction = { btn ->
                    clickCount++
                    assertEquals("打开", btn.rawText)
                    // 点击成功：确认框消失
                    rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = emptyList()
                    true
                },
            ),
        )
        rt.emitArrival(arrival(taskId = 55, pkg = SYSTEM_GUARD_PKG, at = 40_000L))
        val firstSpill = custodian.drainAndTakeLatestSpill()
        assertEquals(1, clickCount)
        assertNull("Auto-allowed dialog must not park interstitialState", custodian.interstitialState.value)
        assertEquals(JumpConfirmOutcome.AUTO_ALLOWED, firstSpill?.opened?.jumpConfirm?.outcome)
        assertEquals(1, events.size)
        assertEquals("auto_allowed", events.single().result)
        assertFalse(events.single().degraded)

        // 确认框放行后目标 App 落地主屏：由 R2 立即拉回虚拟屏并继承 AUTO_ALLOWED
        rt.emitArrival(
            arrival(
                taskId = 99,
                pkg = TARGET_PKG,
                originUid = SYSTEM_UID,
                originPkg = SYSTEM_GUARD_PKG,
                at = 40_200L + testScheduler.currentTime,
            ),
        )
        val followup = custodian.drainAndTakeLatestSpill()
        assertTrue(rt.moveCalls.contains(99 to VD_ID))
        assertTrue(followup?.opened?.pulledBack == true)
        assertEquals(JumpConfirmOutcome.AUTO_ALLOWED, followup?.opened?.jumpConfirm?.outcome)
        custodian.close()
    }

    @Test
    fun switchOnNotDismissedWithin1sTriggersCircuitBreakerAndFallsBackToUser() = runTest {
        val rt = FakeRuntime()
        val events = mutableListOf<RunEvent.JumpConfirm>()
        var broken = false
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            recordEvent = { if (it is RunEvent.JumpConfirm) events += it },
            hostingEnabled = { true },
            isDeviceCircuitBroken = { broken },
            onCircuitBreak = { broken = true },
            clock = { 50_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(INITIATOR_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = TARGET_PKG, beforePkg = INITIATOR_PKG)
        runCurrent()

        // 模拟 OEM 窗口拦截了 ACTION_CLICK：返回 true 但确认框 1s 内一直没有消失
        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(taskId = 55, clickAction = { true }),
        )
        rt.emitArrival(arrival(taskId = 55, pkg = SYSTEM_GUARD_PKG, at = 50_000L))
        val spill = custodian.drainAndTakeLatestSpill()

        assertTrue("Must trip device circuit breaker after 1s timeout", broken)
        assertNotNull("Must fall back to user confirmation", custodian.interstitialState.value)
        assertEquals(JumpConfirmDegradeReason.DEVICE_CIRCUIT_BROKEN, custodian.interstitialState.value?.degradeReason)
        assertEquals(JumpConfirmOutcome.WAITING_USER, spill?.opened?.jumpConfirm?.outcome)
        assertTrue(events.any { it.result == "degraded" && it.reason == "not_dismissed_1s" })
        custodian.close()
    }

    @Test
    fun systemAlertWindowOverlayConfirmDetectedByWindowChangesAndAutoClicked() = runTest {
        val rt = FakeRuntime()
        var overlayClicks = 0
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { true },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            clock = { 60_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(INITIATOR_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = TARGET_PKG, beforePkg = INITIATOR_PKG)
        runCurrent()

        // SYSTEM_ALERT_WINDOW / TYPE_SYSTEM 悬浮窗没有 taskId (-1)
        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(
                displayId = Display.DEFAULT_DISPLAY,
                taskId = -1,
                windowType = AccessibilityWindowInfo.TYPE_SYSTEM,
                clickAction = {
                    overlayClicks++
                    rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = emptyList()
                    true
                },
            ),
        )
        rt.emitWindowChange()
        runCurrent()
        val spill = custodian.drainAndTakeLatestSpill()
        assertEquals(1, overlayClicks)
        assertEquals(JumpConfirmOutcome.AUTO_ALLOWED, spill?.opened?.jumpConfirm?.outcome)
        custodian.close()
    }

    @Test
    fun launcherIntentInUseAndSpontaneousIdleJumpNeverPullOrAutoClick() = runTest {
        val rt = FakeRuntime()
        var clicks = 0
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { true },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            clock = { 70_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(INITIATOR_PKG, taskId = 20)
        runCurrent()

        // 场景 A：主屏用户已有同任务继续操作（InUse）绝不挪回 VD
        rt.taskPackages[1] = "com.example.existing"
        rt.topTaskByDisplay[Display.DEFAULT_DISPLAY] = 1
        custodian.onAgentActionStarting()
        rt.emitArrival(
            arrival(
                taskId = 1,
                pkg = "com.example.existing",
                sameTask = true,
                fromDisplay = Display.DEFAULT_DISPLAY,
                at = 70_100L,
            ),
        )
        custodian.drainAndTakeLatestSpill()
        assertTrue("InUse task on main screen must never be moved", rt.moveCalls.isEmpty())

        // 场景 B：用户在桌面点击（Launcher intent），就算后台进程随后在主屏拉起也不误捞
        rt.emitA11y(
            DisplayCustodian.A11yEventInfo(
                displayId = Display.DEFAULT_DISPLAY,
                eventType = AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
                packageName = LAUNCHER_PKG,
                className = null,
                text = null,
                atMs = 70_500L + testScheduler.currentTime,
            ),
        )
        runCurrent()
        rt.emitArrival(arrival(taskId = 102, pkg = TARGET_PKG, at = 70_800L + testScheduler.currentTime))
        custodian.drainAndTakeLatestSpill()
        assertTrue("User-initiated app open must stay on main screen", rt.moveCalls.isEmpty())

        // 场景 C：没有最近 Agent 动作时的自发跳转弹窗：绝不自动代点
        advanceTimeBy(15_000L) // 超过 3s 活跃动作窗
        runCurrent()
        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(taskId = 103, clickAction = { clicks++; true }),
        )
        rt.emitArrival(arrival(taskId = 103, pkg = SYSTEM_GUARD_PKG, at = 70_000L + testScheduler.currentTime))
        custodian.drainAndTakeLatestSpill()
        assertEquals("Spontaneous idle jump must never be auto-clicked", 0, clicks)
        custodian.close()
    }

    @Test
    fun unattendedModeFailsClosedWithUnavailableWithoutHanging() = runTest {
        val rt = FakeRuntime()
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            allowUserInteraction = false,
            hostingEnabled = { false },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            clock = { 80_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(INITIATOR_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = TARGET_PKG, beforePkg = INITIATOR_PKG)
        runCurrent()

        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(jumpConfirmWindow(taskId = 55))
        rt.emitArrival(arrival(taskId = 55, pkg = SYSTEM_GUARD_PKG, at = 80_000L))
        val spill = custodian.drainAndTakeLatestSpill()

        assertNull("Unattended mode must not park waiting UI state", custodian.interstitialState.value)
        assertEquals(JumpConfirmOutcome.UNAVAILABLE, spill?.opened?.jumpConfirm?.outcome)
        assertTrue(rt.moveCalls.isEmpty())
        custodian.close()
    }

    @Test
    fun colorOsSystemMediatedMiniProgramSwitchOffPausesAndPullsBackTarget() = runTest {
        val rt = FakeRuntime().apply {
            taskPackages[20] = WEREAD_PKG
        }
        var autoClicks = 0
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { false },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            targetAppProvider = { WEREAD_PKG to 20 },
            clock = { 90_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(WEREAD_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
        runCurrent()

        // 真实 PLK110：uid=1000 / pkg="android" 拉起 AppStartConfirmDialogActivity（“微信读书”想要打开“微信读书”小程序）
        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(
                taskId = 48921,
                titleText = "“微信读书”想要打开“微信读书”小程序",
                clickAction = { autoClicks++; true },
            ),
        )
        rt.emitArrival(
            arrival(
                taskId = 48921,
                pkg = SYSTEM_GUARD_PKG,
                originUid = SYSTEM_UID,
                originPkg = "android",
                managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                at = 90_050L,
            ),
        )
        val openSpill = custodian.drainAndTakeLatestSpill()
        assertEquals(0, autoClicks)
        assertEquals(JumpConfirmOutcome.WAITING_USER, openSpill?.opened?.jumpConfirm?.outcome)
        val pending = custodian.interstitialState.value
        assertNotNull("Must park Waiting state instead of dropping uid=1000 dialog as R6 OTHER", pending)
        assertEquals(WEREAD_PKG, pending?.initiatorPackage)
        assertNull(pending?.targetPackage)
        assertEquals("微信读书小程序", pending?.targetLabel)

        val waitDeferred = async { custodian.awaitInterstitial() }
        runCurrent()

        // 用户点击“打开”（即使冷启动 >3.5s 仍应保留 ALLOW 供后续 R2 回捞）
        rt.emitA11y(
            DisplayCustodian.A11yEventInfo(
                displayId = Display.DEFAULT_DISPLAY,
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                packageName = SYSTEM_GUARD_PKG,
                className = "android.widget.Button",
                text = "打开",
                atMs = 90_300L + testScheduler.currentTime,
            ),
        )
        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = emptyList()
        advanceTimeBy(4_000L)
        runCurrent()

        val result = waitDeferred.await() as DisplayCustodian.WaitResult.Settled
        // 即使稍晚（>3.5s）微信小程序宿主由 system_server(1000/android) 在主屏落地，R2 依然准确回捞到 VD
        rt.emitArrival(
            arrival(
                taskId = 48930,
                pkg = INITIATOR_PKG,
                originUid = SYSTEM_UID,
                originPkg = "android",
                managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                at = 90_000L + testScheduler.currentTime,
            ),
        )
        val followup = custodian.drainAndTakeLatestSpill() ?: result.report
        assertEquals(listOf(48930 to VD_ID), rt.moveCalls.toList())
        assertTrue(followup?.opened?.pulledBack == true)
        assertEquals(JumpConfirmOutcome.USER_ALLOWED, followup?.opened?.jumpConfirm?.outcome)
        custodian.close()
    }

    @Test
    fun colorOsSystemMediatedMiniProgramSwitchOnSafelyDegradesUnknownTarget() = runTest {
        val rt = FakeRuntime().apply {
            taskPackages[20] = WEREAD_PKG
        }
        var autoClicks = 0
        val events = mutableListOf<RunEvent.JumpConfirm>()
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            recordEvent = { ev -> if (ev is RunEvent.JumpConfirm) events += ev },
            hostingEnabled = { true },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            targetAppProvider = { WEREAD_PKG to 20 },
            clock = { 100_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(WEREAD_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
        runCurrent()

        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(
                taskId = 48921,
                titleText = "“微信读书”想要打开“微信读书”小程序",
                clickAction = { autoClicks++; true },
            ),
        )
        rt.emitArrival(
            arrival(
                taskId = 48921,
                pkg = SYSTEM_GUARD_PKG,
                originUid = SYSTEM_UID,
                originPkg = "android",
                managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                at = 100_050L,
            ),
        )
        val spill = custodian.drainAndTakeLatestSpill()
        assertEquals(0, autoClicks)
        assertEquals(JumpConfirmDegradeReason.TARGET_UNKNOWN, spill?.opened?.jumpConfirm?.degradeReason)
        assertEquals(JumpConfirmOutcome.WAITING_USER, spill?.opened?.jumpConfirm?.outcome)
        assertEquals(1, events.count { it.result == "degraded" && it.reason == "target_unknown" })
        custodian.close()
    }

    @Test
    fun alreadyOpenMainSystemJumpDialogDetectedByRefreshAndKnownTargetSwitchOnAutoClicks() = runTest {
        val rt = FakeRuntime().apply {
            taskPackages[20] = WEREAD_PKG
        }
        var autoClicks = 0
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { false },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            targetAppProvider = { WEREAD_PKG to 20 },
            clock = { 110_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(WEREAD_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
        runCurrent()

        // 模拟没有收到新的 CustodyArrival（例如 result code=3 复用既有任务 48921），但主屏仍挂着系统弹窗
        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(
                taskId = 48921,
                titleText = "“微信读书”想要打开“微信读书”小程序",
            ),
        )
        val pending = custodian.refreshAndCurrentInterstitial()
        assertNotNull("refreshAndCurrentInterstitial must detect existing main OEM dialog", pending)
        assertEquals(48921, pending?.taskId)
        assertEquals("微信读书小程序", pending?.targetLabel)
        custodian.close()

        // 明确跨 App（微信读书 -> 微信）且开关开启、origin=(1000, android)：自动代点一次并在 R2 回捞
        val rtOn = FakeRuntime().apply { taskPackages[20] = WEREAD_PKG }
        val custodianOn = DisplayCustodian(
            runtime = rtOn,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { true },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            targetAppProvider = { WEREAD_PKG to 20 },
            clock = { 120_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )
        custodianOn.start()
        custodianOn.onTargetObserved(WEREAD_PKG, taskId = 20)
        custodianOn.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
        runCurrent()

        rtOn.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(
                taskId = 48922,
                titleText = "“微信读书”想要打开“微信”",
                clickAction = {
                    autoClicks++
                    rtOn.windowsByDisplay[Display.DEFAULT_DISPLAY] = emptyList()
                    true
                },
            ),
        )
        rtOn.emitArrival(
            arrival(
                taskId = 48922,
                pkg = SYSTEM_GUARD_PKG,
                originUid = SYSTEM_UID,
                originPkg = "android",
                managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                at = 120_050L,
            ),
        )
        val autoSpill = custodianOn.drainAndTakeLatestSpill()
        assertEquals(1, autoClicks)
        assertEquals(JumpConfirmOutcome.AUTO_ALLOWED, autoSpill?.opened?.jumpConfirm?.outcome)

        rtOn.emitArrival(
            arrival(
                taskId = 48931,
                pkg = INITIATOR_PKG,
                originUid = SYSTEM_UID,
                originPkg = "android",
                managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                at = 120_250L,
            ),
        )
        val pulledSpill = custodianOn.drainAndTakeLatestSpill()
        assertEquals(listOf(48922 to VD_ID, 48931 to VD_ID), rtOn.moveCalls.toList())
        assertTrue(pulledSpill?.opened?.pulledBack == true)
        custodianOn.close()
    }

    @Test
    fun transientOpeningAnimationBoundsDoNotMisclassifyFullscreenAppAsDialog() = runTest {
        val rt = FakeRuntime()
        var inspections = 0
        // 第 1 次抓取仍处于开场缩放动画中（isDialogWindow=true），稳定后恢复全屏
        val baseSettingsWindow = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            taskId = 500,
            windowId = 501,
            packageName = TARGET_PKG,
            componentName = "com.oplus.settings.feature.homepage.OplusSettingsHomepageActivity",
            windowType = AccessibilityWindowInfo.TYPE_APPLICATION,
            isDialogWindow = true,
            texts = listOf("设置", "WLAN", "蓝牙"),
        )
        val animatingCustodian = DisplayCustodian(
            runtime = object : DisplayCustodian.Runtime by rt {
                override suspend fun inspectDisplayWindows(
                    displayId: Int,
                    componentHint: String?,
                    checkSecureOnUnreadable: Boolean,
                ): List<DisplayCustodian.InspectedWindow> {
                    if (displayId != Display.DEFAULT_DISPLAY) return rt.inspectDisplayWindows(displayId, componentHint, checkSecureOnUnreadable)
                    inspections++
                    val settledWin = if (inspections == 1) baseSettingsWindow else baseSettingsWindow.copy(isDialogWindow = false)
                    return listOf(DisplayCustodian.InspectedWindow(settledWin) { false })
                }
            },
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { false },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            clock = { 130_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )
        animatingCustodian.start()
        animatingCustodian.onTargetObserved(INITIATOR_PKG, taskId = 20)
        animatingCustodian.onAgentActionStarting(expectedTargetPkg = TARGET_PKG, beforePkg = INITIATOR_PKG)
        runCurrent()

        rt.emitArrival(arrival(taskId = 500, pkg = TARGET_PKG, at = 130_050L))
        val report = animatingCustodian.drainAndTakeLatestSpill()
        assertTrue("settling should re-inspect beyond animation frame", inspections >= 2)
        assertEquals(listOf(500 to VD_ID), rt.moveCalls.toList())
        assertTrue("fullscreen app after animation settle must be pulled back", report?.opened?.pulledBack == true)
        animatingCustodian.close()
    }

    @Test
    fun colorOsPostConfirmSelfOriginatedLauncherAndAppBrandTasksPullBackAndResolveAllowedAfterLongWait() = runTest {
        val rt = FakeRuntime().apply {
            taskPackages[20] = WEREAD_PKG
        }
        val events = CopyOnWriteArrayList<RunEvent.JumpConfirm>()
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            recordEvent = { ev -> if (ev is RunEvent.JumpConfirm) events += ev },
            hostingEnabled = { false },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            targetAppProvider = { WEREAD_PKG to 20 },
            clock = { 140_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(WEREAD_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
        runCurrent()

        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(
                taskId = 49054,
                titleText = "“微信读书”想要打开“微信读书”小程序",
                clickAction = { false },
            ),
        )
        rt.emitArrival(
            arrival(
                taskId = 49054,
                pkg = SYSTEM_GUARD_PKG,
                originUid = SYSTEM_UID,
                originPkg = "android",
                managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                at = 140_050L,
            ),
        )
        val openSpill = custodian.drainAndTakeLatestSpill()
        assertEquals(JumpConfirmOutcome.WAITING_USER, openSpill?.opened?.jumpConfirm?.outcome)

        val waitDeferred = async { custodian.awaitInterstitial() }
        runCurrent()

        // 用户等待 >120s（超出旧 DEFAULT_OPEN_TTL_MS）后点击“打开”，且无按钮文本 TYPE_VIEW_CLICKED 线索
        advanceTimeBy(130_000L)
        runCurrent()
        assertFalse("Must stay parked even beyond 120s wait", waitDeferred.isCompleted)

        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = emptyList()
        rt.emitVanished(taskId = 49054, lastDisplayId = Display.DEFAULT_DISPLAY, atMs = 140_000L + testScheduler.currentTime)
        runCurrent()

        // ColorOS 将目标微信任务以 com.tencent.mm/10123 自转派顺序拉起到主屏（先 LauncherUI，620ms 后 AppBrandUI00）
        rt.emitArrival(
            arrival(
                taskId = 49055,
                pkg = INITIATOR_PKG,
                originUid = INITIATOR_UID,
                originPkg = INITIATOR_PKG,
                managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                at = 140_000L + testScheduler.currentTime + 50L,
            ),
        )
        advanceTimeBy(620L)
        rt.emitArrival(
            arrival(
                taskId = 49056,
                pkg = INITIATOR_PKG,
                originUid = INITIATOR_UID,
                originPkg = INITIATOR_PKG,
                managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                at = 140_000L + testScheduler.currentTime,
            ),
        )

        val result = waitDeferred.await() as DisplayCustodian.WaitResult.Settled
        assertEquals(listOf(49055 to VD_ID, 49056 to VD_ID), rt.moveCalls.toList())
        assertTrue(result.report?.opened?.pulledBack == true)
        assertEquals(JumpConfirmOutcome.USER_ALLOWED, result.report?.opened?.jumpConfirm?.outcome)

        advanceTimeBy(4_000L)
        runCurrent()
        assertTrue("Must not emit delayed dismissed_without_target denial after self-origin pullback", events.none { it.result == "denied" })
        custodian.close()
    }

    @Test
    fun scheme1ResolvesMiniProgramTargetForAutoClickAndScheme2LearnsFallbackProfileAcrossTurns() = runTest {
        var resolvedTargetByShell: String? = INITIATOR_PKG

        // 1. 方案 1 命中：系统侧返回 com.tencent.mm -> 开关开启时直接自动放行并拉回 VD
        val profilesFile = java.nio.file.Files.createTempFile("custodian-profiles", ".json").toFile().apply { delete() }
        val profileStore = com.wangzan101.mika.settings.JumpConfirmProfileStore(profilesFile)
        try {
            var blockedApps = emptySet<String>()
            val policy = com.wangzan101.mika.core.access.AppAccessPolicy {
                com.wangzan101.mika.core.access.AppAccessState(blocked = blockedApps)
            }
            val fakeRt = FakeRuntime().apply { taskPackages[20] = WEREAD_PKG }
            val runtimeWithShellTarget = object : DisplayCustodian.Runtime by fakeRt {
                override fun resolveJumpTarget(taskId: Int, interstitialPackage: String?, initiatorPackage: String?): String? =
                    if (initiatorPackage == WEREAD_PKG) resolvedTargetByShell else null
            }
            var clicks = 0
            val custodian = DisplayCustodian(
                runtime = runtimeWithShellTarget,
                classifier = classifier(),
                selfPackage = SELF_PKG,
                launcherPackages = setOf(LAUNCHER_PKG),
                appAccess = policy,
                jumpConfirmProfiles = profileStore,
                hostingEnabled = { true },
                isDeviceCircuitBroken = { false },
                onCircuitBreak = {},
                targetAppProvider = { WEREAD_PKG to 20 },
                clock = { 160_000L + testScheduler.currentTime },
                parentScope = backgroundScope,
            )

            custodian.start()
            custodian.onTargetObserved(WEREAD_PKG, taskId = 20)
            custodian.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
            runCurrent()

            fakeRt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
                jumpConfirmWindow(
                    taskId = 49061,
                    titleText = "“微信读书”想要打开“微信读书”小程序",
                    clickAction = {
                        clicks++
                        fakeRt.windowsByDisplay[Display.DEFAULT_DISPLAY] = emptyList()
                        true
                    },
                ),
            )
            fakeRt.emitArrival(
                arrival(
                    taskId = 49061,
                    pkg = SYSTEM_GUARD_PKG,
                    originUid = SYSTEM_UID,
                    originPkg = "android",
                    managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                    at = 160_020L,
                ),
            )
            val firstSpill = custodian.drainAndTakeLatestSpill()
            assertEquals(
                "scheme1 click failed: firstSpill=$firstSpill pending=${custodian.interstitialState.value}",
                1,
                clicks,
            )
            assertEquals(JumpConfirmOutcome.AUTO_ALLOWED, firstSpill?.opened?.jumpConfirm?.outcome)
            // 方案 1 已经明确解析过，不向方案 2 注入冗余学习规则
            assertTrue(profileStore.current().profiles.isEmpty())

            // 2. 方案 1 失配（返回 null）-> 首次退回人工确认；用户放行且 R2 拉回 com.tencent.mm 后，方案 2 自动记住规则
            resolvedTargetByShell = null
            advanceTimeBy(15_000L)
            custodian.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
            fakeRt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
                jumpConfirmWindow(
                    taskId = 49064,
                    titleText = "“微信读书”想要打开“微信读书”小程序",
                    clickAction = {
                        clicks++
                        fakeRt.windowsByDisplay[Display.DEFAULT_DISPLAY] = emptyList()
                        true
                    },
                ),
            )
            fakeRt.emitArrival(
                arrival(
                    taskId = 49064,
                    pkg = SYSTEM_GUARD_PKG,
                    originUid = SYSTEM_UID,
                    originPkg = "android",
                    managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                    at = 160_000L + testScheduler.currentTime,
                ),
            )
            assertEquals(1, clicks)
            assertEquals(JumpConfirmOutcome.WAITING_USER, custodian.drainAndTakeLatestSpill()?.opened?.jumpConfirm?.outcome)

            val waitDeferred = async { custodian.awaitInterstitial() }
            runCurrent()
            fakeRt.windowsByDisplay[Display.DEFAULT_DISPLAY] = emptyList()
            fakeRt.emitVanished(taskId = 49064, lastDisplayId = Display.DEFAULT_DISPLAY, atMs = 160_000L + testScheduler.currentTime)
            fakeRt.emitArrival(
                arrival(
                    taskId = 49065,
                    pkg = INITIATOR_PKG,
                    originUid = INITIATOR_UID,
                    originPkg = INITIATOR_PKG,
                    managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                    at = 160_000L + testScheduler.currentTime + 40L,
                ),
            )
            waitDeferred.await()

            assertEquals(1, profileStore.current().profiles.size)
            assertEquals(INITIATOR_PKG, profileStore.current().profiles.single().targetPackage)

            // 3. 再次出现相同“微信读书小程序”弹窗（方案 1 仍为 null）-> 命中方案 2 记住的映射，自动点击！
            advanceTimeBy(15_000L)
            custodian.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
            fakeRt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
                jumpConfirmWindow(
                    taskId = 49066,
                    titleText = "“微信读书”想要打开“微信读书”小程序",
                    clickAction = {
                        clicks++
                        fakeRt.windowsByDisplay[Display.DEFAULT_DISPLAY] = emptyList()
                        true
                    },
                ),
            )
            fakeRt.emitArrival(
                arrival(
                    taskId = 49066,
                    pkg = SYSTEM_GUARD_PKG,
                    originUid = SYSTEM_UID,
                    originPkg = "android",
                    managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                    at = 160_000L + testScheduler.currentTime,
                ),
            )
            val scheme2Spill = custodian.drainAndTakeLatestSpill()
            assertEquals(
                "scheme2 click failed: spill=$scheme2Spill pending=${custodian.interstitialState.value}",
                2,
                clicks,
            )
            assertEquals(JumpConfirmOutcome.AUTO_ALLOWED, scheme2Spill?.opened?.jumpConfirm?.outcome)

            // 4. 若用户已在「应用访问」禁用目标应用 com.tencent.mm，即便方案 2 命中也绝不越权放行
            blockedApps = setOf(INITIATOR_PKG)
            advanceTimeBy(15_000L)
            custodian.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
            fakeRt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
                jumpConfirmWindow(
                    taskId = 49067,
                    titleText = "“微信读书”想要打开“微信读书”小程序",
                    clickAction = { clicks++; true },
                ),
            )
            fakeRt.emitArrival(
                arrival(
                    taskId = 49067,
                    pkg = SYSTEM_GUARD_PKG,
                    originUid = SYSTEM_UID,
                    originPkg = "android",
                    managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                    at = 160_000L + testScheduler.currentTime,
                ),
            )
            val blockedSpill = custodian.drainAndTakeLatestSpill()
            assertEquals(2, clicks)
            assertEquals(JumpConfirmDegradeReason.TARGET_BLOCKED, blockedSpill?.opened?.jumpConfirm?.degradeReason)
            assertEquals(JumpConfirmDegradeReason.TARGET_BLOCKED, custodian.interstitialState.value?.degradeReason)
            custodian.close()
        } finally {
            profilesFile.delete()
        }
    }

    @Test
    fun unpromptedCrossAppDirectJumpFromVirtualDisplayPullsExistingBackgroundTaskBackAndPreservesMainTop() = runTest {
        val rt = FakeRuntime().apply {
            taskPackages[20] = WEREAD_PKG
            taskDisplays[49336] = Display.DEFAULT_DISPLAY
            taskPackages[49336] = INITIATOR_PKG
            topTaskByDisplay[Display.DEFAULT_DISPLAY] = 1
        }
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { false },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            targetAppProvider = { WEREAD_PKG to 20 },
            clock = { 180_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )

        custodian.start()
        custodian.onTargetObserved(WEREAD_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
        runCurrent()

        // 虚拟屏内广告跳转直接通过 OpenSDK 唤醒主屏后台已有微信任务 49336，未弹系统授权窗；
        // 且 LaunchOriginResolver 仅看见微信自启动（origin = com.tencent.mm/10123）
        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            DisplayCustodian.InspectedWindow(
                InterstitialClassifier.WindowInspection(
                    displayId = Display.DEFAULT_DISPLAY,
                    taskId = 49336,
                    windowId = 1901,
                    packageName = INITIATOR_PKG,
                    componentName = "com.tencent.mm/.plugin.base.stub.WXBizEntryActivity",
                    isDialogWindow = false,
                    texts = listOf("微信", "通讯录", "发现"),
                ),
            ) { false },
        )
        rt.emitArrival(
            arrival(
                taskId = 49336,
                pkg = INITIATOR_PKG,
                displayId = Display.DEFAULT_DISPLAY,
                originUid = INITIATOR_UID,
                originPkg = INITIATOR_PKG,
                sameTask = false,
                fromDisplay = Display.DEFAULT_DISPLAY,
                managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                at = 180_050L,
            ),
        )
        val firstSpill = custodian.drainAndTakeLatestSpill()
        assertEquals(listOf(49336 to VD_ID), rt.moveCalls.toList())
        assertTrue("Unprompted direct jump must be pulled back to VD", firstSpill?.opened?.pulledBack == true)
        assertEquals(INITIATOR_PKG, firstSpill?.opened?.packageName)
        assertNull("Must not park user interstitial when no dialog appeared", custodian.interstitialState.value)

        // 紧接着同一任务若再次从主屏弹出同任务切换事件（sameTask=true），也不得误判主屏 InUse（WAS_MAIN_TOP_TASK），仍继续回捞；
        // 最后任务在虚拟屏稳定后不再重复迁移
        rt.emitArrival(
            arrival(
                taskId = 49336,
                pkg = INITIATOR_PKG,
                displayId = Display.DEFAULT_DISPLAY,
                originUid = INITIATOR_UID,
                originPkg = INITIATOR_PKG,
                sameTask = true,
                fromDisplay = Display.DEFAULT_DISPLAY,
                managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                at = 180_120L,
            ),
        )
        val secondSpill = custodian.drainAndTakeLatestSpill()
        assertTrue("Re-bounced sameTask arrival must still be pulled back, never R1 InUse", secondSpill?.opened?.pulledBack == true)
        assertEquals(2, rt.moveCalls.size)

        rt.emitArrival(
            arrival(
                taskId = 49336,
                pkg = INITIATOR_PKG,
                displayId = VD_ID,
                originUid = INITIATOR_UID,
                originPkg = INITIATOR_PKG,
                sameTask = true,
                fromDisplay = VD_ID,
                managedDisplay = VD_ID,
                at = 180_160L,
            ),
        )
        custodian.drainAndTakeLatestSpill()
        assertEquals(2, rt.moveCalls.size)
        custodian.close()
    }

    @Test
    fun switchOnJumpConfirmMovesDialogToVdAndReturnsToMainOnlyWhenNeedingHuman() = runTest {
        // Case A：开关开启 + 已知目标 -> 先移入虚拟屏托管再自动代点，不移回主屏
        val rtA = FakeRuntime()
        val eventsA = mutableListOf<RunEvent.JumpConfirm>()
        var clickedOnVd = false
        val custodianA = DisplayCustodian(
            runtime = rtA,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            recordEvent = { if (it is RunEvent.JumpConfirm) eventsA += it },
            hostingEnabled = { true },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            allowUserInteraction = true,
            targetAppProvider = { INITIATOR_PKG to 20 },
            clock = { 190_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )
        custodianA.start()
        custodianA.onTargetObserved(INITIATOR_PKG, taskId = 20)
        custodianA.onAgentActionStarting(expectedTargetPkg = TARGET_PKG, beforePkg = INITIATOR_PKG)
        runCurrent()

        rtA.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(
                taskId = 601,
                titleText = "“微信”想要打开“淘宝”",
                clickAction = {
                    clickedOnVd = rtA.taskDisplays[601] == VD_ID
                    rtA.windowsByDisplay[Display.DEFAULT_DISPLAY] = emptyList()
                    rtA.windowsByDisplay[VD_ID] = emptyList()
                    true
                },
            ),
        )
        rtA.emitArrival(arrival(taskId = 601, pkg = SYSTEM_GUARD_PKG, at = 190_050L))
        custodianA.drainAndTakeLatestSpill()
        assertTrue("Dialog task must be moved to VD before auto-clicking", clickedOnVd)
        assertTrue(rtA.moveCalls.contains(601 to VD_ID))
        assertFalse(rtA.moveCalls.contains(601 to Display.DEFAULT_DISPLAY))
        assertEquals(1, eventsA.size)
        assertEquals("auto_allowed", eventsA.single().result)
        assertEquals(VD_ID, eventsA.single().displayId)
        custodianA.close()

        // Case B：开关开启 + 未知目标 + allowUserInteraction=true -> 先进 VD，因需人工介入再移回主屏
        val rtB = FakeRuntime().apply { taskPackages[20] = WEREAD_PKG }
        val custodianB = DisplayCustodian(
            runtime = rtB,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { true },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            allowUserInteraction = true,
            targetAppProvider = { WEREAD_PKG to 20 },
            clock = { 200_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )
        custodianB.start()
        custodianB.onTargetObserved(WEREAD_PKG, taskId = 20)
        custodianB.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
        runCurrent()

        rtB.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(
                taskId = 602,
                titleText = "“微信读书”想要打开“未知应用”",
            ),
        )
        rtB.emitArrival(
            arrival(
                taskId = 602,
                pkg = SYSTEM_GUARD_PKG,
                originUid = WEREAD_UID,
                originPkg = WEREAD_PKG,
                managedDisplay = VD_ID,
                at = 200_050L,
            ),
        )
        custodianB.drainAndTakeLatestSpill()
        assertEquals(listOf(602 to VD_ID, 602 to Display.DEFAULT_DISPLAY), rtB.moveCalls.toList())
        assertEquals(Display.DEFAULT_DISPLAY, custodianB.interstitialState.value?.displayId)
        assertEquals(false, custodianB.interstitialState.value?.onVirtualDisplay)
        custodianB.close()

        // Case C：开关开启 + 未知目标 + allowUserInteraction=false -> 留在 VD 不移回主屏
        val rtC = FakeRuntime().apply { taskPackages[20] = WEREAD_PKG }
        val custodianC = DisplayCustodian(
            runtime = rtC,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { true },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            allowUserInteraction = false,
            targetAppProvider = { WEREAD_PKG to 20 },
            clock = { 210_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )
        custodianC.start()
        custodianC.onTargetObserved(WEREAD_PKG, taskId = 20)
        custodianC.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
        runCurrent()

        rtC.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            jumpConfirmWindow(
                taskId = 603,
                titleText = "“微信读书”想要打开“未知应用”",
            ),
        )
        rtC.emitArrival(
            arrival(
                taskId = 603,
                pkg = SYSTEM_GUARD_PKG,
                originUid = WEREAD_UID,
                originPkg = WEREAD_PKG,
                managedDisplay = VD_ID,
                at = 210_050L,
            ),
        )
        custodianC.drainAndTakeLatestSpill()
        assertEquals(listOf(603 to VD_ID), rtC.moveCalls.toList())
        custodianC.close()
    }

    @Test
    fun wxBizEntryTranslucentBridgeIsPulledToVdEvenWhenExcludeFromRecentsAndUnreadable() = runTest {
        val rt = FakeRuntime().apply {
            taskPackages[20] = WEREAD_PKG
            taskDisplays[701] = Display.DEFAULT_DISPLAY
            taskPackages[701] = INITIATOR_PKG
            topTaskByDisplay[Display.DEFAULT_DISPLAY] = 1
        }
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { true },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            targetAppProvider = { WEREAD_PKG to 20 },
            clock = { 220_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )
        custodian.start()
        custodian.onTargetObserved(WEREAD_PKG, taskId = 20)
        custodian.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
        runCurrent()

        rt.windowsByDisplay[Display.DEFAULT_DISPLAY] = listOf(
            DisplayCustodian.InspectedWindow(
                InterstitialClassifier.WindowInspection(
                    displayId = Display.DEFAULT_DISPLAY,
                    taskId = 701,
                    windowId = 7010,
                    packageName = INITIATOR_PKG,
                    componentName = "com.tencent.mm/.plugin.base.stub.WXBizEntryActivity",
                    isDialogWindow = false,
                    isExcludeFromRecents = true,
                    readable = false,
                    texts = emptyList(),
                ),
            ) { false },
        )
        rt.emitArrival(
            arrival(
                taskId = 701,
                pkg = INITIATOR_PKG,
                displayId = Display.DEFAULT_DISPLAY,
                originUid = INITIATOR_UID,
                originPkg = INITIATOR_PKG,
                sameTask = false,
                fromDisplay = Display.DEFAULT_DISPLAY,
                managedDisplay = CustodyArrival.NO_MANAGED_DISPLAY,
                at = 220_050L,
            ),
        )
        val spill = custodian.drainAndTakeLatestSpill()
        assertNull("Unreadable EXCLUDE_FROM_RECENTS bridge must not park NeedsUser", custodian.interstitialState.value)
        assertTrue("Bridge task must be pulled back to VD", spill?.opened?.pulledBack == true)
        assertEquals(listOf(701 to VD_ID), rt.moveCalls.toList())
        custodian.close()
    }

    @Test
    fun vanishedBridgeTaskAfterFocusingExistingVdTaskStillReportsPulledBackTrue() = runTest {
        val baseRt = FakeRuntime().apply {
            taskPackages[20] = WEREAD_PKG
            taskPackages[49389] = INITIATOR_PKG
            taskDisplays[49389] = VD_ID
            topTaskByDisplay[VD_ID] = 49389
            topTaskByDisplay[Display.DEFAULT_DISPLAY] = 1
        }
        val rt = object : DisplayCustodian.Runtime by baseRt {
            override suspend fun inspectDisplayWindows(
                displayId: Int,
                componentHint: String?,
                checkSecureOnUnreadable: Boolean,
            ): List<DisplayCustodian.InspectedWindow> {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    baseRt.taskDisplays.remove(49390)
                    baseRt.topTaskByDisplay[Display.DEFAULT_DISPLAY] = 1
                }
                return baseRt.inspectDisplayWindows(displayId, componentHint, checkSecureOnUnreadable)
            }
        }
        val custodian = DisplayCustodian(
            runtime = rt,
            classifier = classifier(),
            selfPackage = SELF_PKG,
            launcherPackages = setOf(LAUNCHER_PKG),
            appAccess = AppAccessPolicy.ALLOW,
            hostingEnabled = { true },
            isDeviceCircuitBroken = { false },
            onCircuitBreak = {},
            targetAppProvider = { WEREAD_PKG to 20 },
            clock = { 230_000L + testScheduler.currentTime },
            parentScope = backgroundScope,
        )
        custodian.start()
        custodian.onTargetObserved(WEREAD_PKG, taskId = 20)
        custodian.onTargetObserved(INITIATOR_PKG, taskId = 49389)
        custodian.onAgentActionStarting(expectedTargetPkg = null, beforePkg = WEREAD_PKG)
        runCurrent()

        baseRt.emitArrival(
            arrival(
                taskId = 49390,
                pkg = INITIATOR_PKG,
                displayId = Display.DEFAULT_DISPLAY,
                originUid = INITIATOR_UID,
                originPkg = INITIATOR_PKG,
                managedDisplay = VD_ID,
                at = 230_050L,
            ),
        )
        val spill = custodian.drainAndTakeLatestSpill()
        assertEquals(INITIATOR_PKG, spill?.opened?.packageName)
        assertTrue("Vanished bridge task with target already on VD must report pulledBack=true", spill?.opened?.pulledBack == true)
        assertFalse("Must not report onUserScreen=true when VD already holds target", spill?.opened?.onUserScreen == true)
        assertFalse("Should not invoke moveTask on vanished task 49390", baseRt.moveCalls.contains(49390 to VD_ID))
        custodian.close()
    }
}
