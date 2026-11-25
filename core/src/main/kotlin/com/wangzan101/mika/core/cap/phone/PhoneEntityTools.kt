package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.text.TextEntity
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import com.agent.use.ui.UiTreeNode
import com.wangzan101.mika.core.uistate.session
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把一段文字或当前屏上的文字交给端上模型，标出人名、电话、地址、公司、快递单号、航班车次一类实体。
 *
 * 和 `phone_extract` 的分工：那个给的是**界面结构**（节点、列表行、表单字段），这个给的是**语义**——
 * 一串地址从哪到哪、哪个长数字是电话而不是订单号。给了 `text` 就只分析文字、不读屏；
 * 不给就读一屏，逐条文本分析，只回有实体的条目，并带上节点的 ref，方便接着点。
 *
 * 模型只输出标签、不给置信度（argmax 在图里），所以这里也不编一个分数给模型看。
 */
internal object PhoneEntities : PhoneTool {
    override val verb = Verb.PhoneExtract
    override val risk = Risk.Read
    override val label = "识别文字里的实体"
    override val def = ToolDef(
        "phone_entities",
        Prompts.tool("phone_entities"),
        mapOf(
            "text" to str("Text to analyse, up to 2000 characters. Omit to read the current screen instead."),
            "maxItems" to int("When reading the screen, how many text nodes to analyse at most, 1-200 (default 50).", 1, 200),
        ),
    )

    override fun available(ctx: ToolContext): Boolean = ctx.config.textEntities != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ctx.session.textOnly {
        val extractor = ctx.config.textEntities
            ?: return@textOnly fail("unavailable", "This build has no on-device entity model.")
        val given = a.str("text")?.takeIf { it.isNotBlank() }
        if (given != null) {
            val text = given.take(MAX_TEXT)
            return@textOnly ok {
                put("source", "text")
                put("items", JsonArray(listOf(item(text, ref = null, entities = extractor.extract(text)))))
            }
        }

        ctx.session.observe(withImage = false, recordFrame = false)
        val screen = ctx.session.lastSnapshot
            ?: return@textOnly fail("read_failed", "Could not read the current screen.")
        val max = a.int("maxItems", 1, 200) ?: DEFAULT_ITEMS
        val items = mutableListOf<JsonObject>()
        var analysed = 0

        fun walk(node: UiTreeNode) {
            if (analysed >= max) return
            if (!node.secret) {
                listOfNotNull(node.text, node.description)
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .forEach { value ->
                        if (analysed < max) {
                            analysed++
                            val text = value.take(MAX_TEXT)
                            val entities = extractor.extract(text)
                            if (entities.isNotEmpty()) items += item(text, node.ref, entities)
                        }
                    }
            }
            node.children.forEach(::walk)
        }
        screen.windows.forEach { window -> window.nodes.forEach(::walk) }

        ok {
            put("source", "screen")
            put("snapshotId", screen.snapshotId)
            put("currentApp", screen.packageName ?: screen.unreadableApp)
            put("items", JsonArray(items))
            put("texts", analysed)
            put("truncated", analysed >= max)
            if (items.isEmpty()) put("note", "No entity was recognised in the visible text.")
        }
    }

    private fun item(text: String, ref: String?, entities: List<TextEntity>) = buildJsonObject {
        put("text", Untrusted.wrapWithin(text, MAX_TEXT))
        ref?.let { put("ref", it) }
        put("entities", JsonArray(entities.map { e ->
            buildJsonObject {
                put("kind", e.kind)
                put("start", e.start)
                put("end", e.end)
                put("text", Untrusted.wrapWithin(e.text, MAX_ENTITY))
            }
        }))
    }

    private const val MAX_TEXT = 2_000
    private const val MAX_ENTITY = 200
    private const val DEFAULT_ITEMS = 50
}
