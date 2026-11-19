package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.tool.EffectKind
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.guard.GuardScope
import com.wangzan101.mika.core.guard.elementsHash
import com.wangzan101.mika.core.guard.resultFingerprint
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.policy.PacingContext
import com.wangzan101.mika.core.policy.PacingDirective
import com.wangzan101.mika.core.policy.PacingPolicy
import com.wangzan101.mika.core.policy.asPacingPolicy
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.effectiveWrite
import com.wangzan101.mika.core.uistate.Described
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.interfaces
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.ToolTraits
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.parseArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.tool.SubActions
import com.wangzan101.mika.core.guard.OffScreenGuard
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.wangzan101.mika.core.run.waitOrSteer
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.failCode
import com.wangzan101.mika.core.capability.Paced

/**
 * 所有改变外部世界的动作唯一的执行入口:[ToolDispatcher.execute] 要一张 [ActionPass],只有这里有。
 * 每个来源都按同一顺序:操作守卫 → 锁屏/被拿走的关卡(写操作)→ 不可逆判定与同意核对(按过关卡之后此刻的屏幕)→
 * 执行 → 守卫核对(失败熔断、画面变化、重读)→ 运行记录。来源之间有意的不同只写在 [OriginPolicy] 里。
 */
class ActionExecutor(
    private val tools: ToolDispatcher,
    private val guards: OperationGuards,
    confirmations: ConfirmationGate?,
    private val gate: suspend () -> Unit,
    val recorder: ActionRecorder,
    /** 不动屏幕的工具的守卫;没有为 null。 */
    private val offScreen: OffScreenGuard? = null,
    approvals: ApprovalSettings = ApprovalSettings.ASK,
    provenance: RunProvenance = RunProvenance(),
    selfPackage: String? = null,
    autonomous: Boolean = false,
    /** 同一网站、App 里连着做对外动作(发送、提交)时两次之间的间隔(见 [OutwardPacing]);默认不等。 */
    private val pacing: OutwardPacing = OutwardPacing.Off,
    /** 统一动作节拍策略（未传且旧 [pacing] 开启时自动桥接为 [com.wangzan101.mika.core.policy.OutwardPacingPolicy]）。 */
    private val pacingPolicy: PacingPolicy = pacing.asPacingPolicy(),
    /** 无条件检查并等待系统中间页/跳转确认弹窗解决的门禁；曾等待过时返回 true。 */
    private val interstitialGate: suspend () -> Boolean = { false },
) {
    private val decision = ConfirmDecision(tools, confirmations, approvals, recorder = recorder, provenance = provenance, selfPackage = selfPackage, autonomous = autonomous)
    private val decisionMutex = Mutex()
    private val pass = ActionPass()

    /** 配了确认关卡(付款能经用户同意后做)。 */
    val confirms: Boolean = confirmations != null

    /** 不动屏幕的工具的守卫叫停的结论;没叫停为 null。 */
    val offScreenStop: GuardVerdict? get() = offScreen?.stop

    /** 用户回答追问或交回控制权后通知节拍策略重置连续等待窗口。 */
    fun onUserAnswered() {
        pacingPolicy.onUserAnswered()
    }

    suspend fun run(call: Part.ToolCall, origin: Origin, followUp: FollowUp = FollowUp.NONE): ActionOutcome =
        run(call, origin, followUp, reads = null)

    private fun traitsOf(name: String, verb: Verb?): ToolTraits =
        tools.declared(name)?.traits
            ?: tools.index.traits(name)
            ?: verb?.let { ToolTraits.fromVerb(it, offScreen = tools.offScreen(name)) }
            ?: ToolTraits()

    /** [reads]:页面脚本一次调用里的读单独计数的范围(来源要求隔离时);那里的动作仍按任务计。 */
    private suspend fun run(call: Part.ToolCall, origin: Origin, followUp: FollowUp, reads: GuardScope?): ActionOutcome {
        val args = parseArgs(call.argsJson)
        // 不动屏幕的工具(沙箱一类):不进屏幕守卫,由各自的守卫另管
        val offScreen = tools.offScreen(call.name)
        val verb = tools.verb(call.name)
        val traits = traitsOf(call.name, verb)
        // 并行安全且不写屏的工具不触碰 SurfaceLayer,避免并发争夺焦点/动作目标
        if (tools.skipsSurface(call.name)) {
            return try {
                aimed(call, origin, followUp, reads, args, offScreen, verb, traits, skipSurface = true)
            } catch (e: com.wangzan101.mika.core.access.AppAccessDenied) {
                refusedAppAccess(call, origin, followUp, verb, e)
            }
        }
        // 用了另一块屏的编号的:不判不做、不记成在操作哪块屏,直接交给分发器说明该用哪个工具
        if (tools.misaimed(call.name, args) != null) {
            return ran(Step(call, args, origin, emptyList(), writing = false, offScreen, verb, traits, null, followUp, reads))
        }
        // 之后的关卡、判定都按这次调用的那块屏(到这次调用结束)
        tools.aim(call.name, args)
        try {
            return aimed(call, origin, followUp, reads, args, offScreen, verb, traits, skipSurface = false)
        } catch (e: com.wangzan101.mika.core.access.AppAccessDenied) {
            return refusedAppAccess(call, origin, followUp, verb, e)
        } finally {
            // 成功与否由分发器记;这里只结束这一层
            tools.release(false)
        }
    }

    private fun refusedAppAccess(
        call: Part.ToolCall, origin: Origin, followUp: FollowUp, verb: Verb?, e: com.wangzan101.mika.core.access.AppAccessDenied,
    ): ActionOutcome.Refused {
        val content = buildJsonObject {
            put("ok", false); put("error", e.code); put("message", e.message); put("retryable", false)
            e.packageName?.let { put("package", it) }
        }
        // 单步:作为这次工具调用的结果交给模型,让它换路线或向用户说明;批和脚本按 Refused 自己处理
        if (origin.policy.recordsRefusal) followUp.refused(content)
        recorder.result(origin.step, call.name, verb, content, content, null, null, 0, origin.policy.source, call.id, traits = tools.traits(call.name))
        return ActionOutcome.Refused(e.code, content, emptyList())
    }

    private suspend fun judge(
        name: String,
        args: JsonObject,
        origin: Origin,
        exactAsk: com.wangzan101.mika.core.gate.ConfirmationRequest? = null,
    ): Pair<Decision, String?> =
        decisionMutex.withLock { decision.judge(name, args, origin, exactAsk) to decision.outwardSite }

    private suspend fun aimed(
        given: Part.ToolCall, origin: Origin, followUp: FollowUp, reads: GuardScope?, givenArgs: JsonObject,
        offScreen: Boolean, verb: Verb?, traits: ToolTraits, skipSurface: Boolean = false,
    ): ActionOutcome {
        // 按描述指的目标先换成此刻那一屏里的编号:之后的判定、审批、执行和按编号的完全一样;找不到或不唯一就不做
        var call = given
        var args = givenArgs
        var note: JsonObject? = null
        if (!skipSurface) {
            tools.interfaces.checkAppAccess(tools.declared(call.name))
            when (val d = tools.interfaces.described(tools.declared(call.name), args)) {
                is Described.Failed -> return ran(Step(call, args, origin, emptyList(), writing = false, offScreen, verb, traits, null, followUp, reads, preset = d.result))
                is Described.Resolved -> { args = d.args; call = call.copy(argsJson = d.args.toString()); note = d.note }
                null -> Unit
            }
        }
        val policy = origin.policy
        val readScope = reads?.takeIf { traits.pageRead }
        val (before, stopped) = if (offScreen) {
            emptyList<GuardVerdict>() to false
        } else {
            synchronized(guards) {
                val list = if (readScope != null) guards.beforeAction(call.name, args, traits.diagnosticRead, readScope)
                else guards.beforeAction(call.name, args, traits.diagnosticRead)
                list to (guards.stop != null)
            }
        }
        if (stopped) return refuse("guard_stopped", origin, before)
        if (policy.stopOnGuardWarning && before.isNotEmpty()) return refuse("guard_warning", origin, before)
        val writing = tools.writesScreen(call.name)
        if (!policy.gatedByCaller) {
            if (writing) gate() else if (!offScreen) interstitialGate()
        }
        // 没有可用的同意就不执行,也不计入失败熔断
        var (d, site) = judge(call.name, args, origin)
        // 不在屏上、工具声明要问的一步(会改动东西的 root 命令):调用方问得到人就当场问,同意了按这次同意再判一次;
        // 问不到人(这次运行不能问、调用方不是模型单步)就不做,照被拦下记
        val ask = if (d is Decision.Required || d is Decision.Denied) {
            tools.approvalRisk(call.name, args)?.ask ?: if (d is Decision.Required && offScreen) {
                d.ask ?: com.wangzan101.mika.core.gate.ConfirmationRequest(
                    action = d.hit.action,
                    target = call.name,
                    content = d.hit.matched.ifBlank { Redactor.args(verb, call.argsJson, false) }.take(200),
                    amount = null,
                    app = tools.declared(call.name)?.capability ?: call.name,
                )
            } else null
        } else null
        if (ask != null) {
            val hit = Irreversible(ask.action, ask.content)
            val answer = (if (d is Decision.Required) followUp.approve(ask) else null)
                ?: return declined(call, args, origin, before, hit, ToolExecution(Refusals.unavailable()), followUp, "approval_unavailable")
            if (answer.content["ok"] != JsonPrimitive(true)) return declined(call, args, origin, before, hit, answer, followUp, "confirmation_declined")
            val rejudged = judge(call.name, args, origin, exactAsk = ask.takeIf { (d as? Decision.Required)?.ask == null })
            d = rejudged.first
            site = rejudged.second
        }
        when (d) {
            is Decision.Required -> return blocked(call, args, origin, before, d.hit, null, followUp)
            is Decision.Mismatch -> return blocked(call, args, origin, before, d.hit, d.reason, followUp)
            is Decision.Denied -> return approvalUnavailable(call, args, origin, before, d.hit, followUp)
            Decision.Unresolved -> return refuse("target_unresolved", origin, before)
            Decision.OwnApp -> return ownApp(call, args, origin, before, followUp)
            else -> Unit
        }
        // 动作执行前的统一节拍策略（含对外动作间隔、站点/页面反爬节拍、风控熔断停止）
        val pacingCtx = if (pacingPolicy.enabled) {
            PacingContext(
                tool = call.name,
                capability = tools.declared(call.name)?.capability,
                traits = traits,
                site = site,
                outward = site != null,
                writesScreen = writing && !offScreen,
                nowMs = recorder.now(),
            )
        } else null
        val directive = pacingCtx?.let(pacingPolicy::beforeAction) ?: PacingDirective.NONE
        directive.stopReason?.let { stopReason ->
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, traits, null, followUp, reads,
                preset = fail("paced_stop", stopReason, recoverable = false), skipSurface = skipSurface))
        }
        val paced = directive.waitMs.takeIf { it > 0L } ?: 0L
        // 等间隔时用户插了话:这次不做,先让模型读那句话。间隔从上一次做完算起,下次再做时余下的照样补上
        if (paced > 0 && waitOrSteer { delay(paced) } == null) {
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, traits, null, followUp, reads, preset = failCode(SKIPPED_FOR_NEW_MESSAGE), skipSurface = skipSurface))
        }
        // 改动屏幕的动作:按这块屏的能力的节奏先停一会儿(App 里见 AppDwell),单步、一批里的每一步都在这里停;
        // 组合动作(脚本)本身不停,里面的每一步经这里各自停。到了屏数上限、停留中用户插了话就不做
        val screenPaced = writing && !offScreen && !tools.composite(call.name)
        val dwelt = if (screenPaced) tools.beforeWrite(call.name, outward = site != null) else Paced.NONE
        dwelt.stop?.let { stop ->
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, traits, null, followUp, reads, preset = stop, pacedMs = paced + dwelt.waitMs, skipSurface = skipSurface))
        }
        if (writing) {
            synchronized(guards) { guards.noteAction() }
            decision.noteWrite(call.name)
        }
        val idleScrolls = dwelt.idleScrolls + if (directive.idleScroll) 1 else 0
        val step = Step(call, args, origin, before, writing, offScreen, verb, traits, (d as? Decision.Traced)?.detail, followUp, reads, note = note,
            pacedMs = paced + dwelt.waitMs, idleScrolls = idleScrolls, outward = site != null, skipSurface = skipSurface)
        // 动作一旦开始就做完并记下:脚本超时、任务取消都不能把它掐在半中间、漏掉记录
        // 对外动作告诉这块屏的能力(网页的页数上限只管浏览,见 PageDwell)
        site?.let(tools::outwardStart)
        var done = false
        try {
            val outcome = if (policy.actionCapMs == null) ran(step) else withContext(NonCancellable) { ran(step) }
            if (screenPaced && outcome is ActionOutcome.Ran) tools.afterWrite(call.name, outcome.exec.content)
            val succeeded = outcome is ActionOutcome.Ran && outcome.exec.content["ok"] != JsonPrimitive(false)
            if (pacingCtx != null) {
                pacingPolicy.afterAction(pacingCtx.copy(nowMs = recorder.now()), succeeded)
            }
            if (outcome is ActionOutcome.Ran && succeeded) {
                val place = outcome.exec.place
                if (place != null && place.kind == PlaceKind.Site) {
                    pacingPolicy.onSiteLoaded(place.name, recorder.now())
                }
                if (traits.userInteraction) {
                    pacingPolicy.onUserAnswered()
                }
            }
            if (site != null && succeeded) {
                done = true
            }
            return outcome
        } finally {
            site?.let { tools.outwardEnd(it, done) }
        }
    }

    private class Step(
        val call: Part.ToolCall, val args: JsonObject, val origin: Origin, val before: List<GuardVerdict>, val writing: Boolean,
        val offScreen: Boolean, val verb: Verb?, val traits: ToolTraits, val traced: String?, val followUp: FollowUp, val reads: GuardScope?,
        /** 没交给工具就有的结果(按描述找不到目标)。 */
        val preset: ToolExecution? = null,
        /** 附进结果的说明(按描述对上的是谁)。 */
        val note: JsonObject? = null,
        /** 执行前为对外动作的间隔、这块屏的节奏等了多久;算进这次调用的等待。 */
        val pacedMs: Long = 0,
        /** 执行前的停留里轻轻滚了几下;只进运行记录。 */
        val idleScrolls: Int = 0,
        /** 不可逆判定认出是对外动作(发送、提交);记进这次的活动。 */
        val outward: Boolean = false,
        /** 并行安全且不写屏的工具跳过了 SurfaceLayer 入栈。 */
        val skipSurface: Boolean = false,
    )

    private suspend fun ran(s: Step): ActionOutcome {
        val call = s.call
        val policy = s.origin.policy
        val n = s.origin.step
        // 页面脚本里的每个动作经这里回来,同样走完整个入口;脚本里的读只在这一次调用里计数
        val subVerdicts = mutableListOf<GuardVerdict>()
        val sub = if (tools.composite(call.name)) {
            val replayOrigin = tools.replaySubActions(call.name)
            val subOrigin = if (replayOrigin) Origin.Replay else Origin.Script(n)
            val window = if (!replayOrigin && subOrigin.policy.isolatedReads) GuardScope(canStop = false) else null
            SubActions { c ->
                if (replayOrigin && tools.verb(c.name) == Verb.ListElements) gate()
                val o = run(c, subOrigin, FollowUp.NONE, window)
                subVerdicts += o.before
                if (o is ActionOutcome.Ran) subVerdicts += o.after
                o.execution()
            }
        } else null
        val started = recorder.now()
        // 只有改了可见 UI 屏幕的动作才记 unreadChange:offScreen 后台工具(如 schedule_task、沙箱等)不改屏幕,不触发重读提醒
        val changes = !s.offScreen && s.traits.effect != EffectKind.ReadOnly && s.traits.modifiesScreenState
        val mark = tools.ledgerMark()
        // 启动 App 不走元素闸门,拿不到动作前的目标与屏幕;先读一屏,后面靠它补出入口边(桌面 → App)
        val beforeScreen = if (policy.routeTargets && tools.captureTargets && call.name in SCREEN_CAPTURE_TOOLS) {
            try {
                tools.interfaces.peek()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else null
        val raw = (s.preset ?: offScreen?.let { g -> synchronized(g) { g.exhausted(call.name) } }?.let { ToolExecution(it) } ?: execute(s, sub))
            .let { r -> s.note?.let { r.with(content = JsonObject(r.content + it)) } ?: r }
            .let { r -> if (s.pacedMs > 0) r.with(waitMs = r.waitMs + s.pacedMs) else r }
            .let { r -> if (s.idleScrolls > 0) r.with(record = JsonObject(r.record + ("idleScrolls" to JsonPrimitive(s.idleScrolls)))) else r }
        // 报了失败、也没说界面变了的动作什么也没改成:回答前不用为它再读一遍
        val failed = raw.content["ok"] == JsonPrimitive(false) && raw.changed != true
        if (changes && s.preset == null && !failed) recorder.noteChange(mark)
        val durationMs = recorder.now() - started
        val resolved = s.followUp.resolve(raw) ?: return ActionOutcome.Cancelled(s.before)
        if (!s.offScreen && !s.skipSurface) tools.interfaces.checkAppAccess(tools.declared(call.name), after = true)

        val after = subVerdicts.toMutableList()
        after += if (s.offScreen) offScreen?.let { g -> synchronized(g) { g.result(call.name, s.args, resolved.content) } }.orEmpty()
        else synchronized(guards) { guards.result(call.name, resolved.content) }
        // 动作报回的实际效果先记(没有效果、换了地方),接着的画面就不再按指纹重复记
        (resolved.content["effect"] as? JsonObject)?.takeIf { !s.offScreen }?.let { effect ->
            val kind = (effect["kind"] as? JsonPrimitive)?.content ?: return@let
            after += synchronized(guards) { guards.effect(kind, (effect["treeBlind"] as? JsonPrimitive)?.booleanOrNull == true) }
        }
        val elements = resolved.screenElements ?: resolved.content["elements"] as? JsonArray
        val frameHash = elementsHash(elements)
        if (frameHash != null || !s.offScreen) {
            synchronized(guards) {
                if (frameHash != null) {
                    val beforeFrameProgress = guards.progressVersion
                    after += guards.frame("$n:${call.id}", frameHash)
                    if (guards.progressVersion != beforeFrameProgress) after.removeAll { it.code == "no_progress" }
                }
                if (!s.offScreen) {
                    if (s.traits.pageRead) {
                        // 读到画面上次变化以来没读到过的内容(新的一屏、整页换了起点、脚本给出新结果)不算重读
                        val fp = frameHash ?: resultFingerprint(resolved.content)
                        val beforeReadProgress = guards.progressVersion
                        after += if (s.reads != null) guards.read(call.name, guards.firstSeen(fp, s.reads), s.reads, fp)
                        else guards.read(call.name, guards.firstSeen(fp), fingerprint = fp)
                        if (guards.progressVersion != beforeReadProgress) after.removeAll { it.code == "no_progress" }
                    } else if (s.writing && resolved.changed != false && resolved.content["ok"] == JsonPrimitive(true)) {
                        guards.pageChanged()
                    }
                }
            }
        }
        val (exec, content) = s.followUp.present(resolved, s.before + after)

        // 画面:模型单步交给调用方按它的顺序存;子步界面变了才当场存;回放不存
        val picture = if (policy.frames == Frames.Caller && !s.traits.recallsPastScreens && !s.offScreen) exec.image ?: exec.frame else null
        val shotId = when (policy.frames) {
            Frames.Caller -> picture?.let { recorder.nextShot() }
            Frames.WhenChanged -> (exec.image ?: exec.frame)?.takeIf { exec.changed != false }?.let(recorder::saveFrame)
            Frames.None -> null
        }
        recorder.result(n, call.name, s.verb, content, exec.record, exec.changed, shotId, durationMs, policy.source, call.id, exec.waitMs, traits = s.traits)
        ActivityRecord.of(tools, call.name, s.args, exec, n, policy.source, call.id, recorder.now(), s.outward)?.let(recorder::event)
        if (policy.frames == Frames.WhenChanged && shotId != null) recorder.event(RunEvent.Media(recorder.now(), shotId, n, RunEvent.placeLabel(exec.place)))
        val seen = if (policy.routeTargets) routeTarget(call, s.verb, s.origin, exec, beforeScreen) else null
        s.traced?.let { recorder.event(RunEvent.State(recorder.now(), "gate_unresolved", "${call.name} $it")) }
        return ActionOutcome.Ran(exec, s.before, after, content, durationMs, shotId.takeIf { picture != null }, picture, seen)
    }

    private suspend fun execute(s: Step, sub: SubActions?): ToolExecution {
        val call = if (s.offScreen && offScreen != null) offScreen.capTimeout(s.call, s.args) else s.call
        val after = if (!s.offScreen && !s.skipSurface && !s.origin.policy.gatedByCaller) interstitialGate else null
        val cap = s.origin.policy.actionCapMs ?: return tools.execute(call, pass, s.origin.step, sub, afterScreenAction = after)
        // 动作自己有上限
        return withTimeoutOrNull(cap) { tools.execute(call, pass, s.origin.step, sub, afterScreenAction = after) } ?: ToolExecution(buildJsonObject {
            put("ok", false); put("error", "action_timeout"); put("message", "the action did not finish within ${cap / 1000} s; read the screen before going on")
        })
    }

    /**
     * 开着回放时记路线要的目标元素和前后锚点(动作后多读一屏,返回它)。
     * 取不到目标、动作却生效了:路线不能缺步,标 incomplete,整条路线不记。
     * 启动 App 这类不经过元素闸门的动作没有目标,用执行前读的那一屏补前后签名;
     * 前后都拿不到才标 incomplete。
     */
    private suspend fun routeTarget(
        call: Part.ToolCall,
        verb: Verb?,
        origin: Origin,
        exec: ToolExecution,
        beforeFallback: ScreenSnapshot? = null,
    ): ScreenSnapshot? {
        if (!tools.captureTargets || tools.index[call.name]?.routable != true) return null
        // 只有端口明确说「不是密码框」才记原文
        val secret = (exec.content["secret"] as? JsonPrimitive)?.booleanOrNull != false
        val args = Redactor.args(verb, call.argsJson, secret)
        val effective = effectiveWrite(verb, exec.content)
        val source = origin.policy.source
        val none = Anchors(null, emptyList())
        val ui = tools.interfaces
        // 启动 App 走自己的前后签名,不拿上一次写操作残留的目标与快照
        val captured = if (beforeFallback != null) null else ui.lastActionTarget()
        val beforeSnap = beforeFallback ?: ui.lastActionSnapshot()
        if (captured == null && beforeSnap == null) {
            if (effective) recorder.event(RunEvent.ActionTarget(recorder.now(), origin.step, call.name, args, null, none, none, true,
                callId = call.id, source = source, incomplete = true))
            return null
        }
        val beforeProtected = beforeSnap?.protected == true
        val seen = if (effective) ui.peek() else null
        val after = seen?.let(ui::anchorsAfter) ?: seen?.let { Anchors(it.packageName, emptyList()) } ?: none
        val beforeActivity = if (beforeProtected) null else beforeSnap?.let { it.activity ?: it.screenSig?.activity }
        val beforeSig = if (beforeProtected) null else beforeSnap?.resolvedScreenSig()
        val afterProtected = seen?.protected == true
        val afterActivity = if (afterProtected) null else seen?.let { it.activity ?: it.screenSig?.activity }
        val afterSig = if (afterProtected) null else seen?.resolvedScreenSig()
        recorder.event(RunEvent.ActionTarget(recorder.now(), origin.step, call.name, args, captured?.first, captured?.second ?: beforeSnap?.let { Anchors(it.packageName, emptyList()) } ?: none, after, effective,
            callId = call.id, protected = beforeProtected, source = source,
            beforeActivity = beforeActivity, beforeSig = beforeSig, afterActivity = afterActivity, afterSig = afterSig))
        return seen
    }

    /** 作用在 Mika 自己身上:不做;模型单步照常拿到说明、记进运行记录。 */
    private fun ownApp(call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, followUp: FollowUp): ActionOutcome {
        val content = Refusals.content(Refusals.OWN_APP, origin.policy.refusals, before, null, null)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id, traits = tools.traits(call.name))
        }
        return ActionOutcome.Refused(Refusals.OWN_APP, content, before)
    }

    private fun refuse(code: String, origin: Origin, before: List<GuardVerdict>) =
        ActionOutcome.Refused(code, Refusals.content(code, origin.policy.refusals, before, null, null), before)

    private fun approvalUnavailable(
        call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, hit: Irreversible, followUp: FollowUp,
    ): ActionOutcome {
        val code = "approval_unavailable"
        val content = Refusals.content(code, origin.policy.refusals, before, hit, null)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id, traits = tools.traits(call.name))
        }
        return ActionOutcome.Refused(code, content, before, hit)
    }

    /** 要问的一步没有同意(用户没同意,或问不到人):[answer] 就是结果,照被拦下一样记。 */
    private fun declined(
        call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, hit: Irreversible, answer: ToolExecution, followUp: FollowUp,
        code: String,
    ): ActionOutcome {
        recorder.confirmation(hit, if (code == "confirmation_declined") "declined" else "denied")
        if (origin.policy.recordsRefusal) {
            followUp.refused(answer.content)
            recorder.result(origin.step, call.name, tools.verb(call.name), answer.content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id, traits = tools.traits(call.name))
        }
        return ActionOutcome.Refused(code, answer.content, before, hit)
    }

    private fun blocked(
        call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, hit: Irreversible, reason: String?, followUp: FollowUp,
    ): ActionOutcome {
        recorder.confirmation(hit, if (reason != null) "mismatch" else "required")
        val code = if (reason != null) "confirmation_mismatch" else "confirmation_required"
        val content = Refusals.content(code, origin.policy.refusals, before, hit, reason)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id, traits = tools.traits(call.name))
        }
        return ActionOutcome.Refused(code, content, before, hit)
    }
}

/** 不经过元素闸门、却会换 App 的动作:执行前先读一屏,用它补入口边的前后签名。 */
private val SCREEN_CAPTURE_TOOLS = setOf("phone_launch_app")
