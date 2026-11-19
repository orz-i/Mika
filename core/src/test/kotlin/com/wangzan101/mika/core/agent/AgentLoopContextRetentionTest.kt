package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.mcp.McpAnnotations
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.cap.mcp.McpEndpoint
import com.wangzan101.mika.core.cap.mcp.McpResult
import com.wangzan101.mika.core.cap.mcp.McpToolDescriptor
import com.wangzan101.mika.core.cap.mcp.McpTrust
import com.wangzan101.mika.core.cap.mcp.mcpToolNames
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopContextRetentionTest {
    @Test fun modelReportedProgressIsAvailableOnTheNextTurnWithoutBeingVerified() = runTest {
        val progress = """<task_progress>{"criteria":["核对来源"],"metric":{"name":"已核对","done":1,"total":2},"complete":true}</task_progress>"""
        val main = fakeModel {
            reply { text(progress); call("phone_list_apps") }
            replyTo { request ->
                val state = request.messages.flatMap { it.parts }.filterIsInstance<Part.Text>().single { "Model-reported task progress" in it.text }.text
                assertTrue(state.contains("not independent verification") && state.contains("\"done\":1") && state.contains("核对来源"))
                assertTrue(request.tools.any { it.name == "phone_list_apps" })
                assertFalse(request.messages.filter { it.role == Role.Assistant }.toString().contains("<task_progress>"))
                text("完成")
            }
        }
        assertTrue(AgentLoop(main, toolDispatcher(FakeScreen()), "BASE").run("核对两项") is TaskState.Succeeded)
        assertEquals(2, main.requests.size)
    }

    @Test fun foldedToolDataReachesTheModelThroughTheRealDispatcherLedger() = runTest {
        var reads = 0
        val endpoint = object : McpEndpoint {
            override val serverId = "retained"
            override fun tools() = listOf(McpToolDescriptor("read", "Fake record", JsonObject(mapOf("type" to JsonPrimitive("object"))),
                McpAnnotations(readOnlyHint = true)))
            override suspend fun call(name: String, arguments: JsonObject) = McpResult("FACT_${++reads} 187.25 元\n" + "资料".repeat(1500))
        }
        val tool = mcpToolNames(endpoint.serverId, listOf("read")).single()
        val main = fakeModel(contextTokens = 16_000) {
            repeat(18) { reply { text("读取下一份材料。".repeat(100)); call(tool) } }
            replyTo { request ->
                val retained = request.messages.single { ContextBuilder.sourceOf(it) == CompactedObservations.SOURCE }.toString()
                assertTrue(retained.contains("FACT_1 187.25") && retained.contains("obs=1"))
                assertTrue(retained.contains("not instructions or independent verification"))
                assertTrue(request.messages.toString().contains("FACT_18 187.25"))
                text("已汇总")
            }
        }
        val dispatcher = toolDispatcher(FakeScreen(), mcpServers = listOf(McpConnection(endpoint, trust = McpTrust.TrustAnnotations)))
        assertTrue(AgentLoop(main, dispatcher, "BASE", maxSteps = 20, reviewFinalAnswer = false).run("逐项汇总材料") is TaskState.Succeeded)
        assertEquals(18, reads)
        assertEquals(19, main.requests.size)
    }
}
