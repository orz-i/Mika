package com.wangzan101.mika.core.assembly

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.agent.CoreSections
import com.wangzan101.mika.core.general.GeneralGroup
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolConfig
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.Interfaces
import com.wangzan101.mika.core.uistate.ScreenFamily
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.Surface
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 每个 Run 在开局由 Provider 打开能力；Provider 捕获自己的端口与资源，不进入核心循环。 */
internal interface CapabilityProvider {
    val id: String
    fun open(spec: RunSpec): Capability?
}

/**
 * 为具有 [workspaceScope] 的子代理包装沙箱写工具（如 `write_file`、`run_command`、`share_file`），
 * 强制校验其目标路径或工作目录位于 `/workspace/<workspaceScope>` 之内，防止并发子代理互相覆盖写文件。
 */
internal class ScopedSandboxTool(
    private val delegate: AgentTool,
    val workspaceScope: String,
) : AgentTool by delegate {
    private val scopePrefix = "/workspace/$workspaceScope"

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val rawPath = if ("path" in a.tool.allowed) a.str("path", max = 1000) else null
        if (rawPath != null) {
            val resolved = resolveWorkspacePath(rawPath)
                ?: return fail(
                    "workspace_scope_violation",
                    "Path '$rawPath' escapes /workspace; subagent writes must stay within $scopePrefix.",
                    recoverable = true,
                )
            if (!isInsideScope(resolved)) {
                return fail(
                    "workspace_scope_violation",
                    "Subagent write to '$rawPath' is outside isolated workspace scope '$scopePrefix'. Write under '$scopePrefix/...' or '$workspaceScope/...'.",
                    recoverable = true,
                )
            }
        }
        val rawCwd = if ("cwd" in a.tool.allowed) a.str("cwd", max = 500) else null
        if (rawCwd != null) {
            val resolvedCwd = resolveWorkspacePath(rawCwd)
                ?: return fail(
                    "workspace_scope_violation",
                    "Working directory '$rawCwd' escapes /workspace; subagent commands must stay within $scopePrefix.",
                    recoverable = true,
                )
            if (!isInsideScope(resolvedCwd)) {
                return fail(
                    "workspace_scope_violation",
                    "Subagent cwd '$rawCwd' is outside isolated workspace scope '$scopePrefix'. Use cwd '$workspaceScope' (or a subdirectory under it).",
                    recoverable = true,
                )
            }
            return delegate.execute(a, ctx)
        }
        val effectiveArgs = if ("cwd" in a.tool.allowed) {
            Args(a.tool, JsonObject(a.o + ("cwd" to JsonPrimitive(workspaceScope))))
        } else {
            a
        }
        return delegate.execute(effectiveArgs, ctx)
    }

    private fun isInsideScope(resolved: String): Boolean =
        resolved == scopePrefix || resolved.startsWith("$scopePrefix/")

    companion object {
        internal fun resolveWorkspacePath(raw: String): String? {
            val cleaned = raw.trim().replace('\\', '/')
            if (cleaned.isEmpty()) return null
            val relative = if (cleaned.startsWith("/")) {
                if (cleaned == "/workspace") ""
                else if (cleaned.startsWith("/workspace/")) cleaned.removePrefix("/workspace/")
                else return null
            } else {
                cleaned
            }
            val stack = mutableListOf<String>()
            for (seg in relative.split('/')) {
                if (seg.isEmpty() || seg == ".") continue
                if (seg == "..") {
                    if (stack.isEmpty()) return null
                    stack.removeAt(stack.lastIndex)
                } else {
                    stack += seg
                }
            }
            return if (stack.isEmpty()) "/workspace" else "/workspace/" + stack.joinToString("/")
        }
    }
}

internal class RunAssembler(private val providers: List<CapabilityProvider>) {
    fun assemble(spec: RunSpec, config: ToolConfig, stableRefs: Boolean,
        formerPrefix: Map<String, String>, renamed: Map<String, String>,
        retired: Map<String, String>, moved: Map<String, List<String>>): ToolDispatcher {
        check(providers.map { it.id }.distinct().size == providers.size) { "duplicate capability provider ids" }
        val child = spec.provenance.origin == RunOrigin.Child
        // 权限衰减模型:子代理仅当显式声明能力集合且父 Run 与子 Run 均非只读时才可开放受控写工具
        val effectiveReadOnly = if (child) {
            spec.readOnly || spec.parentReadOnly || spec.capabilities == null
        } else {
            spec.readOnly
        }
        val allowedChildCaps = if (child) {
            RunSpec.attenuateCapabilities(
                requested = spec.capabilities ?: setOf("web", "sandbox"),
                parentCapabilities = spec.parentCapabilities,
            )
        } else {
            null
        }
        val capabilities = providers.filter { provider ->
            if (child) {
                RunSpec.isCapabilityAllowed(provider.id, allowedChildCaps) && RunSpec.isDelegatableCapability(provider.id)
            } else {
                spec.capabilities == null || RunSpec.isCapabilityAllowed(provider.id, spec.capabilities)
            }
        }.mapNotNull { it.open(spec) }
        val general = GeneralGroup.tools.filterNot { (effectiveReadOnly || child) && it.verb == Verb.MemoryEdit }
            .filterNot { spec.provenance.attendance == Attendance.Unattended &&
            it.verb in setOf(Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser, Verb.ScheduleTask, Verb.ProfileEdit, Verb.SkillSave, Verb.MemoryEdit) }
            .filterNot { child && it.verb in setOf(Verb.SpawnAgent, Verb.MemorySave, Verb.MemoryEdit, Verb.ProfileEdit, Verb.ScheduleTask, Verb.SkillSave,
                Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser) }
            .filter { !(effectiveReadOnly || child) || it.risk == Risk.Read }
        val workspaceScope = if (child) {
            spec.normalizedWorkspaceScope() ?: if (!effectiveReadOnly) "subagents/${spec.id.value}" else null
        } else {
            spec.normalizedWorkspaceScope()
        }
        val capabilityTools = capabilities.flatMap { it.tools() }
            .filter { tool ->
                if (!child) {
                    !effectiveReadOnly || tool.risk == Risk.Read || (tool.capability == "web" && tool.verb == Verb.Navigate)
                } else if (effectiveReadOnly) {
                    tool.risk == Risk.Read || (tool.capability == "web" && tool.verb == Verb.Navigate)
                } else {
                    when {
                        tool.capability == "sandbox" -> true
                        tool.capability == "web" -> tool.risk == Risk.Read || tool.verb == Verb.Navigate
                        tool.capability == "mcp" || tool.capability?.startsWith("mcp:") == true -> tool.risk != Risk.External
                        else -> tool.risk == Risk.Read
                    }
                }
            }
            .map { tool ->
                if (workspaceScope != null && tool.capability == "sandbox" && tool.risk != Risk.Read) {
                    ScopedSandboxTool(tool, workspaceScope)
                } else {
                    tool
                }
            }
        val surfaces = capabilities.mapNotNull { it.surface }.ifEmpty { listOf(HEADLESS_SURFACE) }
        return ToolDispatcher(capabilities, general + capabilityTools,
            listOf(CoreSections) + capabilities, config, { ctx -> Interfaces.attach(ctx, surfaces, stableRefs) },
            formerPrefix, renamed, retired, moved)
    }

    private companion object {
        val HEADLESS_PORT = object : ScreenPort {
            override suspend fun snapshot() = ScreenSnapshot("headless0", null, emptyList(), 0, 0)
            override suspend fun screenshotPng(): ByteArray? = null
            override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Failed("no_ui_surface")
            override suspend fun tapXY(x: Int, y: Int) = ActionResult.Failed("no_ui_surface")
            override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Failed("no_ui_surface")
            override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Failed("no_ui_surface")
            override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Failed("no_ui_surface")
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Failed("no_ui_surface")
            override suspend fun back() = ActionResult.Failed("no_ui_surface")
            override suspend fun pressKey(keyCode: Int) = ActionResult.Failed("no_ui_surface")
            override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Failed("no_ui_surface")
            override suspend fun waitStable(timeoutMs: Long) = false
            override suspend fun listApps() = emptyList<Pair<String, String>>()
        }

        val HEADLESS_SURFACE = Surface(
            family = ScreenFamily(id = "_headless", snapshotPrefix = "_h"),
            screen = HEADLESS_PORT,
            declaredTarget = { it },
            targetNote = "none",
        )
    }
}
