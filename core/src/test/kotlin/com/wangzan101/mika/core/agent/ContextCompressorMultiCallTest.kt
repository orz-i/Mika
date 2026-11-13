package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.testing.TOOLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressorMultiCallTest {
    private fun multiRound(
        calls: List<Triple<String, String, String>>,
        results: Map<String, String>,
        said: String? = null,
    ): List<Message> = listOf(
        Message(
            Role.Assistant,
            listOfNotNull(said?.let { Part.Text(it) }) + calls.map { (id, name, args) -> Part.ToolCall(id, name, args) },
            Author.Mika,
        ),
        Message(
            Role.Tool,
            calls.map { (id, _, _) -> Part.ToolResult(id, results.getValue(id), isError = false) },
            Author.Mika,
        ),
    )

    private fun taps(startIdx: Int, count: Int): List<Message> = (startIdx until startIdx + count).flatMap { i ->
        multiRound(
            listOf(Triple("tap$i", "phone_tap", """{"target":"按钮$i"}""")),
            mapOf("tap$i" to """{"ok":true,"changed":true}"""),
        )
    }

    @Test
    fun foldGeneratesSummaryLineForEveryCallInMultiCallRound() {
        val h = listOf(Message(Role.User, listOf(Part.Text("查资料")), Author.User)) +
            multiRound(
                calls = listOf(
                    Triple("c1", "read_file", """{"path":"/a.txt"}"""),
                    Triple("c2", "web_search", """{"query":"kotlin"}"""),
                ),
                results = mapOf(
                    "c1" to """{"ok":true,"content":"hello"}""",
                    "c2" to """{"ok":true,"results":["r1"]}""",
                ),
                said = "并行读取和搜索",
            ) +
            taps(1, 10)

        val compressed = ContextCompressor(TOOLS, structured = true).compress(h, contextTokens = 100)
        assertEquals(10, compressed.foldedSteps)
        val summary = compressed.messages.firstNotNullOf {
            it.plainText().takeIf { t -> t.startsWith(ContextCompressor.HEADER) }
        }
        assertTrue(summary, summary.contains("- Step 1 read_file → applied"))
        assertTrue(summary, summary.contains("- Step 1 web_search(kotlin) → applied"))
    }

    @Test
    fun pinnedVerbAnywhereInMultiCallRoundKeepsEntireRound() {
        val pinnedRound = multiRound(
            calls = listOf(
                Triple("p1", "phone_tap", """{"target":"登录"}"""),
                Triple("p2", "hand_to_user", """{"reason":"输入密码"}"""),
            ),
            results = mapOf(
                "p1" to """{"ok":true,"changed":true}""",
                "p2" to """{"outcome":"complete","ok":true}""",
            ),
        )
        val h = listOf(Message(Role.User, listOf(Part.Text("登录")), Author.User)) +
            taps(1, 2) +
            pinnedRound +
            taps(3, 8)

        val compressed = ContextCompressor(TOOLS).compress(h, contextTokens = 100)
        val allCalls = compressed.messages.flatMap { it.parts }.filterIsInstance<Part.ToolCall>().map { it.id }
        assertTrue("expected both calls of pinned round kept: $allCalls", "p1" in allCalls && "p2" in allCalls)
        val allResults = compressed.messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.callId }.toSet()
        assertEquals(allCalls.toSet(), allResults)
    }
}
