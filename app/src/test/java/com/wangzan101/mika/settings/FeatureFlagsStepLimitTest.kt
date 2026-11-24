package com.wangzan101.mika.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureFlagsStepLimitTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun defaultsToTheSafetyNet() {
        // 默认不限制会让翻不完的任务跑到 Token 烧完,所以没设过时走安全网
        assertEquals(FeatureFlags.DEFAULT_TASK_STEP_LIMIT, flags().taskStepLimit())
    }

    @Test fun keepsAChosenLimit() {
        val f = flags()
        f.setTaskStepLimit(60)
        assertEquals(60, f.taskStepLimit())
        f.setTaskStepLimit(null)
        assertNull(f.taskStepLimit())
    }

    @Test fun clampsIntoRange() {
        val f = flags()
        f.setTaskStepLimit(0)
        assertNull(f.taskStepLimit())
        f.setTaskStepLimit(-5)
        assertNull(f.taskStepLimit())
        f.setTaskStepLimit(FeatureFlags.MAX_TASK_STEP_LIMIT + 100)
        assertEquals(FeatureFlags.MAX_TASK_STEP_LIMIT, f.taskStepLimit())
    }
}
