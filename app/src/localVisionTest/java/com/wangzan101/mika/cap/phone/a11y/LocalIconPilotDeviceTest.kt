package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agent.use.vision.AauIconDetector
import com.agent.use.vision.IconDetection
import com.agent.use.vision.IconGlyph
import com.wangzan101.mika.core.uistate.LocalIcon
import com.wangzan101.mika.core.uistate.LocalIconFrame
import com.wangzan101.mika.core.uistate.LocalIconRegion
import com.wangzan101.mika.core.uistate.LocalIconResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Same fixtures, assertions and CPU threading for both build variants. No model provider or network. */
@RunWith(AndroidJUnit4::class)
class LocalIconPilotDeviceTest {
    private val assets get() = InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Test fun realRuntimeFindsThreeCanvasGlyphsInEitherThemeAndOrientation() {
        assertTrue(AauIconDetector.supported)
        LocalIcons.create(assets)!!.use { port ->
            for (glyph in IconGlyph.entries) for (dark in listOf(false, true)) for (landscape in listOf(false, true)) {
                val w = if (landscape) 320 else 160
                val h = if (landscape) 160 else 320
                val cx = w * 3 / 4
                val cy = h / 3
                // The generated pilot kernels cover a 4/6/8 px glyph radius;
                // use the largest fixture so both themes exercise the same model path.
                val bitmap = fixture(w, h, dark, glyph, listOf(cx to cy), 8f)
                try {
                    val kind = when (glyph) {
                        IconGlyph.Search -> LocalIcon.Search
                        IconGlyph.Close -> LocalIcon.Close
                        IconGlyph.More -> LocalIcon.More
                    }
                    val frame = LocalIconFrame(5, 1L, 1L, w, h, 0)
                    val found = port.find(bitmap, kind, LocalIconRegion.Whole, frame)
                    assertTrue("${AauIconDetector.backend}, $glyph, dark=$dark landscape=$landscape: $found", found is LocalIconResult.Found)
                    found as LocalIconResult.Found
                    val expectedX = cx * 999 / (w - 1)
                    val expectedY = cy * 999 / (h - 1)
                    assertTrue(found.toString(), expectedX in (found.left - 35)..(found.right + 35))
                    assertTrue(found.toString(), expectedY in (found.top - 35)..(found.bottom + 35))
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun rejectsEmptyAndMultipleMatchesAndMapsCroppedCoordinates() {
        AauIconDetector(assets).use { detector ->
            val blank = fixture(160, 160, true, IconGlyph.Search, emptyList(), 6f)
            val two = fixture(160, 160, true, IconGlyph.Close, listOf(42 to 32, 119 to 116), 6f)
            try {
                assertEquals(IconDetection.Miss.NotFound, detector.detect(blank, IconGlyph.Search))
                assertEquals(IconDetection.Miss.Ambiguous, detector.detect(two, IconGlyph.Close))
                assertEquals(IconDetection.Miss.NotFound, detector.detect(two, IconGlyph.Search))
                val found = detector.detect(two, IconGlyph.Close, Rect(40, 70, 160, 160))
                assertTrue(found.toString(), found is IconDetection.Match)
                assertTrue((found as IconDetection.Match).bounds.contains(119, 116))
            } finally { blank.recycle(); two.recycle() }
        }
        val bitmap = fixture(160, 360, true, IconGlyph.More, listOf(113 to 40), 8f)
        try {
            LocalIcons.create(assets)!!.use { port ->
                val frame = LocalIconFrame(5, 1, 1, 160, 360, 0)
                val found = port.find(bitmap, LocalIcon.More, LocalIconRegion.Top, frame)
                assertTrue(found.toString(), found is LocalIconResult.Found)
                found as LocalIconResult.Found
                assertEquals(frame.displayId, found.frame.displayId)
                assertEquals(frame.width, found.frame.width)
                assertTrue(found.frame.frameHash != 0L || found.frame.targetHash != 0L)
                assertTrue(710 in found.left..found.right)
                assertTrue(111 in found.top..found.bottom)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun detectorKeepsToolbarGlyphScaleOnLargePhoneFrames() {
        LocalIcons.create(assets)!!.use { port ->
            for (glyph in IconGlyph.entries) {
                val bitmap = fixture(720, 720, dark = false, glyph = glyph, centers = listOf(590 to 86), r = 27f)
                try {
                    val icon = when (glyph) {
                        IconGlyph.Search -> LocalIcon.Search
                        IconGlyph.Close -> LocalIcon.Close
                        IconGlyph.More -> LocalIcon.More
                    }
                    val frame = LocalIconFrame(5, 2L, 2L, bitmap.width, bitmap.height, 0)
                    val result = port.find(bitmap, icon, LocalIconRegion.Whole, frame)
                    assertTrue("${AauIconDetector.backend}, $glyph on a 720px frame: $result", result is LocalIconResult.Found)
                    result as LocalIconResult.Found
                    val expectedX = 590 * 999 / 719
                    val expectedY = 86 * 999 / 719
                    assertTrue(result.toString(), expectedX in (result.left - 30)..(result.right + 30))
                    assertTrue(result.toString(), expectedY in (result.top - 30)..(result.bottom + 30))
                } finally { bitmap.recycle() }
            }
        }
    }

    /** Synthetic 720px fixture: reports runtime + first-result cold cost and warm end-to-end detector P50/P95. */
    @Test fun reportsColdAndWarmLatencyOnLargeCanvasFixture() {
        val bitmap = fixture(720, 720, dark = false, glyph = IconGlyph.Search, centers = listOf(590 to 86), r = 27f)
        val frame = LocalIconFrame(5, 3L, 3L, bitmap.width, bitmap.height, 0)
        try {
            val port = LocalIcons.create(assets) ?: error("${AauIconDetector.backend} backend unavailable")
            port.use { detector ->
                val coldStart = SystemClock.elapsedRealtimeNanos()
                val cold = detector.find(bitmap, LocalIcon.Search, LocalIconRegion.Whole, frame)
                val coldNs = SystemClock.elapsedRealtimeNanos() - coldStart
                assertTrue("cold: $cold", cold is LocalIconResult.Found)

                repeat(8) {
                    assertTrue(detector.find(bitmap, LocalIcon.Search, LocalIconRegion.Whole, frame) is LocalIconResult.Found)
                }
                val samples = LongArray(100) { sample ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    val result = detector.find(bitmap, LocalIcon.Search, LocalIconRegion.Whole, frame)
                    val elapsed = SystemClock.elapsedRealtimeNanos() - start
                    assertTrue("warm sample $sample: $result", result is LocalIconResult.Found)
                    elapsed
                }.sorted()
                fun ms(ns: Long) = String.format(Locale.US, "%.2f", ns / 1_000_000.0)
                Log.i("LocalIconPilotBench", "backend=${AauIconDetector.backend} device=${Build.MODEL} " +
                    "sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull()} fixture=720x720 " +
                    "cold_first_result_ms=${ms(coldNs)} warm_n=${samples.size} " +
                    "warm_p50_ms=${ms(samples[49])} warm_p95_ms=${ms(samples[94])}")
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun fixture(w: Int, h: Int, dark: Boolean, glyph: IconGlyph, centers: List<Pair<Int, Int>>, r: Float): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            canvas.drawColor(if (dark) Color.rgb(28, 28, 31) else Color.rgb(245, 245, 241))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (dark) Color.WHITE else Color.rgb(32, 34, 39)
                strokeCap = Paint.Cap.ROUND
            }
            for ((x, y) in centers) when (glyph) {
                IconGlyph.Search -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = .26f * r
                    canvas.drawCircle(x - .21f * r, y - .21f * r, .62f * r, paint)
                    canvas.drawLine(x + .23f * r, y + .23f * r, x + .86f * r, y + .86f * r, paint)
                }
                IconGlyph.Close -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = .30f * r
                    val d = .83f * r
                    canvas.drawLine(x - d, y - d, x + d, y + d, paint)
                    canvas.drawLine(x - d, y + d, x + d, y - d, paint)
                }
                IconGlyph.More -> {
                    paint.style = Paint.Style.FILL
                    for (i in -1..1) canvas.drawCircle(x + i * .77f * r, y.toFloat(), .21f * r, paint)
                }
            }
        }
}
