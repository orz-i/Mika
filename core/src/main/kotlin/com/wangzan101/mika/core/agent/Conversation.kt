package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role

/**
 * 一轮对话:用户说了什么、结果如何。[outcome] 是成功时的答案或失败原因;
 * [status] 为 succeeded / unverified(办完了但没能核实,[note] 是原因,[unconfirmed] 是标成未确认的几处)/ failed / cancelled。
 * [runId] 是这轮的运行记录;没开起记录或更早的轮次为 null。
 */
/**
 * [memoryNotes]/[usedMemory]/[skillNotes]:结果卡片下的记忆与技能提示,任务结束后补上;不进给模型的上文。
 * [result]:交出回答后判的结局([RunOutcome.wire]),后台补上;没判过为 null。
 */
data class Turn(
    val userText: String, val outcome: String, val status: String, val note: String? = null, val runId: String? = null,
    val memoryNotes: List<com.wangzan101.mika.core.memory.MemoryNote> = emptyList(),
    val usedMemory: List<String> = emptyList(),
    val skillNotes: List<com.wangzan101.mika.core.skills.SkillNote> = emptyList(),
    val unconfirmed: List<String> = emptyList(),
    val result: String? = null,
    /** 任务进行中用户插的、已交给这个任务的话(界面在那一轮下面显示)。 */
    val steers: List<String> = emptyList(),
    /** 和 [steers] 一一对应:每句插话打断的那段回复里用户已经看到的字,没打断的是空串。只给界面显示,不进给模型的上文。 */
    val interruptedReplies: List<String> = emptyList(),
    /** 和 [steers] 一一对应:每句插话交给模型之前、已经说完显示出来的那段回复,没有的是空串。只给界面显示。 */
    val finishedReplies: List<String> = emptyList(),
    /** 这一轮真的用过的能力组(手机、网页、沙箱……);下一轮开局预先展开,不进给模型的上文。 */
    val groups: List<String> = emptyList(),
    /** 任务结束后留在后台接着运行的那一页的站点(见 web_keep_open);没留为 null。 */
    val keptPage: String? = null,
    /** 留下的那一页后来关掉的原因([com.wangzan101.mika.core.cap.web.KeptPageClose.wire]);还开着或没留为 null。 */
    val keptPageClosed: String? = null,
    /** 这一轮存进对话的毫秒时刻;旧记录没有时为 null,不由文件时间猜测。 */
    val atMs: Long? = null,
    /** 实际核验的结果。未核验和没有该字段的旧记录均为 null。 */
    val verified: Boolean? = null,
    /** 电脑派发时的任务编号，在运行记录创建前也可用。 */
    val remoteTaskId: String? = null,
)

object Conversation {
    const val HEADER = "Earlier turns of this conversation (for reference; what you read this time shows the current state):"

    /** 停止的那一轮还没有交代(或没什么可交代)时记下的话。 */
    const val CANCELLED_OUTCOME = "已取消"

    /** 停止的那一轮补上的交代;没有时为 null。 */
    fun stopSummary(t: Turn): String? =
        t.outcome.trim().takeIf { t.status == "cancelled" && it.isNotEmpty() && it != CANCELLED_OUTCOME }

    /** 默认用于参考历史的上下文窗口比例。 */
    const val DEFAULT_BUDGET_RATIO = 0.10
    const val DEFAULT_CONTEXT_TOKENS = 128_000
    const val MIN_ADAPTIVE_TOKENS = 1_000
    const val MAX_ADAPTIVE_TOKENS = 24_000

    /**
     * 按模型上下文窗口 [contextTokens] 与比例 [budgetRatio] 计算参考历史可用的 token 预算:
     * 扣掉输出预留后的可用窗口按比例分配,并在 [minTokens]..[maxTokens] 间自适应,同时不超过可用窗口的三分之一。
     */
    fun tokenBudget(
        contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
        budgetRatio: Double = DEFAULT_BUDGET_RATIO,
        minTokens: Int = MIN_ADAPTIVE_TOKENS,
        maxTokens: Int = MAX_ADAPTIVE_TOKENS,
    ): Int {
        val usable = ContextLimits.of(contextTokens).usable
        if (usable <= 0) return 0
        val ratio = budgetRatio.coerceIn(0.01, 0.5)
        val cap = (usable / 3).coerceAtLeast(1)
        val low = minOf(minTokens, cap)
        val high = minOf(maxTokens, cap).coerceAtLeast(low)
        return (usable * ratio).toInt().coerceIn(low, high)
    }

    /**
     * 把之前的轮次做成一条用户角色的参考消息,最近的在最后;整条超过预算(按 [TokenEstimate])就丢掉较早的轮次。
     * 不传 [maxTurns] 时不设轮数硬上限,按预算尽可能装载多轮;传 [maxTokens] 时按固定上限,否则按 [contextTokens] 与 [budgetRatio] 自适应分配。
     * 只剩一轮还超长时各字段分别截断(用户原话和结局都留一段),仍超长再整段截断。没有轮次或预算为 0 返回 null。
     */
    fun context(
        turns: List<Turn>,
        maxTurns: Int = Int.MAX_VALUE,
        maxTokens: Int? = null,
        contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
        budgetRatio: Double = DEFAULT_BUDGET_RATIO,
    ): Message? {
        val budget = (maxTokens ?: tokenBudget(contextTokens, budgetRatio)).coerceAtLeast(0)
        if (budget <= 0) return null
        val capped = turns.takeLast(maxTurns.coerceAtLeast(0))
        if (capped.isEmpty()) return null
        val header = "$HEADER\n"
        fun render(ts: List<Turn>, clip: Int) = ts.mapIndexed { i, t -> line(i + 1, t, clip) }.joinToString("\n")
        fun fits(drop: Int) = TokenEstimate.of(header + render(capped.subList(drop, capped.size), Int.MAX_VALUE)) <= budget
        var recent = if (capped.size <= 1 || fits(0)) capped else {
            var lo = 1
            var hi = capped.size - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (fits(mid)) hi = mid else lo = mid + 1
            }
            capped.subList(lo, capped.size)
        }
        var body = render(recent, Int.MAX_VALUE)
        val room = (budget - TokenEstimate.of(header)).coerceAtLeast(20)
        if (TokenEstimate.of(header + body) > budget) {
            // 只剩一轮还超长:各字段截断,原话和结局各留一段
            body = render(recent, room / 3)
            if (TokenEstimate.of(header + body) > budget) body = TokenEstimate.clip(body, room, "…")
        }
        return Message(Role.User, listOf(Part.Text("$HEADER\n$body")), Author.Mika)
    }

    private fun line(n: Int, t: Turn, clip: Int): String {
        // 回答如实说没办成或只办了一部分:按交出后判的结局说,下一轮别当它办成了
        val answered = t.status == "succeeded" || t.status == "unverified"
        val result = when {
            answered && t.result == RunOutcome.Partial.wire -> "only partly done: ${t.outcome.clip(clip)}"
            answered && t.result == RunOutcome.NotPossible.wire -> "not done: ${t.outcome.clip(clip)}"
            t.status == "succeeded" -> "result: ${t.outcome.clip(clip)}"
            t.status == "unverified" -> "result (could not be verified): ${t.outcome.clip(clip)}"
            // 停止后补上的交代(做过什么、哪些没做完)带上,下一句「继续」才知道从哪接着做;旧记录里只有「已取消」
            t.status == "cancelled" -> stopSummary(t)?.let { "the user stopped this task partway; before the stop: ${it.clip(clip)}" } ?: "the user cancelled this task"
            else -> "not finished, reason: ${t.outcome.clip(clip)}"
        }
        // 任务结束后留下的那一页:下一轮被问到「还在播吗」时知道它还开着还是已经关了
        val kept = t.keptPage?.let { host ->
            if (t.keptPageClosed == null) "; after it Mika kept the page on $host running in the background"
            else "; Mika kept the page on $host running after it, and that page has since been closed"
        }.orEmpty()
        return "$n. The user said: ${t.userText.clip(clip)}; $result$kept"
    }

    /** 截到 [max] 个 token(含末尾的「…」)。 */
    private fun String.clip(max: Int) = if (max == Int.MAX_VALUE || TokenEstimate.of(this) <= max) this else TokenEstimate.clip(this, max, "…")
}
