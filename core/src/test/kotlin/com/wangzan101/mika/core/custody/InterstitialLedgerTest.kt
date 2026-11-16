package com.wangzan101.mika.core.custody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class InterstitialLedgerTest {

    private fun jump(id: String, displayId: Int = 2, pkg: String = "com.miui.securitycenter", openedAt: Long = 1_000L) =
        Interstitial.JumpConfirm(
            id = id,
            displayId = displayId,
            taskId = 101,
            packageName = pkg,
            openedAtMs = openedAt,
            initiatorPackage = "com.initiator",
            targetPackage = "com.target",
        )

    @Test
    fun `open and resolve lifecycle tracks actor choice and timestamp`() {
        val ledger = InterstitialLedger()
        val opened = ledger.open(jump("i1", openedAt = 1_000L))
        assertTrue(opened.isOpen)
        assertFalse(opened.isResolved)

        val resolved = ledger.resolve("i1", InterstitialActor.AGENT, InterstitialChoice.ALLOW, atMs = 1_500L)!!
        assertTrue(resolved.isResolved)
        val state = resolved.state as LedgerState.Resolved
        assertEquals(InterstitialActor.AGENT, state.by)
        assertEquals(InterstitialChoice.ALLOW, state.choice)
        assertEquals(1_500L, state.at)
    }

    @Test
    fun `resolve preserves first terminal choice and only refines unknown`() {
        val ledger = InterstitialLedger()
        ledger.open(jump("i1", openedAt = 1_000L))
        ledger.resolve("i1", InterstitialActor.USER, InterstitialChoice.DENY, atMs = 1_200L)

        // 用户已明确拒绝后，Agent 或后续重复事件不得覆盖
        val second = ledger.resolve("i1", InterstitialActor.AGENT, InterstitialChoice.ALLOW, atMs = 1_300L)!!
        val secondState = second.state as LedgerState.Resolved
        assertEquals(InterstitialActor.USER, secondState.by)
        assertEquals(InterstitialChoice.DENY, secondState.choice)
        assertEquals(1_200L, secondState.at)

        // 若先记录为 UNKNOWN，允许后续补充具体结果
        ledger.open(jump("i2", openedAt = 2_000L))
        ledger.resolve("i2", InterstitialActor.AGENT, InterstitialChoice.UNKNOWN, atMs = 2_100L)
        val refined = ledger.resolve("i2", InterstitialActor.USER, InterstitialChoice.ALLOW, atMs = 2_500L)!!
        val refinedState = refined.state as LedgerState.Resolved
        assertEquals(InterstitialActor.USER, refinedState.by)
        assertEquals(InterstitialChoice.ALLOW, refinedState.choice)
        assertEquals(2_500L, refinedState.at)
    }

    @Test
    fun `attribution window accepts open entries and resolved within 10s boundary`() {
        val ledger = InterstitialLedger(
            openTtlMs = 120_000L,
            resolvedTtlMs = 30_000L,
            attributionResolvedWindowMs = 10_000L,
        )
        ledger.open(jump("open1", displayId = 2, openedAt = 1_000L))
        ledger.open(jump("res1", displayId = 2, openedAt = 2_000L))
        ledger.resolve("res1", InterstitialActor.USER, InterstitialChoice.ALLOW, atMs = 5_000L)

        // 恰好 10_000ms 时仍应包含 res1 与 open1（Open 优先）
        val at15s = ledger.activeOrRecentForDisplay(displayId = 2, nowMs = 15_000L)
        assertEquals(listOf("open1", "res1"), at15s.map { it.interstitial.id })

        // 10_001ms 时 res1 超出 R2 归因窗，仅保留 open1
        val at15sPlus1 = ledger.activeOrRecentForDisplay(displayId = 2, nowMs = 15_001L)
        assertEquals(listOf("open1"), at15sPlus1.map { it.interstitial.id })
    }

    @Test
    fun `ttl expiry filters lookups and pruneExpired removes stale entries`() {
        val ledger = InterstitialLedger(openTtlMs = 60_000L, resolvedTtlMs = 30_000L)
        ledger.open(jump("openOld", openedAt = 1_000L))
        ledger.open(jump("resOld", openedAt = 2_000L))
        ledger.resolve("resOld", InterstitialActor.AGENT, InterstitialChoice.ALLOW, atMs = 3_000L)

        // t=32_999ms: resOld 尚未达 30s TTL
        assertNotNull(ledger.get("resOld", nowMs = 32_999L))
        assertNotNull(ledger.get("openOld", nowMs = 32_999L))

        // t=33_000ms: resOld 刚好满 30s TTL，不再返回；openOld 仍在
        assertNull(ledger.get("resOld", nowMs = 33_000L))
        assertNotNull(ledger.get("openOld", nowMs = 33_000L))
        assertEquals(1, ledger.pruneExpired(33_000L))
        assertEquals(1, ledger.size())

        // t=61_000ms: openOld 满 60s TTL，被清理
        assertEquals(1, ledger.pruneExpired(61_000L))
        assertEquals(0, ledger.size())
    }

    @Test
    fun `resolveOpenForDisplay closes only matching open entries`() {
        val ledger = InterstitialLedger()
        ledger.open(jump("d2_a", displayId = 2, pkg = "android", openedAt = 100L))
        ledger.open(jump("d2_b", displayId = 2, pkg = "com.miui.securitycenter", openedAt = 200L))
        ledger.open(jump("d3_a", displayId = 3, pkg = "android", openedAt = 300L))

        val resolved = ledger.resolveOpenForDisplay(
            displayId = 2,
            packageName = "android",
            by = InterstitialActor.USER,
            choice = InterstitialChoice.ALLOW,
            atMs = 500L,
        )
        assertEquals(listOf("d2_a"), resolved.map { it.interstitial.id })
        assertTrue(ledger.get("d2_b")!!.isOpen)
        assertTrue(ledger.get("d3_a")!!.isOpen)
    }

    @Test
    fun `concurrent access remains thread safe`() {
        val ledger = InterstitialLedger(openTtlMs = 50_000L, resolvedTtlMs = 10_000L)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val errors = ArrayList<Throwable>()

        repeat(threads) { worker ->
            pool.submit {
                try {
                    for (i in 0 until 200) {
                        val id = "w${worker}_$i"
                        val now = 1_000L + i * 10L
                        ledger.open(jump(id, displayId = 2 + (worker % 2), openedAt = now))
                        if (i % 2 == 0) {
                            ledger.resolve(id, InterstitialActor.AGENT, InterstitialChoice.ALLOW, atMs = now + 5L)
                        }
                        ledger.activeOrRecentForDisplay(2, nowMs = now + 6L)
                        if (i % 20 == 0) {
                            ledger.pruneExpired(now + 100L)
                        }
                    }
                } catch (t: Throwable) {
                    synchronized(errors) { errors += t }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        pool.shutdown()
        assertTrue("Unexpected concurrent errors: $errors", errors.isEmpty())
    }
}
