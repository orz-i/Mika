package com.wangzan101.mika.atlas

import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.FeatureFlagsCapsuleTest
import com.wangzan101.mika.task.EvalHooks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasFeatureFlagTest {
    @Test fun hintsAndLearningSwitchIndependently() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)

        // 两个开关默认都开，且都不再受「照着上次的做法做」影响
        assertTrue(flags.atlasHintsEnabled())
        assertTrue(flags.atlasLearnEnabled())
        assertTrue(flags.effectiveAtlasHints())
        assertTrue(flags.effectiveAtlasLearn())
        flags.setSpeed(SpeedFlags.OFF)
        assertTrue(flags.effectiveAtlasHints())
        assertTrue(flags.effectiveAtlasLearn())

        // 只关提示：仍然记录
        flags.setAtlasHintsEnabled(false)
        assertFalse(flags.effectiveAtlasHints())
        assertTrue(flags.effectiveAtlasLearn())

        // 提示关、记录开，各自独立
        flags.setAtlasHintsEnabled(true)
        flags.setAtlasLearnEnabled(false)
        assertTrue(flags.effectiveAtlasHints())
        assertFalse(flags.effectiveAtlasLearn())

        // 评测覆盖优先于设置
        assertTrue(flags.effectiveAtlasLearn(override = true))
        assertFalse(flags.effectiveAtlasHints(override = false))
    }

    @Test fun evalHooksResetAtlasOverrides() {
        val hooks = EvalHooks()
        assertNull(hooks.atlasHints)
        assertNull(hooks.atlasLearn)
        hooks.atlasHints = true
        hooks.atlasLearn = true
        hooks.reset()
        assertNull(hooks.atlasHints)
        assertNull(hooks.atlasLearn)
    }
}
