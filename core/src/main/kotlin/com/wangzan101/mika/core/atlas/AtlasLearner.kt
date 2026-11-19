package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.agent.LearnGate
import com.wangzan101.mika.core.agent.RunOutcome
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.Selector
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.uistate.ScreenSig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 离线页面图谱学习器：在任务结束后解析持久化运行事件（[RunEvent.ActionTarget]、[RunEvent.TaskEnded]、[RunEvent.OutcomeJudged] 等），
 * 合并相似页面节点、追踪 App 版本升级并标记待核实节点、按 [LearnGate.done] 门禁累加边成功次数与无效果/失误次数。
 */
object AtlasLearner {
    const val DEFAULT_SELF_PACKAGE = "com.wangzan101.mika"
    const val DEFAULT_MATCH_THRESHOLD = ScreenSig.DEFAULT_MATCH_THRESHOLD

    data class LearnResult(
        /** 本次更新后各包的最新页面图谱（已写回 store）。 */
        val updatedByPackage: Map<String, AppAtlas>,
        /** 本次新发现或仍待命名的页面 sigId 集合（按包名分组），供 [AtlasNamer] 批量命名。 */
        val unnamedScreenIds: Map<String, Set<String>>,
    )

    private data class StepObservation(
        val atMs: Long,
        val tool: String,
        val effective: Boolean,
        val beforePkg: String,
        val beforeSig: ScreenSig,
        val target: Selector?,
        val afterPkg: String?,
        val afterSig: ScreenSig?,
    )

    /** 一次「从 App 外部进入这个 App」的观测，用于建立入口边。 */
    private data class EntryObservation(
        val atMs: Long,
        val sig: ScreenSig,
    )

    /** 按 [LearnGate.done] 语义从运行事件序列推断本次任务是否算「真正办成」。 */
    fun isLearnDone(events: List<RunEvent>): Boolean {
        val ended = events.lastOrNull { it is RunEvent.TaskEnded } as? RunEvent.TaskEnded ?: return false
        if (ended.outcome != "succeeded") return false
        if (events.any { it is RunEvent.Unconfirmed && it.items.isNotEmpty() }) return false
        val judged = events.lastOrNull { it is RunEvent.OutcomeJudged } as? RunEvent.OutcomeJudged ?: return false
        return judged.outcome == RunOutcome.Done.wire
    }

    /** 显式传入终态与结局判定进行离线学习。 */
    fun learn(
        events: List<RunEvent>,
        end: TaskState,
        outcome: RunOutcome,
        store: AtlasStore,
        appVersions: Map<String, String> = emptyMap(),
        appLabels: Map<String, String> = emptyMap(),
        selfPackage: String = DEFAULT_SELF_PACKAGE,
        matchThreshold: Double = DEFAULT_MATCH_THRESHOLD,
        nowMs: Long = events.lastOrNull()?.atMs ?: System.currentTimeMillis(),
        goal: String? = null,
        tools: ToolIndex? = null,
        routes: List<Route> = emptyList(),
    ): LearnResult = learn(
        events = events,
        store = store,
        learnDone = LearnGate.done(end, outcome),
        succeeded = end is TaskState.Succeeded,
        appVersions = appVersions,
        appLabels = appLabels,
        selfPackage = selfPackage,
        matchThreshold = matchThreshold,
        nowMs = nowMs,
        goal = goal,
        tools = tools,
        routes = routes,
    )

    /** 从运行事件序列离线学习并原子更新 [store] 中涉及的各包 [AppAtlas]。 */
    fun learn(
        events: List<RunEvent>,
        store: AtlasStore,
        learnDone: Boolean = isLearnDone(events),
        succeeded: Boolean = learnDone,
        appVersions: Map<String, String> = emptyMap(),
        appLabels: Map<String, String> = emptyMap(),
        selfPackage: String = DEFAULT_SELF_PACKAGE,
        matchThreshold: Double = DEFAULT_MATCH_THRESHOLD,
        nowMs: Long = events.lastOrNull()?.atMs ?: System.currentTimeMillis(),
        goal: String? = null,
        tools: ToolIndex? = null,
        routes: List<Route> = emptyList(),
    ): LearnResult {
        // 搜索词可能回显成不可编辑的标题或点击目标；只在内存里用来剥离，不写入图谱。
        val inputValues = events.filterIsInstance<RunEvent.ActionTarget>()
            .filter { it.tool.endsWith("_type_text") }
            .mapNotNull { event -> runCatching {
                val args = Json.parseToJsonElement(event.argsJson) as? JsonObject
                (args?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
            }.getOrNull() }
            .filter { it.isNotBlank() }.toSet()
        val resultsByCallId = events.filterIsInstance<RunEvent.ToolResult>()
            .mapNotNull { r -> r.callId?.let { it to r } }
            .toMap()

        val stepsByPkg = linkedMapOf<String, MutableList<StepObservation>>()
        val afterOnlyByPkg = linkedMapOf<String, MutableList<Pair<Long, ScreenSig>>>()
        val entryByPkg = linkedMapOf<String, MutableList<EntryObservation>>()

        for (event in events) {
            if (event !is RunEvent.ActionTarget) continue
            if (event.incomplete) continue
            val paired = event.callId?.let(resultsByCallId::get)
            if (AtlasSanitizer.isSecretOrProtectedAction(event, paired)) continue

            val rawBeforeSig = event.beforeSig?.takeUnless { it.isBlank }
            val beforePkg = AtlasSanitizer.cleanPackage(
                rawBeforeSig?.packageName ?: event.before.packageName,
                selfPackage,
            )
            val beforeSig = if (rawBeforeSig != null && beforePkg != null) {
                normalizeSig(rawBeforeSig, beforePkg, event.beforeActivity ?: rawBeforeSig.activity, inputValues)
            } else null

            val rawAfterSig = if (event.effective) event.afterSig?.takeUnless { it.isBlank } else null
            val afterPkg = AtlasSanitizer.cleanPackage(
                rawAfterSig?.packageName ?: event.after.packageName,
                selfPackage,
            )
            val afterSig = if (rawAfterSig != null && afterPkg != null) {
                normalizeSig(rawAfterSig, afterPkg, event.afterActivity ?: rawAfterSig.activity, inputValues)
            } else null

            val cleanTarget = event.target?.let {
                AtlasSanitizer.cleanSelector(it, isTypeAction = AtlasSanitizer.isTextInputTool(event.tool) || inputValues.any { value -> value in it.name })
            }

            if (beforeSig != null && beforePkg != null) {
                stepsByPkg.getOrPut(beforePkg) { mutableListOf() } += StepObservation(
                    atMs = event.atMs,
                    tool = event.tool,
                    effective = event.effective,
                    beforePkg = beforePkg,
                    beforeSig = beforeSig,
                    target = cleanTarget,
                    afterPkg = afterPkg,
                    afterSig = afterSig,
                )
            }
            if (afterSig != null && afterPkg != null && (beforeSig == null || afterPkg != beforePkg)) {
                afterOnlyByPkg.getOrPut(afterPkg) { mutableListOf() } += (event.atMs to afterSig)
            }
            // 跨包进入（桌面/通知/另一个 App → 本 App）：记一条入口观测。
            // 启动 App 时 Mika 在自己的屏上读不到桌面，没有前屏也算从外部进入。
            val launchedFromOutside = event.tool == "phone_launch_app" && beforePkg == null
            if (afterSig != null && afterPkg != null &&
                (launchedFromOutside || (beforePkg != null && afterPkg != beforePkg))
            ) {
                entryByPkg.getOrPut(afterPkg) { mutableListOf() } += EntryObservation(event.atMs, afterSig)
            }
        }

        val allPackages = (stepsByPkg.keys + afterOnlyByPkg.keys + entryByPkg.keys).distinct()
        if (allPackages.isEmpty()) return LearnResult(emptyMap(), emptyMap())

        val updated = linkedMapOf<String, AppAtlas>()
        val unnamed = linkedMapOf<String, Set<String>>()

        for (pkg in allPackages) {
            val steps = stepsByPkg[pkg].orEmpty()
            val extraVisits = afterOnlyByPkg[pkg].orEmpty()
            val entries = entryByPkg[pkg].orEmpty()
            val pkgUnnamed = linkedSetOf<String>()
            val version = appVersions[pkg]?.trim()?.takeIf { it.isNotEmpty() }
            val label = AtlasSanitizer.cleanSingleLine(appLabels[pkg], 48)

            val saved = store.update(pkg) { existing ->
                val merged = mergePackage(
                    existing = existing?.let { AtlasSanitizer.cleanStoredAtlas(it, inputValues) },
                    pkg = pkg,
                    label = label,
                    version = version,
                    steps = steps,
                    extraVisits = extraVisits,
                    entries = entries,
                    learnDone = learnDone,
                    succeeded = succeeded,
                    matchThreshold = matchThreshold,
                    nowMs = nowMs,
                    unnamedOut = pkgUnnamed,
                )
                val afterRunShortcuts = if (learnDone) {
                    AtlasShortcutMiner.learnFromRun(
                        atlas = merged,
                        events = events,
                        goal = goal,
                        tools = tools,
                        appVersion = version,
                        matchThreshold = matchThreshold,
                        nowMs = nowMs,
                        selfPackage = selfPackage,
                    )
                } else {
                    merged
                }
                val withRoutes = if (routes.isEmpty()) afterRunShortcuts
                else AtlasShortcutMiner.mineFromRoutes(afterRunShortcuts, routes, tools = tools, nowMs = nowMs)
                AtlasSanitizer.cleanStoredAtlas(withRoutes, inputValues)
            }
            if (saved != null) {
                updated[pkg] = saved
                val keptIds = saved.screens.mapTo(HashSet()) { it.sigId }
                val activeUnnamed = pkgUnnamed.filterTo(LinkedHashSet()) { it in keptIds }
                if (activeUnnamed.isNotEmpty()) unnamed[pkg] = activeUnnamed
            }
        }

        return LearnResult(updated, unnamed)
    }

    private fun mergePackage(
        existing: AppAtlas?,
        pkg: String,
        label: String?,
        version: String?,
        steps: List<StepObservation>,
        extraVisits: List<Pair<Long, ScreenSig>>,
        entries: List<EntryObservation>,
        learnDone: Boolean,
        succeeded: Boolean,
        matchThreshold: Double,
        nowMs: Long,
        unnamedOut: MutableSet<String>,
    ): AppAtlas {
        val previousLatest = existing?.versionsSeen?.lastOrNull()
        val versionChanged = version != null && previousLatest != null && version != previousLatest

        val screens = (existing?.screens ?: emptyList()).map { s ->
            if (versionChanged && s.appVersion != version) s.copy(unverifiedAfterUpgrade = true) else s
        }.toMutableList()
        // 老版本的图谱把输入框里的字当成了地标存进边里（搜索词、账号都可能这样留下来）。
        // 清输入文字，但合并必须保留具体输入框的身份，不能把同一表单的多个字段并成一个。
        val edges = mergeTextInputEdges(existing?.edges.orEmpty()).toMutableList()

        var lastAfterSigId: String? = null
        var maxAtMs = existing?.lastUsedMs ?: 0L

        fun upsertScreen(sig: ScreenSig, atMs: Long, matchedIdx: Int = AtlasEviction.findMatchingScreenIndex(screens, pkg, sig, matchThreshold), countHit: Boolean): AtlasScreen {
            maxAtMs = maxOf(maxAtMs, atMs)
            val merged = if (matchedIdx >= 0) {
                val old = screens[matchedIdx]
                val mergedAct = sig.activity ?: old.activity
                val mergedShort = sig.shortActivity ?: old.shortActivity ?: ScreenSig.shortActivityName(pkg, mergedAct)
                val mergedAnchors = (sig.anchors + old.anchors).distinct().take(MAX_ATLAS_ANCHORS)
                val mergedRids = (sig.resourceIds + old.resourceIds).distinct().take(MAX_ATLAS_RESOURCE_IDS)
                val nextTitle = if (old.title.isNotBlank()) old.title else mergedShort ?: ScreenSig.shortHashTag(old.sigId)
                val nextSource = if (old.title.isNotBlank()) old.titleSource else AtlasScreen.TITLE_ACTIVITY
                old.copy(
                    activity = mergedAct,
                    shortActivity = mergedShort,
                    fingerprint = sig.fingerprint,
                    anchors = mergedAnchors,
                    resourceIds = mergedRids,
                    title = nextTitle,
                    titleSource = nextSource,
                    appVersion = version ?: old.appVersion,
                    hits = old.hits + if (countHit) 1 else 0,
                    lastSeenMs = maxOf(old.lastSeenMs, atMs),
                    unverifiedAfterUpgrade = if (version != null) false else old.unverifiedAfterUpgrade,
                ).also { screens[matchedIdx] = it }
            } else {
                val shortAct = sig.shortActivity ?: ScreenSig.shortActivityName(pkg, sig.activity)
                val newId = sig.sigId.ifBlank { ScreenSig.formatSigId(shortAct, sig.fingerprint) }
                AtlasScreen(
                    sigId = newId,
                    activity = sig.activity,
                    shortActivity = shortAct,
                    fingerprint = sig.fingerprint,
                    anchors = sig.anchors,
                    resourceIds = sig.resourceIds,
                    title = shortAct ?: ScreenSig.shortHashTag(newId),
                    titleSource = AtlasScreen.TITLE_ACTIVITY,
                    appVersion = version,
                    hits = 1,
                    lastSeenMs = atMs,
                    unverifiedAfterUpgrade = false,
                ).also { screens += it }
            }
            if (merged.needsNaming()) unnamedOut += merged.sigId
            return merged
        }

        for (step in steps) {
            val fromIdx = AtlasEviction.findMatchingScreenIndex(screens, pkg, step.beforeSig, matchThreshold)
            val matchedBeforeId = if (fromIdx >= 0) screens[fromIdx].sigId else step.beforeSig.sigId
            val countBeforeHit = matchedBeforeId != lastAfterSigId
            lastAfterSigId = null

            var fromScreen = upsertScreen(step.beforeSig, step.atMs, matchedIdx = fromIdx, countHit = countBeforeHit)
            if (step.target != null) {
                fromScreen = updateControl(fromScreen, step.target, step.effective, step.atMs)
                val idx = screens.indexOfFirst { it.sigId == fromScreen.sigId }
                if (idx >= 0) screens[idx] = fromScreen
            }

            val intraAfter = step.afterSig?.takeIf { step.afterPkg == pkg }
            if (step.effective && intraAfter != null) {
                val afterIdx = AtlasEviction.findMatchingScreenIndex(screens, pkg, intraAfter, matchThreshold)
                val candidateAfterId = if (afterIdx >= 0) screens[afterIdx].sigId else intraAfter.sigId
                val toScreen = upsertScreen(intraAfter, step.atMs, matchedIdx = afterIdx, countHit = candidateAfterId != fromScreen.sigId)
                lastAfterSigId = toScreen.sigId
                if (learnDone) {
                    upsertEdge(edges, fromScreen.sigId, toScreen.sigId, step.tool, step.target, step.atMs, version, strong = true)
                } else if (succeeded) {
                    // 任务整体成功但未达到 Done 门禁：记弱成功，图谱才能在真实使用中长出边
                    upsertEdge(edges, fromScreen.sigId, toScreen.sigId, step.tool, step.target, step.atMs, version, strong = false)
                }
            } else if (!step.effective) {
                incrementMatchingEdgeMiss(edges, fromScreen.sigId, step.tool, step.target, step.atMs)
            }
        }

        for ((atMs, sig) in extraVisits) {
            upsertScreen(sig, atMs, countHit = true)
        }

        // 入口边：从 App 外部进入的首页。只记录启动这一种方式，执行时补包名，避免回放任意跨包动作。
        for (entry in entries) {
            val idx = AtlasEviction.findMatchingScreenIndex(screens, pkg, entry.sig, matchThreshold)
            val home = upsertScreen(entry.sig, entry.atMs, matchedIdx = idx, countHit = false)
            when {
                learnDone -> upsertEdge(edges, ENTRY_SIG_ID, home.sigId, ATLAS_ENTRY_TOOL, null, entry.atMs, version, strong = true)
                succeeded -> upsertEdge(edges, ENTRY_SIG_ID, home.sigId, ATLAS_ENTRY_TOOL, null, entry.atMs, version, strong = false)
            }
        }

        val versionsSeen = buildList {
            val old = existing?.versionsSeen.orEmpty()
            if (version == null) {
                addAll(old)
            } else {
                addAll(old.filter { it != version })
                add(version)
            }
        }.takeLast(MAX_ATLAS_VERSIONS)

        val resolvedLabel = label?.takeIf { it.isNotBlank() } ?: existing?.appLabel.orEmpty()
        val resolvedLastUsed = maxOf(maxAtMs, nowMs.takeIf { steps.isNotEmpty() || extraVisits.isNotEmpty() } ?: 0L)

        return AppAtlas(
            packageName = pkg,
            appLabel = resolvedLabel,
            versionsSeen = versionsSeen,
            screens = screens,
            edges = edges,
            lastUsedMs = resolvedLastUsed,
            shortcuts = existing?.shortcuts.orEmpty(),
            candidatePatterns = existing?.candidatePatterns.orEmpty(),
        ).compacted()
    }

    private fun normalizeSig(sig: ScreenSig, pkg: String, rawActivity: String?, inputValues: Set<String>): ScreenSig {
        val normAct = ScreenSig.normalizeActivityName(pkg, rawActivity) ?: rawActivity?.trim()?.ifEmpty { null }
        val shortAct = sig.shortActivity ?: ScreenSig.shortActivityName(pkg, normAct)
        val cleanAnchors = AtlasSanitizer.cleanAnchors(sig.anchors.filterNot { anchor -> inputValues.any { it in anchor } })
        val cleanRids = AtlasSanitizer.cleanResourceIds(sig.resourceIds)
        val sigId = sig.sigId.trim().ifEmpty { ScreenSig.formatSigId(shortAct, sig.fingerprint) }
        return sig.copy(
            sigId = sigId,
            packageName = pkg,
            activity = normAct,
            shortActivity = shortAct,
            anchors = cleanAnchors,
            resourceIds = cleanRids,
        )
    }

    private fun updateControl(screen: AtlasScreen, target: Selector, effective: Boolean, atMs: Long): AtlasScreen {
        val list = screen.controls.toMutableList()
        val idx = list.indexOfFirst { AtlasEviction.sameControl(it.selector, target) }
        if (idx >= 0) {
            val old = list[idx]
            list[idx] = old.copy(
                selector = if (old.selector.name.isBlank() && target.name.isNotBlank()) target else old.selector,
                used = old.used + 1,
                noEffect = old.noEffect + if (effective) 0 else 1,
                lastUsedMs = maxOf(old.lastUsedMs, atMs),
            )
        } else {
            list += AtlasControl(
                selector = target,
                meaning = "",
                used = 1,
                noEffect = if (effective) 0 else 1,
                lastUsedMs = atMs,
            )
        }
        return screen.copy(controls = list)
    }

    /**
     * 清掉旧输入文字，并按 (起点, 终点, 工具, 输入框身份) 合并。
     * 只动输入类动作，其它边的身份仍然按目标元素区分。
     */
    private fun mergeTextInputEdges(edges: List<AtlasEdge>): List<AtlasEdge> {
        val out = mutableListOf<AtlasEdge>()
        for (edge in edges) {
            if (!AtlasSanitizer.isTextInputTool(edge.tool)) {
                out += edge
                continue
            }
            val clean = edge.copy(target = edge.target?.let { AtlasSanitizer.cleanSelector(it, isTypeAction = true) })
            val i = out.indexOfFirst {
                it.fromSigId == clean.fromSigId && it.toSigId == clean.toSigId && it.tool == clean.tool &&
                    AtlasEviction.sameTextInputTarget(it.target, clean.target)
            }
            if (i < 0) {
                out += clean
            } else {
                val old = out[i]
                out[i] = old.copy(
                    ok = old.ok + clean.ok,
                    weakOk = old.weakOk + clean.weakOk,
                    miss = old.miss + clean.miss,
                    lastOkMs = maxOf(old.lastOkMs, clean.lastOkMs),
                    lastAttemptMs = maxOf(old.lastAttemptMs, clean.lastAttemptMs),
                )
            }
        }
        return out
    }

    private fun upsertEdge(
        edges: MutableList<AtlasEdge>,
        fromSigId: String,
        toSigId: String,
        tool: String,
        target: Selector?,
        atMs: Long,
        version: String?,
        strong: Boolean,
    ) {
        val idx = edges.indexOfFirst {
            it.fromSigId == fromSigId &&
                it.toSigId == toSigId &&
                it.tool == tool &&
                AtlasEviction.sameEdgeTarget(tool, it.target, target)
        }
        if (idx >= 0) {
            val old = edges[idx]
            edges[idx] = old.copy(
                ok = old.ok + if (strong) 1 else 0,
                weakOk = old.weakOk + if (strong) 0 else 1,
                lastOkMs = maxOf(old.lastOkMs, atMs),
                lastAttemptMs = maxOf(old.lastAttemptMs, atMs),
                appVersion = version ?: old.appVersion,
            )
        } else {
            edges += AtlasEdge(
                fromSigId = fromSigId,
                toSigId = toSigId,
                tool = tool,
                target = target,
                ok = if (strong) 1 else 0,
                weakOk = if (strong) 0 else 1,
                miss = 0,
                lastOkMs = atMs,
                lastAttemptMs = atMs,
                appVersion = version,
            )
        }
    }

    private fun incrementMatchingEdgeMiss(
        edges: MutableList<AtlasEdge>,
        fromSigId: String,
        tool: String,
        target: Selector?,
        atMs: Long,
    ) {
        for (i in edges.indices) {
            val e = edges[i]
            if (e.fromSigId == fromSigId && e.tool == tool && AtlasEviction.sameEdgeTarget(tool, e.target, target)) {
                edges[i] = e.copy(
                    miss = e.miss + 1,
                    lastAttemptMs = maxOf(e.lastAttemptMs, atMs),
                )
            }
        }
    }
}
