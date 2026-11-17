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
    @Test fun atlasHintsDefaultsOnWithReplayAndRespectsOverrides() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)

        assertTrue(flags.atlasHintsEnabled())
        assertTrue(flags.effectiveAtlasHints())

        // 关闭 replay 时默认一起关闭；显式覆盖仍可开启
        flags.setSpeed(SpeedFlags.OFF)
        assertFalse(flags.effectiveAtlasHints())
        assertTrue(flags.effectiveAtlasHints(override = true))

        // 恢复 replay 后，可单独关闭 atlasHintsEnabled
        flags.setSpeed(SpeedFlags(replay = true))
        flags.setAtlasHintsEnabled(false)
        assertFalse(flags.atlasHintsEnabled())
        assertFalse(flags.effectiveAtlasHints())
        assertTrue(flags.effectiveAtlasHints(override = true))

        flags.setAtlasHintsEnabled(true)
        assertFalse(flags.effectiveAtlasHints(override = false))
    }

    @Test fun evalHooksResetsAtlasHintsOverride() {
        val hooks = EvalHooks()
        assertNull(hooks.atlasHints)
        hooks.atlasHints = true
        hooks.reset()
        assertNull(hooks.atlasHints)
    }
}
