package com.wangzan101.mika.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 形状提取只回答「有哪些字段、各是什么类型」，值一律不留。
 * 每个用例都顺手断言一次「值没进结果」，防止哪天为了调试把值也带上。
 */
class ApiShapeTest {

    private fun shape(json: String, maxFields: Int = MAX_API_FIELDS): ApiShapeResult {
        val r = ApiShape.extract(json, maxFields)
        assertNotNull("expected parsable json: $json", r)
        return r!!
    }

    private fun typeOf(result: ApiShapeResult, path: String): String? =
        result.fields.firstOrNull { it.path == path }?.type

    @Test
    fun flatObjectKeepsKeyOrder() {
        val r = shape("""{"Result":0,"Message":"ok","HasMore":true}""")
        assertEquals(listOf("Result", "Message", "HasMore"), r.fields.map { it.path })
        assertEquals("int", typeOf(r, "Result"))
        assertEquals("string", typeOf(r, "Message"))
        assertEquals("bool", typeOf(r, "HasMore"))
    }

    @Test
    fun nestedObjectsUseDotPath() {
        val r = shape("""{"Data":{"DailyBenefitModule":{"Process":1}}}""")
        assertEquals("object", typeOf(r, "Data"))
        assertEquals("object", typeOf(r, "Data.DailyBenefitModule"))
        assertEquals("int", typeOf(r, "Data.DailyBenefitModule.Process"))
    }

    @Test
    fun arrayElementObjectFieldsUseBrackets() {
        val r = shape("""{"Data":{"TaskList":[{"IsFinished":1,"Desc":"x"}]}}""")
        assertEquals("array", typeOf(r, "Data.TaskList"))
        assertEquals("object", typeOf(r, "Data.TaskList[]"))
        assertEquals("int", typeOf(r, "Data.TaskList[].IsFinished"))
        assertEquals("string", typeOf(r, "Data.TaskList[].Desc"))
    }

    @Test
    fun arrayElementsAreUnioned() {
        val r = shape("""{"list":[{"a":1},{"b":"x"},{"a":2}]}""")
        assertEquals("int", typeOf(r, "list[].a"))
        assertEquals("string", typeOf(r, "list[].b"))
        assertEquals("元素并集里 a 出现两次", 2, r.fields.first { it.path == "list[].a" }.seen)
    }

    @Test
    fun arraySamplesAreCapped() {
        val items = (1..(MAX_API_ARRAY_SAMPLES + 5)).joinToString(",") { """{"k$it":1}""" }
        val r = shape("""{"list":[$items]}""")
        // 第 21 个元素的键不该出现
        assertNull(typeOf(r, "list[].k${MAX_API_ARRAY_SAMPLES + 1}"))
        assertEquals("int", typeOf(r, "list[].k1"))
    }

    @Test
    fun typeConflictBecomesMixed() {
        val r = shape("""{"list":[{"v":1},{"v":"x"}]}""")
        assertEquals("mixed", typeOf(r, "list[].v"))
    }

    @Test
    fun largeIntegersAreLong() {
        val r = shape("""{"id":9007199254740993}""")
        assertEquals("long", typeOf(r, "id"))
    }

    @Test
    fun doublesAndNullsHaveOwnTypes() {
        val r = shape("""{"price":1.5,"gone":null}""")
        assertEquals("double", typeOf(r, "price"))
        assertEquals("null", typeOf(r, "gone"))
    }

    @Test
    fun depthIsCapped() {
        val json = """{"a":{"b":{"c":{"d":{"e":{"f":{"g":1}}}}}}}"""
        val r = shape(json)
        assertEquals("object", typeOf(r, "a.b.c.d.e.f"))
        // 第六层之后不再下探
        assertNull(typeOf(r, "a.b.c.d.e.f.g"))
    }

    @Test
    fun fieldCountIsCappedAndFlagged() {
        val r = shape("""{"a":1,"b":2,"c":3,"d":4,"e":5}""", maxFields = 3)
        assertEquals(3, r.fields.size)
        assertTrue(r.truncated)
    }

    @Test
    fun defaultCapCoversARealWelfareCenterResponse() {
        // 实测:mainPage 一次响应 19.9KB,按同一套规则切出 220 条路径、24 个模块;
        // 上限 80 时后面的 PrivilegedTaskModule、ReadingPageTaskModule 整块丢掉
        val modules = (1..24).joinToString(",") { m -> """"M$m":{"a":1,"b":"x","c":{"d":1,"e":2,"f":3,"g":4,"h":5}}""" }
        val r = shape("""{"Data":{$modules}}""")
        assertEquals(24 * 9 + 1, r.fields.size)
        assertFalse("默认上限要能装下一次真实响应", r.truncated)
    }

    @Test
    fun dynamicKeysAreFoldedWhenObjectIsWide() {
        val keys = (10..49).joinToString(",") { "\"$it\":{\"v\":1}" }
        val r = shape("""{"items":{$keys}}""")
        assertEquals("object", typeOf(r, "items"))
        assertEquals("折叠成 * 之后只留一条样本路径", "object", typeOf(r, "items.*"))
        assertEquals("int", typeOf(r, "items.*.v"))
        assertFalse("数字键本身不该出现在结果里", r.fields.any { it.path == "items.10" })
    }

    @Test
    fun narrowObjectKeepsNumericKeys() {
        // 键少时不折叠：3 个数字键更像固定字段（如分档 1/2/3），不是动态 id
        val r = shape("""{"level":{"1":"a","2":"b","3":"c"}}""")
        assertEquals("string", typeOf(r, "level.1"))
    }

    @Test
    fun topLevelArrayIsAccepted() {
        // 顶层数组没有自己的路径（根就是空），元素用 `[]`；`[].a` 已隐含「顶层是数组」
        val r = shape("""[{"a":1},{"a":2}]""")
        assertEquals("object", typeOf(r, "[]"))
        assertEquals("int", typeOf(r, "[].a"))
        assertEquals(2, r.fields.first { it.path == "[].a" }.seen)
    }

    @Test
    fun rejectsNonJsonAndScalars() {
        assertNull(ApiShape.extract("not json"))
        assertNull(ApiShape.extract(""))
        assertNull(ApiShape.extract("42"))
        assertNull(ApiShape.extract("\"just a string\""))
    }

    @Test
    fun valuesNeverLeakIntoPaths() {
        val r = shape("""{"name":"张三","token":"secret-value","phone":"13800000000"}""")
        val joined = r.fields.joinToString("|") { it.path }
        assertFalse(joined.contains("张三"))
        assertFalse(joined.contains("secret-value"))
        assertFalse(joined.contains("13800000000"))
    }

    @Test
    fun looksLikeJsonHandlesBomAndWhitespace() {
        assertTrue(ApiShape.looksLikeJson("""{"a":1}""".toByteArray()))
        assertTrue(ApiShape.looksLikeJson("[1,2]".toByteArray()))
        assertTrue(ApiShape.looksLikeJson("\n  \t{\"a\":1}".toByteArray()))
        assertTrue(
            ApiShape.looksLikeJson(
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + """{"a":1}""".toByteArray()
            )
        )
    }

    @Test
    fun looksLikeJsonRejectsBinaryAndHtml() {
        assertFalse(ApiShape.looksLikeJson(null))
        assertFalse(ApiShape.looksLikeJson(ByteArray(0)))
        assertFalse(ApiShape.looksLikeJson("<!DOCTYPE html>".toByteArray()))
        assertFalse(ApiShape.looksLikeJson(byteArrayOf(0x1f, 0x8b.toByte(), 0x08)))
    }
}
