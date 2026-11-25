package com.wangzan101.mika.cap.phone.a11y

import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.core.uistate.ActionResult
import com.agent.use.ui.ScreenSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 只有 shell 通道时(无障碍服务完全不参与)能不能照常干活:
 * 读屏走快照、点击按坐标注入、界面变化靠轮询任务/页面、主屏截图走内置助手。
 *
 * 这台机器上无障碍服务是开着的,所以每个用例都是在"服务可用但故意不用"的条件下跑的:
 * 走通的路径就是关掉服务之后的那条。
 */
class ShellAccessDeviceTest {

    private lateinit var display: AgentDisplay
    private lateinit var access: ShellScreenAccess
    private lateinit var screen: AndroidScreen

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        val shell = DeviceSupport.shell()
        display = AgentDisplay(
            shell, 720, 1600, 320,
            context.getSystemService(android.hardware.display.DisplayManager::class.java),
        )
        access = ShellScreenAccess(context, shell) { display.displayId }
        screen = AndroidScreen(access, shell, DeviceSupport.ready(display))
    }

    @After
    fun tearDown() {
        screen.close()
        display.release()
    }

    @Test
    fun readFallsBackToTheShellSnapshot() = runBlocking<Unit> {
        assertFalse("shell access must not claim accessibility", access.live)
        assertTrue(access.windowsOn(display.displayId).isEmpty())
        assertNull(access.overlayArea())

        DeviceSupport.startTestActivity(display.displayId, PlainTestActivity::class.java)
        // 屏刚建好时可能还一帧都没到,读出来会是包名对、没有元素的样子,多等一会儿
        val snap = snapUntil { it.packageName == DeviceSupport.TEST_PACKAGE && it.elements.isNotEmpty() }
        assertTrue("snapshot has no elements", snap.elements.isNotEmpty())
        assertEquals(display.width, snap.width)
    }

    @Test
    fun tapUsesCoordinatesWhenNodesHaveNoReference() = runBlocking<Unit> {
        DeviceSupport.startTestActivity(display.displayId, GestureTapTestActivity::class.java)
        val before = snapUntil { s -> s.elements.any { it.name == "taps:0" } }
        val target = before.elements.first { it.name == "taps:0" }

        val r = screen.tapRef(target.ref, before.snapshotId)
        assertTrue("tap reported $r", r is ActionResult.Ok)
        snapUntil { s -> s.elements.any { it.name == "taps:1" } }
    }

    @Test
    fun eventsFollowTheTopActivityWithoutAccessibility() = runBlocking<Unit> {
        DeviceSupport.startTestActivity(display.displayId, PlainTestActivity::class.java)
        // 让轮询先读到当前状态,否则起始值本身就会被当成"变了"
        delay(1_200)
        val first = CompletableDeferred<android.view.accessibility.AccessibilityEvent>()
        val job = launch(Dispatchers.IO) { first.complete(access.eventsFor(display.displayId).first()) }
        delay(1_000)
        DeviceSupport.startTestActivity(display.displayId, GestureTapTestActivity::class.java)

        val event = withTimeoutOrNull(20_000) { first.await() }
        job.cancel()
        assertNotNull("no event came out of the shell channel", event)
        assertTrue(
            "event did not name the new page: ${event!!.className}",
            event.className?.contains("GestureTapTestActivity") == true,
        )
        assertTrue("generation did not move", access.windowGeneration() > 0)
    }

    @Test
    fun mainScreenshotComesFromTheHelper() = runBlocking<Unit> {
        when (val shot = access.takeMainScreenshot()) {
            is MainShot.Ok -> {
                assertTrue("empty bitmap", shot.bitmap.width > 0 && shot.bitmap.height > 0)
                shot.bitmap.recycle()
            }
            MainShot.Secure -> Unit
            is MainShot.Failed -> throw AssertionError("shell capture failed: code=${shot.code}")
        }
    }

    /** 轮着读,直到这份快照满足条件;读不到内容时给一句看得懂的失败原因。 */
    private fun snapUntil(predicate: (ScreenSnapshot) -> Boolean): ScreenSnapshot {
        val end = System.currentTimeMillis() + 20_000
        var last: ScreenSnapshot? = null
        while (System.currentTimeMillis() < end) {
            val snap = runBlocking { screen.snapshot() }
            last = snap
            if (predicate(snap)) return snap
            Thread.sleep(250)
        }
        val raw = runCatching { DeviceSupport.shell().inspectUi(8, 40) }.getOrNull()
        // 只挑定位用得上的字段:整棵树会把关键信息挤出断言信息
        val brief = runCatching {
            val o = org.json.JSONObject(raw!!)
            "ok=${o.optBoolean("ok")} pkg=${o.optString("pkg")} display=${o.optInt("display")} " +
                "count=${o.optInt("count")} windows=${o.optJSONArray("windows")}"
        }.getOrElse { raw?.take(400) }
        val focus = runCatching { DeviceSupport.sh("dumpsys window | grep mTopFocusedDisplayId") }.getOrNull()
        // WMS 能列出所有屏的可见窗口（条目名带窗口标题，系统窗口会带 _displayId_N）
        val windows = runCatching {
            DeviceSupport.sh(
                "cmd window dump-visible-window-views > /data/local/tmp/vd.zip 2>/dev/null; " +
                    "grep -a -o '[0-9a-f]\\{6,8\\} [a-zA-Z0-9._/]\\{2,\\}' /data/local/tmp/vd.zip | sort -u | head -25",
            )
        }.getOrNull()
        throw AssertionError(
            "screen never matched: pkg=${last?.packageName} elements=${last?.elements?.map { it.name }} " +
                "focus=${focus?.trim()} ui=$brief windows=$windows",
        )
    }
}
