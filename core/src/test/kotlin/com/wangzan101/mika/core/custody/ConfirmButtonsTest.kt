package com.wangzan101.mika.core.custody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.agent.use.ui.Hant

class ConfirmButtonsTest {

    @Test
    fun `accepts valid simplified chinese pairs`() {
        val pairs = listOf(
            "允许" to "拒绝",
            "打开" to "取消",
            "同意" to "不允许",
            "前往" to "禁止",
            "确定" to "暂不打开",
            "继续" to "关闭",
            "打开淘宝" to "拒绝",
            "仅本次允许" to "不允许",
            "允许 (3s)" to "取消",
        )
        for ((pos, neg) in pairs) {
            val analysis = ConfirmButtons.analyzeTexts(listOf(neg, pos))
            assertTrue("Expected valid for [$neg, $pos], got ${analysis.rejectReason}", analysis.valid)
            assertEquals(pos, analysis.positiveButton?.rawText)
            assertEquals(listOf(neg), analysis.negativeButtons.map { it.rawText })
        }
    }

    @Test
    fun `accepts traditional chinese via Hant folding`() {
        val pairs = listOf(
            "允許" to "拒絕",
            "打開" to "取消",
            "開啟" to "取消",
            "繼續" to "不允許",
            "確定" to "關閉",
            "前往" to "攔截",
            "僅此一次" to "暫不",
        )
        for ((pos, neg) in pairs) {
            val analysis = ConfirmButtons.analyzeTexts(listOf(neg, pos))
            assertTrue("Expected valid for TC [$neg, $pos], got ${analysis.rejectReason}", analysis.valid)
            assertEquals(pos, analysis.positiveButton?.rawText)
        }
    }

    @Test
    fun `accepts english pairs including negation containing allow`() {
        val pairs = listOf(
            "Allow" to "Deny",
            "Open" to "Cancel",
            "Continue" to "Don't allow",
            "Confirm" to "Do not allow",
            "Allow once" to "Reject",
            "Proceed" to "Disallow",
            "Open Taobao" to "Decline",
        )
        for ((pos, neg) in pairs) {
            val analysis = ConfirmButtons.analyzeTexts(listOf(neg, pos))
            assertTrue("Expected valid for EN [$neg, $pos], got ${analysis.rejectReason}", analysis.valid)
            assertEquals(pos, analysis.positiveButton?.rawText)
        }
        assertEquals(ConfirmButtonRole.NEGATIVE, ConfirmButtons.classify("Don’t allow"))
        assertEquals(ConfirmButtonRole.NEGATIVE, ConfirmButtons.classify("不允许"))
        assertEquals(ConfirmButtonRole.NEGATIVE, ConfirmButtons.classify("不允許"))
    }

    @Test
    fun `forbids persistent authorization words across simplified traditional and english`() {
        val forbiddenDialogs = listOf(
            listOf("拒绝", "始终允许"),
            listOf("取消", "始终允许打开"),
            listOf("拒絕", "總是允許"),
            listOf("取消", "一律允許打開"),
            listOf("取消", "一律允許開啟"),
            listOf("取消", "总是打开"),
            listOf("拒绝", "记住选择并允许"),
            listOf("取消", "默认打开"),
            listOf("Deny", "Always allow"),
            listOf("Cancel", "Allow always"),
            listOf("拒绝", "仅本次允许", "始终允许"),
            listOf("Cancel", "Don't ask again"),
            listOf("拒绝", "不再询问"),
        )
        for (buttons in forbiddenDialogs) {
            val analysis = ConfirmButtons.analyzeTexts(buttons)
            assertFalse("Expected rejection for $buttons", analysis.valid)
            assertEquals(
                "Unexpected rejectReason for $buttons",
                ConfirmButtonRejectReason.PERSISTENT_BUTTON_FORBIDDEN,
                analysis.rejectReason,
            )
            assertNull(analysis.positiveButton)
            assertTrue("Expected persistentButtons non-empty for $buttons", analysis.persistentButtons.isNotEmpty())
        }
        assertTrue(ConfirmButtons.containsPersistentKeyword("始終允許"))
        assertTrue(ConfirmButtons.containsPersistentKeyword("始终允许打开"))
        assertTrue(ConfirmButtons.containsPersistentKeyword("一律允許打開"))
        assertTrue(ConfirmButtons.containsPersistentKeyword("一律允許開啟"))
        assertTrue(ConfirmButtons.containsPersistentKeyword("不再詢問"))
        assertTrue(ConfirmButtons.containsPersistentKeyword("Remember my choice"))
        assertFalse(ConfirmButtons.containsPersistentKeyword("仅本次允许"))
    }

    @Test
    fun `rejects ambiguous missing positive or missing negative structures`() {
        val multiplePos = ConfirmButtons.analyzeTexts(listOf("拒绝", "允许", "打开"))
        assertFalse(multiplePos.valid)
        assertEquals(ConfirmButtonRejectReason.MULTIPLE_POSITIVE_BUTTONS, multiplePos.rejectReason)

        val missingNeg = ConfirmButtons.analyzeTexts(listOf("允许", "查看详情"))
        assertFalse(missingNeg.valid)
        assertEquals(ConfirmButtonRejectReason.NO_NEGATIVE_BUTTON, missingNeg.rejectReason)

        val missingPos = ConfirmButtons.analyzeTexts(listOf("取消", "拒绝"))
        assertFalse(missingPos.valid)
        assertEquals(ConfirmButtonRejectReason.NO_POSITIVE_BUTTON, missingPos.rejectReason)

        val disabledPos = ConfirmButtons.analyze(
            listOf(
                ConfirmButtonInput("拒绝", enabled = true, clickable = true),
                ConfirmButtonInput("允许", enabled = false, clickable = false),
            ),
        )
        assertFalse(disabledPos.valid)
        assertEquals(ConfirmButtonRejectReason.NO_POSITIVE_BUTTON, disabledPos.rejectReason)
    }

    @Test
    fun `does not false match short words inside longer unrelated text`() {
        assertEquals(ConfirmButtonRole.UNKNOWN, ConfirmButtons.classify("是否是本人操作"))
        assertEquals(ConfirmButtonRole.UNKNOWN, ConfirmButtons.classify("Book a trip"))
        assertEquals(ConfirmButtonRole.UNKNOWN, ConfirmButtons.classify("Know more"))
    }
}
