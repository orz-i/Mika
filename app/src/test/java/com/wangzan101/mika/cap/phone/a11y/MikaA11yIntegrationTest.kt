package com.wangzan101.mika.cap.phone.a11y

import android.accessibilityservice.AccessibilityService
import com.agent.use.a11y.AgentAccessibilityService
import com.agent.use.a11y.DisplayEventStream
import com.agent.use.a11y.DisplayWindowInspector
import com.wangzan101.mika.a11y.MikaAccessibilityService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Mika 无障碍服务门面与 `aau-a11y` 底层框架的继承与委托一致性。
 */
class MikaA11yIntegrationTest {

    @Test
    fun screenAccessibilityInheritsAgentAccessibilityService() {
        assertTrue(AgentAccessibilityService::class.java.isAssignableFrom(ScreenAccessibility::class.java))
        assertTrue(AgentAccessibilityService::class.java.isAssignableFrom(MikaAccessibilityService::class.java))
        assertNull(ScreenAccessibility.instance.value)
        assertNull(AgentAccessibilityService.instance.value)
        assertFalse(AgentAccessibilityService.isRunning())
    }

    @Test
    fun windowGenerationSynchronizesWithDisplayEventStream() {
        assertEquals(DisplayEventStream.windowGeneration(), ScreenAccessibility.windowGeneration())
    }

    @Test
    fun screenReaderDelegatesToDisplayWindowInspector() {
        assertEquals(emptyList<Any>(), ScreenReader.contentWindows(emptyList()))
        assertNull(ScreenReader.topPackage(emptyList()))
        assertEquals(DisplayWindowInspector.FNV_OFFSET, ScreenReader.hash(emptyList()))
    }

    @Test
    fun injectedGesturesAliasResolvesToAauType() {
        assertSame(com.agent.use.a11y.InjectedGestures::class.java, InjectedGestures::class.java)
    }

    @Test
    fun disconnectedScreenshotReturnsNoAccessibilityAccess() = runBlocking {
        val service = ScreenAccessibility()
        val shot = service.takeMainScreenshot()
        assertTrue(shot is MainShot.Failed)
        assertEquals(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS,
            (shot as MainShot.Failed).code,
        )
    }
}
