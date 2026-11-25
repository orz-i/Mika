package com.wangzan101.mika.cap.phone.a11y

import android.hardware.display.DisplayManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.core.uistate.ActionResult
import com.agent.use.ui.ScreenSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 免无障碍的端到端验证:真实 App(系统设置)在虚拟屏上,**只用 shell 通道**读屏、点击、返回。
 *
 * 与 [ShellAccessDeviceTest] 的区别:那边读的是测试包里的页面,这边读的是真 App 的界面,
 * 走的是产线上那条路——虚拟屏自己的帧 + 端侧 OCR,再交给 [FrozenReader] 解析。
 * 这台机器上无障碍服务是开着的,所以每个用例都在"服务可用但故意不用"的条件下跑:
 * 走通的路径就是关掉服务之后的那条。
 */
@RunWith(AndroidJUnit4::class)
class A11yFreeDeviceTest {

    private lateinit var display: AgentDisplay
    private lateinit var access: ShellScreenAccess
    private lateinit var screen: AndroidScreen

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        val shell = DeviceSupport.shell()
        display = AgentDisplay(shell, 720, 1600, 320, context.getSystemService(DisplayManager::class.java))
        access = ShellScreenAccess(context, shell) { display.displayId }
        screen = AndroidScreen(access, shell, DeviceSupport.ready(display))
        assertFalse("shell access must not claim accessibility", access.live)
        assertTrue("shell access must not expose windows", access.windowsOn(display.displayId).isEmpty())
    }

    @After
    fun tearDown() {
        screen.close()
        display.release()
    }

    @Test
    fun readsRealSettingsWithoutAccessibility() = runBlocking<Unit> {
        val s = settingsHome()
        assertEquals(DeviceSupport.SETTINGS, s.packageName)
        assertEquals(720, s.width)
        assertEquals(1600, s.height)
        assertTrue("no elements on the virtual display: ${s.elements.map { it.name }}", s.elements.size >= 3)
        // 归一化坐标、可按下(只有坐标可用,元素本来就是快照)这几条对快照型同样成立
        s.elements.forEach { e ->
            assertTrue("$e", e.bounds.size == 4 && e.bounds.all { it in 0..999 })
            assertTrue("$e", e.centerX in 0 until 720 && e.centerY in 0 until 1600)
        }
        assertFalse("a screen read through the shell channel is not protected", s.protected)
    }

    @Test
    fun tapsIntoWlanWithoutAccessibility() = runBlocking<Unit> {
        val shell = DeviceSupport.shell()
        val main = DeviceSupport.mainScreenState(shell)
        val home = settingsHome()
        val before = home.elements.map { it.name }.toSet()
        val wlan = home.elements.first { it.name.contains("WLAN", ignoreCase = true) }

        val r = screen.tapRef(wlan.ref, home.snapshotId)
        assertTrue("tap failed: $r", r is ActionResult.Ok)
        // 进了 WLAN 页:冒出首页没有的字。
        // 别在这台机器上断言包名——ColorOS 的 WLAN 是另一个 App(com.oplus.wirelesssettings),
        // 点过去之后前台包会变;这不是 Mika 的问题(无障碍那版 coldLaunchReturnsToFirstPage 同样栽在这上面)。
        val opened = snapUntil(20_000) { s -> s.elements.any { it.name !in before } }
        assertTrue("nothing on the new page: ${opened.elements.map { it.name }}", opened.elements.isNotEmpty())
        assertEquals("main screen changed", main, DeviceSupport.mainScreenState(shell))

        // 返回首页:原来那几项又回来
        val backResult = screen.back()
        assertTrue("back failed", backResult is ActionResult.Ok)
        val back = snapUntil(20_000) { s ->
            val names = s.elements.map { it.name }.toSet()
            names.intersect(before).size >= minOf(3, before.size)
        }
        assertTrue("did not come back: ${back.elements.map { it.name }}", back.elements.any { it.name.contains("WLAN", ignoreCase = true) })
        assertEquals("main screen changed after back", main, DeviceSupport.mainScreenState(shell))
    }

    /** 打开系统设置并读一屏,读到 WLAN 那一行为止。 */
    private suspend fun settingsHome(): ScreenSnapshot {
        val r = screen.openApp(DeviceSupport.SETTINGS)
        assertTrue("openApp: $r", r is ActionResult.Ok)
        return snapUntil(20_000) { s -> s.elements.any { it.name.contains("WLAN", ignoreCase = true) } }
    }

    private suspend fun snapUntil(timeoutMs: Long, test: (ScreenSnapshot) -> Boolean): ScreenSnapshot {
        val end = System.currentTimeMillis() + timeoutMs
        var s = screen.snapshot()
        while (!test(s) && System.currentTimeMillis() < end) {
            Thread.sleep(250)
            s = screen.snapshot()
        }
        assertTrue(
            "screen never matched: pkg=${s.packageName} elements=${s.elements.map { it.name }} " +
                "capture=${s.captureError} display=${display.displayId} frameAt=${display.lastFrameAt()}",
            test(s),
        )
        return s
    }
}
