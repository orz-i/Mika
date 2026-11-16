package com.wangzan101.mika.cap.phone.a11y

import android.content.Intent
import android.util.DisplayMetrics
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.SETTINGS
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.pollUntil
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.sh
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.core.custody.JumpConfirmDegradeReason
import com.wangzan101.mika.core.custody.JumpConfirmOutcome
import com.wangzan101.mika.core.custody.InterstitialKind
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.settings.FeatureFlags
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 跳转授权托管真机端到端测试：
 * - Activity 型确认框：开关关闭手动等待/拒绝/>10s 延迟确认按 R2 回捞、开关开启 9 护栏全过原地 `ACTION_CLICK`；
 * - 悬浮窗型（`SYSTEM_ALERT_WINDOW` / `TYPE_APPLICATION_OVERLAY`）确认框；
 * - 桌面导航防误捞、`InUse` 不挪、无近期动作自发跳转不代点、`ACTION_CLICK` 1s 未消失熔断降级。
 */
@RunWith(AndroidJUnit4::class)
class JumpConfirmSpillDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val flags = FeatureFlags.get(context)
    private val events = CopyOnWriteArrayList<RunEvent>()
    private val shell = DeviceSupport.shell()
    private val target = InstrumentationRegistry.getArguments().getString("spillTarget") ?: SETTINGS

    private var priorHosting = false
    private var priorAutoBlocked = false
    private var activeAgent: DeviceSupport.Agent? = null
    private var preExistingTargetTaskIds: Set<Int> = emptySet()

    private fun recentTaskIdsFor(pkg: String): List<Int> =
        sh("dumpsys activity recents").lines().mapNotNull { line ->
            val t = line.trim()
            if (!t.startsWith("* Recent") || " A=" !in t) return@mapNotNull null
            if (!t.substringAfter(" A=").substringAfter(':').startsWith(pkg)) return@mapNotNull null
            Regex("""Task\{[^#]*#(\d+)""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

    private fun createAgent(): DeviceSupport.Agent {
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            dm.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        val d = AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, dm)
        val screen = AndroidScreen(
            a11y = DeviceSupport.service(),
            shell = shell,
            display = DeviceSupport.ready(d),
            flags = flags,
            appVersionCode = BuildConfig.VERSION_CODE,
            allowUserInteraction = true,
            recordEvent = { events += it },
            isSystemPackageOverride = { pkg -> pkg == TEST_PACKAGE },
        )
        return DeviceSupport.Agent(d, screen).also { activeAgent = it }
    }

    @Before
    fun saveFlags() {
        priorHosting = flags.jumpConfirmHosting()
        priorAutoBlocked = flags.jumpConfirmAutoBlocked(BuildConfig.VERSION_CODE)
        flags.clearJumpConfirmAutoBlocked()
        flags.setJumpConfirmHosting(false)
        events.clear()
        preExistingTargetTaskIds = recentTaskIdsFor(target).toSet()
    }

    @After
    fun tearDown() {
        runCatching {
            sh("am startservice -n $TEST_PACKAGE/${JumpConfirmOverlayService::class.java.name} --es ${JumpConfirmOverlayService.EXTRA_ACTION} ${JumpConfirmOverlayService.ACTION_DISMISS}")
        }
        val main = shell.topTaskId(Display.DEFAULT_DISPLAY)
        if (main >= 0 && shell.packageOfTask(main) == TEST_PACKAGE) sh("input keyevent 4")
        activeAgent?.let { ag ->
            val nowMain = shell.topTaskId(Display.DEFAULT_DISPLAY)
            if (nowMain >= 0 && shell.packageOfTask(nowMain) == target) {
                shell.moveTask(nowMain, ag.display.displayId)
            }
            ag.release()
            activeAgent = null
        }
        recentTaskIdsFor(TEST_PACKAGE).forEach { id -> sh("am stack remove $id") }
        recentTaskIdsFor(target)
            .filter { it !in preExistingTargetTaskIds }
            .forEach { id -> sh("am stack remove $id") }
        flags.setJumpConfirmHosting(priorHosting)
        if (priorAutoBlocked) flags.markJumpConfirmAutoBlocked(BuildConfig.VERSION_CODE)
        else flags.clearJumpConfirmAutoBlocked()
    }

    private fun assumeNoExistingTargetTask() {
        if (InstrumentationRegistry.getArguments().getString("allowExisting") == "true") return
        assumeFalse(
            "$target already has a task",
            sh("dumpsys activity recents").lines().any { l ->
                val t = l.trim()
                t.startsWith("* Recent") && " A=" in t && t.substringAfter(" A=").substringAfter(':').startsWith(target)
            },
        )
    }

    private fun findMainButton(label: String): UiElement? {
        val service = DeviceSupport.service()
        val display = activeAgent!!.display
        val windows = ScreenReader.contentWindows(service.windowsOn(Display.DEFAULT_DISPLAY))
            .filter { it.root?.packageName?.toString() == TEST_PACKAGE }
        val entries = windows.flatMap { w ->
            ScreenReader.read(listOf(w), display.width, display.height).entries
        }
        return entries.map { it.element }.firstOrNull { it.clickable && it.name.equals(label, ignoreCase = true) }
    }

    private suspend fun openSpillPage(
        screen: AndroidScreen,
        vd: Int,
        buttonName: String,
        ignoreClick: Boolean = false,
        overlay: Boolean = false,
    ): com.wangzan101.mika.core.uistate.ScreenSnapshot {
        screen.ensureDisplay()
        val extra = buildString {
            append("--es ${SpillTestActivity.EXTRA_TARGET} $target ")
            if (overlay) {
                append("--ez ${SpillTestActivity.EXTRA_OVERLAY} true ")
            } else {
                append("--es ${SpillTestActivity.EXTRA_DIALOG} ${JumpConfirmDialogTestActivity::class.java.name} ")
            }
            if (ignoreClick) {
                append("--ez ${JumpConfirmDialogTestActivity.EXTRA_IGNORE_CLICK} true")
            }
        }
        sh("am start --display $vd -n $TEST_PACKAGE/${SpillTestActivity::class.java.name} $extra")
        val snap = pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { s -> s.elements.any { it.name == buttonName } }
        }
        assertNotNull("spill page with '$buttonName' did not appear on VD", snap)
        return snap!!
    }

    @Test
    fun switchOffActivityConfirmWaitsAndPullsBackTargetEvenAfter10sDelay() = runBlocking<Unit> {
        assumeNoExistingTargetTask()
        flags.setJumpConfirmHosting(false)
        val agent = createAgent()
        val screen = agent.screen
        screen.ensureDisplay()
        val vd = agent.display.displayId

        val s = openSpillPage(screen, vd, buttonName = "open dialog")
        val button = s.elements.first { it.name == "open dialog" }
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val res = screen.tapRef(button.ref, s.snapshotId)
        assertTrue("expected ActionResult.Ok, got $res", res is ActionResult.Ok)

        val openBtn = pollUntil(5_000) { findMainButton("Open") }
        assertNotNull("JumpConfirmDialogTestActivity 'Open' button not found on main screen", openBtn)
        val pending = pollUntil(3_000) { screen.custodyState.value }
        assertNotNull("Switch OFF must park custodyState", pending)
        assertEquals(InterstitialKind.JUMP_CONFIRM, pending!!.kind)

        val waitJob = async { screen.awaitInterstitial() }
        // 故意等待 >10s，验证彻底取消旧 6s/10s 超时限制后仍能挂起并回捞
        delay(11_000L)
        assertNotNull("custodyState must remain active past 10s", screen.custodyState.value)

        sh("input tap ${openBtn!!.centerX} ${openBtn.centerY}")
        val settled = waitJob.await()
        assertTrue("expected Settled after user tap, got $settled", settled is DisplayCustodian.WaitResult.Settled)
        assertEquals(
            "target app must be pulled back onto Mika VD",
            target,
            pollUntil(4_000) { shell.packageOfTask(shell.topTaskId(vd)).takeIf { it == target } },
        )
        assertEquals(
            "main screen should return to previous task",
            mainBefore,
            pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == mainBefore } },
        )
    }

    @Test
    fun switchOffActivityConfirmUserDenyReportsDeniedWithoutMovingTasks() = runBlocking<Unit> {
        flags.setJumpConfirmHosting(false)
        val agent = createAgent()
        val screen = agent.screen
        screen.ensureDisplay()
        val vd = agent.display.displayId

        val s = openSpillPage(screen, vd, buttonName = "open dialog")
        val button = s.elements.first { it.name == "open dialog" }
        screen.tapRef(button.ref, s.snapshotId)

        val cancelBtn = pollUntil(5_000) { findMainButton("Cancel") }
        assertNotNull("'Cancel' button not found on main screen", cancelBtn)
        assertNotNull(pollUntil(3_000) { screen.custodyState.value })

        val waitJob = async { screen.awaitInterstitial() }
        delay(400L)
        sh("input tap ${cancelBtn!!.centerX} ${cancelBtn.centerY}")
        val settled = waitJob.await() as DisplayCustodian.WaitResult.Settled
        assertEquals(JumpConfirmOutcome.DENIED, settled.report?.opened?.jumpConfirm?.outcome)
        assertEquals(TEST_PACKAGE, shell.packageOfTask(shell.topTaskId(vd)))
    }

    @Test
    fun switchOnActivityConfirmAutoClicksViaA11yLogsEventAndPullsBackTarget() = runBlocking<Unit> {
        assumeNoExistingTargetTask()
        flags.setJumpConfirmHosting(true)
        val agent = createAgent()
        val screen = agent.screen
        screen.ensureDisplay()
        val vd = agent.display.displayId

        val s = openSpillPage(screen, vd, buttonName = "open dialog")
        val button = s.elements.first { it.name == "open dialog" }
        val mainBefore = shell.topTaskId(Display.DEFAULT_DISPLAY)
        val res = screen.tapRef(button.ref, s.snapshotId)
        assertTrue("expected Ok, got $res", res is ActionResult.Ok)

        // 原地 ACTION_CLICK 自动允许后，Settings 会落地并被 R2 拉回虚拟屏
        val pulledTarget = pollUntil(6_000) {
            shell.packageOfTask(shell.topTaskId(vd)).takeIf { it == target }
        }
        assertEquals(target, pulledTarget)
        assertNull("custodyState must clear after auto-allow", screen.custodyState.value)
        assertTrue(
            "expected JumpConfirm RunEvent with auto_allowed",
            events.filterIsInstance<RunEvent.JumpConfirm>().any { it.result == "auto_allowed" && !it.degraded },
        )
        assertEquals(
            "main screen should return to previous task",
            mainBefore,
            pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == mainBefore } },
        )
    }

    @Test
    fun systemAlertWindowOverlayConfirmAutoClicksAndPullsTargetBack() = runBlocking<Unit> {
        assumeNoExistingTargetTask()
        sh("appops set $TEST_PACKAGE SYSTEM_ALERT_WINDOW allow")
        flags.setJumpConfirmHosting(true)
        val agent = createAgent()
        val screen = agent.screen
        screen.ensureDisplay()
        val vd = agent.display.displayId

        val s = openSpillPage(screen, vd, buttonName = "open overlay", overlay = true)
        val button = s.elements.first { it.name == "open overlay" }
        val res = screen.tapRef(button.ref, s.snapshotId)
        assertTrue("expected Ok, got $res", res is ActionResult.Ok)

        val pulledTarget = pollUntil(6_000) {
            shell.packageOfTask(shell.topTaskId(vd)).takeIf { it == target }
        }
        assertEquals("Target launched from SYSTEM_ALERT_WINDOW overlay must be pulled back", target, pulledTarget)
        assertTrue(
            "expected JumpConfirm RunEvent auto_allowed for overlay",
            events.filterIsInstance<RunEvent.JumpConfirm>().any { it.result == "auto_allowed" },
        )
    }

    @Test
    fun inUseMainScreenTaskIsNeverMoved() = runBlocking<Unit> {
        assumeNoExistingTargetTask()
        val agent = createAgent()
        val screen = agent.screen
        screen.ensureDisplay()
        val vd = agent.display.displayId

        // 用户已在主屏打开 PlainTestActivity，随后 Agent 在 VD 动作也不得把主屏用户已有页面挪走
        sh("am start --display 0 -n $TEST_PACKAGE/${PlainTestActivity::class.java.name}")
        val userTask = pollUntil(5_000) {
            val tid = shell.topTaskId(Display.DEFAULT_DISPLAY)
            if (tid >= 0 && shell.topActivity(Display.DEFAULT_DISPLAY)?.contains("PlainTestActivity") == true) tid else null
        }
        assertNotNull("PlainTestActivity did not come to main screen", userTask)

        sh("am start --display $vd -n $TEST_PACKAGE/${SpillTestActivity::class.java.name}")
        pollUntil(5_000) { runBlocking { screen.snapshot() }.elements.any { it.name == "open elsewhere" }.takeIf { it } }
        screen.snapshot()
        assertEquals(
            "Existing main-screen user task must never be moved off main screen",
            userTask,
            shell.topTaskId(Display.DEFAULT_DISPLAY),
        )
    }

    @Test
    fun spontaneousIdleJumpConfirmIsNeverAutoClickedEvenWhenHostingEnabled() = runBlocking<Unit> {
        flags.setJumpConfirmHosting(true)
        val agent = createAgent()
        val screen = agent.screen
        screen.ensureDisplay()
        val vd = agent.display.displayId

        openSpillPage(screen, vd, buttonName = "open dialog")
        // 超过 3s 无任何 Agent 操作后再从主屏自发拉起确认框
        delay(3_600L)
        sh("am start --display 0 -n $TEST_PACKAGE/${JumpConfirmDialogTestActivity::class.java.name} --es ${JumpConfirmDialogTestActivity.EXTRA_TARGET} $target")
        val openBtn = pollUntil(5_000) { findMainButton("Open") }
        assertNotNull("spontaneous dialog did not open", openBtn)
        delay(1_500L)
        assertNotNull("spontaneous jump confirm must NOT be auto-clicked", findMainButton("Open"))
        assertTrue(
            "spontaneous dialog must not record auto_allowed",
            events.filterIsInstance<RunEvent.JumpConfirm>().none { it.result == "auto_allowed" },
        )
    }

    @Test
    fun launcherNavigationOnMainScreenDoesNotMisPullUserApp() = runBlocking<Unit> {
        assumeNoExistingTargetTask()
        val pm = context.packageManager
        val homeApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        assertTrue("no home handler", homeApps.isNotEmpty())

        val agent = createAgent()
        val screen = agent.screen
        screen.ensureDisplay()
        val vd = agent.display.displayId
        openSpillPage(screen, vd, buttonName = "open dialog")

        sh("am start --display 0 -a ${Intent.ACTION_MAIN} -c ${Intent.CATEGORY_HOME}")
        assertNotNull(
            "home did not come to main screen",
            pollUntil(3_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { shell.packageOfTask(it) in homeApps } },
        )
        val launch = pm.getLaunchIntentForPackage(target)
        assertNotNull("no launcher activity for $target", launch)
        sh("am start --display 0 -n ${launch!!.component!!.flattenToShortString()}")
        assertNotNull(
            "target did not open on main screen",
            pollUntil(4_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { shell.packageOfTask(it) == target } },
        )
        delay(800L)
        screen.snapshot()
        assertEquals("user-opened app must stay on main screen", target, shell.packageOfTask(shell.topTaskId(Display.DEFAULT_DISPLAY)))
        assertEquals("VD task must remain intact", TEST_PACKAGE, shell.packageOfTask(shell.topTaskId(vd)))
    }

    @Test
    fun ignoredA11yClickTrips1sCircuitBreakerAndDegradesToUser() = runBlocking<Unit> {
        flags.setJumpConfirmHosting(true)
        val agent = createAgent()
        val screen = agent.screen
        screen.ensureDisplay()
        val vd = agent.display.displayId

        val s = openSpillPage(screen, vd, buttonName = "open dialog", ignoreClick = true)
        val button = s.elements.first { it.name == "open dialog" }
        screen.tapRef(button.ref, s.snapshotId)

        val pending = pollUntil(6_000) { screen.custodyState.value }
        assertNotNull("ignored ACTION_CLICK must fall back to user wait", pending)
        assertEquals(JumpConfirmDegradeReason.DEVICE_CIRCUIT_BROKEN, pending!!.degradeReason)
        assertTrue("FeatureFlags circuit breaker must be marked", flags.jumpConfirmAutoBlocked(BuildConfig.VERSION_CODE))
        assertTrue(
            "expected degraded not_dismissed_1s event",
            events.filterIsInstance<RunEvent.JumpConfirm>().any { it.degraded && it.reason == "not_dismissed_1s" },
        )
    }
}
