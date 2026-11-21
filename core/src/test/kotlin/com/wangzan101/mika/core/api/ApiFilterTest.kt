package com.wangzan101.mika.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过滤规则的目标是「宁可多留，不要丢真接口」。
 * 所以这里重点盯两类反例：看起来像埋点的业务接口不能丢，看起来像接口的静态资源不能留。
 */
class ApiFilterTest {

    private fun view(url: String): ApiUrlView = ApiUrl.normalize(url)!!

    private fun keep(
        url: String,
        method: String = "GET",
        status: Int = 200,
        contentType: String? = "application/json",
        bodySize: Int = 500,
        hasStructuredRequest: Boolean = false,
    ): Boolean = ApiFilter.keep(view(url), method, status, contentType, bodySize, hasStructuredRequest)

    @Test
    fun keepsJsonResponse() {
        assertTrue(keep("https://h5.if.qidian.com/argus/api/v2/video/adv/mainPage?positions=x"))
    }

    @Test
    fun keepsPathThatOnlyLooksLikeApi() {
        assertTrue(keep("https://a.com/v2/feed", contentType = "text/plain"))
    }

    @Test
    fun keepsPostWithFormBody() {
        assertTrue(
            keep(
                "https://a.com/submit",
                method = "POST",
                contentType = "text/html",
                bodySize = 300,
                hasStructuredRequest = true,
            )
        )
    }

    @Test
    fun keepsPostWithoutResponseBody() {
        assertTrue(
            keep(
                "https://a.com/action",
                method = "POST",
                contentType = null,
                bodySize = 0,
                hasStructuredRequest = true,
            )
        )
    }

    @Test
    fun dropsGetWithoutBody() {
        assertFalse(keep("https://a.com/ping", contentType = null, bodySize = 0))
    }

    @Test
    fun dropsStaticAssets() {
        assertFalse(keep("https://a.com/static/app.js", contentType = "application/javascript"))
        assertFalse(keep("https://a.com/img/banner.png", contentType = "image/png"))
        assertFalse(keep("https://a.com/x/icon.woff2", contentType = "font/woff2"))
    }

    @Test
    fun dropsTelemetryPaths() {
        assertFalse(keep("https://a.com/log/event", method = "POST", hasStructuredRequest = true))
        assertFalse(keep("https://a.com/track/exposure", method = "POST", hasStructuredRequest = true))
        assertFalse(keep("https://a.com/argus/qreport", method = "POST", hasStructuredRequest = true))
    }

    @Test
    fun keepsBusinessPathThatContainsCollect() {
        // `/bookshelf/collect` 是收藏，不是上报；埋点判定要求带分隔符的段
        assertTrue(keep("https://a.com/bookshelf/collect", method = "POST", hasStructuredRequest = true))
    }

    @Test
    fun dropsTelemetryHost() {
        assertFalse(
            keep(
                "https://reportapi.a.com/collect",
                method = "POST",
                contentType = "application/json",
                hasStructuredRequest = true,
            )
        )
    }

    @Test
    fun dropsNoContentStatus() {
        assertFalse(keep("https://a.com/api/v1/x", status = 204))
        assertFalse(keep("https://a.com/api/v1/x", status = 304))
    }

    @Test
    fun dropsHtmlPage() {
        assertFalse(keep("https://h5.if.qidian.com/new/welfareCenter/", contentType = "text/html; charset=utf-8", bodySize = 573696))
    }

    // ===== 真机实测（2026-11-21，起点福利中心一次运行 54 条观测）暴露的形态 =====

    @Test
    fun dropsCamelCaseTelemetryPath() {
        // 41/54 条是这个：段名里既没有 `/log/` 也没有 `/report/`，只有驼峰的 CurveReport
        assertFalse(
            keep(
                "https://zhiyan.monitor.tencent-cloud.net/access_v1.http_service/HttpCurveReportRpc",
                method = "POST",
                contentType = "application/json",
                hasStructuredRequest = true,
            )
        )
        assertEquals(
            ApiFilter.DROP_TRACKING,
            ApiFilter.classify(
                view("https://zhiyan.monitor.tencent-cloud.net/access_v1.http_service/HttpCurveReportRpc"),
                "POST", 200, "application/json", 500, true,
            ),
        )
    }

    @Test
    fun dropsHyphenatedLogTail() {
        // `/s/tts-report/log` 以 log 结尾，旧规则要求 `/log/` 带尾斜杠，漏了
        assertFalse(keep("https://ys.yuewen.com/s/tts-report/log", method = "POST", contentType = "application/json", hasStructuredRequest = true))
    }

    @Test
    fun dropsHostWithMonitorSegment() {
        // 埋点名在 host 的第二段，旧规则只看第一段（zhiyan）
        assertFalse(keep("https://zhiyan.monitor.tencent-cloud.net/anything", contentType = "application/json"))
    }

    @Test
    fun keepsBusinessEndpointsAroundTelemetry() {
        // 同一批抓包里真正要学的几个
        assertTrue(keep("https://h5.if.qidian.com/argus/api/v2/video/adv/mainPage"))
        assertTrue(keep("https://h5.if.qidian.com/argus/api/v3/checkin/getcurrentweekcheckininfo"))
        assertTrue(keep("https://h5.if.qidian.com/argus/api/v2/readtime/readpage"))
        assertTrue(keep("https://druidv6.if.qidian.com/argus/api/v1/common/risk/check?appId=x"))
        assertTrue(keep("https://druidv6.if.qidian.com/argus/api/v1/bookshelf/collect", method = "POST", hasStructuredRequest = true))
    }

    @Test
    fun dropsSdkCollectOnRootPath() {
        // 冷启动那次 47/126 条是这两类：`/collect` 是 SDK 上报的通用根路径
        assertFalse(keep("https://galileotelemetry.tencent.com/collect", method = "POST", hasStructuredRequest = true))
        assertFalse(keep("https://publiclog.zhiyan.tencent-cloud.net/collect", method = "POST", hasStructuredRequest = true))
        assertFalse(keep("https://path.book.qq.com/collect", method = "POST", hasStructuredRequest = true))
    }

    @Test
    fun dropsFramesWithUnknownMethod() {
        // h2 优先帧/隧道帧：host=unknown-host、path=/、method=UNKNOWN、status=200，看着像成功交换
        assertFalse(keep("https://unknown-host/", method = "UNKNOWN", contentType = "application/json"))
        assertEquals(
            ApiFilter.DROP_INVALID,
            ApiFilter.classify(view("https://druidv6.if.qidian.com/"), "UNKNOWN", 200, "application/json", 454, false),
        )
    }

    @Test
    fun keepsStandardMethodsThatAreNotGetOrPost() {
        assertTrue(keep("https://a.com/api/v1/x", method = "PUT", hasStructuredRequest = true))
        assertTrue(keep("https://a.com/api/v1/x", method = "DELETE"))
    }

    @Test
    fun keepsBusinessHostsThatMerelyContainShortWords() {
        // blog / logistics / statics 这类业务域名不该被 log、stat 误伤
        assertTrue(keep("https://blog.a.com/api/v1/posts", contentType = "application/json"))
        assertTrue(keep("https://stats-api.a.com/v1/numbers", contentType = "text/plain"))
    }

    @Test
    fun keepsSmallJsonResponse() {
        // `{"Result":0}` 只有 12 字节，但成功标记结构正是要学的，不能按长度丢
        assertTrue(keep("https://a.com/api/v1/x", contentType = "application/json", bodySize = 12))
    }

    @Test
    fun dropsTinyUnstructuredBody() {
        assertFalse(keep("https://a.com/x", contentType = null, bodySize = 10))
    }

    @Test
    fun keepsErrorResponsesForLaterGating() {
        // 402 这类正是要看的证据（起点签名错误就是 402），建图阶段再决定是否成边
        assertTrue(keep("https://h5.if.qidian.com/argus/api/v1/x", status = 402))
    }
}

class ApiTraitsTest {

    @Test
    fun detectsCookieAndReferer() {
        val traits = ApiTraits.of(listOf("Cookie", "Referer"), emptyList(), emptyList())
        assertTrue(traits and TRAIT_COOKIE != 0)
        assertTrue(traits and TRAIT_REFERER != 0)
        assertFalse(traits and TRAIT_AUTHORIZATION != 0)
    }

    @Test
    fun detectsAuthorizationAndCustomHeader() {
        val traits = ApiTraits.of(listOf("authorization", "x-request-id"), emptyList(), emptyList())
        assertTrue(traits and TRAIT_AUTHORIZATION != 0)
        assertTrue(traits and TRAIT_CUSTOM_HEADER != 0)
    }

    @Test
    fun signatureHintComesFromKeysNotValues() {
        val traits = ApiTraits.of(emptyList(), listOf("_t", "ibex", "borgus", "tstamp"), emptyList())
        assertTrue(traits and TRAIT_SIGNATURE_HINT != 0)
    }

    @Test
    fun plainKeysHaveNoSignatureHint() {
        val traits = ApiTraits.of(emptyList(), listOf("page", "size"), listOf("bookId"))
        assertFalse(traits and TRAIT_SIGNATURE_HINT != 0)
    }

    @Test
    fun detectsSignatureHintsInHeaders() {
        // 实测:起点的 tstamp / ibex / borgus / sdksign 全在请求头,query 一个都没有
        val traits = ApiTraits.of(listOf("tstamp", "ibex", "borgus", "sdksign"), emptyList(), emptyList())
        assertTrue(traits and TRAIT_SIGNATURE_HINT != 0)
    }

    @Test
    fun describeIsReadable() {
        val traits = ApiTraits.of(listOf("cookie"), listOf("sign"), emptyList())
        assertEquals(listOf("cookie", "疑似签名"), ApiTraits.describe(traits))
    }
}

class ApiRequestKeysTest {

    @Test
    fun extractsTopLevelJsonKeys() {
        assertEquals(
            listOf("bookId", "chapterId", "extra"),
            ApiRequestKeys.of("""{"bookId":1,"chapterId":2,"extra":{"a":1}}"""),
        )
    }

    @Test
    fun extractsFormKeys() {
        assertEquals(listOf("a", "b"), ApiRequestKeys.of("a=1&b=2"))
    }

    @Test
    fun ignoresValues() {
        val keys = ApiRequestKeys.of("""{"token":"secret"}""")
        assertEquals(listOf("token"), keys)
    }

    @Test
    fun emptyForUnparsableBody() {
        assertTrue(ApiRequestKeys.of(null).isEmpty())
        assertTrue(ApiRequestKeys.of("   ").isEmpty())
        assertTrue(ApiRequestKeys.of("plain text body").isEmpty())
    }
}
