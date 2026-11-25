package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.agent.use.ui.ScreenSig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.agent.use.ui.AtlasEviction

/**
 * 页面与高频控件批量命名器。
 * 优先调用轻量模型（短超时、只传脱敏后的静态锚点/资源 ID/控件轮廓）；模型不可用、超时、返回空或命中敏感拦截时，
 * 严格回退到 Activity 短名（如 `LauncherUI`）或 `#<短哈希>`。
 */
class AtlasNamer(
    private val model: ChatModel? = null,
    private val fallback: ChatModel? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    /**
     * 对 [atlas] 中尚未命名的页面（若指定 [targetSigIds] 则仅限该集合）批量命名。
     * 用户手动命名过（[AtlasScreen.TITLE_USER]）的页面绝不覆盖。
     */
    suspend fun nameScreens(atlas: AppAtlas, targetSigIds: Set<String>? = null): AppAtlas {
        val candidates = atlas.screens
            .filter { s ->
                s.titleSource != AtlasScreen.TITLE_USER &&
                    (targetSigIds == null || s.sigId in targetSigIds) &&
                    s.needsNaming()
            }
            .sortedWith(compareByDescending<AtlasScreen> { it.lastSeenMs }.thenByDescending { it.hits })
            .take(MAX_BATCH_SCREENS)

        if (candidates.isEmpty()) return disambiguateTitles(applyFallbacks(atlas, targetSigIds))

        val controlSlices = candidates.associate { s ->
            val topIndices = s.controls.withIndex()
                .sortedWith(compareByDescending<IndexedValue<AtlasControl>> { it.value.used }.thenByDescending { it.value.lastUsedMs })
                .take(MAX_CONTROLS_PER_SCREEN)
                .map { it.index }
            s.sigId to topIndices
        }

        val namedByModel = queryModel(atlas, candidates, controlSlices)

        val updatedScreens = atlas.screens.map { screen ->
            if (screen.titleSource == AtlasScreen.TITLE_USER) return@map screen
            val inScope = targetSigIds == null || screen.sigId in targetSigIds
            val parsed = namedByModel[screen.sigId]
            val nextTitle = parsed?.title
            val updatedTitle = when {
                nextTitle != null -> nextTitle
                inScope && screen.title.isBlank() -> screen.fallbackTitle()
                else -> screen.title
            }
            val updatedSource = when {
                nextTitle != null -> AtlasScreen.TITLE_LLM
                inScope && screen.title.isBlank() -> AtlasScreen.TITLE_ACTIVITY
                else -> screen.titleSource
            }
            val controlMeanings = parsed?.controlMeanings.orEmpty()
            val updatedControls = if (controlMeanings.isEmpty()) screen.controls else {
                val indexMap = controlSlices[screen.sigId].orEmpty()
                screen.controls.mapIndexed { origIdx, ctrl ->
                    val sliceIdx = indexMap.indexOf(origIdx)
                    val meaning = if (sliceIdx >= 0) controlMeanings[sliceIdx] else null
                    if (meaning != null && ctrl.meaning.isBlank()) ctrl.copy(meaning = meaning) else ctrl
                }
            }
            screen.copy(
                title = updatedTitle,
                titleSource = updatedSource,
                controls = updatedControls,
            )
        }
        return disambiguateTitles(atlas.copy(screens = updatedScreens))
    }

    /** 批量处理 [AtlasStore] 中指定各包的新增未命名页面并写回存储。 */
    suspend fun nameStoreScreens(store: AtlasStore, targetSigIdsByPackage: Map<String, Set<String>>) {
        for ((pkg, sigIds) in targetSigIdsByPackage) {
            if (sigIds.isEmpty()) continue
            val current = store.get(pkg) ?: continue
            val named = nameScreens(current, sigIds)
            if (named != current) {
                store.update(pkg) { latest ->
                    if (latest == null) null else mergeNames(latest, named)
                }
            }
        }
    }

    private fun applyFallbacks(atlas: AppAtlas, targetSigIds: Set<String>?): AppAtlas {
        var changed = false
        val nextScreens = atlas.screens.map { s ->
            val inScope = targetSigIds == null || s.sigId in targetSigIds
            if (inScope && s.title.isBlank()) {
                changed = true
                s.copy(title = s.fallbackTitle(), titleSource = AtlasScreen.TITLE_ACTIVITY)
            } else s
        }
        return if (changed) atlas.copy(screens = nextScreens) else atlas
    }

    private suspend fun queryModel(
        atlas: AppAtlas,
        candidates: List<AtlasScreen>,
        controlSlices: Map<String, List<Int>>,
    ): Map<String, NamedItem> {
        val models = listOfNotNull(model, fallback?.takeUnless { it === model })
        if (models.isEmpty()) return emptyMap()

        val payload = buildJsonObject {
            put("packageName", atlas.packageName)
            if (atlas.appLabel.isNotBlank()) put("appLabel", atlas.appLabel)
            putJsonArray("screens") {
                for (s in candidates) {
                    add(buildJsonObject {
                        put("sigId", s.sigId)
                        s.shortActivity?.let { put("shortActivity", it) }
                        put("anchors", JsonArray(AtlasSanitizer.cleanAnchors(s.anchors, MAX_PROMPT_ANCHORS).map(::JsonPrimitive)))
                        put("resourceIds", JsonArray(AtlasSanitizer.cleanResourceIds(s.resourceIds, MAX_PROMPT_RIDS).map(::JsonPrimitive)))
                        val indices = controlSlices[s.sigId].orEmpty()
                        if (indices.isNotEmpty()) {
                            putJsonArray("controls") {
                                indices.forEachIndexed { sliceIdx, origIdx ->
                                    val c = s.controls[origIdx]
                                    add(buildJsonObject {
                                        put("index", sliceIdx)
                                        put("role", c.selector.role)
                                        AtlasSanitizer.cleanSingleLine(c.selector.name, AtlasSanitizer.MAX_SELECTOR_NAME_LEN)
                                            ?.let { put("name", it) }
                                        c.selector.resourceId?.let { put("resourceId", it) }
                                    })
                                }
                            }
                        }
                    })
                }
            }
        }

        val allowedIds = candidates.mapTo(HashSet()) { it.sigId }
        for (candidate in models) {
            val parsed = try {
                withTimeoutOrNull(timeoutMs) {
                    val reply = candidate.complete(
                        PROMPT,
                        listOf(Message(Role.User, listOf(Part.Text(payload.toString())), Author.Mika)),
                        emptyList(),
                    )
                    parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }, allowedIds)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return emptyMap()
    }

    private fun mergeNames(latest: AppAtlas, named: AppAtlas): AppAtlas {
        val byId = named.screens.associateBy { it.sigId }
        return latest.copy(screens = latest.screens.map { cur ->
            if (cur.titleSource == AtlasScreen.TITLE_USER) return@map cur
            val n = byId[cur.sigId] ?: return@map cur
            val mergedTitle = if (cur.needsNaming() && n.title.isNotBlank()) n.title else cur.title
            val mergedSource = if (cur.needsNaming() && n.title.isNotBlank()) n.titleSource else cur.titleSource
            val meaningsBySel = n.controls.filter { it.meaning.isNotBlank() }
            val mergedControls = if (meaningsBySel.isEmpty()) cur.controls else {
                cur.controls.map { ctrl ->
                    if (ctrl.meaning.isNotBlank()) ctrl else {
                        val hit = meaningsBySel.firstOrNull { AtlasEviction.sameControl(it.selector, ctrl.selector) }
                        if (hit != null) ctrl.copy(meaning = hit.meaning) else ctrl
                    }
                }
            }
            cur.copy(title = mergedTitle, titleSource = mergedSource, controls = mergedControls)
        })
    }

    internal data class NamedItem(val title: String?, val controlMeanings: Map<Int, String>)

    companion object {
        /**
         * 一次批量命名最多等多久。这是跑在任务结束之后的离线活，用户已经拿到结果了，等得起；
         * 太短（曾经 12 秒）会让慢一点的模型总是超时，页面就永远只有 Activity 短名，提示里几个页面分不开。
         */
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MAX_BATCH_SCREENS = 10
        const val MAX_CONTROLS_PER_SCREEN = 6
        /** 消歧后缀取屏幕文字，太长会顶掉提示的其余内容。 */
        private const val DISAMBIGUATE_MAX_LEN = 8
        private const val MAX_PROMPT_ANCHORS = 12
        private const val MAX_PROMPT_RIDS = 12

        val PROMPT: String by lazy { Prompts.role("atlas_name") }

        /**
         * 同包内多个页面被命名成同一个标题时（同一个 Activity 的不同状态最常遇到，比如搜索框、搜索建议、搜索结果都叫「职位搜索页」），
         * 用各自独有的屏幕文字把标题分开，否则提示里几个页面长得一模一样，模型分不出「从这里点进去是哪一个」。
         * 已经有后缀的标题按「（」前面那段参与分组，重复跑不会越接越长。
         */
        internal fun disambiguateTitles(atlas: AppAtlas): AppAtlas {
            fun base(title: String) = title.substringBefore("（").trim()
            val groups = atlas.screens
                .filter { it.titleSource != AtlasScreen.TITLE_USER && it.title.isNotBlank() }
                .groupBy { base(it.title) }
            val renamed = HashMap<String, String>()
            for ((title, group) in groups) {
                if (group.size < 2 || title.isEmpty()) continue
                val anchorsById = group.associate { it.sigId to it.anchors.toSet() }
                for (s in group) {
                    val others = anchorsById.filterKeys { it != s.sigId }.values.flatten().toSet()
                    val unique = s.anchors.asSequence()
                        .mapNotNull { AtlasSanitizer.cleanSingleLine(it, DISAMBIGUATE_MAX_LEN) }
                        .filter { it.length >= 2 && it.none(Char::isDigit) && it !in others }
                        .maxByOrNull { it.length }
                    renamed[s.sigId] = if (unique != null) {
                        "$title（$unique）"
                    } else {
                        "$title ${ScreenSig.shortHashTag(s.sigId)}"
                    }
                }
            }
            if (renamed.isEmpty()) return atlas
            return atlas.copy(screens = atlas.screens.map { s -> renamed[s.sigId]?.let { s.copy(title = it) } ?: s })
        }

        /** 解析模型返回的 JSON 数组，并对每条标题与控件含义做敏感词/脱敏校验。 */
        internal fun parse(raw: String, allowedSigIds: Set<String>): Map<String, NamedItem> {
            val cleaned = stripFence(raw)
            val start = cleaned.indexOf('[')
            val end = cleaned.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyMap()
            val array = try {
                Json.parseToJsonElement(cleaned.substring(start, end + 1)).jsonArray
            } catch (_: Exception) {
                return emptyMap()
            }
            val out = linkedMapOf<String, NamedItem>()
            for (el in array) {
                val o = el as? JsonObject ?: continue
                val sigId = (o["sigId"] as? JsonPrimitive)?.contentOrNull?.trim() ?: continue
                if (sigId !in allowedSigIds) continue
                val title = AtlasSanitizer.cleanSingleLine(
                    (o["title"] as? JsonPrimitive)?.contentOrNull,
                    AtlasSanitizer.MAX_TITLE_LEN,
                )
                val ctrlMap = linkedMapOf<Int, String>()
                val ctrls = o["controls"] as? JsonArray
                if (ctrls != null) {
                    for (cEl in ctrls) {
                        val cObj = cEl as? JsonObject ?: continue
                        val idx = (cObj["index"] as? JsonPrimitive)?.intOrNull ?: continue
                        val meaning = AtlasSanitizer.cleanSingleLine(
                            (cObj["meaning"] as? JsonPrimitive)?.contentOrNull,
                            AtlasSanitizer.MAX_MEANING_LEN,
                        ) ?: continue
                        ctrlMap[idx] = meaning
                    }
                }
                if (title != null || ctrlMap.isNotEmpty()) {
                    out[sigId] = NamedItem(title, ctrlMap)
                }
            }
            return out
        }

        private fun stripFence(raw: String): String {
            var s = raw.trim()
            if (s.startsWith("```")) {
                s = s.substringAfter('\n', s).substringBeforeLast("```").trim()
            }
            return s
        }
    }
}
