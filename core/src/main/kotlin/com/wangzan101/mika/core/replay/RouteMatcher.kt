package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.knowledge.KnowledgeStore
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** 选中的路线和本次原话里的槽值(按 `{1}`、`{2}` 的顺序)。 */
data class RouteChoice(val route: Route, val slots: List<String>)

/**
 * 任务开始时找一条以往成功的路线:先按 App 与字面相似度取候选,再用一次模型调用(没有候选就不调)选定并抽出槽值。
 * 代码再核一遍:槽值个数要对,且每个都是本次原话里逐字出现的一段。[store] 也供循环更新路线计数。
 */
class RouteMatcher(private val model: ChatModel, val store: RouteStore, private val knowledge: KnowledgeStore?) {

    fun candidates(goal: String, limit: Int = 5): List<Route> {
        val apps = matchedPackages(goal)
        val g = bigrams(goal)
        val gSquashed = squash(goal).lowercase()
        return store.all().filter { !it.disabled }
            .map { r ->
                val tmpl = SLOT.replace(r.template, "\u0000")
                val sim = jaccard(g, bigrams(tmpl))
                val fixed = squash(SLOT.replace(r.template, "")).lowercase()
                val charSim = multisetCharDice(gSquashed, fixed)
                val pkgMatched = r.packages.any(apps::contains)
                val rankScore = maxOf(sim, if (pkgMatched) charSim else 0.0) + if (pkgMatched) 0.1 else 0.0
                Triple(r, sim >= MIN_SIMILARITY || pkgMatched, rankScore)
            }
            .filter { it.second }
            .sortedByDescending { it.third }
            .take(limit)
            .map { it.first }
    }

    private fun matchedPackages(goal: String): Set<String> =
        knowledge?.matchTask(goal, Int.MAX_VALUE)?.flatMap { it.packages }?.toSet().orEmpty()

    suspend fun choose(goal: String): RouteChoice? {
        val list = candidates(goal)
        if (list.isEmpty()) return null
        val apps = matchedPackages(goal)
        val text = list.withIndex().joinToString("\n", "用户原话:$goal\n\n做法模板:\n") { (i, r) -> "${i + 1}. ${r.template}" }
        val reply = try {
            withTimeoutOrNull(TIMEOUT_MS) { model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(text)), Author.Mika)), emptyList()) } ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }, list, goal, apps)
    }

    companion object {
        const val MIN_SIMILARITY = 0.3
        const val RELAXED_FILLED_DICE_THRESHOLD = 0.50
        const val RELAXED_FIXED_DICE_THRESHOLD = 0.28
        const val RELAXED_HYBRID_THRESHOLD = 0.38
        const val MIN_RELAXED_SLOT_LEN = 2
        const val TIMEOUT_MS = 20_000L
        private val SLOT = Regex("\\{\\d+\\}")
        private val SLOT_N = Regex("\\{(\\d+)\\}")

        private fun squash(s: String) = s.filterNot { it.isWhitespace() }

        private fun occurrences(s: String, v: String): Int {
            var n = 0; var i = s.indexOf(v)
            while (i >= 0) { n++; i = s.indexOf(v, i + 1) }
            return n
        }

        val PROMPT: String by lazy { Prompts.role("route_match") }

        internal fun parse(
            text: String,
            list: List<Route>,
            goal: String,
            matchedPackages: Set<String> = emptySet(),
        ): RouteChoice? {
            val start = text.indexOf('{'); val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o = try { Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject } catch (e: Exception) { null } ?: return null
            val pick = o["route"]
            if (pick == null || pick is JsonNull) return null
            val idx = (pick as? JsonPrimitive)?.intOrNull ?: return null
            val route = list.getOrNull(idx - 1) ?: return null
            val slots = (o["slots"] as? JsonArray ?: JsonArray(emptyList())).map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull ?: return null }
            if (slots.size != route.slots) return null
            // 每个槽值必须在用户原话里逐字且唯一出现
            if (slots.any { it.isBlank() || occurrences(goal, it) != 1 }) return null
            var unknown = false
            val filled = SLOT_N.replace(route.template) { m -> slots.getOrNull(m.groupValues[1].toInt() - 1) ?: run { unknown = true; "" } }
            if (unknown) return null

            // 1. 最高优先级：填回模板后忽略空白与原话完全一致
            if (squash(filled) == squash(goal)) {
                return RouteChoice(route, slots)
            }

            // 2. 放宽兜底：同包名命中 + 模板与填充后文本相似度过阈值 + 严格槽位长度/唯一性/不重叠/防截断校验，支持同义改写与语序调整
            if (!passesRelaxedMatch(route, slots, filled, goal, matchedPackages)) return null
            return RouteChoice(route, slots)
        }

        private fun passesRelaxedMatch(
            route: Route,
            slots: List<String>,
            filled: String,
            goal: String,
            matchedPackages: Set<String>,
        ): Boolean {
            if (matchedPackages.isEmpty() || route.packages.none { it in matchedPackages }) return false
            if (slots.any { it.trim().length < MIN_RELAXED_SLOT_LEN }) return false

            val spans = mutableListOf<IntRange>()
            for ((idx, slot) in slots.withIndex()) {
                val pos = goal.indexOf(slot)
                if (pos < 0) return false
                val end = pos + slot.length - 1
                // 防止从英文或数字连续词内部截半（例如把 "100" 抠成 "10"）
                if (slot.first().isAsciiAlnum() && pos > 0 && goal[pos - 1].isAsciiAlnum()) return false
                if (slot.last().isAsciiAlnum() && end + 1 < goal.length && goal[end + 1].isAsciiAlnum()) return false
                if (hasGluedTemplateOverflow(route.template, idx + 1, slot, goal, pos)) return false
                spans += pos..end
            }
            val sortedSpans = spans.sortedBy { it.first }
            for (i in 1 until sortedSpans.size) {
                if (sortedSpans[i - 1].last >= sortedSpans[i].first) return false
            }

            val goalSquashed = squash(goal).lowercase()
            val filledSquashed = squash(filled).lowercase()
            val fixedTemplate = squash(SLOT_N.replace(route.template, "")).lowercase()
            val remainingGoal = stripSpans(goal, sortedSpans).let(::squash).lowercase()

            val filledDice = multisetCharDice(filledSquashed, goalSquashed)
            val fixedDice = multisetCharDice(fixedTemplate, remainingGoal)
            val filledBigram = jaccard(bigrams(filledSquashed), bigrams(goalSquashed))
            val hybridFilled = 0.5 * filledBigram + 0.5 * filledDice

            return filledDice >= RELAXED_FILLED_DICE_THRESHOLD &&
                fixedDice >= RELAXED_FIXED_DICE_THRESHOLD &&
                hybridFilled >= RELAXED_HYBRID_THRESHOLD
        }

        /** 若模板占位符 {k} 紧贴前后固定字面字符，而原话同一锚点之间夹了更长文本却只截取了前缀/后缀，则拒绝部分截断槽。 */
        private fun hasGluedTemplateOverflow(
            template: String,
            slotNum: Int,
            slot: String,
            goal: String,
            slotPos: Int,
        ): Boolean {
            val token = "{$slotNum}"
            val tIdx = template.indexOf(token)
            if (tIdx < 0) return false
            val prefixChar = template.getOrNull(tIdx - 1)?.takeIf { !it.isWhitespace() && it != '}' }
            val suffixChar = template.getOrNull(tIdx + token.length)?.takeIf { !it.isWhitespace() && it != '{' }
            if (prefixChar != null && suffixChar != null && slotPos > 0) {
                val pInGoal = goal.lastIndexOf(prefixChar, slotPos - 1)
                val sInGoal = goal.indexOf(suffixChar, slotPos + slot.length)
                if (pInGoal >= 0 && sInGoal >= slotPos + slot.length) {
                    val gapLeft = goal.substring(pInGoal + 1, slotPos)
                    val gapRight = goal.substring(slotPos + slot.length, sInGoal)
                    if ((gapLeft.length in 1..2 || gapRight.length in 1..2) &&
                        gapLeft.length in 0..2 && gapRight.length in 0..2 &&
                        gapLeft.none { it.isWhitespace() } && gapRight.none { it.isWhitespace() }
                    ) {
                        return true
                    }
                }
            }
            return false
        }

        private fun stripSpans(s: String, sortedSpans: List<IntRange>): String {
            if (sortedSpans.isEmpty()) return s
            val sb = StringBuilder(s.length)
            var cursor = 0
            for (r in sortedSpans) {
                if (r.first > cursor) sb.append(s, cursor, r.first)
                cursor = r.last + 1
            }
            if (cursor < s.length) sb.append(s, cursor, s.length)
            return sb.toString()
        }

        private fun Char.isAsciiAlnum(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

        internal fun multisetCharDice(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val counts = HashMap<Char, Int>()
            for (ch in a) counts[ch] = (counts[ch] ?: 0) + 1
            var common = 0
            for (ch in b) {
                val c = counts[ch] ?: 0
                if (c > 0) {
                    common++
                    counts[ch] = c - 1
                }
            }
            return (2.0 * common) / (a.length + b.length).toDouble()
        }

        /** 字符二元组;占位符(\u0000)切断前后。 */
        internal fun bigrams(s: String): Set<String> =
            s.lowercase().filterNot { it.isWhitespace() }.windowed(2).filter { '\u0000' !in it }.toSet()

        internal fun jaccard(a: Set<String>, b: Set<String>): Double =
            if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size
    }
}
