package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.tool.ModelTier
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.ToolTraits
import com.wangzan101.mika.core.tool.Verb

/** 决定下一步用哪个模型时看的上一步情况。 */
data class StepContext(
    val step: Int,
    val lastOk: Boolean,
    val lastHadGuard: Boolean,
    /** 上一步之后画面变没变;读屏一类不报时为 null。 */
    val lastChanged: Boolean?,
    /** 回放交还、批量停止、核查退回、接管或追问恢复。 */
    val lastWasRecovery: Boolean,
    val sameAppAsLast: Boolean,
    /** 要发出的上下文里有截图;不看图的轻量模型不能接。 */
    val hasImages: Boolean = false,
)

/**
 * 实验开关 lightSteps 的分工:简单步给轻量模型,其余给主模型。
 * 轻量模型没配、不支持工具调用时恒用主模型。一步出了错误、守卫提示或画面没变,接下来 [TROUBLE_STEPS] 步强制主模型。
 */
class ModelRouter(private val main: ChatModel, private val light: ChatModel?) {
    private var forcedMain = 0
    private var disabled = false

    /** 轻量模型能不能接手机步;不能时 lightSteps 不生效。 */
    val active: Boolean get() = !disabled && light != null && light.capabilities.toolCalls

    /** 轻量模型出了重试也没用的错(Key、额度、模型名、请求不对):余下都用主模型。一个 router 只服务一次任务。 */
    fun disable() { disabled = true }

    fun pick(ctx: StepContext): ChatModel {
        if (!ctx.lastOk || ctx.lastHadGuard || ctx.lastChanged == false) noteTrouble()
        if (forcedMain > 0) {
            forcedMain--
            return main
        }
        val l = light?.takeIf { active } ?: return main
        val simple = ctx.step > 1 && !ctx.lastWasRecovery && ctx.sameAppAsLast && (!ctx.hasImages || l.capabilities.vision)
        return if (simple) l else main
    }

    /** 出了状况(含轻量模型调用失败、回复被改派给主模型):之后 [TROUBLE_STEPS] 步用主模型。 */
    fun noteTrouble() { forcedMain = TROUBLE_STEPS }

    fun isLight(m: ChatModel): Boolean = light != null && m === light

    companion object {
        const val TROUBLE_STEPS = 2

        /** 只能由主模型给出的调用:按 [ToolTraits.modelTier] 判断(以及 do_steps)。 */
        fun mainOnly(name: String, traits: ToolTraits): Boolean =
            name == DO_STEPS_TOOL || traits.modelTier == ModelTier.MainModelOnly

        /** 按工具索引查询是否只能由主模型给出。 */
        fun mainOnly(name: String, tools: ToolIndex): Boolean =
            name == DO_STEPS_TOOL || tools.traits(name)?.modelTier == ModelTier.MainModelOnly

        /** 兼容旧调用方:由 [Verb] 推导 [ToolTraits] 后判断。 */
        fun mainOnly(name: String, verb: Verb?): Boolean =
            name == DO_STEPS_TOOL || verb?.let { ToolTraits.fromVerb(it).modelTier == ModelTier.MainModelOnly } == true
    }
}

/** AgentLoop 用:记下每一步的情况,拼出下一步的 [StepContext]。 */
internal class StepTrack {
    private var ok = true
    private var guard = false
    private var changed: Boolean? = null
    private var recovery = false
    private var app: String? = null
    private var appBefore: String? = null
    private var switched = false

    fun context(step: Int, hasImages: Boolean) =
        StepContext(step, ok, guard, changed, recovery, !switched && app != null && app == appBefore, hasImages)

    /** [app] 为 null 表示这一步的结果没报前台 App,沿用之前的。 */
    /** 刚从回放交还、批量停止这类状况里恢复:下一步按恢复步算。在 [after] 之后调用。 */
    fun markRecovery() { recovery = true }

    fun after(traits: ToolTraits, ok: Boolean, guard: Boolean, changed: Boolean?, recovery: Boolean, app: String?) =
        recordAfter(traits, ok, guard, changed, recovery, app)

    fun after(verb: Verb?, ok: Boolean, guard: Boolean, changed: Boolean?, recovery: Boolean, app: String?) =
        recordAfter(verb?.let { ToolTraits.fromVerb(it) }, ok, guard, changed, recovery, app)

    private fun recordAfter(traits: ToolTraits?, ok: Boolean, guard: Boolean, changed: Boolean?, recovery: Boolean, app: String?) {
        this.ok = ok
        this.guard = guard
        this.changed = changed
        this.recovery = recovery || traits?.userInteraction == true
        appBefore = this.app
        if (app != null) this.app = app
        switched = traits?.switchesPlace == true
    }
}
