package com.wangzan101.mika.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsJumpConfirmTest {
    @Test fun jumpConfirmHostingDefaultsToFalseAndPersists() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        assertFalse(flags.jumpConfirmHosting())
        flags.setJumpConfirmHosting(true)
        assertTrue(FeatureFlags(prefs).jumpConfirmHosting())
        flags.setJumpConfirmHosting(false)
        assertFalse(FeatureFlags(prefs).jumpConfirmHosting())
    }

    @Test fun autoBlockDisablesHostingAndClearsOnClearOrVersionBump() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val flags = FeatureFlags(prefs)
        flags.setJumpConfirmHosting(true)
        flags.markJumpConfirmAutoBlocked(versionCode = 100)

        assertFalse(flags.jumpConfirmHosting())
        assertTrue(flags.jumpConfirmAutoBlocked(versionCode = 100))
        assertFalse(flags.jumpConfirmAutoBlocked(versionCode = 101))

        flags.clearJumpConfirmAutoBlocked()
        assertFalse(flags.jumpConfirmAutoBlocked(versionCode = 100))

        flags.markJumpConfirmAutoBlocked(versionCode = 100)
        assertTrue(flags.jumpConfirmAutoBlocked(versionCode = 100))
        flags.setJumpConfirmHosting(true)
        assertFalse(flags.jumpConfirmAutoBlocked(versionCode = 100))
    }
}
