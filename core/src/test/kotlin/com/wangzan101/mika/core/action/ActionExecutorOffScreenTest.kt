package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.uistate.ScreenPort
import com.agent.use.ui.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutorOffScreenTest {
    @Test
    fun offScreenScheduleTaskDoesNotTriggerUnreadChangeOrScreenRead() = runTest {
        var snapshots = 0
        val fake = FakeScreen()
        val screen = object : ScreenPort by fake {
            override suspend fun snapshot(): ScreenSnapshot {
                snapshots++
                return fake.snapshot()
            }
        }
        val tools = toolDispatcher(
            screen = screen,
            scheduleTrigger = { Scheduled(onTime = true) },
        )
        val recorder = ActionRecorder(RunSink.NONE)
        val executor = ActionExecutor(tools, OperationGuards(), null, gate = {}, recorder = recorder, autonomous = true)

        val scheduled = executor.run(
            Part.ToolCall("s1", "schedule_task", """{"goal":"明天提醒我喝水","at":"2026-11-14T09:00:00+08:00"}"""),
            Origin.Model(1),
        )
        assertTrue("$scheduled", scheduled is ActionOutcome.Ran)
        assertNull("offScreen tool must not mark unreadChange", recorder.lastChangeMark)
        assertEquals("offScreen ParallelSafe tool must not read screen", 0, snapshots)

        val tap = executor.run(
            Part.ToolCall("t1", "phone_tap", """{"x":100,"y":200}"""),
            Origin.Model(2),
        )
        assertTrue("$tap", tap is ActionOutcome.Ran)
        assertNotNull("visible UI modify tool must mark unreadChange", recorder.lastChangeMark)
    }

    @Test
    fun dispatcherExposesConcurrencyAndScreenPropertiesForLoopCore() {
        val tools = toolDispatcher(
            screen = FakeScreen(),
            scheduleTrigger = { Scheduled(onTime = true) },
            webSearch = object : com.wangzan101.mika.core.search.WebSearch {
                override suspend fun search(query: String, count: Int) =
                    com.wangzan101.mika.core.search.WebSearchResult.Success(emptyList())
            },
        )
        assertFalse(tools.isParallelSafe("schedule_task"))
        assertTrue(tools.offScreen("schedule_task"))
        assertFalse(tools.writesScreen("schedule_task"))

        assertTrue(tools.isParallelSafe("web_search"))
        assertTrue(tools.offScreen("web_search"))
        assertFalse(tools.writesScreen("web_search"))

        assertFalse(tools.isParallelSafe("phone_tap"))
        assertFalse(tools.offScreen("phone_tap"))
        assertTrue(tools.writesScreen("phone_tap"))

        assertFalse(tools.isParallelSafe("phone_observe"))
        assertFalse(tools.offScreen("phone_observe"))
        assertFalse(tools.writesScreen("phone_observe"))
    }
}
