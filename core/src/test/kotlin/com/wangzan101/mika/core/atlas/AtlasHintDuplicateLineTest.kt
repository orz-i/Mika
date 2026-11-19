package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.uistate.ScreenSig
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/** 列表页同一种入口存成好几条边时，提示里只出一行（拿最强的那条），别把位置浪费在重复上。 */
class AtlasHintDuplicateLineTest {
    private val pkg = "com.example.list"
    private val resultSig = ScreenSig(
        sigId = "ListActivity#aaaabbbbccccdddd",
        activity = "com.example.list/.ListActivity",
        fingerprint = 0xaaaabbbbccccdddduL.toLong(),
        anchors = listOf("搜索结果", "筛选"),
        resourceIds = listOf("rv_list"),
        packageName = pkg,
        shortActivity = "ListActivity",
    )
    private val detailSig = ScreenSig(
        sigId = "DetailActivity#1111222233334444",
        activity = "com.example.list/.DetailActivity",
        fingerprint = 0x1111222233334444L,
        anchors = listOf("立即沟通"),
        resourceIds = listOf("btn_chat"),
        packageName = pkg,
        shortActivity = "DetailActivity",
    )

    private fun tapEdge(ok: Int, weak: Int, ordinal: Int) = AtlasEdge(
        fromSigId = resultSig.sigId,
        toSigId = detailSig.sigId,
        tool = "phone_tap",
        target = Selector("ViewGroup", "", null, listOf(0, 100 * ordinal, 900, 100 * (ordinal + 1)), ordinal),
        ok = ok,
        weakOk = weak,
    )

    @Test
    fun `同一种入口只出一行`() {
        val atlas = AppAtlas(
            packageName = pkg,
            screens = listOf(
                AtlasScreen(sigId = resultSig.sigId, shortActivity = "ListActivity", title = "职位搜索结果页"),
                AtlasScreen(sigId = detailSig.sigId, shortActivity = "DetailActivity", title = "职位详情页"),
            ),
            edges = listOf(tapEdge(16, 0, 0), tapEdge(12, 0, 1), tapEdge(0, 3, 2)),
        )
        val snapshot = ScreenSnapshot(
            snapshotId = "s1",
            packageName = pkg,
            elements = emptyList(),
            width = 1080,
            height = 2400,
            activity = resultSig.activity,
            screenSig = resultSig,
        )

        val hint = AtlasHintPolicy().hintFor(snapshot, InMemoryAtlasStore().apply { put(atlas) })

        val lines = hint!!.text.lines().filter { it.startsWith("- ") }
        assertEquals(1, lines.size)
        assertEquals("- tap ViewGroup → 职位详情页 (成功 16)", lines.single())
    }
}
