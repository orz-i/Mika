package com.wangzan101.mika.core.atlas

import com.agent.use.ui.Selector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入框里的字是用户这次打进去的内容，不是页面地标：不能进图谱，也不能出现在提示里。 */
class AtlasSanitizerTypeTextTest {
    private val bounds = listOf(0, 0, 10, 10)

    @Test
    fun `输入动作不保留输入内容`() {
        val sel = Selector("EditText", "安卓开发", "et_search", bounds, 0, uniqueAtRecord = true)

        val cleaned = AtlasSanitizer.cleanSelector(sel, isTypeAction = true)

        assertEquals("", cleaned.name)
        assertEquals("et_search", cleaned.resourceId)
        assertFalse(cleaned.uniqueAtRecord)
    }

    @Test
    fun `非输入动作照旧保留元素上的文字`() {
        val sel = Selector("Button", "搜索", "btn_search", bounds, 0, uniqueAtRecord = true)

        val cleaned = AtlasSanitizer.cleanSelector(sel, isTypeAction = false)

        assertEquals("搜索", cleaned.name)
        assertTrue(cleaned.uniqueAtRecord)
    }
}
