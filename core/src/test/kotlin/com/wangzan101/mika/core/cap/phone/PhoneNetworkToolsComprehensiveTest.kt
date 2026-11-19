package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.net.PhoneNetworkPort
import com.wangzan101.mika.core.net.NetworkResponseInfo
import com.wangzan101.mika.core.net.NetworkResponseBody
import com.wangzan101.mika.core.net.NetworkRequestSummary
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 深入验证 Mika 手机网络抓包 Agent 工具（phone_wait_network, phone_get_network_response, phone_inspect_network）
 * 的参数校验、返回值封装、超限截断、Untrusted 安全防护及工具注册元数据。
 */
class PhoneNetworkToolsComprehensiveTest {

    private class MockNetworkPort : PhoneNetworkPort {
        var waitHandler: ((String, Long) -> NetworkResponseInfo?)? = null
        var getHandler: ((String?, String?, Boolean) -> NetworkResponseBody?)? = null
        var inspectHandler: ((Int, String?) -> List<NetworkRequestSummary>)? = null

        override suspend fun waitForResponse(urlPattern: String, timeoutMs: Long): NetworkResponseInfo? =
            waitHandler?.invoke(urlPattern, timeoutMs)

        override suspend fun getResponseBody(requestId: String?, urlPattern: String?, asString: Boolean): NetworkResponseBody? =
            getHandler?.invoke(requestId, urlPattern, asString)

        override suspend fun inspectNetwork(limit: Int, filter: String?): List<NetworkRequestSummary> =
            inspectHandler?.invoke(limit, filter) ?: emptyList()
    }

    private class NetworkScreen(val net: PhoneNetworkPort?) : ScreenPort by FakeScreen() {
        override val network: PhoneNetworkPort? = net
    }

    private fun call(name: String, args: String) = Part.ToolCall("call-id-1", name, args)
    private fun JsonObject.s(key: String) = this[key]?.jsonPrimitive?.content
    private fun JsonObject.b(key: String) = this[key]?.jsonPrimitive?.boolean

    @Test
    fun testToolRegistryAndMetadata() {
        val tools = PhoneGroup.tools
        val waitTool = tools.firstOrNull { it.def.name == "phone_wait_network" }
        val getTool = tools.firstOrNull { it.def.name == "phone_get_network_response" }
        val inspectTool = tools.firstOrNull { it.def.name == "phone_inspect_network" }

        assertNotNull("phone_wait_network must be in PhoneGroup", waitTool)
        assertNotNull("phone_get_network_response must be in PhoneGroup", getTool)
        assertNotNull("phone_inspect_network must be in PhoneGroup", inspectTool)

        assertEquals(Verb.PhoneWaitNetwork, waitTool?.verb)
        assertEquals(Verb.PhoneGetNetworkResponse, getTool?.verb)
        assertEquals(Verb.PhoneInspectNetwork, inspectTool?.verb)

        assertEquals(Risk.Read, waitTool?.risk)
        assertEquals(Risk.Read, getTool?.risk)
        assertEquals(Risk.Read, inspectTool?.risk)

        assertEquals("等待网络响应", waitTool?.label)
        assertEquals("获取网络响应体", getTool?.label)
        assertEquals("查看近期网络请求", inspectTool?.label)
    }

    @Test
    fun testWaitNetwork_MissingUrlPatternThrows() = runTest {
        val port = MockNetworkPort()
        val d = toolDispatcher(NetworkScreen(port))

        val res = d.execute(call("phone_wait_network", "{}")).content
        assertEquals("false", res.s("ok"))
        assertEquals("invalid_arguments", res.s("error"))
    }

    @Test
    fun testWaitNetwork_FullPayloadFields() = runTest {
        val port = MockNetworkPort().apply {
            waitHandler = { pattern, timeout ->
                assertEquals("order/pay", pattern)
                assertEquals(15000L, timeout)
                NetworkResponseInfo(
                    requestId = "req-999",
                    url = "https://mall.example.com/api/order/pay",
                    method = "POST",
                    statusCode = 200,
                    durationMs = 245,
                    contentType = "application/json; charset=utf-8",
                    contentLength = 1024L,
                    timing = 123456789L
                )
            }
        }
        val d = toolDispatcher(NetworkScreen(port))
        val res = d.execute(call("phone_wait_network", """{"urlPattern":"order/pay","timeoutMs":15000}""")).content

        assertEquals("true", res.s("ok"))
        assertEquals("req-999", res.s("requestId"))
        assertEquals("https://mall.example.com/api/order/pay", res.s("url"))
        assertEquals("POST", res.s("method"))
        assertEquals("200", res.s("statusCode"))
        assertEquals("245", res.s("durationMs"))
        assertEquals("application/json; charset=utf-8", res.s("contentType"))
        assertEquals("1024", res.s("contentLength"))
        assertEquals("123456789", res.s("timing"))
    }

    @Test
    fun testGetNetworkResponse_MissingBothParamsThrows() = runTest {
        val port = MockNetworkPort()
        val d = toolDispatcher(NetworkScreen(port))

        val res = d.execute(call("phone_get_network_response", "{}")).content
        assertEquals("false", res.s("ok"))
        assertEquals("invalid_arguments", res.s("error"))
    }

    @Test
    fun testGetNetworkResponse_UntrustedWrappingAndTruncation() = runTest {
        val bigJson = "{\"data\":\"" + "A".repeat(60_000) + "\"}"
        val port = MockNetworkPort().apply {
            getHandler = { reqId, pattern, asStr ->
                assertEquals("req-big", reqId)
                assertTrue(asStr)
                NetworkResponseBody(
                    requestId = "req-big",
                    url = "https://api.example.com/feed",
                    statusCode = 200,
                    contentType = "application/json",
                    body = bigJson,
                    isTruncated = true,
                    error = null
                )
            }
        }
        val d = toolDispatcher(NetworkScreen(port))
        val res = d.execute(call("phone_get_network_response", """{"requestId":"req-big"}""")).content

        assertEquals("true", res.s("ok"))
        assertEquals(true, res.b("truncated"))
        val bodyContent = res.s("body")
        assertNotNull(bodyContent)
        // 经过 Untrusted.wrapWithin 截断后长度应小于原始 60000 字符
        assertTrue(bodyContent!!.length < 60_000)
    }

    @Test
    fun testGetNetworkResponse_ErrorPropagation() = runTest {
        val port = MockNetworkPort().apply {
            getHandler = { _, pattern, _ ->
                assertEquals("failed/api", pattern)
                NetworkResponseBody(
                    requestId = "req-err",
                    url = "https://api.example.com/failed/api",
                    statusCode = 502,
                    contentType = null,
                    body = null,
                    error = "Bad Gateway"
                )
            }
        }
        val d = toolDispatcher(NetworkScreen(port))
        val res = d.execute(call("phone_get_network_response", """{"urlPattern":"failed/api"}""")).content

        assertEquals("false", res.s("ok"))
        assertEquals("Bad Gateway", res.s("error"))
        assertEquals("req-err", res.s("requestId"))
    }

    @Test
    fun testInspectNetwork_EmptyAndFiltered() = runTest {
        val port = MockNetworkPort().apply {
            inspectHandler = { limit, filter ->
                assertEquals(10, limit)
                assertNull(filter)
                emptyList()
            }
        }
        val d = toolDispatcher(NetworkScreen(port))
        val res = d.execute(call("phone_inspect_network", "{}")).content

        assertEquals("true", res.s("ok"))
        assertEquals("0", res.s("count"))
        assertEquals(0, (res["requests"] as JsonArray).size)
    }
}
