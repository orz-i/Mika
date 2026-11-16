package com.wangzan101.mika.core.custody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JumpConfirmProfilesTest {

    @Test
    fun `normalizeLabel folds traditional chinese, strips decorative quotes and spaces`() {
        assertEquals("开宝小程序", JumpConfirmProfileMatcher.normalizeLabel("“開寶” 小程序？"))
        assertEquals("微信读书小程序", JumpConfirmProfileMatcher.normalizeLabel("「微信读书」 小程序！"))
        assertEquals("wechat mini program", JumpConfirmProfileMatcher.normalizeLabel("  \"WeChat  Mini Program\"  "))
        assertEquals(
            JumpConfirmProfileMatcher.profileId("com.tencent.weread", "“微信读书”小程序"),
            JumpConfirmProfileMatcher.profileId("COM.TENCENT.WEREAD", "微信读书 小程序"),
        )
    }

    @Test
    fun `match respects initiator isolation, enabled flag, manual priority, and installed check`() {
        val learnedWeRead = JumpConfirmProfile(
            id = JumpConfirmProfileMatcher.profileId("com.tencent.weread", "微信读书小程序"),
            initiatorPackage = "com.tencent.weread",
            targetLabel = "微信读书小程序",
            targetPackage = "com.tencent.mm",
            enabled = true,
            source = JumpProfileSource.LEARNED,
            updatedAtMs = 1_000L,
        )
        val disabledOther = JumpConfirmProfile(
            id = JumpConfirmProfileMatcher.profileId("com.xingin.xhs", "小红书小程序"),
            initiatorPackage = "com.xingin.xhs",
            targetLabel = "小红书小程序",
            targetPackage = "com.tencent.mm",
            enabled = false,
            source = JumpProfileSource.LEARNED,
            updatedAtMs = 2_000L,
        )

        val hit = JumpConfirmProfileMatcher.match(
            profiles = listOf(learnedWeRead, disabledOther),
            initiatorPackage = "com.tencent.weread",
            targetLabel = "「微信读书」 小程序",
            isPackageInstalled = { it == "com.tencent.mm" },
        )
        assertEquals("com.tencent.mm", hit?.targetPackage)

        // 不同发起方不串用同名展示名称
        assertNull(
            JumpConfirmProfileMatcher.match(
                profiles = listOf(learnedWeRead),
                initiatorPackage = "com.ss.android.ugc.aweme",
                targetLabel = "微信读书小程序",
            ),
        )

        // 用户关闭该条规则时不再自动匹配
        assertNull(
            JumpConfirmProfileMatcher.match(
                profiles = listOf(disabledOther),
                initiatorPackage = "com.xingin.xhs",
                targetLabel = "小红书小程序",
            ),
        )

        // 目标 App 未安装时不误匹配
        assertNull(
            JumpConfirmProfileMatcher.match(
                profiles = listOf(learnedWeRead),
                initiatorPackage = "com.tencent.weread",
                targetLabel = "微信读书小程序",
                isPackageInstalled = { false },
            ),
        )

        // 手动规则优先于自动学习规则
        val manualOverride = learnedWeRead.copy(
            targetPackage = "com.eg.android.AlipayGphone",
            source = JumpProfileSource.MANUAL,
            updatedAtMs = 500L,
        )
        val preferred = JumpConfirmProfileMatcher.match(
            profiles = listOf(learnedWeRead, manualOverride),
            initiatorPackage = "com.tencent.weread",
            targetLabel = "微信读书小程序",
        )
        assertEquals("com.eg.android.AlipayGphone", preferred?.targetPackage)
    }
}
