package com.wangzan101.mika.cap.phone.a11y

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.ParcelFileDescriptor
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.agent.use.common.capture.ScreenCapturePrivilege
import com.agent.use.ui.ScreenEvent
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * 只有 shell 通道时的 [ScreenAccess]:无障碍服务没开(或被关掉)时顶上,让任务照常跑。
 *
 * 能做的和做不了的:
 * - 读屏:借 shell 进程的 UiAutomation 出一份快照(见 [AndroidScreen.shellScreen]),拿到的是字段副本,不是节点引用;
 * - 手势:走定向注入(和虚拟屏上原本的优先路径同一条),事件带真实触摸屏的 deviceId、手指类型、亚像素坐标;
 * - 事件:无障碍事件流不存在,改成轮询这块屏最上面的任务/页面,变了就发一条等价信号;
 * - 截图:主屏走内置助手的免授权抓帧;通知事件没有。
 *
 * 少了什么:窗口列表恒空(读屏由上层退化到快照)、拿不到防截屏标记以外的窗口属性、
 * 多指手势做不了、`waitStable` 最细只能看到"任务/页面变了"。
 */
class ShellScreenAccess(
    override val context: Context,
    private val shell: IMikaShell,
    /** 此刻 Mika 正在用的屏;屏还没建时为 -1。轮询用,传 null 时只看主屏。 */
    private val displayId: () -> Int = { Display.DEFAULT_DISPLAY },
) : ScreenAccess {

    /** 轮询发现界面变了就加一,给上层判断"画面还是不是同一屏"用。 */
    private val generation = AtomicLong(0L)

    override val live: Boolean get() = false

    override fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> = emptyList()

    override suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean {
        val sampled = sample(path, durationMs) ?: return false
        return withContext(Dispatchers.IO) {
            runCatching { shell.injectStroke(displayId, sampled.first, sampled.second, sampled.third) }.getOrDefault(false)
        }
    }

    /** 多指手势 shell 通道做不了:返回 false,由调用方按失败处理。 */
    override suspend fun gesture(displayId: Int, paths: List<Path>, durationMs: Long): Boolean = false

    /**
     * 连点几下。shell 通道一次只能发一组按下/抬起,所以按 [gapMs] 间隔分开发。
     * [holdMs] 用不上:注入的按下时长由助手固定(TAP_MS),不按这里给的节奏走。
     */
    override suspend fun taps(displayId: Int, x: Float, y: Float, count: Int, holdMs: Long, gapMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            var ok = false
            repeat(count.coerceAtLeast(1)) { i ->
                if (i > 0) delay(gapMs.coerceAtLeast(0))
                ok = runCatching { shell.injectTap(displayId, x.roundToInt(), y.roundToInt()) }.getOrDefault(false)
            }
            ok
        }

    override fun eventsFor(displayId: Int): Flow<AccessibilityEvent> = flow {
        var last = top(displayId)
        while (true) {
            delay(EVENT_POLL_MS)
            val now = top(displayId)
            if (now == last) continue
            last = now
            generation.incrementAndGet()
            emit(
                AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                    packageName = now.packageName
                    className = now.activity
                },
            )
        }
    }.flowOn(Dispatchers.IO)

    override fun windowChanges(): Flow<Unit> = flow {
        var last = watchKey()
        while (true) {
            delay(WINDOW_POLL_MS)
            val now = watchKey()
            if (now == last) continue
            last = now
            generation.incrementAndGet()
            emit(Unit)
        }
    }.flowOn(Dispatchers.IO)

    /** 通知只有无障碍事件里有;这条通道拿不到,返回空流,上层等超时。 */
    override fun notificationEvents(): Flow<ScreenEvent> = emptyFlow()

    override fun windowGeneration(): Long = generation.get()

    override suspend fun takeMainScreenshot(): MainShot = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            val pfd = shell.capturePng(Display.DEFAULT_DISPLAY, SCREENSHOT_TIMEOUT_MS, ScreenCapturePrivilege.AUTO)
                ?: return@runCatching null
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }.getOrNull() ?: return@withContext MainShot.Failed(SCREENSHOT_UNAVAILABLE)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return@withContext MainShot.Failed(SCREENSHOT_UNAVAILABLE)
        MainShot.Ok(bitmap)
    }

    /** 浮层是服务才有的东西;没有服务就没有浮层占位,交给调用方退回整屏尺寸。 */
    override fun overlayArea(): OverlayArea? = null

    private data class Top(val task: Int, val packageName: String?, val activity: String?)

    private fun top(displayId: Int): Top {
        val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
        if (task < 0) return Top(-1, null, null)
        val pkg = runCatching { shell.packageOfTask(task) }.getOrNull()?.takeIf { it.isNotBlank() }
        val activity = runCatching { shell.topActivity(displayId) }.getOrNull()
        return Top(task, pkg, activity)
    }

    /** 屏本身换了也算一次变化:新屏建出来时上层必然要重读。 */
    private fun watchKey(): Pair<Int, Int> {
        val id = displayId()
        val task = runCatching { shell.topTaskId(id) }.getOrDefault(-1)
        return id to task
    }

    /** 把一条路径按时间均匀采样成注入点;零长度(单点)返回 null,点击请走 [taps]。 */
    private fun sample(path: Path, durationMs: Long): Triple<IntArray, IntArray, LongArray>? {
        val measure = PathMeasure(path, false)
        val length = measure.length
        if (length <= 0f) return null
        val steps = (durationMs / SAMPLE_STEP_MS).toInt().coerceIn(2, MAX_STROKE_POINTS)
        val duration = durationMs.coerceIn(1L, MAX_STROKE_MS)
        val xs = IntArray(steps)
        val ys = IntArray(steps)
        val pos = FloatArray(2)
        for (i in 0 until steps) {
            measure.getPosTan(length * i / (steps - 1), pos, null)
            xs[i] = pos[0].roundToInt()
            ys[i] = pos[1].roundToInt()
        }
        val times = LongArray(steps) { duration * it / (steps - 1) }
        return Triple(xs, ys, times)
    }

    private companion object {
        /** 轮询最上面任务的间隔:比上层判"安静"的 500ms 细,变化能在安静判定之前被看到。 */
        const val EVENT_POLL_MS = 300L
        /** 只看任务换没换,可以粗一点(这条流整个任务期间都开着)。 */
        const val WINDOW_POLL_MS = 400L
        const val SAMPLE_STEP_MS = 16L
        /** 助手侧单笔最多 1024 个点、10 秒。 */
        const val MAX_STROKE_POINTS = 256
        const val MAX_STROKE_MS = 10_000L
        const val SCREENSHOT_TIMEOUT_MS = 5_000
        /** 助手侧没给错误码(抓帧为 null)时的占位值。 */
        const val SCREENSHOT_UNAVAILABLE = -1
    }
}
