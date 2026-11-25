package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.agent.use.ui.InMemoryAtlasStore
import com.wangzan101.mika.core.tool.FakeScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「使用页面经验」与「记录页面经验」互相独立：只要记录开着就取目标，只开提示不取。 */
class AgentLoopAtlasCaptureTest {
    @Test fun learningAloneCapturesTargetsWithoutHints() {
        val tools = toolDispatcher(FakeScreen())
        AgentLoop(FakeModel(), tools, "BASE", atlasLearn = true)
        assertTrue(tools.captureTargets)
    }

    @Test fun hintsAloneDoNotCaptureWhenLearningIsOff() {
        val tools = toolDispatcher(FakeScreen())
        AgentLoop(FakeModel(), tools, "BASE", atlas = InMemoryAtlasStore(), atlasLearn = false)
        assertFalse(tools.captureTargets)
    }

    @Test fun passingAtlasWithoutFlagsKeepsCapturing() {
        val tools = toolDispatcher(FakeScreen())
        AgentLoop(FakeModel(), tools, "BASE", atlas = InMemoryAtlasStore())
        assertTrue(tools.captureTargets)
    }
}
