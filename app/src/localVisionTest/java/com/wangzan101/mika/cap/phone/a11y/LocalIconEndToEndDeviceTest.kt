package com.wangzan101.mika.cap.phone.a11y

import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.display.DisplayManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.LocalIcon
import com.wangzan101.mika.core.uistate.LocalIconRegion
import com.wangzan101.mika.core.uistate.LocalIconResult
import com.wangzan101.mika.core.uistate.ScreenState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity/frame/gesture path; the page has pixels but no text or accessible icon node. */
@RunWith(AndroidJUnit4::class)
class LocalIconEndToEndDeviceTest {
    private var agent: DeviceSupport.Agent? = null

    @After
    fun tearDown() {
        agent?.release()
    }

    @Test
    fun locatesAndTapsIconOnVirtualDisplayFrame() = runBlocking {
        val shell = DeviceSupport.shell()
        // Fail fast before creating a virtual display if this appId's service is not actually bound.
        val a11y = DeviceSupport.service()
        val display = AgentDisplay(
            shell,
            720,
            1600,
            320,
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(android.hardware.display.DisplayManager::class.java),
        )
        val screen = AndroidScreen(a11y, shell, DeviceSupport.ready(display))
        agent = DeviceSupport.Agent(display, screen)
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val mainState = DeviceSupport.mainScreenState(shell)
        DeviceSupport.sh("am start -W --display ${display.displayId} -f 0x30000000 " +
            "-n $testPackage/${LocalIconTestActivity::class.java.name}")

        val frameStamp = DeviceSupport.pollUntil(5_000) { display.visibleFrameStamp() }
        assertNotNull(
            "virtual display frame did not become stampable: display=${display.displayId} " +
                "flags=${dmFlags(display.displayId)} lastFrameAt=${display.lastFrameAt()} " +
                "pngBytes=${display.latestFramePng()?.size}",
            frameStamp,
        )

        var lastResult: LocalIconResult? = null
        val found = DeviceSupport.pollUntil(7_000) {
            runBlocking { screen.findLocalIcon(LocalIcon.Search, LocalIconRegion.Top) }
                .also { lastResult = it }
                .takeIf { it is LocalIconResult.Found }
        }
        val dm = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(DisplayManager::class.java)
        val shown = dm.getDisplay(display.displayId)
        val frameSize = display.latestFramePng()?.size
        assertNotNull(
            "local icon was not found on the real virtual-display frame: last=$lastResult found=$found " +
                "display=${display.displayId} shown=${shown != null} flags=${shown?.flags} rotation=${shown?.rotation} " +
                "lastFrameAt=${display.lastFrameAt()} visibleStamp=${display.visibleFrameStamp()} pngBytes=$frameSize",
            found,
        )
        val match = found as LocalIconResult.Found

        val action = screen.tapLocalIcon(
            (match.left + match.right) / 2,
            (match.top + match.bottom) / 2,
            match.frame,
        )
        assertTrue("strict local-icon tap failed: $action", action is ActionResult.Ok)

        val tapped = DeviceSupport.pollUntil(3_000) {
            runBlocking { screen.screenshotPng() }?.let { png ->
                val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
                try {
                    val marker = bitmap.getPixel(bitmap.width - 28, bitmap.height - 28)
                    true.takeIf { nearColor(marker, Color.CYAN) }
                } finally {
                    bitmap.recycle()
                }
            }
        }
        assertNotNull("the real Activity did not observe the local-icon gesture", tapped)
        val snapshot = screen.snapshot()
        assertTrue("probe unexpectedly exposed text: ${snapshot.elements}", snapshot.elements.none { it.name.isNotBlank() })
        assertEquals("local icon work changed the main display", mainState, DeviceSupport.mainScreenState(shell))
    }

    @Test
    fun blackSecureWindowFrameNeverReachesTheIconDetector() = runBlocking {
        val shell = DeviceSupport.shell()
        val a11y = DeviceSupport.service()
        val display = AgentDisplay(
            shell,
            720,
            1600,
            320,
            InstrumentationRegistry.getInstrumentation().targetContext
                .getSystemService(DisplayManager::class.java),
        )
        val screen = AndroidScreen(a11y, shell, DeviceSupport.ready(display))
        agent = DeviceSupport.Agent(display, screen)
        val mainState = DeviceSupport.mainScreenState(shell)

        DeviceSupport.startTestActivity(display.displayId, SecureTestActivity::class.java)
        val securePage = DeviceSupport.pollUntil(5_000) {
            runBlocking { screen.snapshot() }.takeIf { snapshot ->
                snapshot.packageName == DeviceSupport.TEST_PACKAGE &&
                    snapshot.elements.any { it.name == "secure page" }
            }
        }
        assertNotNull("secure probe Activity did not open on the virtual display", securePage)

        val result = screen.findLocalIcon(LocalIcon.Search, LocalIconRegion.Top)
        val png = screen.screenshotPng()
        assertEquals("secure-window flags prevent detector input", LocalIconResult.Miss.Protected, result)
        assertEquals("a black frame must not be returned as a screenshot", null, png)
        assertEquals("secure-window pixels must not reach OCR", null, screen.readVisibleText())
        assertEquals("secure-window pixels must not reach frame sampling", null, screen.frameGrid())
        val check = screen.checkScreen()
        assertEquals("explicit FLAG_SECURE evidence is protected", ScreenState.PROTECTED, check.state)
        assertTrue(securePage!!.protected)
        assertTrue(screen.isProtected())
        assertEquals("secure-page probe changed the main display", mainState, DeviceSupport.mainScreenState(shell))
    }

    private fun nearColor(actual: Int, expected: Int): Boolean =
        kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= 8 &&
            kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= 8 &&
            kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= 8

    private fun dmFlags(displayId: Int): Int? = InstrumentationRegistry.getInstrumentation().targetContext
        .getSystemService(DisplayManager::class.java).getDisplay(displayId)?.flags
}
