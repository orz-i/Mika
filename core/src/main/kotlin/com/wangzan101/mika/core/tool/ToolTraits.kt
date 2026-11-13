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
) {
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
