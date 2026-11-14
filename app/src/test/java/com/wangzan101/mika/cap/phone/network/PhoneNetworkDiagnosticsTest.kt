package com.wangzan101.mika.cap.phone.network

import android.content.Context
import android.content.ContextWrapper
import com.agent.use.client.network.AgentNetworkManager
import com.agent.use.common.network.CaptureMode
import com.agent.use.common.network.HttpRequestEvent
import com.agent.use.common.network.INetworkCapture
import com.agent.use.common.network.INetworkEventListener
import com.agent.use.common.network.NetworkCaptureConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneNetworkDiagnosticsTest {

    private fun dummyContext(): Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun testVpnTlsSniBodyLookupReturnsExplicitTlsEncryptedErrorInsteadOfFake200() = runTest {
        var listenerRef: INetworkEventListener? = null
        val vpnMock = object : INetworkCapture.Stub() {
            var active = false
            override fun startCapture(config: NetworkCaptureConfig, listener: INetworkEventListener): Boolean {
                listenerRef = listener
                active = true
                return true
            }
            override fun stopCapture() { active = false }
            override fun getResponseBody(requestId: String?): ByteArray? = null
            override fun getActiveMode(): Int = if (active) CaptureMode.ACTIVE_MODE_VPN_SERVICE else CaptureMode.ACTIVE_MODE_NONE
        }

        val manager = PhoneNetworkManager(
            context = dummyContext(),
            shellProvider = { null },
            agentManagerFactory = { ctx, _ ->
                AgentNetworkManager(
                    context = ctx,
                    shell = null,
                    vpnEngineProvider = { vpnMock },
                    rootChecker = { false },
                    kernelChecker = { false }
                )
            },
            coroutineContext = coroutineContext
        )

        assertTrue(manager.attachToPackage("com.example.shop", CaptureMode.AUTO))
        val status = manager.captureStatus()
        assertTrue(status.capturing)
        assertEquals("VPN_SERVICE", status.activeMode)

        listenerRef!!.onRequestWillBeSent(
            HttpRequestEvent(
                requestId = "vpn_tls_1234",
                timestamp = System.currentTimeMillis(),
                url = "https://api.example.com/",
                method = "CONNECT",
                isTls = true
            )
        )
        delay(50)

        val bodyResp = manager.getResponseBody(requestId = "vpn_tls_1234", urlPattern = null, asString = true)
        assertNotNull(bodyResp)
        assertEquals("tls_encrypted_in_vpn_mode_use_root_ebpf", bodyResp?.error)
        assertEquals(0, bodyResp?.statusCode)
        manager.close()
    }

    @Test
    fun testCaptureStatusReportsFailureReasonWhenAttachFails() = runTest {
        val manager = PhoneNetworkManager(
            context = dummyContext(),
            shellProvider = { null },
            agentManagerFactory = { ctx, _ ->
                AgentNetworkManager(
                    context = ctx,
                    shell = null,
                    vpnEngineProvider = {
                        object : INetworkCapture.Stub() {
                            override fun startCapture(config: NetworkCaptureConfig, listener: INetworkEventListener): Boolean {
                                listener.onErrorOccurred("", "vpn_permission_not_granted")
                                return false
                            }
                            override fun stopCapture() {}
                            override fun getResponseBody(requestId: String?): ByteArray? = null
                            override fun getActiveMode(): Int = CaptureMode.ACTIVE_MODE_NONE
                        }
                    },
                    rootChecker = { false },
                    kernelChecker = { false }
                )
            },
            coroutineContext = coroutineContext
        )

        assertFalse(manager.attachToPackage("com.example.shop", CaptureMode.VPN_SERVICE))
        val status = manager.captureStatus()
        assertFalse(status.capturing)
        assertEquals("NONE", status.activeMode)
        assertEquals("vpn_permission_not_granted", status.error)
        manager.close()
    }

    @Test
    fun testGetResponseBodyDecodesGrpcProtobufAndZeroBodyTrailers() = runTest {
        var listenerRef: INetworkEventListener? = null
        val bodies = HashMap<String, ByteArray>()
        val ebpfMock = object : INetworkCapture.Stub() {
            var active = false
            override fun startCapture(config: NetworkCaptureConfig, listener: INetworkEventListener): Boolean {
                listenerRef = listener
                active = true
                return true
            }
            override fun stopCapture() { active = false }
            override fun getResponseBody(requestId: String?): ByteArray? = requestId?.let { bodies[it] }
            override fun getActiveMode(): Int = if (active) CaptureMode.ACTIVE_MODE_EBPF else CaptureMode.ACTIVE_MODE_NONE
        }

        val manager = PhoneNetworkManager(
            context = dummyContext(),
            shellProvider = { null },
            agentManagerFactory = { ctx, _ ->
                AgentNetworkManager(
                    context = ctx,
                    shell = null,
                    serverCaptureProvider = { ebpfMock },
                    rootChecker = { true },
                    kernelChecker = { true }
                )
            },
            coroutineContext = coroutineContext
        )

        assertTrue(manager.attachToPackage("com.example.grpc", CaptureMode.AUTO))

        // Case 1: Protobuf payload inside gRPC frame
        val proto = byteArrayOf(0x0A, 0x05) + "alice".toByteArray() + byteArrayOf(0x10, 0x7D) // 1:"alice", 2:125
        val framed = byteArrayOf(0x00, 0x00, 0x00, 0x00, proto.size.toByte()) + proto
        bodies["grpc_1"] = framed
        listenerRef!!.onResponseReceived(
            com.agent.use.common.network.HttpResponseEvent(
                requestId = "grpc_1",
                timestamp = System.currentTimeMillis(),
                url = "https://api.example.com/user.v1.UserService/GetProfile",
                statusCode = 200,
                statusText = "OK",
                headers = listOf(
                    com.agent.use.common.network.HttpHeader("content-type", "application/grpc"),
                    com.agent.use.common.network.HttpHeader("grpc-status", "0")
                ),
                mimeType = "application/grpc",
                contentLength = framed.size.toLong()
            )
        )

        // Case 2: Zero-body trailers-only gRPC error
        listenerRef!!.onResponseReceived(
            com.agent.use.common.network.HttpResponseEvent(
                requestId = "grpc_err_2",
                timestamp = System.currentTimeMillis(),
                url = "https://api.example.com/user.v1.UserService/Secret",
                statusCode = 200,
                statusText = "OK",
                headers = listOf(
                    com.agent.use.common.network.HttpHeader("content-type", "application/grpc+proto"),
                    com.agent.use.common.network.HttpHeader("grpc-status", "7"),
                    com.agent.use.common.network.HttpHeader("grpc-message", "permission denied")
                ),
                mimeType = "application/grpc+proto",
                contentLength = 0L
            )
        )
        delay(50)

        val decodedOk = manager.getResponseBody(requestId = "grpc_1", urlPattern = null, asString = true)
        assertNotNull(decodedOk)
        assertEquals(null, decodedOk?.error)
        assertTrue(decodedOk?.body?.contains(""""grpcStatus":"0"""") == true)
        assertTrue(decodedOk?.body?.contains(""""1":"alice"""") == true)
        assertTrue(decodedOk?.body?.contains(""""2":125""") == true)

        val decodedErr = manager.getResponseBody(requestId = "grpc_err_2", urlPattern = null, asString = true)
        assertNotNull(decodedErr)
        assertEquals(null, decodedErr?.error)
        assertTrue(decodedErr?.body?.contains(""""grpcStatus":"7"""") == true)
        assertTrue(decodedErr?.body?.contains(""""grpcMessage":"permission denied"""") == true)
        manager.close()
    }

    @Test
    fun testResponseBodySurvivesEngineEvictionViaLocalAndDiskCacheAndPreciseErrors() = runTest {
        val tempCacheDir = java.nio.file.Files.createTempDirectory("mika_net_cache_test").toFile()
        try {
            val ctx = object : ContextWrapper(null) {
                override fun getApplicationContext(): Context = this
                override fun getCacheDir(): java.io.File = tempCacheDir
            }

            var listenerRef: INetworkEventListener? = null
            val engineBodies = HashMap<String, ByteArray>()
            val ebpfMock = object : INetworkCapture.Stub() {
                var active = false
                override fun startCapture(config: NetworkCaptureConfig, listener: INetworkEventListener): Boolean {
                    listenerRef = listener
                    active = true
                    return true
                }
                override fun stopCapture() { active = false }
                override fun getResponseBody(requestId: String?): ByteArray? = requestId?.let { engineBodies[it] }
                override fun getActiveMode(): Int = if (active) CaptureMode.ACTIVE_MODE_EBPF else CaptureMode.ACTIVE_MODE_NONE
            }

            fun newManager(clock: () -> Long = { System.currentTimeMillis() }) = PhoneNetworkManager(
                context = ctx,
                shellProvider = { null },
                agentManagerFactory = { c, _ ->
                    AgentNetworkManager(
                        context = c,
                        shell = null,
                        serverCaptureProvider = { ebpfMock },
                        rootChecker = { true },
                        kernelChecker = { true }
                    )
                },
                coroutineContext = coroutineContext,
                clockMs = clock
            )

            val manager = newManager()
            assertTrue(manager.attachToPackage("com.xingin.xhs", CaptureMode.EBPF))

            // 1. Complete JSON response: arrives via chunkFlow + responseFlow, then engine evicts it
            val payload = """{"data":{"comments":[{"content":"hello"}]}}""".toByteArray(Charsets.UTF_8)
            val reqId = "evict_req_1"
            listenerRef!!.onRequestWillBeSent(
                HttpRequestEvent(
                    requestId = reqId,
                    timestamp = 1000L,
                    url = "https://edith.xiaohongshu.com/api/sns/v2/comment/page",
                    method = "GET"
                )
            )
            listenerRef!!.onDataChunkReceived(reqId, payload)
            listenerRef!!.onResponseReceived(
                com.agent.use.common.network.HttpResponseEvent(
                    requestId = reqId,
                    timestamp = 1025L,
                    url = "https://edith.xiaohongshu.com/api/sns/v2/comment/page",
                    statusCode = 200,
                    statusText = "OK",
                    headers = listOf(com.agent.use.common.network.HttpHeader("content-type", "application/json")),
                    mimeType = "application/json",
                    contentLength = payload.size.toLong()
                )
            )
            delay(50)

            // Evict from underlying engine completely
            engineBodies.clear()

            val inspected = manager.inspectNetwork(10, "comment")
            assertEquals(1, inspected.size)
            assertEquals("application/json", inspected[0].contentType)
            assertTrue(inspected[0].hasResponseBody)

            val fetched = manager.getResponseBody(requestId = reqId, urlPattern = null, asString = true)
            assertNotNull(fetched)
            assertEquals(null, fetched?.error)
            assertEquals("""{"data":{"comments":[{"content":"hello"}]}}""", fetched?.body)

            // Verify persisted on disk even in a separate PhoneNetworkManager with empty localBodyCache
            val readerManager = newManager()
            val fromDisk = readerManager.getResponseBody(reqId)
            assertNotNull(fromDisk)
            assertEquals("""{"data":{"comments":[{"content":"hello"}]}}""", String(fromDisk!!, Charsets.UTF_8))
            readerManager.close()

            // 2. In-flight request (no response yet) -> response_pending_in_flight
            listenerRef!!.onRequestWillBeSent(
                HttpRequestEvent(
                    requestId = "pending_req_2",
                    timestamp = 2000L,
                    url = "https://edith.xiaohongshu.com/api/sns/v1/pending",
                    method = "POST"
                )
            )
            delay(50)
            val pendingResp = manager.getResponseBody(requestId = "pending_req_2", urlPattern = null, asString = true)
            assertEquals("response_pending_in_flight", pendingResp?.error)

            // 3. HTTP 204 empty response -> empty_response_body
            listenerRef!!.onResponseReceived(
                com.agent.use.common.network.HttpResponseEvent(
                    requestId = "empty_204_3",
                    timestamp = 3000L,
                    url = "https://edith.xiaohongshu.com/api/sns/v1/ack",
                    statusCode = 204,
                    statusText = "No Content",
                    headers = emptyList(),
                    mimeType = null,
                    contentLength = 0L
                )
            )
            delay(50)
            val emptyResp = manager.getResponseBody(requestId = "empty_204_3", urlPattern = null, asString = true)
            assertEquals("empty_response_body", emptyResp?.error)

            manager.close()
        } finally {
            tempCacheDir.deleteRecursively()
        }
    }

    @Test
    fun testEnsureCapturingRecoversTransientFailureOnInspectNetwork() = runTest {
        var fakeNow = 10_000L
        var startAttempts = 0
        val ebpfMock = object : INetworkCapture.Stub() {
            var active = false
            override fun startCapture(config: NetworkCaptureConfig, listener: INetworkEventListener): Boolean {
                startAttempts++
                if (startAttempts == 1) {
                    listener.onErrorOccurred("", "libssl_not_found")
                    active = false
                    return false
                }
                active = true
                return true
            }
            override fun stopCapture() { active = false }
            override fun getResponseBody(requestId: String?): ByteArray? = null
            override fun getActiveMode(): Int = if (active) CaptureMode.ACTIVE_MODE_EBPF else CaptureMode.ACTIVE_MODE_NONE
        }

        val manager = PhoneNetworkManager(
            context = dummyContext(),
            shellProvider = { null },
            agentManagerFactory = { ctx, _ ->
                AgentNetworkManager(
                    context = ctx,
                    shell = null,
                    serverCaptureProvider = { ebpfMock },
                    rootChecker = { true },
                    kernelChecker = { true }
                )
            },
            coroutineContext = coroutineContext,
            clockMs = { fakeNow }
        )

        assertFalse(manager.attachToPackage("com.xingin.xhs", CaptureMode.EBPF))
        assertFalse(manager.captureStatus().capturing)
        assertEquals(1, startAttempts)

        // Inside MIN_REATTACH_INTERVAL_MS cooldown: inspectNetwork does not hammer startCapture
        fakeNow = 10_300L
        manager.inspectNetwork(10, null)
        assertEquals(1, startAttempts)
        assertFalse(manager.captureStatus().capturing)

        // Past cooldown: ensureCapturing() automatically recovers capture on inspectNetwork
        fakeNow = 11_500L
        manager.inspectNetwork(10, null)
        assertEquals(2, startAttempts)
        assertTrue(manager.captureStatus().capturing)
        assertEquals("EBPF", manager.captureStatus().activeMode)
        manager.close()
    }
}
