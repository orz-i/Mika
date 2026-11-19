package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.net.PhoneNetworkPort
import com.wangzan101.mika.core.net.NetworkResponseInfo
import com.wangzan101.mika.core.net.NetworkResponseBody
import com.wangzan101.mika.core.net.NetworkRequestSummary
import com.wangzan101.mika.core.net.NetworkCaptureStatus
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNetworkStatusToolsTest {

    private class StatusAwareNetworkPort(
        var status: NetworkCaptureStatus
    ) : PhoneNetworkPort {
        var waitCalled = false

        override suspend fun waitForResponse(urlPattern: String, timeoutMs: Long): NetworkResponseInfo? {
            waitCalled = true
            return null
        }

        override suspend fun getResponseBody(
            requestId: String?,
            urlPattern: String?,
            asString: Boolean
        ): NetworkResponseBody? = null

        override suspend fun inspectNetwork(limit: Int, filter: String?): List<NetworkRequestSummary> = emptyList()

        override suspend fun captureStatus(): NetworkCaptureStatus = status
    }

    private class NetScreen(val net: PhoneNetworkPort) : ScreenPort by FakeScreen() {
        override val network: PhoneNetworkPort = net
    }

    @Test
    fun waitNetworkFailsFastWhenCaptureNotActive() = runTest {
        val port = StatusAwareNetworkPort(
            NetworkCaptureStatus(
                enabled = true,
                capturing = false,
                activeMode = "NONE",
                targetPackage = "com.example.shop",
                error = "vpn_permission_not_granted"
            )
        )
        val result = toolDispatcher(NetScreen(port)).execute(
            Part.ToolCall("c1", "phone_wait_network", """{"urlPattern":"api/order","timeoutMs":5000}""")
        ).content

        assertFalse("Should not report ok when capture is inactive", result["ok"]!!.jsonPrimitive.boolean)
        assertEquals("vpn_permission_not_granted", result["error"]!!.jsonPrimitive.content)
        assertFalse("Should fail fast without blocking on waitForResponse", port.waitCalled)
        assertFalse(result["capturing"]!!.jsonPrimitive.boolean)
        assertEquals("NONE", result["activeMode"]!!.jsonPrimitive.content)
        assertEquals("com.example.shop", result["targetPackage"]!!.jsonPrimitive.content)
    }

    @Test
    fun inspectNetworkSurfacesCaptureStatusAndErrors() = runTest {
        val port = StatusAwareNetworkPort(
            NetworkCaptureStatus(
                enabled = true,
                capturing = false,
                activeMode = "NONE",
                targetPackage = "com.example.shop",
                error = "capture_not_active"
            )
        )
        val result = toolDispatcher(NetScreen(port)).execute(
            Part.ToolCall("c2", "phone_inspect_network", """{"limit":10}""")
        ).content

        assertFalse("ok must be false when capture is inactive and 0 requests cached", result["ok"]!!.jsonPrimitive.boolean)
        assertEquals(0, result["count"]!!.jsonPrimitive.int)
        assertFalse(result["capturing"]!!.jsonPrimitive.boolean)
        assertEquals("NONE", result["activeMode"]!!.jsonPrimitive.content)
        assertEquals("capture_not_active", result["captureError"]!!.jsonPrimitive.content)

        // When active -> ok = true
        port.status = NetworkCaptureStatus(
            enabled = true,
            capturing = true,
            activeMode = "EBPF",
            targetPackage = "com.example.shop",
            error = null
        )
        val activeRes = toolDispatcher(NetScreen(port)).execute(
            Part.ToolCall("c3", "phone_inspect_network", """{"limit":10}""")
        ).content
        assertTrue(activeRes["ok"]!!.jsonPrimitive.boolean)
        assertTrue(activeRes["capturing"]!!.jsonPrimitive.boolean)
        assertEquals("EBPF", activeRes["activeMode"]!!.jsonPrimitive.content)
    }
}
