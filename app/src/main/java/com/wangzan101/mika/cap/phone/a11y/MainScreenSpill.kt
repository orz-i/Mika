package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityEvent

/**
 * 主屏与托管虚拟屏之间的轻量辅助判断（跨屏归因与时间窗判定已由 `CustodyAttribution` 与 [DisplayCustodian] 接管）。
 */
internal object MainScreenSpill {
    const val MAIN = 0
    const val NEW = -1

    fun isUserTap(eventType: Int): Boolean = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> true
        else -> false
    }

    /** 窗口保护判断（已迁移并转发至 [InterstitialClassifier.needsUser]）。 */
    fun needsUser(passwordField: Boolean, readable: Boolean, secureWindow: Boolean): Boolean =
        InterstitialClassifier.needsUser(passwordField, readable, secureWindow)

    /** 认证器 UI 判断（已迁移并转发至 [InterstitialClassifier.authenticatorUi]）。 */
    fun authenticatorUi(pkg: String, authenticators: Set<String>, hasLauncher: Boolean): Boolean =
        InterstitialClassifier.authenticatorUi(pkg, authenticators, hasLauncher)
}
