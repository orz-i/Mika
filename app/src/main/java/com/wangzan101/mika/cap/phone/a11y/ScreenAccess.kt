package com.wangzan101.mika.cap.phone.a11y

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.agent.use.ui.ScreenEvent
import kotlinx.coroutines.flow.Flow

/** 截一张用户主屏的结果:画面,或屏上有防截屏窗口,或截不到(错误码)。 */
sealed interface MainShot {
    class Ok(val bitmap: Bitmap) : MainShot
    data object Secure : MainShot
    data class Failed(val code: Int) : MainShot
}

/**
 * Agent 操作手机时用到的那一层系统能力:读窗口树、发手势、收界面事件、截主屏。
 *
 * 两条实现:
 * - [ScreenAccessibility] 本身(无障碍服务):能拿到窗口列表和节点引用,是完整能力;
 * - [ShellScreenAccess](只有 shell 通道、服务没开时):窗口列表恒空、没有事件流(靠轮询任务变化凑),
 *   手势改成定向注入,读屏走 `inspectUi` 快照。
 *
 * 上层([AndroidScreen]、[DisplayCustodian]、[MainScreenCapture])只依赖这个接口,
 * 所以无障碍关掉时任务仍能跑,只是读法从"引用"退化成"快照"。
 */
interface ScreenAccess {
    /** 取包名、资源、系统服务用的上下文:真实服务本身,或 shell 模式下的 App 上下文。 */
    val context: Context

    /** 无障碍服务此刻是否可用(能读窗口树、能发无障碍动作)。shell 通道为 false。 */
    val live: Boolean

    /** 这块屏上的顶层窗口;无障碍不可用时为空(读屏会退化到快照)。 */
    fun windowsOn(displayId: Int): List<AccessibilityWindowInfo>

    /** 在指定屏上沿 [path] 执行单指手势。 */
    suspend fun gesture(displayId: Int, path: Path, durationMs: Long): Boolean

    /** 在指定屏上沿多条 [paths] 并行执行手势。 */
    suspend fun gesture(displayId: Int, paths: List<Path>, durationMs: Long): Boolean

    /** 在同一点上连点 [count] 下。 */
    suspend fun taps(displayId: Int, x: Float, y: Float, count: Int, holdMs: Long, gapMs: Long): Boolean

    /** 发生在指定屏上的界面事件流。无障碍不可用时是轮询出来的等价信号(见 [ShellScreenAccess])。 */
    fun eventsFor(displayId: Int): Flow<AccessibilityEvent>

    /** 窗口变化的信号流;只用来判断「窗口自上次以来变没变」。 */
    fun windowChanges(): Flow<Unit>

    /** 通知事件流;无障碍不可用时为空。 */
    fun notificationEvents(): Flow<ScreenEvent>

    /**
     * 窗口变化的计数:加一就表示界面换过一茬。用来判断一帧画面是不是还是同一屏
     * (见 [AndroidScreen] 的本地图标定位);值本身没有含义。
     */
    fun windowGeneration(): Long

    /** 截一张用户主屏(含 Mika 自己的浮层;要不要藏起来由调用方定)。 */
    suspend fun takeMainScreenshot(): MainShot

    /** 此刻屏幕大小和系统栏占位(浮层用的那份);读不到为 null。 */
    fun overlayArea(): OverlayArea?
}

/** 包名:和 Context 上的同名属性一致,让上层不必关心是从服务还是 App 上下文拿。 */
val ScreenAccess.packageName: String get() = context.packageName

val ScreenAccess.packageManager: PackageManager get() = context.packageManager

val ScreenAccess.resources: Resources get() = context.resources

val ScreenAccess.assets: AssetManager get() = context.assets

fun <T> ScreenAccess.getSystemService(service: Class<T>): T? = context.getSystemService(service)
