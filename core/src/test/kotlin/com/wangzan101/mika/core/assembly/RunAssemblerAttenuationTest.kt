package com.wangzan101.mika.core.assembly

import com.wangzan101.mika.core.cap.mcp.McpAnnotations
import com.wangzan101.mika.core.cap.mcp.McpConnection
import com.wangzan101.mika.core.cap.mcp.McpEndpoint
import com.wangzan101.mika.core.cap.mcp.McpResult
import com.wangzan101.mika.core.cap.mcp.McpToolDescriptor
import com.wangzan101.mika.core.cap.mcp.McpTrust
import com.wangzan101.mika.core.cap.root.RootPort
import com.wangzan101.mika.core.cap.root.RootRun
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunAssemblerAttenuationTest {
    private class FakeMcpEndpoint(override val serverId: String) : McpEndpoint {
        override fun tools() = listOf(
            McpToolDescriptor("read_doc", "Read doc", buildJsonObject { put("type", "object") }, McpAnnotations(readOnlyHint = true)),
            McpToolDescriptor("edit_doc", "Edit doc", buildJsonObject { put("type", "object") }, McpAnnotations(readOnlyHint = false, openWorldHint = false)),
            McpToolDescriptor("send_email", "Send email", buildJsonObject { put("type", "object") }, McpAnnotations(readOnlyHint = false, openWorldHint = true)),
        )

        override suspend fun call(name: String, arguments: JsonObject) = McpResult("called:$name")
    }

    @Test fun childCapabilitiesAreAttenuatedByParentAndNeverEscalateToPhoneOrRoot() {
        val childSpec = RunSpec(
            id = RunId("child-1"),
            goal = "work",
            threadId = "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended),
            capabilities = setOf("phone", "root", "sandbox", "mcp:docs", "mcp:slack"),
            parentCapabilities = setOf("web", "sandbox", "mcp:docs"),
            readOnly = false,
            parent = RunId("parent-1"),
            workspaceScope = "subagents/child-1",
        )
        val tools = toolDispatcher(
            screen = FakeScreen(),
            web = FakeWebPort(),
            sandbox = FakeSandbox(),
            root = object : RootPort { override suspend fun run(command: String, timeoutMs: Long) = RootRun.Unavailable },
            mcpServers = listOf(
                McpConnection(FakeMcpEndpoint("docs"), trust = McpTrust.TrustAnnotations),
                McpConnection(FakeMcpEndpoint("slack"), trust = McpTrust.TrustAnnotations),
            ),
            runSpec = childSpec,
        )
        val names = tools.definitions().map { it.name }.toSet()
        // Never escalate to phone or root
        assertFalse(names.any { it.startsWith("phone_") || it == "root_run_command" })
        // Parent lacked mcp:slack and child didn't request web
        assertFalse(names.any { it.startsWith("mcp_slack__") || it.startsWith("web_") })
        // Allowed writable sandbox + non-external mcp:docs tools are present; external send_email is filtered out
        assertTrue("write_file" in names)
        assertTrue("run_command" in names)
        assertTrue("mcp_docs__read_doc" in names)
        assertTrue("mcp_docs__edit_doc" in names)
        assertFalse("mcp_docs__send_email" in names)
    }

    @Test fun parentReadOnlyPreventsChildFromEnablingWriteTools() {
        val childSpec = RunSpec(
            id = RunId("child-ro"),
            goal = "cannot write",
            threadId = "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended),
            capabilities = setOf("sandbox", "web", "mcp:docs"),
            parentCapabilities = setOf("sandbox", "web", "mcp:docs"),
            readOnly = false,
            parentReadOnly = true,
            parent = RunId("parent-ro"),
        )
        val tools = toolDispatcher(
            screen = FakeScreen(),
            web = FakeWebPort(),
            sandbox = FakeSandbox(),
            mcpServers = listOf(McpConnection(FakeMcpEndpoint("docs"), trust = McpTrust.TrustAnnotations)),
            runSpec = childSpec,
        )
        val names = tools.definitions().map { it.name }.toSet()
        assertTrue("read_file" in names)
        assertTrue("web_read_page" in names)
        assertTrue("mcp_docs__read_doc" in names)
        assertFalse("write_file" in names)
        assertFalse("run_command" in names)
        assertFalse("mcp_docs__edit_doc" in names)
    }

    @Test fun workspaceScopeIsolatesSandboxCommandsAndBlocksOutsideWrites() = runBlocking {
        val sandbox = FakeSandbox().apply {
            next = { r -> CommandResult(0, false, false, 5, "cwd=${r.cwd}", 0, false) }
        }
        val childSpec = RunSpec(
            id = RunId("child-ws"),
            goal = "scoped edits",
            threadId = "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended),
            capabilities = setOf("sandbox"),
            readOnly = false,
            parent = RunId("parent-ws"),
            workspaceScope = "subagents/worker-a",
        )
        val tools = toolDispatcher(FakeScreen(), sandbox = sandbox, runSpec = childSpec)

        // Writing outside scope is rejected cleanly without touching sandbox
        val outsideWrite = tools.execute(
            Part.ToolCall("w1", "write_file", """{"path":"/workspace/shared.txt","content":"hijack"}"""),
            ActionPass(),
        )
        assertEquals("false", outsideWrite.content["ok"].toString())
        assertEquals("workspace_scope_violation", outsideWrite.content["error"]!!.jsonPrimitive.content)

        // Traversal escaping scope is also rejected
        val traversalWrite = tools.execute(
            Part.ToolCall("w2", "write_file", """{"path":"/workspace/subagents/worker-a/../worker-b/pwn.txt","content":"hijack"}"""),
            ActionPass(),
        )
        assertEquals("workspace_scope_violation", traversalWrite.content["error"]!!.jsonPrimitive.content)

        // run_command with outside cwd is rejected; default cwd is automatically pinned to workspaceScope
        val outsideCwd = tools.execute(
            Part.ToolCall("c1", "run_command", """{"command":"ls","cwd":"subagents/worker-b"}"""),
            ActionPass(),
        )
        assertEquals("workspace_scope_violation", outsideCwd.content["error"]!!.jsonPrimitive.content)
        assertTrue(sandbox.runs.isEmpty())

        val defaultCwdCmd = tools.execute(
            Part.ToolCall("c2", "run_command", """{"command":"pwd"}"""),
            ActionPass(),
        )
        assertEquals("true", defaultCwdCmd.content["ok"].toString())
        assertEquals("subagents/worker-a", sandbox.runs.single().cwd)
    }
}
