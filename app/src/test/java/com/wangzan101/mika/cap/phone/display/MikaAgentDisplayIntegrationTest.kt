package com.wangzan101.mika.cap.phone.display

import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.agent.use.common.DisplayConfig
import com.agent.use.common.IAgentCustodyListener
import com.agent.use.common.IAgentShell
import com.agent.use.common.LaunchOrigin
import com.agent.use.setup.CheckItem
import com.agent.use.setup.DiagnosticReport
import com.agent.use.setup.DiagnosticStatus
import com.wangzan101.mika.cap.phone.shell.MikaAgentShellBridge
import com.wangzan101.mika.cap.phone.shell.asAgentShell
import com.wangzan101.mika.shell.IMikaShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证适配后的 Mika 宿主与 AgentDisplayKit 底层框架的端到端集成测试。
 *
 * 覆盖：
 * 1. MikaAgentShellBridge 与 IMikaShell.asAgentShell() 契约映射与参数转换闭环；
 * 2. 跨进程任务调度、主屏防扰、手势注入与音频控制代理链路；
 * 3. DiagnosticReport 报告结构、脱敏处理及 Markdown / JSON 导出有效性。
 */
class MikaAgentDisplayIntegrationTest {

    /**
     * 测试桩：模拟运行在 Shell UID 2000 进程的 IMikaShell 服务实现
     */
    private class FakeMikaShell : IMikaShell {
        override fun asBinder(): android.os.IBinder? = null
        var createdSurface: Surface? = null
        var createdWidth: Int = 0
        var createdHeight: Int = 0
        var createdDpi: Int = 0
        var releasedDisplayId: Int = -1
        var movedTaskId: Int = -1
        var movedDisplayId: Int = -1
        var mutedPackage: String? = null
        var isMuted: Boolean = false
        var tappedDisplayId: Int = -1
        var tappedX: Int = -1
        var tappedY: Int = -1
        var finishedDisplayId: Int = -1

        override fun createDisplay(surface: Surface?, width: Int, height: Int, dpi: Int): Int {
            createdSurface = surface
            createdWidth = width
            createdHeight = height
            createdDpi = dpi
            return 42
        }

        override fun releaseDisplay(displayId: Int) {
            releasedDisplayId = displayId
        }

        override fun startApp(displayId: Int, packageName: String?): Int = 0
        /** 单帧截图：本桩不产图，真实实现由 Shell 侧完成。 */
        override fun capturePng(displayId: Int, timeoutMs: Int, privilege: Int): ParcelFileDescriptor? = null
        /** 长图截取：同上，结构体只做类型闭环。 */
        override fun captureLongPng(
            displayId: Int,
            maxScrolls: Int,
            maxHeightPx: Int,
            privilege: Int,
            topInsetPercent: Int,
            bottomInsetPercent: Int,
        ): Bundle? = null
        /** 截图能力探测：无图可截，返回空能力表。 */
        override fun captureCapability(): Bundle? = null
        override fun topTaskId(displayId: Int): Int = 101
        override fun moveTask(taskId: Int, displayId: Int): Boolean {
            movedTaskId = taskId
            movedDisplayId = displayId
            return true
        }
        override fun injectKey(displayId: Int, keyCode: Int): Boolean = true
        override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
            tappedDisplayId = displayId
            tappedX = x
            tappedY = y
            return true
        }
        override fun setAudioMuted(packageName: String?, muted: Boolean): Boolean {
            mutedPackage = packageName
            isMuted = muted
            return true
        }
        override fun restartApp(displayId: Int, packageName: String?): Int = 0
        override fun getAudioMode(packageName: String?): String = "allow"
        override fun setAudioMode(packageName: String?, mode: String?): Boolean = true
        override fun displayOfTask(taskId: Int): Int = 42
        override fun packageOfTask(taskId: Int): String = "com.test.app"
        override fun setClipText(text: String?): Int = 0
        override fun restoreClip(): Boolean = true
        override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int = 0
        override fun displayOfPackage(packageName: String?): Int = 42
        override fun savedClipText(): String = ""
        override fun restoreClipText(original: String?, ours: String?): Boolean = true
        override fun identity(): IntArray = intArrayOf(2000, 1234)
        override fun releaseAll() {}
        override fun openDevtoolsSocket(name: String?): ParcelFileDescriptor? = null
        override fun runSystemCommand(displayId: Int, command: String?): Bundle = Bundle().apply {
            putInt("exitCode", 0)
            putString("output", "ok")
        }
        override fun topActivity(displayId: Int): String = "com.test.app.MainActivity"
        override fun inspectUi(maxDepth: Int, maxNodes: Int): String = """{"ok":false,"reason":"not_supported"}"""
        override fun focusDisplay(displayId: Int): Boolean = false
        override fun focusMainDisplay(): Boolean = false
        override fun injectCtrlKey(displayId: Int, keyCode: Int): Int = 1
        override fun listUsers(): String = "Users:\nUserInfo{0:Owner:13}\n"
        override fun listPackages(userId: Int): String = "package:com.test.app\n"
        override fun isSystemPackage(packageName: String?): Boolean = false
        override fun startShare(displayId: Int, packageName: String?, text: String?): Int = 0
        var startedCustodyDisplayId: Int = -1
        var stoppedCustodyDisplayId: Int = -1
        var custodyListener: IAgentCustodyListener? = null
        var queriedOriginTaskId: Int = -1
        val lastOriginBundle: Bundle = Bundle()
        override fun startCustody(displayId: Int, listener: IAgentCustodyListener?): Boolean {
            startedCustodyDisplayId = displayId
            custodyListener = listener
            return true
        }
        override fun stopCustody(displayId: Int) {
            stoppedCustodyDisplayId = displayId
        }
        override fun launchOrigin(taskId: Int): Bundle {
            queriedOriginTaskId = taskId
            return lastOriginBundle
        }
        override fun resolveJumpTarget(
            taskId: Int,
            interstitialPackage: String?,
            initiatorPackage: String?,
        ): String? = null
        override fun userOfTask(taskId: Int): Int = 0
        override fun injectStroke(displayId: Int, xs: IntArray?, ys: IntArray?, times: LongArray?): Boolean = true
        override fun startSystemAction(displayId: Int, intent: Intent?, asMika: android.app.PendingIntent?): Int = 0
        override fun runCommand(command: String?): Bundle = Bundle().apply {
            putInt("exitCode", 0)
            putString("output", "success")
        }
        override fun setRestoreWish(flags: Int) {}
        override fun appCopyUsers(): Bundle = Bundle().apply {
            putIntArray("users", intArrayOf(0))
            putString("summary", "User 0")
        }
        override fun startAppInUser(displayId: Int, packageName: String?, userId: Int, cold: Boolean): Int = 0
        override fun displayOfPackageInUser(packageName: String?, userId: Int): Int = 42
        override fun wakeDevice(): Boolean = true
        override fun dismissDeviceKeyguard(): Boolean = true
        override fun enterDeviceCredential(kind: Int, credential: ByteArray?, clear: Int): Boolean = true
        override fun getAudioModeInUser(packageName: String?, userId: Int): String = "allow"
        override fun setAudioMutedInUser(packageName: String?, userId: Int, muted: Boolean): Boolean = true
        override fun setAudioModeInUser(packageName: String?, userId: Int, mode: String?): Boolean = true
        override fun visibleOnMainScreen(packageName: String?, userId: Int): Boolean = false
        override fun playAudioAppOpsDenied(): Boolean = false
        override fun setTaskMediaMuted(displayId: Int, packageName: String?, userId: Int, muted: Boolean, preparing: Boolean): Boolean = false
        override fun finishDisplay(displayId: Int, keep: Array<out String>?, close: Array<out String>?): Bundle = Bundle().apply {
            finishedDisplayId = displayId
            putStringArray("kept", keep ?: emptyArray())
            putStringArray("closed", close ?: emptyArray())
            putString("note", "ok")
        }
        override fun revealDeviceCredential(): Boolean = true
        override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String?, text: String?): Int = 1
        override fun revealDeviceCredentialTap(x: Int, y: Int): Boolean = true
        override fun destroy() {}
    }

    @Test
    fun testMikaAgentShellBridgeMethodDelegation() {
        val fakeShell = FakeMikaShell()
        val agentShell: IAgentShell = fakeShell.asAgentShell()

        // 1. 验证 identity
        val id = agentShell.identity()
        assertEquals(2000, id[0])
        assertEquals(1234, id[1])

        // 2. 验证 releaseDisplay 映射
        agentShell.releaseDisplay(99)
        assertEquals(99, fakeShell.releasedDisplayId)

        // 3. 验证 startApp
        val startResult = agentShell.startApp(42, "com.test.app")
        assertEquals(0, startResult)

        // 4. 验证 moveTask 任务迁移回捞映射
        val moved = agentShell.moveTask(505, 42)
        assertTrue(moved)
        assertEquals(505, fakeShell.movedTaskId)
        assertEquals(42, fakeShell.movedDisplayId)

        // 5. 验证音频 AppOps 静音控制
        val muted = agentShell.setAudioMuted("com.test.app", true)
        assertTrue(muted)
        assertEquals("com.test.app", fakeShell.mutedPackage)
        assertTrue(fakeShell.isMuted)

        // 6. 验证输入注入 injectTap
        val tapped = agentShell.injectTap(42, 350, 720)
        assertTrue(tapped)
        assertEquals(42, fakeShell.tappedDisplayId)
        assertEquals(350, fakeShell.tappedX)
        assertEquals(720, fakeShell.tappedY)

        // 7. 验证 finishDisplay 结果结构与调用转发
        val finishBundle = agentShell.finishDisplay(42, arrayOf("com.keep"), arrayOf("com.close"))
        assertNotNull(finishBundle)
        assertEquals(42, fakeShell.finishedDisplayId)

        // 8. 验证常驻托管监听与启动来源查询桥接
        val custodyListener = object : IAgentCustodyListener.Stub() {
            override fun onTaskArrived(e: Bundle?) {}
            override fun onTaskVanished(taskId: Int, lastDisplayId: Int, at: Long) {}
        }
        assertTrue(agentShell.startCustody(42, custodyListener))
        assertEquals(42, fakeShell.startedCustodyDisplayId)
        assertEquals(custodyListener, fakeShell.custodyListener)
        val originBundle = agentShell.launchOrigin(777)
        assertNotNull(originBundle)
        assertEquals(777, fakeShell.queriedOriginTaskId)
        assertSame(fakeShell.lastOriginBundle, originBundle)
        agentShell.stopCustody(42)
        assertEquals(42, fakeShell.stoppedCustodyDisplayId)

        // 9. 验证原生网络捕获接口 (Mika 宿主桥接默认返回 null 以触发客户端 VPN 回退)
        assertEquals(null, agentShell.networkCapture)
    }

    @Test
    fun testDiagnosticReportDataModelAndExport() {
        val staticChecks = listOf(
            CheckItem(
                key = "android_sdk",
                title = "Android 版本与 API 级别",
                status = DiagnosticStatus.PASS,
                detail = "Android 14 (API 34)",
                latencyMs = 5L
            ),
            CheckItem(
                key = "focus_isolation",
                title = "主屏焦点防争抢支持",
                status = DiagnosticStatus.PASS,
                detail = "支持 FLAG_STEAL_TOP_FOCUS_DISABLED",
                latencyMs = 2L
            )
        )

        val dynamicChecks = listOf(
            CheckItem(
                key = "shell_ipc",
                title = "Shell 守护进程通信",
                status = DiagnosticStatus.PASS,
                detail = "UID 2000, PID 1234, RTT 4ms",
                latencyMs = 4L
            )
        )

        val report = DiagnosticReport(
            timestamp = 1700000000000L,
            manufacturer = "Xiaomi",
            model = "2407FRK8EC",
            androidRelease = "14",
            sdk = 34,
            screenResolution = "1080x2400 (420 DPI)",
            selinuxMode = "Enforcing",
            isShellConnected = true,
            shellUid = 2000,
            shellPid = 1234,
            staticItems = staticChecks,
            dynamicItems = dynamicChecks,
            overallStatus = DiagnosticStatus.PASS,
            summary = "运行环境完全满足 AgentDisplayKit 要求",
            advices = listOf("无需特别调整")
        )

        // 1. 验证统计聚合属性
        assertEquals(3, report.allItems.size)
        assertEquals(DiagnosticStatus.PASS, report.overallStatus)
        assertTrue(report.isShellConnected)

        // 2. 验证脱敏逻辑
        val desensitized = report.desensitize()
        assertEquals("240***", desensitized.model)

        // 3. 验证 Markdown 导出
        val md = report.toMarkdown()
        assertTrue("Markdown 应包含报告主标题", md.contains("环境与运行时诊断报告"))
        assertTrue("Markdown 应包含设备信息", md.contains("Xiaomi 2407FRK8EC"))
        assertTrue("Markdown 应包含检查表格", md.contains("| Android 版本与 API 级别 |"))

        // 4. 验证 JSON 导出
        val json = report.toJson()
        assertTrue("JSON 应以大括号开头", json.startsWith("{"))
        assertTrue("JSON 应以大括号结尾", json.endsWith("}"))
        assertTrue("JSON 应包含 overallStatus 字段", json.contains("\"overallStatus\":\"PASS\""))
        assertTrue("JSON 应包含 items 数组", json.contains("\"items\":["))
    }
}
