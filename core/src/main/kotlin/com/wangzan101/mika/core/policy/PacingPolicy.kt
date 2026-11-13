package com.wangzan101.mika.core.policy

import com.wangzan101.mika.core.tool.Verb

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
    val verb: Verb? = null,
    val site: String? = null,
    val outward: Boolean = false,
    val writesScreen: Boolean = false,
    val nowMs: Long = 0L,
)

/**
 * Abstraction for anti-crawl / human-like pacing (outward cooldown, page dwell, idle scroll).
 * General runs use [NONE] so core execution has zero hard dependency on UI pacing components.
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
