package com.wangzan101.mika.cap.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agent.use.a11y.AgentA11yBridge
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 验证主屏手势的注入通道换了之后，应用层收到的 [MotionEvent] 到底长什么样。
 *
 * 做法：用 Mika 无障碍服务加一个全屏 `TYPE_ACCESSIBILITY_OVERLAY` 探针视图，
 * 再用生产路径 [AgentA11yBridge.gestureDispatcher] 点它，读事件字段。
 *
 * 对照组直接调 `dispatchGesture`：那条通道注入到应用层是 `deviceId = 0`、
 * `toolType = TOOL_TYPE_UNKNOWN(0)`、`eventId = -1`，正是反自动化检测的判据。
 * 走内核注入后这些应当是真实输入设备的值。
 *
 * 需要：Mika 无障碍服务已开启 + 设备有 root（evdev 通道不可用时相关用例跳过）。
 */
@RunWith(AndroidJUnit4::class)
class EvdevChannelTest {

    /** 记录收到的每一个触摸事件。 */
    private class Probe(context: Context) : View(context) {
        data class Snapshot(
            val action: Int,
            val deviceId: Int,
            val toolType: Int,
            val source: Int,
            val x: Float,
            val y: Float,
            val duration: Long,
        )

        val events: MutableList<Snapshot> = Collections.synchronizedList(mutableListOf<Snapshot>())

        override fun onTouchEvent(event: MotionEvent): Boolean {
            events += Snapshot(
                action = event.actionMasked,
                deviceId = event.deviceId,
                toolType = event.getToolType(0),
                source = event.source,
                x = event.x,
                y = event.y,
                duration = event.eventTime - event.downTime,
            )
            return true
        }

        fun firstDown(): Snapshot? = synchronized(events) { events.firstOrNull { it.action == MotionEvent.ACTION_DOWN } }
    }

    private fun service(): ScreenAccessibility = runBlocking {
        withTimeoutOrNull(15_000) { ScreenAccessibility.instance.filterNotNull().first() }
    } ?: error("Mika accessibility service is not enabled")

    /** 加一层全屏探针，跑完一定拆掉。 */
    private suspend fun withProbe(block: suspend (Probe, Int, Int) -> Unit) {
        val a11y = service()
        val probe = Probe(a11y)
        val wm = a11y.getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        val instr = InstrumentationRegistry.getInstrumentation()
        instr.runOnMainSync { wm.addView(probe, params) }
        try {
            SystemClock.sleep(700)
            val dm = a11y.resources.displayMetrics
            block(probe, dm.widthPixels / 2, dm.heightPixels / 2)
            SystemClock.sleep(700)
        } finally {
            instr.runOnMainSync { runCatching { wm.removeView(probe) } }
        }
    }

    @Test
    fun kernelChannelDeliversRealFingerFields() = runBlocking {
        val bridge = AgentA11yBridge.getInstance()
        var down: Probe.Snapshot? = null
        var tappedX = 0

        withProbe { probe, cx, cy ->
            tappedX = cx
            val ok = bridge.gestureDispatcher.tap(Display.DEFAULT_DISPLAY, cx.toFloat(), cy.toFloat(), 90)
            Log.i(TAG, "evdev tap dispatched=$ok")
            down = probe.firstDown()
        }

        val s = down
        assumeTrue("evdev 通道不可用（无 root 或未就绪），跳过", s != null)
        Log.i(TAG, "evdev down: $s")

        assertEquals("toolType 应是 FINGER(1)", MotionEvent.TOOL_TYPE_FINGER, s!!.toolType)
        assertNotEquals("deviceId 不应是注入事件的 0", 0, s.deviceId)
        assertEquals("source 应是触摸屏", InputDevice.SOURCE_TOUCHSCREEN, s.source)
        assertTrue(
            "落点应在请求位置附近，请求 $tappedX 实际 ${s.x}",
            kotlin.math.abs(s.x - tappedX) < 40f,
        )
    }

    @Test
    fun systemGestureChannelIsTheOldTell() = runBlocking {
        val a11y = service()
        var down: Probe.Snapshot? = null

        withProbe { probe, cx, cy ->
            val path = Path().apply { moveTo(cx.toFloat(), cy.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 90))
                .build()
            val latch = CountDownLatch(1)
            a11y.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    latch.countDown()
                }
            }, null)
            latch.await(5, TimeUnit.SECONDS)
            Log.i(TAG, "system gesture completed")
            down = probe.firstDown()
        }

        val s = down
        assumeTrue("系统手势没有送达探针", s != null)
        Log.i(TAG, "system down: $s")
        // 实测（PLK110 / Android 17）：MotionEventInjector 注入的事件是 deviceId = -1、toolType = 0。
        // 这正是任何反自动化检测一眼就能认出的标记，也是主屏必须换通道的原因。
        assertEquals("系统注入的 toolType 是 UNKNOWN(0)", MotionEvent.TOOL_TYPE_UNKNOWN, s!!.toolType)
        assertEquals("系统注入的 deviceId 不是真实输入设备", -1, s.deviceId)
    }

    companion object {
        private const val TAG = "EvdevChannelTest"
    }
}
