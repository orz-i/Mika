package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.agent.use.a11y.AgentAccessibilityService
import com.agent.use.a11y.DisplayEventStream
import com.wangzan101.mika.core.uistate.ScreenEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Mika 的无障碍服务: 继承并委托给 `aau-a11y` 的 [AgentAccessibilityService] 提供多屏事件流、
 * 手势分发、窗口计数与截屏能力；在此基础上叠加 Mika 宿主特有的浮层隔离、应用访问控制与教学录制钩子。
 */
open class ScreenAccessibility : AgentAccessibilityService() {

    private var overlayHost: OverlayHost? = null

    /** 在用户屏幕上画浮层用的宿主(只在主线程用);服务断开时它加的窗口一起拆掉。 */
    fun overlays(): OverlayHost = overlayHost ?: OverlayHost(this).also { overlayHost = it }

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

    /** 截一张用户屏幕(主屏)的结果:画面,或屏上有防截屏窗口,或截不到(错误码)。 */
    sealed interface MainShot {
        class Ok(val bitmap: Bitmap) : MainShot
        data object Secure : MainShot
        data class Failed(val code: Int) : MainShot
    }

    /**
     * 截一张主屏(用户看着的那块屏,包括 Mika 自己的浮层:要不要藏起来由调用方定)。系统限制两次截图至少隔一会儿,
     * 太快时等一秒再截一次。画面是软件位图(可以读像素、编码)。
     */
    suspend fun takeMainScreenshot(): MainShot = when (val result = captureScreenshot(Display.DEFAULT_DISPLAY)) {
        is ScreenshotOutcome.Ok -> MainShot.Ok(result.bitmap)
        ScreenshotOutcome.Secure -> MainShot.Secure
        is ScreenshotOutcome.Failed -> MainShot.Failed(result.code)
    }

    fun notificationEvents(): Flow<ScreenEvent> =
        DisplayEventStream.notificationEvents().map { ScreenEvent("notification", it.packageName, it.text) }

    companion object {
        /** 窗口每加上、拿掉、换页一次就加一(任何屏上的都算);只用来判断「窗口自上次以来变没变」,值本身没有含义。 */
        fun windowGeneration(): Long = DisplayEventStream.windowGeneration()

        private val _instance = MutableStateFlow<ScreenAccessibility?>(null)
        /** 已连接的服务实例;服务未开启或已断开时为 null。 */
        val instance: StateFlow<ScreenAccessibility?> = _instance.asStateFlow()
    }
}
