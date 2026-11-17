package com.wangzan101.mika.atlas

import com.agent.use.a11y.ScreenBounds
import com.agent.use.a11y.ScreenNodeDescriptor
import com.wangzan101.mika.cap.phone.a11y.ScreenReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReaderSignatureTest {
    @Test fun normalizeTopActivityHandlesBareAndPrefixedClassNames() {
        assertEquals(
            "com.tencent.mm/com.tencent.mm.ui.LauncherUI",
            ScreenReader.normalizeTopActivity("com.tencent.mm", ".ui.LauncherUI"),
        )
        assertEquals(
            "com.tencent.mm/com.tencent.mm.ui.LauncherUI",
            ScreenReader.normalizeTopActivity("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"),
        )
        assertEquals(
            "com.tencent.mm/com.tencent.mm.LauncherUI",
            ScreenReader.normalizeTopActivity("com.tencent.mm", "LauncherUI"),
        )
        assertEquals(
            "com.tencent.mm/com.tencent.mm.ui.LauncherUI",
            ScreenReader.normalizeTopActivity("com.tencent.mm", "com.tencent.mm/.ui.LauncherUI"),
        )
        assertNull(ScreenReader.normalizeTopActivity("com.tencent.mm", null))
        assertNull(ScreenReader.normalizeTopActivity("com.tencent.mm", "  "))
        assertNull(ScreenReader.normalizeTopActivity("com.tencent.mm", "com.tencent.mm"))
    }

    @Test fun extractSignatureSkipsProtectedAndBlankScreens() {
        val nodes = listOf(
            ScreenNodeDescriptor(
                role = "FrameLayout",
                bounds = ScreenBounds(0, 0, 999, 999),
                children = listOf(
                    ScreenNodeDescriptor("TextView", text = "登录", bounds = ScreenBounds(40, 40, 200, 100)),
                    ScreenNodeDescriptor(
                        role = "EditText",
                        text = "should_never_leak",
                        hintText = "请输入密码",
                        isHint = false,
                        editable = true,
                        password = true,
                        bounds = ScreenBounds(40, 200, 900, 280),
                    ),
                    ScreenNodeDescriptor("Button", text = "确定", clickable = true, bounds = ScreenBounds(40, 340, 900, 420)),
                ),
            ),
        )

        assertNull(ScreenReader.extractSignature(nodes, "com.example.auth", "com.example.auth/.LoginActivity", protected = true))
        assertNull(ScreenReader.extractSignature(emptyList(), "com.example.auth", "com.example.auth/.LoginActivity", protected = false))

        val sig = ScreenReader.extractSignature(nodes, "com.example.auth", ".LoginActivity", protected = false)
        assertNotNull(sig)
        assertEquals("com.example.auth/com.example.auth.LoginActivity", sig!!.activity)
        assertEquals("LoginActivity", sig.shortActivity)
        assertFalse(sig.anchors.any { "should_never_leak" in it })
        assertTrue("确定" in sig.anchors)
    }

    @Test fun extractSignatureMatchesSamePageAcrossDynamicListAndSeparatesTabs() {
        fun chatTab(listItems: List<String>, selectedTab: String): List<ScreenNodeDescriptor> = listOf(
            ScreenNodeDescriptor(
                role = "FrameLayout",
                bounds = ScreenBounds(0, 0, 999, 999),
                children = listOf(
                    ScreenNodeDescriptor("TextView", text = selectedTab, resourceId = "com.chat:id/header_title", bounds = ScreenBounds(30, 30, 300, 90)),
                    ScreenNodeDescriptor(
                        role = "RecyclerView",
                        resourceId = "com.chat:id/main_list",
                        scrollable = true,
                        isListContainer = true,
                        bounds = ScreenBounds(0, 120, 999, 860),
                        children = listItems.mapIndexed { i, title ->
                            ScreenNodeDescriptor("TextView", text = title, bounds = ScreenBounds(20, 130 + i * 90, 900, 200 + i * 90))
                        },
                    ),
                    ScreenNodeDescriptor("TextView", text = "消息", resourceId = "com.chat:id/tab_msg", clickable = true, selected = selectedTab == "消息", bounds = ScreenBounds(0, 880, 330, 999)),
                    ScreenNodeDescriptor("TextView", text = "联系人", resourceId = "com.chat:id/tab_contacts", clickable = true, selected = selectedTab == "联系人", bounds = ScreenBounds(333, 880, 666, 999)),
                    ScreenNodeDescriptor("TextView", text = "我的", resourceId = "com.chat:id/tab_me", clickable = true, selected = selectedTab == "我的", bounds = ScreenBounds(667, 880, 999, 999)),
                ),
            ),
        )

        val msgA = ScreenReader.extractSignature(chatTab(listOf("张三", "李四"), "消息"), "com.chat", ".MainActivity")!!
        val msgB = ScreenReader.extractSignature(chatTab(listOf("王五", "赵六", "通知"), "消息"), "com.chat", ".MainActivity")!!
        val contacts = ScreenReader.extractSignature(chatTab(listOf("好友分组", "群聊"), "联系人"), "com.chat", ".MainActivity")!!

        assertTrue(msgA.matches(msgB))
        assertFalse(msgA.matches(contacts))
    }
}
