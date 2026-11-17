package com.wangzan101.mika.ui

import android.app.UiAutomation
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ChineseUi
import com.wangzan101.mika.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android 17 真机 Compose 无障碍冒烟测试（不依赖 Espresso `onIdle` / `InputManager.getInstance`）。
 *
 * 严格只读验证真机 [MainActivity] 上的设置路径：
 * 聊天页菜单 -> 设置 -> 记忆与数据 -> App 经验列表 -> 搜索栏开合、导入弹窗打开与取消；
 * 若当前设备已有学到的 App 经验，进一步点进单个 App 详情页核对各区块渲染，全程不修改、不删除用户数据。
 */
@RunWith(AndroidJUnit4::class)
class AppExperienceUiAutomationDeviceTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @Test
    fun mainActivityNavigatesToAppExperienceReadOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uiAutomation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val targetPackage = instrumentation.targetContext.packageName

        ActivityScenario.launch(MainActivity::class.java).use {
            // 1. 打开抽屉 -> 设置 -> 记忆与数据 -> App 经验
            val menuDesc = appString(R.string.chat_menu)
            val menuNode = awaitNode(uiAutomation, targetPackage, timeoutMs = 10_000L) {
                matchesDesc(it, menuDesc)
            }
            clickNodeOrAncestor(menuNode)

            val settingsLabel = appString(R.string.settings_title)
            val settingsNav = awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                matchesText(it, settingsLabel)
            }
            clickNodeOrAncestor(settingsNav)

            awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                matchesText(it, settingsLabel)
            }
            val memoryLabel = appString(R.string.settings_memory_data)
            val memoryRow = scrollAndFind(uiAutomation, targetPackage) {
                matchesText(it, memoryLabel)
            }
            clickNodeOrAncestor(memoryRow)

            awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                matchesText(it, memoryLabel)
            }
            val appExpTitle = appString(R.string.settings_app_experience)
            val appExpRow = scrollAndFind(uiAutomation, targetPackage, maxScrolls = 30) {
                matchesText(it, appExpTitle)
            }
            clickNodeOrAncestor(appExpRow)

            // 2. 列表页：验证标题与导入入口；打开导入弹窗后立即取消
            awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                matchesText(it, appString(R.string.app_exp_title))
            }
            val importBtnText = appString(R.string.app_exp_import)
            val importBtn = awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                matchesText(it, importBtnText)
            }
            clickNodeOrAncestor(importBtn)

            val importDialogTitle = appString(R.string.app_exp_import_title)
            awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                matchesText(it, importDialogTitle)
            }
            awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                matchesText(it, appString(R.string.app_exp_import_paste))
            }
            val cancelBtn = awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                matchesText(it, appString(R.string.cancel))
            }
            clickNodeOrAncestor(cancelBtn)
            awaitGone(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                matchesText(it, importDialogTitle)
            }

            // 3. 若本机已有经验条目：测试搜索栏开合并进入首行 App 详情页只读校验
            val searchDesc = appString(R.string.search)
            val searchBtn = findVisibleNode(uiAutomation, targetPackage) {
                matchesDesc(it, searchDesc)
            }
            if (searchBtn != null) {
                clickNodeOrAncestor(searchBtn)
                awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                    it.isEditable
                }
                val closeSearchBtn = awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                    matchesDesc(it, searchDesc)
                }
                clickNodeOrAncestor(closeSearchBtn)
                awaitGone(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                    it.isEditable
                }

                val firstAppRow = awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) { node ->
                    isAppExperienceRowNode(node)
                }
                clickNodeOrAncestor(firstAppRow)

                val pagesSection = appString(R.string.app_exp_section_pages)
                val routesSection = appString(R.string.app_exp_section_routes)
                val shortcutsSection = appString(R.string.app_exp_section_shortcuts)
                val knowledgeSection = appString(R.string.app_exp_section_knowledge)

                awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                    containsText(it, pagesSection)
                }
                awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                    matchesText(it, appString(R.string.app_exp_share))
                }
                awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                    matchesText(it, appString(R.string.app_exp_export))
                }
                scrollAndFind(uiAutomation, targetPackage) { containsText(it, routesSection) }
                scrollAndFind(uiAutomation, targetPackage) { containsText(it, shortcutsSection) }
                scrollAndFind(uiAutomation, targetPackage) { containsText(it, knowledgeSection) }

                val backBtn = awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                    matchesDesc(it, appString(R.string.back))
                }
                clickNodeOrAncestor(backBtn)
                awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                    matchesText(it, importBtnText)
                }
            } else {
                awaitNode(uiAutomation, targetPackage, timeoutMs = 5_000L) {
                    matchesText(it, appString(R.string.app_exp_empty))
                }
            }
        }
    }

    private fun appRoots(uiAutomation: UiAutomation, pkg: String): List<AccessibilityNodeInfo> {
        val windowRoots = uiAutomation.windows
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() == pkg }
        if (windowRoots.isNotEmpty()) return windowRoots
        return listOfNotNull(uiAutomation.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg })
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(64)
        fun walk(node: AccessibilityNodeInfo) {
            out += node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(::walk)
            }
        }
        walk(root)
        return out
    }

    private fun findVisibleNode(
        uiAutomation: UiAutomation,
        pkg: String,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        for (root in appRoots(uiAutomation, pkg)) {
            for (node in collectNodes(root)) {
                if (node.isVisibleToUser && predicate(node)) return node
            }
        }
        return null
    }

    private fun awaitNode(
        uiAutomation: UiAutomation,
        pkg: String,
        timeoutMs: Long = 5_000L,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            findVisibleNode(uiAutomation, pkg, predicate)?.let { return it }
            SystemClock.sleep(100L)
        }
        val found = findVisibleNode(uiAutomation, pkg, predicate)
        assertNotNull("Timed out waiting for UI node (${timeoutMs}ms)", found)
        return found!!
    }

    private fun awaitGone(
        uiAutomation: UiAutomation,
        pkg: String,
        timeoutMs: Long = 5_000L,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (findVisibleNode(uiAutomation, pkg, predicate) == null) return
            SystemClock.sleep(100L)
        }
        assertTrue("Expected node to disappear within ${timeoutMs}ms", findVisibleNode(uiAutomation, pkg, predicate) == null)
    }

    private fun scrollAndFind(
        uiAutomation: UiAutomation,
        pkg: String,
        maxScrolls: Int = 15,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        findVisibleNode(uiAutomation, pkg, predicate)?.let { return it }
        repeat(maxScrolls) {
            val scroller = largestScrollableNode(uiAutomation, pkg) ?: return@repeat
            scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250L)
            findVisibleNode(uiAutomation, pkg, predicate)?.let { return it }
        }
        return awaitNode(uiAutomation, pkg, timeoutMs = 2_000L, predicate = predicate)
    }

    private fun largestScrollableNode(uiAutomation: UiAutomation, pkg: String): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = -1L
        val rect = Rect()
        for (root in appRoots(uiAutomation, pkg)) {
            for (node in collectNodes(root)) {
                if (!node.isVisibleToUser || !node.isScrollable) continue
                node.getBoundsInScreen(rect)
                val area = rect.width().toLong().coerceAtLeast(0L) * rect.height().toLong().coerceAtLeast(0L)
                if (area > bestArea) {
                    bestArea = area
                    best = node
                }
            }
        }
        return best
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo) {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) {
                assertTrue("Failed ACTION_CLICK on node text=${cur.text} desc=${cur.contentDescription}", cur.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                SystemClock.sleep(300L)
                return
            }
            cur = cur.parent
        }
        assertTrue("Target node and ancestors are not clickable: text=${node.text} desc=${node.contentDescription}", false)
    }

    private fun isAppExperienceRowNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        val allTexts = collectNodes(node).mapNotNull { it.text?.toString() }
        return allTexts.any { text ->
            text.contains(" · ") && (text.contains("页面") || text.contains("page", ignoreCase = true))
        }
    }

    private fun matchesText(node: AccessibilityNodeInfo, expected: String): Boolean {
        val raw = node.text?.toString()
        if (raw != null && (raw == expected || raw.lines().any { it.trim() == expected })) return true
        val hint = node.hintText?.toString() ?: return false
        return hint == expected || hint.lines().any { it.trim() == expected }
    }

    private fun containsText(node: AccessibilityNodeInfo, needle: String): Boolean =
        node.text?.toString()?.contains(needle) == true

    private fun matchesDesc(node: AccessibilityNodeInfo, expected: String): Boolean =
        node.contentDescription?.toString() == expected
}
