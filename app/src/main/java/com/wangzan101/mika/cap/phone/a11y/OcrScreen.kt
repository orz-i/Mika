package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.agent.use.a11y.model.A11yNode

/**
 * 用虚拟屏的最新一帧 + 端侧 OCR 拼出一屏。
 *
 * 为什么需要它：`inspectUi`（shell 的 UiAutomation 通道）只读**焦点屏**，
 * 而 Mika 的任务跑在 overlay 虚拟屏上——实测 `setFocusedTask` / `focusTopTask`
 * 都换不动 `mTopFocusedDisplayId`（Android 17 已移除 `DisplayManager.setFocusedDisplay`），
 * 所以那条通道在虚拟屏上永远只能读到用户主屏。
 *
 * 能拿到的和拿不到的：
 * - 有：每行文字的像素位置，于是"按文字找元素再点"这条最常用的路径成立；
 * - 没有：可点击/可编辑/滚动、资源 ID、控件层级。文字行一律按 TextView 上报，
 *   动作走坐标注入（[ShellScreenAccess] 的点击本来就是按坐标做的）。
 *
 * 坐标：OCR 给的是 0–999 归一化值，这里按位图尺寸还原成像素，
 * 好让 [FrozenReader] 用和引用型读屏同一套规则再归一化一次。
 */
internal object OcrScreen {
    private const val TAG = "MikaScreen"
    private const val NORMALIZED_SCALE = 999f

    /** 文本行的类名:让上层把它当普通文本节点,不去猜它可点什么。 */
    private const val TEXT_CLASS = "android.widget.TextView"
    private const val ROOT_CLASS = "android.widget.FrameLayout"

    /**
     * [bitmap] 会被 OCR 释放,调用方不要再碰它。
     * 读不出字返回 null——那和"读不到这一屏"是两件事,由调用方决定怎么上报。
     */
    suspend fun read(bitmap: Bitmap, displayId: Int): ShellScreen? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            bitmap.recycle()
            return null
        }
        val lines = runCatching { PhoneOcr.read(bitmap) }.onFailure {
            Log.w(TAG, "ocr read failed", it)
        }.getOrNull().orEmpty()
        if (lines.isEmpty()) return null

        val nodes = lines.mapNotNull { line ->
            val b = line.bounds
            if (b.size < 4) return@mapNotNull null
            val rect = Rect(
                (b[0] * width / NORMALIZED_SCALE).toInt(),
                (b[1] * height / NORMALIZED_SCALE).toInt(),
                (b[2] * width / NORMALIZED_SCALE).toInt(),
                (b[3] * height / NORMALIZED_SCALE).toInt(),
            )
            if (rect.width() <= 0 || rect.height() <= 0) return@mapNotNull null
            A11yNode(
                text = line.text,
                className = TEXT_CLASS,
                bounds = rect,
                isEnabled = true,
            )
        }
        if (nodes.isEmpty()) return null

        val root = A11yNode(
            className = ROOT_CLASS,
            bounds = Rect(0, 0, width, height),
            children = nodes,
        )
        return ShellScreen(
            packageName = null,
            displayId = displayId,
            roots = listOf(root),
            count = nodes.size + 1,
        )
    }
}
