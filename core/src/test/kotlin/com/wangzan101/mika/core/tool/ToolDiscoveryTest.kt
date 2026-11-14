package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDiscoveryTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("d${++seq}", name, args)
    private fun all() = toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" })
    private fun names(specs: List<ToolSpec>) = specs.map { it.name }

    @Test fun searchFindsDeferredToolsAndTopicsWithoutExpandingGroup() = runTest {
        val d = all()
        val initial = d.offeredNow()
        val res = d.execute(call("tool_search", """{"query":"tap","capability":"phone"}""")).content
        assertEquals("true", res["ok"]!!.jsonPrimitive.content)
        val matches = res["matches"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("phone_tap" in matches)
        val topics = res["topics"]!!.jsonArray.map { it.jsonObject["topic"]!!.jsonPrimitive.content }
        assertTrue(topics.containsAll(listOf("phone:observe", "phone:ui", "phone:network")))
        // 检索目录不展开任何组,也不改动当前提供的工具列表
        assertEquals(emptySet<String>(), d.expanded)
        assertEquals(initial, d.offeredNow())
    }

    @Test fun activateIndividualToolsAppendsAtTailWithoutExpandingAllPhoneTools() = runTest {
        val d = all()
        val before = d.offeredNow()

        // Step 1: 仅激活 phone_observe 与 phone_tap 两个工具,不展开整组 57 个工具
        val r1 = d.execute(call("tool_activate", """{"tools":["phone_observe","phone_tap"]}""")).content
        assertEquals("true", r1["ok"]!!.jsonPrimitive.content)
        val act1 = r1["activated"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("phone_observe", "phone_tap"), act1)
        assertEquals(emptySet<String>(), d.expanded)

        val after1 = d.offeredNow()
        assertEquals(before.size + 2, after1.size)
        // 前缀完全不变(Append-only)
        assertEquals(before, after1.take(before.size))
        assertEquals(listOf("phone_observe", "phone_tap"), names(after1.drop(before.size)))
        assertFalse("phone_set_wheel" in names(after1))

        // Step 2: 再按子主题激活 sandbox:files,继续追加在已有末尾之后
        val r2 = d.execute(call("tool_activate", """{"topic":"sandbox:files"}""")).content
        assertEquals("true", r2["ok"]!!.jsonPrimitive.content)
        val after2 = d.offeredNow()
        assertEquals(after1, after2.take(after1.size))
        assertTrue(names(after2.drop(after1.size)).containsAll(listOf("read_file", "write_file", "search_files")))
        assertEquals(emptySet<String>(), d.expanded)

        // Step 3: 重复激活已激活的工具不产生重复项
        val r3 = d.execute(call("tool_activate", """{"tools":["phone_tap"]}""")).content
        assertEquals("true", r3["ok"]!!.jsonPrimitive.content)
        assertTrue(r3["activated"]!!.jsonArray.isEmpty())
        assertEquals(listOf("phone_tap"), r3["alreadyActive"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(after2, d.offeredNow())
    }

    @Test fun callingActivatedToolDoesNotExpandEntireCapabilityGroup() = runTest {
        val d = all()
        d.execute(call("tool_activate", """{"tools":["phone_observe","phone_tap"]}"""))
        val afterActivate = d.offeredNow()

        // 调用已通过 tool_activate 激活的 phone_observe:正常执行,且不触发整组 57 个手机工具展开
        val obs = d.execute(call("phone_observe")).content
        assertEquals("true", obs["ok"]!!.jsonPrimitive.content)
        assertNull(obs["moreTools"])
        assertEquals(emptySet<String>(), d.expanded)
        assertEquals(afterActivate, d.offeredNow())
        assertEquals(setOf("phone"), d.used)
    }

    @Test fun fullGroupExpansionLaterDeduplicatesPreviouslyActivatedTools() = runTest {
        val d = all()
        d.execute(call("tool_activate", """{"tools":["phone_observe","phone_tap"]}"""))
        // 之后调用入口工具只追加激活 app/observe/ui/handover 子主题,不重复定义已激活工具,也不展开 phone:automation / phone:network
        d.execute(call("phone_launch_app", """{"package":"com.x"}"""))
        assertEquals(emptySet<String>(), d.expanded)
        val afterEntry = names(d.offeredNow())
        assertEquals(afterEntry.distinct().size, afterEntry.size)
        assertTrue("phone_observe" in afterEntry && "phone_tap" in afterEntry && "phone_set_wheel" in afterEntry)
        assertFalse("phone_query" in afterEntry)
        // 若显式整组展开(例如 preload),同样不出现重复定义
        d.expand("phone")
        assertEquals(setOf("phone"), d.expanded)
        val now = names(d.offeredNow())
        assertEquals(now.distinct().size, now.size)
        assertTrue("phone_observe" in now && "phone_tap" in now && "phone_query" in now)
    }
}
