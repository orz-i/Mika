package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.SubActions
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolInfo
import com.wangzan101.mika.core.tool.Verb
import com.agent.use.ui.ScreenSnapshot
import com.wangzan101.mika.core.uistate.interfaces
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 快捷流程执行器与自动寻路器共用的 `Origin.Replay` 安全执行桥接层：
 * 统一封装读屏、受保护界面识别、`ActionExecutor`（`Origin.Replay`）子步执行、
 * 不可逆/支付拦截与守卫告警即时中止语义，避免在多处重复编写同一套守卫逻辑。
 */
internal interface AtlasReplayBridge {
    suspend fun gate()
    suspend fun readScreen(): ScreenSnapshot?
    suspend fun peekScreen(): ScreenSnapshot?
    fun toolInfo(name: String): ToolInfo?
    fun linkAllowed(action: String?, uri: String?, pkg: String?): Boolean
    suspend fun executeReplay(call: Part.ToolCall): StepExecOutcome

    sealed interface StepExecOutcome {
        data class Ran(
            val content: JsonObject,
            val seen: ScreenSnapshot?,
            val effective: Boolean,
        ) : StepExecOutcome

        data class StoppedForConfirmation(val code: String, val detail: String?) : StepExecOutcome
        data class GuardBlocked(val code: String) : StepExecOutcome
        data class Refused(val code: String) : StepExecOutcome
        data object Cancelled : StepExecOutcome
    }

    companion object {
        val OPENS_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink)
        val FORBIDDEN_VERBS = setOf(Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser)
        val NOT_EXECUTED_ERRORS = setOf(
            "invalid_arguments", "unknown_tool", "invalid_link", "link_unresolved", "ref_expired",
            "app_in_use_by_other_task", "app_wait_would_deadlock", "app_wait_timed_out",
            "app_in_use_by_user", "app_not_found", "no_app_open", "confirmation_required", "batch_stopped",
            "skipped_for_new_message",
        )
        const val PROTECTED_SEEN = "a protected page"

        fun summary(s: ScreenSnapshot): String =
            if (s.protected) PROTECTED_SEEN
            else "${s.packageName ?: "unknown app"}: " +
                s.elements.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().take(8).joinToString(", ")

        fun effectiveWrite(verb: Verb, content: JsonObject): Boolean {
            if (content["ok"] != JsonPrimitive(true)) return false
            return when (verb) {
                Verb.TypeText, Verb.ClearText -> content["verification"]?.let { it != JsonPrimitive("mismatch") } ?: true
                else -> content["changed"] != JsonPrimitive(false)
            }
        }

        /** 直接通过 [ActionExecutor] 以 [Origin.Replay] 执行（供单独单测或循环直接调用）。 */
        fun forExecutor(
            tools: ToolDispatcher,
            executor: ActionExecutor,
            gateFn: suspend () -> Unit = {},
        ): AtlasReplayBridge = object : AtlasReplayBridge {
            private var seq = 0
            override suspend fun gate() = gateFn()
            override suspend fun readScreen(): ScreenSnapshot? = try {
                val dump = tools.index.nameOf(Verb.ListElements, tools.surface) ?: return null
                val r = tools.executeRead(Part.ToolCall("atlas-read-${++seq}", dump, "{}"), 0)
                val sid = (r.content["snapshotId"] as? JsonPrimitive)?.contentOrNull ?: return null
                tools.interfaces.snapshotFor(tools.declared(dump), JsonObject(mapOf("snapshotId" to JsonPrimitive(sid))))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            override suspend fun peekScreen(): ScreenSnapshot? = try {
                tools.interfaces.peek()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            override fun toolInfo(name: String): ToolInfo? = tools.index[name]
            override fun linkAllowed(action: String?, uri: String?, pkg: String?): Boolean = tools.linkAllowed(action, uri, pkg)

            override suspend fun executeReplay(call: Part.ToolCall): StepExecOutcome {
                val verb = tools.verb(call.name) ?: Verb.Tap
                return when (val out = executor.run(call, Origin.Replay)) {
                    is ActionOutcome.Ran -> StepExecOutcome.Ran(
                        content = out.exec.content,
                        seen = out.seen ?: peekScreen(),
                        effective = effectiveWrite(verb, out.exec.content),
                    )
                    is ActionOutcome.Refused -> mapRefusal(out.code, out.hit?.matched)
                    is ActionOutcome.Cancelled -> StepExecOutcome.Cancelled
                }
            }
        }

        /** 通过 `PhoneTool` 内部持有的 [SubActions] 执行（外层由 `ActionExecutor` 统一配为 `Origin.Replay`）。 */
        fun forSubActions(
            sub: SubActions,
            infoLookup: (String) -> ToolInfo?,
            readSnapshotFn: suspend () -> ScreenSnapshot?,
            peekSnapshotFn: suspend () -> ScreenSnapshot?,
            linkAllowedFn: (String?, String?, String?) -> Boolean = { _, _, _ -> true },
        ): AtlasReplayBridge = object : AtlasReplayBridge {
            override suspend fun gate() {}
            override suspend fun readScreen(): ScreenSnapshot? = readSnapshotFn()
            override suspend fun peekScreen(): ScreenSnapshot? = peekSnapshotFn()
            override fun toolInfo(name: String): ToolInfo? = infoLookup(name)
            override fun linkAllowed(action: String?, uri: String?, pkg: String?): Boolean = linkAllowedFn(action, uri, pkg)

            override suspend fun executeReplay(call: Part.ToolCall): StepExecOutcome {
                val verb = infoLookup(call.name)?.verb ?: Verb.Tap
                val exec = sub.run(call)
                val ok = exec.content["ok"] == JsonPrimitive(true)
                val err = (exec.content["error"] as? JsonPrimitive)?.contentOrNull
                val executed = exec.content["executed"] != JsonPrimitive(false)
                if (!ok && !executed && err != null) {
                    return when (err) {
                        "confirmation_required", "confirmation_mismatch" ->
                            StepExecOutcome.StoppedForConfirmation(err, (exec.content["matched"] as? JsonPrimitive)?.contentOrNull)
                        "guard_stopped", "guard_warning" -> StepExecOutcome.GuardBlocked(err)
                        "cancelled" -> StepExecOutcome.Cancelled
                        "approval_unavailable", "target_unresolved", "own_app" -> StepExecOutcome.Refused(err)
                        else -> StepExecOutcome.Ran(exec.content, peekScreen(), effective = false)
                    }
                }
                return StepExecOutcome.Ran(
                    content = exec.content,
                    seen = peekScreen(),
                    effective = effectiveWrite(verb, exec.content),
                )
            }
        }

        private fun mapRefusal(code: String, matched: String?): StepExecOutcome = when (code) {
            "confirmation_required", "confirmation_mismatch" -> StepExecOutcome.StoppedForConfirmation(code, matched)
            "guard_stopped", "guard_warning" -> StepExecOutcome.GuardBlocked(code)
            else -> StepExecOutcome.Refused(code)
        }
    }
}
