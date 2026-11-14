package com.wangzan101.mika.core.policy

import com.wangzan101.mika.core.action.OutwardPacing
import com.wangzan101.mika.core.tool.EffectKind
import com.wangzan101.mika.core.tool.ToolTraits

/**
 * Result of evaluating [PacingPolicy.beforeAction].
 * [waitMs] requests delaying before the action; [idleScroll] indicates optional micro-scrolling during wait;
 * [stopReason] stops the action before execution (e.g., site page cap reached).
 */
data class PacingDirective(
    val waitMs: Long = 0L,
    val idleScroll: Boolean = false,
    val stopReason: String? = null,
) {
    val shouldWait: Boolean get() = waitMs > 0L
    val isStopped: Boolean get() = stopReason != null

    companion object {
        val NONE = PacingDirective()
    }
}

/** Snapshot passed to [PacingPolicy] around an action. */
data class PacingContext(
    val tool: String,
    val capability: String? = null,
    val traits: ToolTraits? = null,
    val site: String? = null,
    val outward: Boolean = false,
    val writesScreen: Boolean = false,
    val nowMs: Long = 0L,
)

/**
 * Abstraction for anti-crawl / human-like pacing (outward cooldown, page dwell, idle scroll).
 * General runs use [NONE] or a selective [SensitiveUiPacingPolicy] so sandbox, MCP, memory,
 * and read/workspace-write actions run at full speed.
 */
interface PacingPolicy {
    val enabled: Boolean

    fun beforeAction(ctx: PacingContext): PacingDirective = PacingDirective.NONE

    fun afterAction(ctx: PacingContext, succeeded: Boolean) {}

    fun onSiteLoaded(site: String, nowMs: Long) {}

    fun onUserAnswered() {}

    companion object {
        val NONE: PacingPolicy = NoOpPacingPolicy
    }
}

private data object NoOpPacingPolicy : PacingPolicy {
    override val enabled: Boolean = false
}

/**
 * Selective UI/anti-crawl pacing policy.
 *
 * Returns [PacingDirective.NONE] immediately (zero delay) for all general, read-only,
 * workspace-write, sandbox, MCP, memory, and conversational operations.
 * Applies pacing only to:
 * 1. Outward actions ([PacingContext.outward] or [EffectKind.External] on an identifiable site/app) via [OutwardPacing].
 * 2. Sensitive UI screen-write operations (`phone`/`web` or [EffectKind.ScreenWrite]) when [uiActionGapMs] > 0.
 */
class SensitiveUiPacingPolicy(
    private val outwardPacing: OutwardPacing = OutwardPacing.Off,
    private val uiActionGapMs: Long = 0L,
) : PacingPolicy {

    private val lastUiWriteAtBySite = HashMap<String, Long>()

    override val enabled: Boolean
        get() = outwardPacing.paced || uiActionGapMs > 0L

    @Synchronized
    override fun beforeAction(ctx: PacingContext): PacingDirective {
        if (!enabled) return PacingDirective.NONE
        val effect = ctx.traits?.effect
        val capability = ctx.capability?.lowercase()

        // Non-UI capabilities (sandbox, mcp, memory, skills, root, etc.) never receive UI anti-crawl pacing
        // unless an explicit outward site cooldown applies.
        val isUiCap = capability == null || capability in RunProfile.UI_CAPABILITIES
        val isOutward = ctx.outward || effect == EffectKind.External
        if (isOutward && ctx.site != null && outwardPacing.paced) {
            val wait = outwardPacing.waitBefore(ctx.site, ctx.nowMs)
            return if (wait > 0L) PacingDirective(waitMs = wait) else PacingDirective.NONE
        }

        if (!isUiCap) return PacingDirective.NONE
        if (effect == EffectKind.ReadOnly || effect == EffectKind.WorkspaceWrite) return PacingDirective.NONE
        val isUiWrite = ctx.writesScreen || effect == EffectKind.ScreenWrite || ctx.traits?.modifiesScreenState == true
        if (!isUiWrite || uiActionGapMs <= 0L) return PacingDirective.NONE

        val key = ctx.site ?: DEFAULT_UI_KEY
        val lastAt = lastUiWriteAtBySite[key] ?: return PacingDirective.NONE
        val remaining = (uiActionGapMs - (ctx.nowMs - lastAt)).coerceAtLeast(0L)
        return if (remaining > 0L) PacingDirective(waitMs = remaining) else PacingDirective.NONE
    }

    @Synchronized
    override fun afterAction(ctx: PacingContext, succeeded: Boolean) {
        if (!enabled || !succeeded) return
        val effect = ctx.traits?.effect
        val isOutward = ctx.outward || effect == EffectKind.External
        if (isOutward && ctx.site != null && outwardPacing.paced) {
            outwardPacing.noteDone(ctx.site, ctx.nowMs)
        }
        val capability = ctx.capability?.lowercase()
        val isUiCap = capability == null || capability in RunProfile.UI_CAPABILITIES
        val isUiWrite = ctx.writesScreen || effect == EffectKind.ScreenWrite || ctx.traits?.modifiesScreenState == true
        if (isUiCap && isUiWrite && uiActionGapMs > 0L && effect != EffectKind.ReadOnly && effect != EffectKind.WorkspaceWrite) {
            lastUiWriteAtBySite[ctx.site ?: DEFAULT_UI_KEY] = ctx.nowMs
        }
    }

    private companion object {
        const val DEFAULT_UI_KEY = "_ui"
    }
}
