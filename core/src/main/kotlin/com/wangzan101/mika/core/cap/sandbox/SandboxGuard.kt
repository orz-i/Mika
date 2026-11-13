package com.wangzan101.mika.core.cap.sandbox

import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.guard.OffScreenGuard
import com.wangzan101.mika.core.guard.Severity
import com.wangzan101.mika.core.guard.actionSignature
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.policy.GuardMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 一个任务里沙箱命令累计最多跑这么久。 */
const val SANDBOX_BUDGET_MS = 30 * 60_000L
private val SANDBOX_COMMANDS = setOf(RunCommand.name, RunBundledScript.name)
internal const val SANDBOX_LOOP_WARN = 3
internal const val SANDBOX_LOOP_STOP = 15
private const val SANDBOX_LOOP_HINT = "This exact command has failed several times in a row with the same result. Do not repeat it unchanged: read the error/output, modify files or arguments, or try a different approach."

/** 沙箱命令的守卫(每次任务一份):同一条命令原样重复且结果无变化地失败的次数,和累计运行时长。屏幕守卫不看沙箱工具。 */
class SandboxGuard(
    private val budgetMs: Long = SANDBOX_BUDGET_MS,
    private val loopWarn: Int = SANDBOX_LOOP_WARN,
    private val loopStop: Int = SANDBOX_LOOP_STOP,
    private val mode: GuardMode = GuardMode.SOFT,
) : OffScreenGuard {
    private var usedMs = 0L
    private var lastSignature: String? = null
    private var lastOutcome: String? = null
    private var repeats = 0

    override var stop: GuardVerdict? = null
        private set

    /** 时长用完时代替执行的结果;没用完为 null。 */
    override fun exhausted(tool: String): JsonObject? {
        if (tool !in SANDBOX_COMMANDS || usedMs < budgetMs) return null
        return buildJsonObject {
            put("ok", false); put("error", "sandbox_budget_exhausted")
            put("message", "Sandbox commands in this task have used their 30 minutes. Finish with what you have, or end_not_done.")
            put("recoverable", false); put("usedMs", usedMs)
        }
    }

    /** 命令的时限不超过剩下的时长,一条命令不会把累计时长冲过上限。参数不对的原样交给工具层去拒。 */
    override fun capTimeout(call: Part.ToolCall, args: JsonObject): Part.ToolCall {
        if (call.name !in SANDBOX_COMMANDS || args.isEmpty()) return call
        val leftSec = ((budgetMs - usedMs) / 1000).coerceAtLeast(1)
        val given = args["timeoutSec"]
        val requested = if (given == null) DEFAULT_TIMEOUT_SEC.toLong()
        else (given as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.toLong() ?: return call
        if (requested !in 1..600 || requested <= leftSec) return call
        return call.copy(argsJson = JsonObject(args + ("timeoutSec" to JsonPrimitive(leftSec))).toString())
    }

    override fun result(tool: String, args: JsonObject, content: JsonObject): List<GuardVerdict> {
        if (tool !in SANDBOX_COMMANDS) {
            if (tool == WriteFile.name && (content["ok"] as? JsonPrimitive)?.booleanOrNull != false) {
                resetLoop()
            }
            return emptyList()
        }
        (content["durationMs"] as? JsonPrimitive)?.longOrNull?.let { usedMs += it }
        if ((content["ok"] as? JsonPrimitive)?.booleanOrNull != false) {
            resetLoop()
            return emptyList()
        }
        val sig = actionSignature(tool, args)
        val outcome = outcomeSignature(content)
        repeats = if (sig == lastSignature && outcome == lastOutcome) repeats + 1 else 1
        lastSignature = sig
        lastOutcome = outcome
        if (repeats < loopWarn) return emptyList()
        val severity = if (mode != GuardMode.WARN_ONLY && repeats >= loopStop) Severity.Stop else Severity.Warn
        val nudge = if (severity == Severity.Warn) {
            "$SANDBOX_LOOP_HINT (repeated $repeats times with identical exitCode/output)."
        } else null
        val v = GuardVerdict("sandbox_loop", severity, buildJsonObject {
            put("tool", tool); put("repeatCount", repeats); put("hint", SANDBOX_LOOP_HINT)
        }, nudge)
        if (v.severity == Severity.Stop) stop = v
        return listOf(v)
    }

    private fun resetLoop() {
        lastSignature = null
        lastOutcome = null
        repeats = 0
        stop = null
    }

    private fun outcomeSignature(content: JsonObject): String {
        val exitCode = (content["exitCode"] as? JsonPrimitive)?.contentOrNull
        val timedOut = (content["timedOut"] as? JsonPrimitive)?.booleanOrNull
        val killed = (content["killedBySystem"] as? JsonPrimitive)?.booleanOrNull
        val error = (content["error"] as? JsonPrimitive)?.contentOrNull
        val output = (content["output"] as? JsonPrimitive)?.contentOrNull
        return "$exitCode|$timedOut|$killed|$error|$output"
    }
}
