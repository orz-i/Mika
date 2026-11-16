package com.wangzan101.mika.core.custody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustodyAttributionTest {

    private val selfPkg = "com.wangzan101.mika"
    private val launcherPkg = "com.miui.home"
    private val managedAppUid = 10201
    private val interstitialPkg = "com.miui.securitycenter"
    private val managedDisplayId = 2

    private fun baseArrival(
        taskId: Int = 50,
        pkg: String? = "com.taobao.taobao",
        displayId: Int = 0,
        newTask: Boolean = true,
        sameTask: Boolean = false,
        at: Long = 10_000L,
        standardTask: Boolean = true,
    ) = Arrival(
        taskId = taskId,
        pkg = pkg,
        userId = 0,
        uid = 10300,
        displayId = displayId,
        fromDisplay = Arrival.UNKNOWN_DISPLAY,
        newTask = newTask,
        sameTask = sameTask,
        topActivity = pkg?.let { "$it/.MainActivity" },
        at = at,
        standardTask = standardTask,
    )

    private fun baseContext(
        previousMainTopTaskId: Int? = 10,
        existingMainUserTaskIds: Set<Int> = setOf(10, 11),
        ledgerEntries: List<LedgerEntry> = emptyList(),
        userIntents: List<UserIntentEvidence> = emptyList(),
        recentAgentAction: Boolean = false,
        lastAgentActionAtMs: Long? = null,
    ) = AttributionContext(
        selfPackage = selfPkg,
        launcherPackages = setOf(launcherPkg),
        managedDisplays = mapOf(managedDisplayId to setOf(managedAppUid)),
        mainDisplayId = 0,
        previousMainTopTaskId = previousMainTopTaskId,
        existingMainUserTaskIds = existingMainUserTaskIds,
        ledgerEntries = ledgerEntries,
        userIntents = userIntents,
        recentAgentAction = recentAgentAction,
        lastAgentActionAtMs = lastAgentActionAtMs,
    )

    @Test
    fun `R0 ignores launcher self non-standard task and non-main display before R1-R5`() {
        val origin = LaunchOrigin(uid = managedAppUid, pkg = "com.bilibili", confidence = OriginConfidence.HIGH)
        val openLedger = listOf(
            LedgerEntry(
                Interstitial.JumpConfirm(
                    id = "j1",
                    displayId = managedDisplayId,
                    packageName = interstitialPkg,
                    openedAtMs = 9_500L,
                ),
            ),
        )
        val ctx = baseContext(previousMainTopTaskId = 50, ledgerEntries = openLedger)

        assertEquals(
            AttributionResult.Ignore(IgnoreReason.LAUNCHER_PACKAGE),
            CustodyAttribution.attribute(baseArrival(pkg = launcherPkg), origin, ctx),
        )
        assertEquals(
            AttributionResult.Ignore(IgnoreReason.SELF_PACKAGE),
            CustodyAttribution.attribute(baseArrival(pkg = selfPkg), origin, ctx),
        )
        assertEquals(
            AttributionResult.Ignore(IgnoreReason.NON_STANDARD_TASK),
            CustodyAttribution.attribute(baseArrival(standardTask = false), origin, ctx),
        )
        assertEquals(
            AttributionResult.Ignore(IgnoreReason.NOT_MAIN_DISPLAY),
            CustodyAttribution.attribute(baseArrival(displayId = managedDisplayId), origin, ctx),
        )
    }

    @Test
    fun `R1 returns InUse when task was previous main top or sameTask in existing user task overriding R2 and R3`() {
        val origin = LaunchOrigin(uid = managedAppUid, pkg = "com.bilibili", confidence = OriginConfidence.HIGH)
        val openLedger = listOf(
            LedgerEntry(
                Interstitial.JumpConfirm(
                    id = "j1",
                    displayId = managedDisplayId,
                    packageName = interstitialPkg,
                    openedAtMs = 9_500L,
                ),
            ),
        )

        // 到达前就是主屏顶部任务（优先级高于 R2/R3）
        val r1Top = CustodyAttribution.attribute(
            baseArrival(taskId = 10),
            origin,
            baseContext(previousMainTopTaskId = 10, ledgerEntries = openLedger),
        )
        assertEquals(AttributionResult.InUse(InUseReason.WAS_MAIN_TOP_TASK), r1Top)

        // sameTask 落入用户主屏原有任务 11（优先级高于 R2/R3）
        val r1Same = CustodyAttribution.attribute(
            baseArrival(taskId = 11, sameTask = true, newTask = false),
            origin,
            baseContext(previousMainTopTaskId = 10, existingMainUserTaskIds = setOf(10, 11), ledgerEntries = openLedger),
        )
        assertEquals(AttributionResult.InUse(InUseReason.SAME_TASK_IN_EXISTING_MAIN_TASK), r1Same)

        // sameTask=true 但 taskId 不在用户主屏原有任务中，不能误判 R1，继续命中 R3 Spill
        val notUserTask = CustodyAttribution.attribute(
            baseArrival(taskId = 99, sameTask = true, newTask = false),
            origin,
            baseContext(previousMainTopTaskId = 10, existingMainUserTaskIds = setOf(10, 11)),
        )
        assertEquals(
            AttributionResult.Spill(managedDisplayId, SpillReason.MANAGED_ORIGIN_NO_USER_INTENT),
            notUserTask,
        )
    }

    @Test
    fun `R2 attributes Spill via open or recently resolved interstitial and ignores user intent clicks`() {
        // 用例 A: Open 中间页 + origin.pkg == I.pkg（即便没有 origin.uid 且存在用户点击证据）
        val openEntry = LedgerEntry(
            Interstitial.JumpConfirm(
                id = "j-open",
                displayId = managedDisplayId,
                packageName = interstitialPkg,
                openedAtMs = 9_000L,
            ),
        )
        val userClickOnInterstitial = UserIntentEvidence(
            source = UserIntentSource.SYSTEM_UI,
            packageName = interstitialPkg,
            at = 9_800L,
        )
        val resOpen = CustodyAttribution.attribute(
            baseArrival(at = 10_000L),
            LaunchOrigin(uid = -1, pkg = interstitialPkg, confidence = OriginConfidence.MEDIUM),
            baseContext(ledgerEntries = listOf(openEntry), userIntents = listOf(userClickOnInterstitial)),
        )
        assertEquals(
            AttributionResult.Spill(managedDisplayId, SpillReason.INTERSTITIAL_LEDGER, ledgerEntryId = "j-open"),
            resOpen,
        )

        // 用例 B: Resolved 恰好在 10s 边界上 + origin.uid in managed(D)，忽略用户点击
        val resolvedEntry = LedgerEntry(
            interstitial = Interstitial.Chooser(
                id = "c-resolved",
                displayId = managedDisplayId,
                packageName = "android",
                openedAtMs = 5_000L,
            ),
            state = LedgerState.Resolved(InterstitialActor.USER, InterstitialChoice.ALLOW, at = 6_000L),
            updatedAtMs = 6_000L,
        )
        val resAt10s = CustodyAttribution.attribute(
            baseArrival(at = 16_000L),
            LaunchOrigin(uid = managedAppUid, pkg = "com.bilibili", confidence = OriginConfidence.HIGH),
            baseContext(
                ledgerEntries = listOf(resolvedEntry),
                userIntents = listOf(UserIntentEvidence(UserIntentSource.LAUNCHER, launcherPkg, at = 15_500L)),
            ),
        )
        assertEquals(
            AttributionResult.Spill(managedDisplayId, SpillReason.INTERSTITIAL_LEDGER, ledgerEntryId = "c-resolved"),
            resAt10s,
        )

        // 用例 C: Resolved 超出 10s（10_001ms），R2 不再生效，因存在近期用户点击而落入 R4 Ignore
        val resPast10s = CustodyAttribution.attribute(
            baseArrival(at = 16_001L),
            LaunchOrigin(uid = managedAppUid, pkg = "com.bilibili", confidence = OriginConfidence.HIGH),
            baseContext(
                ledgerEntries = listOf(resolvedEntry),
                userIntents = listOf(UserIntentEvidence(UserIntentSource.LAUNCHER, launcherPkg, at = 15_500L)),
            ),
        )
        assertEquals(AttributionResult.Ignore(IgnoreReason.USER_INTENT_EVIDENCE), resPast10s)
    }

    @Test
    fun `R3 and R4 split managed origin by 1_5s user intent evidence window`() {
        val origin = LaunchOrigin(uid = managedAppUid, pkg = "com.bilibili", confidence = OriginConfidence.HIGH)

        // 1.5s 之内有 SystemUI 通知点击 -> R4 Ignore
        val withinWindow = CustodyAttribution.attribute(
            baseArrival(at = 10_000L),
            origin,
            baseContext(userIntents = listOf(UserIntentEvidence(UserIntentSource.SYSTEM_UI, "com.android.systemui", at = 8_500L))),
        )
        assertEquals(AttributionResult.Ignore(IgnoreReason.USER_INTENT_EVIDENCE), withinWindow)

        // 最近任务点击在 1.5s 内 -> R4 Ignore
        val recentsIntent = CustodyAttribution.attribute(
            baseArrival(at = 10_000L),
            origin,
            baseContext(userIntents = listOf(UserIntentEvidence(UserIntentSource.RECENTS, launcherPkg, at = 9_200L))),
        )
        assertEquals(AttributionResult.Ignore(IgnoreReason.USER_INTENT_EVIDENCE), recentsIntent)

        // 超过 1.5s（1_501ms）-> 无有效用户意图证据 -> R3 Spill
        val staleIntent = CustodyAttribution.attribute(
            baseArrival(at = 10_000L),
            origin,
            baseContext(userIntents = listOf(UserIntentEvidence(UserIntentSource.LAUNCHER, launcherPkg, at = 8_499L))),
        )
        assertEquals(AttributionResult.Spill(managedDisplayId, SpillReason.MANAGED_ORIGIN_NO_USER_INTENT), staleIntent)
    }

    @Test
    fun `R5 handles UNKNOWN origin with recentAgentAction flag or timestamp window`() {
        val unknownResultRecent = CustodyAttribution.attribute(
            baseArrival(at = 10_000L),
            LaunchOrigin.UNKNOWN,
            baseContext(lastAgentActionAtMs = 8_000L),
        )
        assertTrue(unknownResultRecent is AttributionResult.Unattributed)
        assertTrue((unknownResultRecent as AttributionResult.Unattributed).recentAgentAction)

        val unknownResultIdle = CustodyAttribution.attribute(
            baseArrival(at = 10_000L),
            LaunchOrigin.UNKNOWN,
            baseContext(lastAgentActionAtMs = 5_000L),
        )
        assertTrue(unknownResultIdle is AttributionResult.Unattributed)
        assertFalse((unknownResultIdle as AttributionResult.Unattributed).recentAgentAction)
    }

    @Test
    fun `R6 ignores known unmanaged origin when no ledger entry matches`() {
        val otherOrigin = LaunchOrigin(uid = 10999, pkg = "com.unrelated.app", confidence = OriginConfidence.HIGH)
        val openLedger = listOf(
            LedgerEntry(
                Interstitial.JumpConfirm(
                    id = "j1",
                    displayId = managedDisplayId,
                    packageName = interstitialPkg,
                    openedAtMs = 9_000L,
                ),
            ),
        )
        val res = CustodyAttribution.attribute(
            baseArrival(at = 10_000L),
            otherOrigin,
            baseContext(ledgerEntries = openLedger),
        )
        assertEquals(AttributionResult.Ignore(IgnoreReason.OTHER), res)
    }

    @Test
    fun `R5b treats system-mediated uid 1000 android origin as Unattributed unless user intent exists`() {
        val systemOrigin = LaunchOrigin(uid = 1000, pkg = "android", confidence = OriginConfidence.HIGH)
        val dialogArrival = baseArrival(
            taskId = 48921,
            pkg = "com.oplus.securitypermission",
            at = 10_000L,
        )

        val unattributed = CustodyAttribution.attribute(
            dialogArrival,
            systemOrigin,
            baseContext(lastAgentActionAtMs = 9_500L),
        )
        assertEquals(AttributionResult.Unattributed(recentAgentAction = true), unattributed)

        val userSystemUiTap = CustodyAttribution.attribute(
            dialogArrival,
            systemOrigin,
            baseContext(
                lastAgentActionAtMs = 9_500L,
                userIntents = listOf(UserIntentEvidence(UserIntentSource.SYSTEM_UI, "com.android.systemui", 9_700L)),
            ),
        )
        assertEquals(AttributionResult.Ignore(IgnoreReason.USER_INTENT_EVIDENCE), userSystemUiTap)

        // 无任何托管屏时不误判 Unattributed，落入 R6 Ignore(OTHER)
        val noManaged = CustodyAttribution.attribute(
            dialogArrival,
            systemOrigin,
            AttributionContext(
                selfPackage = selfPkg,
                launcherPackages = setOf(launcherPkg),
                managedDisplays = emptyMap(),
                lastAgentActionAtMs = 9_500L,
            ),
        )
        assertEquals(AttributionResult.Ignore(IgnoreReason.OTHER), noManaged)
    }

    @Test
    fun `R2 matches system-mediated follow-up target after open or allow, skips same-pkg restart and DENY`() {
        val systemOrigin = LaunchOrigin(uid = 1000, pkg = "android", confidence = OriginConfidence.HIGH)
        val miniProgramConfirm = Interstitial.JumpConfirm(
            id = "jc-mini",
            displayId = managedDisplayId,
            packageName = "com.oplus.securitypermission",
            openedAtMs = 9_000L,
            initiatorPackage = "com.tencent.weread",
            targetPackage = null,
            targetLabel = "微信读书小程序",
        )

        // 1. Open 状态下的同包 Confirm 自身重启（result code=3）不能命中 R2（否则误判确认框本身为目标落地）
        val selfRestart = CustodyAttribution.attribute(
            baseArrival(taskId = 48921, pkg = "com.oplus.securitypermission", at = 10_000L),
            systemOrigin,
            baseContext(ledgerEntries = listOf(LedgerEntry(miniProgramConfirm)), lastAgentActionAtMs = 9_800L),
        )
        assertEquals(AttributionResult.Unattributed(recentAgentAction = true), selfRestart)

        // 2. 用户放行后微信（com.tencent.mm）经 system uid=1000 派发回主屏 -> 命中 R2 INTERSTITIAL_LEDGER
        val allowedEntry = LedgerEntry(
            interstitial = miniProgramConfirm,
            state = LedgerState.Resolved(InterstitialActor.USER, InterstitialChoice.ALLOW, at = 12_000L),
            updatedAtMs = 12_000L,
        )
        val targetFollowup = CustodyAttribution.attribute(
            baseArrival(taskId = 48925, pkg = "com.tencent.mm", at = 12_200L),
            systemOrigin,
            baseContext(ledgerEntries = listOf(allowedEntry)),
        )
        assertEquals(
            AttributionResult.Spill(managedDisplayId, SpillReason.INTERSTITIAL_LEDGER, ledgerEntryId = "jc-mini"),
            targetFollowup,
        )

        // 3. 用户点击“取消”(DENY) 后，后续 system_server 拉起的任务绝不因 R2 回捞
        val deniedEntry = LedgerEntry(
            interstitial = miniProgramConfirm,
            state = LedgerState.Resolved(InterstitialActor.USER, InterstitialChoice.DENY, at = 12_000L),
            updatedAtMs = 12_000L,
        )
        val afterDeny = CustodyAttribution.attribute(
            baseArrival(taskId = 48926, pkg = "com.tencent.mm", at = 12_500L),
            systemOrigin,
            baseContext(ledgerEntries = listOf(deniedEntry)),
        )
        assertEquals(AttributionResult.Unattributed(recentAgentAction = false), afterDeny)
    }

    @Test
    fun `R2 matches self-originated target followup after ColorOS confirm and respects R1, user intent and DENY`() {
        val miniProgramConfirm = Interstitial.JumpConfirm(
            id = "jc-self",
            displayId = managedDisplayId,
            packageName = "com.oplus.securitypermission",
            openedAtMs = 9_000L,
            initiatorPackage = "com.tencent.weread",
            targetPackage = null,
            targetLabel = "微信读书小程序",
        )
        val wechatSelfOrigin = LaunchOrigin(uid = 10433, pkg = "com.tencent.mm", confidence = OriginConfidence.HIGH)
        val wechatArrival = Arrival(
            taskId = 49055,
            pkg = "com.tencent.mm",
            userId = 0,
            uid = 10433,
            displayId = 0,
            fromDisplay = Arrival.UNKNOWN_DISPLAY,
            newTask = true,
            sameTask = false,
            topActivity = "com.tencent.mm/.ui.LauncherUI",
            at = 12_200L,
            standardTask = true,
        )

        // 1. Open 状态下 ColorOS 将已确认跳转的 target 任务以目标自身 UID/pkg 派发回主屏 -> 命中 R2 INTERSTITIAL_LEDGER
        assertEquals(
            AttributionResult.Spill(managedDisplayId, SpillReason.INTERSTITIAL_LEDGER, ledgerEntryId = "jc-self"),
            CustodyAttribution.attribute(
                wechatArrival,
                wechatSelfOrigin,
                baseContext(ledgerEntries = listOf(LedgerEntry(miniProgramConfirm))),
            ),
        )

        // 2. 弹窗先消失进入 Resolved(USER, UNKNOWN) 沉降窗，随后 50ms LauncherUI / 620ms AppBrandUI00 自转派到达 -> 仍命中 R2
        val pendingDismissEntry = LedgerEntry(
            interstitial = miniProgramConfirm,
            state = LedgerState.Resolved(InterstitialActor.USER, InterstitialChoice.UNKNOWN, at = 12_150L),
            updatedAtMs = 12_150L,
        )
        assertEquals(
            AttributionResult.Spill(managedDisplayId, SpillReason.INTERSTITIAL_LEDGER, ledgerEntryId = "jc-self"),
            CustodyAttribution.attribute(
                wechatArrival,
                wechatSelfOrigin,
                baseContext(ledgerEntries = listOf(pendingDismissEntry)),
            ),
        )

        // 3. 已知 targetPackage 时仅匹配该 target，无关 App 的自转派仍由 R6 忽略
        val knownTargetEntry = LedgerEntry(miniProgramConfirm.copy(id = "jc-known", targetPackage = "com.tencent.mm"))
        val unrelatedSelf = LaunchOrigin(uid = 10999, pkg = "com.unrelated.app", confidence = OriginConfidence.HIGH)
        assertEquals(
            AttributionResult.Ignore(IgnoreReason.OTHER),
            CustodyAttribution.attribute(
                wechatArrival.copy(pkg = "com.unrelated.app", uid = 10999),
                unrelatedSelf,
                baseContext(ledgerEntries = listOf(knownTargetEntry)),
            ),
        )

        // 4. 无人值守 UNAVAILABLE（AGENT + UNKNOWN）、用户拒绝（DENY）、近期用户意图及 R1 主屏原有任务均不得回捞
        val unattendedUnavailable = LedgerEntry(
            interstitial = miniProgramConfirm,
            state = LedgerState.Resolved(InterstitialActor.AGENT, InterstitialChoice.UNKNOWN, at = 12_000L),
            updatedAtMs = 12_000L,
        )
        assertEquals(
            AttributionResult.Ignore(IgnoreReason.OTHER),
            CustodyAttribution.attribute(
                wechatArrival,
                wechatSelfOrigin,
                baseContext(ledgerEntries = listOf(unattendedUnavailable)),
            ),
        )
        val deniedEntry = LedgerEntry(
            interstitial = miniProgramConfirm,
            state = LedgerState.Resolved(InterstitialActor.USER, InterstitialChoice.DENY, at = 12_000L),
            updatedAtMs = 12_000L,
        )
        assertEquals(
            AttributionResult.Ignore(IgnoreReason.OTHER),
            CustodyAttribution.attribute(
                wechatArrival,
                wechatSelfOrigin,
                baseContext(ledgerEntries = listOf(deniedEntry)),
            ),
        )
        assertEquals(
            AttributionResult.Ignore(IgnoreReason.OTHER),
            CustodyAttribution.attribute(
                wechatArrival,
                wechatSelfOrigin,
                baseContext(
                    ledgerEntries = listOf(LedgerEntry(miniProgramConfirm)),
                    userIntents = listOf(UserIntentEvidence(UserIntentSource.LAUNCHER, launcherPkg, at = 12_000L)),
                ),
            ),
        )
        assertEquals(
            AttributionResult.InUse(InUseReason.WAS_MAIN_TOP_TASK),
            CustodyAttribution.attribute(
                wechatArrival,
                wechatSelfOrigin,
                baseContext(
                    previousMainTopTaskId = 49055,
                    ledgerEntries = listOf(LedgerEntry(miniProgramConfirm)),
                ),
            ),
        )
    }
}
