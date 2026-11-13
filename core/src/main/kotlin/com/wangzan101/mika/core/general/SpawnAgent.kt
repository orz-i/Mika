package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.child.ChildPhase
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.policy.RunProfile
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.SubagentRole
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.bool
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.tool.strArray
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SpawnAgent : AgentTool {
    /** 子任务默认的步数、步数上限。 */
    const val DEFAULT_STEPS = 15
    const val MAX_STEPS = 40
    /** 子任务结果默认留多少字、最多留多少字、最少留多少字。 */
    const val DEFAULT_RESULT_CHARS = 4_000
    const val MAX_RESULT_CHARS = 8_000
    const val MIN_RESULT_CHARS = 500

    override val verb = Verb.SpawnAgent
    override val risk = Risk.Write
    override val offScreen = true
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("spawn_agent", Prompts.tool("spawn_agent",
        "steps" to DEFAULT_STEPS, "maxSteps" to MAX_STEPS, "chars" to DEFAULT_RESULT_CHARS, "maxChars" to MAX_RESULT_CHARS), mapOf(
        "goal" to str("A bounded research question for the child agent, using web and sandbox reads only."),
        "role" to str("Optional subagent role preset.", SubagentRole.IDS),
        "capabilities" to strArray("Optional subset of parent capabilities to grant (web, sandbox, mcp).", max = 8),
        "readOnly" to bool("Optional read-only override (defaults to role preset; cannot be false if parent is read-only)."),
        "workspaceScope" to str("Optional relative subdirectory under /workspace to isolate child sandbox writes."),
        "format" to str("Optional output format.", listOf("text", "json")),
        "maxSteps" to int("Optional step budget for the child, 1-$MAX_STEPS (default $DEFAULT_STEPS); larger values are lowered to $MAX_STEPS.", 1),
        "maxResultChars" to int("Optional length limit of the returned result in characters, $MIN_RESULT_CHARS-$MAX_RESULT_CHARS (default $DEFAULT_RESULT_CHARS); values outside are moved into range.", 1),
    ), listOf("goal"))

    override fun available(ctx: ToolContext) = ctx.config.childHost != null && ctx.config.runSpec != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val parent = ctx.config.runSpec ?: error("no parent RunSpec")
        val host = ctx.config.childHost ?: error("no child host")
        val goal = a.str("goal", max = 4000)!!
        val rawRole = a.str("role", max = 40)
        val rolePreset = if (rawRole == null) {
            SubagentRole.Researcher
        } else {
            SubagentRole.fromId(rawRole) ?: throw ArgError("role", "must be one of ${SubagentRole.IDS.joinToString()}")
        }
        val requestedCaps = a.strList("capabilities", 1, 8, itemMax = 80)?.map { it.trim() }?.toSet()
        if (requestedCaps != null) {
            val invalid = requestedCaps.firstOrNull { !RunSpec.isDelegatableCapability(it) }
            if (invalid != null) {
                throw ArgError("capabilities", "capability '$invalid' cannot be delegated to a subagent; allowed: web, sandbox, mcp (or mcp:<serverId>)")
            }
        }
        val attenuatedCaps = RunSpec.attenuateCapabilities(
            requested = requestedCaps ?: rolePreset.defaultCapabilities,
            parentCapabilities = parent.capabilities,
        )
        val requestedReadOnly = a.bool("readOnly", rolePreset.defaultReadOnly)
        val effectiveReadOnly = parent.readOnly || requestedReadOnly
        val rawScope = a.str("workspaceScope", max = 200)
        if (rawScope != null && !RunSpec.isValidWorkspaceScope(rawScope)) {
            throw ArgError("workspaceScope", "must be a safe relative subdirectory under /workspace without '..'")
        }
        val childId = RunId(UUID.randomUUID().toString())
        val effectiveScope = rawScope?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
            ?: if (!effectiveReadOnly && RunSpec.isCapabilityAllowed("sandbox", attenuatedCaps)) {
                "subagents/${childId.value}"
            } else {
                null
            }
        val childProfile = RunProfile.resolveForChild(
            parentProfile = parent.profile,
            capabilities = attenuatedCaps,
            role = rolePreset.id,
            readOnly = effectiveReadOnly,
        )
        val format = a.str("format")
        // 只校验是整数;超出范围的拉回范围内,不当错误(子任务照样能派)
        val steps = (a.int("maxSteps", Int.MIN_VALUE, Int.MAX_VALUE) ?: DEFAULT_STEPS).coerceIn(1, MAX_STEPS)
        val limit = (a.int("maxResultChars", Int.MIN_VALUE, Int.MAX_VALUE) ?: DEFAULT_RESULT_CHARS).coerceIn(MIN_RESULT_CHARS, MAX_RESULT_CHARS)
        val child = RunSpec(
            id = childId,
            goal = goal,
            threadId = parent.threadId,
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended),
            capabilities = attenuatedCaps,
            readOnly = effectiveReadOnly,
            parent = parent.id,
            stepBudget = steps,
            profile = childProfile,
            role = rolePreset.id,
            workspaceScope = effectiveScope,
            parentCapabilities = parent.capabilities,
            parentReadOnly = parent.readOnly,
        )
        val result = host.spawn(child, format)
        val text = result.text.take(limit)
        return ToolExecution(buildJsonObject {
            put("ok", result.phase == ChildPhase.Completed)
            put("childRunId", result.id.value)
            put("status", result.phase.name.lowercase())
            put("role", rolePreset.id)
            put("readOnly", effectiveReadOnly)
            effectiveScope?.let { put("workspaceScope", it) }
            put("maxSteps", steps)
            put("result", Untrusted.wrap(text))
            if (result.text.length > limit) {
                put("truncated", true)
                put("fullLength", result.text.length)
            }
        }, observations = listOf(Observation(ObsKind.Reply, text, source = "child", trust = Trust.Untrusted)))
    }
}
