package com.wangzan101.mika.core.atlas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同包内多个页面被命名成同一个标题时，标题要能分开，否则提示里分不清「从这里点进去是哪一个」。 */
class AtlasNamerDisambiguateTest {
    private fun screen(
        sigId: String,
        title: String,
        anchors: List<String>,
        source: String = AtlasScreen.TITLE_LLM,
    ) = AtlasScreen(
        sigId = sigId,
        activity = "com.example/.SearchActivity",
        shortActivity = "SearchActivity",
        anchors = anchors,
        title = title,
        titleSource = source,
    )

    private fun atlas(vararg screens: AtlasScreen) = AppAtlas(packageName = "com.example", screens = screens.toList())

    @Test
    fun `同名页面用各自独有的屏幕文字分开`() {
        val input = atlas(
            screen("SearchActivity#aaaa1111", "职位搜索页", listOf("历史搜索", "搜索")),
            screen("SearchActivity#bbbb2222", "职位搜索页", listOf("搜索", "热门搜索")),
            screen("SearchActivity#cccc3333", "职位搜索页", listOf("搜索结果", "立即沟通")),
        )

        val out = AtlasNamer.disambiguateTitles(input)
        val titles = out.screens.map { it.title }

        assertEquals(listOf("职位搜索页（历史搜索）", "职位搜索页（热门搜索）", "职位搜索页（搜索结果）"), titles)
        assertEquals(3, titles.toSet().size)
    }

    @Test
    fun `重复跑不会越接越长`() {
        val once = AtlasNamer.disambiguateTitles(
            atlas(
                screen("SearchActivity#aaaa1111", "职位搜索页", listOf("历史搜索", "搜索")),
                screen("SearchActivity#bbbb2222", "职位搜索页", listOf("搜索", "热门搜索")),
            ),
        )
        val twice = AtlasNamer.disambiguateTitles(once)

        assertEquals(once.screens.map { it.title }, twice.screens.map { it.title })
    }

    @Test
    fun `没有独有文字时退回短哈希`() {
        val out = AtlasNamer.disambiguateTitles(
            atlas(
                screen("SearchActivity#aaaa1111", "职位搜索页", listOf("搜索")),
                screen("SearchActivity#bbbb2222", "职位搜索页", listOf("搜索")),
            ),
        )

        assertTrue(out.screens.all { it.title.startsWith("职位搜索页 ") })
        assertEquals(2, out.screens.map { it.title }.toSet().size)
    }

    @Test
    fun `用户命名的标题原样保留`() {
        val input = atlas(
            screen("SearchActivity#aaaa1111", "我的搜索页", listOf("历史搜索"), AtlasScreen.TITLE_USER),
            screen("SearchActivity#bbbb2222", "我的搜索页", listOf("热门搜索"), AtlasScreen.TITLE_USER),
        )

        val out = AtlasNamer.disambiguateTitles(input)

        assertEquals(listOf("我的搜索页", "我的搜索页"), out.screens.map { it.title })
    }

    @Test
    fun `没有重名时不改动`() {
        val input = atlas(
            screen("SearchActivity#aaaa1111", "职位搜索页", listOf("历史搜索")),
            screen("JobActivity#bbbb2222", "职位详情页", listOf("立即沟通")),
        )

        assertEquals(input, AtlasNamer.disambiguateTitles(input))
    }

    @Test
    fun `带数字的屏幕文字不当后缀`() {
        val out = AtlasNamer.disambiguateTitles(
            atlas(
                screen("SearchActivity#aaaa1111", "职位搜索页", listOf("15-30K", "历史搜索")),
                screen("SearchActivity#bbbb2222", "职位搜索页", listOf("12-15K", "热门搜索")),
            ),
        )

        assertEquals(listOf("职位搜索页（历史搜索）", "职位搜索页（热门搜索）"), out.screens.map { it.title })
    }
}
