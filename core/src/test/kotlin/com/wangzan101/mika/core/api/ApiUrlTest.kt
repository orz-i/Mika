package com.wangzan101.mika.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归一化的每一条规则都要能单独解释，所以一例只验证一条。
 * 这里的用例就是 `docs/dev/app-api-graph.md` 4.4 那张表的可执行版本。
 */
class ApiUrlTest {

    private fun view(url: String): ApiUrlView {
        val v = ApiUrl.normalize(url)
        assertNotNull("expected parsable url: $url", v)
        return v!!
    }

    @Test
    fun lowercasesHost() {
        assertEquals("h5.if.qidian.com", view("https://H5.IF.Qidian.com/new/welfareCenter").host)
    }

    @Test
    fun dropsDefaultHttpsPort() {
        assertEquals("h5.if.qidian.com", view("https://h5.if.qidian.com:443/x").host)
    }

    @Test
    fun keepsNonDefaultPort() {
        assertEquals("h5.if.qidian.com:8443", view("https://h5.if.qidian.com:8443/x").host)
    }

    @Test
    fun keepsSchemeLowercased() {
        assertEquals("https", view("HTTPS://a.com/x").scheme)
    }

    @Test
    fun templatesMultiDigitNumericSegment() {
        assertEquals("/user/{id}/orders", view("https://a.com/user/12345/orders").path)
    }

    @Test
    fun keepsSingleDigitSegment() {
        // 一位数更像序号而不是 id，替换掉会把 `/v1` 这类结构也吃掉
        assertEquals("/rank/7", view("https://a.com/rank/7").path)
    }

    @Test
    fun keepsVersionSegment() {
        assertEquals(
            "/argus/api/v2/video/adv/mainPage",
            view("https://h5.if.qidian.com/argus/api/v2/video/adv/mainPage").path,
        )
    }

    @Test
    fun templatesUuidSegment() {
        assertEquals(
            "/task/{uuid}/state",
            view("https://a.com/task/3f2504e0-4f89-11d3-9a0c-0305e82c3301/state").path,
        )
    }

    @Test
    fun templatesLongHexSegment() {
        assertEquals("/task/{uuid}", view("https://a.com/task/a1b2c3d4e5").path)
    }

    @Test
    fun queryKeepsKeysOnlyAndSortsThem() {
        val v = view("https://a.com/x?positions=newcheckin&_t=1712345678")
        assertEquals(listOf("_t", "positions"), v.queryKeys)
    }

    @Test
    fun queryDeduplicatesKeys() {
        assertEquals(listOf("a", "b"), view("https://a.com/x?a=1&b=2&a=3").queryKeys)
    }

    @Test
    fun queryCapsKeyCount() {
        val url = (1..(MAX_API_QUERY_KEYS + 10)).joinToString("&") { "k$it=$it" }
        assertEquals(MAX_API_QUERY_KEYS, view("https://a.com/x?$url").queryKeys.size)
    }

    @Test
    fun stripsTrailingSlash() {
        assertEquals("/new/welfareCenter", view("https://h5.if.qidian.com/new/welfareCenter/").path)
    }

    @Test
    fun emptyPathBecomesRoot() {
        assertEquals("/", view("https://a.com").path)
    }

    @Test
    fun fragmentIsIgnored() {
        val v = view("https://a.com/x?a=1#section")
        assertEquals(listOf("a"), v.queryKeys)
        assertEquals("/x", v.path)
    }

    @Test
    fun rejectsNonHttpScheme() {
        assertNull(ApiUrl.normalize("ftp://a.com/x"))
        assertNull(ApiUrl.normalize("ws://a.com/x"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(ApiUrl.normalize(""))
        assertNull(ApiUrl.normalize("   "))
        assertNull(ApiUrl.normalize("not a url"))
        assertNull(ApiUrl.normalize("/just/a/path"))
    }

    @Test
    fun endpointIdIncludesSortedQueryKeys() {
        val v = view("https://h5.if.qidian.com/argus/api/v2/video/adv/mainPage?positions=x&_t=1")
        assertEquals(
            "GET h5.if.qidian.com/argus/api/v2/video/adv/mainPage?_t&positions",
            ApiUrl.endpointId("get", v),
        )
    }

    @Test
    fun endpointIdOmitsQuestionMarkWhenNoQuery() {
        val v = view("https://a.com/api/v1/ping")
        assertEquals("POST a.com/api/v1/ping", ApiUrl.endpointId("post", v))
    }

    @Test
    fun sameEndpointForDifferentQueryValues() {
        val a = ApiUrl.endpointId("GET", view("https://a.com/x?t=1"))
        val b = ApiUrl.endpointId("GET", view("https://a.com/x?t=999"))
        assertEquals(a, b)
    }

    @Test
    fun differentEndpointsForDifferentQueryKeys() {
        val a = ApiUrl.endpointId("GET", view("https://a.com/x?t=1"))
        val b = ApiUrl.endpointId("GET", view("https://a.com/x?page=1"))
        assertTrue(a != b)
    }
}
