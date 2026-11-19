package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Anchors
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteAnchors
import com.wangzan101.mika.core.replay.RouteStep
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.uistate.ScreenSig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/**
 * L2 可复用子流程挖掘器：
 * 严格遵循 `RouteRecorder` 的安全与隐私规则（拒绝 AskUser / RequestConfirmation / HandToUser、
 * 支付确认/接管障碍、受保护页面与未明确声明非密码框的输入），从跨次成功运行或已有路线中抽取出现 >= 2 次的
 * 2–10 步高频动作子序列，自动将变化参数（输入内容、同槽位控件名）参数化为 `{1}`、`{2}` 槽位。
 */
object AtlasShortcutMiner {
    const val MIN_STEPS = 2
    const val MAX_STEPS = MAX_SHORTCUT_STEPS
    const val MIN_OCCURRENCES = 2
    private const val MIN_SLOT_LEN = 2

    private val NO_ROUTE_VERBS = setOf(Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser)
    private val NO_ROUTE_NAMES = setOf("ask_user", "request_confirmation", "hand_to_user")
    private val DEFAULT_ROUTABLE_PHONE_TOOLS = setOf(
        "phone_tap", "phone_type_text", "phone_clear_text", "phone_scroll",
        "phone_swipe", "phone_back", "phone_press_key", "phone_launch_app", "phone_open_link",
    )
    private val WAITING_STATES = setOf("waiting_input", "waiting_takeover", "no_answer")
    private val PROTECTED_JSON = Regex("\"protected\"\\s*:\\s*true|\"state\"\\s*:\\s*\"protected\"")
    private val NOT_SECRET_JSON = Regex("\"secret\"\\s*:\\s*false")
    private val SLOT_REF = Regex("\\{(\\d+)\\}")

    internal data class ExtractedStep(
        val entrySig: ScreenSig,
        val routeStep: RouteStep,
        val rawTypedText: String? = null,
        val rawTargetName: String? = null,
        val slotBoundFromGoal: Boolean = false,
    )

    /** 在单次成功运行结束时，记录候选子序列并将共现 >= 2 次的安全子流程晋升进 [atlas]。 */
    fun learnFromRun(
        atlas: AppAtlas,
        events: List<RunEvent>,
        goal: String? = null,
        tools: ToolIndex? = null,
        appVersion: String? = null,
        matchThreshold: Double = ScreenSig.DEFAULT_MATCH_THRESHOLD,
        nowMs: Long = events.lastOrNull()?.atMs ?: System.currentTimeMillis(),
        selfPackage: String = AtlasLearner.DEFAULT_SELF_PACKAGE,
    ): AppAtlas {
        val pkg = atlas.packageName
        if (spoilsRun(events, tools)) return atlas
        val segments = extractSafeSegments(events, pkg, goal, tools, selfPackage)
        if (segments.isEmpty()) return atlas

        val candidates = atlas.candidatePatterns.toMutableList()
        val shortcuts = atlas.shortcuts.toMutableList()

        for (segment in segments) {
            val resolvedEntries = segment.map { step ->
                val idx = AtlasEviction.findMatchingScreenIndex(atlas.screens, pkg, step.entrySig, matchThreshold)
                if (idx >= 0) atlas.screens[idx].sigId else step.entrySig.sigId
            }

            // 记录本段内已被更长序列成功晋升的范围，避免为同一段连续动作重复刷出被包含的短快捷
            val promotedRanges = mutableListOf<IntRange>()
            val maxLen = minOf(MAX_STEPS, segment.size)
            for (len in maxLen downTo MIN_STEPS) {
                for (start in 0..(segment.size - len)) {
                    val range = start until (start + len)
                    if (promotedRanges.any { it.first <= range.first && range.last <= it.last }) continue

                    val window = segment.subList(start, start + len)
                    val entrySigId = resolvedEntries[start]
                    val skelKey = skeletonKey(entrySigId, window)

                    // 1. 若已有对应快捷流程，匹配成功后仅刷新版本号与使用时间（不越过 LearnGate 虚增 successes）
                    val existingShortcutIdx = shortcuts.indexOfFirst { sc ->
                        sc.entrySigId == entrySigId && matchesSkeleton(sc, window, pkg)
                    }
                    if (existingShortcutIdx >= 0) {
                        val cur = shortcuts[existingShortcutIdx]
                        shortcuts[existingShortcutIdx] = cur.copy(
                            appVersion = appVersion ?: cur.appVersion,
                            lastUsedMs = maxOf(cur.lastUsedMs, nowMs),
                        )
                        promotedRanges += range
                        continue
                    }

                    // 2. 与历史候选匹配：首次出现记为候选（seenCount=1），第二次起参数化并晋升为正式快捷流程
                    val candIdx = candidates.indexOfFirst { cand ->
                        cand.entrySigId == entrySigId && cand.signatureKey == skelKey
                    }
                    if (candIdx >= 0) {
                        val old = candidates[candIdx]
                        val nextCount = old.seenCount + 1
                        val generalized = generalizeSteps(old.steps, window.map { it.routeStep })
                        if (generalized != null && nextCount >= MIN_OCCURRENCES) {
                            val (finalSteps, slotCount) = generalized
                            if (allTypeStepsParameterized(finalSteps, slotCount)) {
                                val id = shortcutId(entrySigId, skelKey)
                                val entryTitle = atlas.findScreen(entrySigId)?.displayTitle()
                                    ?: ScreenSig.shortHashTag(entrySigId)
                                val built = buildShortcut(id, entryTitle, entrySigId, finalSteps, slotCount, appVersion, nowMs)
                                if (built != null && shortcuts.none { it.id == built.id }) {
                                    shortcuts += built
                                    candidates.removeAt(candIdx)
                                    promotedRanges += range
                                    continue
                                }
                            }
                        }
                        candidates[candIdx] = old.copy(
                            seenCount = nextCount,
                            appVersion = appVersion ?: old.appVersion,
                            lastSeenMs = maxOf(old.lastSeenMs, nowMs),
                        )
                    } else {
                        val cleanSteps = window.map { it.routeStep }
                        val slotCount = countSlots(cleanSteps)
                        val sameRunRepeated = countSkeletonInSegment(segment, resolvedEntries, start, len, skelKey) >= MIN_OCCURRENCES
                        if (sameRunRepeated && allTypeStepsParameterized(cleanSteps, slotCount)) {
                            val id = shortcutId(entrySigId, skelKey)
                            val entryTitle = atlas.findScreen(entrySigId)?.displayTitle()
                                ?: ScreenSig.shortHashTag(entrySigId)
                            val built = buildShortcut(id, entryTitle, entrySigId, cleanSteps, slotCount, appVersion, nowMs)
                            if (built != null && shortcuts.none { it.id == built.id }) {
                                shortcuts += built
                                promotedRanges += range
                                continue
                            }
                        }
                        candidates += ShortcutCandidate(
                            signatureKey = skelKey,
                            entrySigId = entrySigId,
                            steps = cleanSteps,
                            slots = slotCount,
                            seenCount = 1,
                            appVersion = appVersion,
                            lastSeenMs = nowMs,
                        )
                    }
                }
            }
        }

        return atlas.copy(
            shortcuts = shortcuts,
            candidatePatterns = candidates,
        ).compacted()
    }

    /** 直接从多条已录制历史路线中挖掘高频（>= 2 条路线共用）安全子流程并写入 [atlas]。 */
    fun mineFromRoutes(
        atlas: AppAtlas,
        routes: List<Route>,
        tools: ToolIndex? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): AppAtlas {
        val pkg = atlas.packageName
        val pkgRoutes = routes.filter { !it.disabled && pkg in it.packages && it.steps.size >= MIN_STEPS }
        if (pkgRoutes.size < MIN_OCCURRENCES) return atlas

        val shortcuts = atlas.shortcuts.toMutableList()
        val seenKeys = linkedMapOf<String, MutableList<Pair<String, List<RouteStep>>>>()

        for (r in pkgRoutes) {
            // 不能删掉不可复用的中间步后把两边拼起来，那会制造从未执行过的流程。
            val maybeSteps = r.steps.map { st ->
                val routable = if (tools != null) tools[st.tool]?.routable == true else st.tool in DEFAULT_ROUTABLE_PHONE_TOOLS
                if (!routable || st.tool in NO_ROUTE_NAMES) null else AtlasSanitizer.sanitizeRouteStep(st)
            }
            if (maybeSteps.any { it == null }) continue
            val cleanSteps = maybeSteps.filterNotNull()
            if (cleanSteps.size < MIN_STEPS) continue
            for (len in minOf(MAX_STEPS, cleanSteps.size) downTo MIN_STEPS) {
                for (start in 0..(cleanSteps.size - len)) {
                    val window = cleanSteps.subList(start, start + len)
                    val entryId = resolveEntrySigIdForRouteStep(atlas, window.first()) ?: continue
                    val skelKey = skeletonKeyFromRouteSteps(entryId, window)
                    seenKeys.getOrPut(skelKey) { mutableListOf() } += (entryId to window)
                }
            }
        }

        val sortedCandidates = seenKeys.entries
            .filter { it.value.size >= MIN_OCCURRENCES }
            .sortedByDescending { it.value.first().second.size }

        for ((skelKey, group) in sortedCandidates) {
            val entryId = group.first().first
            var mergedSteps = group.first().second
            var valid = true
            for (idx in 1 until group.size) {
                val gen = generalizeSteps(mergedSteps, group[idx].second)
                if (gen == null) {
                    valid = false
                    break
                }
                mergedSteps = gen.first
            }
            if (!valid) continue
            val slotCount = countSlots(mergedSteps)
            if (!allTypeStepsParameterized(mergedSteps, slotCount)) continue
            val id = shortcutId(entryId, skelKey)
            if (shortcuts.any { it.id == id || (it.entrySigId == entryId && skeletonKeyFromRouteSteps(entryId, it.steps) == skelKey) }) {
                continue
            }
            val entryTitle = atlas.findScreen(entryId)?.displayTitle() ?: ScreenSig.shortHashTag(entryId)
            val built = buildShortcut(id, entryTitle, entryId, mergedSteps, slotCount, appVersion = null, nowMs = nowMs)
            if (built != null) shortcuts += built
        }

        return atlas.copy(shortcuts = shortcuts).compacted()
    }

    internal fun spoilsRun(events: List<RunEvent>, tools: ToolIndex?): Boolean = events.any { e ->
        when (e) {
            is RunEvent.Takeover, is RunEvent.Confirmation -> true
            is RunEvent.State -> e.name in WAITING_STATES
            is RunEvent.Barrier -> e.kind == "payment"
            is RunEvent.ModelReply -> e.tool?.let { isBarredTool(it, tools) } == true
            is RunEvent.ToolResult -> isBarredTool(e.tool, tools) || PROTECTED_JSON.containsMatchIn(e.resultJson)
            is RunEvent.ActionTarget -> isBarredTool(e.tool, tools) || e.protected
            else -> false
        }
    }

    private fun isBarredTool(tool: String, tools: ToolIndex?): Boolean {
        if (tool in NO_ROUTE_NAMES) return true
        val info = tools?.get(tool) ?: return false
        if (info.verb in NO_ROUTE_VERBS) return true
        val routedCaps = tools.names.mapNotNull { tools[it]?.takeIf { t -> t.routable }?.capability }.toSet()
        return info.capability != null && info.capability !in routedCaps
    }

    private fun extractSafeSegments(
        events: List<RunEvent>,
        pkg: String,
        goal: String?,
        tools: ToolIndex?,
        selfPackage: String,
    ): List<List<ExtractedStep>> {
        val results = events.filterIsInstance<RunEvent.ToolResult>()
        val resultsByCallId = results.mapNotNull { r -> r.callId?.let { it to r } }.toMap()
        // 即使回显出现在输入之前/之后的锚点或点击名字中，也不能变成持久经验。
        val inputValues = events.filterIsInstance<RunEvent.ActionTarget>()
            .filter { it.tool.endsWith("_type_text") }
            .mapNotNull { (parseJson(it.argsJson)?.get("text") as? JsonPrimitive)?.contentOrNull }
            .filter { it.isNotBlank() }.toSet()
        val segments = mutableListOf<MutableList<ExtractedStep>>()
        var current = mutableListOf<ExtractedStep>()

        fun flush() {
            if (current.size >= MIN_STEPS) segments += current
            current = mutableListOf()
        }

        // 若提供了本轮用户原话，按 RouteRecorder 口径提前绑定唯一出现的参数槽位
        val goalSlots = mutableListOf<String>()
        fun bindFromGoal(value: String): Int? {
            if (goal.isNullOrBlank()) return null
            val existing = goalSlots.indexOf(value)
            if (existing >= 0) return existing + 1
            if (value.trim().length < MIN_SLOT_LEN || occurrences(goal, value) != 1) return null
            val newStart = goal.indexOf(value)
            val newEnd = newStart + value.length - 1
            val overlaps = goalSlots.any { prev ->
                val ps = goal.indexOf(prev)
                val pe = ps + prev.length - 1
                ps <= newEnd && newStart <= pe
            }
            if (overlaps) return null
            goalSlots += value
            return goalSlots.size
        }

        for (event in events) {
            if (event !is RunEvent.ActionTarget) continue
            if (event.incomplete || !event.effective || event.protected) {
                flush(); continue
            }
            val paired = event.callId?.let(resultsByCallId::get)
            if (AtlasSanitizer.isSecretOrProtectedAction(event, paired)) {
                flush(); continue
            }
            val routable = if (tools != null) tools[event.tool]?.routable == true else event.tool in DEFAULT_ROUTABLE_PHONE_TOOLS
            if (!routable) {
                flush(); continue
            }

            val beforeSig = event.beforeSig?.takeUnless { it.isBlank }
            val beforePkg = AtlasSanitizer.cleanPackage(beforeSig?.packageName ?: event.before.packageName, selfPackage)
            if (beforeSig == null || beforePkg != pkg) {
                flush(); continue
            }

            val parsedArgs = parseJson(event.argsJson)
            if (parsedArgs == null) {
                flush(); continue
            }
            var cleanedArgs = parsedArgs.filterKeys { it != "ref" && it != "snapshotId" && it != "target" && it != "reason" }
            val isType = (tools?.verb(event.tool) == Verb.TypeText) || event.tool.endsWith("_type_text")

            var rawTypedText: String? = null
            var goalBound = false
            if (isType) {
                val typed = (parsedArgs["text"] as? JsonPrimitive)?.contentOrNull
                if (typed.isNullOrEmpty() || typed == Redactor.MASK || !AtlasSanitizer.isSafeText(typed)) {
                    flush(); continue
                }
                if (paired == null || !NOT_SECRET_JSON.containsMatchIn(paired.resultJson)) {
                    flush(); continue
                }
                rawTypedText = typed
                val boundIdx = bindFromGoal(typed)
                if (boundIdx == null) {
                    // 候选也要先参数化；无法从本次目标安全绑定就切断，不能先存原文等下次泛化。
                    flush(); continue
                }
                cleanedArgs = cleanedArgs + ("text" to JsonPrimitive("{$boundIdx}"))
                goalBound = true
            }

            val cleanTarget = event.target?.let { sel ->
                val sanitized = AtlasSanitizer.cleanSelector(sel, isTypeAction = isType)
                val n = sanitized.name
                when {
                    n.isEmpty() -> sanitized
                    goal != null && n in goal -> {
                        val idx = bindFromGoal(n)
                        if (idx != null) {
                            goalBound = true
                            sanitized.copy(name = "{$idx}")
                        } else sanitized
                    }
                    (goalSlots + inputValues).any { it in n } -> sanitized.copy(name = "", uniqueAtRecord = false)
                    else -> sanitized
                }
            }

            fun cleanAnchors(a: Anchors, target: Selector?) = Anchors(
                packageName = a.packageName,
                texts = AtlasSanitizer.cleanAnchors(
                    a.texts.map(String::trim).filter { s ->
                        RouteAnchors.stable(s) && s != target?.name?.trim() && (goalSlots + inputValues).none { it.trim() in s }
                    },
                    max = RouteAnchors.MAX,
                ),
            )

            val routeStep = RouteStep(
                tool = event.tool,
                argsJson = JsonObject(cleanedArgs).toString(),
                target = cleanTarget,
                before = cleanAnchors(event.before, event.target),
                after = cleanAnchors(event.after, null),
            )

            current += ExtractedStep(
                entrySig = beforeSig.copy(
                    packageName = pkg,
                    sigId = beforeSig.sigId.ifBlank {
                        ScreenSig.formatSigId(beforeSig.shortActivity, beforeSig.fingerprint)
                    },
                ),
                routeStep = routeStep,
                rawTypedText = rawTypedText,
                rawTargetName = cleanTarget?.name,
                slotBoundFromGoal = goalBound,
            )

            // 若这一步切出了当前 App，切分当前段
            val afterPkg = AtlasSanitizer.cleanPackage(event.afterSig?.packageName ?: event.after.packageName, selfPackage)
            if (afterPkg != null && afterPkg != pkg) flush()
        }
        flush()
        return segments
    }

    /** 比较两段同构步骤列表；若对应位置输入或同 resourceId 控件名不同，则自动抽象为递增的 `{1}`、`{2}` 槽位。 */
    private fun generalizeSteps(a: List<RouteStep>, b: List<RouteStep>): Pair<List<RouteStep>, Int>? {
        if (a.size != b.size) return null
        val out = mutableListOf<RouteStep>()
        var nextSlot = maxOf(countSlots(a), countSlots(b))
        val mappedValues = linkedMapOf<Pair<String, String>, Int>()

        fun allocateSlot(v1: String, v2: String): Int {
            val pair = v1 to v2
            return mappedValues.getOrPut(pair) { ++nextSlot }
        }

        for (i in a.indices) {
            val s1 = a[i]
            val s2 = b[i]
            if (s1.tool != s2.tool) return null

            val o1 = parseJson(s1.argsJson) ?: return null
            val o2 = parseJson(s2.argsJson) ?: return null
            if (o1.keys != o2.keys) return null

            val mergedArgs = o1.toMutableMap()
            for (k in o1.keys) {
                val v1 = o1[k]
                val v2 = o2[k]
                if (v1 == v2) continue
                if (k == "text" && v1 is JsonPrimitive && v1.isString && v2 is JsonPrimitive && v2.isString) {
                    val slotIdx = when {
                        SLOT_REF.matches(v1.content) -> v1.content.removeSurrounding("{", "}").toInt()
                        SLOT_REF.matches(v2.content) -> v2.content.removeSurrounding("{", "}").toInt()
                        else -> allocateSlot(v1.content, v2.content)
                    }
                    mergedArgs[k] = JsonPrimitive("{$slotIdx}")
                } else {
                    return null
                }
            }

            val mergedTarget = when {
                s1.target == null && s2.target == null -> null
                s1.target == null || s2.target == null -> return null
                else -> {
                    val t1 = s1.target
                    val t2 = s2.target
                    if (t1.role != t2.role || t1.resourceId != t2.resourceId) return null
                    val mergedName = when {
                        t1.name == t2.name -> t1.name
                        SLOT_REF.matches(t1.name) -> t1.name
                        SLOT_REF.matches(t2.name) -> t2.name
                        // 仅在存在明确 resourceId 时才将不同展示文本视为动态参数槽位，避免混淆无 id 的不同固定菜单项
                        !t1.resourceId.isNullOrBlank() -> "{${allocateSlot(t1.name, t2.name)}}"
                        else -> return null
                    }
                    t1.copy(
                        name = mergedName,
                        uniqueAtRecord = if (SLOT_REF.containsMatchIn(mergedName)) false else (t1.uniqueAtRecord && t2.uniqueAtRecord),
                    )
                }
            }

            val commonBefore = s1.before.texts.filter { it in s2.before.texts }
                .ifEmpty { s1.before.texts }
                .take(RouteAnchors.MAX)
            val commonAfter = s1.after.texts.filter { it in s2.after.texts }
                .ifEmpty { s1.after.texts }
                .take(RouteAnchors.MAX)

            out += RouteStep(
                tool = s1.tool,
                argsJson = JsonObject(mergedArgs).toString(),
                target = mergedTarget,
                before = s1.before.copy(texts = commonBefore),
                after = s1.after.copy(texts = commonAfter),
            )
        }

        val finalSlots = countSlots(out)
        if (finalSlots > 10) return null
        return out to finalSlots
    }

    /** 普通输入也必须是参数槽，敏感词过滤通过不等于允许保存常量。 */
    private fun allTypeStepsParameterized(steps: List<RouteStep>, slotCount: Int): Boolean {
        for (st in steps) {
            if (st.tool.endsWith("_type_text")) {
                val text = (parseJson(st.argsJson)?.get("text") as? JsonPrimitive)?.contentOrNull ?: return false
                if (!SLOT_REF.matches(text)) return false
            }
            val refs = SLOT_REF.findAll(st.argsJson + "\u0000" + st.target?.name.orEmpty())
                .mapNotNull { it.groupValues[1].toIntOrNull() }
            if (refs.any { it !in 1..slotCount }) return false
        }
        return true
    }

    private fun countSlots(steps: List<RouteStep>): Int =
        steps.flatMap { st ->
            SLOT_REF.findAll(st.argsJson + "\u0000" + st.target?.name.orEmpty())
                .mapNotNull { it.groupValues[1].toIntOrNull() }
        }.maxOrNull() ?: 0

    private fun resolveEntrySigIdForRouteStep(atlas: AppAtlas, step: RouteStep): String? {
        if (atlas.screens.isEmpty()) return null
        val anchorSet = step.before.texts.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        var bestId: String? = null
        var bestOverlap = 0
        for (sc in atlas.screens) {
            val overlap = sc.anchors.count { it.trim() in anchorSet }
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestId = sc.sigId
            }
        }
        if (bestOverlap > 0) return bestId
        val target = step.target ?: return null
        return atlas.screens.firstOrNull { sc ->
            sc.controls.any { AtlasEviction.sameControl(it.selector, target) }
        }?.sigId
    }

    private fun countSkeletonInSegment(
        segment: List<ExtractedStep>,
        resolvedEntries: List<String>,
        start: Int,
        len: Int,
        skelKey: String,
    ): Int {
        var count = 0
        var i = 0
        while (i <= segment.size - len) {
            val entry = resolvedEntries[i]
            if (skeletonKey(entry, segment.subList(i, i + len)) == skelKey) {
                count++
                i += len
            } else {
                i++
            }
        }
        return count
    }

    private fun matchesSkeleton(sc: AtlasShortcut, window: List<ExtractedStep>, pkg: String): Boolean {
        if (sc.steps.size != window.size) return false
        return sc.steps.zip(window).all { (a, b) ->
            stepSkeleton(a) == stepSkeleton(b.routeStep) && (a.before.packageName == null || a.before.packageName == pkg)
        }
    }

    private fun skeletonKey(entrySigId: String, window: List<ExtractedStep>): String =
        entrySigId + "::" + window.joinToString("->") { stepSkeleton(it.routeStep) }

    private fun skeletonKeyFromRouteSteps(entrySigId: String, steps: List<RouteStep>): String =
        entrySigId + "::" + steps.joinToString("->") { stepSkeleton(it) }

    private fun stepSkeleton(step: RouteStep): String {
        val args = parseJson(step.argsJson)
        val argShape = args?.entries
            ?.sortedBy { it.key }
            ?.joinToString(",") { (k, v) ->
                if (k == "text") "text=*" else "$k=${v}"
            }.orEmpty()
        val t = step.target
        val targetShape = if (t == null) "" else {
            val rid = t.resourceId.orEmpty()
            val nameShape = if (rid.isNotBlank() || SLOT_REF.containsMatchIn(t.name)) "*" else t.name
            "${t.role}|$rid|$nameShape"
        }
        return "${step.tool}($argShape)[$targetShape]"
    }

    private fun shortcutId(entrySigId: String, skelKey: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("$entrySigId|$skelKey".toByteArray(Charsets.UTF_8))
        val hex = d.take(5).joinToString("") { "%02x".format(it) }
        return "sc_$hex"
    }

    private fun buildShortcut(
        id: String,
        entryTitle: String,
        entrySigId: String,
        steps: List<RouteStep>,
        slots: Int,
        appVersion: String?,
        nowMs: Long,
    ): AtlasShortcut? {
        val stepSummaries = steps.map { st ->
            val verb = st.tool.removePrefix("phone_")
            val targetLabel = st.target?.name?.takeIf { it.isNotBlank() }
                ?: st.target?.resourceId?.substringAfterLast('/')
                ?: st.target?.role.orEmpty()
            if (targetLabel.isNotBlank()) "$verb $targetLabel" else verb
        }
        val cleanEntry = AtlasSanitizer.cleanSingleLine(entryTitle, 16) ?: ScreenSig.shortHashTag(entrySigId)
        val firstAction = stepSummaries.firstOrNull().orEmpty()
        val lastAction = if (stepSummaries.size > 1) stepSummaries.last() else ""
        val rawName = if (lastAction.isNotBlank() && lastAction != firstAction) {
            "$cleanEntry: $firstAction -> $lastAction"
        } else {
            "$cleanEntry: $firstAction"
        }
        val name = AtlasSanitizer.cleanSingleLine(rawName, 48) ?: return null
        val desc = AtlasSanitizer.cleanSingleLine(stepSummaries.joinToString(" -> "), 120).orEmpty()
        return AtlasSanitizer.sanitizeShortcut(
            AtlasShortcut(
                id = id,
                name = name,
                description = desc,
                slots = slots,
                entrySigId = entrySigId,
                steps = steps,
                appVersion = appVersion,
                lastUsedMs = nowMs,
            ),
        )
    }

    private fun occurrences(s: String, sub: String): Int {
        var count = 0
        var idx = s.indexOf(sub)
        while (idx >= 0) {
            count++
            idx = s.indexOf(sub, idx + 1)
        }
        return count
    }

    private fun parseJson(raw: String): JsonObject? = try {
        Json.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    }
}
