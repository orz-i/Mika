package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.prompt.Prompts

/** 细粒度工具目录条目（供 tool_search 返回）。 */
internal data class ToolCatalogEntry(
    val name: String,
    val capability: String?,
    val subtopic: String?,
    val topicId: String?,
    val active: Boolean,
    val isEntry: Boolean,
    val summary: String,
    val description: String,
)

/** 延迟子主题摘要（供 tool_search 列出可按子主题激活的分组）。 */
internal data class TopicSummary(
    val topic: String,
    val capability: String,
    val subtopic: String,
    val totalCount: Int,
    val deferredCount: Int,
    val tools: List<String>,
)

/** tool_activate 的激活结果。 */
internal data class ActivationResult(
    val activated: List<ToolSpec>,
    val alreadyActive: List<String>,
    val unknown: List<String>,
    val totalActivatedCount: Int,
)

/** 挂在 [ToolContext] 上的动态工具发现与按需激活服务接口。 */
internal interface ToolDiscovery {
    fun search(
        query: String? = null,
        capability: String? = null,
        topic: String? = null,
        includeActive: Boolean = false,
        limit: Int = 20,
    ): List<ToolCatalogEntry>

    fun deferredTopics(capability: String? = null): List<TopicSummary>

    fun activate(
        tools: List<String>? = null,
        topic: String? = null,
        capability: String? = null,
    ): ActivationResult

    companion object {
        val KEY = CapabilityKey<ToolDiscovery>("tool_discovery")
    }
}

/**
 * 本任务的工具:[all] 是各能力与核心可能提供的全部工具(按发给模型的顺序),本任务能调用的按配置筛过。
 * 每次调用按名字找到工具再执行,分发器不认识具体工具。[groupOf] 给出工具所属的组(见 [ToolGroups];一直都在为 null)。
 */
internal class ToolRegistry(private val ctx: ToolContext, all: List<AgentTool>, private val groupOf: (AgentTool) -> String? = { null }) {
    private val declared: Map<String, AgentTool> = all.associateBy { it.name }
    private val tools: Map<String, AgentTool> = all.filter { it.available(ctx) }.associateBy { it.name }

    init {
        check(declared.size == all.size) { "duplicate tool names" }
    }

    operator fun get(name: String): AgentTool? = tools[name]

    /** 按名字查工具的声明(风险、判定目标……),本任务没提供的也查得到;不认识的为 null。 */
    fun declared(name: String): AgentTool? = declared[name]

    /**
     * 第一次取目录时冻结本 Run 的工具定义(全文、入口的短说明、所属的组与子主题);后续端口状态变化只能影响执行,不能改提示词前缀。
     * 入口的短说明在 prompts/entries/<工具名>.md,参数和全文一样。
     */
    private val frozen: List<ToolGroups.Slot> by lazy {
        tools.values.filter { it.offered(ctx) }.map { t ->
            val d = t.def(ctx)
            val g = groupOf(t)
            ToolGroups.Slot(
                d.spec(),
                t.entry?.let { ToolDef(d.name, Prompts.entry(d.name), d.props, d.required).spec() },
                g,
                ToolGroups.subtopicOf(d.name, g),
            )
        }
    }

    /** 本任务冻结的全部槽位。 */
    fun slots(): List<ToolGroups.Slot> = frozen

    /** 按名字查本任务冻结的槽位。 */
    fun slot(name: String): ToolGroups.Slot? = frozen.firstOrNull { it.full.name == name }

    /** 本任务提供的全部工具(每组都展开、全文)。 */
    fun definitions(): List<ToolSpec> = frozen.map { it.full }

    /** 此刻发给模型的工具:只展开 [expanded] 里的组,并将 [activated] 按需激活的工具追加在末尾(见 [ToolGroups.layout])。 */
    fun layered(
        expanded: Set<String>,
        extra: Map<String, List<ToolSpec>>,
        activated: Collection<String> = emptyList(),
    ): List<ToolSpec> = ToolGroups.layout(frozen, expanded, extra, activated)

    /** 组 [group] 里有发给模型的入口工具。 */
    fun hasEntry(group: String): Boolean = frozen.any { it.group == group && it.entry != null }

    /** 组 [group] 里发给模型的工具名(按登记的顺序)。 */
    fun members(group: String): List<String> = frozen.filter { it.group == group }.map { it.full.name }

    /** 能放进 do_steps 的工具名(本任务没提供的也列),按登记的顺序。 */
    fun batchable(): List<String> = declared.values.filter { it.batchable }.map { it.name }

    /** 「未知工具」的说明里列出的名字:随时可用的那些(不动屏幕的一组看运行环境就绪,不列)。 */
    fun listedNames(): List<String> = tools.values.filter { !it.offScreen }.map { it.name }

    /** 检索工具目录（支持按关键词、能力组、子主题过滤）。 */
    fun searchCatalog(
        query: String?,
        capability: String?,
        topic: String?,
        expanded: Set<String>,
        activated: Set<String>,
        includeActive: Boolean = false,
        limit: Int = 20,
    ): List<ToolCatalogEntry> {
        fun isFullyActive(s: ToolGroups.Slot): Boolean =
            s.group == null || s.group in expanded || s.full.name in activated

        val filtered = frozen.filter { s ->
            if (s.full.name == "tool_search" || s.full.name == "tool_activate") return@filter false
            if (!capability.isNullOrBlank() && !s.group.equals(capability.trim(), ignoreCase = true)) return@filter false
            if (!topic.isNullOrBlank() && !matchesTopic(s, topic, capability)) return@filter false
            true
        }

        val q = query?.trim()?.lowercase().orEmpty()
        if (q.isEmpty()) {
            val pool = if (includeActive) filtered else filtered.filter { !isFullyActive(it) }
            return pool.take(limit).map { toCatalogEntry(it, isFullyActive(it)) }
        }

        val tokens = q.split(Regex("[\\s,;:_/|-]+")).filter { it.isNotEmpty() }.distinct()
        val scored = filtered.mapNotNull { s ->
            val score = scoreSlot(s, q, tokens)
            if (score <= 0) null else s to score
        }.sortedWith(
            compareByDescending<Pair<ToolGroups.Slot, Int>> { !isFullyActive(it.first) }
                .thenByDescending { it.second },
        )

        val primary = if (includeActive) scored else scored.filter { !isFullyActive(it.first) }
        val chosen = (if (primary.isNotEmpty()) primary else scored).take(limit)
        return chosen.map { (s, _) -> toCatalogEntry(s, isFullyActive(s)) }
    }

    /** 汇总当前尚未全量展开的子主题列表。 */
    fun deferredTopics(
        expanded: Set<String>,
        activated: Set<String>,
        capability: String? = null,
    ): List<TopicSummary> {
        return frozen
            .filter { s ->
                s.group != null &&
                    (capability.isNullOrBlank() || s.group.equals(capability.trim(), ignoreCase = true))
            }
            .groupBy { s -> s.topicId ?: s.group!! }
            .mapNotNull { (topicId, members) ->
                val cap = members.first().group ?: return@mapNotNull null
                val sub = members.first().subtopic ?: cap
                val deferred = members.filter { s -> s.group !in expanded && s.full.name !in activated && s.entry == null }
                if (deferred.isEmpty() && cap in expanded) return@mapNotNull null
                TopicSummary(
                    topic = topicId,
                    capability = cap,
                    subtopic = sub,
                    totalCount = members.size,
                    deferredCount = deferred.size,
                    tools = members.map { it.full.name },
                )
            }
    }

    /** 按子主题与能力过滤匹配槽位。 */
    fun matchesTopic(s: ToolGroups.Slot, rawTopic: String, capability: String? = null): Boolean {
        val group = s.group?.lowercase() ?: return false
        if (!capability.isNullOrBlank() && group != capability.trim().lowercase()) return false
        val t = rawTopic.trim().lowercase()
        if (':' in t) {
            val parts = t.split(':', limit = 2)
            val capPart = parts[0].trim()
            val subPart = ToolGroups.normalizeSubtopic(parts[1])
            return group == capPart && (s.subtopic?.lowercase() == subPart || subPart == "all" || subPart == "*")
        }
        val norm = ToolGroups.normalizeSubtopic(t)
        return s.subtopic?.lowercase() == norm || group == t || s.topicId?.lowercase() == t
    }

    private fun scoreSlot(s: ToolGroups.Slot, fullQuery: String, tokens: List<String>): Int {
        val name = s.full.name.lowercase()
        val desc = s.full.description.lowercase()
        val schema = s.full.parametersJsonSchema.lowercase()
        val group = s.group?.lowercase().orEmpty()
        val sub = s.subtopic?.lowercase().orEmpty()
        val topicId = s.topicId?.lowercase().orEmpty()
        val label = tools[s.full.name]?.label?.lowercase().orEmpty()

        var score = 0
        if (name == fullQuery || topicId == fullQuery) score += 120
        else if (name.contains(fullQuery)) score += 60
        if (label.isNotEmpty() && label.contains(fullQuery)) score += 50

        for (tok in tokens) {
            val normTok = ToolGroups.normalizeSubtopic(tok)
            if (name == tok) score += 80
            else if (name.split('_').any { it == tok }) score += 45
            else if (name.contains(tok)) score += 30
            if (sub == normTok || sub == tok || topicId == tok) score += 35
            if (group == tok) score += 20
            if (label.isNotEmpty() && label.contains(tok)) score += 30
            if (desc.contains(tok)) score += 15
            if (schema.contains(tok)) score += 5
        }
        return score
    }

    private fun toCatalogEntry(s: ToolGroups.Slot, active: Boolean): ToolCatalogEntry {
        val fullDesc = s.full.description.trim()
        val firstLine = fullDesc.lineSequence().firstOrNull()?.trim().orEmpty()
        val summary = if (firstLine.length <= 180) firstLine else firstLine.take(179) + "…"
        return ToolCatalogEntry(
            name = s.full.name,
            capability = s.group,
            subtopic = s.subtopic,
            topicId = s.topicId,
            active = active,
            isEntry = s.entry != null,
            summary = summary,
            description = fullDesc,
        )
    }
}
