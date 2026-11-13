package com.wangzan101.mika.core.policy

import com.wangzan101.mika.core.action.OutwardPacing

/**
 * Adapter bridging legacy [OutwardPacing] into [PacingPolicy] for backward compatibility.
 * Phase 2 capability pacing (`PagePacing` / `AppPacing` / `IdleScroll`) can implement [PacingPolicy] similarly.
 */
class OutwardPacingPolicy(private val outward: OutwardPacing) : PacingPolicy {
    override val enabled: Boolean get() = outward.paced

    override fun beforeAction(ctx: PacingContext): PacingDirective {
        if (!outward.paced || !ctx.outward) return PacingDirective.NONE
        val site = ctx.site ?: return PacingDirective.NONE
        val wait = outward.waitBefore(site, ctx.nowMs)
        return if (wait > 0L) PacingDirective(waitMs = wait) else PacingDirective.NONE
    }

    override fun afterAction(ctx: PacingContext, succeeded: Boolean) {
        if (!outward.paced || !ctx.outward || !succeeded) return
        val site = ctx.site ?: return
        outward.noteDone(site, ctx.nowMs)
    }
}

fun OutwardPacing.asPacingPolicy(): PacingPolicy = if (paced) OutwardPacingPolicy(this) else PacingPolicy.NONE
