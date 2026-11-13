package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.policy.RunProfile

@JvmInline value class RunId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = value
}

/**
 * 轻量级子代理角色预设：
 * - [Researcher]：默认只读调研（web + sandbox 只读）；
 * - [Coder]：允许在隔离工作区或路径范围内读写 sandbox（及只读 web/mcp）；
 * - [Reviewer]：代码与产物只读审查（sandbox + web 只读）。
 */
enum class SubagentRole(val id: String, val defaultCapabilities: Set<String>, val defaultReadOnly: Boolean) {
    Researcher("researcher", setOf("web", "sandbox"), true),
    Coder("coder", setOf("sandbox", "web"), false),
    Reviewer("reviewer", setOf("sandbox", "web"), true);

    companion object {
        val IDS: List<String> = entries.map { it.id }
        fun fromId(raw: String?): SubagentRole? =
            if (raw == null) null else entries.firstOrNull { it.id.equals(raw.trim(), ignoreCase = true) }
    }
}

/** 一次运行的不可变开局信息。后续步骤会补能力、资源预算与审批快照。 */
data class RunSpec(
    val id: RunId,
    val goal: String,
    val threadId: String,
    val priorTurns: List<Turn> = emptyList(),
    val provenance: RunProvenance = RunProvenance(),
    /** null 表示默认能力；子 Run 可给严格子集（受父 Run 能力衰减约束）。 */
    val capabilities: Set<String>? = null,
    /** 条件检查或只读 Run 只允许读取；网页导航作为取得读取对象的准备动作例外。 */
    val readOnly: Boolean = false,
    val parent: RunId? = null,
    val stepBudget: Int? = null,
    /** 主循环、旁路调用和子任务共享的已报告 Token 预算；null 不限制。 */
    val tokenBudget: Long? = null,
    /** 运行场景画像与执行策略（默认 [RunProfile.GENERAL]）。 */
    val profile: RunProfile = RunProfile.GENERAL,
    /** 子代理角色预设标识（如 researcher / coder / reviewer）。 */
    val role: String? = null,
    /** 子代理独立工作区或相对 `/workspace/` 的路径隔离范围（防止并发子代理写冲突）。 */
    val workspaceScope: String? = null,
    /** 父 Run 的能力集合快照（用于强制校验 `child.capabilities ⊆ parent.capabilities`；null 表示父拥有默认全集）。 */
    val parentCapabilities: Set<String>? = null,
    /** 父 Run 是否只读（用于强制校验权限不放大 `child.readOnly >= parent.readOnly`）。 */
    val parentReadOnly: Boolean = false,
) {
    init {
        require(goal.isNotBlank() && threadId.isNotBlank())
        require(tokenBudget == null || tokenBudget > 0)
        require(workspaceScope == null || isValidWorkspaceScope(workspaceScope)) {
            "invalid workspaceScope: $workspaceScope"
        }
    }

    /** 将基础工作区标识与 [workspaceScope] 组合为隔离的子工作区路径。 */
    fun scopedWorkspace(baseWorkspace: String): String {
        val scope = normalizedWorkspaceScope() ?: return baseWorkspace
        return "$baseWorkspace/$scope"
    }

    /** 规范化后的相对工作区范围（不含首尾 `/`），无隔离范围时返回 null。 */
    fun normalizedWorkspaceScope(): String? =
        workspaceScope?.trim()?.replace('\\', '/')?.trim('/')?.takeIf { it.isNotEmpty() }

    companion object {
        /** 允许下放给子代理的能力族（手机 UI 与 root 绝不下放给后台子代理）。 */
        val DELEGATABLE_CAPABILITIES = setOf("web", "sandbox", "mcp")

        fun isDelegatableCapability(id: String): Boolean =
            id in DELEGATABLE_CAPABILITIES || id.startsWith("mcp:")

        /** 校验一个能力 ID 是否包含在允许集合中（支持 `"mcp"` 通配全部 `"mcp:<serverId>"`）。 */
        fun isCapabilityAllowed(id: String, allowed: Set<String>?): Boolean {
            if (allowed == null) return true
            return id in allowed || (id.startsWith("mcp:") && "mcp" in allowed)
        }

        /**
         * 计算 `requested` 相对父能力集合 `parentCapabilities` 的衰减交集（`child.capabilities ⊆ parent.capabilities`）。
         */
        fun attenuateCapabilities(requested: Set<String>, parentCapabilities: Set<String>?): Set<String> {
            val delegatable = requested.filter(::isDelegatableCapability).toSet()
            if (parentCapabilities == null) return delegatable
            val out = linkedSetOf<String>()
            for (req in delegatable) {
                if (req in parentCapabilities || (req.startsWith("mcp:") && "mcp" in parentCapabilities)) {
                    out += req
                } else if (req == "mcp") {
                    val parentMcp = parentCapabilities.filter { it == "mcp" || it.startsWith("mcp:") }
                    out.addAll(parentMcp)
                }
            }
            return out
        }

        fun isValidWorkspaceScope(raw: String): Boolean {
            val cleaned = raw.trim().replace('\\', '/').trim('/')
            if (cleaned.isEmpty()) return false
            val segments = cleaned.split('/')
            return segments.all { seg ->
                seg.isNotEmpty() && seg != "." && seg != ".." && seg.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' }
            }
        }
    }
}

/** 从本次对话送给运行的输入。取消走控制通道,不会等在插话队列里。 */
sealed interface RunInput {
    /**
     * 插话是补充:撤掉还在等的模型请求,接在已有历史后面重问(做过的一概保留);正在执行的工具照常做完、结果照记,
     * 撤掉也不丢东西的等待提前结束(见 [waitOrSteer])。
     */
    data class Steer(val messageId: String, val text: String) : RunInput
    /** 和 [Steer] 走同一条路(旧入口的名字)。 */
    data class Redirect(val messageId: String, val text: String) : RunInput
    data class Answer(val text: String, val askId: String? = null) : RunInput
    data class Confirm(val choice: ConfirmChoice, val askId: String? = null) : RunInput
    data class HandBack(val result: com.wangzan101.mika.core.tool.HandBack, val askId: String? = null) : RunInput
    data object Cancel : RunInput
}

enum class InputReceipt { Accepted, NoPendingInteraction, NextTask, Closed }

sealed interface RunPhase {
    data object Queued : RunPhase
    data object Preparing : RunPhase
    data object Running : RunPhase
    data class WaitingUser(val askId: String? = null) : RunPhase
    data class Parked(val askId: String) : RunPhase
    data class Paused(val reason: String) : RunPhase
    data object Finishing : RunPhase
    data object Aftercare : RunPhase
    data object Ended : RunPhase
    data object Interrupted : RunPhase
}
