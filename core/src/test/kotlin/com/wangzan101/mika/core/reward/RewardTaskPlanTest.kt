package com.wangzan101.mika.core.reward

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务接口的读法。
 *
 * 样本按起点福利中心 `mainPage` 的真实结构裁剪：一个 `DailyBenefitModule`，
 * `Process` 是已完成阶梯数，`TaskList` 每条是一个阶梯，九个阶梯共用同一个 `TaskId`。
 */
class RewardTaskPlanTest {

    private fun page(process: Int, vararg steps: Pair<Int, Int>): String {
        val list = steps.mapIndexed { i, (finished, received) ->
            """{"TaskId":"1205501151274598400","TaskRawId":"T2025090203","Desc":"奖励$i","IsFinished":$finished,"IsReceived":$received}"""
        }.joinToString(",")
        return """{"Result":0,"Message":"成功","Data":{"DailyBenefitModule":{"Count":46,""" +
            """"Title":"激励任务","Process":$process,"TaskList":[$list]}}}"""
    }

    @Test
    fun readsProcessAndClaimableCount() {
        val state = RewardTaskPlan.readMainPage(page(1, 1 to 0, 0 to 0, 0 to 0))!!
        assertEquals("激励任务", state.title)
        assertEquals(1, state.process)
        assertEquals(3, state.total)
        assertEquals(1, state.claimable)
        assertTrue(state.hasWork)
    }

    @Test
    fun claimsBeforeWatchingAgain() {
        val state = RewardTaskPlan.readMainPage(page(1, 1 to 0, 0 to 0))!!
        assertEquals(RewardTaskMove.Claim, RewardTaskPlan.nextMove(state))
    }

    @Test
    fun claimsNothingWhenEverythingIsReceived() {
        val state = RewardTaskPlan.readMainPage(page(1, 1 to 1, 0 to 0))!!
        assertEquals(0, state.claimable)
        assertEquals(RewardTaskMove.Watch, RewardTaskPlan.nextMove(state))
    }

    @Test
    fun finishesTheDayWhenEveryStepIsDone() {
        val state = RewardTaskPlan.readMainPage(page(2, 1 to 1, 1 to 1))!!
        assertEquals(RewardTaskMove.Done, RewardTaskPlan.nextMove(state))
        assertTrue(!state.hasWork)
    }

    @Test
    fun toleratesBomAndWhitespace() {
        val body = "\uFEFF \n" + page(0, 0 to 0, 0 to 0) + "\n"
        assertEquals(2, RewardTaskPlan.readMainPage(body)!!.total)
    }

    @Test
    fun returnsNullForSomethingThatIsNotTheTaskPage() {
        assertNull(RewardTaskPlan.readMainPage(""))
        assertNull(RewardTaskPlan.readMainPage("<html></html>"))
        assertNull(RewardTaskPlan.readMainPage("""{"Result":0,"Data":{"Other":1}}"""))
        assertNull(RewardTaskPlan.readMainPage("""{"Result":0,"Data":{"DailyBenefitModule":{"TaskList":[]}}}"""))
    }

    @Test
    fun findsTheModuleWhenItIsNotDirectlyUnderData() {
        val body = """{"Result":0,"Data":{"Outer":{"DailyBenefitModule":{"Process":0,"TaskList":[{"IsFinished":0}]}}}}"""
        assertEquals(1, RewardTaskPlan.readMainPage(body)!!.total)
    }

    @Test
    fun countsTheRewardsThatFinishWatchGranted() {
        assertEquals(2, RewardTaskPlan.rewardCount("""{"Result":0,"Data":{"RewardList":[{"a":1},{"b":2}]}}"""))
        assertEquals(0, RewardTaskPlan.rewardCount("""{"Result":0,"Data":{"RewardList":[]}}"""))
        // 不是这个接口的响应时给 -1，调用方据此判断「没读到」而不是「没发奖」
        assertEquals(-1, RewardTaskPlan.rewardCount("""{"Result":0,"Data":{"Other":1}}"""))
        assertEquals(-1, RewardTaskPlan.rewardCount("x"))
    }

    @Test
    fun tellsSuccessFromArbitraryBodies() {
        assertEquals(true, RewardTaskPlan.succeeded("""{"Result":0}"""))
        assertEquals(false, RewardTaskPlan.succeeded("""{"Result":402,"Message":"风控"}"""))
        assertNull(RewardTaskPlan.succeeded("""{"Data":{}}"""))
    }
}
