package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.reward.RewardTaskMove
import com.wangzan101.mika.core.reward.RewardTaskPlan
import com.wangzan101.mika.core.reward.RewardTaskState
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.SubActions
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.uistate.session
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 默认当作「领奖/收下」的按钮文字，按先试的顺序。
 *
 * `知道了` 是领完奖那张「恭喜获得…」弹窗的关闭键——少了它，弹窗会一直盖在网页上，
 * 后面每一下都点在弹窗外面的遮罩上。
 */
private const val DEFAULT_CLAIM_TEXTS = "领取,立即领奖,知道了,开心收下,确定"

/** 两轮查询之间歇多久。 */
private const val POLL_MS = 1_200L

/** 广告最多等这么大工夫盖上来；按轮询次数算，不按墙上时间（测试里的 `delay` 不真等）。 */
private const val AD_SHOW_POLLS = 10

/** 正片放完要等这么久：起点这批激励广告都是 15 秒。 */
private const val VIDEO_POLLS = 13

/** 点进第三方之后要停留这么久：广告上写的是「点击广告浏览 15 秒 即可领取奖励」。 */
private const val BROWSE_POLLS = 13

/** 点一下 CTA 之后等这么久，看有没有离开当前 App。 */
private const val CTA_SETTLE_MS = 2_500L

/** 点一下 ✕ 之后等这么久，看回没回到网页。 */
private const val CLOSE_SETTLE_MS = 2_000L

/** 结算页 CTA（「去微信看看」「立即下载」之类）的千分比位置，从上往下试。 */
private val CTA_POINTS = listOf(500 to 800, 500 to 770, 500 to 835)

/**
 * 广告里那个「点了就走」的按钮，按优先级。
 *
 * 位置不能写死：这批素材的 CTA 有的在页面中部（「查看商品」在 y≈32%），
 * 有的在底部（「去微信看看」在 y≈82%），写死坐标只会在空白处戳。
 * 所以先读屏上的字，按文字找它的中心；`去浏览` 是挽留页上的同一个按钮。
 */
private val CTA_TEXTS = listOf("去浏览", "去微信看看", "查看商品", "立即购买", "立即下载", "了解详情", "查看详情", "去逛逛")

/** 广告右上角那个 ✕ 的千分比位置。 */
private const val CLOSE_X = 923
private const val CLOSE_Y = 115

/**
 * 跳转确认框/授权弹窗上「不走」这一侧的按钮文字；这里点下去就撤销跳转，人不会被带进别人家 App。
 *
 * `拒绝`/`禁止`/`仅此一次` 是权限授权弹窗那一侧：授权框在广告流程里出现的次数不比跳转框少，
 * 一旦点了「允许」，授予的是「打开微信」之外的运行时权限，没有理由在广告里给出去。
 * 顺序有意：先按「取消」，实在没有这词才按「拒绝」。
 */
private const val DISMISS_TEXTS = "取消,不允许,拒绝,禁止,暂不允许,暂不,仅此一次,以后再说"

/** 跳转确认框上「确认跳转」那一侧的文字；只有它也在这块屏上，才认定这是个确认框。 */
private const val ACCEPT_TEXTS = "打开,确定,允许,继续"

/** 「现在退出就没有奖励哦」这种挽留页上的「就是要走」按钮。 */
private const val LEAVE_TEXTS = "放弃奖励,残忍离开"

/**
 * 真正的任务入口。福利中心的阶梯上写着奖励名字（`惊喜礼包`），但那是标签，
 * 点它页面不动；能叫出广告的是旁边那个红字「去完成」。标签点不出广告时退到这几个词。
 */
private const val ENTRY_TEXTS = "去完成,立即做任务,做任务"

/** 一次运行里给子动作编的号，作用域仅限本次调用。 */
private class Steps {
    var n = 0
}

/**
 * 连看看广告领奖：点任务入口 → 看完一次激励广告 → 收下奖励 → 再来一轮。
 *
 * 这是「真实播放」那条路（`reverse/qidian/VIDEO_TASK.md` 的方案 A）：不伪造任何回调，
 * 用和手点一样的方式把流程走完。起点福利中心的「激励任务」是一个任务九个阶梯，
 * 每看一个广告 `Process` 加一，所以这里按轮次走，每轮处理一个阶梯。
 *
 * 这批广告要的是「点 CTA 之后在外面待够 15 秒」——光等、直接关，弹的是「现在退出就没有奖励哦」。
 * 好在「即将打开微信」这种跳转确认框本身就算一次跳转：停在框上把时间耗掉再按取消，奖励照发，
 * 人也不会被带进微信。所以这里优先认确认框，真跳出去了才按返回。
 *
 * 判定不靠屏幕上的进度条：抓包开着时读任务接口和 `finishWatch` 的响应，`RewardList`
 * 非空才算这阶真的发下来了；没开抓包就只看「广告关掉之后网页回来了没有」。
 *
 * 每一步都是经执行入口的子动作（`phone_query`、`phone_tap`、`phone_back`、`phone_diagnose`），
 * 所以取消、状态记录、屏幕守卫和单步操作完全一致；本工具自己不动界面。
 */
internal object PhoneWatchRewardVideos : PhoneTool {
    override val verb = Verb.Collect
    override val risk = Risk.Write
    override val composite = true
    override val label = "连看看广告任务"
    override val yieldsToSteer = true

    override val def = ToolDef(
        "phone_watch_reward_videos",
        Prompts.tool("phone_watch_reward_videos"),
        mapOf(
            "taskText" to str("Text on the task entry to tap, comma separated. Empty: only claim what is already finished."),
            "claimText" to str("Claim/dismiss texts, comma separated (default $DEFAULT_CLAIM_TEXTS)."),
            "rounds" to int("Maximum rounds, 1-20 (default 9).", 1, 20),
        ),
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ctx.session.textOnly { run(a, ctx) }

    private suspend fun run(a: Args, ctx: ToolContext): ToolExecution {
        val sub = ctx.sub ?: return failure("actions_unavailable")
        val steps = Steps()
        val taskTexts = split(a.str("taskText", max = 120))
        val claimTexts = split(a.str("claimText", max = 200)).ifEmpty { split(DEFAULT_CLAIM_TEXTS) }
        val rounds = a.int("rounds", 1, 20) ?: 9
        // 现在前台是谁：从福利页出发时它就是起点，广告把屏带走了才不是它
        val host = foreground(sub, ctx, steps)

        val trace = mutableListOf<JsonObject>()
        var taps = 0
        var watched = 0
        var claimed = 0
        var rewards = 0
        var stopped = "round_limit"
        var state: RewardTaskState? = apiState(ctx)
        // 点过的领奖按钮。灰按钮点下去没反应，靠 key 记住它，别每一轮都去点同一个
        val claimedKeys = mutableSetOf<String>()

        for (n in 1..rounds) {
            // 第一轮不看接口说的,那一屏可能是几分钟前甚至昨天的:只在自己走过一轮之后再拿它判「今天走完了」
            if (n > 1 && state != null && RewardTaskPlan.nextMove(state) == RewardTaskMove.Done) {
                stopped = "done"
                break
            }
            // 1. 已达成的阶梯先领掉，别堆着。
            //    「已领取」里含「领取」，光按文字查会把灰按钮当成本轮要点的目标，所以要按元素自己的字排掉。
            val claim = find(sub, ctx, steps, claimTexts) { it.key !in claimedKeys && !it.name.contains("已领") }
            if (claim != null) {
                if (!tap(sub, ctx, steps, claim)) { stopped = "tap_failed"; break }
                claimedKeys += claim.key
                taps++
                claimed++
                trace += round(n, "claim", claim.name)
                continue
            }
            // 2. 点任务入口，把广告叫出来
            if (taskTexts.isEmpty()) { stopped = "nothing_to_watch"; break }
            val cell = find(sub, ctx, steps, taskTexts)
            if (cell == null) { stopped = "task_not_found"; break }
            if (!tap(sub, ctx, steps, cell)) { stopped = "tap_failed"; break }
            taps++
            trace += round(n, "task", cell.name)

            // 3. 走完这一次广告
            var ad = watch(sub, ctx, steps, taskTexts, host, n, trace)
            taps += ad.taps
            if (ad.outcome == "no_ad") {
                // 阶梯上那行字只是标签，真正能叫出广告的是旁边的「去完成」。
                // 真机上点「惊喜礼包」那几个字，页面一动不动，一次广告都没出来。
                val entry = find(sub, ctx, steps, split(ENTRY_TEXTS))
                if (entry != null && tap(sub, ctx, steps, entry)) {
                    taps++
                    trace += round(n, "entry", entry.name)
                    ad = watch(sub, ctx, steps, taskTexts, host, n, trace)
                    taps += ad.taps
                }
            }
            if (ad.outcome == "no_ad") { stopped = "ad_not_shown"; break }
            if (ad.outcome != "closed") { stopped = ad.outcome; break }
            watched++
            // 4. 抓包开着时核对这一阶到底发没发
            if (ctx.network != null) rewards += finishWatchRewards(ctx)
            state = apiState(ctx)
        }

        return ToolExecution(buildJsonObject {
            put("ok", stopped == "round_limit" || stopped == "done")
            put("watched", watched)
            put("claimed", claimed)
            put("taps", taps)
            if (ctx.network != null) put("verifiedRewards", rewards)
            put("stopped", stopped)
            state?.let {
                put("apiProcess", it.process)
                put("apiTotal", it.total)
                put("apiClaimable", it.claimable)
            }
            put("rounds", JsonArray(trace))
        })
    }

    /**
     * 走完一次广告：等它盖上来 → 放完 → 点 CTA 待够时长 → 回来 → 关掉。
     *
     * 广告的窗口读屏看不到（起点这批广告是广告 SDK 自己画的，里面一个可读的文字节点都没有），
     * 所以全程不看广告里的字，只看三样：[taskTexts] 还在不在（判广告盖上来没有、关掉没有）、
     * 前台 App 是谁（判 CTA 点出去没有）、点完 ✕ 之后网页回来了没有。
     *
     * 「点 CTA 待够时长」这步不能省：这批广告写的是「点击广告浏览 15 秒 即可领取奖励」，
     * 光等不放直接关，弹的是「现在退出就没有奖励哦」。读不出前台 App 时不敢瞎点，退回到只关掉。
     */
    private suspend fun watch(
        sub: SubActions,
        ctx: ToolContext,
        steps: Steps,
        taskTexts: List<String>,
        host: String?,
        n: Int,
        trace: MutableList<JsonObject>,
    ): AdRun {
        var taps = 0
        // 1. 等广告盖上来：网页上的任务入口看不见了，就是它把屏占住了
        var shown = false
        var polls = 0
        while (polls++ < AD_SHOW_POLLS) {
            if (find(sub, ctx, steps, taskTexts) == null) { shown = true; break }
            delay(POLL_MS)
        }
        if (!shown) return AdRun("no_ad", taps)

        // 2. 让正片放完。放完之前点什么都没用，倒计时没走完不给奖励
        repeat(VIDEO_POLLS) { delay(POLL_MS) }

        // 3. 点 CTA，让广告记下「跳出去了」，再待够时长
        var target: String? = null
        var dialog: Hit? = null
        if (host != null) {
            // 先按屏上的字找 CTA；读不到字才退回固定位置戳
            val cta = pick(screenWords(sub, ctx, steps), CTA_TEXTS)
            val points = if (cta != null) listOf(cta.x to cta.y) else CTA_POINTS
            for ((x, y) in points) {
                if (!tapAt(sub, ctx, steps, x, y)) return AdRun("tap_failed", taps)
                taps++
                trace += round(n, "cta", cta?.text?.take(20) ?: "$x,$y")
                delay(CTA_SETTLE_MS)
                // 先认框、再看前台：确认框弹出来的这一刻，广告那边已经把「用户点了跳转」记上了，
                // 而人还留在原地。有的系统确认框会换前台（变成 intentresolver），有的不换，
                // 所以不能只靠「前台 App 变了」来判断这次跳转成没成立——认框更早也更准。
                dialog = jumpDialog(sub, ctx, steps)
                if (dialog != null) break
                val app = foreground(sub, ctx, steps)
                if (app != null && app != host) { target = app; break }
            }
        }
        if (dialog != null) {
            // 停在框上把时长耗掉，最后按「取消」：广告记到了一次跳转点击，人一次都没进微信。
            // 这条路上不产生任何跨 App 的调用，对方也拿不到这一次唤起。
            trace += round(n, "dialog", "confirm")
            repeat(BROWSE_POLLS) { delay(POLL_MS) }
            if (tap(sub, ctx, steps, dialog)) {
                taps++
                trace += round(n, "dismiss", dialog.name)
                delay(CTA_SETTLE_MS)
            }
            // 按完还在别人家（少数 ROM 的框点了直接放行），按返回把人带回来
            var backs = 0
            while (backs < 3 && foreground(sub, ctx, steps) != host) {
                if (!back(sub, ctx, steps)) return AdRun("tap_failed", taps)
                backs++
                delay(CTA_SETTLE_MS)
            }
            if (backs > 0) trace += round(n, "back", "$backs")
            if (foreground(sub, ctx, steps) != host) return AdRun("stuck_outside", taps)
        } else if (target != null) {
            trace += round(n, "jump", target)
            repeat(BROWSE_POLLS) { delay(POLL_MS) }
            // 已经没有框可按了，人是真的在别人家 App 里：只按返回，不点任何按钮、不授权任何东西，
            // 也不去读那边的界面。停留的这几秒里对方最多记到一次唤起。
            var backs = 0
            while (backs < 3 && foreground(sub, ctx, steps) != host) {
                if (!back(sub, ctx, steps)) return AdRun("tap_failed", taps)
                backs++
                delay(CTA_SETTLE_MS)
            }
            trace += round(n, "back", "$backs")
            // 退不回来就别接着点了：后面每一下都会落在别人家的界面上
            if (foreground(sub, ctx, steps) != host) return AdRun("stuck_outside", taps)
        }

        // 4. 关掉广告，回到网页。关闭按钮同样按字找（「点击退出关闭视频」），
        //    写死的右上角在这批素材上偏了一截，之前是蒙中的。
        repeat(2) {
            if (find(sub, ctx, steps, taskTexts) != null) return AdRun("closed", taps)
            val close = pick(screenWords(sub, ctx, steps), listOf("退出关闭", "关闭"))
            val cx = close?.x ?: CLOSE_X
            val cy = close?.y ?: CLOSE_Y
            if (!tapAt(sub, ctx, steps, cx, cy)) return AdRun("tap_failed", taps)
            taps++
            trace += round(n, "close", close?.text?.take(20) ?: "$cx,$cy")
            delay(CLOSE_SETTLE_MS)
        }
        if (find(sub, ctx, steps, taskTexts) != null) return AdRun("closed", taps)
        // 4.1 还盖着、屏上又是「现在退出就没有奖励哦」：刚才那下 CTA 没算数。
        //     挽留页上那个「点击去浏览」就是同一个按钮，再点一次、待够时长，比直接放弃多一次机会。
        val again = pick(screenWords(sub, ctx, steps), CTA_TEXTS)
        if (again != null && tapAt(sub, ctx, steps, again.x, again.y)) {
            taps++
            trace += round(n, "retry", again.text.take(20))
            delay(CTA_SETTLE_MS)
            val app = foreground(sub, ctx, steps)
            if (app != null && app != host) {
                // 这一次真跳出去了：待够时长，只按返回，不在对方界面点任何东西
                repeat(BROWSE_POLLS) { delay(POLL_MS) }
                var backs = 0
                while (backs < 3 && foreground(sub, ctx, steps) != host) {
                    if (!back(sub, ctx, steps)) return AdRun("tap_failed", taps)
                    backs++
                    delay(CTA_SETTLE_MS)
                }
                trace += round(n, "back", "$backs")
                if (foreground(sub, ctx, steps) != host) return AdRun("stuck_outside", taps)
            } else {
                repeat(BROWSE_POLLS) { delay(POLL_MS) }
            }
            repeat(2) {
                if (find(sub, ctx, steps, taskTexts) != null) return AdRun("closed", taps)
                if (!tapAt(sub, ctx, steps, CLOSE_X, CLOSE_Y)) return AdRun("tap_failed", taps)
                taps++
                trace += round(n, "close", "$CLOSE_X,$CLOSE_Y")
                delay(CLOSE_SETTLE_MS)
            }
            if (find(sub, ctx, steps, taskTexts) != null) return AdRun("closed", taps)
        }
        // 4.2 还是关不掉：认了这次没拿到，把屏腾出来，不然下面几轮全点在广告上。
        //     挽留页的按钮在无障碍树里常常没有，靠 OCR 找「放弃」。
        val leaveWord = pick(screenWords(sub, ctx, steps), listOf("放弃"))
        if (leaveWord != null && tapAt(sub, ctx, steps, leaveWord.x, leaveWord.y)) {
            taps++
            trace += round(n, "leave", leaveWord.text.take(20))
            delay(CLOSE_SETTLE_MS)
        } else {
            val leave = find(sub, ctx, steps, split(LEAVE_TEXTS))
            if (leave != null && tap(sub, ctx, steps, leave)) {
                taps++
                trace += round(n, "leave", leave.name)
                delay(CLOSE_SETTLE_MS)
            }
        }
        return AdRun(if (find(sub, ctx, steps, taskTexts) != null) "closed" else "ad_timeout", taps)
    }

    /** 一次广告的结果：[outcome] 见 [watch] 的返回说明，[taps] 是这一趟按了几次。 */
    private data class AdRun(val outcome: String, val taps: Int)

    /**
     * 点屏幕上的一个千分比位置（`x`/`y` 都是 0-999），广告里那些读不到字的地方只能这么点。
     *
     * 走的是 `phone_gesture` 而不是 `phone_tap` 的 x/y：脚本通道里「点到什么」必须能解析出来，
     * 而广告是广告 SDK 自绘的全屏页，无障碍树是空的（`treeQuality: unusable`），
     * `phone_tap` 带坐标会被判 `target_unresolved` 直接拦下——真机上就停在 `tap_failed`。
     * 路径手势不做目标解析，落下去就是落下去。
     */
    private suspend fun tapAt(sub: SubActions, ctx: ToolContext, steps: Steps, x: Int, y: Int): Boolean {
        val point = JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y)))
        val args = buildJsonObject {
            put("paths", JsonArray(listOf(JsonArray(listOf(point, point)))))
            put("durationMs", 80)
        }
        val result = runCatching {
            sub.run(Part.ToolCall("${ctx.callId}.tap${steps.n++}", "phone_gesture", args.toString()))
        }.getOrNull() ?: return false
        return result.content["ok"] == JsonPrimitive(true)
    }

    /**
     * 屏幕上是不是「即将打开微信」这种跳转确认框：同一个屏上既有取消、又有打开。
     *
     * 只要一个「取消」不算——正经 App 里到处都是取消；两边都在才敢当确认框，才敢按取消。
     */
    private suspend fun jumpDialog(sub: SubActions, ctx: ToolContext, steps: Steps): Hit? {
        val dismiss = find(sub, ctx, steps, split(DISMISS_TEXTS)) ?: return null
        return if (find(sub, ctx, steps, split(ACCEPT_TEXTS)) != null) dismiss else null
    }

    /**
     * 屏上读得出来的字（无障碍树缺失时由截图 OCR 补）。
     *
     * `visibleText[].center` **已经是 0-999 的千分比**（与 `phone_tap` 的 x/y 同一套网格），
     * 不要再按 viewport 折算一次——真机上就是这么把所有点击都打到屏幕左上角的。
     */
    private suspend fun screenWords(sub: SubActions, ctx: ToolContext, steps: Steps): List<ScreenWord> {
        val result = runCatching {
            sub.run(Part.ToolCall("${ctx.callId}.o${steps.n++}", "phone_observe", "{}"))
        }.getOrNull() ?: return emptyList()
        if (result.content["ok"] != JsonPrimitive(true)) return emptyList()
        val list = result.content["visibleText"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val text = (o["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val c = (o["center"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: return@mapNotNull null
            if (c.size < 2) return@mapNotNull null
            ScreenWord(text, c[0].coerceIn(0, 999), c[1].coerceIn(0, 999))
        }
    }

    /** 按候选词的顺序挑第一个出现在屏上的；一个都没读到给 null。 */
    private fun pick(words: List<ScreenWord>, keys: List<String>): ScreenWord? {
        for (k in keys) words.firstOrNull { it.text.contains(k) }?.let { return it }
        return null
    }

    /** 屏上的一句话和它的千分比中心。 */
    private data class ScreenWord(val text: String, val x: Int, val y: Int)

    /** 现在前台是哪个 App；读不出来给 null（读不出来就不敢按位置瞎点）。 */
    private suspend fun foreground(sub: SubActions, ctx: ToolContext, steps: Steps): String? {        val result = runCatching {
            sub.run(Part.ToolCall("${ctx.callId}.d${steps.n++}", "phone_diagnose", "{}"))
        }.getOrNull() ?: return null
        return (result.content["currentApp"] as? JsonPrimitive)?.contentOrNull
    }

    /** 返回键。 */
    private suspend fun back(sub: SubActions, ctx: ToolContext, steps: Steps): Boolean {
        val result = runCatching {
            sub.run(Part.ToolCall("${ctx.callId}.b${steps.n++}", "phone_back", "{}"))
        }.getOrNull() ?: return false
        return result.content["ok"] == JsonPrimitive(true)
    }

    /** 这次广告服务端发了几条奖励；抓包没开着、或这段时间没有 `finishWatch` 时为 0。 */
    private suspend fun finishWatchRewards(ctx: ToolContext): Int {
        val net = ctx.network ?: return 0
        val status = runCatching { net.captureStatus() }.getOrNull()
        if (status != null && !status.capturing) return 0
        val body = runCatching { net.getResponseBody(null, FINISH_WATCH, asString = true) }.getOrNull() ?: return 0
        val text = body.body ?: return 0
        if (RewardTaskPlan.succeeded(text) == false) return 0
        return RewardTaskPlan.rewardCount(text).coerceAtLeast(0)
    }

    /** 抓包开着时读一次任务接口，读不到就当作不知道（返回 null，完全按屏幕走）。 */
    private suspend fun apiState(ctx: ToolContext): RewardTaskState? {
        val net = ctx.network ?: return null
        val status = runCatching { net.captureStatus() }.getOrNull()
        if (status == null || !status.capturing) return null
        val body = runCatching { net.getResponseBody(null, MAIN_PAGE, asString = true) }.getOrNull() ?: return null
        return body.body?.let { RewardTaskPlan.readMainPage(it) }
    }

    /**
     * 按候选文字依次查，返回第一个满足 [accept] 的命中。
     *
     * [accept] 是用来把「领过了」的按钮排掉的：`已领取` 里含 `领取`，只按文字模糊匹配会一直命中它，
     * 于是一轮轮点灰按钮、`claimed` 一路上涨，广告一次都没播——真机第一次跑就是这么卡住的。
     */
    private suspend fun find(
        sub: SubActions,
        ctx: ToolContext,
        steps: Steps,
        texts: List<String>,
        accept: (Hit) -> Boolean = { true },
    ): Hit? {
        for (text in texts) {
            val args = buildJsonObject { put("text", text); put("limit", 5) }
            val result = runCatching {
                sub.run(Part.ToolCall("${ctx.callId}.q${steps.n++}", "phone_query", args.toString()))
            }.getOrNull() ?: continue
            if (result.content["ok"] != JsonPrimitive(true)) continue
            hits(result.content, text).firstOrNull(accept)?.let { return it }
        }
        return null
    }

    /** 一次查询里的所有可点命中；元素自己的文字留在 [Hit.name]，用来判断它是不是「已领取」。 */
    private fun hits(content: JsonObject, label: String): List<Hit> {
        val snapshotId = (content["snapshotId"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        val matches = (content["matches"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyList()
        return matches.mapNotNull { match ->
            val ref = ((match["actionRef"] ?: match["ref"]) as? JsonPrimitive)?.takeIf { it.isString }?.content
            val bounds = (match["bounds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
            if (ref == null && bounds.size < 4) return@mapNotNull null
            val name = ((match["name"] ?: match["text"]) as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: label
            Hit(snapshotId, ref, bounds, label, name)
        }
    }

    /**
     * 点一下。有可点的编号就按编号点；只有位置的就点文字中心。
     *
     * 网页（WebView）里的文字常常没有可点击的编号，只有位置——福利中心就是这种，
     * 只按编号点会一直点不动，所以位置这一路必须留着。
     */
    private suspend fun tap(sub: SubActions, ctx: ToolContext, steps: Steps, hit: Hit): Boolean {
        // 有可点的编号就按编号点；只有位置的走手势（原因见 tapAt）
        val ref = hit.ref ?: return tapAt(
            sub, ctx, steps,
            (hit.bounds[0] + hit.bounds[2]) / 2,
            (hit.bounds[1] + hit.bounds[3]) / 2,
        )
        val args = buildJsonObject { put("ref", ref); put("snapshotId", hit.snapshotId) }
        val result = runCatching {
            sub.run(Part.ToolCall("${ctx.callId}.tap${steps.n++}", "phone_tap", args.toString()))
        }.getOrNull() ?: return false
        return result.content["ok"] == JsonPrimitive(true)
    }

    private fun round(n: Int, what: String, label: String) = buildJsonObject {
        put("round", n); put("do", what); put("on", label.take(40))
    }

    private fun split(raw: String?): List<String> =
        raw.orEmpty().split(',', '，', '|').map(String::trim).filter(String::isNotEmpty).take(8)

    private fun failure(error: String) = ToolExecution(buildJsonObject { put("ok", false); put("error", error) })

    private data class Hit(
        val snapshotId: String,
        val ref: String?,
        val bounds: List<Int>,
        /** 当初查它用的词。 */
        val label: String,
        /** 元素自己写的字，可能是 `已领取`、`领取` 这种跟查询词不完全一样的。 */
        val name: String,
    ) {
        /** 同一块屏上的同一个元素。领过一次之后按这个排掉，免得一轮轮点同一个灰按钮。 */
        val key: String get() = "$snapshotId|${ref ?: bounds.joinToString(",")}"
    }

    /** 抓包时用来核对的两个接口。 */
    private const val FINISH_WATCH = "video/adv/finishWatch"
    private const val MAIN_PAGE = "video/adv/mainPage"
}
