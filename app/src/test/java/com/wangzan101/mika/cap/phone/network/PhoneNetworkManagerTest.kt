package com.wangzan101.mika.cap.phone.network

import android.content.Context
import com.agent.use.client.network.AgentNetworkManager
import com.agent.use.common.network.CaptureMode
import com.agent.use.common.network.HttpRequestEvent
import com.agent.use.common.network.HttpResponseEvent
import com.agent.use.common.network.INetworkCapture
import com.agent.use.common.network.INetworkEventListener
import com.agent.use.common.network.NetworkCaptureConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class PhoneNetworkManagerTest {

    private class MockEngine(
        val mode: Int = CaptureMode.ACTIVE_MODE_VPN_SERVICE
    ) : INetworkCapture.Stub() {
        var startCount = 0
        var stopCount = 0
        var lastConfig: NetworkCaptureConfig? = null
        var lastListener: INetworkEventListener? = null
        val responseBodies = mutableMapOf<String, ByteArray>()

        override fun startCapture(config: NetworkCaptureConfig, listener: INetworkEventListener): Boolean {
            startCount++
            lastConfig = config
            lastListener = listener
            return true
        }

        override fun stopCapture() {
            stopCount++
            lastListener = null
        }

        override fun getResponseBody(requestId: String): ByteArray? {
            return responseBodies[requestId]
        }

        override fun getActiveMode(): Int = if (startCount > stopCount) mode else CaptureMode.ACTIVE_MODE_NONE

        fun emitRequest(request: HttpRequestEvent) {
            lastListener?.onRequestWillBeSent(request)
        }

        fun emitResponse(response: HttpResponseEvent) {
            lastListener?.onResponseReceived(response)
        }
    }

    private fun createTestAgentManager(mockEngine: MockEngine): AgentNetworkManager {
        return AgentNetworkManager(
            context = null,
            shell = null,
            vpnEngineProvider = { mockEngine },
            serverCaptureProvider = { null },
            rootChecker = { false },
            kernelChecker = { false }
        )
    }

    private fun createDummyContext(): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
    }

    @Test
    fun testAttachAndDetachTargetPackage() {
        val mockEngine = MockEngine()
        val manager = PhoneNetworkManager(
            context = createDummyContext(),
            shellProvider = { null },
            agentManagerFactory = { _, _ -> createTestAgentManager(mockEngine) }
        )

        assertFalse(manager.isCapturing())
        assertNull(manager.currentPackage)

        // 绑定应用
        val attached = manager.attachToPackage("com.tencent.mm", CaptureMode.AUTO)
        assertTrue(attached)
        assertTrue(manager.isCapturing())
        assertEquals("com.tencent.mm", manager.currentPackage)
        assertEquals(1, mockEngine.startCount)

        // 相同包名重入应直接返回 true
        assertTrue(manager.attachToPackage("com.tencent.mm"))
        assertEquals(1, mockEngine.startCount)

        // 解绑
        manager.detach()
        assertFalse(manager.isCapturing())
        assertNull(manager.currentPackage)
        assertEquals(1, mockEngine.stopCount)

        manager.close()
    }

    @Test
    fun testTrafficHistoryRingBufferAndLookup() = runTest {
        val mockEngine = MockEngine()
        mockEngine.responseBodies["req-order"] = "{\"status\":\"ok\",\"orderId\":9988}".toByteArray(StandardCharsets.UTF_8)

        val manager = PhoneNetworkManager(
            context = createDummyContext(),
            agentManagerFactory = { _, _ -> createTestAgentManager(mockEngine) },
            coroutineContext = coroutineContext
        )

        manager.attachToPackage("com.example.shop")

        val req1 = HttpRequestEvent("req-home", System.currentTimeMillis() - 500L, "https://api.shop.com/home", "GET")
        val req2 = HttpRequestEvent("req-order", System.currentTimeMillis() - 200L, "https://api.shop.com/order/submit", "POST")

        mockEngine.emitRequest(req1)
        mockEngine.emitRequest(req2)
        delay(50)

        // 仅请求到达时
        val pendingTraffic = manager.getRecentTraffic(10)
        assertEquals(2, pendingTraffic.size)
        assertEquals("req-order", pendingTraffic[0].requestId)
        assertNull(pendingTraffic[0].statusCode)

        // 响应到达
        val res2 = HttpResponseEvent(
            requestId = "req-order",
            timestamp = System.currentTimeMillis(),
            url = "https://api.shop.com/order/submit",
            statusCode = 200,
            statusText = "OK",
            mimeType = "application/json",
            contentLength = 32L
        )
        mockEngine.emitResponse(res2)
        delay(50)

        val updatedTraffic = manager.getRecentTraffic(10)
        assertEquals(2, updatedTraffic.size)
        assertEquals("req-order", updatedTraffic[0].requestId)
        assertEquals(200, updatedTraffic[0].statusCode)
        assertEquals("OK", updatedTraffic[0].statusText)
        assertNotNull(updatedTraffic[0].durationMs)

        // 查找与提取正文
        val entry = manager.findRecentTraffic("order/submit")
        assertNotNull(entry)
        assertEquals("req-order", entry?.requestId)

        val bodyText = manager.getResponseBodyForUrl("order/submit")
        assertEquals("{\"status\":\"ok\",\"orderId\":9988}", bodyText)

        // 清空
        manager.clearHistory()
        assertEquals(0, manager.getRecentTraffic().size)

        manager.close()
    }

    @Test
    fun testWaitForResponse() = runTest {
        val mockEngine = MockEngine()
        val manager = PhoneNetworkManager(
            context = createDummyContext(),
            agentManagerFactory = { _, _ -> createTestAgentManager(mockEngine) },
            coroutineContext = coroutineContext
        )

        manager.attachToPackage("com.example.search")

        val waitJob = backgroundScope.async {
            manager.waitForResponse("api/search", timeoutMs = 2000L)
        }

        delay(50)
        val response = HttpResponseEvent(
            requestId = "search-1",
            timestamp = System.currentTimeMillis(),
            url = "https://api.search.com/api/search?q=phone",
            statusCode = 200,
            statusText = "OK"
        )
        mockEngine.emitResponse(response)

        val result = waitJob.await()
        assertNotNull(result)
        assertEquals("search-1", result?.requestId)
        assertEquals(200, result?.statusCode)

        // 测试 PhoneNetworkPort 的 inspectNetwork 与 getResponseBody
        val summaries = manager.inspectNetwork(5, "search")
        assertEquals(1, summaries.size)
        assertEquals("search-1", summaries[0].requestId)
        assertEquals(200, summaries[0].statusCode)

        val portRespBody = manager.getResponseBody(requestId = "search-1", urlPattern = null, asString = true)
        assertNotNull(portRespBody)
        assertEquals("search-1", portRespBody?.requestId)

        manager.close()
    }
}
