package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNetworkToolsTest {

    private class FakeNetworkPort(
        var waitResult: NetworkResponseInfo? = null,
        var getResult: NetworkResponseBody? = null,
        var inspectResult: List<NetworkRequestSummary> = emptyList(),
    ) : PhoneNetworkPort {
        var lastWaitPattern: String? = null
        var lastWaitTimeout: Long? = null
        var lastGetRequestId: String? = null
        var lastGetPattern: String? = null
        var lastInspectLimit: Int? = null
        var lastInspectFilter: String? = null

        override suspend fun waitForResponse(urlPattern: String, timeoutMs: Long): NetworkResponseInfo? {
            lastWaitPattern = urlPattern
            lastWaitTimeout = timeoutMs
            return waitResult
        }

        override suspend fun getResponseBody(requestId: String?, urlPattern: String?, asString: Boolean): NetworkResponseBody? {
            lastGetRequestId = requestId
            lastGetPattern = urlPattern
            return getResult
        }

        override suspend fun inspectNetwork(limit: Int, filter: String?): List<NetworkRequestSummary> {
            lastInspectLimit = limit
            lastInspectFilter = filter
            return inspectResult
        }
    }

    private class NetworkScreen(
        val net: PhoneNetworkPort? = null,
    ) : ScreenPort by FakeScreen() {
        override val network: PhoneNetworkPort? = net
    }

    private fun call(name: String, args: String) = Part.ToolCall("c", name, args)
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    @Test
    fun waitNetworkUnavailableWithoutCaptureEngine() = runTest {
        val d = toolDispatcher(NetworkScreen(net = null))
        val res = d.execute(call("phone_wait_network", """{"urlPattern":"api/order"}""")).content
        assertEquals("false", res.s("ok"))
        assertEquals("network_capture_unavailable", res.s("error"))
    }

    @Test
    fun waitNetworkSuccess() = runTest {
        val net = FakeNetworkPort(
            waitResult = NetworkResponseInfo(
                requestId = "req-001",
                url = "https://example.com/api/order/submit",
                method = "POST",
                statusCode = 200,
                durationMs = 120,
                contentType = "application/json",
                contentLength = 42,
            )
        )
        val d = toolDispatcher(NetworkScreen(net))
        val res = d.execute(call("phone_wait_network", """{"urlPattern":"api/order", "timeoutMs": 5000}""")).content
        assertEquals("true", res.s("ok"))
        assertEquals("req-001", res.s("requestId"))
        assertEquals("https://example.com/api/order/submit", res.s("url"))
        assertEquals("200", res.s("statusCode"))
        assertEquals("120", res.s("durationMs"))
        assertEquals("api/order", net.lastWaitPattern)
        assertEquals(5000L, net.lastWaitTimeout)
    }

    @Test
    fun waitNetworkTimeout() = runTest {
        val net = FakeNetworkPort(waitResult = null)
        val d = toolDispatcher(NetworkScreen(net))
        val res = d.execute(call("phone_wait_network", """{"urlPattern":"api/order"}""")).content
        assertEquals("false", res.s("ok"))
        assertEquals("timeout", res.s("error"))
        assertEquals("api/order", res.s("urlPattern"))
    }

    @Test
    fun getNetworkResponseByRequestId() = runTest {
        val rawBody = "{\"success\":true,\"orderId\":\"888\"}"
        val net = FakeNetworkPort(
            getResult = NetworkResponseBody(
                requestId = "req-123",
                url = "https://example.com/api/data",
                statusCode = 200,
                contentType = "application/json",
                body = rawBody,
            )
        )
        val d = toolDispatcher(NetworkScreen(net))
        val res = d.execute(call("phone_get_network_response", """{"requestId":"req-123"}""")).content
        assertEquals("true", res.s("ok"))
        assertEquals("req-123", res.s("requestId"))
        assertEquals(com.wangzan101.mika.core.observation.Untrusted.wrap(rawBody), res.s("body"))
        assertEquals("req-123", net.lastGetRequestId)
    }

    @Test
    fun getNetworkResponseNotFound() = runTest {
        val net = FakeNetworkPort(getResult = null)
        val d = toolDispatcher(NetworkScreen(net))
        val res = d.execute(call("phone_get_network_response", """{"urlPattern":"nonexistent"}""")).content
        assertEquals("false", res.s("ok"))
        assertEquals("not_found", res.s("error"))
    }

    @Test
    fun inspectNetworkRecentList() = runTest {
        val net = FakeNetworkPort(
            inspectResult = listOf(
                NetworkRequestSummary("req-1", "https://example.com/1", "GET", 200, 35, 1000L),
                NetworkRequestSummary("req-2", "https://example.com/2", "POST", 201, 80, 2000L),
            )
        )
        val d = toolDispatcher(NetworkScreen(net))
        val res = d.execute(call("phone_inspect_network", """{"limit": 5, "filter": "example"}""")).content
        assertEquals("true", res.s("ok"))
        assertEquals("2", res.s("count"))
        val arr = res["requests"] as JsonArray
        assertEquals(2, arr.size)
        assertEquals(5, net.lastInspectLimit)
        assertEquals("example", net.lastInspectFilter)
    }
}
