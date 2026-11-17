package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.model.ToolSpec

/**
 * 工具分两层发给模型:一直都在的(核心的通用工具、不收的能力),和按能力分的几组(收着时只有入口工具,用短说明;
 * 第一次用到时展开成整组、换回全文,本任务里一直留着)。只在有屏幕的那几组展开时才提供的通用工具(接管、报障碍、回看画面、一次多步)
 * 算作 [SCREENS] 一组,任何一块屏展开它就展开。
 *
 * 同时支持细粒度按需发现(tool_search / tool_activate):按单个工具或子主题([Slot.subtopic])增量激活延迟工具时,
 * 新激活的 [ToolSpec] 严格按激活先后 **Append-only 追加在工具表末尾**,不改动既有区 0 工具顺序与字节前缀。
 */
internal object ToolGroups {
    /** 有屏幕的几组共用的那一组。 */
    const val SCREENS = "screens"

    /** 冻结下来的一个工具:全文定义、收着时的入口定义(不是入口为 null)、所属的组(一直都在为 null)、所属子主题。 */
    class Slot(
        val full: ToolSpec,
        val entry: ToolSpec?,
        val group: String?,
        val subtopic: String? = subtopicOf(full.name, group),
    ) {
        val topicId: String? get() = if (group != null && subtopic != null) "$group:$subtopic" else group
    }

    /**
     * 此刻发给模型的工具:[expanded] 是已展开的组(含 [SCREENS]);[extra] 是循环自己另给、属于某一组的定义
     * (一次多步属于 [SCREENS]),排在那一组的末尾,只在那一组展开时给;
     * [activated] 是经 tool_activate 按需增量激活的工具名,严格按激活先后追加在最末尾以保持 Zone 0 Prefix Cache 稳定。
     */
    fun layout(
        slots: List<Slot>,
        expanded: Set<String>,
        extra: Map<String, List<ToolSpec>> = emptyMap(),
        activated: Collection<String> = emptyList(),
    ): List<ToolSpec> {
        val base = order(slots).flatMap { g ->
            val members = slots.filter { it.group == g }
            if (g == null || g in expanded) members.map { it.full } + extra[g].orEmpty()
            else members.mapNotNull { it.entry }
        }
        if (activated.isEmpty()) return base
        val present = base.mapTo(HashSet()) { it.name }
        val byName = slots.associateBy { it.full.name }
        val appended = activated.distinct().mapNotNull { name ->
            if (name in present) null else byName[name]?.full
        }
        return base + appended
    }

    /** 各组的先后:一直都在的、[SCREENS]、再按能力第一次出现的顺序。 */
    fun order(slots: List<Slot>): List<String?> = listOf(null, SCREENS) + slots.mapNotNull { it.group }.filter { it != SCREENS }.distinct()

    /** 按工具名与所属组推导细粒度子主题,避免一次性展开整组(如 57 个手机工具)。 */
    fun subtopicOf(name: String, group: String?): String? = when (group) {
        null -> null
        SCREENS -> "handover"
        "phone" -> when (name) {
            "phone_observe", "phone_list_elements", "phone_screenshot", "phone_describe",
            "phone_find_visually", "phone_diagnose",
            -> "observe"
            "phone_tap", "phone_type_text", "phone_clear_text", "phone_scroll", "phone_swipe",
            "phone_back", "phone_press_key", "phone_gesture", "phone_set_wheel", "phone_dialog",
            -> "ui"
            "phone_query", "phone_find", "phone_extract", "phone_collect",
            "phone_wait", "phone_event", "phone_run", "phone_run_shortcut", "phone_navigate_to", "phone_system_command",
            -> "automation"
            "phone_list_apps", "phone_launch_app", "phone_open_link",
            "phone_share_text", "phone_app_after_task",
            -> "app"
            "phone_set_alarm", "phone_set_timer", "phone_show_alarms",
            "phone_add_calendar_event", "phone_calendar_bulk_insert",
            -> "schedule"
            "phone_inspect_network", "phone_wait_network", "phone_get_network_response", "phone_probe_ebpf" -> "network"
            else -> "ui"
        }
        "web" -> when (name) {
            "web_read_page", "web_observe", "web_list_elements", "web_screenshot",
            "web_describe", "web_find_visually", "web_diagnose", "web_capture",
            -> "observe"
            "web_navigate", "web_tap", "web_type_text", "web_clear_text", "web_scroll",
            "web_swipe", "web_back", "web_press_key", "web_interact", "web_wait_for",
            -> "ui"
            "web_evaluate_js", "web_run_site_command", "web_script", "web_tabs",
            "web_downloads", "web_upload", "web_site_mode", "web_keep_open",
            -> "automation"
            else -> "ui"
        }
        "sandbox" -> when (name) {
            "read_file", "write_file", "search_files", "share_file" -> "files"
            else -> "exec"
        }
        else -> "general"
    }

    /**
     * 首次直接调用未展开能力组内的工具(入口工具或模型凭记忆直接调用的折叠工具)时,
     * 仅按细粒度子主题增量激活最小必要工具集,避免一次性把整组数十个工具(如全部 phone 工具)塞进上下文。
     */
    fun autoExpandTopics(toolName: String, group: String?, currentScreen: String?): List<String> = when (group) {
        null -> emptyList()
        SCREENS -> listOfNotNull(currentScreen?.let { "$it:observe" }, currentScreen?.let { "$it:ui" }, "$SCREENS:handover")
        "phone" -> when (val sub = subtopicOf(toolName, group)) {
            "app" -> listOf("phone:app", "phone:observe", "phone:ui", "$SCREENS:handover")
            "observe", "ui" -> listOf("phone:observe", "phone:ui", "$SCREENS:handover")
            null -> emptyList()
            else -> listOf("phone:$sub")
        }
        "web" -> when (val sub = subtopicOf(toolName, group)) {
            "ui" -> listOf("web:ui", "web:observe", "$SCREENS:handover")
            null -> emptyList()
            else -> listOf("web:$sub")
        }
        "sandbox" -> listOf("sandbox:exec", "sandbox:files")
        else -> listOfNotNull(subtopicOf(toolName, group)?.let { "$group:$it" })
    }

    /** 该次自动激活是否同时打开所属能力组的基础提示词段落(如 phone/web UI、sandbox)。 */
    fun opensPromptSection(toolName: String, group: String?): Boolean = when (group) {
        null -> false
        SCREENS, "sandbox" -> true
        "phone" -> subtopicOf(toolName, group) in setOf("app", "observe", "ui")
        "web" -> subtopicOf(toolName, group) in setOf("observe", "ui")
        else -> true
    }

    /** 子主题别名归一化。 */
    fun normalizeSubtopic(raw: String): String = when (val s = raw.trim().lowercase()) {
        "interact", "interaction", "control", "controls", "action", "actions" -> "ui"
        "read", "screen", "vision", "inspect", "observation" -> "observe"
        "query", "script", "scripts", "batch" -> "automation"
        "calendar", "alarm", "alarms", "timer", "timers", "clock" -> "schedule"
        "http", "api", "capture", "packet", "packets", "net" -> "network"
        "launch", "package", "packages", "apps" -> "app"
        "file", "fs", "workspace" -> "files"
        "command", "commands", "shell", "bash", "run" -> "exec"
        "obstacle", "takeover", "recall", "screens" -> "handover"
        else -> s
    }
}
