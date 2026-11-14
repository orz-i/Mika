package com.wangzan101.mika.core.tool

/**
 * 工具对外部世界或运行环境产生的作用类别（与具体能力/动词解耦的正交特征）。
 */
enum class EffectKind {
    /** 纯读取/查询，不修改工作区、屏幕或外部状态。 */
    ReadOnly,
    /** 修改沙箱工作区、本地记忆、技能或配置等非屏幕状态。 */
    WorkspaceWrite,
    /** 修改可见 UI 屏幕或浏览器页面状态。 */
    ScreenWrite,
    /** 触发对外发送、支付、定时任务、子代理派发或开放世界副作用。 */
    External,
}

/**
 * 工具结果在上下文压缩与历史裁剪（HistoryPruner）中的保留策略。
 */
enum class ContextRetention {
    /** 瞬态界面状态（如元素树快照、界面诊断）：裁剪线之前除锚点外一律折叠为简短占位。 */
    InterfaceEphemeral,
    /** 普通读取或操作输出：仅当超过大结果阈值时裁剪长字段并保留观察引用。 */
    ShrinkIfLarge,
    /** 参考资料（知识库、技能说明、记忆检索）：裁剪线之前仍原样保留全文供模型对照执行。 */
    PinReference,
}

/**
 * 工具对模型层级的要求（用于 ModelRouter 轻重模型分工）。
 */
enum class ModelTier {
    /** 默认层级：简单步骤可交由轻量模型发起。 */
    Default,
    /** 仅限主模型：放弃、确认、接管、追问、障碍处理、复杂手机脚本及沙箱/破坏性操作必须由主模型决定。 */
    MainModelOnly,
}

/**
 * 流程控制与人机交互角色（替代多处按控制/交互 Verb 枚举的 1-to-1 分支）。
 */
enum class InteractionKind {
    None,
    AskUser,
    RequestConfirmation,
    HandToUser,
    HandleObstacle,
    EndNotDone,
}

/**
 * 对持久化记忆库的访问类别。
 */
enum class MemoryAccess {
    None,
    Search,
    Save,
    Edit,
}

/**
 * 工具的正交行为特征描述，取代按 [Verb] 枚举集合硬编码的分支判断。
 */
data class ToolTraits(
    val effect: EffectKind = EffectKind.ReadOnly,
    val concurrency: Concurrency = Concurrency.Serial,
    val retention: ContextRetention = ContextRetention.ShrinkIfLarge,
    val modelTier: ModelTier = ModelTier.Default,
    val surfaceKind: String? = null,
    /** 结果是否包含完整界面元素树快照（用于压缩/裁剪识别全量界面锚点）。 */
    val fullScreenSnapshot: Boolean = false,
    /** 是否为回看历史截图（按 screenshots 列表裁剪，执行器不重复计为新画面帧）。 */
    val recallsPastScreens: Boolean = false,
    /** 是否涉及等待用户介入/回答/确认（压缩时整轮保留，路由时下一步按恢复步处理）。 */
    val userInteraction: Boolean = false,
    /** 是否会切换前台应用或网页标签（路由时下一步不视为同一场所）。 */
    val switchesPlace: Boolean = false,
    /** 是否为界面诊断/观察类只读动作（供守卫统计诊断读）。 */
    val diagnosticRead: Boolean = false,
    /** 是否为界面/页面内容读取（供守卫按内容指纹去重统计页面读取）。 */
    val pageRead: Boolean = false,
    /** 是否直接改变当前可见界面控件状态（触发未读界面变化提醒）。 */
    val modifiesScreenState: Boolean = false,
    /** 人机交互或流程收尾角色。 */
    val interactionKind: InteractionKind = InteractionKind.None,
    /** 记忆库访问类别。 */
    val memoryAccess: MemoryAccess = MemoryAccess.None,
    /** 是否为界面修改后可重新锚定最新界面的新鲜读取动作。 */
    val freshRead: Boolean = false,
    /** 是否属于界面/页面采集与浏览类推进动作（供新数据追踪器判断）。 */
    val collectionOp: Boolean = false,
    /** 是否会落入站点/应用场所上下文并触发知识查询。 */
    val siteEntry: Boolean = false,
    /** 是否为讨论模式允许的本地记忆/知识/技能/历史只读检索。 */
    val lookupRead: Boolean = false,
    /** 是否为安全的界面导航动作（只读/子任务模式仍可放行网页导航）。 */
    val navigable: Boolean = false,
    /** 是否向焦点输入框输入文本（记录明文去敏回显、卡住建议去重、同意核对）。 */
    val textInput: Boolean = false,
    /** 是否作用于当前焦点可编辑输入框（输入/清空：风险判定位目标且保留未消费授权）。 */
    val usesFocusedEditable: Boolean = false,
    /** 是否返回无图结构化元素树快照（路线回放等按特征定位）。 */
    val elementSnapshot: Boolean = false,
    /** 是否读取技能全文（用于记录个人技能阅读）。 */
    val readsSkill: Boolean = false,
    /** 记录工具结果时是否遮蔽 answer 字段。 */
    val hidesAnswerInRecord: Boolean = false,
    /** 是否记入用户可见的活动流（纯等待与流程控制工具不记入）。 */
    val recordedInActivity: Boolean = true,
    /** 是否调度定时/后台任务。 */
    val schedulesTask: Boolean = false,
    /** 是否派生子代理任务。 */
    val delegatesTask: Boolean = false,
    /** 是否修改用户个人画像配置。 */
    val modifiesProfile: Boolean = false,
    /** 是否写入/保存个人技能。 */
    val writesSkill: Boolean = false,
) {
    /** 是否访问记忆库（检索、写入或编辑）。 */
    val memoryOp: Boolean get() = memoryAccess != MemoryAccess.None

    /** 是否为结束当前轮次并报告未完成的终结工具。 */
    val terminatesRun: Boolean get() = interactionKind == InteractionKind.EndNotDone

    companion object {
        private val REFERENCE_VERBS = setOf(
            Verb.KnowledgeSearch, Verb.KnowledgeRead, Verb.SkillSearch, Verb.SkillRead, Verb.MemorySearch,
        )
        private val INTERFACE_EPHEMERAL_VERBS = setOf(
            Verb.Observe, Verb.ListElements, Verb.Diagnose,
        )
        private val FULL_SCREEN_VERBS = setOf(
            Verb.Observe, Verb.ListElements,
        )
        private val USER_INTERACTION_VERBS = setOf(
            Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation,
        )
        private val SWITCH_PLACE_VERBS = setOf(
            Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.ShowAlarms,
            Verb.AddCalendarEvent, Verb.Navigate, Verb.Tabs,
        )
        private val DIAGNOSTIC_READ_VERBS = setOf(
            Verb.Observe, Verb.ListElements, Verb.Screenshot, Verb.Describe, Verb.Diagnose, Verb.ViewPastScreens,
        )
        private val PAGE_READ_EXTRA_VERBS = setOf(
            Verb.ReadPage, Verb.EvaluateJs, Verb.SiteCommand,
        )
        private val SCREEN_STATE_CHANGE_VERBS = setOf(
            Verb.Tap, Verb.TypeText, Verb.ClearText, Verb.PressKey, Verb.Upload, Verb.Interact,
        )
        private val FRESH_READ_VERBS = setOf(
            Verb.Observe, Verb.Screenshot, Verb.Capture, Verb.ReadPage, Verb.Wait, Verb.WaitFor,
        )
        private val COLLECTION_OP_VERBS = setOf(
            Verb.Observe, Verb.ListElements, Verb.Query, Verb.Collect, Verb.PhoneExtract,
            Verb.ReadPage, Verb.EvaluateJs, Verb.SiteCommand, Verb.Scroll, Verb.Swipe,
        )
        private val SITE_ENTRY_VERBS = setOf(
            Verb.Observe, Verb.ListElements, Verb.Navigate, Verb.ReadPage,
        )
        private val LOOKUP_READ_VERBS = setOf(
            Verb.MemorySearch, Verb.KnowledgeSearch, Verb.KnowledgeRead, Verb.SkillSearch, Verb.SkillRead, Verb.HistoryRead,
        )
        private val MAIN_MODEL_ONLY_VERBS = setOf(
            Verb.EndNotDone, Verb.RequestConfirmation, Verb.HandToUser, Verb.AskUser, Verb.HandleObstacle,
            Verb.PhoneRun, Verb.Collect, Verb.PhoneFind, Verb.PhoneDialog, Verb.PhoneGesture, Verb.PhoneCommand,
            Verb.RunCommand, Verb.RunBundledScript, Verb.ReadFile, Verb.WriteFile, Verb.SearchFiles, Verb.ShareFile,
        )
        private val WORKSPACE_WRITE_VERBS = setOf(
            Verb.RunCommand, Verb.RunBundledScript, Verb.WriteFile, Verb.ShareFile, Verb.RootCommand,
            Verb.SkillSave, Verb.MemorySave, Verb.MemoryEdit, Verb.ProfileEdit, Verb.SettingsSet,
        )
        private val EXTERNAL_VERBS = setOf(
            Verb.ScheduleTask, Verb.SpawnAgent, Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation,
        )
        private val SCREEN_WRITE_VERBS = setOf(
            Verb.Tap, Verb.TypeText, Verb.ClearText, Verb.Scroll, Verb.Swipe, Verb.Back, Verb.PressKey,
            Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.AppAfterTask,
            Verb.SetAlarm, Verb.SetTimer, Verb.ShowAlarms, Verb.AddCalendarEvent, Verb.BulkAddCalendarEvents, Verb.SetWheel,
            Verb.Navigate, Verb.SiteCommand, Verb.Tabs, Verb.Upload, Verb.SiteMode, Verb.Script, Verb.Interact, Verb.KeepOpen,
            Verb.PhoneRun, Verb.PhoneDialog, Verb.PhoneGesture, Verb.PhoneCommand,
        )
        private val PARALLEL_SAFE_VERBS = setOf(
            Verb.ReadFile, Verb.SearchFiles, Verb.Downloads, Verb.WebSearch,
            Verb.ViewUserScreen, Verb.ViewPastScreens, Verb.MemorySearch,
            Verb.KnowledgeSearch, Verb.KnowledgeRead, Verb.SkillSearch, Verb.SkillRead,
            Verb.HistoryRead, Verb.SettingsList,
        )

        /**
         * 由 [Verb] 推导默认 [ToolTraits]（供只持有 [ToolInfo] / [Verb] 的旧调用方平滑过渡）。
         */
        fun fromVerb(
            verb: Verb,
            capability: String? = null,
            concurrency: Concurrency = if (verb in PARALLEL_SAFE_VERBS) Concurrency.ParallelSafe else Concurrency.Serial,
            offScreen: Boolean = false,
            effectOverride: EffectKind? = null,
        ): ToolTraits {
            val effect = effectOverride ?: when (verb) {
                in EXTERNAL_VERBS -> EffectKind.External
                in WORKSPACE_WRITE_VERBS -> EffectKind.WorkspaceWrite
                in SCREEN_WRITE_VERBS -> if (offScreen) EffectKind.WorkspaceWrite else EffectKind.ScreenWrite
                else -> EffectKind.ReadOnly
            }
            val retention = when (verb) {
                in REFERENCE_VERBS -> ContextRetention.PinReference
                in INTERFACE_EPHEMERAL_VERBS -> ContextRetention.InterfaceEphemeral
                else -> ContextRetention.ShrinkIfLarge
            }
            val tier = if (verb in MAIN_MODEL_ONLY_VERBS) ModelTier.MainModelOnly else ModelTier.Default
            val diagnostic = verb in DIAGNOSTIC_READ_VERBS
            val interaction = when (verb) {
                Verb.AskUser -> InteractionKind.AskUser
                Verb.RequestConfirmation -> InteractionKind.RequestConfirmation
                Verb.HandToUser -> InteractionKind.HandToUser
                Verb.HandleObstacle -> InteractionKind.HandleObstacle
                Verb.EndNotDone -> InteractionKind.EndNotDone
                else -> InteractionKind.None
            }
            val memory = when (verb) {
                Verb.MemorySearch -> MemoryAccess.Search
                Verb.MemorySave -> MemoryAccess.Save
                Verb.MemoryEdit -> MemoryAccess.Edit
                else -> MemoryAccess.None
            }
            return ToolTraits(
                effect = effect,
                concurrency = concurrency,
                retention = retention,
                modelTier = tier,
                surfaceKind = capability,
                fullScreenSnapshot = verb in FULL_SCREEN_VERBS,
                recallsPastScreens = verb == Verb.ViewPastScreens,
                userInteraction = verb in USER_INTERACTION_VERBS,
                switchesPlace = verb in SWITCH_PLACE_VERBS,
                diagnosticRead = diagnostic,
                pageRead = diagnostic || verb in PAGE_READ_EXTRA_VERBS,
                modifiesScreenState = !offScreen && verb in SCREEN_STATE_CHANGE_VERBS,
                interactionKind = interaction,
                memoryAccess = memory,
                freshRead = verb in FRESH_READ_VERBS,
                collectionOp = verb in COLLECTION_OP_VERBS,
                siteEntry = verb in SITE_ENTRY_VERBS,
                lookupRead = verb in LOOKUP_READ_VERBS,
                navigable = verb == Verb.Navigate,
                textInput = verb == Verb.TypeText,
                usesFocusedEditable = verb == Verb.TypeText || verb == Verb.ClearText,
                elementSnapshot = verb == Verb.ListElements,
                readsSkill = verb == Verb.SkillRead,
                hidesAnswerInRecord = verb == Verb.AskUser,
                recordedInActivity = verb != Verb.Wait && interaction == InteractionKind.None,
                schedulesTask = verb == Verb.ScheduleTask,
                delegatesTask = verb == Verb.SpawnAgent,
                modifiesProfile = verb == Verb.ProfileEdit,
                writesSkill = verb == Verb.SkillSave,
            )
        }

        /**
         * 由 [AgentTool] 声明的属性推导默认 [ToolTraits]。
         */
        internal fun fromAgentTool(tool: AgentTool): ToolTraits {
            val effect = when {
                tool.risk == Risk.Read -> EffectKind.ReadOnly
                tool.verb in EXTERNAL_VERBS -> EffectKind.External
                tool.writesScreen -> EffectKind.ScreenWrite
                tool.offScreen && tool.risk == Risk.External -> EffectKind.External
                tool.offScreen -> EffectKind.WorkspaceWrite
                tool.risk == Risk.External -> EffectKind.External
                else -> EffectKind.ScreenWrite
            }
            val surface = when {
                tool.offScreen -> tool.capability
                tool.capability != null -> tool.capability
                tool.withScreens -> "screen"
                else -> null
            }
            return fromVerb(
                verb = tool.verb,
                capability = surface,
                concurrency = tool.concurrency,
                offScreen = tool.offScreen,
                effectOverride = effect,
            )
        }
    }
}
