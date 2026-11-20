package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.graphics.Rect
import com.wangzan101.mika.core.uistate.LocalIcon
import com.wangzan101.mika.core.uistate.LocalIconFrame
import com.wangzan101.mika.core.uistate.LocalIconRegion
import com.wangzan101.mika.core.uistate.LocalIconResult
import java.io.Closeable

internal interface LocalIconPixels : Closeable {
    fun find(bitmap: Bitmap, icon: LocalIcon, region: LocalIconRegion, frame: LocalIconFrame): LocalIconResult
}

/** 8x8 average hashes for the full frame and target neighborhood; memory-only. */
internal object LocalIconFingerprint {
    private const val PATCH_SIDE = 32

    fun hashes(bitmap: Bitmap, centerX: Int, centerY: Int): Pair<Long, Long> =
        averageHash(bitmap, Rect(0, 0, bitmap.width, bitmap.height)) to averageHash(
            bitmap,
            Rect(centerX - PATCH_SIDE / 2, centerY - PATCH_SIDE / 2,
                centerX + (PATCH_SIDE + 1) / 2, centerY + (PATCH_SIDE + 1) / 2),
        )

    private fun averageHash(bitmap: Bitmap, region: Rect): Long {
        val left = region.left.coerceIn(0, bitmap.width - 1)
        val top = region.top.coerceIn(0, bitmap.height - 1)
        val right = region.right.coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        val samples = LongArray(64)
        var sum = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val px = left + ((2 * x + 1) * width / 16).coerceIn(0, width - 1)
            val py = top + ((2 * y + 1) * height / 16).coerceIn(0, height - 1)
            val c = bitmap.getPixel(px, py)
            val luma = ((c shr 16 and 255) * 77L + (c shr 8 and 255) * 150L + (c and 255) * 29L) / 256L
            samples[y * 8 + x] = luma
            sum += luma
        }
        val average = sum / samples.size
        var hash = 0L
        for (i in samples.indices) if (samples[i] > average) hash = hash or (1L shl i)
        return hash
    }
}
