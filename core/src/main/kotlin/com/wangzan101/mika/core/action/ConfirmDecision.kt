package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.gate.IrreversibleDetector
import com.wangzan101.mika.core.gate.Permit
import com.wangzan101.mika.core.policy.PermissionContext
import com.wangzan101.mika.core.policy.PermissionDecision
import com.wangzan101.mika.core.policy.PermissionPolicy
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.ToolTraits
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.interfaces
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 一次动作执行前的审批结论。 */
sealed interface Decision {
    /** 不用判,或判过放行(含付款时用户的同意对上了)。 */
    data object Clear : Decision
    /** 放行,但找不到点在哪个元素上:运行记录里留痕。 */
    data class Traced(val detail: String) : Decision
    /** 找不到点在哪个元素上,这一来源不盲点。 */
    data object Unresolved : Decision
    /**
     * 这一步要用户这一次的同意(花钱,或 [ask] 不为空时是工具声明要问的不在屏上的一步),没有可用的。
     * 有 [ask] 时调用方能问人就当场问(见 [FollowUp.approve])。
     */
    data class Required(val hit: Irreversible, val ask: ConfirmationRequest? = null) : Decision
    /** 有同意,但和这一步对不上;那次同意已作废。 */
    data class Mismatch(val hit: Irreversible, val reason: String) : Decision
    /** 要花钱,本次运行却问不到手机旁的用户,直接拒绝。 */
    data class Denied(val hit: Irreversible) : Decision
    /** 作用在 Mika 自己身上,不做。 */
    data object OwnApp : Decision
}

/**
 * 不可逆判定、审批与同意核对。整个代码库里只有这里调用 [IrreversibleDetector] 的判定和 [ConfirmationGate.permits]
 * (有测试守着),各来源只在 [OriginPolicy] 里写明哪里不同;问不问由 [ApprovalPolicy.decide] 定(付款和工具声明要问的问)。
 */
class ConfirmDecision(
    private val tools: ToolDispatcher,
    private val confirmations: ConfirmationGate?,
    private val approvals: ApprovalSettings = ApprovalSettings.ASK,
    private val risks: RiskJudge = ClassifiedRisk,
    private val recorder: ActionRecorder? = null,
    private val provenance: RunProvenance = RunProvenance(),
    /** 开工就是自主执行(没有确认关卡):跳过普通确认弹窗,但仍受硬性安全底线(如 ownApp 与 Hard Deny 规则)约束。 */
    private val autonomous: Boolean = false,
    /** Mika 自己的包名:作用在它上面的写操作一律不做(不自己给自己点同意、改设置)。 */
    private val selfPackage: String? = null,
    /** 规则式权限策略(支持工具名/Capability/参数模式匹配与 Once/Session/Persistent);默认取 [ApprovalSettings.permissionPolicy]。 */
    private val permissionPolicy: PermissionPolicy = approvals.permissionPolicy(),
) {
    /**
     * 刚判过的这一步是对外动作(发送、提交:判定命中、放行后要做的)时它所在的网站或 App(见 [OutwardPacing.siteOf]),
     * 执行入口据此和上一次对外动作隔开;不是为 null。自主执行时也认(按模型手里那一屏判,不另读屏),节奏不因此失效。
     */
    var outwardSite: String? = null
        private set

    /** 判定放在锁屏等待之后:按此刻重新读的一屏判。 */
    suspend fun judge(tool: String, args: JsonObject, origin: Origin, exactAsk: ConfirmationRequest? = null): Decision {
        outwardSite = null
        val policy = origin.policy
        val target = tools.gateTarget(tool)
        val declared = tools.declared(tool)
        // 这一步落在哪里由界面一侧给出;点看图找到的 @vN 时参数已换成它登记的坐标和找的文字
        val aim = tools.interfaces.actionTarget(declared, args)
        val checked = aim.args
        val traits = tools.traits(tool) ?: declared?.traits ?: ToolTraits()
        val isAutonomous = autonomous || approvals.autonomous()

        // 1. 硬性安全底线(不因 autonomous 绕过):ownApp 边界
        if (ownApp(tool, args, aim.aimed)) return Decision.OwnApp

        val declaredRisk = tools.approvalRisk(tool, checked)
        val needsIrrev = declaredRisk == null && IrreversibleDetector.needsCheck(target, args)
        val snapHit = if (needsIrrev) IrreversibleDetector.classify(target, checked, aim.aimed) else null
        val preliminaryRisk = declaredRisk ?: risks.assess(traits, snapHit) { aim.aimed }

        val basePermCtx = PermissionContext(
            tool = tool,
            capability = declared?.capability,
            traits = traits,
            args = checked,
            risk = preliminaryRisk,
            provenance = provenance,
            autonomous = isAutonomous,
            targetApp = aim.aimed?.packageName,
            selfPackage = selfPackage,
            writesScreen = tools.writesScreen(tool),
        )

        // 2. 硬性规则底线(Hard Deny 不因 autonomous 自动放行)
        val preDecision = permissionPolicy.peek(basePermCtx)
        if (preDecision is PermissionDecision.Deny && preDecision.hard) {
            val asked = snapHit ?: declaredRisk?.ask?.let { Irreversible(it.action, it.content) }
                ?: Irreversible(IrrevAction.other, preDecision.reason)
            recorder?.approval(origin, tool, asked, preliminaryRisk, "denied")
            return Decision.Denied(asked)
        }

        // 3. 自主模式:硬性底线已通过,放行普通确认弹窗并保留 outwardSite
        if (isAutonomous) {
            if (selfPackage != null && tools.writesScreen(tool) && aim.aimed == null) {
                val fresh = aim.now()
                if (ownApp(tool, args, fresh)) return Decision.OwnApp
            }
            if (needsIrrev) {
                outwardSite = outward(snapHit, aim.aimed)
            }
            return Decision.Clear
        }

        // 4. 非自主模式:工具声明自身风险(如 root / mcp)
        if (declaredRisk != null) {
            return approve(tool, checked, origin, null, declaredRisk, null, null, null, permissionPolicy.evaluate(basePermCtx), exactAsk) ?: Decision.Clear
        }

        // 5. 普通动作或未配确认关卡:若命中声明式权限规则仍按规则裁决,未命中则直接放行
        if (!needsIrrev || (confirmations == null && !policy.judgeWithoutGate)) {
            return evaluateNonIrrevRule(tool, origin, basePermCtx, aim.aimed, exactAsk) ?: Decision.Clear
        }

        val typing = if (traits.textInput) (args["text"] as? JsonPrimitive)?.contentOrNull else null
        val snap = aim.aimed
        val fresh = aim.now()
        if (ownApp(tool, args, fresh)) return Decision.OwnApp
        // 复看确认过的所见文字(@vN 才有):读屏里没有文字时(自绘界面)付款的同意按它核对象
        val seen = aim.seen
        val hit = IrreversibleDetector.classify(target, checked, snap, fresh)
        val risk = risks.assess(traits, hit) { fresh ?: snap }
        outwardSite = outward(hit, fresh ?: snap)
        val fullPermCtx = basePermCtx.copy(risk = risk, targetApp = (fresh ?: snap)?.packageName)
        approve(tool, checked, origin, hit, risk, fresh ?: snap, typing, seen, permissionPolicy.evaluate(fullPermCtx), exactAsk)?.let { return it }
        if (hit != null || !IrreversibleDetector.unresolved(target, checked, snap, fresh)) return Decision.Clear
        return if (policy.refuseUnresolved) Decision.Unresolved else Decision.Traced(unresolvedDetail(args, aim.readAgeMs, fresh != null))
    }

    /** 命中的是发送、提交时这一屏的网站或 App。 */
    private fun outward(hit: Irreversible?, screen: ScreenSnapshot?): String? =
        if (hit?.action == IrrevAction.send || hit?.action == IrrevAction.submit) OutwardPacing.siteOf(screen?.packageName, screen?.web != null) else null

    /** 写操作作用在 Mika 自己身上:屏上是 Mika,或要打开 Mika(含它的应用详情页)。 */
    private fun ownApp(tool: String, args: JsonObject, screen: ScreenSnapshot?): Boolean {
        val self = selfPackage ?: return false
        if (!tools.writesScreen(tool)) return false
        if (screen?.packageName == self) return true
        return listOf("package", "uri").any { k -> (args[k] as? JsonPrimitive)?.contentOrNull?.let { self in it } == true }
    }

    private fun evaluateNonIrrevRule(
        tool: String, origin: Origin, permCtx: PermissionContext, screen: ScreenSnapshot?,
        exactAsk: ConfirmationRequest? = null,
    ): Decision? = when (val decision = permissionPolicy.evaluate(permCtx)) {
        PermissionDecision.Abstain -> null
        else -> approve(tool, permCtx.args, origin, null, permCtx.risk, screen, null, null, decision, exactAsk)
    }

    /** 按来源与权限规则判;要花钱或规则要求确认时核对用户同意。放行为 null。 */
    private fun approve(
        tool: String, checked: JsonObject, origin: Origin, hit: Irreversible?, risk: Risk, screen: ScreenSnapshot?,
        typing: String?, seen: String?, policyDecision: PermissionDecision = PermissionDecision.Abstain,
        exactAsk: ConfirmationRequest? = null,
    ): Decision? {
        val policy = origin.policy
        // 没命中不可逆判定的(工具自己声明的风险或规则命中):有申报按申报记,否则按动作落在的元素记成「其他」
        val asked = hit ?: risk.ask?.let { Irreversible(it.action, it.content) } ?: Irreversible(IrrevAction.other,
            screen?.let { ClassifiedRisk.target(tools.traits(tool), checked, it) }?.name?.trim()?.takeIf { it.isNotEmpty() } ?: tool)
        // 对外、要花钱和没放行的都记下;界面上的改动不记
        fun audit(decision: String) {
            if (decision != "allowed" || risk.level.outward || risk.payment) recorder?.approval(origin, tool, asked, risk, decision)
        }
        when (ApprovalPolicy.decide(policyDecision, risk, provenance)) {
            Approval.Allow -> return null.also { audit("allowed") }
            Approval.Deny -> return Decision.Denied(asked).also { audit("denied") }
            Approval.Ask -> Unit
        }
        val required = Decision.Required(asked, risk.ask)
        val gate = confirmations?.takeIf { policy.usesGrant } ?: return required.also { audit("required") }
        val exact = risk.ask ?: exactAsk
        val permit = exact?.let { gate.permitsExactly(it, origin.step) } ?: gate.permits(asked, screen, origin.step, typing, listOfNotNull(seen))
        return when (val p = permit) {
            Permit.Allowed -> null.also { audit("allowed") }
            Permit.NoGrant -> required.also { audit("required") }
            is Permit.Mismatch -> Decision.Mismatch(asked, p.reason).also { audit("mismatch") }
        }
    }

    /** 执行了一个写操作:同意按它的规则作废或保留。 */
    fun noteWrite(tool: String) {
        confirmations?.noteWrite(tools.traits(tool) ?: ToolTraits())
    }

    /** 留痕:点的是哪个编号、那次读屏多久以前,或点的坐标。 */
    private fun unresolvedDetail(args: JsonObject, readAgeMs: Long?, freshRead: Boolean): String {
        fun arg(k: String) = args[k]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
        val ref = arg("ref")
        return if (ref != null) {
            val sid = arg("snapshotId")
            "ref=$ref snapshotId=$sid age=${readAgeMs?.let { "${it}ms" } ?: "unknown"}"
        } else "x=${arg("x")},y=${arg("y")} freshRead=$freshRead"
    }

    companion object {
        /**
         * 不读屏的预判(大小模型分工用):按上一次读屏,这一步可能是不可逆的,或不知道点的是什么(看图得来、找不到元素);
         * 这样的一步交主模型做。
         */
        fun mayBeIrreversible(tools: ToolDispatcher, tool: String, args: JsonObject): Boolean {
            val target = tools.gateTarget(tool)
            if (!IrreversibleDetector.needsCheck(target, args)) return false
            val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
            val checked = aim.args
            val snap = aim.aimed
            return aim.bySight || IrreversibleDetector.classify(target, checked, snap) != null ||
                IrreversibleDetector.unresolved(target, checked, snap)
        }
    }
}
