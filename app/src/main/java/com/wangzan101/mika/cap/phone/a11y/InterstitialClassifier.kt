package com.wangzan101.mika.cap.phone.a11y

import android.accounts.AccountManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.core.custody.ConfirmButtonAnalysis
import com.wangzan101.mika.core.custody.ConfirmButtonInput
import com.wangzan101.mika.core.custody.ConfirmButtonRejectReason
import com.wangzan101.mika.core.custody.ConfirmButtons
import com.wangzan101.mika.core.custody.ConfirmCheckbox
import com.wangzan101.mika.core.custody.Interstitial
import com.wangzan101.mika.core.custody.JumpConfirmProfile
import com.wangzan101.mika.core.custody.JumpConfirmProfileMatcher
import com.wangzan101.mika.core.custody.WindowSafetySignals
import com.wangzan101.mika.core.uistate.Hant
import com.wangzan101.mika.core.uistate.LaunchChoice
import com.wangzan101.mika.core.uistate.LaunchChooser
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiStates

/**
 * 主屏或托管虚拟屏上出现的中间页统一分类器。
 *
 * 吸收并替换原 `mainChooser`、`needsUserWindow`、`authenticatorUi` 零散判定，新增通用应用间跳转确认
 * 弹窗（[Interstitial.JumpConfirm]）识别：
 * 1. 先按窗口安全（密码框、认证器、`FLAG_SECURE`）和运行时权限弹窗排除，绝不误判为可代点跳转；
 * 2. 再识别系统选择器（[Interstitial.Chooser]）；
 * 3. 在 `FLAG_EXCLUDE_FROM_RECENTS` 兜底之前优先匹配 [Interstitial.JumpConfirm]（兼容如 ColorOS
 *    `AppStartConfirmDialogActivity` `flags=0x10220` 同时带 `FLAG_EXCLUDE_FROM_RECENTS` 的情况）：
 *    系统包 + 非运行时权限框 + 来源或 2s 无用户意图时间窗 + [ConfirmButtons] 按钮结构 +
 *    已安装 App 名匹配；厂商组件表只做置信度加分与定位提示；
 * 4. 最后回落到 `FLAG_EXCLUDE_FROM_RECENTS` 的 [Interstitial.NeedsUser]。
 */
internal class InterstitialClassifier(
    private val selfPackage: String,
    private val systemPickers: Set<String> = emptySet(),
    private val isSystemPackage: (String) -> Boolean,
    private val isAuthenticatorPackage: (String) -> Boolean = { false },
    private val appLabel: (String) -> String? = { null },
    private val installedApps: () -> Map<String, String> = { emptyMap() },
    private val profiles: () -> Map<String, List<String>> = { emptyMap() },
    private val isPackageInstalled: (String) -> Boolean = { true },
) {
    /** 弹窗上的单个可交互节点快照。 */
    data class NodeInspection(
        val text: String,
        val role: String = "View",
        val resourceId: String? = null,
        val clickable: Boolean = false,
        val enabled: Boolean = true,
        val checkable: Boolean = false,
        val checked: Boolean = false,
        val password: Boolean = false,
    )

    /** 窗口分类所需的只读快照（与具体 Android Framework 解耦，便于单测直接构造）。 */
    data class WindowInspection(
        val displayId: Int,
        val taskId: Int = -1,
        val windowId: Int = -1,
        val windowType: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
        val packageName: String,
        val componentName: String? = null,
        val title: String? = null,
        val texts: List<String> = emptyList(),
        val elements: List<UiElement> = emptyList(),
        val nodes: List<NodeInspection> = emptyList(),
        val readable: Boolean = texts.isNotEmpty() || elements.isNotEmpty() || nodes.isNotEmpty(),
        val hasPasswordField: Boolean = nodes.any { it.password },
        val isFlagSecure: Boolean = false,
        val isExcludeFromRecents: Boolean = false,
        val isAuthenticator: Boolean = false,
        val isDialogWindow: Boolean = false,
    )

    /** 分类时的归因/意图上下文。 */
    data class ClassificationContext(
        val interstitialId: String = "",
        val attributedDisplayId: Int = 0,
        val nowMs: Long = 0L,
        val launcherPackages: Set<String> = emptySet(),
        val originPackage: String? = null,
        val originUid: Int? = null,
        val hasManagedOrigin: Boolean = false,
        val hasRecentUserIntentWithin2s: Boolean = false,
        val hasActiveCustodyDisplay: Boolean = false,
        val recentAgentAction: Boolean = false,
        val expectedTargetPackage: String? = null,
        val vdForegroundPackages: Set<String> = emptySet(),
        val fallbackInitiatorPackage: String? = null,
        val learnedTargetProfiles: List<JumpConfirmProfile> = emptyList(),
        val resolveSystemTarget: (taskId: Int, interstitialPackage: String, initiatorPackage: String?) -> String? = { _, _, _ -> null },
    )

    /** 厂商已知跳转拦截组件的置信度加分与定位提示（绝不作为识别前提）。 */
    data class OemJumpHint(
        val packageName: String,
        val componentMarkers: List<String> = emptyList(),
        val preferredPositiveIds: List<String> = DEFAULT_POSITIVE_BUTTON_IDS,
    )

    /** 按钮节点与 [AccessibilityNodeInfo] 对应关系，供无障碍原地 `ACTION_CLICK` 使用。 */
    data class ClickableButtonNode(
        val input: ConfirmButtonInput,
        val node: AccessibilityNodeInfo,
    )

    /** 带节点引用的真实无障碍窗口抓取结果。 */
    data class AccessibilityWindowSnapshot(
        val inspection: WindowInspection,
        val clickableButtons: List<ClickableButtonNode>,
    )

    /** 分类输出，附带策略评估所需的按钮结构、勾选框及窗口安全信号。 */
    sealed interface ClassificationOutcome {
        data object None : ClassificationOutcome

        data class Classified(
            val interstitial: Interstitial,
            val launchChoice: LaunchChoice? = null,
            val buttons: List<ConfirmButtonInput> = emptyList(),
            val buttonAnalysis: ConfirmButtonAnalysis? = null,
            val checkboxes: List<ConfirmCheckbox> = emptyList(),
            val alwaysAllowCheckboxChecked: Boolean = false,
            val safety: WindowSafetySignals = WindowSafetySignals(),
            val confidence: Float = 0f,
            val oemHint: OemJumpHint? = null,
            val dialogTexts: List<String> = emptyList(),
        ) : ClassificationOutcome
    }

    /** 查询包是否属于系统或预置特权应用。 */
    fun isSystemApp(pkg: String): Boolean = isSystemPackage(pkg)

    /** 检查是否属于系统选择器（吸收原 `AndroidScreen.mainChooser`）。 */
    fun mainChooser(
        window: WindowInspection,
        targetPackage: String?,
        beforePackage: String?,
    ): LaunchChoice? {
        if (targetPackage.isNullOrBlank()) return null
        val pkg = window.packageName
        if (pkg == targetPackage || pkg == beforePackage || !isSystemPackage(pkg)) return null
        val label = appLabel(targetPackage) ?: return null
        val options = LaunchChooser.options(label, window.elements, profiles()[targetPackage].orEmpty()) ?: return null
        return LaunchChoice(pkg, options)
    }

    /** 统一对窗口做中间页分类。 */
    fun classify(
        window: WindowInspection,
        ctx: ClassificationContext = ClassificationContext(),
    ): ClassificationOutcome {
        val pkg = window.packageName.trim()
        if (pkg.isEmpty() || pkg == selfPackage || pkg in ctx.launcherPackages) {
            return ClassificationOutcome.None
        }

        val id = ctx.interstitialId.ifBlank { "${window.displayId}:${window.taskId}:${window.windowId}:$pkg:${ctx.nowMs}" }
        val systemPkg = isSystemPackage(pkg)
        val auth = window.isAuthenticator || (!systemPkg && isAuthenticatorPackage(pkg))
        val dialogTexts = collectTexts(window)
        val safety = WindowSafetySignals(
            isFlagSecure = window.isFlagSecure,
            hasPasswordField = window.hasPasswordField,
            isAuthenticator = auth,
            isRuntimePermission = false,
        )

        if (!systemPkg) {
            return when {
                auth -> classifiedNeedsUser(id, ctx, window, "authenticator", safety)
                needsUser(window.hasPasswordField, window.readable, window.isFlagSecure) ->
                    classifiedNeedsUser(
                        id, ctx, window,
                        reason = if (window.hasPasswordField) "password" else "secure",
                        safety = safety,
                    )
                else -> ClassificationOutcome.None
            }
        }

        // 1. 密码 / 认证器 / FLAG_SECURE 严格优先于 JumpConfirm 与 Chooser，直接归入 NeedsUser
        if (window.hasPasswordField || auth || window.isFlagSecure) {
            val reason = when {
                window.hasPasswordField -> "password"
                auth -> "authenticator"
                else -> "secure"
            }
            return classifiedNeedsUser(id, ctx, window, reason, safety, dialogTexts)
        }

        // 2. 运行时权限框严格排除（归入 NeedsUser，绝不触发 JumpConfirm 自动代点）
        if (isRuntimePermissionWindow(window, dialogTexts)) {
            return classifiedNeedsUser(
                id, ctx, window, "runtime_permission",
                safety.copy(isRuntimePermission = true), dialogTexts,
            )
        }

        // 3. 系统应用选择器 / 多开分身选择
        val chooserTarget = ctx.expectedTargetPackage
        val choice = mainChooser(window, chooserTarget, beforePackage = null)
        if (choice != null || isExplicitChooserWindow(window)) {
            return ClassificationOutcome.Classified(
                interstitial = Interstitial.Chooser(
                    id = id,
                    displayId = ctx.attributedDisplayId,
                    taskId = window.taskId,
                    packageName = pkg,
                    componentName = window.componentName,
                    openedAtMs = ctx.nowMs,
                    initiatorPackage = ctx.originPackage,
                    options = choice?.options?.map { it.label }.orEmpty(),
                ),
                launchChoice = choice,
                safety = safety,
                confidence = if (choice != null) 0.95f else 0.85f,
                dialogTexts = dialogTexts,
            )
        }

        // 4. 通用跳转确认弹窗（必须在 FLAG_EXCLUDE_FROM_RECENTS 之前检查，兼容 ColorOS flags=0x10220）
        classifyJumpConfirm(id, window, ctx, dialogTexts, safety)?.let { return it }

        // 5. 其余系统侧带 FLAG_EXCLUDE_FROM_RECENTS 或非全屏对话框的弹窗（如凭据、付款确认等）留在主屏给用户
        if (window.isExcludeFromRecents || window.isDialogWindow) {
            val reason = if (window.isExcludeFromRecents) "exclude_from_recents" else "dialog"
            return classifiedNeedsUser(id, ctx, window, reason, safety, dialogTexts)
        }

        return ClassificationOutcome.None
    }

    private fun classifyJumpConfirm(
        id: String,
        window: WindowInspection,
        ctx: ClassificationContext,
        dialogTexts: List<String>,
        safety: WindowSafetySignals,
    ): ClassificationOutcome.Classified? {
        // 来源或 2s 无用户意图时间窗前提：
        // - 若无托管来源且 2s 内用户在主屏有桌面/通知/最近任务点击，视作用户主动操作，不判为托管弹窗
        if (!ctx.hasManagedOrigin && ctx.hasRecentUserIntentWithin2s) {
            return null
        }
        // - 若既无托管来源、无活跃托管屏、也无近期 Agent 动作，不认作托管跳转弹窗
        if (!ctx.hasManagedOrigin && !ctx.hasActiveCustodyDisplay && !ctx.recentAgentAction) {
            return null
        }

        val oemHint = matchOemHint(window.packageName, window.componentName)
        val buttons = extractButtonInputs(window, oemHint)
        val buttonAnalysis = ConfirmButtons.analyze(buttons)
        val checkboxes = extractCheckboxes(window)
        val persistentBoxChecked = checkboxes.any { it.checked && containsPersistentWord(it.text) }

        val apps = installedApps()
        val cleanOriginHint = sanitizeOriginHint(ctx.originPackage, window.packageName, ctx.vdForegroundPackages)
        val parsed = resolveAppsFromDialog(
            texts = dialogTexts,
            installedApps = apps,
            originHint = cleanOriginHint,
            vdPackages = ctx.vdForegroundPackages,
        )

        val hasJumpPhrasing = hasJumpConfirmPhrasing(dialogTexts)
        val buttonStructureAcceptable = buttonAnalysis.valid ||
            (buttonAnalysis.rejectReason == ConfirmButtonRejectReason.PERSISTENT_BUTTON_FORBIDDEN && (hasJumpPhrasing || oemHint != null))
        if (!buttonStructureAcceptable) {
            return null
        }

        // 若已知目标 App 包名直接放行；针对小程序/未知目标包，仅在明确识别出跳转句式且有双端语义或已知 OEM 拦截组件时放行：
        // 优先走方案 1（系统 Shell 真实解析目标包），失败后走方案 2（Profile 映射兜底），均未命中再由 Policy 安全降级 TARGET_UNKNOWN。
        val allowUnknownTarget = parsed.matchedExplicitJump && hasJumpPhrasing && (
            oemHint != null || parsed.isTwoPartyOrQuoted
            )
        var targetPkg = parsed.targetPackage
        if (targetPkg == null && !allowUnknownTarget) {
            return null
        }

        val resolvedInitiator = parsed.initiatorPackage ?: cleanOriginHint ?: ctx.fallbackInitiatorPackage
        if (targetPkg == null) {
            val systemPkg = ctx.resolveSystemTarget(window.taskId, window.packageName, resolvedInitiator)
                ?.trim()
                ?.takeIf { pkg ->
                    pkg.isNotEmpty() &&
                        pkg != selfPackage &&
                        pkg != window.packageName &&
                        pkg != resolvedInitiator &&
                        (apps.containsKey(pkg) || isPackageInstalled(pkg))
                }
            if (systemPkg != null) {
                targetPkg = systemPkg
            } else {
                val matchedProfile = JumpConfirmProfileMatcher.match(
                    profiles = ctx.learnedTargetProfiles,
                    initiatorPackage = resolvedInitiator,
                    targetLabel = parsed.targetLabel,
                    isPackageInstalled = { pkg -> apps.containsKey(pkg) || isPackageInstalled(pkg) },
                )
                if (matchedProfile != null) {
                    targetPkg = matchedProfile.targetPackage
                }
            }
        }

        val targetLabel = parsed.targetLabel ?: targetPkg?.let { apps[it] ?: appLabel(it) }
        val initiatorPkg = resolvedInitiator?.takeIf { it != targetPkg }

        var confidence = if (targetPkg != null) 0.65f else 0.55f
        if (buttonAnalysis.valid) confidence += 0.10f
        if (hasJumpPhrasing) confidence += 0.15f
        if (oemHint != null) confidence += 0.10f

        return ClassificationOutcome.Classified(
            interstitial = Interstitial.JumpConfirm(
                id = id,
                displayId = ctx.attributedDisplayId,
                taskId = window.taskId,
                packageName = window.packageName,
                componentName = window.componentName,
                openedAtMs = ctx.nowMs,
                initiatorPackage = initiatorPkg,
                initiatorUid = ctx.originUid,
                targetPackage = targetPkg,
                targetLabel = targetLabel,
                positiveButtonText = buttonAnalysis.positiveButton?.rawText,
                negativeButtonText = buttonAnalysis.negativeButtons.firstOrNull()?.rawText,
            ),
            buttons = buttons,
            buttonAnalysis = buttonAnalysis,
            checkboxes = checkboxes,
            alwaysAllowCheckboxChecked = persistentBoxChecked,
            safety = safety,
            confidence = confidence.coerceAtMost(1f),
            oemHint = oemHint,
            dialogTexts = dialogTexts,
        )
    }

    private fun classifiedNeedsUser(
        id: String,
        ctx: ClassificationContext,
        window: WindowInspection,
        reason: String,
        safety: WindowSafetySignals,
        dialogTexts: List<String> = emptyList(),
    ): ClassificationOutcome.Classified = ClassificationOutcome.Classified(
        interstitial = Interstitial.NeedsUser(
            id = id,
            displayId = ctx.attributedDisplayId,
            taskId = window.taskId,
            packageName = window.packageName,
            componentName = window.componentName,
            openedAtMs = ctx.nowMs,
            initiatorPackage = ctx.originPackage,
            reason = reason,
        ),
        safety = safety,
        confidence = 1f,
        dialogTexts = dialogTexts,
    )

    private fun isExplicitChooserWindow(window: WindowInspection): Boolean {
        if (window.packageName in systemPickers) return true
        val comp = window.componentName?.lowercase().orEmpty()
        return comp.contains("resolveractivity") ||
            comp.contains("chooseractivity") ||
            comp.contains("appcloneresolver") ||
            comp.contains("multiapp")
    }

    private fun isRuntimePermissionWindow(window: WindowInspection, dialogTexts: List<String>): Boolean {
        if (window.packageName in RUNTIME_PERMISSION_PACKAGES) return true
        val comp = window.componentName?.lowercase().orEmpty()
        if (RUNTIME_PERMISSION_COMPONENT_MARKERS.any { it in comp }) return true
        val hasPermId = window.nodes.any { node ->
            val rid = node.resourceId?.lowercase().orEmpty()
            RUNTIME_PERMISSION_VIEW_IDS.any { it in rid }
        }
        if (hasPermId) return true
        val hasPermButton = extractButtonInputs(window, oemHint = null).any { btn ->
            val norm = ConfirmButtons.normalize(btn.text).lowercase()
            RUNTIME_PERMISSION_BUTTONS_ZH.any { it in norm } ||
                RUNTIME_PERMISSION_BUTTONS_EN.any { it == norm || norm.contains(it) }
        }
        if (hasPermButton) return true
        if (!hasJumpConfirmPhrasing(dialogTexts)) {
            for (raw in dialogTexts) {
                val folded = Hant.fold(raw)
                val lower = folded.lowercase()
                if (RUNTIME_PERMISSION_TEXT_ZH.any { it in folded } ||
                    RUNTIME_PERMISSION_TEXT_EN.any { it in lower }
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun extractButtonInputs(window: WindowInspection, oemHint: OemJumpHint?): List<ConfirmButtonInput> {
        val checkboxTexts = window.nodes
            .asSequence()
            .filter { it.checkable || it.role in CHECKBOX_ROLES }
            .map { ConfirmButtons.normalize(it.text) }
            .filter { it.isNotEmpty() }
            .toSet()
        val fromNodes = window.nodes
            .asSequence()
            .filter { !it.checkable && it.role !in CHECKBOX_ROLES && !it.password && it.text.isNotBlank() }
            .filter { node ->
                node.role.equals("Button", ignoreCase = true) ||
                    ConfirmButtons.normalize(node.text) !in checkboxTexts
            }
            .filter { node ->
                node.clickable ||
                    node.role.equals("Button", ignoreCase = true) ||
                    isDialogButtonId(node.resourceId, oemHint)
            }
            .toList()
        val raw = if (fromNodes.isNotEmpty()) {
            fromNodes.map { ConfirmButtonInput(text = it.text, id = it.resourceId, enabled = it.enabled, clickable = it.clickable) }
        } else {
            window.elements
                .asSequence()
                .filter {
                    it.clickable && !it.editable && !it.secret && it.name.isNotBlank() &&
                        UiStates.CHECKED !in it.states &&
                        ConfirmButtons.normalize(it.name) !in checkboxTexts &&
                        !containsPersistentWord(it.name)
                }
                .map {
                    ConfirmButtonInput(
                        text = it.name,
                        id = it.resourceId,
                        enabled = UiStates.DISABLED !in it.states,
                        clickable = true,
                    )
                }
                .toList()
        }
        val preferredIds = oemHint?.preferredPositiveIds ?: DEFAULT_POSITIVE_BUTTON_IDS
        return raw.sortedBy { btn ->
            val rid = btn.id.orEmpty()
            preferredIds.indexOfFirst { rid == it || rid.endsWith("/$it") }.let { if (it >= 0) it else Int.MAX_VALUE }
        }
    }

    private fun isDialogButtonId(resourceId: String?, oemHint: OemJumpHint?): Boolean {
        val rid = resourceId ?: return false
        if (oemHint?.preferredPositiveIds?.any { rid == it || rid.endsWith("/$it") } == true) return true
        val lower = rid.lowercase()
        return lower.endsWith("button1") || lower.endsWith("button2") || lower.endsWith("button3") ||
            lower.contains("btn_allow") || lower.contains("btn_deny") || lower.contains("btn_cancel") ||
            lower.contains("allow_button") || lower.contains("deny_button") || lower.contains("positive") || lower.contains("negative")
    }

    private fun extractCheckboxes(window: WindowInspection): List<ConfirmCheckbox> =
        window.nodes
            .filter { it.checkable || it.role in CHECKBOX_ROLES }
            .map { ConfirmCheckbox(text = it.text, checked = it.checked) }

    private fun collectTexts(window: WindowInspection): List<String> {
        val ordered = LinkedHashSet<String>()
        window.title?.trim()?.takeIf { it.isNotEmpty() }?.let(ordered::add)
        for (t in window.texts) t.trim().takeIf { it.isNotEmpty() }?.let(ordered::add)
        for (n in window.nodes) n.text.trim().takeIf { it.isNotEmpty() }?.let(ordered::add)
        for (e in window.elements) e.name.trim().takeIf { it.isNotEmpty() }?.let(ordered::add)
        return ordered.toList()
    }

    private fun sanitizeOriginHint(
        originPkg: String?,
        windowPkg: String,
        vdPackages: Set<String>,
    ): String? {
        val pkg = originPkg?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if ((pkg == windowPkg && pkg !in vdPackages) || pkg == "android") return null
        if (isSystemPackage(pkg) && pkg !in vdPackages) return null
        return pkg
    }

    private data class ResolvedDialogApps(
        val initiatorPackage: String? = null,
        val targetPackage: String? = null,
        val targetLabel: String? = null,
        val matchedExplicitJump: Boolean = false,
        val isTwoPartyOrQuoted: Boolean = false,
    )

    private fun resolveAppsFromDialog(
        texts: List<String>,
        installedApps: Map<String, String>,
        originHint: String?,
        vdPackages: Set<String>,
    ): ResolvedDialogApps {
        val candidates = installedApps.entries
            .mapNotNull { (pkg, rawLabel) ->
                val label = rawLabel.trim()
                if (pkg == selfPackage || label.isEmpty()) null else Triple(pkg, label, Hant.fold(label))
            }
            .sortedByDescending { it.third.length }

        for (rawLine in texts) {
            val foldedLine = Hant.fold(rawLine)
            for ((index, pattern) in JUMP_PHRASE_PATTERNS.withIndex()) {
                val m = pattern.find(foldedLine) ?: continue
                val rawMatch = pattern.find(rawLine)
                val groups = m.groupValues
                if (groups.size >= 3) {
                    val left = groups[1]
                    val right = groups[2]
                    val rawRight = rawMatch?.groupValues?.getOrNull(2) ?: right
                    val cleanRight = cleanDialogTargetLabel(rawRight)
                    val leftMatch = findBestAppMatch(left, candidates, excludePkg = null)
                    val cleanLeft = QUOTE_STRIP_REGEX.replace(Hant.fold(left).trim(), "").trim()
                    val unwrappedLeft = cleanLeft.removePrefix("应用").trim()
                        .let { QUOTE_STRIP_REGEX.replace(it, "").trim() }
                    val initiatorPkg = when {
                        leftMatch != null && (
                            cleanLeft.equals(Hant.fold(leftMatch.second), ignoreCase = true) ||
                                unwrappedLeft.equals(Hant.fold(leftMatch.second), ignoreCase = true) ||
                                leftMatch.first in vdPackages
                            ) -> leftMatch.first
                        else -> originHint
                    }
                    // 先在不排除发起方的前提下匹配右段，避免“微信读书小程序”被误剪出前缀“微信”
                    val rightMatch = findBestAppMatch(right, candidates, excludePkg = null)
                    if (rightMatch != null && rightMatch.first != leftMatch?.first && rightMatch.first != initiatorPkg) {
                        return ResolvedDialogApps(
                            initiatorPackage = initiatorPkg,
                            targetPackage = rightMatch.first,
                            targetLabel = rightMatch.second,
                            matchedExplicitJump = true,
                            isTwoPartyOrQuoted = true,
                        )
                    }
                    if (rightMatch != null && rightMatch.first == leftMatch?.first) {
                        // 右段明确匹配了与发起方同名的主体（如“微信读书”想要打开“微信读书”小程序）：
                        // 目标并不是另一个前缀 App，而是该同名小程序/扩展页，targetPackage 留空交由 Policy/R2 处理
                        return ResolvedDialogApps(
                            initiatorPackage = initiatorPkg,
                            targetPackage = null,
                            targetLabel = cleanRight ?: rightMatch.second,
                            matchedExplicitJump = true,
                            isTwoPartyOrQuoted = true,
                        )
                    }
                    return ResolvedDialogApps(
                        initiatorPackage = initiatorPkg,
                        targetPackage = null,
                        targetLabel = cleanRight,
                        matchedExplicitJump = true,
                        isTwoPartyOrQuoted = true,
                    )
                } else if (groups.size == 2) {
                    val rawTarget = rawMatch?.groupValues?.getOrNull(1) ?: groups[1]
                    val quoted = index in QUOTED_SINGLE_PATTERN_INDICES
                    val only = findBestAppMatch(groups[1], candidates, excludePkg = null)
                    if (only != null && only.first != originHint) {
                        return ResolvedDialogApps(
                            initiatorPackage = originHint,
                            targetPackage = only.first,
                            targetLabel = only.second,
                            matchedExplicitJump = true,
                            isTwoPartyOrQuoted = quoted,
                        )
                    }
                    return ResolvedDialogApps(
                        initiatorPackage = originHint,
                        targetPackage = null,
                        targetLabel = cleanDialogTargetLabel(rawTarget),
                        matchedExplicitJump = true,
                        isTwoPartyOrQuoted = quoted,
                    )
                }
            }
        }

        if (installedApps.isEmpty()) return ResolvedDialogApps(initiatorPackage = originHint)

        // 兜底：无固定句式但对话文案明确提到已安装 App 名
        val matched = mutableListOf<Pair<String, String>>()
        val joined = texts.joinToString("\n") { Hant.fold(it) }
        val positions = HashMap<String, Int>()
        for ((pkg, rawLabel, foldedLabel) in candidates) {
            if (!isMatchableLabel(foldedLabel, joined)) continue
            val idx = findMatchIndex(joined, foldedLabel)
            if (idx >= 0) {
                val overlapsExisting = matched.any { (_, existingRaw) ->
                    val existingFolded = Hant.fold(existingRaw)
                    existingFolded.contains(foldedLabel, ignoreCase = true) && positions[existingRaw] == idx
                }
                if (!overlapsExisting) {
                    matched += pkg to rawLabel
                    positions[rawLabel] = idx
                }
            }
        }
        val sortedMatches = matched.sortedBy { positions[it.second] ?: Int.MAX_VALUE }
        if (sortedMatches.isEmpty()) return ResolvedDialogApps(initiatorPackage = originHint)
        if (sortedMatches.size == 1) {
            val single = sortedMatches.single()
            if (originHint != null && single.first == originHint) {
                return ResolvedDialogApps(initiatorPackage = originHint, targetPackage = null)
            }
            return ResolvedDialogApps(initiatorPackage = originHint, targetPackage = single.first, targetLabel = single.second)
        }
        val initiator = sortedMatches.firstOrNull { it.first == originHint || it.first in vdPackages } ?: sortedMatches.first()
        val target = sortedMatches.lastOrNull { it.first != initiator.first } ?: sortedMatches.last()
        return ResolvedDialogApps(
            initiatorPackage = initiator.first,
            targetPackage = target.first,
            targetLabel = target.second,
        )
    }

    private fun cleanDialogTargetLabel(raw: String): String? {
        val stripped = INLINE_DECORATIVE_QUOTES_REGEX.replace(raw, "")
            .replace(TRAILING_PUNCTUATION_REGEX, "")
            .replace(MULTI_WHITESPACE_REGEX, " ")
            .trim()
        return stripped.takeIf { it.isNotEmpty() }
    }

    private fun findBestAppMatch(
        segment: String,
        candidates: List<Triple<String, String, String>>,
        excludePkg: String?,
    ): Pair<String, String>? {
        val foldedSeg = Hant.fold(segment).trim()
        if (foldedSeg.isEmpty()) return null
        val cleaned = QUOTE_STRIP_REGEX.replace(foldedSeg, "").trim()
        for ((pkg, rawLabel, foldedLabel) in candidates) {
            if (pkg == excludePkg) continue
            if (cleaned.equals(foldedLabel, ignoreCase = true)) return pkg to rawLabel
        }
        for ((pkg, rawLabel, foldedLabel) in candidates) {
            if (pkg == excludePkg) continue
            if (isMatchableLabel(foldedLabel, foldedSeg) && findMatchIndex(foldedSeg, foldedLabel) >= 0) {
                return pkg to rawLabel
            }
        }
        return null
    }

    private fun isMatchableLabel(foldedLabel: String, foldedText: String): Boolean {
        if (foldedLabel.length >= 2) return true
        // 单字标签只在被引号包围或独立成词时才参与匹配，降低误匹配概率
        return foldedText.contains("“$foldedLabel”") ||
            foldedText.contains("「$foldedLabel」") ||
            foldedText.contains("\"$foldedLabel\"")
    }

    private fun findMatchIndex(foldedText: String, foldedLabel: String): Int {
        if (foldedLabel.all { it.code < 128 && (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') }) {
            val escaped = Regex.escape(foldedLabel)
            val pattern = "(?<![A-Za-z0-9])$escaped(?![A-Za-z0-9])"
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            return regex.find(foldedText)?.range?.first ?: -1
        }
        return foldedText.indexOf(foldedLabel, ignoreCase = true)
    }

    private fun hasJumpConfirmPhrasing(texts: List<String>): Boolean {
        for (raw in texts) {
            val folded = Hant.fold(raw)
            val lower = folded.lowercase()
            if (JUMP_PHRASE_MARKERS_ZH.any { it in folded } ||
                JUMP_PHRASE_MARKERS_EN.any { it in lower }
            ) {
                return true
            }
        }
        return false
    }

    private fun containsPersistentWord(text: String): Boolean =
        ConfirmButtons.containsPersistentKeyword(text) || "一律" in Hant.fold(text)

    private fun matchOemHint(pkg: String, component: String?): OemJumpHint? {
        val exact = OEM_HINTS.firstOrNull { hint ->
            hint.packageName == pkg && component != null && hint.componentMarkers.any { marker -> component.contains(marker, ignoreCase = true) }
        }
        return exact ?: OEM_HINTS.firstOrNull { it.packageName == pkg }
    }

    companion object {
        private const val MAX_WALK_DEPTH = 40
        private const val FULL_SCREEN_SHARE = 0.8f
        private val CHECKBOX_ROLES = setOf("CheckBox", "Switch", "ToggleButton", "CompoundButton")
        private val QUOTE_STRIP_REGEX = Regex("^[“”\"'「」『』《》〈〉\\s]+|[“”\"'「」『』《》〈〉.\\s]+$")
        private val INLINE_DECORATIVE_QUOTES_REGEX = Regex("[“”\"「」『』]")
        private val TRAILING_PUNCTUATION_REGEX = Regex("[？?!！。.\\s]+$")
        private val MULTI_WHITESPACE_REGEX = Regex("\\s+")
        private val QUOTED_SINGLE_PATTERN_INDICES = setOf(4, 6)

        private val DEFAULT_POSITIVE_BUTTON_IDS = listOf(
            "android:id/button1",
            "button1",
            "btn_allow",
            "allow_button",
            "btn_positive",
            "positive",
        )

        private val OEM_HINTS = listOf(
            OemJumpHint(
                packageName = "com.oplus.securitypermission",
                componentMarkers = listOf("AppStartConfirmDialogActivity", "CheckAllowStartActivity"),
                preferredPositiveIds = listOf("android:id/button1", "button1"),
            ),
            OemJumpHint(
                packageName = "com.oplus.safecenter",
                componentMarkers = listOf("AppStartConfirmDialogActivity", "ConfirmStart"),
                preferredPositiveIds = listOf("android:id/button1", "button1"),
            ),
            OemJumpHint(
                packageName = "com.coloros.safecenter",
                componentMarkers = listOf("AppStartConfirm", "ConfirmStart"),
            ),
            OemJumpHint(
                packageName = "com.miui.securitycenter",
                componentMarkers = listOf("ConfirmStartActivity", "AppStartConfirm"),
                preferredPositiveIds = listOf("android:id/button1", "button1"),
            ),
            OemJumpHint(
                packageName = "com.lbe.security.miui",
                componentMarkers = listOf("ConfirmStart", "AppStart"),
            ),
            OemJumpHint(
                packageName = "com.huawei.systemmanager",
                componentMarkers = listOf("AppStartConfirm", "JumpConfirm"),
            ),
            OemJumpHint(
                packageName = "com.hihonor.systemmanager",
                componentMarkers = listOf("AppStartConfirm", "JumpConfirm"),
            ),
            OemJumpHint(
                packageName = "com.vivo.permissionmanager",
                componentMarkers = listOf("AppStartConfirm", "JumpConfirm"),
            ),
            OemJumpHint(
                packageName = "com.iqoo.secure",
                componentMarkers = listOf("AppStartConfirm", "JumpConfirm"),
            ),
        )

        private val RUNTIME_PERMISSION_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.samsung.android.permissioncontroller",
        )

        private val RUNTIME_PERMISSION_COMPONENT_MARKERS = listOf(
            "grantpermissions",
            "requestpermissions",
            "runtimepermission",
        )

        private val RUNTIME_PERMISSION_VIEW_IDS = listOf(
            "permission_allow_button",
            "permission_allow_foreground_only_button",
            "permission_allow_one_time_button",
            "permission_deny_button",
            "permission_message",
        )

        private val RUNTIME_PERMISSION_BUTTONS_ZH = listOf(
            "仅在使用中允许",
            "使用应用时允许",
            "仅在使用该应用时允许",
            "仅充电时允许",
        )

        private val RUNTIME_PERMISSION_BUTTONS_EN = listOf(
            "while using the app",
            "only while using the app",
            "only while using app",
            "allow all the time",
        )

        private val RUNTIME_PERMISSION_TEXT_ZH = listOf(
            "位置信息",
            "访问您的位置",
            "拍摄照片和录制视频",
            "访问您设备上的照片",
            "发送通知",
            "拨打电话",
            "读取联系人",
            "录音权限",
        )

        private val RUNTIME_PERMISSION_TEXT_EN = listOf(
            "access this device's location",
            "take pictures and record video",
            "access photos and videos",
            "record audio",
            "send you notifications",
            "access your contacts",
            "make and manage phone calls",
        )

        private val JUMP_PHRASE_MARKERS_ZH = listOf(
            "想要打开",
            "申请打开",
            "请求打开",
            "尝试打开",
            "即将打开",
            "允许打开",
            "跳转至",
            "跳转到",
            "前往",
        )

        private val JUMP_PHRASE_MARKERS_EN = listOf(
            "wants to open",
            "want to open",
            "wants to launch",
            "requesting to open",
            "attempting to open",
            "open ",
            "switch to ",
        )

        // 先匹配「A 想要打开 B」等双应用句式，再匹配单目标句式
        private val JUMP_PHRASE_PATTERNS = listOf(
            Regex("""(.+?)\s*(?:想要|申请|请求|尝试|即将)\s*(?:打开|开启|启动|进入|跳转(?:到|至)?)\s*(.+)"""),
            Regex("""是否允许\s*(.+?)\s*(?:打开|开启|启动|进入|跳转(?:到|至)?)\s*(.+)"""),
            Regex("""(.+?)\s*(?:wants?|is trying|requests?|requesting)\s+to\s+(?:open|launch|start)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""Allow\s+(.+?)\s+to\s+(?:open|launch|start)\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:即将|确认|是否允许)?(?:打开|开启|跳转(?:到|至)?)\s*[“"「『]([^”"」』]+)[”"」』]"""),
            Regex("""(?:即将|确认|是否允许)?(?:打开|开启|跳转(?:到|至)?)\s*([^\s?？]+)"""),
            Regex("""Open\s+["“]([^"”]+)["”]""", RegexOption.IGNORE_CASE),
            Regex("""Open\s+([^\s?]+)""", RegexOption.IGNORE_CASE),
        )

        /** 吸收自原 `MainScreenSpill.needsUser`：有密码框、或不可读且防截屏时需交由用户亲自处理。 */
        fun needsUser(passwordField: Boolean, readable: Boolean, secureWindow: Boolean): Boolean =
            passwordField || (!readable && secureWindow)

        /** 吸收自原 `MainScreenSpill.authenticatorUi`：无桌面入口的系统认证器包必须交由用户处理。 */
        fun authenticatorUi(pkg: String, authenticators: Set<String>, hasLauncher: Boolean): Boolean =
            pkg in authenticators && !hasLauncher

        /** 构造默认生产环境的 [InterstitialClassifier]（可测注入部分覆盖）。 */
        fun create(
            context: Context,
            selfPackage: String = context.packageName,
            systemPickers: Set<String> = SystemPickers.resolve(context.packageManager),
            isSystemPackage: (String) -> Boolean,
            installedAppsProvider: () -> Map<String, String>,
            profilesProvider: () -> Map<String, List<String>> = { emptyMap() },
        ): InterstitialClassifier {
            val pm = context.packageManager
            return InterstitialClassifier(
                selfPackage = selfPackage,
                systemPickers = systemPickers,
                isSystemPackage = isSystemPackage,
                isAuthenticatorPackage = { pkg ->
                    val am = context.getSystemService(AccountManager::class.java)
                    val authenticators = runCatching { am?.authenticatorTypes?.map { it.packageName }?.toSet() }.getOrNull().orEmpty()
                    if (pkg !in authenticators) false
                    else {
                        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
                        val hasLauncher = runCatching { pm.queryIntentActivities(launcher, 0).isNotEmpty() }.getOrDefault(true)
                        authenticatorUi(pkg, authenticators, hasLauncher)
                    }
                },
                appLabel = { pkg ->
                    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                        .getOrNull()?.takeIf { it.isNotBlank() }
                },
                installedApps = installedAppsProvider,
                profiles = profilesProvider,
                isPackageInstalled = { pkg ->
                    runCatching {
                        pm.getApplicationInfo(pkg, 0)
                        true
                    }.getOrDefault(false)
                },
            )
        }

        /** 按 Android [Context] 与 Shell 系统包查询构造 [InterstitialClassifier]，支持测试注入 [isSystemPackageOverride]。 */
        fun fromAndroidContext(
            context: Context,
            systemPickers: Set<String> = SystemPickers.resolve(context.packageManager),
            isSystemByShell: (String) -> Boolean,
            installedAppsProvider: () -> Map<String, String>,
            profilesProvider: () -> Map<String, List<String>> = { emptyMap() },
            isSystemPackageOverride: ((String) -> Boolean)? = null,
        ): InterstitialClassifier {
            val pm = context.packageManager
            return create(
                context = context,
                selfPackage = context.packageName,
                systemPickers = systemPickers,
                isSystemPackage = { pkg ->
                    if (isSystemPackageOverride?.invoke(pkg) == true) true
                    else isAndroidSystemPackage(pm, pkg, systemPickers) ?: isSystemByShell(pkg)
                },
                installedAppsProvider = installedAppsProvider,
                profilesProvider = profilesProvider,
            )
        }

        /** 读取已安装且可从桌面启动的第三方/系统应用标签映射，供跳转确认目标匹配使用。 */
        fun installedAppLabels(
            pm: PackageManager,
            selfPackage: String,
            launcherPackages: Set<String>,
        ): Map<String, String> {
            val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = runCatching { pm.queryIntentActivities(launch, 0) }.getOrDefault(emptyList())
            val out = LinkedHashMap<String, String>(activities.size + 1)
            for (info in activities) {
                val appInfo = info.activityInfo?.applicationInfo ?: continue
                val pkg = appInfo.packageName ?: continue
                if (pkg == selfPackage || pkg in launcherPackages) continue
                val label = runCatching { pm.getApplicationLabel(appInfo).toString().trim() }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: runCatching { info.loadLabel(pm).toString().trim() }
                        .getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: continue
                out.putIfAbsent(pkg, label)
            }
            val settingsPkg = SystemPickers.settingsPackage(pm)
            if (settingsPkg != null && settingsPkg !in out && settingsPkg != selfPackage && settingsPkg !in launcherPackages) {
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(settingsPkg, 0)).toString().trim()
                }.getOrNull()?.takeIf { it.isNotEmpty() }
                if (label != null) out[settingsPkg] = label
            }
            return out
        }

        /** 查询指定 Activity 是否带 `FLAG_EXCLUDE_FROM_RECENTS`（吸收自 `needsUserWindow`）。 */
        fun isExcludeFromRecents(pm: PackageManager, pkg: String, component: String?): Boolean {
            if (component.isNullOrBlank()) return false
            val cn = ComponentName.unflattenFromString(component)
                ?: ComponentName(pkg, component)
            val info = runCatching { pm.getActivityInfo(cn, 0) }.getOrNull() ?: return false
            return info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0
        }

        /** 判断包是否为系统包（仅在找不到时交由调用方询问 shell）。 */
        fun isAndroidSystemPackage(pm: PackageManager, pkg: String, systemPickers: Set<String>): Boolean? {
            if (pkg in systemPickers) return true
            val flags = runCatching { pm.getApplicationInfo(pkg, 0).flags }.getOrNull() ?: return null
            return flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }

        /**
         * 从真实无障碍窗口抓取 [WindowInspection] 与按钮节点引用，供 [InterstitialClassifier.classify]
         * 以及后续原地 `AccessibilityNodeInfo.ACTION_CLICK`（绝不走主屏手势注入）使用。
         */
        fun inspectAccessibilityWindow(
            window: AccessibilityWindowInfo,
            displayId: Int,
            taskId: Int,
            componentName: String?,
            isFlagSecure: Boolean,
            isExcludeFromRecents: Boolean,
            screenWidth: Int,
            screenHeight: Int,
        ): AccessibilityWindowSnapshot? {
            val root = window.root ?: return null
            val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val read = ScreenReader.read(listOf(window), screenWidth, screenHeight)
            val nodes = ArrayList<NodeInspection>()
            val texts = ArrayList<String>()
            val seenTexts = HashSet<String>()
            val clickableButtons = ArrayList<ClickableButtonNode>()
            var hasPassword = read.passwordField

            fun addText(s: String?) {
                val clean = s?.trim()?.takeIf { it.isNotEmpty() } ?: return
                if (seenTexts.add(clean)) texts += clean
            }

            fun siblingTextForCheckable(node: AccessibilityNodeInfo): String? {
                val own = ScreenReader.ownLabel(node)
                if (!own.isNullOrBlank()) return own
                val parent = node.parent ?: return null
                val parts = ArrayList<String>()
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChild(i) ?: continue
                    if (sibling == node) continue
                    ScreenReader.ownLabel(sibling)?.let(parts::add)
                }
                return parts.joinToString(" ").trim().takeIf { it.isNotEmpty() }
            }

            fun hasCheckableDescendant(node: AccessibilityNodeInfo, remainingDepth: Int = 4): Boolean {
                if (remainingDepth <= 0) return false
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    if (child.isCheckable) return true
                    if (hasCheckableDescendant(child, remainingDepth - 1)) return true
                }
                return false
            }

            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > MAX_WALK_DEPTH) return
                if (n.isVisibleToUser) {
                    if (n.isPassword) hasPassword = true
                    val role = n.className?.toString()?.substringAfterLast('.')?.ifEmpty { null } ?: "View"
                    val wrapsCheckable = !n.isCheckable &&
                        !role.equals("Button", ignoreCase = true) &&
                        hasCheckableDescendant(n)
                    val label = if (n.isCheckable) {
                        siblingTextForCheckable(n) ?: ""
                    } else if (wrapsCheckable) {
                        ""
                    } else {
                        ScreenReader.labelOf(n)
                    }
                    if (!n.isPassword && label.isNotBlank()) addText(label)
                    val rid = n.viewIdResourceName
                    val inspection = NodeInspection(
                        text = if (n.isPassword) "" else label,
                        role = role,
                        resourceId = rid,
                        clickable = n.isClickable && !wrapsCheckable,
                        enabled = n.isEnabled,
                        checkable = n.isCheckable,
                        checked = n.isChecked,
                        password = n.isPassword,
                    )
                    nodes += inspection
                    if (!n.isCheckable && !wrapsCheckable && !n.isPassword && label.isNotBlank() &&
                        (n.isClickable || role.equals("Button", ignoreCase = true))
                    ) {
                        clickableButtons += ClickableButtonNode(
                            input = ConfirmButtonInput(
                                text = label,
                                id = rid,
                                enabled = n.isEnabled,
                                clickable = n.isClickable,
                            ),
                            node = n,
                        )
                    }
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { walk(it, depth + 1) }
                }
            }

            walk(root, 0)
            read.texts.forEach(::addText)
            val bounds = Rect().also(window::getBoundsInScreen)
            val isDialogWindow = bounds.width() > 0 && bounds.height() > 0 &&
                (bounds.width() < screenWidth * FULL_SCREEN_SHARE || bounds.height() < screenHeight * FULL_SCREEN_SHARE)

            val inspection = WindowInspection(
                displayId = displayId,
                taskId = taskId,
                windowId = window.id,
                windowType = window.type,
                packageName = pkg,
                componentName = componentName,
                title = window.title?.toString()?.takeIf { it.isNotBlank() },
                texts = texts,
                elements = read.entries.map { it.element },
                nodes = nodes,
                readable = texts.isNotEmpty() || read.entries.isNotEmpty(),
                hasPasswordField = hasPassword,
                isFlagSecure = isFlagSecure,
                isExcludeFromRecents = isExcludeFromRecents,
                isDialogWindow = isDialogWindow,
            )
            return AccessibilityWindowSnapshot(inspection, clickableButtons)
        }
    }
}
