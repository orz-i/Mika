package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.memory.Change
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.memory.ProfileStore
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.vision.ImageOps
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.core.visual.Shots
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.knowledge.BundledScripts
import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.ScheduleAdmin
import com.wangzan101.mika.core.child.ChildRunHost
import com.wangzan101.mika.core.run.RunSpec
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 单次工具调用的局部可变状态;由 [ToolDispatcher] 为每次分发独立创建并绑定到协程上下文,
 * 保证并行工具调用之间互不干扰。
 */
internal class CallSession(
    var callId: String = "",
    var sub: SubActions? = null,
    var step: Int = 0,
    var reportsEffect: Boolean = false,
    var pacedWaitMs: Long = 0L,
    var idleScrolls: Int = 0,
)

/** 本任务的配置(模型、技能、记忆、开关);能力各自的端口与开关在能力里。 */
internal class ToolConfig(
    val visionEnabled: Boolean,
    val skills: GuideCatalog?,
    val recordFrames: Boolean,
    val vision: VisionService?,
    val inlineScreen: Boolean,
    /** 元素优先:可读页面不把截图送给主模型,读不到时自动补图。 */
    val adaptiveVision: Boolean,
    val memory: MemoryStore?,
    val profile: ProfileStore?,
    val utterances: UserUtterances?,
    val conversationId: String?,
    val imageOps: ImageOps?,
    /** 存下一个定时任务,并说明能不能准时(见 [Scheduled])。 */
    val scheduleTrigger: ((TriggerDefinition) -> Scheduled)? = null,
    val scheduleAdmin: ScheduleAdmin? = null,
    val runSpec: RunSpec? = null,
    val childHost: ChildRunHost? = null,
    /** 这个对话里用户发来的屏幕;没有时不提供 view_user_screen。 */
    val userScreens: com.wangzan101.mika.core.userscreen.UserScreenSource? = null,
    /** 用户的个人技能;给了(且有技能目录、用户本次说的话)才提供 skill_save。 */
    val personalSkills: com.wangzan101.mika.core.skills.PersonalSkills? = null,
    /** 改 Mika 自己设置的白名单入口;为空(用户没允许)时不提供 settings_list、settings_set。 */
    val selfConfig: com.wangzan101.mika.core.settings.SelfConfig? = null,
    /** 存下的运行记录;给了、而这个对话之前有记过运行的轮次时提供 history_read。 */
    val runRecords: com.wangzan101.mika.core.record.RunRecords? = null,
    /** 用户配置并启用的网页搜索;为空时不提供 web_search。 */
    val webSearch: com.wangzan101.mika.core.search.WebSearch? = null,
    /** 端上实体识别;为空时不提供 phone_entities(构建里没带模型,或这台设备没有对应的运行库)。 */
    val textEntities: com.wangzan101.mika.core.text.TextEntities? = null,
)

/**
 * 工具执行时共用的东西:配置、核查材料、能力放进来的东西([get]),以及跨工具的少量状态(被拒的付款申报、允许打开的链接……)。
 * 工具本身无状态,都从这里取。有界面的能力的读屏状态也是放进来的一份,由界面状态一侧取用。
 */
internal class ToolContext(
    val config: ToolConfig,
    val evidence: EvidenceLog,
    /** 本任务可能提供的全部工具的声明(页面脚本按动作找工具)。 */
    val tools: ToolIndex,
    /** 能跑技能脚本的能力;没有为 null。 */
    val scripts: BundledScripts?,
    val shots: Shots,
) {
    private val parts = ConcurrentHashMap<CapabilityKey<*>, Any>()

    /** 能力在任务开始时放进来的东西;没放为 null。 */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: CapabilityKey<T>): T? = parts[key] as T?

    operator fun <T : Any> set(key: CapabilityKey<T>, value: T) { parts[key] = value }

    private val sessionLocal = ThreadLocal<CallSession?>()
    private var fallbackCallId = ""
    private var fallbackSub: SubActions? = null
    private var fallbackReportsEffect = false
    private var fallbackPacedWaitMs = 0L
    private var fallbackIdleScrolls = 0

    internal fun currentSession(): CallSession? = sessionLocal.get()

    internal suspend fun <T> withSession(session: CallSession, block: suspend () -> T): T =
        withContext(sessionLocal.asContextElement(session)) { block() }

    /** 最近一次被拒的付款申报;接管付款时补上模型没写的商品和金额。 */
    @Volatile var refusedPay: ConfirmationRequest? = null
    /** 本次任务有没有成功打开过 App;没有时核查器知道没有屏幕可对照。 */
    @Volatile var appOpened = false
    /** 本次调用的编号;页面脚本里的动作按它编号。 */
    var callId: String
        get() = sessionLocal.get()?.callId ?: fallbackCallId
        set(v) {
            val s = sessionLocal.get()
            if (s != null) s.callId = v else fallbackCallId = v
        }
    /** 自己不动界面的工具(页面脚本)里的动作经它回到执行入口;只读调用、测试里不给时里面的动作一律不做。 */
    var sub: SubActions?
        get() = sessionLocal.get().let { if (it != null) it.sub else fallbackSub }
        set(v) {
            val s = sessionLocal.get()
            if (s != null) s.sub = v else fallbackSub = v
        }
    /** 本次调用所在的轮次(循环的「第 N 步」)。 */
    var currentStep: Int
        get() = sessionLocal.get()?.step ?: shots.step
        set(v) {
            val s = sessionLocal.get()
            if (s != null) s.step = v else shots.step = v
        }

    /** 参考资料里给过模型的链接;不带 action 的 open_link 只能开这些(uri 与包名都要一致)。 */
    val allowedLinks: MutableSet<Pair<String, String?>> = ConcurrentHashMap.newKeySet()

    private val saved = mutableListOf<Change>()
    val savedChanges: List<Change> get() = synchronized(saved) { saved.toList() }
    fun noteSaved(c: Change) = synchronized(saved) { saved += c }

    /** 最近一次 memory_search 返回的稳定编号;null 表示本任务还没搜过,空列表表示最近搜不到。 */
    @Volatile private var memoryCandidates: List<String>? = null
    fun noteMemorySearch(ids: List<String>) { memoryCandidates = ids }
    fun memorySearchCandidates(): List<String>? = memoryCandidates

    /** 这次调用的工具会由分发器报实际效果(effect):「没变化」由效果来说,这里不另提示。 */
    var reportsEffect: Boolean
        get() = sessionLocal.get()?.reportsEffect ?: fallbackReportsEffect
        set(v) {
            val s = sessionLocal.get()
            if (s != null) s.reportsEffect = v else fallbackReportsEffect = v
        }

    /** 这次调用里能力为节奏有意停下的时间(见 [ToolExecution.waitMs]);能力在停下时往上加,分发器每次调用清零后交进结果。 */
    var pacedWaitMs: Long
        get() = sessionLocal.get()?.pacedWaitMs ?: fallbackPacedWaitMs
        set(v) {
            val s = sessionLocal.get()
            if (s != null) s.pacedWaitMs = v else fallbackPacedWaitMs = v
        }

    /** 这次调用里停留时轻轻滚了几下页面(见 [com.wangzan101.mika.core.uistate.IdleScroll]);和 [pacedWaitMs] 一样每次调用清零,只进运行记录。 */
    var idleScrolls: Int
        get() = sessionLocal.get()?.idleScrolls ?: fallbackIdleScrolls
        set(v) {
            val s = sessionLocal.get()
            if (s != null) s.idleScrolls = v else fallbackIdleScrolls = v
        }
}

/** 模型写的申报字段截短到界面原样显示得下。 */
internal fun capField(s: String): String = s.trim().let { if (it.length <= CONFIRM_FIELD_MAX) it else it.take(CONFIRM_FIELD_MAX - 1) + "…" }
