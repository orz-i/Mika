package com.wangzan101.mika.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class HttpServerTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    /** 一次连接上写出去的响应，以及这段字节里处理了几个请求。 */
    private class Exchange(val reply: String, val served: Int)

    /**
     * 把一段请求字节喂给同一个连接，返回写出的响应与处理的请求数。
     *
     * 不走真 socket：本机回环可能被过滤类代理接管（把流水线合并成一条、塞一张 502 拦截页、或直接掐断），
     * 那种机器上做字节级断言，测到的是代理而不是这段逻辑。这一层只认字节，喂进去就能确定地验证
     * keep-alive、流水线与关闭时机；真连接的接线由 `HttpServerStopTest` 覆盖。
     */
    private fun exchange(
        request: String,
        handler: (HttpRequest) -> HttpResponse = { req -> HttpResponse(200, req.path.toByteArray(Charsets.UTF_8)) },
        stillRunning: () -> Boolean = { true },
    ): Exchange {
        val output = ByteArrayOutputStream()
        val served = MiniHttpServer.serveRequests(
            input = request.byteInputStream(Charsets.ISO_8859_1),
            output = output,
            remote = loopback,
            handler = handler,
            stillRunning = stillRunning,
        )
        return Exchange(String(output.toByteArray(), Charsets.ISO_8859_1), served)
    }

    /** 放在关闭请求之后：它要是也被处理了，说明连接没关。 */
    private val afterClose = "GET /after HTTP/1.1\r\nHost: localhost\r\n\r\n"

    private fun request(chunks: String) =
        "POST /mcp HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n$chunks"

    private fun read(chunks: String) = MiniHttpServer.readRequest(request(chunks).byteInputStream(), loopback)!!

    private fun chunk(body: String) = "${body.length.toString(16)}\r\n$body\r\n"

    private fun assertTooLarge(chunks: String) {
        assertEquals(413, assertThrows(HttpProtocolException::class.java) { read(chunks) }.status)
    }

    @Test fun rejectsChunkSizeThatWouldOverflowTheAccumulatedLength() {
        // No huge body is needed: reject the declared size before allocating or reading it.
        assertTooLarge("1\r\nx\r\n7fffffff\r\n")
    }

    @Test fun rejectsOneChunkAboveTheBodyLimit() {
        assertTooLarge("${(MiniHttpServer.MAX_BODY + 1).toString(16)}\r\n")
    }

    @Test fun rejectsMultipleChunksWhoseCombinedLengthExceedsTheBodyLimit() {
        val full = "x".repeat(MiniHttpServer.MAX_BODY)
        assertTooLarge(chunk(full) + "1\r\n")
    }

    @Test fun acceptsMultipleChunksAtExactlyTheBodyLimit() {
        val first = "a".repeat(MiniHttpServer.MAX_BODY / 2)
        val second = "b".repeat(MiniHttpServer.MAX_BODY - first.length)
        assertEquals(first + second, read(chunk(first) + chunk(second) + "0\r\n\r\n").body.toString(Charsets.UTF_8))
    }

    @Test fun acceptsAnEmptyChunkedBody() {
        assertEquals(0, read("0\r\n\r\n").body.size)
    }

    @Test fun closesWhenConnectionOptionsIncludeClose() {
        for (options in listOf("keep-alive, close", "close, keep-alive", "keep-alive, \tClOsE\t")) {
            val sent = exchange("GET /closed HTTP/1.1\r\nHost: localhost\r\nConnection: $options\r\n\r\n$afterClose")
            with(sent.reply) {
                assertEquals(this, 1, sent.served)
                assertTrue(this, startsWith("HTTP/1.1 200 "))
                assertTrue(this, contains("Connection: close\r\n"))
                assertTrue(this, endsWith("/closed"))
            }
        }
    }

    @Test fun closesWhenRepeatedConnectionHeadersIncludeClose() {
        for (headers in listOf(
            "Connection: keep-alive\r\nConnection: close",
            "Connection: close\r\nConnection: keep-alive",
        )) {
            val sent = exchange("GET /closed HTTP/1.1\r\nHost: localhost\r\n$headers\r\n\r\n$afterClose")
            with(sent.reply) {
                assertEquals(this, 1, sent.served)
                assertTrue(this, startsWith("HTTP/1.1 200 "))
                assertTrue(this, contains("Connection: close\r\n"))
                assertTrue(this, endsWith("/closed"))
            }
        }
    }

    @Test fun keepsServingWhenConnectionOptionsDoNotIncludeClose() {
        // 关闭指令只认完整的连接选项："x-close"、"close-extra" 不是。
        val sent = exchange(
            "GET /first HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive, x-close, close-extra\r\n\r\n" +
                "GET /second HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n$afterClose",
        )
        with(sent.reply) {
            assertEquals(this, 2, sent.served)
            assertTrue(this, startsWith("HTTP/1.1 200 "))
            assertTrue(this, contains("/firstHTTP/1.1 200 "))
            assertTrue(this, endsWith("/second"))
        }
    }

    @Test fun stopsWhenStillRunningTurnsFalse() {
        // 服务停止时不再读下一个请求：连接线程不该在 running 变假后还接着服务。
        var served = 0
        val sent = exchange(
            "GET /first HTTP/1.1\r\nHost: localhost\r\n\r\nGET /second HTTP/1.1\r\nHost: localhost\r\n\r\n",
            handler = { req -> served++; HttpResponse(200, req.path.toByteArray(Charsets.UTF_8)) },
            stillRunning = { served == 0 },
        )
        assertEquals(sent.reply, 1, sent.served)
        assertTrue(sent.reply, sent.reply.endsWith("/first"))
    }

    @Test fun rejectsUnauthenticatedOverflowBeforeTheHandlerAndKeepsServing() {
        val handled = AtomicInteger()
        val counting: (HttpRequest) -> HttpResponse = { req ->
            assertNull(req.header("authorization"))
            handled.incrementAndGet()
            HttpResponse.empty(200)
        }

        // 超限的 chunked 正文：回 413 并断开，后面的请求一个字节都不处理。
        val overflow = exchange(request("1\r\nx\r\n7fffffff\r\n") + "GET /mcp HTTP/1.1\r\nHost: localhost\r\n\r\n", counting)
        assertEquals(overflow.reply, 0, overflow.served)
        assertTrue(overflow.reply, overflow.reply.startsWith("HTTP/1.1 413 "))
        assertTrue(overflow.reply, overflow.reply.contains("Connection: close\r\n"))
        assertFalse(overflow.reply, overflow.reply.contains("HTTP/1.1 200 "))
        assertEquals(0, handled.get())

        // 下一条连接照常服务（真连接的这一半由 `HttpServerStopTest` 覆盖）。
        val next = exchange("GET /mcp HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", counting)
        assertTrue(next.reply, next.reply.startsWith("HTTP/1.1 200 "))
        assertEquals(1, handled.get())
    }
}
