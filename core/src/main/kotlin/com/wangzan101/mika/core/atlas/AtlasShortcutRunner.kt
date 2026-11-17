package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.atlas.AtlasReplayBridge.Companion.FORBIDDEN_VERBS
import com.wangzan101.mika.core.atlas.AtlasReplayBridge.Companion.NOT_EXECUTED_ERRORS
import com.wangzan101.mika.core.atlas.AtlasReplayBridge.Companion.OPENS_VERBS
import com.wangzan101.mika.core.atlas.AtlasReplayBridge.Companion.PROTECTED_SEEN
import com.wangzan101.mika.core.atlas.AtlasReplayBridge.Companion.summary
import com.wangzan101.mika.core.atlas.AtlasReplayBridge.StepExecOutcome
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.uistate.ScreenSig
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * L2 快捷流程安全执行器：
 * 通过 [ActionExecutor]（`Origin.Replay`）逐拍运行 [AtlasShortcut.steps]。
 * 每步均先读屏并校验包名、前屏锚点与选择器唯一性；遇到受保护界面、不可逆/支付操作、
 * 守卫告警或页面偏离时立即停步并安全交还模型。
 */
class AtlasShortcutRunner private constructor(
    private val bridge: AtlasReplayBridge,
    private val matchThreshold: Double = ScreenSig.DEFAULT_MATCH_THRESHOLD,
) {
    sealed interface Outcome {
        val done: Int
        val total: Int

        data class Completed(override val done: Int, override val total: Int) : Outcome

        data class StoppedForConfirmation(
            override val done: Int,
            override val total: Int,
            val stepTool: String,
            val reason: String,
        ) : Outcome

        data class Diverged(
            override val done: Int,
            override val total: Int,
            val reason: String,
            val seen: String,
            val executed: Boolean = false,
            val error: String? = null,
        ) : Outcome
    }

    suspend fun run(
        shortcut: AtlasShortcut,
        slots: List<String> = emptyList(),
        atlas: AppAtlas? = null,
        callIdPrefix: String = "shortcut",
    ): Outcome {
        val total = shortcut.steps.size
        if (shortcut.disabled) {
            return Outcome.Diverged(0, total, "shortcut is disabled", "", executed = false, error = "shortcut_disabled")
        }
        if (shortcut.steps.isEmpty()) {
            return Outcome.Diverged(0, total, "shortcut has no steps", "", executed = false, error = "empty_shortcut")
        }
        if (slots.size != shortcut.slots) {
            return Outcome.Diverged(0, total, "expected ${shortcut.slots} slot values, got ${slots.size}", "", executed = false, error = "slot_count_mismatch")
        }

        var seq = 0
        for ((i, step) in shortcut.steps.withIndex()) {
            fun diverged(why: String, seen: String, executed: Boolean = false, error: String? = null): Outcome =
                Outcome.Diverged(i, total, why, seen, executed, error)

            val info = bridge.toolInfo(step.tool)
            if (info == null || !info.routable || info.verb in FORBIDDEN_VERBS) {
                return diverged("step tool ${step.tool} is not safely replayable", "", error = "non_replayable_step")
            }

            bridge.gate()
            val snap = bridge.readScreen() ?: return diverged("the screen could not be read", "")
            if (snap.protected) return diverged("the page is protected", PROTECTED_SEEN, error = "protected_page")
            val seen = summary(snap)

            // 首步若带有已知入口屏幕结构特征，先核对当前屏是否匹配 entrySigId
            if (i == 0 && atlas != null) {
                val entryScreen = atlas.findScreen(shortcut.entrySigId)
                val curSig = snap.resolvedScreenSig()
                if (entryScreen != null && entryScreen.fingerprint != 0L && curSig != null && !curSig.isBlank) {
                    val matched = AtlasEviction.findMatchingScreenIndex(listOf(entryScreen), atlas.packageName, curSig, matchThreshold)
                    if (matched < 0) {
                        return diverged("entry screen does not match shortcut entry", seen, error = "entry_mismatch")
                    }
                }
            }

            step.before.packageName?.let { expectedPkg ->
                if (snap.packageName != expectedPkg) {
                    return diverged("the foreground app is not $expectedPkg", seen, error = "wrong_app")
                }
            }
            val names = snap.elements.map { it.name.trim() }.toSet()
            val need = minOf(2, step.before.texts.size)
            if (step.before.texts.count { it in names } < need) {
                return diverged("the page before the action does not match", seen, error = "anchor_mismatch")
            }

            val args = fillArgs(step, slots) ?: return diverged("the slots in the arguments cannot be filled", seen, error = "slot_fill_failed")
            val targetEl = step.target?.let { sel ->
                AtlasEviction.findMatchingElement(snap, sel, slots)
                    ?: return diverged("the target element is not unique or not found", seen, error = "target_not_found")
            }
            if (info.verb == Verb.Tap && targetEl == null) {
                return diverged("the tap has no target to check", seen, error = "target_required")
            }
            if (info.verb == Verb.OpenLink) {
                fun arg(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
                if (!bridge.linkAllowed(arg("action"), arg("uri"), arg("package"))) {
                    return diverged("the link is no longer in the reference material", seen, error = "link_disallowed")
                }
            }

            val callArgs = targetEl?.let { el ->
                JsonObject(args + mapOf("ref" to JsonPrimitive(el.ref), "snapshotId" to JsonPrimitive(snap.snapshotId)))
            } ?: args

            val out = bridge.executeReplay(Part.ToolCall("$callIdPrefix.${++seq}", step.tool, callArgs.toString()))
            val ran = when (out) {
                is StepExecOutcome.Ran -> out
                is StepExecOutcome.StoppedForConfirmation -> {
                    val reason = out.detail?.let { "${out.code}: $it" } ?: out.code
                    return Outcome.StoppedForConfirmation(i, total, step.tool, reason)
                }
                is StepExecOutcome.GuardBlocked ->
                    return diverged("the action guard raised a warning (${out.code})", seen, error = out.code)
                is StepExecOutcome.Refused ->
                    return diverged("action refused (${out.code})", seen, error = out.code)
                is StepExecOutcome.Cancelled ->
                    return diverged("cancelled", seen, error = "cancelled")
            }

            val fresh = ran.seen
            val nowSummary = fresh?.let(::summary) ?: seen
            if (ran.content["ok"] != JsonPrimitive(true)) {
                val err = (ran.content["error"] as? JsonPrimitive)?.contentOrNull
                val executed = err !in NOT_EXECUTED_ERRORS
                return diverged("the action failed" + err?.let { ": $it" }.orEmpty(), nowSummary, executed = executed, error = err)
            }
            if (fresh?.protected == true) {
                return diverged("landed on a protected page", PROTECTED_SEEN, executed = true, error = "protected_page")
            }

            val shown = fresh?.elements?.map { it.name.trim() }?.toSet().orEmpty()
            val landed = ran.content["changed"] == JsonPrimitive(true) ||
                ran.content["verification"] == JsonPrimitive("match") ||
                (ran.effective && info.verb in OPENS_VERBS) ||
                step.after.texts.any { it in shown }
            if (!landed) {
                return diverged("no expected change after the action", nowSummary, executed = true, error = "no_expected_change")
            }
        }
        return Outcome.Completed(total, total)
    }

    private fun fillArgs(step: RouteStep, slots: List<String>): JsonObject? {
        val obj = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (_: Exception) { null } ?: return null
        val out = mutableMapOf<String, JsonElement>()
        for ((k, v) in obj) {
            if (k == "ref" || k == "snapshotId" || k == "target") continue
            out[k] = when {
                v is JsonPrimitive && v.isString -> JsonPrimitive(AtlasEviction.fillSlots(v.content, slots) ?: return null)
                SLOT_REF.containsMatchIn(v.toString()) -> return null
                else -> v
            }
        }
        return JsonObject(out)
    }

    companion object {
        private val SLOT_REF = Regex("\\{(\\d+)\\}")

        fun forExecutor(
            tools: ToolDispatcher,
            executor: ActionExecutor,
            gate: suspend () -> Unit = {},
            matchThreshold: Double = ScreenSig.DEFAULT_MATCH_THRESHOLD,
        ): AtlasShortcutRunner = AtlasShortcutRunner(
            bridge = AtlasReplayBridge.forExecutor(tools, executor, gate),
            matchThreshold = matchThreshold,
        )

        internal fun forBridge(
            bridge: AtlasReplayBridge,
            matchThreshold: Double = ScreenSig.DEFAULT_MATCH_THRESHOLD,
        ): AtlasShortcutRunner = AtlasShortcutRunner(bridge, matchThreshold)
    }
}
