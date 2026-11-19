package com.wangzan101.mika.record

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.trace.TraceStore
import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.io.IOException

class OtlpSenderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun validatesEndpointAndHeaderBeforeSending() {
        for (url in listOf("https://example.com/v1/traces", "http://localhost:4318/v1/traces",
            "http://192.168.1.9/v1/traces", "http://[::1]:4318/v1/traces", "http://collector.local/v1/traces")) {
            assertNotNull(url, OtlpSender.endpoint(url))
        }
        for (url in listOf("http://example.com/v1/traces", "http://8.8.8.8", "https://user:password@example.com", "ftp://localhost", "https://host/#fragment")) {
            assertNull(url, OtlpSender.endpoint(url))
        }
        assertTrue(OtlpSender.validHeader("Authorization", "Bearer test"))
        assertTrue(OtlpSender.validHeader("X-Api-Key", "test"))
        assertTrue(OtlpSender.validHeader("", ""))
        for (name in listOf("Host", "Content-Type", "content-LENGTH", " bad", "Connection")) {
            assertFalse(name, OtlpSender.validHeader(name, "test"))
        }
        assertFalse(OtlpSender.validHeader("Authorization", "x\r\nX-Test: y"))
    }

    /**
     * 绑定显式回环地址。
     *
     * `MockWebServer()` 默认拿 `InetAddress.getByName("localhost").hostName` 当主机名，而 Windows 上
     * 这个名字会反向解析成机器名（如 `http://PC:4318`）。[OtlpSender.endpoint] 只接受 https 与本机地址，
     * 机器名既不是回环也不是私网 IP，于是合法的本地接收端被判成 "Invalid traces endpoint"。
     */
    private fun loopback() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

    /** 显式拼回环地址:`url()` 用的是 localhost 反向解析的结果,Windows 上那是机器名,过不了 endpoint 的本机校验。 */
    private fun localUrl(server: MockWebServer, path: String) = "http://127.0.0.1:${server.port}$path"

    @Test fun sendsTheExactLocalMetadataAndDistinguishesReceiptsWithoutForwardingCredentials() = runBlocking {
        val store = TraceStore(temp.newFolder())
        val trace = store.start("short")!!
        trace.event(RunEvent.TaskEnded(0, "succeeded", "private answer"))
        store.finish(trace)
        val archive = store.archive("short")!!
        val expected = ByteArrayOutputStream().also { archive.save(it, false) }.toByteArray()
        loopback().use { receiver ->
            val url = localUrl(receiver, "/v1/traces")
            suspend fun send() = OtlpSender.send(archive, url, "X-Api-Key", "test-only")
            receiver.enqueue(MockResponse().setHeader("Content-Type", "application/x-protobuf"))
            assertEquals(OtlpReceipt.Accepted, send())
            val request = receiver.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("application/x-protobuf", request.getHeader("Content-Type"))
            assertEquals("test-only", request.getHeader("X-Api-Key"))
            assertArrayEquals(expected, request.body.readByteArray())
            for (count in listOf(0L, 2L)) {
                val ack = ExportTraceServiceResponse.newBuilder().setPartialSuccess(
                    ExportTracePartialSuccess.newBuilder().setRejectedSpans(count).setErrorMessage("receiver detail")).build()
                receiver.enqueue(MockResponse().setHeader("Content-Type", "application/x-protobuf").setBody(Buffer().write(ack.toByteArray())))
                assertEquals(if (count == 0L) OtlpReceipt.AcceptedWithWarning else OtlpReceipt.Rejected(count), send())
            }
            val beforeRetry = receiver.requestCount
            receiver.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            assertEquals(OtlpReceipt.HttpError(503), send())
            assertEquals(beforeRetry + 1, receiver.requestCount)
            receiver.enqueue(MockResponse().setResponseCode(401))
            assertEquals(OtlpReceipt.HttpError(401), send())
            loopback().use { redirect ->
                receiver.enqueue(MockResponse().setResponseCode(307).setHeader("Location", redirect.url("/elsewhere")))
                assertEquals(OtlpReceipt.HttpError(307), send())
                assertEquals(0, redirect.requestCount)
            }
            for (invalid in listOf(
                MockResponse(),
                MockResponse().setHeader("Content-Type", "text/html"),
                MockResponse().setHeader("Content-Type", "text/html").setBody("OK"),
                MockResponse().setHeader("Content-Type", "application/x-protobuf").setBody("not an acknowledgement"),
            )) {
                receiver.enqueue(invalid)
                try { send(); fail("invalid receiver response") } catch (_: IOException) {}
            }
            assertArrayEquals(expected, ByteArrayOutputStream().also { archive.save(it, false) }.toByteArray())
        }
    }
}
