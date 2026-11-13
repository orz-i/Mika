package com.wangzan101.mika.core.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuardRulesTest {
    @Test fun climbWarnsOncePerRungAndStopsAtTop() {
        val ladder = GuardRules.repeatLadder(diagnostic = false)
        assertNull(GuardRules.climb(3, ladder, 0, canStop = true))
        assertEquals(GuardRules.Rung(4, Severity.Warn), GuardRules.climb(4, ladder, 0, canStop = true))
        assertNull(GuardRules.climb(5, ladder, 4, canStop = true))
        assertEquals(GuardRules.Rung(8, Severity.Warn), GuardRules.climb(8, ladder, 4, canStop = true))
        assertNull(GuardRules.climb(13, ladder, 8, canStop = true))
        assertEquals(GuardRules.Rung(14, Severity.Warn), GuardRules.climb(14, ladder, 8, canStop = true))
        assertNull(GuardRules.climb(23, ladder, 14, canStop = true))
        assertEquals(GuardRules.Rung(24, Severity.Stop), GuardRules.climb(24, ladder, 14, canStop = true))
        // 不许叫停的范围里最高一级也只提醒
        assertEquals(GuardRules.Rung(24, Severity.Warn), GuardRules.climb(25, ladder, 14, canStop = false))
    }

    @Test fun diagnosticReadsHaveHigherLadder() {
        assertNull(GuardRules.climb(7, GuardRules.repeatLadder(diagnostic = true), 0, canStop = true))
        assertEquals(Severity.Warn, GuardRules.climb(8, GuardRules.repeatLadder(diagnostic = true), 0, canStop = true)!!.severity)
        assertEquals(Severity.Stop, GuardRules.climb(24, GuardRules.REREAD_LADDER, 16, canStop = true)!!.severity)
    }

    /** 安全网远在提醒之后:每条阶梯叫停的门槛至少是第一次提醒的两倍。 */
    @Test fun stopsAreFarBehindNudges() {
        val ladders = listOf(
            GuardRules.repeatLadder(false), GuardRules.repeatLadder(true), GuardRules.NO_EFFECT_LADDER, GuardRules.TREE_BLIND_LADDER,
            GuardRules.sameStateLadder(null), GuardRules.cycleLadder(null), GuardRules.noNewStateLadder(null),
            GuardRules.REREAD_LADDER, GuardRules.FAILURE_LADDER, GuardRules.UNRECOVERABLE_LADDER,
            GuardRules.sameStateLadder(5), GuardRules.cycleLadder(5), GuardRules.noNewStateLadder(5),
        )
        for (l in ladders) assert(l.last() >= 2 * l.first() && l == l.sorted()) { "ladder $l" }
        assertEquals(24, GuardRules.NO_EFFECT_LADDER.last())
        assertEquals(12, GuardRules.TREE_BLIND_LADDER.last())
    }

    /** 慢慢兜圈子的安全网跟着任务的步数上限走,但不低于下限。 */
    @Test fun lateStopsFollowTheStepBudget() {
        assertEquals(30, GuardRules.noNewStateLadder(null).last())
        assertEquals(50, GuardRules.noNewStateLadder(100).last())
        assertEquals(24, GuardRules.noNewStateLadder(20).last())
        assertEquals(25, GuardRules.sameStateLadder(100).last())
        assertEquals(20, GuardRules.cycleLadder(100).last())
    }

    @Test fun shortCyclesAreFound() {
        assertNull(GuardRules.cycle(listOf(1L, 2L)))
        assertEquals(GuardRules.Cycle(2, 3), GuardRules.cycle(listOf(1L, 2L, 1L)))
        assertEquals(2, GuardRules.cycle(listOf(5L, 3L, 1L, 2L, 1L, 2L))!!.rounds)
        assertNull(GuardRules.cycle(listOf(1L, 2L, 1L, 3L)))
        // 三个状态一圈
        val three = GuardRules.cycle(listOf(1L, 2L, 3L, 1L, 2L, 3L))!!
        assertEquals(3, three.period); assertEquals(2, three.rounds)
    }

    @Test fun failures() {
        assertNull(GuardRules.failures(1, 0))
        assertEquals(Severity.Warn, GuardRules.failures(2, 0))
        assertEquals(Severity.Warn, GuardRules.failures(1, 1))
        assertEquals(Severity.Warn, GuardRules.failures(15, 0))
        assertEquals(Severity.Stop, GuardRules.failures(16, 0))
        assertEquals(Severity.Warn, GuardRules.failures(3, 3))
        assertEquals(Severity.Stop, GuardRules.failures(10, 10))
        assertEquals(Severity.Warn, GuardRules.failures(16, 10, canStop = false))
    }
}
