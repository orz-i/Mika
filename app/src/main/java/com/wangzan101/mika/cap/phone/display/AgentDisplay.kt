package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import com.agent.use.client.AgentDisplay as DelegateDisplay
import com.wangzan101.mika.cap.phone.shell.asAgentShell
import com.wangzan101.mika.shell.IMikaShell
import kotlinx.coroutines.flow.StateFlow

/**
 * Mika 虚拟屏客户端门面。
 * 现已全面接入并由底层通用库 AgentDisplayKit (com.agent.display.client.AgentDisplay) 强力驱动，
 * 保持原有接口签名完全兼容。
 */
class AgentDisplay(
    shell: IMikaShell,
    bufferWidth: Int,
    bufferHeight: Int,
    dpi: Int,
    displayManager: DisplayManager,
) {
    private val delegate = DelegateDisplay(
        shell = shell.asAgentShell(),
        width = bufferWidth,
        height = bufferHeight,
        dpi = dpi,
        displayManager = displayManager,
    )

    val displayId: Int get() = delegate.displayId
    val width: Int get() = delegate.width
    val height: Int get() = delegate.height

    /** 实时画面:限速 <= 10 帧/秒,缩到短边 <= 360。 */
    val frames: StateFlow<Bitmap?> get() = delegate.frames

    /** 最新一帧的整幅 PNG;还没收到画面时返回 null。 */
    fun latestFramePng(): ByteArray? = delegate.latestFramePng()

    /** 最新已发布帧的单调时钟纳秒时间戳。 */
    fun lastFrameAt(): Long? = delegate.lastFrameTimestampNanos()

    /** OCR 使用原始尺寸的画面副本，避免 PNG 编码和解码；调用方负责 recycle。 */
    fun latestFrameBitmap(): Bitmap? = delegate.latestFrameBitmap()

    fun visibleFrameStamp() = delegate.visibleFrameStamp()

    fun release() {
        delegate.release()
    }
}
