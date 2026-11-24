package com.wangzan101.mika.cap.phone.a11y

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 免无障碍读法的单元验证:把 shell 通道的扁平 JSON 还原成快照,再整理成元素表。
 *
 * 这里验证的是「快照型」这一半:元素、引用、签名照常生成,节点没有引用、发不了动作,
 * 上层据此走坐标路径(见 [NodeHandle])。
 */
@RunWith(AndroidJUnit4::class)
class FrozenScreenTest {

    private val json = """
        {"ok":true,"pkg":"com.test.app","display":8,"count":6,"nodes":[
          {"parent":-1,"cls":"android.widget.FrameLayout","l":0,"t":0,"r":720,"b":1280,"scroll":1},
          {"parent":0,"cls":"android.widget.TextView","text":"消息","id":"com.test.app:id/title",
           "l":0,"t":100,"r":720,"b":160,"vis":1},
          {"parent":0,"cls":"android.widget.Button","text":"确定","id":"com.test.app:id/ok",
           "l":40,"t":200,"r":300,"b":260,"click":1,"vis":1},
          {"parent":0,"cls":"android.widget.EditText","hint":"搜索职位","id":"com.test.app:id/search",
           "l":40,"t":300,"r":680,"b":360,"edit":1,"vis":1},
          {"parent":0,"cls":"android.widget.TextView","text":"隐藏项",
           "l":0,"t":400,"r":720,"b":460,"vis":0},
          {"parent":4,"cls":"android.widget.TextView","text":"隐藏里的可见项",
           "l":0,"t":470,"r":720,"b":520,"vis":1}
        ]}
    """.trimIndent()

    private fun read() = FrozenReader.read(
        ShellScreenParser.parse(json)!!, 720, 1280, collectSignature = true,
    )

    @Test
    fun elementsComeFromTheSnapshot() {
        val r = read()
        assertEquals("com.test.app", r.topPackage)
        assertTrue(r.anyAppWindow)
        assertFalse(r.entries.isEmpty())
        assertEquals("@e1", r.entries.first().element.ref)

        val names = r.entries.map { it.element.name }
        assertTrue("标题应该在: $names", "消息" in names)
        assertTrue("按钮应该在: $names", "确定" in names)

        val ok = r.entries.first { it.element.name == "确定" }.element
        assertTrue(ok.clickable)
        assertEquals(170, ok.centerX)
        assertEquals(230, ok.centerY)

        val search = r.entries.first { it.element.editable }.element
        assertEquals("搜索职位", search.placeholder)
        assertEquals("search", search.resourceId)
    }

    @Test
    fun invisibleNodeIsDroppedButItsVisibleChildIsHoisted() {
        val names = read().entries.map { it.element.name }
        assertFalse("不可见节点不该出现: $names", "隐藏项" in names)
        assertTrue("不可见节点的可见子节点要接上来: $names", "隐藏里的可见项" in names)
    }

    @Test
    fun nodesAreSnapshotOnlyAndCannotAct() {
        val entry = read().entries.first { it.element.name == "确定" }
        val node = entry.node
        assertNull(node.raw)
        assertFalse(node.isLive)
        assertFalse(node.refresh())
        assertFalse(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue(node.visible)
        assertTrue(node.enabled)
        assertEquals(40, node.bounds().left)
        assertEquals(300, node.bounds().right)
    }

    @Test
    fun signatureComesFromTheSnapshot() {
        val r = read()
        assertTrue(r.signatureNodes.isNotEmpty())
        assertNotNull(ScreenReader.extractSignature(r))
    }
}
