package com.wangzan101.mika.cap.phone.a11y

import android.content.Context
import android.graphics.Path
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.agent.use.a11y.AgentAccessibilityService
import com.agent.use.a11y.DisplayEventStream
import com.agent.use.ui.ScreenEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Mika 的无障碍服务: 继承并委托给 `aau-a11y` 的 [AgentAccessibilityService] 提供多屏事件流、
 * 手势分发、窗口计数与截屏能力；在此基础上叠加 Mika 宿主特有的浮层隔离、应用访问控制与教学录制钩子。
 *
 * 同时它是 [ScreenAccess] 的"完整能力"实现:上层拿到的就是这个对象;
 * 服务没开时换成 [ShellScreenAccess],同一套接口只有读法不同。
 */
open class ScreenAccessibility : AgentAccessibilityService(), ScreenAccess {

    private var overlayHost: OverlayHost? = null

    /** 在用户屏幕上画浮层用的宿主(只在主线程用);服务断开时它加的窗口一起拆掉。 */
    fun overlays(): OverlayHost = overlayHost ?: OverlayHost(this).also { overlayHost = it }

    override val context: Context get() = this

    /** 服务连着才轮得到这个对象,所以它自己就是"可用"。 */
    override val live: Boolean get() = true

    override fun windowsOn(displayId: Int): List<AccessibilityWindowInfo> = super.windowsOn(displayId)

    override suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean =
        super.gesture(displayId, path, durationMs)

    override suspend fun gesture(displayId: Int, paths: List<Path>, durationMs: Long): Boolean =
        super.gesture(displayId, paths, durationMs)

    override suspend fun taps(displayId: Int, x: Float, y: Float, count: Int, holdMs: Long, gapMs: Long): Boolean =
        super.taps(displayId, x, y, count, holdMs, gapMs)

    override fun eventsFor(displayId: Int): Flow<AccessibilityEvent> = super.eventsFor(displayId)

    override fun windowChanges(): Flow<Unit> = super.windowChanges()

    override fun windowGeneration(): Long = DisplayEventStream.windowGeneration()

    override fun overlayArea(): OverlayArea? = runCatching { overlays().area() }.getOrNull()

    override fun onServiceConnectedHook() {
        _instance.value = this
    }

    override fun onServiceCleared() {
        _instance.compareAndSet(this, null)
        overlayHost?.closeAll()
        overlayHost = null
    }

    override fun shouldProcessEvent(event: AccessibilityEvent): Boolean {
        val app = application as? com.wangzan101.mika.MikaApp ?: return true
        val access = app.appAccess.current()
        if (access.denial(event.packageName?.toString()) != null) return false
        if (app.teachReady && app.teach.wantsEvents()) {
            val displayId = displayOf(event)
            if (displayId != Display.INVALID_DISPLAY) {
                com.wangzan101.mika.teach.TeachEvents.capture(event, displayId)?.let(app.teach::offer)
            }
        }
        return true
    }

    override suspend fun <T> passInjectedGestureThrough(block: suspend () -> T): T {
        // 注入是在用户屏幕上操作别的 App,不是操作 Mika 自己的浮层;所有单点、双击和拖动统一隔离
        return (_instance.value?.overlayHost ?: overlayHost)?.passInjectedGesturesThrough { block() } ?: block()
    }

    /**
     * 截一张主屏(用户看着的那块屏,包括 Mika 自己的浮层:要不要藏起来由调用方定)。系统限制两次截图至少隔一会儿,
     * 太快时等一秒再截一次。画面是软件位图(可以读像素、编码)。
     */
    override suspend fun takeMainScreenshot(): MainShot = when (val result = captureScreenshot(Display.DEFAULT_DISPLAY)) {
        is ScreenshotOutcome.Ok -> MainShot.Ok(result.bitmap)
        ScreenshotOutcome.Secure -> MainShot.Secure
        is ScreenshotOutcome.Failed -> MainShot.Failed(result.code)
    }

    override fun notificationEvents(): Flow<ScreenEvent> =
        DisplayEventStream.notificationEvents().map { ScreenEvent("notification", it.packageName, it.text) }

    companion object {
        /** 窗口每加上、拿掉、换页一次就加一(任何屏上的都算);只用来判断「窗口自上次以来变没变」,值本身没有含义。 */
        fun windowGeneration(): Long = DisplayEventStream.windowGeneration()

        private val _instance = MutableStateFlow<ScreenAccessibility?>(null)
        /** 已连接的服务实例;服务未开启或已断开时为 null。 */
        val instance: StateFlow<ScreenAccessibility?> = _instance.asStateFlow()
    }
}
