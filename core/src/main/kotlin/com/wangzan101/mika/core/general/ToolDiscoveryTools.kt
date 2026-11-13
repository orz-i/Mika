package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.ContextRetention
import com.wangzan101.mika.core.tool.EffectKind
import com.wangzan101.mika.core.tool.ModelTier
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolDiscovery
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.ToolTraits
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.bool
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.tool.strArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 细粒度延迟工具目录检索：按关键词、能力组或子主题查询尚未展开的工具与子主题，无需一次性展开整组。
 */
internal object ToolSearch : AgentTool {
    override val verb = Verb.KnowledgeSearch
    override val label = "检索可用工具"
    override val risk = Risk.Read
    override val offScreen = true
    override val concurrency = Concurrency.ParallelSafe
    override val traits = ToolTraits(
        effect = EffectKind.ReadOnly,
        concurrency = Concurrency.ParallelSafe,
        retention = ContextRetention.PinReference,
        modelTier = ModelTier.Default,
    )
    override val def = ToolDef(
        "tool_search",
        Prompts.tool("tool_search"),
        mapOf(
            "query" to str("Optional keywords matching tool name, topic, description, or parameters (e.g. 'tap', 'network', 'calendar', 'cookie', 'file'). Omit to browse deferred tools and topics."),
            "capability" to str("Optional capability filter (e.g. phone, web, sandbox, screens)."),
            "topic" to str("Optional subtopic filter (e.g. observe, ui, automation, app, schedule, network, files, exec, handover, or phone:ui)."),
            "includeActive" to bool("If true, also include tools already active in your current toolset (default false)."),
            "limit" to int("Maximum number of tool matches to return, 1-50 (default 15).", 1, 50),
        ),
        emptyList(),
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val discovery = ctx[ToolDiscovery.KEY]
            ?: return fail("discovery_unavailable", "Tool discovery catalog is not initialized in this context.")
        val query = a.str("query", max = 200)
        val capability = a.str("capability", max = 60)
        val topic = a.str("topic", max = 80)
        val includeActive = a.bool("includeActive", false)
        val limit = a.int("limit", 1, 50) ?: 15

        val matches = discovery.search(
            query = query,
            capability = capability,
            topic = topic,
            includeActive = includeActive,
            limit = limit,
        )
        val topics = discovery.deferredTopics(capability = capability)
        return ok {
            put("matches", JsonArray(matches.map { m ->
                buildJsonObject {
                    put("name", m.name)
                    m.capability?.let { put("capability", it) }
                    m.topicId?.let { put("topic", it) }
                    put("active", m.active)
                    if (m.isEntry) put("entry", true)
                    put("description", m.summary)
                }
            }))
            put("topics", JsonArray(topics.map { t ->
                buildJsonObject {
                    put("topic", t.topic)
                    put("capability", t.capability)
                    put("subtopic", t.subtopic)
                    put("deferredCount", t.deferredCount)
                    put("totalCount", t.totalCount)
                    put("tools", JsonArray(t.tools.map(::JsonPrimitive)))
                }
            }))
        }
    }
}

/**
 * 细粒度按需工具激活：按单个/多个工具名或子主题增量激活工具，新激活的 ToolSpec 严格追加在工具表末尾以保持 Prefix Cache 稳定。
 */
internal object ToolActivate : AgentTool {
    override val verb = Verb.KnowledgeRead
    override val label = "按需激活工具"
    override val risk = Risk.Read
    override val offScreen = true
    override val concurrency = Concurrency.ParallelSafe
    override val traits = ToolTraits(
        effect = EffectKind.ReadOnly,
        concurrency = Concurrency.ParallelSafe,
        retention = ContextRetention.PinReference,
        modelTier = ModelTier.Default,
    )
    override val def = ToolDef(
        "tool_activate",
        Prompts.tool("tool_activate"),
        mapOf(
            "tools" to strArray("Specific deferred tool names to activate (e.g. [\"phone_observe\", \"phone_tap\"]). Appended to the end of the tool list.", 30),
            "topic" to str("Subtopic or capability:subtopic to activate as a focused bundle (e.g. 'phone:observe', 'phone:ui', 'phone:network', 'web:ui', 'sandbox:files', 'screens:handover')."),
            "capability" to str("Optional capability filter when activating by subtopic (e.g. 'phone', 'web', 'sandbox')."),
        ),
        emptyList(),
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val discovery = ctx[ToolDiscovery.KEY]
            ?: return fail("discovery_unavailable", "Tool discovery catalog is not initialized in this context.")
        val tools = a.strList("tools", 1, 30, itemMax = 80)
        val topic = a.str("topic", max = 80)
        val capability = a.str("capability", max = 60)

        if (tools.isNullOrEmpty() && topic.isNullOrBlank()) {
            throw ArgError("tools", "must provide non-empty tools list or a topic to activate")
        }

        val res = discovery.activate(tools = tools, topic = topic, capability = capability)
        if (res.activated.isEmpty() && res.alreadyActive.isEmpty()) {
            val detail = if (res.unknown.isNotEmpty()) "Unknown tools: ${res.unknown.joinToString(", ")}"
            else "No matching tools found for topic '${topic.orEmpty()}'"
            return fail("unknown_tool_or_topic", "$detail. Use tool_search to inspect available tool names and topics.", recoverable = true)
        }

        return ok {
            put("activated", JsonArray(res.activated.map { JsonPrimitive(it.name) }))
            if (res.alreadyActive.isNotEmpty()) {
                put("alreadyActive", JsonArray(res.alreadyActive.map(::JsonPrimitive)))
            }
            if (res.unknown.isNotEmpty()) {
                put("unknown", JsonArray(res.unknown.map(::JsonPrimitive)))
            }
            put("activeCount", res.totalActivatedCount)
            put("specs", JsonArray(res.activated.map { spec ->
                buildJsonObject {
                    put("name", spec.name)
                    put("description", spec.description)
                }
            }))
        }
    }
}
