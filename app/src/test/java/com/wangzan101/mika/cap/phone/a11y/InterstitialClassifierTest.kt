package com.wangzan101.mika.cap.phone.a11y

import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.custody.ConfirmButtonRejectReason
import com.wangzan101.mika.core.custody.Interstitial
import com.wangzan101.mika.core.custody.JumpConfirmProfile
import com.wangzan101.mika.core.custody.JumpConfirmProfileMatcher
import com.wangzan101.mika.core.custody.JumpProfileSource
import com.agent.use.ui.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InterstitialClassifierTest {

    private val apps = mapOf(
        "com.tencent.mm" to "微信",
        "com.tencent.weread" to "微信读书",
        "com.netease.cloudmusic" to "网易云音乐",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.example.shop" to "Shop",
        "com.wangzan101.mika" to "Mika",
    )

    private val systemPackages = setOf(
        "android",
        "com.oplus.securitypermission",
        "com.android.permissioncontroller",
        "com.vendor.unknown.guard",
    )

    private fun classifier(
        authenticators: Set<String> = emptySet(),
        extraApps: Map<String, String> = emptyMap(),
        extraSystemPackages: Set<String> = emptySet(),
    ) = InterstitialClassifier(
        selfPackage = "com.wangzan101.mika",
        systemPickers = setOf("android"),
        isSystemPackage = { it in systemPackages || it in extraSystemPackages },
        isAuthenticatorPackage = { it in authenticators },
        appLabel = { (apps + extraApps)[it] },
        installedApps = { apps + extraApps },
        profiles = { mapOf("com.tencent.mm" to listOf("工作")) },
    )

    private fun baseCtx(
        hasManagedOrigin: Boolean = true,
        hasRecentUserIntentWithin2s: Boolean = false,
        hasActiveCustodyDisplay: Boolean = true,
        recentAgentAction: Boolean = true,
        expectedTargetPackage: String? = null,
    ) = InterstitialClassifier.ClassificationContext(
        interstitialId = "test-id",
        attributedDisplayId = 2,
        nowMs = 10_000L,
        launcherPackages = setOf("com.android.launcher"),
        originPackage = "com.tencent.mm",
        originUid = 10123,
        hasManagedOrigin = hasManagedOrigin,
        hasRecentUserIntentWithin2s = hasRecentUserIntentWithin2s,
        hasActiveCustodyDisplay = hasActiveCustodyDisplay,
        recentAgentAction = recentAgentAction,
        expectedTargetPackage = expectedTargetPackage,
        vdForegroundPackages = setOf("com.tencent.mm"),
    )

    @Test
    fun classifiesColorOsAppStartDialogAsJumpConfirmDespiteExcludeFromRecents() {
        val window = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            taskId = 55,
            windowId = 7,
            packageName = "com.oplus.securitypermission",
            componentName = "com.oplus.securitypermission/com.oplusos.securitypermission.permission.ui.AppStartConfirmDialogActivity",
            texts = listOf("“微信”想要打开“支付宝”"),
            nodes = listOf(
                InterstitialClassifier.NodeInspection(text = "“微信”想要打开“支付宝”", role = "TextView"),
                InterstitialClassifier.NodeInspection(
                    text = "始终允许打开",
                    role = "CheckBox",
                    checkable = true,
                    checked = false,
                ),
                InterstitialClassifier.NodeInspection(
                    text = "取消",
                    role = "Button",
                    resourceId = "android:id/button2",
                    clickable = true,
                ),
                InterstitialClassifier.NodeInspection(
                    text = "打开",
                    role = "Button",
                    resourceId = "android:id/button1",
                    clickable = true,
                ),
            ),
            isExcludeFromRecents = true,
        )

        val result = classifier().classify(window, baseCtx())
        assertTrue(result is InterstitialClassifier.ClassificationOutcome.Classified)
        val classified = result as InterstitialClassifier.ClassificationOutcome.Classified
        val jc = classified.interstitial as Interstitial.JumpConfirm
        assertEquals("com.tencent.mm", jc.initiatorPackage)
        assertEquals("com.eg.android.AlipayGphone", jc.targetPackage)
        assertEquals("打开", jc.positiveButtonText)
        assertEquals("取消", jc.negativeButtonText)
        assertFalse(classified.alwaysAllowCheckboxChecked)
        assertEquals(true, classified.buttonAnalysis?.valid)
        assertTrue(classified.confidence >= 0.9f)
    }

    @Test
    fun classifiesGenericUnknownSystemPackageWithoutOemTable() {
        val window = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            windowType = AccessibilityWindowInfo.TYPE_SYSTEM,
            packageName = "com.vendor.unknown.guard",
            texts = listOf("是否允许 微信 打开 支付宝"),
            nodes = listOf(
                InterstitialClassifier.NodeInspection(text = "仅本次允许", role = "Button", clickable = true),
                InterstitialClassifier.NodeInspection(text = "拒绝", role = "Button", clickable = true),
            ),
        )

        val result = classifier().classify(window, baseCtx(hasManagedOrigin = false, hasRecentUserIntentWithin2s = false))
        assertTrue(result is InterstitialClassifier.ClassificationOutcome.Classified)
        val jc = (result as InterstitialClassifier.ClassificationOutcome.Classified).interstitial as Interstitial.JumpConfirm
        assertEquals("com.tencent.mm", jc.initiatorPackage)
        assertEquals("com.eg.android.AlipayGphone", jc.targetPackage)
        assertEquals("仅本次允许", jc.positiveButtonText)
        assertEquals("拒绝", jc.negativeButtonText)
    }

    @Test
    fun detectsCheckedTraditionalAlwaysAllowCheckbox() {
        val window = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            packageName = "com.oplus.securitypermission",
            texts = listOf("「微信」想要打開「支付寶」"),
            nodes = listOf(
                InterstitialClassifier.NodeInspection(
                    text = "一律允許打開",
                    role = "CheckBox",
                    checkable = true,
                    checked = true,
                ),
                InterstitialClassifier.NodeInspection(text = "打開", role = "Button", clickable = true),
                InterstitialClassifier.NodeInspection(text = "取消", role = "Button", clickable = true),
            ),
            isExcludeFromRecents = true,
        )

        val result = classifier().classify(window, baseCtx()) as InterstitialClassifier.ClassificationOutcome.Classified
        assertTrue(result.interstitial is Interstitial.JumpConfirm)
        assertTrue(result.alwaysAllowCheckboxChecked)
    }

    @Test
    fun capturesPersistentOnlyButtonForPolicyGuardrailRejection() {
        val window = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            packageName = "com.vendor.unknown.guard",
            texts = listOf("“微信”想要打开“支付宝”"),
            nodes = listOf(
                InterstitialClassifier.NodeInspection(text = "始终允许", role = "Button", clickable = true),
                InterstitialClassifier.NodeInspection(text = "拒绝", role = "Button", clickable = true),
            ),
        )

        val result = classifier().classify(window, baseCtx()) as InterstitialClassifier.ClassificationOutcome.Classified
        assertTrue(result.interstitial is Interstitial.JumpConfirm)
        assertEquals(ConfirmButtonRejectReason.PERSISTENT_BUTTON_FORBIDDEN, result.buttonAnalysis?.rejectReason)
    }

    @Test
    fun rejectsRuntimePermissionDialogFromJumpConfirm() {
        val window = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            packageName = "com.android.permissioncontroller",
            componentName = "com.android.permissioncontroller/.permission.ui.GrantPermissionsActivity",
            texts = listOf("允许 微信 访问您的位置信息？"),
            nodes = listOf(
                InterstitialClassifier.NodeInspection(text = "仅在使用中允许", role = "Button", clickable = true),
                InterstitialClassifier.NodeInspection(text = "允许", role = "Button", clickable = true),
                InterstitialClassifier.NodeInspection(text = "拒绝", role = "Button", clickable = true),
            ),
        )

        val result = classifier().classify(window, baseCtx()) as InterstitialClassifier.ClassificationOutcome.Classified
        assertTrue(result.interstitial is Interstitial.NeedsUser)
        assertTrue(result.safety.isRuntimePermission)
    }

    @Test
    fun rejectsPasswordAndSecureWindowsBeforeJumpConfirm() {
        val withPassword = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            packageName = "com.vendor.unknown.guard",
            texts = listOf("“微信”想要打开“支付宝”"),
            nodes = listOf(
                InterstitialClassifier.NodeInspection(text = "", role = "EditText", password = true),
                InterstitialClassifier.NodeInspection(text = "允许", role = "Button", clickable = true),
                InterstitialClassifier.NodeInspection(text = "取消", role = "Button", clickable = true),
            ),
            hasPasswordField = true,
        )
        val resPass = classifier().classify(withPassword, baseCtx()) as InterstitialClassifier.ClassificationOutcome.Classified
        assertTrue(resPass.interstitial is Interstitial.NeedsUser)
        assertTrue(resPass.safety.hasPasswordField)

        val withSecure = withPassword.copy(
            nodes = withPassword.nodes.drop(1),
            hasPasswordField = false,
            isFlagSecure = true,
        )
        val resSecure = classifier().classify(withSecure, baseCtx()) as InterstitialClassifier.ClassificationOutcome.Classified
        assertTrue(resSecure.interstitial is Interstitial.NeedsUser)
        assertTrue(resSecure.safety.isFlagSecure)
    }

    @Test
    fun ignoresJumpDialogWhenUserTappedMainScreenWithin2sWithoutManagedOrigin() {
        val window = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            packageName = "com.vendor.unknown.guard",
            texts = listOf("“微信”想要打开“支付宝”"),
            nodes = listOf(
                InterstitialClassifier.NodeInspection(text = "允许", role = "Button", clickable = true),
                InterstitialClassifier.NodeInspection(text = "取消", role = "Button", clickable = true),
            ),
        )

        val result = classifier().classify(
            window,
            baseCtx(hasManagedOrigin = false, hasRecentUserIntentWithin2s = true),
        )
        assertEquals(InterstitialClassifier.ClassificationOutcome.None, result)
    }

    @Test
    fun doesNotClassifyGenericSystemDialogWithoutInstalledAppMatch() {
        val window = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            packageName = "com.vendor.unknown.guard",
            texts = listOf("系统更新已准备好，是否重启？"),
            nodes = listOf(
                InterstitialClassifier.NodeInspection(text = "允许", role = "Button", clickable = true),
                InterstitialClassifier.NodeInspection(text = "取消", role = "Button", clickable = true),
            ),
        )

        val result = classifier().classify(window, baseCtx())
        assertEquals(InterstitialClassifier.ClassificationOutcome.None, result)
    }

    @Test
    fun recognizesAuthenticatorAndSecureNonSystemWindowsAsNeedsUser() {
        val authWin = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            packageName = "com.example.auth",
            texts = listOf("Sign in"),
        )
        val authRes = classifier(authenticators = setOf("com.example.auth")).classify(authWin, baseCtx())
        assertTrue((authRes as InterstitialClassifier.ClassificationOutcome.Classified).interstitial is Interstitial.NeedsUser)

        val secureUnreadable = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            packageName = "com.example.bank",
            readable = false,
            isFlagSecure = true,
        )
        val secRes = classifier().classify(secureUnreadable, baseCtx())
        assertTrue((secRes as InterstitialClassifier.ClassificationOutcome.Classified).interstitial is Interstitial.NeedsUser)
    }

    @Test
    fun recognizesMainChooserFromElements() {
        val elements = listOf(
            UiElement("@e1", "TextView", "微信", 100, 200, listOf(0, 100, 500, 300), resourceId = null, clickable = true, editable = false, scrollable = false),
            UiElement("@e2", "TextView", "工作 微信", 100, 350, listOf(0, 300, 500, 450), resourceId = null, clickable = true, editable = false, scrollable = false),
        )
        val chooserWin = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            packageName = "android",
            componentName = "android/com.android.internal.app.ResolverActivity",
            texts = listOf("微信", "工作 微信"),
            elements = elements,
        )

        val res = classifier().classify(chooserWin, baseCtx(expectedTargetPackage = "com.tencent.mm"))
            as InterstitialClassifier.ClassificationOutcome.Classified
        assertTrue(res.interstitial is Interstitial.Chooser)
        assertNotNull(res.launchChoice)
        assertEquals(2, res.launchChoice!!.options.size)
    }

    @Test
    fun classifiesColorOsMiniProgramSameNameTitleWithoutPrefixFalseMatchOrDropping() {
        // 真实 PLK110 ColorOS 17 层级：AppStartConfirmDialogActivity，launchedFromUid=1000 / pkg="android"
        val window = InterstitialClassifier.WindowInspection(
            displayId = Display.DEFAULT_DISPLAY,
            taskId = 48921,
            windowId = 348,
            packageName = "com.oplus.securitypermission",
            componentName = "com.oplus.securitypermission/com.oplusos.securitypermission.permission.ui.AppStartConfirmDialogActivity",
            windowType = AccessibilityWindowInfo.TYPE_APPLICATION,
            isDialogWindow = true,
            isExcludeFromRecents = true,
            texts = listOf("“微信读书”想要打开“微信读书”小程序", "始终允许打开", "取消", "打开"),
            nodes = listOf(
                InterstitialClassifier.NodeInspection(
                    text = "“微信读书”想要打开“微信读书”小程序",
                    role = "TextView",
                    resourceId = "com.oplus.securitypermission:id/alertTitle",
                ),
                InterstitialClassifier.NodeInspection(
                    text = "始终允许打开",
                    role = "CompoundButton",
                    resourceId = "com.oplus.securitypermission:id/coui_security_alert_dialog_checkbox",
                    checkable = true,
                    checked = false,
                ),
                InterstitialClassifier.NodeInspection(
                    text = "取消",
                    role = "Button",
                    resourceId = "android:id/button2",
                    clickable = true,
                ),
                InterstitialClassifier.NodeInspection(
                    text = "打开",
                    role = "Button",
                    resourceId = "android:id/button1",
                    clickable = true,
                ),
            ),
        )
        val systemCtx = InterstitialClassifier.ClassificationContext(
            interstitialId = "plk110-t48921",
            attributedDisplayId = 5,
            nowMs = 20_000L,
            launcherPackages = setOf("com.android.launcher"),
            originPackage = "android",
            originUid = 1000,
            hasManagedOrigin = false,
            hasRecentUserIntentWithin2s = false,
            hasActiveCustodyDisplay = true,
            recentAgentAction = true,
            vdForegroundPackages = setOf("com.tencent.weread"),
        )

        val outcome = classifier().classify(window, systemCtx)
            as InterstitialClassifier.ClassificationOutcome.Classified
        val jc = outcome.interstitial as Interstitial.JumpConfirm
        assertEquals("com.tencent.weread", jc.initiatorPackage)
        assertNull("同名前缀“微信读书”不能误匹配为“微信”", jc.targetPackage)
        assertEquals("微信读书小程序", jc.targetLabel)
        assertEquals("打开", jc.positiveButtonText)
        assertEquals("取消", jc.negativeButtonText)
        assertFalse(outcome.alwaysAllowCheckboxChecked)

        // 另一款无前缀重合的小程序（网易云音乐）同样稳定识别为 JumpConfirm，而不是掉进 NeedsUser
        val musicWin = window.copy(
            texts = listOf("“网易云音乐”想要打开“网易云音乐”小程序", "始终允许打开", "取消", "打开"),
            nodes = window.nodes.mapIndexed { idx, node ->
                if (idx == 0) node.copy(text = "“网易云音乐”想要打开“网易云音乐”小程序") else node
            },
        )
        val musicOutcome = classifier().classify(
            musicWin,
            systemCtx.copy(vdForegroundPackages = setOf("com.netease.cloudmusic")),
        ) as InterstitialClassifier.ClassificationOutcome.Classified
        val musicJc = musicOutcome.interstitial as Interstitial.JumpConfirm
        assertEquals("com.netease.cloudmusic", musicJc.initiatorPackage)
        assertNull(musicJc.targetPackage)
        assertEquals("网易云音乐小程序", musicJc.targetLabel)

        // 明确跨 App 跳转（微信读书 -> 微信）仍准确解析出 com.tencent.mm
        val crossAppWin = window.copy(
            texts = listOf("“微信读书”想要打开“微信”", "始终允许打开", "取消", "打开"),
            nodes = window.nodes.mapIndexed { idx, node ->
                if (idx == 0) node.copy(text = "“微信读书”想要打开“微信”") else node
            },
        )
        val crossOutcome = classifier().classify(crossAppWin, systemCtx)
            as InterstitialClassifier.ClassificationOutcome.Classified
        val crossJc = crossOutcome.interstitial as Interstitial.JumpConfirm
        assertEquals("com.tencent.weread", crossJc.initiatorPackage)
        assertEquals("com.tencent.mm", crossJc.targetPackage)
        assertEquals("微信", crossJc.targetLabel)
    }

    @Test
    fun classifySamePackageTestDialogPreservesVdOriginAndTwoPartyUnknownTarget() {
        val dialogWin = InterstitialClassifier.WindowInspection(
            packageName = "com.wangzan101.mika.test",
            displayId = Display.DEFAULT_DISPLAY,
            taskId = 48950,
            windowId = 349,
            componentName = "com.wangzan101.mika.cap.phone.a11y.JumpConfirmDialogTestActivity",
            windowType = AccessibilityWindowInfo.TYPE_APPLICATION,
            isDialogWindow = true,
            readable = true,
            isExcludeFromRecents = true,
            texts = listOf("\"Mika Test\" wants to open \"UnknownTarget\"", "CANCEL", "OPEN"),
            nodes = listOf(
                InterstitialClassifier.NodeInspection(
                    text = "\"Mika Test\" wants to open \"UnknownTarget\"",
                    role = "TextView",
                ),
                InterstitialClassifier.NodeInspection(
                    text = "CANCEL",
                    role = "Button",
                    clickable = true,
                ),
                InterstitialClassifier.NodeInspection(
                    text = "OPEN",
                    role = "Button",
                    clickable = true,
                ),
            ),
        )
        val samePkgCtx = InterstitialClassifier.ClassificationContext(
            interstitialId = "test-same-pkg",
            attributedDisplayId = 5,
            nowMs = 30_000L,
            launcherPackages = setOf("com.android.launcher"),
            originPackage = "com.wangzan101.mika.test",
            originUid = 10300,
            hasManagedOrigin = true,
            hasRecentUserIntentWithin2s = false,
            hasActiveCustodyDisplay = true,
            recentAgentAction = true,
            vdForegroundPackages = setOf("com.wangzan101.mika.test"),
        )

        val testPkgClassifier = classifier(extraSystemPackages = setOf("com.wangzan101.mika.test"))
        val samePkgOutcome = testPkgClassifier.classify(dialogWin, samePkgCtx)
            as InterstitialClassifier.ClassificationOutcome.Classified
        val samePkgJc = samePkgOutcome.interstitial as Interstitial.JumpConfirm
        assertEquals("com.wangzan101.mika.test", samePkgJc.initiatorPackage)
        assertNull(samePkgJc.targetPackage)
        assertEquals("UnknownTarget", samePkgJc.targetLabel)

        // 即使来源暂时 UNKNOWN，只要是双端加引号的明确跳转句式且在托管周期内，也不应退化为普通 NeedsUser("dialog")
        val noOriginCtx = samePkgCtx.copy(
            originPackage = null,
            hasManagedOrigin = false,
        )
        val noOriginOutcome = testPkgClassifier.classify(dialogWin, noOriginCtx)
            as InterstitialClassifier.ClassificationOutcome.Classified
        val noOriginJc = noOriginOutcome.interstitial as Interstitial.JumpConfirm
        assertNull(noOriginJc.targetPackage)
        assertEquals("UnknownTarget", noOriginJc.targetLabel)

        // 当 Settings 未被 FallbackHome 混入 launcherPackages 时，“设置”应精确解析为 com.android.settings
        val settingsClassifier = classifier(
            extraApps = mapOf("com.android.settings" to "设置"),
            extraSystemPackages = setOf("com.wangzan101.mika.test"),
        )
        val settingsWin = dialogWin.copy(
            texts = listOf("\"Mika Test\" wants to open \"设置\"", "CANCEL", "OPEN"),
            nodes = dialogWin.nodes.mapIndexed { idx, node ->
                if (idx == 0) node.copy(text = "\"Mika Test\" wants to open \"设置\"") else node
            },
        )
        val settingsOutcome = settingsClassifier.classify(settingsWin, samePkgCtx)
            as InterstitialClassifier.ClassificationOutcome.Classified
        val settingsJc = settingsOutcome.interstitial as Interstitial.JumpConfirm
        assertEquals("com.wangzan101.mika.test", settingsJc.initiatorPackage)
        assertEquals("com.android.settings", settingsJc.targetPackage)
        assertEquals("设置", settingsJc.targetLabel)

        // 当 originPackage 未知（如 windowScan）且手机上恰巧存在同名子串“Test”的其他应用时，
        // 绝不能把“Mika Test”误归因成该无关应用，应保留 originHint(null) 走最近动作兜底
        val unrelatedSubstringClassifier = classifier(
            extraApps = mapOf(
                "com.android.settings" to "设置",
                "com.unrelated.test" to "Test",
            ),
            extraSystemPackages = setOf("com.wangzan101.mika.test"),
        )
        val unknownOriginCtx = samePkgCtx.copy(
            originPackage = null,
            originUid = -1,
            hasManagedOrigin = false,
            expectedTargetPackage = "com.android.settings",
        )
        val unknownOriginOutcome = unrelatedSubstringClassifier.classify(settingsWin, unknownOriginCtx)
            as InterstitialClassifier.ClassificationOutcome.Classified
        val unknownOriginJc = unknownOriginOutcome.interstitial as Interstitial.JumpConfirm
        assertNull("部分子串命中非 VD 应用 Test 时不应篡改 initiatorPackage", unknownOriginJc.initiatorPackage)
        assertEquals("com.android.settings", unknownOriginJc.targetPackage)
    }

    @Test
    fun miniProgramPrefersScheme1SystemTargetAndFallsBackToScheme2Profiles() {
        val miniWin = InterstitialClassifier.WindowInspection(
            packageName = "com.oplus.securitypermission",
            displayId = Display.DEFAULT_DISPLAY,
            taskId = 49061,
            windowId = 1775,
            componentName = "com.oplus.securitypermission/com.oplusos.securitypermission.permission.ui.AppStartConfirmDialogActivity",
            windowType = AccessibilityWindowInfo.TYPE_APPLICATION,
            isDialogWindow = true,
            readable = true,
            isExcludeFromRecents = true,
            texts = listOf("“微信读书”想要打开“微信读书”小程序", "始终允许打开", "取消", "打开"),
            nodes = listOf(
                InterstitialClassifier.NodeInspection(text = "“微信读书”想要打开“微信读书”小程序", role = "TextView"),
                InterstitialClassifier.NodeInspection(text = "始终允许打开", role = "CheckBox", checkable = true, checked = false),
                InterstitialClassifier.NodeInspection(text = "取消", role = "Button", resourceId = "android:id/button2", clickable = true),
                InterstitialClassifier.NodeInspection(text = "打开", role = "Button", resourceId = "android:id/button1", clickable = true),
            ),
        )
        val profile = JumpConfirmProfile(
            id = JumpConfirmProfileMatcher.profileId("com.tencent.weread", "微信读书小程序"),
            initiatorPackage = "com.tencent.weread",
            targetLabel = "微信读书小程序",
            targetPackage = "com.eg.android.AlipayGphone",
            enabled = true,
            source = JumpProfileSource.MANUAL,
        )

        val scheme1Classifier = classifier(extraApps = mapOf("com.eg.android.AlipayGphone" to "支付宝"))
        // 方案 1 优先：系统解析出 com.tencent.mm 时覆盖 Profile 兜底并保留直观展示文案
        val scheme1Ctx = baseCtx(recentAgentAction = true).copy(
            originPackage = "android",
            vdForegroundPackages = setOf("com.tencent.weread"),
            learnedTargetProfiles = listOf(profile),
            resolveSystemTarget = { taskId, interstitialPkg, initiatorPkg ->
                if (taskId == 49061 && interstitialPkg == "com.oplus.securitypermission" && initiatorPkg == "com.tencent.weread") {
                    "com.tencent.mm"
                } else null
            },
        )
        val outcome1 = scheme1Classifier.classify(miniWin, scheme1Ctx) as InterstitialClassifier.ClassificationOutcome.Classified
        val jc1 = outcome1.interstitial as Interstitial.JumpConfirm
        assertEquals("com.tencent.mm", jc1.targetPackage)
        assertEquals("微信读书小程序", jc1.targetLabel)

        // 方案 2 兜底：系统返回 null 时回落到已启用的 Profile 映射
        val scheme2Ctx = scheme1Ctx.copy(resolveSystemTarget = { _, _, _ -> null })
        val outcome2 = scheme1Classifier.classify(miniWin, scheme2Ctx) as InterstitialClassifier.ClassificationOutcome.Classified
        val jc2 = outcome2.interstitial as Interstitial.JumpConfirm
        assertEquals("com.eg.android.AlipayGphone", jc2.targetPackage)
        assertEquals("微信读书小程序", jc2.targetLabel)

        // 用户关闭该规则时退回 targetPackage = null
        val disabledCtx = scheme2Ctx.copy(learnedTargetProfiles = listOf(profile.copy(enabled = false)))
        val outcome3 = scheme1Classifier.classify(miniWin, disabledCtx) as InterstitialClassifier.ClassificationOutcome.Classified
        assertNull((outcome3.interstitial as Interstitial.JumpConfirm).targetPackage)
    }
}
