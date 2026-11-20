package com.wangzan101.mika.core.uistate

/** 封闭词表。不是开放式描述，也不能据字形断言按钮的用途。 */
enum class LocalIcon(val wire: String) { Search("search"), Close("close"), More("more") }

enum class LocalIconRegion(val wire: String) { Whole("whole"), Top("top"), Bottom("bottom") }

/** 来自一块虚拟屏同一帧的身份。不会交给模型、也不进记录。 */
data class LocalIconFrame(
    val displayId: Int, val timestampNanos: Long, val windowGeneration: Long,
    val width: Int, val height: Int, val rotation: Int,
    val frameHash: Long = 0L, val targetHash: Long = 0L,
)

sealed interface LocalIconResult {
    /** 框是当前虚拟屏的 0–999 坐标；不作为坐标传出工具。 */
    data class Found(val frame: LocalIconFrame, val left: Int, val top: Int, val right: Int, val bottom: Int) : LocalIconResult {
        init { require(left in 0..right && top in 0..bottom && right <= 999 && bottom <= 999) }
    }

    enum class Miss(val wire: String, val message: String) : LocalIconResult {
        NotFound("not_found", "No confident match for this glyph in the selected region."),
        Ambiguous("ambiguous", "More than one position matches. Narrow the region or use elements/OCR; do not guess."),
        Unavailable("local_vision_unavailable", "The on-device icon detector is unavailable. No image was sent."),
        NoApp("no_app_open", "No app is open on this virtual display."),
        Protected("protected_content", "This screen is protected; the on-device detector did not process it."),
        NoFrame("screenshot_unavailable", "No usable virtual display frame is available."),
        Changed("screen_changed", "The virtual display changed. Wait for it to settle and locate again."),
    }
}
