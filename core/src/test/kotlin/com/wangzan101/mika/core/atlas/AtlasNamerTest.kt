package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.replay.Selector
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AtlasNamerTest {
    private class StubModel(private val response: suspend (String) -> String) : ChatModel {
        override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8192, reasoning = false)
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            val userText = messages.first().parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
            return ModelReply(listOf(Part.Text(response(userText))), StopReason.EndTurn, null)
        }
    }

    private val searchCtrl = AtlasControl(
        selector = Selector("button", "搜索", "btn_search", listOf(0, 0, 100, 100), 0),
        used = 5,
    )
    private val sWithAct = AtlasScreen(
        sigId = "LauncherUI#0011223344556677",
        activity = "com.tencent.mm/.ui.LauncherUI",
        shortActivity = "LauncherUI",
        fingerprint = 0x0011223344556677L,
        anchors = listOf("微信", "通讯录", "发现", "我"),
        resourceIds = listOf("tab_chat", "btn_search"),
        title = "",
        titleSource = AtlasScreen.TITLE_ACTIVITY,
        controls = listOf(searchCtrl),
    )
    private val sNoAct = AtlasScreen(
        sigId = "#abcdef0123456789",
        activity = null,
        shortActivity = null,
        fingerprint = -0x543210fedcba9877L,
        anchors = listOf("首页"),
        title = "",
        titleSource = AtlasScreen.TITLE_ACTIVITY,
    )
    private val userNamed = sWithAct.copy(
        sigId = "SettingsUI#9999",
        shortActivity = "SettingsUI",
        title = "我的定制设置页",
        titleSource = AtlasScreen.TITLE_USER,
    )

    @Test
    fun namesScreensAndTopControlsWithLlmAndPreservesUserTitle() = runBlocking {
        val model = StubModel {
            """
            ```json
            [
              {"sigId":"LauncherUI#0011223344556677","title":"微信聊天首页","controls":[{"index":0,"meaning":"打开全局搜索"}]},
              {"sigId":"#abcdef0123456789","title":"未知页标题"},
              {"sigId":"SettingsUI#9999","title":"试图覆盖用户命名"},
              {"sigId":"injected_unknown","title":"编造页面"}
            ]
            ```
            """.trimIndent()
        }
        val atlas = AppAtlas("com.tencent.mm", "微信", screens = listOf(sWithAct, sNoAct, userNamed))
        val named = AtlasNamer(model).nameScreens(atlas)

        val n1 = named.findScreen(sWithAct.sigId)!!
        assertEquals("微信聊天首页", n1.title)
        assertEquals(AtlasScreen.TITLE_LLM, n1.titleSource)
        assertEquals("打开全局搜索", n1.controls[0].meaning)

        val n2 = named.findScreen(sNoAct.sigId)!!
        assertEquals("未知页标题", n2.title)
        assertEquals(AtlasScreen.TITLE_LLM, n2.titleSource)

        // 用户手工命名绝不被覆盖
        val n3 = named.findScreen(userNamed.sigId)!!
        assertEquals("我的定制设置页", n3.title)
        assertEquals(AtlasScreen.TITLE_USER, n3.titleSource)
    }

    @Test
    fun fallsBackToShortActivityOrShortHashWhenModelUnavailableFailsOrTimesOut() = runBlocking {
        val atlas = AppAtlas("com.tencent.mm", "微信", screens = listOf(sWithAct, sNoAct))

        // 模型为空
        val noModel = AtlasNamer(null).nameScreens(atlas)
        assertEquals("LauncherUI", noModel.findScreen(sWithAct.sigId)!!.title)
        assertEquals(AtlasScreen.TITLE_ACTIVITY, noModel.findScreen(sWithAct.sigId)!!.titleSource)
        assertEquals("#abcdef", noModel.findScreen(sNoAct.sigId)!!.title)

        // 模型抛出异常
        val failing = AtlasNamer(StubModel { error("network down") }).nameScreens(atlas)
        assertEquals("LauncherUI", failing.findScreen(sWithAct.sigId)!!.title)
        assertEquals("#abcdef", failing.findScreen(sNoAct.sigId)!!.title)

        // 模型超时
        val slow = AtlasNamer(StubModel { delay(500); "[]" }, timeoutMs = 30L).nameScreens(atlas)
        assertEquals("LauncherUI", slow.findScreen(sWithAct.sigId)!!.title)
        assertEquals("#abcdef", slow.findScreen(sNoAct.sigId)!!.title)

        // 模型返回空数组或垃圾文本
        val emptyReply = AtlasNamer(StubModel { "not json" }).nameScreens(atlas)
        assertEquals("LauncherUI", emptyReply.findScreen(sWithAct.sigId)!!.title)
        assertEquals("#abcdef", emptyReply.findScreen(sNoAct.sigId)!!.title)
    }

    @Test
    fun filtersSensitiveOutputsFromLlm() = runBlocking {
        val model = StubModel {
            """
            [
              {"sigId":"LauncherUI#0011223344556677","title":"密码 123456 页","controls":[{"index":0,"meaning":"sk-abcdef1234567890abcdef"}]}
            ]
            """.trimIndent()
        }
        val atlas = AppAtlas("com.tencent.mm", "微信", screens = listOf(sWithAct))
        val named = AtlasNamer(model).nameScreens(atlas)
        val screen = named.findScreen(sWithAct.sigId)!!
        assertEquals("LauncherUI", screen.title)
        assertEquals(AtlasScreen.TITLE_ACTIVITY, screen.titleSource)
        assertEquals("", screen.controls[0].meaning)
    }
}
