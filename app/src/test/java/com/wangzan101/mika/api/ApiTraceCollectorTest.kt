package com.wangzan101.mika.api

import com.wangzan101.mika.core.api.ApiExchangeInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集器全链路：客户端 URL → 过滤 → 形状 → trace。
 *
 * 样本按起点福利中心 `mainPage` 的真实响应结构裁剪，用来钉住「完成情况在
 * `Data.DailyBenefitModule.TaskList[].IsFinished`」这条结论——P1 建图要按这个路径取数。
 */
class ApiTraceCollectorTest {

    private val mainPageJson = """
        {"Result":0,"Data":{"DailyBenefitModule":{
          "Count":46,"Process":1,"Desc":"完成广告任务，多重好礼等你解锁",
          "TaskList":[
            {"Desc":"10-3点订阅券","IsFinished":1,"IsReceived":0,"TaskId":"1205501151274598400"},
            {"Desc":"惊喜礼包","IsFinished":0,"IsReceived":0}
          ]}}}
    """.trimIndent()

    private fun collector(maxEntries: Int = 500) =
        ApiTraceCollector(packageName = "com.qidian.QDReader", now = { 1_000L }, maxEntries = maxEntries)

    private fun exchange(
        url: String,
        method: String = "GET",
        status: Int = 200,
        mime: String? = "application/json; charset=utf-8",
        body: String? = null,
        postData: String? = null,
        headers: List<String> = emptyList(),
        endMs: Long = 2_000L,
        durationMs: Long = 120L,
    ) = ApiExchangeInput(
        url = url,
        method = method,
        status = status,
        mime = mime,
        durationMs = durationMs,
        endMs = endMs,
        postData = postData,
        headerNames = headers,
        body = body?.toByteArray(Charsets.UTF_8),
    )

    @Test
    fun recordsEndpointAndFieldShape() {
        val c = collector()
        c.onExchange(
            exchange(
                "https://h5.if.qidian.com/argus/api/v2/video/adv/mainPage?positions=newcheckin&_t=1712345678",
                body = mainPageJson,
                headers = listOf("Cookie", "Referer"),
            )
        )

        val trace = c.snapshot(endedMs = 3_000L)
        assertEquals(1, trace.observations.size)
        val obs = trace.observations.single()

        assertEquals("GET h5.if.qidian.com/argus/api/v2/video/adv/mainPage?_t&positions", obs.endpointId)
        assertEquals(listOf("_t", "positions"), obs.queryKeys)
        assertEquals("application/json", obs.mime)
        assertTrue(obs.authTraits and 1 != 0)   // cookie
        assertTrue(obs.authTraits and 8 != 0)   // referer

        val finished = obs.fields.firstOrNull { it.path == "Data.DailyBenefitModule.TaskList[].IsFinished" }
        assertNotNull("完成度字段必须在形状里", finished)
        assertEquals("int", finished!!.type)
        assertEquals(2, finished.seen)
    }

    @Test
    fun valuesNeverReachTheTrace() {
        val c = collector()
        c.onExchange(exchange("https://h5.if.qidian.com/argus/api/v2/video/adv/mainPage", body = mainPageJson))

        val serialized = c.snapshot().toString()
        assertFalse("任务名不该出现在 trace 里", serialized.contains("10-3点订阅券"))
        assertFalse("任务 id 不该出现", serialized.contains("1205501151274598400"))
        assertFalse(serialized.contains("多重好礼"))
    }

    @Test
    fun queryValuesNeverReachTheTrace() {
        val c = collector()
        c.onExchange(
            exchange(
                "https://a.com/api/v1/x?token=abc123&sign=deadbeef",
                body = """{"Result":0,"Data":{"Page":1}}""",
            )
        )

        val serialized = c.snapshot().toString()
        assertFalse(serialized.contains("abc123"))
        assertFalse(serialized.contains("deadbeef"))
        assertTrue(serialized.contains("token"))
    }

    @Test
    fun staticAssetsAreDroppedAndCounted() {
        val c = collector()
        c.onExchange(exchange("https://a.com/static/main.js", mime = "application/javascript"))
        c.onExchange(exchange("https://a.com/img/banner.png", mime = "image/png"))

        val trace = c.snapshot()
        assertTrue(trace.observations.isEmpty())
        assertEquals(2, trace.dropped)
    }

    @Test
    fun droppedIsBucketedByReason() {
        // 验收时要能回答「这个接口是没抓到，还是被规则丢了」：只记总数回答不了
        val c = collector()
        c.onExchange(exchange("https://a.com/static/main.js", mime = "application/javascript"))
        c.onExchange(exchange("https://zhiyan.monitor.tencent-cloud.net/x/HttpCurveReportRpc", method = "POST", postData = """{"a":1}"""))
        c.onExchange(exchange("https://h5.if.qidian.com/new/welfareCenter/", mime = "text/html", body = "<html></html>".repeat(20)))

        val trace = c.snapshot()
        assertEquals(3, trace.dropped)
        assertEquals(1, trace.droppedBy[com.wangzan101.mika.core.api.ApiFilter.DROP_STATIC])
        assertEquals(1, trace.droppedBy[com.wangzan101.mika.core.api.ApiFilter.DROP_TRACKING])
        assertEquals(1, trace.droppedBy[com.wangzan101.mika.core.api.ApiFilter.DROP_HTML])
        assertEquals(3, trace.droppedBy.values.sum())
    }

    @Test
    fun telemetryIsDropped() {
        val c = collector()
        c.onExchange(
            exchange(
                "https://a.com/argus/qreport",
                method = "POST",
                postData = """{"ev":"click"}""",
            )
        )
        assertTrue(c.snapshot().observations.isEmpty())
    }

    @Test
    fun postBodyKeysAreKeptButNotValues() {
        val c = collector()
        c.onExchange(
            exchange(
                "https://a.com/api/v1/order",
                method = "POST",
                postData = """{"bookId":123,"couponCode":"SECRET"}""",
                body = """{"Result":0}""",
                headers = listOf("X-Sign", "Authorization"),
            )
        )

        val obs = c.snapshot().observations.single()
        assertEquals(listOf("bookId", "couponCode"), obs.bodyKeys)
        assertTrue(obs.authTraits and 2 != 0)    // authorization
        assertTrue(obs.authTraits and 16 != 0)   // x- 自定义头
        assertFalse(obs.toString().contains("SECRET"))
    }

    @Test
    fun entryCapIsEnforcedAndFlagged() {
        val c = collector(maxEntries = 2)
        repeat(5) { i ->
            c.onExchange(
                exchange("https://a.com/api/v1/item$i", body = """{"Result":0,"Data":{"Index":$i}}""")
            )
        }

        val trace = c.snapshot()
        assertEquals(2, trace.observations.size)
        assertTrue(trace.truncated)
    }

    @Test
    fun oversizedBodyKeepsEndpointAndSkipsShape() {
        val c = collector()
        val big = "{" + (1..40_000).joinToString(",") { "\"k$it\":" + "1".repeat(20) } + "}"
        assertTrue("样本要超过 512KB", big.length > 512 * 1024)

        c.onExchange(exchange("https://a.com/api/v1/big", body = big))
        val obs = c.snapshot().observations.single()
        assertEquals("GET a.com/api/v1/big", obs.endpointId)
        assertTrue("超大响应不解析字段", obs.fields.isEmpty())
    }

    @Test
    fun durationGivesStartTimestamp() {
        val c = collector()
        c.onExchange(
            exchange("https://a.com/api/v1/x", body = """{"Result":0}""", endMs = 10_000L, durationMs = 350L)
        )
        val obs = c.snapshot().observations.single()
        assertEquals(9_650L, obs.startMs)
        assertEquals(10_000L, obs.endMs)
    }

    @Test
    fun gzippedBodyIsNotParsedAsJson() {
        val c = collector()
        val gzipMagic = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00)
        c.onExchange(
            ApiExchangeInput(
                url = "https://a.com/api/v1/x",
                method = "GET",
                status = 200,
                mime = "application/json",
                durationMs = 10L,
                endMs = 100L,
                body = gzipMagic + ByteArray(200),
            )
        )
        val obs = c.snapshot().observations.single()
        assertTrue(obs.fields.isEmpty())
    }

    @Test
    fun resetClearsEverything() {
        val c = collector()
        c.onExchange(exchange("https://a.com/api/v1/x"))
        c.reset()
        val trace = c.snapshot()
        assertTrue(trace.observations.isEmpty())
        assertEquals(0, trace.dropped)
    }
}
