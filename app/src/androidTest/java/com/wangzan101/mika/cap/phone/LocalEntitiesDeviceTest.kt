package com.wangzan101.mika.cap.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真模型：确认 app 侧的 [LocalEntities] 在这台设备上装得上、抽得对。
 * 模型资产不入库（见 `aau-ner/README.md`），没导入的检出跳过。
 */
@RunWith(AndroidJUnit4::class)
class LocalEntitiesDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun extractsAddressAndPersonFromOneSentence() {
        val entities = LocalEntities.create(context)
        assumeTrue("NER assets or runtime unavailable on this build", entities != null)

        val text = "明天下午三点去北京朝阳区望京SOHO找张三"
        val spans = entities!!.extract(text)

        assertTrue("address in $spans", spans.any { it.kind == "address" && it.text == "北京朝阳区望京SOHO" })
        assertTrue("person in $spans", spans.any { it.kind == "person" && it.text == "张三" })
        spans.forEach { assertEquals(it.text, text.substring(it.start, it.end)) }
    }

    @Test
    fun keepsExpressNumberAndPhoneApart() {
        val entities = LocalEntities.create(context)
        assumeTrue("NER assets or runtime unavailable on this build", entities != null)

        val spans = entities!!.extract("我的快递单号SF1234567890，电话13800138000")
        assertTrue("express in $spans", spans.any { it.kind == "express_number" && it.text == "SF1234567890" })
        assertTrue("phone in $spans", spans.any { it.kind == "phone" && it.text == "13800138000" })
    }

    @Test
    fun reusesOneModelAcrossCalls() {
        val entities = LocalEntities.create(context)
        assumeTrue("NER assets or runtime unavailable on this build", entities != null)

        val text = "收货人 张三 13800138000 地址 上海市浦东新区世纪大道100号"
        val first = entities!!.extract(text)
        assertTrue("expected spans, got $first", first.isNotEmpty())
        repeat(3) { assertEquals(first, entities.extract(text)) }
    }

    @Test
    fun returnsNothingForTextWithoutEntities() {
        val entities = LocalEntities.create(context) ?: return
        assertNotNull(entities)
        assertTrue(entities.extract("今天天气不错").none { it.kind == "phone" })
    }
}
