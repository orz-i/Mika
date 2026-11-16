package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityEvent
import com.wangzan101.mika.core.custody.Arrival
import com.wangzan101.mika.core.custody.AttributionContext
import com.wangzan101.mika.core.custody.AttributionResult
import com.wangzan101.mika.core.custody.CustodyAttribution
import com.wangzan101.mika.core.custody.IgnoreReason
import com.wangzan101.mika.core.custody.InUseReason
import com.wangzan101.mika.core.custody.Interstitial
import com.wangzan101.mika.core.custody.InterstitialActor
import com.wangzan101.mika.core.custody.InterstitialChoice
import com.wangzan101.mika.core.custody.InterstitialLedger
import com.wangzan101.mika.core.custody.LaunchOrigin
import com.wangzan101.mika.core.custody.OriginConfidence
import com.wangzan101.mika.core.custody.SpillReason
import com.wangzan101.mika.core.custody.UserIntentEvidence
import com.wangzan101.mika.core.custody.UserIntentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨屏归因规则 R0–R6 与 [MainScreenSpill] 轻量辅助函数单元测试。
 */
class MainScreenSpillTest {
    private val since = 10_000L
    private val vd = 5
    private val launchers = setOf("com.home")
    private val selfPkg = "com.wangzan101.mika"
    private val managedUid = 10123

    private fun arrival(
        taskId: Int = 7,
        pkg: String? = "com.chat",
        at: Long = since + 500L,
        fromDisplay: Int = MainScreenSpill.NEW,
        sameTask: Boolean = false,
        standardTask: Boolean = true,
        displayId: Int = MainScreenSpill.MAIN,
        userId: Int = 0,
    ) = Arrival(
        taskId = taskId,
        pkg = pkg,
        userId = userId,
        displayId = displayId,
        fromDisplay = fromDisplay,
        newTask = fromDisplay == MainScreenSpill.NEW,
        sameTask = sameTask,
        at = at,
        standardTask = standardTask,
    )

    private fun managedOrigin(pkg: String = "com.caller") = LaunchOrigin(
        uid = managedUid,
        pkg = pkg,
        confidence = OriginConfidence.HIGH,
    )

    private fun ctx(
        previousMainTopTaskId: Int? = 3,
        existingMainUserTaskIds: Set<Int> = setOf(3),
        ledger: InterstitialLedger = InterstitialLedger(),
        userIntents: List<UserIntentEvidence> = emptyList(),
        recentAgentAction: Boolean = true,
        lastAgentActionAtMs: Long? = since,
        nowMs: Long = since + 500L,
    ) = AttributionContext(
        selfPackage = selfPkg,
        launcherPackages = launchers,
        managedDisplays = mapOf(vd to setOf(managedUid)),
        mainDisplayId = MainScreenSpill.MAIN,
        previousMainTopTaskId = previousMainTopTaskId,
        existingMainUserTaskIds = existingMainUserTaskIds,
        ledgerEntries = ledger.snapshot(nowMs),
        userIntents = userIntents,
        recentAgentAction = recentAgentAction,
        lastAgentActionAtMs = lastAgentActionAtMs,
    )

    @Test
    fun newTaskFromManagedUidWithoutUserIntentIsPulledBackByR3() {
        val result = CustodyAttribution.attribute(arrival(), managedOrigin(), ctx())
        assertEquals(AttributionResult.Spill(vd, SpillReason.MANAGED_ORIGIN_NO_USER_INTENT), result)
    }

    @Test
    fun backgroundTaskOnMainBroughtToFrontIsPulledBackToo() {
        val a = arrival(fromDisplay = MainScreenSpill.MAIN)
        val result = CustodyAttribution.attribute(a, managedOrigin(), ctx())
        assertEquals(AttributionResult.Spill(vd, SpillReason.MANAGED_ORIGIN_NO_USER_INTENT), result)
    }

    @Test
    fun r0IgnoresNonMainLauncherSelfAndNonStandardTasks() {
        assertEquals(
            AttributionResult.Ignore(IgnoreReason.NOT_MAIN_DISPLAY),
            CustodyAttribution.attribute(arrival(displayId = vd), managedOrigin(), ctx()),
        )
        assertEquals(
            AttributionResult.Ignore(IgnoreReason.LAUNCHER_PACKAGE),
            CustodyAttribution.attribute(arrival(pkg = "com.home"), managedOrigin(), ctx()),
        )
        assertEquals(
            AttributionResult.Ignore(IgnoreReason.SELF_PACKAGE),
            CustodyAttribution.attribute(arrival(pkg = selfPkg), managedOrigin(), ctx()),
        )
        assertEquals(
            AttributionResult.Ignore(IgnoreReason.NON_STANDARD_TASK),
            CustodyAttribution.attribute(arrival(standardTask = false), managedOrigin(), ctx()),
        )
    }

    @Test
    fun unknownOriginReportsUnattributedWithRecentAgentFlag() {
        val aRecent = arrival(at = since + 1_000L)
        assertEquals(
            AttributionResult.Unattributed(recentAgentAction = true),
            CustodyAttribution.attribute(aRecent, LaunchOrigin.UNKNOWN, ctx(recentAgentAction = false, lastAgentActionAtMs = since)),
        )
        val aStale = arrival(at = since + 10_000L)
        assertEquals(
            AttributionResult.Unattributed(recentAgentAction = false),
            CustodyAttribution.attribute(aStale, LaunchOrigin.UNKNOWN, ctx(recentAgentAction = false, lastAgentActionAtMs = since, nowMs = since + 10_000L)),
        )
    }

    @Test
    fun userIntentFromLauncherSystemUiOrRecentsWithinWindowBlocksPullbackByR4() {
        assertTrue(MainScreenSpill.isUserTap(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START))
        assertTrue(MainScreenSpill.isUserTap(AccessibilityEvent.TYPE_VIEW_CLICKED))
        assertFalse(MainScreenSpill.isUserTap(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))

        val evidence = UserIntentEvidence(source = UserIntentSource.LAUNCHER, packageName = "com.home", at = since + 400L)
        val result = CustodyAttribution.attribute(
            arrival(at = since + 900L),
            managedOrigin(),
            ctx(userIntents = listOf(evidence)),
        )
        assertEquals(AttributionResult.Ignore(IgnoreReason.USER_INTENT_EVIDENCE), result)

        // 超过 1.5s 时间窗的旧意图不阻断回捞
        val staleEvidence = UserIntentEvidence(source = UserIntentSource.SYSTEM_UI, packageName = "com.android.systemui", at = since - 2_000L)
        val spillAfterStale = CustodyAttribution.attribute(
            arrival(at = since + 900L),
            managedOrigin(),
            ctx(userIntents = listOf(staleEvidence)),
        )
        assertEquals(AttributionResult.Spill(vd, SpillReason.MANAGED_ORIGIN_NO_USER_INTENT), spillAfterStale)
    }

    @Test
    fun previousMainTopAndExistingSameTaskAreInUseByR1() {
        assertEquals(
            AttributionResult.InUse(InUseReason.WAS_MAIN_TOP_TASK),
            CustodyAttribution.attribute(arrival(taskId = 3, fromDisplay = MainScreenSpill.MAIN), managedOrigin(), ctx(previousMainTopTaskId = 3)),
        )
        assertEquals(
            AttributionResult.InUse(InUseReason.SAME_TASK_IN_EXISTING_MAIN_TASK),
            CustodyAttribution.attribute(
                arrival(taskId = 4, sameTask = true, fromDisplay = MainScreenSpill.MAIN),
                managedOrigin(),
                ctx(previousMainTopTaskId = 3, existingMainUserTaskIds = setOf(3, 4)),
            ),
        )
    }

    @Test
    fun delayedInterstitialConfirmationAndChooserFollowupArePulledBackByR2() {
        val ledger = InterstitialLedger()
        val entry = ledger.open(
            Interstitial.JumpConfirm(
                id = "jc-1",
                displayId = vd,
                taskId = 55,
                packageName = "com.oplus.securitypermission",
                componentName = null,
                openedAtMs = since,
                initiatorPackage = "com.caller",
                targetPackage = "com.chat",
                targetLabel = "Chat",
            ),
            nowMs = since,
        )
        // 用户在弹窗里拖延 15s 后才点确认：由于点击的是中间页本身，未记录 Launcher/SystemUI 意图；
        // 且 R2 在 Open 或 Resolved 10s 内都能接管由系统确认页派生的新任务
        val confirmAt = since + 15_000L
        ledger.resolve(entry.interstitial.id, InterstitialActor.USER, InterstitialChoice.ALLOW, confirmAt)
        val resolvedResult = CustodyAttribution.attribute(
            arrival(taskId = 77, pkg = "com.chat", at = confirmAt + 400L),
            LaunchOrigin(uid = 1000, pkg = "com.oplus.securitypermission", confidence = OriginConfidence.HIGH),
            ctx(ledger = ledger, nowMs = confirmAt + 400L),
        )
        assertEquals(
            AttributionResult.Spill(vd, SpillReason.INTERSTITIAL_LEDGER, ledgerEntryId = "jc-1"),
            resolvedResult,
        )
    }

    @Test
    fun needsUserAndAuthenticatorHelpersRemainConsistent() {
        assertTrue(MainScreenSpill.needsUser(passwordField = true, readable = true, secureWindow = false))
        assertTrue(MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = true))
        assertFalse(MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = false))
        assertFalse(MainScreenSpill.needsUser(passwordField = false, readable = true, secureWindow = false))

        val auth = setOf("com.example.accounts", "com.example.chat")
        assertTrue(MainScreenSpill.authenticatorUi("com.example.accounts", auth, hasLauncher = false))
        assertFalse(MainScreenSpill.authenticatorUi("com.example.chat", auth, hasLauncher = true))
        assertFalse(MainScreenSpill.authenticatorUi("com.example.helper", auth, hasLauncher = false))
    }
}
