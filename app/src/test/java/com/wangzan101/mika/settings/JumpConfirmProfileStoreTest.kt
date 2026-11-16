package com.wangzan101.mika.settings

import com.wangzan101.mika.core.custody.JumpConfirmProfileMatcher
import com.wangzan101.mika.core.custody.JumpProfileSource
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpConfirmProfileStoreTest {

    @Test
    fun `records learned profiles, persists across reload, and preserves disabled and manual rules`() {
        val dir = Files.createTempDirectory("jc-profiles").toFile()
        val file = dir.resolve("jump-confirm-profiles.json")
        try {
            val store = JumpConfirmProfileStore(file)
            store.recordLearned(
                initiatorPackage = "com.tencent.weread",
                targetLabel = "“微信读书”小程序",
                targetPackage = "com.tencent.mm",
                nowMs = 1_000L,
            )
            assertEquals(1, store.current().profiles.size)
            val learnedId = JumpConfirmProfileMatcher.profileId("com.tencent.weread", "微信读书小程序")
            assertEquals(JumpProfileSource.LEARNED, store.current().profiles.single().source)
            assertEquals("com.tencent.mm", store.current().profiles.single().targetPackage)

            // 重载文件应保留规则与 autoLearn 设置
            val reloaded = JumpConfirmProfileStore(file)
            assertEquals(1, reloaded.current().profiles.size)
            assertEquals("com.tencent.mm", reloaded.current().profiles.single().targetPackage)

            // 用户禁用该条规则后，后续自动学习不得静默重新开启或覆盖
            reloaded.setEnabled(learnedId, false)
            reloaded.recordLearned(
                initiatorPackage = "com.tencent.weread",
                targetLabel = "微信读书小程序",
                targetPackage = "com.tencent.mm",
                nowMs = 2_000L,
            )
            assertFalse(reloaded.current().profiles.single().enabled)

            // 用户手动覆盖后，source 切换为 MANUAL，自动学习不得改写，clearLearned 保留手动条目
            assertTrue(
                reloaded.upsertManual(
                    initiatorPackage = "com.tencent.weread",
                    targetLabel = "微信读书小程序",
                    targetPackage = "com.tencent.mm",
                    enabled = true,
                    nowMs = 3_000L,
                ),
            )
            assertEquals(JumpProfileSource.MANUAL, reloaded.current().profiles.single().source)

            reloaded.recordLearned(
                initiatorPackage = "com.xingin.xhs",
                targetLabel = "小红书小程序",
                targetPackage = "com.tencent.mm",
                nowMs = 4_000L,
            )
            assertEquals(2, reloaded.current().profiles.size)

            reloaded.clearLearned()
            assertEquals(1, reloaded.current().profiles.size)
            assertEquals("com.tencent.weread", reloaded.current().profiles.single().initiatorPackage)

            // 关闭 autoLearn 后不再记录新的自动规则
            reloaded.setAutoLearn(false)
            reloaded.recordLearned(
                initiatorPackage = "com.xingin.xhs",
                targetLabel = "小红书小程序",
                targetPackage = "com.tencent.mm",
                nowMs = 5_000L,
            )
            assertEquals(1, reloaded.current().profiles.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `corrupt file marks unavailable until reset and rejects self-targeting manual edits`() {
        val dir = Files.createTempDirectory("jc-corrupt").toFile()
        val file = dir.resolve("jump-confirm-profiles.json").apply { writeText("{not-json") }
        try {
            val store = JumpConfirmProfileStore(file)
            assertFalse(store.current().available)
            assertFalse(store.upsertManual("com.a", "标签", "com.b"))

            store.reset()
            assertTrue(store.current().available)
            assertFalse(store.upsertManual("com.a", "标签", "com.a"))
            assertTrue(store.upsertManual("com.a", "标签", "com.b"))
            assertEquals(1, store.current().profiles.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
