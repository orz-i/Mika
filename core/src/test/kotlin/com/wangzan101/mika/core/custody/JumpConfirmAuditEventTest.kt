package com.wangzan101.mika.core.custody

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpConfirmAuditEventTest {

    @Test
    fun `RunEvent JumpConfirm round-trips via Json and preserves RunSummary`() {
        val event = RunEvent.JumpConfirm(
            atMs = 12_345L,
            initiator = "tv.danmaku.bili",
            target = "com.taobao.taobao",
            component = "com.miui.securitycenter/.JumpActivity",
            result = "degraded",
            degraded = true,
            reason = "window_protected",
            by = null,
            displayId = 2,
        )
        val encoded = RunEvent.encode(event)
        val decoded = RunEvent.decode(encoded)
        assertTrue(decoded is RunEvent.JumpConfirm)
        val restored = decoded as RunEvent.JumpConfirm
        assertEquals(event, restored)

        val summary = RunSummary(id = "r1", goal = "test", startedAtMs = 1_000L).with(restored)
        assertEquals(0, summary.steps)
    }
}
