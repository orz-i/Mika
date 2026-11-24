package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.ToolTraits

/** 循环发给模型的一条提醒:来源标签和正文(由 [ContextBuilder.notice] 包成上下文)。 */
internal data class Notice(val source: String, val text: String)

/** 回合策略看的快照:第几步、步数上限。 */
internal data class TurnSnapshot(val step: Int, val maxSteps: Int)

/** 和基础提示词同一条规则:要的结果缺了要用的功能、条目或值是做不成;只有问「有没有」时,App 的搜索明确说没有才是答案。 */
internal val ANSWER_OR_FAILURE: String get() = Feedback["answer_or_failure"]

/** 为什么禁止继续操作、只给最后一次收尾机会。 */
internal enum class Closeout { StepLimit, Completed, NoNewData }

/**
 * 回合策略:每步开头要不要先提醒(中途自查)、回复是空的(或被输出上限截断)时提醒还是结束、核对没对上时怎么提醒、
 * 步数用完后最后一轮说什么、给哪些工具。纯函数,只看传进来的东西。
 * 不带调用的回复本身就是回答,这里不管;只有空回复会被提醒,而且只提醒一次。
 */
internal object TurnPolicy {
    /** 中途自查:步数用到这些比例时提醒一次,找遍了就如实放弃。步数太少的任务不提醒。 */
    private val PROGRESS_CHECKS = listOf(0.5, 0.75)
    private const val PROGRESS_CHECK_MIN_STEPS = 10

    /** 申报的计数连续这么多步没变化就提醒一次;步数上限本身很小(短任务)时不提醒。 */
    private const val METRIC_STALL_STEPS = 8
    private const val METRIC_STALL_MIN_STEPS = 12

    /** 判断某工具是否为步数用完后最后一轮仍保留的终结工具。 */
    fun isFinalTool(traits: ToolTraits?): Boolean = traits?.terminatesRun == true

    /** 这一步发给模型之前先加的提醒;没有为 null。 */
    fun beforeStep(s: TurnSnapshot): Notice? {
        if (s.maxSteps < PROGRESS_CHECK_MIN_STEPS) return null
        val used = s.step - 1
        if (PROGRESS_CHECKS.none { (s.maxSteps * it).toInt() == used }) return null
        return Notice(Feedback["progress_source"],
            Feedback.format("progress_check", "used" to used, "max" to s.maxSteps, "answer_or_failure" to ANSWER_OR_FAILURE))
    }

    /**
     * 回复里没有调用也没有字([truncated]:字被输出上限截断了,不是完整的回答)。[already] 为 true 表示这样的回复刚提醒过一次:
     * 这次不再提醒,返回 null,任务就此结束。
     */
    fun afterEmpty(already: Boolean, truncated: Boolean): Notice? =
        if (already) null else Notice(Feedback["nudge_source"], Feedback[if (truncated) "truncated_reply" else "empty_reply"])

    /**
     * 回答核对没对上,退回一次时的提醒:[gaps] 是读到的材料里找不到的那几处,[objections] 是核查模型判没做成的理由,分开说。
     */
    fun unconfirmed(gaps: List<String>, objections: List<String> = emptyList()): Notice {
        val findings = listOfNotNull(
            gaps.takeIf { it.isNotEmpty() }?.let { Feedback.format("unconfirmed_gaps", "items" to it.joinToString("\n") { g -> "- $g" }) },
            objections.takeIf { it.isNotEmpty() }?.let { Feedback.format("unconfirmed_objection", "reasons" to it.joinToString("\n") { o -> "- $o" }) },
        ).joinToString("\n")
        return Notice(Feedback["unconfirmed_source"], Feedback.format("unconfirmed_nudge", "findings" to findings))
    }

    /** 回答的正文不是用户那句的语言([m] 是两边的语族,见 [ReplyLanguage]):退回一次,整段改写成用户的语言。 */
    fun language(m: ReplyLanguage.Mismatch): Notice =
        Notice(Feedback["language_source"], Feedback.format("language_nudge", "answer" to m.answer.display, "user" to m.user.display))

    /** 回复正文里漏出了对 [tool] 的调用(见 [LeakedToolCall]):退回一次,经工具接口重新调用,或用文字回答。 */
    fun leakedToolCall(tool: String): Notice =
        Notice(Feedback["leaked_call_source"], Feedback.format("leaked_call_nudge", "tool" to tool))

    /** 用户同意了、或回答了([what]),之后却什么也没做就要交回答:提醒一次照做,或说清为什么不做。 */
    fun followUp(what: String): Notice = Notice(Feedback["followup_source"], Feedback.format("followup_nudge", "what" to what))

    /** 步数用完后最后一轮之前的提醒。 */
    fun finalTurn(): Notice = Notice(Feedback["final_source"], Feedback["final_turn"])

    /** 连续多次行动未产生新数据时的一次性软提醒: 不强行终止, 提示模型换方法、核对或直接作答/放弃。 */
    fun noNewDataNotice(progress: RunProgress.Task? = null): Notice {
        val confirmed = progress?.display?.takeIf { it.isNotBlank() }?.let { "\nLatest confirmed progress: $it" }.orEmpty()
        return Notice(
            Feedback["closeout_source"],
            "The last ${NewDataTracker.LIMIT} consecutive collection/browse actions produced no new data. " +
                "Consider changing your query/approach, answering with what you have confirmed, or reporting what could not be found instead of repeating the same step.$confirmed",
        )
    }

    /**
     * 模型写的 `<task_progress>` 被丢弃了([detail] 是理由):说出来,否则它以为已经申报成功。
     *
     * 一个格式错误(比如 `total` 写成 `"20 个"`)会让整块作废,任务因此没有完成条件,
     * 只能一路做到步数用完——这是「任务跑不完」最隐蔽的一种成因。
     */
    fun progressRejected(detail: String): Notice =
        Notice(Feedback["progress_error_source"], Feedback.format("progress_rejected", "detail" to detail))

    /** 步数过半还没有任何可验收的完成条件:提醒一次,否则任务只会在步数用尽时失败。 */
    fun progressMissing(step: Int, maxSteps: Int): Notice? {
        if (maxSteps < METRIC_STALL_MIN_STEPS || step - 1 < maxSteps / 2) return null
        return Notice(Feedback["progress_source"], Feedback["progress_missing"])
    }

    /**
     * 自己申报的计数连续几步一个数都没动:提醒一次按已读到的条目如实更新,或就此交出结果。
     *
     * 这是「任务无限跑下去」最常见的形态——采集一直在正常出内容(所以无新数据的守卫不会响),
     * 但模型不肯把已经读到的条目算成进度,`done` 停在 0,`complete` 就永远到不了,只能等步数用完。
     * 只提醒,不叫停:真的在长任务里慢慢推进时,一句话的代价远小于一个跑不完的任务。
     * [sinceStep] 是这个计数上一次变化的步数,[nudged] 为 true 表示已经提醒过。
     */
    fun metricStalled(step: Int, maxSteps: Int, sinceStep: Int, nudged: Boolean, progress: RunProgress.Task? = null): Notice? {
        if (nudged || maxSteps < METRIC_STALL_MIN_STEPS) return null
        if (step - sinceStep < METRIC_STALL_STEPS) return null
        val confirmed = progress?.display?.takeIf { it.isNotBlank() }?.let { "\nLatest confirmed progress: $it" }.orEmpty()
        return Notice(Feedback["metric_source"], Feedback.format("metric_stalled", "progress" to confirmed))
    }

    /** 完成或连续无新数据后的收尾:不再给操作工具，但仍可交出结果或如实报告部分完成。 */
    fun closeout(reason: Closeout, progress: RunProgress.Task? = null): Notice {
        val confirmed = progress?.display?.takeIf { it.isNotBlank() }?.let { "\nLatest confirmed progress: $it" }.orEmpty()
        return when (reason) {
            Closeout.StepLimit -> finalTurn()
            Closeout.Completed -> Notice(Feedback["closeout_source"], Feedback.format("completed_closeout", "progress" to confirmed))
            Closeout.NoNewData -> Notice(Feedback["closeout_source"],
                Feedback.format("no_new_data_closeout", "count" to NewDataTracker.LIMIT, "progress" to confirmed))
        }
    }

    /** 最后一轮给模型的工具:只有放弃;做完了就直接用文字回答。 */
    fun finalTools(specs: List<ToolSpec>, traitsOf: (String) -> ToolTraits?): List<ToolSpec> = specs.filter { isFinalTool(traitsOf(it.name)) }
}
