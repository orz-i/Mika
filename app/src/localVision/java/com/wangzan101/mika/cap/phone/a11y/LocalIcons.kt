package com.wangzan101.mika.cap.phone.a11y

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Rect
import com.agent.use.vision.AauIconDetector
import com.agent.use.vision.IconDetection
import com.agent.use.vision.IconGlyph
import com.wangzan101.mika.core.uistate.LocalIcon
import com.wangzan101.mika.core.uistate.LocalIconFrame
import com.wangzan101.mika.core.uistate.LocalIconRegion
import com.wangzan101.mika.core.uistate.LocalIconResult
import com.agent.use.ui.normalize

internal object LocalIcons {
    private const val MAX_TILE_SIDE = 720

    val supported: Boolean get() = AauIconDetector.supported

    fun create(assets: AssetManager): LocalIconPixels? = if (!supported) null else object : LocalIconPixels {
        private val detector = AauIconDetector(assets)

        override fun find(bitmap: Bitmap, icon: LocalIcon, region: LocalIconRegion, frame: LocalIconFrame): LocalIconResult {
            val glyph = when (icon) {
                LocalIcon.Search -> IconGlyph.Search
                LocalIcon.Close -> IconGlyph.Close
                LocalIcon.More -> IconGlyph.More
            }
            val area = when (region) {
                LocalIconRegion.Whole -> Rect(0, 0, bitmap.width, bitmap.height)
                LocalIconRegion.Top -> Rect(0, 0, bitmap.width, (bitmap.height / 3).coerceAtLeast(1))
                LocalIconRegion.Bottom -> Rect(0, bitmap.height * 2 / 3, bitmap.width, bitmap.height)
            }
            val detected = scan(bitmap, glyph, area)
            return when (val r = detected) {
                is IconDetection.Match -> r.bounds.let { b ->
                    val centerX = (b.left + b.right) / 2
                    val centerY = (b.top + b.bottom) / 2
                    val (frameHash, targetHash) = LocalIconFingerprint.hashes(bitmap, centerX, centerY)
                    LocalIconResult.Found(
                        frame.copy(frameHash = frameHash, targetHash = targetHash),
                        normalize(b.left, bitmap.width), normalize(b.top, bitmap.height),
                        normalize(b.right - 1, bitmap.width), normalize(b.bottom - 1, bitmap.height),
                    )
                }
                IconDetection.Miss.NotFound -> LocalIconResult.Miss.NotFound
                IconDetection.Miss.Ambiguous -> LocalIconResult.Miss.Ambiguous
            }
        }

        override fun close() = detector.close()

        /** Avoid shrinking portrait/landscape screens to the narrow letterboxed strip.
         * Overlapping crops keep a typical toolbar glyph within the model's trained scale;
         * very wide bands get a second 2:1 pass only if the square pass found nothing. */
        private fun scan(bitmap: Bitmap, glyph: IconGlyph, area: Rect): IconDetection {
            val side = minOf(MAX_TILE_SIDE, area.width(), area.height())
            val xStarts = if (area.width() > side) tileStarts(area.width(), side) else listOf(0)
            val yStarts = if (area.height() > side) tileStarts(area.height(), side) else listOf(0)
            val square = if (area.width() > side || area.height() > side ||
                area.width() >= area.height() * 2 || area.height() >= area.width() * 2) {
                yStarts.flatMap { y -> xStarts.map { x -> Rect(area.left + x, area.top + y,
                    area.left + x + side, area.top + y + side) } }
            } else listOf(area)

            val first = scanTiles(bitmap, glyph, square)
            if (first !is IconDetection.Miss || first == IconDetection.Miss.Ambiguous || area.width() <= area.height()) return first

            val tileHeight = minOf(MAX_TILE_SIDE / 2, area.height(), area.width() / 2)
            val tileWidth = tileHeight * 2
            if (area.width() < tileWidth || tileHeight < 48) return first
            val wide = tileStarts(area.width(), tileWidth).map { start ->
                Rect(area.left + start, area.top, area.left + start + tileWidth, area.top + tileHeight)
            }
            return scanTiles(bitmap, glyph, wide)
        }

        private fun scanTiles(bitmap: Bitmap, glyph: IconGlyph, tiles: List<Rect>): IconDetection {
            val matches = mutableListOf<IconDetection.Match>()
            for (tile in tiles) when (val result = detector.detect(bitmap, glyph, tile)) {
                IconDetection.Miss.Ambiguous -> return result
                IconDetection.Miss.NotFound -> Unit
                is IconDetection.Match -> {
                    val same = matches.indexOfFirst { existing ->
                        val dx = existing.bounds.centerX() - result.bounds.centerX()
                        val dy = existing.bounds.centerY() - result.bounds.centerY()
                        val radius = maxOf(existing.bounds.width(), existing.bounds.height(),
                            result.bounds.width(), result.bounds.height())
                        val tolerance = radius / 2
                        dx * dx + dy * dy <= tolerance * tolerance
                    }
                    if (same < 0) matches += result
                    else if (result.score > matches[same].score) matches[same] = result
                }
            }
            return when (matches.size) {
                0 -> IconDetection.Miss.NotFound
                1 -> matches.single()
                else -> IconDetection.Miss.Ambiguous
            }
        }

        private fun tileStarts(length: Int, side: Int): List<Int> {
            if (length <= side) return listOf(0)
            val count = kotlin.math.ceil(length / (side * .75)).toInt().coerceAtLeast(2)
            return (0 until count).map { i ->
                if (i == count - 1) length - side else i * (length - side) / (count - 1)
            }
        }
    }
}
