package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayMediaRoutesTest {
    private var now = 0L
    private var rows: List<DisplayMediaRoutes.Task>? = emptyList()
    private var playing: Boolean? = false
    private val displays = mutableSetOf(10, 11)
    private val opened = mutableListOf<Int>()
    private val closed = mutableListOf<Int>()
    private var canClose = true
    private var canOpen = true
    private var dedicated = true

    private val routes = DisplayMediaRoutes(
        ownDisplay = { it in displays },
        tasks = { rows },
        dedicatedUid = { _, user -> (user * 100_000 + UID).takeIf { dedicated } },
        playing = { playing },
        open = { uid ->
            if (!canOpen) null else {
                opened += uid
                AutoCloseable { check(canClose); closed += uid }
            }
        },
        now = { now },
    )

    private fun task(display: Int, user: Int? = 0, visible: Boolean = true) =
        DisplayMediaRoutes.Task(display, user, setOf(PKG), visible = visible)

    private fun mute(display: Int = 10, user: Int = 0, pre: Boolean = false) = routes.setMuted(display, PKG, user, true, pre)
    private fun release(display: Int = 10, user: Int = 0) = routes.setMuted(display, PKG, user, false, false)

    @Test fun preArmsThenKeepsTheSameRouteWhenTheAppAppears() {
        assertTrue(mute(pre = true))
        assertTrue(mute(pre = true))
        rows = listOf(task(10))
        assertTrue(mute())
        now = 10_000L
        assertTrue(routes.reconcile())
        assertEquals(listOf(UID), opened)
        assertTrue(closed.isEmpty())
    }

    @Test fun failedOrUnseenLaunchCannotLeaveTheAppRouted() {
        assertTrue(mute(pre = true))
        assertTrue(release())
        assertTrue(release())
        assertTrue(mute(pre = true))
        now = 20_000L
        assertFalse(routes.reconcile())
        assertFalse(mute())
        assertEquals(listOf(UID, UID), closed)
    }

    @Test fun movingToMainCleansUpEvenWhenThereIsStillAnotherVirtualTask() {
        rows = listOf(task(10))
        assertTrue(mute())
        rows = listOf(task(10), task(0))
        assertFalse(routes.reconcile())
        assertEquals(listOf(UID), closed)
        assertFalse(mute())
        rows = listOf(task(10))
        assertTrue(mute()) // 交回后能新建，不信上一条路由仍有效
        assertEquals(listOf(UID, UID), opened)
    }

    @Test fun unknownTaskStateUnknownUserAndPlayAlreadyOnMainFailSafe() {
        rows = null
        assertFalse(mute(pre = true))
        rows = listOf(task(0, user = null), task(10))
        assertFalse(mute())
        rows = listOf(task(0, visible = false))
        playing = true
        assertFalse(mute(pre = true))
        playing = null
        assertFalse(mute(pre = true))
        assertTrue(opened.isEmpty())
    }

    @Test fun fallbackDoesNotTouchNonOwnedDisplaysOrAUidUsedOnAnotherDisplay() {
        assertFalse(mute(display = 99, pre = true))
        rows = listOf(task(11))
        assertFalse(mute(pre = true))
        rows = emptyList()
        dedicated = false
        assertFalse(mute(pre = true))
        dedicated = true
        assertTrue(mute(pre = true))
        assertFalse(mute(display = 11, pre = true))
        assertEquals(listOf(UID), opened)
    }

    @Test fun userCopiesAndCleanupAreIndependent() {
        val copyUid = 10 * 100_000 + UID
        rows = listOf(task(10), task(11, user = 10), task(0, user = 11))
        assertTrue(mute())
        assertTrue(mute(display = 11, user = 10))
        routes.finish(10) { displays.remove(10) }
        assertEquals(listOf(UID), closed)
        assertTrue(routes.reconcile())
        routes.finish { displays.clear() }
        assertEquals(listOf(UID, copyUid), closed)
        assertFalse(routes.hasRoutes())
    }

    @Test fun finishingKeepsTheRouteUntilTheScreenIsGoneEvenIfFinishingThrows() {
        rows = listOf(task(10))
        assertTrue(mute())
        val result = runCatching {
            routes.finish(10) {
                rows = listOf(task(0))
                assertTrue(routes.reconcile())
                assertFalse(mute(pre = true))
                assertTrue(closed.isEmpty())
                error("display cleanup failed")
            }
        }
        assertTrue(result.isFailure)
        assertEquals(listOf(UID), closed)
        assertFalse(routes.hasRoutes())
    }

    @Test fun failedCloseCanBeRetriedButCannotBeTreatedAsAnActiveMute() {
        rows = listOf(task(10))
        assertTrue(mute())
        canClose = false
        assertFalse(release())
        assertFalse(mute())
        assertTrue(routes.reconcile())
        canClose = true
        assertFalse(routes.reconcile())
        assertEquals(listOf(UID), closed)
    }

    @Test fun unsupportedAudioRoutingIsNotReportedAsMuted() {
        rows = listOf(task(10))
        canOpen = false
        assertFalse(mute())
        assertFalse(routes.hasRoutes())
    }

    @Test fun uidMustBeAnAppFromTheRequestedUserAndExclusivelyOwnedByThePackage() {
        fun allowed(uid: Int, user: Int, vararg owners: String) = DisplayMediaRoutes.isDedicatedAppUid(uid, user, PKG, arrayOf(*owners))
        assertTrue(allowed(UID, 0, PKG))
        assertTrue(allowed(UID + 100_000, 1, PKG))
        assertFalse(allowed(UID, 1, PKG))
        assertFalse(allowed(UID, 0, PKG, "other.app"))
        assertFalse(allowed(UID, 0))
        assertFalse(allowed(1000, 0, PKG))
    }

    companion object {
        private const val PKG = "video.app"
        private const val UID = 10_123
    }
}
