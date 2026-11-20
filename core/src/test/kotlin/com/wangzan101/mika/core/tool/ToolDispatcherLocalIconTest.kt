package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.LocalIcon
import com.wangzan101.mika.core.uistate.LocalIconFrame
import com.wangzan101.mika.core.uistate.LocalIconRegion
import com.wangzan101.mika.core.uistate.LocalIconResult
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ToolDispatcherLocalIconTest {
    private class Screen : ScreenPort by FakeScreen() {
        override val supportsLocalIcons = true
        var frame = LocalIconFrame(5, 10L, 2L, 100, 200, 0)
        var protected = false
        var miss: LocalIconResult.Miss? = null
        var reads = 0
        var screenshots = 0
        val taps = mutableListOf<Pair<Int, Int>>()

        override suspend fun isProtected() = protected
        override suspend fun screenshotPng(): ByteArray? { screenshots++; return null }
        override suspend fun findLocalIcon(icon: LocalIcon, region: LocalIconRegion): LocalIconResult {
            reads++
            return miss ?: LocalIconResult.Found(frame, 700, 70, 800, 170)
        }
        override suspend fun tapLocalIcon(x: Int, y: Int, frame: LocalIconFrame): ActionResult {
            if (this.frame != frame || protected) return ActionResult.Failed("located_stale")
            taps += x to y
            return ActionResult.Ok(changed = true)
        }
        override suspend fun tapXY(x: Int, y: Int): ActionResult = error("must not fall back to a coordinate tap")
    }

    private var nextId = 0
    private fun call(tool: String, args: String) = Part.ToolCall("icon${nextId++}", tool, args)
    private fun JsonObject.field(key: String) = this[key]?.jsonPrimitive?.content
    private suspend fun ToolDispatcher.find() = executeRead(call("phone_find_local_icon", """{"icon":"search","region":"top"}"""))
    private fun JsonObject.args() = "\"ref\":\"${field("ref")}\",\"snapshotId\":\"${field("snapshotId")}\""

    @Test fun localToolWorksWithoutVisionAndOnlyReturnsAnOpaqueReference() = runTest {
        val s = Screen()
        val d = toolDispatcher(s)
        val names = d.definitions().map { it.name }
        assertTrue("phone_find_local_icon" in names)
        assertFalse("phone_find_visually" in names)
        assertFalse("phone_find_local_icon" in toolDispatcher(FakeScreen()).definitions().map { it.name })
        val found = d.find()
        assertEquals("local_icon", found.content.field("source"))
        assertEquals("true", found.content.field("found"))
        assertTrue(found.content.field("ref")!!.startsWith("@v"))
        assertTrue(found.content.keys.none { it in setOf("x", "y", "box", "timestampNanos") })
        assertTrue(found.images.isEmpty())
        assertNull(found.frame)
        assertEquals(0, s.screenshots)
        val tapped = d.execute(call("phone_tap", "{${found.content.args()}}"))
        assertEquals(tapped.content.toString(), "true", tapped.content.field("ok"))
        assertEquals(listOf(750 to 120), s.taps)
        assertTrue(tapped.images.isEmpty())
        assertNull(tapped.frame)
    }

    @Test fun aRepaintInvalidatesTheReferenceEvenWithIdenticalTree() = runTest {
        val s = Screen()
        val d = toolDispatcher(s)
        val ref = d.find().content
        s.frame = s.frame.copy(timestampNanos = 11L)
        val tapped = d.execute(call("phone_tap", "{${ref.args()}}"))
        assertEquals("ref_expired", tapped.content.field("error"))
        assertTrue(s.taps.isEmpty())
    }

    @Test fun protectsContentAndDistinguishesAmbiguityWithoutCloudFallback() = runTest {
        val s = Screen()
        val d = toolDispatcher(s)
        s.protected = true
        assertEquals("protected_content", d.find().content.field("reason"))
        assertEquals(0, s.reads)
        s.protected = false
        s.miss = LocalIconResult.Miss.Ambiguous
        val miss = d.find()
        assertEquals("ambiguous", miss.content.field("reason"))
        assertFalse("ref" in miss.content)
        assertEquals(0, s.screenshots)
        assertTrue(s.taps.isEmpty())
    }

    @Test fun cannotTypeOrDoubleTapWithALocalGlyphReference() = runTest {
        val s = Screen()
        val d = toolDispatcher(s)
        val ref = d.find().content
        assertEquals("invalid_arguments", d.execute(call("phone_tap", "{${ref.args()},\"clicks\":2}")).content.field("error"))
        assertEquals("ref_expired", d.execute(call("phone_type_text", "{${ref.args()},\"text\":\"a\"}")).content.field("error"))
        assertTrue(s.taps.isEmpty())
    }
}
