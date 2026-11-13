package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.memory.Change
import com.wangzan101.mika.core.memory.MemoryIndex
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemoryRejected
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.verify.Grounding
import java.text.Normalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private const val MEMORY_SEARCH_LIMIT = 8

/** 记忆和用户本次说过的话都给了才提供。 */
private fun memoryOn(ctx: ToolContext) = ctx.config.memory != null && ctx.config.utterances != null

internal object MemorySearch : AgentTool {
    override val verb = Verb.MemorySearch
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("memory_search",
        Prompts.tool("memory_search"),
        mapOf("query" to str("Words to look for, 1-100 characters.")), listOf("query"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = a.str("query", max = 100)!!
        val found = MemoryIndex(ctx.config.memory!!.items.value).search(q, MEMORY_SEARCH_LIMIT)
        ctx.noteMemorySearch(found.map { (m, _) -> m.id })
        return ok {
            put("memories", JsonArray(found.map { (m, _) ->
                buildJsonObject { put("id", m.id); put("kind", m.kind.name); put("content", m.content) }
            }))
        }
    }

    /** 记忆里的是用户以前说的:作为用户给的事实交给核查。 */
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull((r.content["memories"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { list ->
            Observation(ObsKind.UserSaid, list.joinToString("\n", "记忆检索「${o.argStr("query")}」:\n") { m -> ((m as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty() },
                source = "memory")
        })
    }
}

/** 只改 Mika 自己的记忆,不动屏幕和外部世界。 */
internal object MemorySave : AgentTool {
    override val verb = Verb.MemorySave
    override val risk = Risk.Read
    override val def = ToolDef("memory_save",
        Prompts.tool("memory_save"),
        mapOf(
            "kind" to str("fact = about the user or verified environment/project fact, preference = what the user likes (must come from the user), procedure = how a task or workflow is done.", listOf("fact", "preference", "procedure")),
            "content" to str("One sentence in the third person, at most 200 characters, e.g. 用户点外卖不要香菜 or 项目使用 Gradle 8.11 构建."),
            "quote" to str("The user's exact words this is based on (required for preference and user-stated facts; optional for fact/procedure when evidenceId is provided)."),
            "evidenceId" to int("Optional observation ID (obsId from a trusted tool result in this task) verifying an environment, project, or workflow fact.", min = 1),
            "source" to str("Optional source kind: user_said (default) or observed (for verified environment/project/workflow facts backed by an observation).", listOf("user_said", "observed")),
            "keywords" to buildJsonObject {
                put("type", "array"); put("description", "Optional, up to 8 search words incl. synonyms.")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("kind", "content"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val kind = a.str("kind")!!.let { k -> MemoryKind.entries.find { it.name == k } ?: throw ArgError("kind", "must be fact, preference or procedure") }
        val content = a.str("content", max = MemoryStore.MAX_CONTENT)!!
        val quote = a.str("quote", max = 1000)
        val evidenceId = a.int("evidenceId", 1, Int.MAX_VALUE)
        val sourceKind = a.str("source")?.also { s ->
            if (s != "user_said" && s != "observed") throw ArgError("source", "must be user_said or observed")
        } ?: if (evidenceId != null) "observed" else "user_said"
        val keywords = a.strList("keywords", 0, MemoryStore.MAX_KEYWORDS, itemMax = 30) ?: emptyList()

        // 个人偏好严格要求出自用户本人原话，不允许经观察结果或工具输出写入
        if (kind == MemoryKind.preference && (sourceKind != "user_said" || evidenceId != null)) {
            return fail("preference_requires_user", Feedback["memory.preference_requires_user"], recoverable = false)
        }

        val utterances = ctx.config.utterances!!
        val store = ctx.config.memory!!
        val memorySource = if (sourceKind == "user_said") {
            val userQuote = quote ?: return fail("quote_not_from_user", Feedback["memory.quote_not_from_user"], recoverable = false)
            // 引文出自本轮用户原话,或严格登记的上一轮明确确认;屏幕、网页和助手建议本身都不算
            if (!utterances.containsMemoryQuote(userQuote)) return fail("quote_not_from_user",
                Feedback["memory.quote_not_from_user"], recoverable = false)
            // 引文对上了,内容里的具体值(地址、号码、名字)也得是用户说过的,不能从屏幕上补
            if ((listOf(content) + keywords).any { utterances.unsupportedMemorySpecifics(it, userQuote).isNotEmpty() }) return fail("content_not_from_user",
                Feedback["memory.content_not_from_user"], recoverable = false)
            MemorySource("user_said", userQuote.trim(), ctx.config.conversationId, store.clock(),
                ctx.config.runSpec?.provenance?.origin?.name)
        } else {
            val obs = resolveObservedSource(ctx, evidenceId, quote)
            if (obs == null) {
                if (evidenceId == null && quote != null && hasUntrustedMatch(ctx, quote)) {
                    return fail("evidence_untrusted", Feedback["memory.evidence_untrusted"], recoverable = false)
                }
                return fail("evidence_not_found", Feedback["memory.evidence_not_found"], recoverable = true)
            }
            if (obs.trust != Trust.Trusted) {
                return fail("evidence_untrusted", Feedback["memory.evidence_untrusted"], recoverable = false)
            }
            val corpus = listOfNotNull(obs.text, obs.place?.name, obs.title).joinToString("\n")
            val ground = Grounding.check("", (listOf(content) + keywords).joinToString("\n"), listOf(corpus) + utterances.all)
            if (ground.unmatched.isNotEmpty()) {
                return fail("content_not_grounded", Feedback["memory.content_not_grounded"], recoverable = false)
            }
            val evidenceLabel = quote?.trim()?.takeIf { it.isNotEmpty() }
                ?: ("evidence#${obs.id}" + (obs.place?.name?.let { " ($it)" } ?: ""))
            MemorySource("observed", evidenceLabel, ctx.config.conversationId, store.clock(),
                obs.source ?: ctx.config.runSpec?.provenance?.origin?.name)
        }
        val added = store.add(kind, content, keywords, memorySource)
        added.getOrNull()?.let(ctx::noteSaved)
        return added.fold(
            onSuccess = { c -> ok { put("id", c.item.id); put("kind", c.item.kind.name); put("status", c.item.status.name) } },
            onFailure = { e ->
                when ((e as? MemoryRejected)?.code) {
                    "sensitive" -> fail("sensitive", Feedback["memory.sensitive"], recoverable = false)
                    "memory_full" -> fail("memory_full", Feedback["memory.full"], recoverable = false)
                    "unavailable" -> fail("memory_unavailable", Feedback["memory.unavailable"], recoverable = false)
                    "too_long" -> throw ArgError("content", "must be 1-${MemoryStore.MAX_CONTENT} characters")
                    else -> throw e
                }
            },
        )
    }

    private fun resolveObservedSource(ctx: ToolContext, evidenceId: Int?, quote: String?): Observation? {
        val all = ctx.evidence.ledger.all
        if (evidenceId != null) return all.find { it.id == evidenceId }
        val q = norm(quote ?: return null)
        if (q.length < 2) return null
        return all.lastOrNull { it.trust == Trust.Trusted && norm(it.text).contains(q) }
    }

    private fun hasUntrustedMatch(ctx: ToolContext, quote: String): Boolean {
        val q = norm(quote)
        return q.length >= 2 && ctx.evidence.ledger.all.any { it.trust != Trust.Trusted && norm(it.text).contains(q) }
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).trim()
}

/** 用户本轮明确要求时,按 memory_search 返回的稳定编号改或删一条;变更交给现有结果提示与撤销机制。 */
internal object MemoryEdit : AgentTool {
    override val verb = Verb.MemoryEdit
    override val risk = Risk.Read
    override val def = ToolDef("memory_edit",
        Prompts.tool("memory_edit"),
        mapOf(
            "op" to str("update = replace content and optionally keywords; delete = forget the entry.", listOf("update", "delete")),
            "id" to str("Exact stable id returned by memory_search. Never guess an id or choose between multiple plausible entries."),
            "content" to str("For update: the replacement sentence, at most 200 characters."),
            "quote" to str("The current user message explicitly requesting this update or deletion."),
            "keywords" to buildJsonObject {
                put("type", "array"); put("description", "For update: optional replacement search words, up to 8.")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("op", "id", "quote"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val op = a.str("op")!!
        val id = a.str("id", max = 200)!!
        val quote = a.str("quote", max = 1000)!!
        val content = a.str("content", max = MemoryStore.MAX_CONTENT)
        val keywords = a.strList("keywords", 0, MemoryStore.MAX_KEYWORDS, itemMax = 30)
        if (op == "update" && content == null) throw ArgError("content", "required for update")
        val utterances = ctx.config.utterances!!
        // 用含引文的完整原话判断否定，避免模型只摘出「不要删除」里的「删除」。
        val messages = utterances.matchingCurrentMessages(quote)
        if (messages.isEmpty()) return fail("quote_not_from_user", Feedback["memory.edit_quote_not_from_user"], recoverable = false)
        val (request, negation) = when (op) {
            "update" -> UPDATE_REQUEST to UPDATE_NEGATION
            "delete" -> DELETE_REQUEST to DELETE_NEGATION
            else -> throw ArgError("op", "must be update or delete")
        }
        val requested = request.containsMatchIn(quote) && messages.none(negation::containsMatchIn)
        if (!requested) return fail("edit_not_requested", Feedback["memory.edit_not_requested"], recoverable = false)
        val candidates = ctx.memorySearchCandidates()
        if (candidates == null || id !in candidates) {
            return fail("not_found", Feedback["memory.not_found"], recoverable = true)
        }
        if (candidates.size > 1 && !quotesExactId(quote, id)) {
            return fail("ambiguous_target", Feedback["memory.ambiguous_target"], recoverable = false)
        }
        if (op == "update" && (listOf(content!!) + keywords.orEmpty()).any { utterances.unsupportedSpecifics(it, quote).isNotEmpty() }) {
            return fail("content_not_from_user", Feedback["memory.content_not_from_user"], recoverable = false)
        }
        val store = ctx.config.memory!!
        val changed: Result<Change> = when (op) {
            "update" -> store.update(id, content!!, keywords,
                MemorySource("user_said", quote.trim(), ctx.config.conversationId, store.clock(), ctx.config.runSpec?.provenance?.origin?.name))
            "delete" -> store.forget(id)
            else -> throw ArgError("op", "must be update or delete")
        }
        changed.getOrNull()?.let(ctx::noteSaved)
        return changed.fold(
            onSuccess = { c ->
                ok {
                    put("op", op); put("id", id)
                    when (c) {
                        is Change.Updated -> { put("kind", c.item.kind.name); put("status", c.item.status.name) }
                        is Change.Forgot -> put("deleted", true)
                        else -> Unit
                    }
                }
            },
            onFailure = { e ->
                when ((e as? MemoryRejected)?.code) {
                    "sensitive" -> fail("sensitive", Feedback["memory.sensitive"], recoverable = false)
                    "unavailable" -> fail("memory_unavailable", Feedback["memory.unavailable"], recoverable = false)
                    "not_found" -> fail("not_found", Feedback["memory.not_found"], recoverable = true)
                    "duplicate_id" -> fail("ambiguous_target", Feedback["memory.ambiguous_target"], recoverable = false)
                    "too_long" -> throw ArgError("content", "must be 1-${MemoryStore.MAX_CONTENT} characters")
                    else -> throw e
                }
            },
        )
    }

    private fun quotesExactId(quote: String, id: String): Boolean =
        Regex("(?<![A-Za-z0-9_-])" + Regex.escape(id) + "(?![A-Za-z0-9_-])").containsMatchIn(quote)

    private val UPDATE_REQUEST = Regex("修改|改成|改为|更新|更正|改掉|编辑|\\b(?:update|change|edit|correct)\\b", RegexOption.IGNORE_CASE)
    private val DELETE_REQUEST = Regex("删除|删掉|删了|忘掉|忘记|别记|不要记|取消记忆|\\b(?:delete|forget|remove)\\b", RegexOption.IGNORE_CASE)
    private val UPDATE_NEGATION = Regex("(?:不|别|无需|无须|不用|不必|不要|不能|不准|禁止)\\s*(?:要|用|再|去)?\\s*(?:修改|改成|改为|更新|更正|改掉|编辑)|\\b(?:do\\s+not|don't|dont|never|no\\s+need\\s+to)\\s+(?:update|change|edit|correct)\\b", RegexOption.IGNORE_CASE)
    private val DELETE_NEGATION = Regex("(?:不|别|无需|无须|不用|不必|不要|不能|不准|禁止)\\s*(?:要|用|再|去)?\\s*(?:删(?:除|掉|了)?|忘掉|忘记)|\\b(?:do\\s+not|don't|dont|never)\\s+(?:delete|forget|remove)\\b", RegexOption.IGNORE_CASE)
}
