package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.SubActions
import com.wangzan101.mika.core.tool.ToolExecution
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「连看看广告任务」的走法。
 *
 * 子动作用假的：`phone_query` 按屏上有没有对应文字回答，`phone_tap` 记下点了什么，
 * `phone_diagnose` 报告前台 App，`phone_back` 记一下。时间用的是测试调度器，`delay` 不真等，
 * 所以整条链路（含 15 秒正片、15 秒浏览）几毫秒就跑完。
 */
class PhoneWatchRewardVideosTest {

    private companion object {
        /** 广告跳出去之前待着的那个 App：这里拿起点读书当例子。 */
        const val HOST = "com.qidian.QDReader"
    }

    private fun match(label: String, clickableRef: String?, bounds: List<Int>? = listOf(10, 20, 110, 60)) =
        buildJsonObject {
            clickableRef?.let { put("actionRef", it); put("ref", it) }
            put("name", label)
            bounds?.let { put("bounds", JsonArray(it.map(::JsonPrimitive))) }
        }

    private fun reply(matches: List<JsonObject>) = ToolExecution(buildJsonObject {
        put("ok", true)
        put("snapshotId", "p1")
        put("total", matches.size)
        put("matches", JsonArray(matches))
    })

    /** 从参数里取查的文字。 */
    private fun text(argsJson: String): String =
        Json.parseToJsonElement(argsJson).jsonObject["text"]!!.jsonPrimitive.content

    @Test
    fun watchesTheAdThenClosesIt() = runTest {
        val dispatcher = toolDispatcher(FakeScreen())
        val taps = mutableListOf<String>()
        // 0 = 福利页（任务入口在）；1 = 广告盖上来（入口看不见了）；2 = 关掉之后回到福利页
        var phase = 0
        var app = HOST
        val fake = SubActions { call ->
            when (call.name) {
                "phone_query" -> when (text(call.argsJson)) {
                    "惊喜礼包" -> if (phase == 1) reply(emptyList()) else reply(listOf(match("惊喜礼包", null)))
                    else -> reply(emptyList())
                }
                "phone_tap", "phone_gesture" -> {
                    taps += call.argsJson
                    // 点任务格用的是位置（网页里的文字没有可点编号）
                    if (call.argsJson.contains("[60,40]")) phase = 1
                    // 点 CTA 之后人到了别的 App
                    if (call.argsJson.contains("[500,800]")) app = "com.tencent.mm"
                    ToolExecution(buildJsonObject { put("ok", true) })
                }
                "phone_diagnose" -> ToolExecution(buildJsonObject { put("ok", true); put("currentApp", app) })
                "phone_back" -> {
                    app = HOST
                    phase = 2
                    ToolExecution(buildJsonObject { put("ok", true) })
                }
                else -> ToolExecution(buildJsonObject { put("ok", false) })
            }
        }

        val r = dispatcher.execute(
            Part.ToolCall("c1", "phone_watch_reward_videos", """{"taskText":"惊喜礼包","rounds":1}"""),
            ActionPass(),
            sub = fake,
        )

        assertEquals("round_limit", r.content["stopped"]!!.jsonPrimitive.content)
        assertEquals(1, r.content["watched"]!!.jsonPrimitive.int)
        // 任务格、CTA：一下按位置，一下按 CTA 位置；回来之后网页上的入口已经在了，不用再点 ✕
        assertEquals(2, r.content["taps"]!!.jsonPrimitive.int)
        assertTrue(taps[0].contains("\"paths\""))
        assertTrue(taps[1].contains("[500,800]"))
        assertEquals(HOST, app)
    }

    @Test
    fun cancelsTheJumpPromptInsteadOfOpeningTheOtherApp() = runTest {
        val dispatcher = toolDispatcher(FakeScreen())
        val taps = mutableListOf<String>()
        var phase = 0
        // 确认框本身也被当成「前台换了 App」：广告那边已经记上跳转了
        var app = HOST
        val fake = SubActions { call ->
            when (call.name) {
                "phone_query" -> when (text(call.argsJson)) {
                    "惊喜礼包" -> if (phase == 1) reply(emptyList()) else reply(listOf(match("惊喜礼包", null)))
                    // 「即将打开微信」那种确认框：取消和打开同时在
                    "取消" -> reply(listOf(match("取消", "@cancel")))
                    "打开" -> reply(listOf(match("打开", "@open")))
                    else -> reply(emptyList())
                }
                "phone_tap", "phone_gesture" -> {
                    taps += call.argsJson
                    if (call.argsJson.contains("[60,40]")) phase = 1
                    if (call.argsJson.contains("[500,800]")) app = "com.hihonor.intentresolver"
                    if (call.argsJson.contains("@cancel")) { app = HOST; phase = 2 }
                    ToolExecution(buildJsonObject { put("ok", true) })
                }
                "phone_diagnose" -> ToolExecution(buildJsonObject { put("ok", true); put("currentApp", app) })
                else -> ToolExecution(buildJsonObject { put("ok", false) })
            }
        }

        val r = dispatcher.execute(
            Part.ToolCall("c1", "phone_watch_reward_videos", """{"taskText":"惊喜礼包","rounds":1}"""),
            ActionPass(),
            sub = fake,
        )

        assertEquals(1, r.content["watched"]!!.jsonPrimitive.int)
        // 停在确认框上等够时间，然后按的是「取消」，全程没按「打开」，也没按返回
        assertTrue(taps.any { it.contains("\"ref\":\"@cancel\"") })
        assertTrue(taps.none { it.contains("@open") })
        val rounds = r.content["rounds"]!!.jsonArray.map { it.jsonObject["do"]!!.jsonPrimitive.content }
        assertTrue(rounds.contains("dialog"))
        assertTrue(rounds.contains("dismiss"))
    }

    /**
     * 确认框不换前台 App 的那种：有的系统上「即将打开微信」是画在起点自己窗口里的，
     * `currentApp` 一直报起点，屏上已经不是福利页了。
     *
     * 按前台判断会漏掉这一次跳转，接着去关广告，结果弹出「现在退出就没有奖励哦」。
     * 所以认框要早于认前台：这个用例里前台从头到尾没变，也要走 dialog 那条路。
     */
    @Test
    fun keepsTheUserInsideWhenTheConfirmDialogDoesNotChangeTheForeground() = runTest {
        val dispatcher = toolDispatcher(FakeScreen())
        val taps = mutableListOf<String>()
        var phase = 0
        var dialogUp = false
        val fake = SubActions { call ->
            when (call.name) {
                "phone_query" -> when (text(call.argsJson)) {
                    "惊喜礼包" -> if (phase == 1) reply(emptyList()) else reply(listOf(match("惊喜礼包", null)))
                    "取消" -> if (dialogUp) reply(listOf(match("取消", "@cancel"))) else reply(emptyList())
                    "打开" -> if (dialogUp) reply(listOf(match("打开", "@open"))) else reply(emptyList())
                    else -> reply(emptyList())
                }
                "phone_tap", "phone_gesture" -> {
                    taps += call.argsJson
                    when {
                        call.argsJson.contains("[500,800]") -> dialogUp = true
                        call.argsJson.contains("@cancel") -> { dialogUp = false; phase = 2 }
                        call.argsJson.contains("[60,40]") -> phase = 1
                    }
                    ToolExecution(buildJsonObject { put("ok", true) })
                }
                "phone_diagnose" -> ToolExecution(buildJsonObject { put("ok", true); put("currentApp", HOST) })
                else -> ToolExecution(buildJsonObject { put("ok", false) })
            }
        }

        val r = dispatcher.execute(
            Part.ToolCall("c1", "phone_watch_reward_videos", """{"taskText":"惊喜礼包","rounds":1}"""),
            ActionPass(),
            sub = fake,
        )

        assertEquals("round_limit", r.content["stopped"]!!.jsonPrimitive.content)
        assertEquals(1, r.content["watched"]!!.jsonPrimitive.int)
        assertTrue(taps.any { it.contains("\"ref\":\"@cancel\"") })
        assertTrue(taps.none { it.contains("@open") })
        val rounds = r.content["rounds"]!!.jsonArray.map { it.jsonObject["do"]!!.jsonPrimitive.content }
        assertTrue(rounds.contains("dialog"))
        assertTrue(rounds.contains("dismiss"))
        assertTrue(!rounds.contains("back"))
    }

    /**
     * 广告里的权限授权弹窗（「允许 / 拒绝」那种）：按「拒绝」，不按「允许」。
     * 广告没有理由要到通知、位置、剪贴板这类权限。
     */
    @Test
    fun refusesPermissionDialogInsteadOfGrantingIt() = runTest {
        val dispatcher = toolDispatcher(FakeScreen())
        val taps = mutableListOf<String>()
        var phase = 0
        var dialogUp = false
        val fake = SubActions { call ->
            when (call.name) {
                "phone_query" -> when (text(call.argsJson)) {
                    "惊喜礼包" -> if (phase == 1) reply(emptyList()) else reply(listOf(match("惊喜礼包", null)))
                    "拒绝" -> if (dialogUp) reply(listOf(match("拒绝", "@deny"))) else reply(emptyList())
                    "允许" -> if (dialogUp) reply(listOf(match("允许", "@allow"))) else reply(emptyList())
                    else -> reply(emptyList())
                }
                "phone_tap", "phone_gesture" -> {
                    taps += call.argsJson
                    when {
                        call.argsJson.contains("[500,800]") -> dialogUp = true
                        call.argsJson.contains("@deny") -> { dialogUp = false; phase = 2 }
                        call.argsJson.contains("[60,40]") -> phase = 1
                    }
                    ToolExecution(buildJsonObject { put("ok", true) })
                }
                "phone_diagnose" -> ToolExecution(buildJsonObject { put("ok", true); put("currentApp", HOST) })
                else -> ToolExecution(buildJsonObject { put("ok", false) })
            }
        }

        val r = dispatcher.execute(
            Part.ToolCall("c1", "phone_watch_reward_videos", """{"taskText":"惊喜礼包","rounds":1}"""),
            ActionPass(),
            sub = fake,
        )

        assertEquals(1, r.content["watched"]!!.jsonPrimitive.int)
        assertTrue(taps.any { it.contains("\"ref\":\"@deny\"") })
        assertTrue(taps.none { it.contains("@allow") })
    }

    /**
     * 真机上踩到的那个坑：屏上的「已领取」被「领取」这个候选词模糊命中，
     * 于是每一轮都去点灰按钮，`claimed` 涨到 3、广告一次都没播。
     *
     * 这里让「领取」只命中一个 `已领取`：工具必须放过它，转去找任务格。
     */
    @Test
    fun doesNotTapAnAlreadyClaimedButton() = runTest {
        val dispatcher = toolDispatcher(FakeScreen())
        val taps = mutableListOf<String>()
        var phase = 0
        val fake = SubActions { call ->
            when (call.name) {
                "phone_query" -> when (text(call.argsJson)) {
                    "领取" -> reply(listOf(match("已领取", "@gray")))
                    "惊喜礼包" -> if (phase == 1) reply(emptyList()) else reply(listOf(match("惊喜礼包", null)))
                    else -> reply(emptyList())
                }
                "phone_tap", "phone_gesture" -> {
                    taps += call.argsJson
                    when {
                        call.argsJson.contains("[500,800]") -> phase = 2
                        call.argsJson.contains("[60,40]") -> phase = 1
                    }
                    ToolExecution(buildJsonObject { put("ok", true) })
                }
                "phone_diagnose" -> ToolExecution(buildJsonObject { put("ok", true); put("currentApp", HOST) })
                else -> ToolExecution(buildJsonObject { put("ok", false) })
            }
        }

        val r = dispatcher.execute(
            Part.ToolCall("c1", "phone_watch_reward_videos", """{"taskText":"惊喜礼包","rounds":1}"""),
            ActionPass(),
            sub = fake,
        )

        // 灰按钮一下都没碰，广告照常走完
        assertTrue(taps.none { it.contains("@gray") })
        assertEquals(0, r.content["claimed"]!!.jsonPrimitive.int)
        assertEquals(1, r.content["watched"]!!.jsonPrimitive.int)
    }

    /**
     * 真机上第二个坑：阶梯上那行「惊喜礼包」是标签，点下去页面不动，广告一次都不出来。
     * 能叫出广告的是旁边的「去完成」。标签点不出广告时要退到它，而不是直接报 `ad_not_shown`。
     */
    @Test
    fun fallsBackToTheGoButtonWhenTheLabelDoesNothing() = runTest {
        val dispatcher = toolDispatcher(FakeScreen())
        val taps = mutableListOf<String>()
        var phase = 0
        val fake = SubActions { call ->
            when (call.name) {
                "phone_query" -> when (text(call.argsJson)) {
                    "惊喜礼包" -> if (phase == 1) reply(emptyList()) else reply(listOf(match("惊喜礼包", "@label")))
                    "去完成" -> reply(listOf(match("去完成", "@go")))
                    else -> reply(emptyList())
                }
                "phone_tap", "phone_gesture" -> {
                    taps += call.argsJson
                    when {
                        // 标签点了什么也没发生，phase 不动
                        call.argsJson.contains("@label") -> Unit
                        call.argsJson.contains("@go") -> phase = 1
                        call.argsJson.contains("[500,800]") -> phase = 2
                    }
                    ToolExecution(buildJsonObject { put("ok", true) })
                }
                "phone_diagnose" -> ToolExecution(buildJsonObject { put("ok", true); put("currentApp", HOST) })
                else -> ToolExecution(buildJsonObject { put("ok", false) })
            }
        }

        val r = dispatcher.execute(
            Part.ToolCall("c1", "phone_watch_reward_videos", """{"taskText":"惊喜礼包","rounds":1}"""),
            ActionPass(),
            sub = fake,
        )

        assertEquals("round_limit", r.content["stopped"]!!.jsonPrimitive.content)
        assertEquals(1, r.content["watched"]!!.jsonPrimitive.int)
        assertTrue(taps.any { it.contains("@go") })
        val rounds = r.content["rounds"]!!.jsonArray.map { it.jsonObject["do"]!!.jsonPrimitive.content }
        assertTrue(rounds.contains("entry"))
    }

    /**
     * CTA 位置按屏上的字找，不按写死的坐标：同一批素材里「查看商品」在屏中部（y≈32%），
     * 「去微信看看」在底部（y≈82%）。真机上写死 (500,800) 换算成像素落在广告底部空白，
     * 三次都没点中，最后弹了「现在退出就没有奖励哦」。
     */
    @Test
    fun tapsTheCtaWhereTheTextActuallyIs() = runTest {
        val dispatcher = toolDispatcher(FakeScreen())
        val taps = mutableListOf<String>()
        var phase = 0
        var app = HOST
        val fake = SubActions { call ->
            when (call.name) {
                "phone_query" -> when (text(call.argsJson)) {
                    "惊喜礼包" -> if (phase == 1) reply(emptyList()) else reply(listOf(match("惊喜礼包", "@e1")))
                    else -> reply(emptyList())
                }
                // 广告页无障碍树是空的，只有 OCR 读出来的字；center 本身就是千分比
                "phone_observe" -> ToolExecution(buildJsonObject {
                    put("ok", true)
                    put("visibleText", JsonArray(listOf(buildJsonObject {
                        put("text", "查看商品")
                        put("center", JsonArray(listOf(JsonPrimitive(497), JsonPrimitive(876))))
                    })))
                })
                "phone_tap", "phone_gesture" -> {
                    taps += call.argsJson
                    when {
                        call.argsJson.contains("@e1") -> phase = 1
                        call.argsJson.contains("[497,876]") -> { app = "com.tencent.mm"; phase = 2 }
                    }
                    ToolExecution(buildJsonObject { put("ok", true) })
                }
                "phone_diagnose" -> ToolExecution(buildJsonObject { put("ok", true); put("currentApp", app) })
                "phone_back" -> { app = HOST; ToolExecution(buildJsonObject { put("ok", true) }) }
                else -> ToolExecution(buildJsonObject { put("ok", false) })
            }
        }

        val r = dispatcher.execute(
            Part.ToolCall("c1", "phone_watch_reward_videos", """{"taskText":"惊喜礼包","rounds":1}"""),
            ActionPass(),
            sub = fake,
        )

        assertTrue(taps.any { it.contains("[497,876]") })
        assertEquals(1, r.content["watched"]!!.jsonPrimitive.int)
    }

    @Test
    fun stopsWhenTheAdNeverShowsUp() = runTest {
        val dispatcher = toolDispatcher(FakeScreen())
        var taps = 0
        val fake = SubActions { call ->
            when (call.name) {
                "phone_query" -> if (text(call.argsJson) == "惊喜礼包") reply(listOf(match("惊喜礼包", "@e1"))) else reply(emptyList())
                "phone_tap", "phone_gesture" -> { taps++; ToolExecution(buildJsonObject { put("ok", true) }) }
                else -> ToolExecution(buildJsonObject { put("ok", false) })
            }
        }

        val r = dispatcher.execute(
            Part.ToolCall("c1", "phone_watch_reward_videos", """{"taskText":"惊喜礼包","rounds":3}"""),
            ActionPass(),
            sub = fake,
        )

        assertEquals("ad_not_shown", r.content["stopped"]!!.jsonPrimitive.content)
        assertEquals(0, r.content["watched"]!!.jsonPrimitive.int)
        // 只点了任务格那一下，没在空屏上乱点
        assertEquals(1, taps)
    }

    @Test
    fun stopsWhenTheTaskEntryIsNotThere() = runTest {
        val dispatcher = toolDispatcher(FakeScreen())
        val fake = SubActions { call ->
            if (call.name == "phone_query") reply(emptyList()) else ToolExecution(buildJsonObject { put("ok", false) })
        }

        val r = dispatcher.execute(
            Part.ToolCall("c1", "phone_watch_reward_videos", """{"taskText":"不存在的任务","rounds":2}"""),
            ActionPass(),
            sub = fake,
        )

        assertEquals("task_not_found", r.content["stopped"]!!.jsonPrimitive.content)
        assertEquals(0, r.content["taps"]!!.jsonPrimitive.int)
    }

    @Test
    fun claimsOnlyWithoutATaskText() = runTest {
        val dispatcher = toolDispatcher(FakeScreen())
        // 只有一个奖可领；领掉之后就没有了
        var claimable = true
        val fake = SubActions { call ->
            when (call.name) {
                "phone_query" -> if (text(call.argsJson) == "领取" && claimable) reply(listOf(match("领取", "@e9"))) else reply(emptyList())
                "phone_tap", "phone_gesture" -> { claimable = false; ToolExecution(buildJsonObject { put("ok", true) }) }
                else -> ToolExecution(buildJsonObject { put("ok", false) })
            }
        }

        val r = dispatcher.execute(Part.ToolCall("c1", "phone_watch_reward_videos", "{}"), ActionPass(), sub = fake)

        assertEquals(1, r.content["claimed"]!!.jsonPrimitive.int)
        assertEquals(0, r.content["watched"]!!.jsonPrimitive.int)
        // 领完一轮后没有任务入口可点
        assertEquals("nothing_to_watch", r.content["stopped"]!!.jsonPrimitive.content)
    }
}
