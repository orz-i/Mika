package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.testing.TOOLS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPrunerMultiCallTest {
    @Test
    fun anchorFullScreenOnlyExemptsItsOwnCallIdAndPreservesSiblingObsIds() {
        val bigText = "正文内容".repeat(2_000)
        val history = listOf(
            Message(Role.User, listOf(Part.Text("查看")), Author.User),
            Message(
                Role.Assistant,
                listOf(
                    Part.ToolCall("obs1", "phone_observe", "{}"),
                    Part.ToolCall("read1", "read_file", """{"path":"/a.txt"}"""),
                ),
                Author.Mika,
            ),
            Message(
                Role.Tool,
                listOf(
                    Part.ToolResult("obs1", """{"ok":true,"elements":[{"id":"e1"}],"app":"shop"}""", isError = false),
                    Part.ToolResult(
                        "read1",
                        """{"ok":true,"path":"/a.txt","content":"$bigText","obsIds":["o1","o2"]}""",
                        isError = false,
                    ),
                ),
                Author.Mika,
            ),
            Message(Role.Assistant, listOf(Part.ToolCall("t2", "phone_tap", """{"target":"确定"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("t2", """{"ok":true}""", isError = false)), Author.Mika),
            Message(Role.Assistant, listOf(Part.ToolCall("t3", "phone_tap", """{"target":"提交"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("t3", """{"ok":true}""", isError = false)), Author.Mika),
        )

        val pruned = HistoryPruner.prune(history, TOOLS, HistoryPruner.Cut(2))
        val results = pruned.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().associateBy { it.callId }

        assertTrue("anchor full screen must remain intact", results["obs1"]!!.content.contains(""""id":"e1""""))
        val shrunkRead = Json.parseToJsonElement(results["read1"]!!.content) as JsonObject
        assertEquals(HistoryPruner.OMITTED_READ, shrunkRead["content"]!!.jsonPrimitive.content)
        assertTrue("obsIds must be preserved", results["read1"]!!.content.contains("o1") && results["read1"]!!.content.contains("o2"))
    }

    @Test
    fun shrinkOversizedStopsAfterShrinkingEnoughParallelParts() {
        val big = "x".repeat(4_000)
        val messages = listOf(
            Message(
                Role.Tool,
                listOf(
                    Part.ToolResult("p1", """{"ok":true,"content":"$big"}""", isError = false),
                    Part.ToolResult("p2", """{"ok":true,"content":"$big","obsIds":["o9"]}""", isError = false),
                ),
                Author.Mika,
            ),
        )

        val shrunk = HistoryPruner.shrinkOversized(messages) { ms ->
            val rs = ms.single().parts.filterIsInstance<Part.ToolResult>()
            rs.count { !it.content.contains(HistoryPruner.OMITTED_READ) } <= 1
        }
        val rs = shrunk.single().parts.filterIsInstance<Part.ToolResult>().associateBy { it.callId }
        assertTrue("earlier parallel part should remain unshrunk when later one suffices", rs["p1"]!!.content.contains(big))
        assertTrue("later parallel part should be shrunk", rs["p2"]!!.content.contains(HistoryPruner.OMITTED_READ))
        assertTrue("obsIds on shrunk part must remain", rs["p2"]!!.content.contains("o9"))
    }
}
