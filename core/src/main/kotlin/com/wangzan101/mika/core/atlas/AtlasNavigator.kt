package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.atlas.AtlasReplayBridge.Companion.PROTECTED_SEEN
import com.wangzan101.mika.core.atlas.AtlasReplayBridge.Companion.summary
import com.wangzan101.mika.core.atlas.AtlasReplayBridge.StepExecOutcome
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.agent.use.ui.ScreenSig
import com.agent.use.ui.ScreenSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.PriorityQueue
import com.agent.use.ui.AtlasEviction
import com.agent.use.ui.ENTRY_SIG_ID
import com.agent.use.ui.ATLAS_ENTRY_TOOL

/**
 * L3 跨页面自动寻路器：
 * 1. 基于 [AppAtlas.edges] 构建带权有向图（忽略 `ok == 0`、`miss > ok`、受保护或非导航边），
 *    以 Dijkstra 计算兼顾跳数与历史成功率的可靠最短路径；
 * 2. 经 [ActionExecutor]（`Origin.Replay`）逐跳执行并核验每一步跳转是否落在预期目标页；
 * 3. 一旦遇到不可逆/支付、守卫告警、受保护界面或界面偏离，立即停止并安全交还模型。
 */
class AtlasNavigator private constructor(
    private val bridge: AtlasReplayBridge,
    private val matchThreshold: Double = ScreenSig.DEFAULT_MATCH_THRESHOLD,
) {
    sealed interface Plan {
        data class AlreadyAtTarget(val screen: AtlasScreen) : Plan
        data class Path(
            val fromScreen: AtlasScreen?,
            val targetScreen: AtlasScreen,
            val edges: List<AtlasEdge>,
            val estimatedCost: Double,
            /** 首跳是入口边，需要先启动目标 App。 */
            val needsLaunch: Boolean = false,
        ) : Plan

        data object NoMatchingStart : Plan
        data object NoMatchingTarget : Plan
        data class AmbiguousTarget(val targets: List<AtlasScreen>) : Plan
        data class Unreachable(val fromScreen: AtlasScreen, val targets: List<AtlasScreen>) : Plan
        data class UnreachableFromEntry(val targets: List<AtlasScreen>) : Plan
    }

    sealed interface Outcome {
        data class Arrived(
            val targetScreen: AtlasScreen,
            val hops: Int,
            val alreadyThere: Boolean = false,
        ) : Outcome

        data class StoppedForConfirmation(
            val hopsCompleted: Int,
            val totalHops: Int,
            val reason: String,
        ) : Outcome

        data class Diverged(
            val hopsCompleted: Int,
            val totalHops: Int,
            val expectedScreen: AtlasScreen?,
            val reason: String,
            val seen: String,
            val executed: Boolean = false,
            val error: String? = null,
            val failedEdge: AtlasEdge? = null,
        ) : Outcome

        data class NoPath(val reason: String, val seen: String = "") : Outcome
    }

    suspend fun navigate(
        atlas: AppAtlas,
        targetQuery: String,
        maxHops: Int = DEFAULT_MAX_HOPS,
        store: AtlasStore? = null,
        callIdPrefix: String = "nav",
        nowMs: Long = System.currentTimeMillis(),
        initialSnapshot: ScreenSnapshot? = null,
    ): Outcome {
        val initial = initialSnapshot ?: run {
            bridge.gate()
            bridge.readScreen() ?: return Outcome.NoPath("the screen could not be read")
        }
        if (initial.protected) return Outcome.NoPath("the page is protected", PROTECTED_SEEN)
        val initialPkg = initial.packageName
        val insideTargetApp = initialPkg == null || initialPkg == atlas.packageName
        val fromSig = initial.resolvedScreenSig()
        val plan = if (insideTargetApp && fromSig != null) {
            val inside = planPath(atlas, fromSig, targetQuery, matchThreshold, maxHops)
            if (inside is Plan.NoMatchingStart) planFromEntry(atlas, targetQuery, matchThreshold, maxHops) else inside
        } else {
            planPathFromOutside(atlas, targetQuery, matchThreshold, maxHops)
        }
        return when (plan) {
            is Plan.AlreadyAtTarget -> Outcome.Arrived(plan.screen, hops = 0, alreadyThere = true)
            Plan.NoMatchingStart -> Outcome.NoPath("current screen is not mapped in atlas for ${atlas.packageName}", summary(initial))
            Plan.NoMatchingTarget -> Outcome.NoPath("target '$targetQuery' not found in atlas for ${atlas.packageName}", summary(initial))
            is Plan.AmbiguousTarget -> Outcome.NoPath("target '$targetQuery' is ambiguous; use a screen sigId: " + plan.targets.joinToString { it.sigId }, summary(initial))
            is Plan.Unreachable -> Outcome.NoPath("no reliable path from '${plan.fromScreen.displayTitle()}' to '$targetQuery'", summary(initial))
            is Plan.UnreachableFromEntry -> Outcome.NoPath("no reliable path from the app entry to '$targetQuery'", summary(initial))
            is Plan.Path -> executePlan(atlas, plan, initial, store, callIdPrefix, nowMs, maxHops)
        }
    }

    private suspend fun executePlan(
        atlas: AppAtlas,
        plan: Plan.Path,
        initial: ScreenSnapshot,
        store: AtlasStore?,
        callIdPrefix: String,
        nowMs: Long,
        maxHops: Int,
    ): Outcome {
        val total = plan.edges.size
        val pkg = atlas.packageName
        var seq = 0

        for ((i, edge) in plan.edges.withIndex()) {
            val toScreen = atlas.findScreen(edge.toSigId)
            val isEntryHop = edge.isEntry()
            fun diverged(why: String, seen: String, executed: Boolean = false, error: String? = null, markMiss: Boolean = false): Outcome {
                if (markMiss && store != null) {
                    store.update(pkg) { cur -> cur?.recordEdgeMiss(edge.fromSigId, edge.toSigId, edge.tool, edge.target, nowMs) }
                }
                return Outcome.Diverged(i, total, toScreen, why, seen, executed, error, edge)
            }

            if (i > 0) bridge.gate()
            val snap = if (i == 0) initial else (bridge.readScreen() ?: return diverged("the screen could not be read", ""))
            if (snap.protected) return diverged("the page is protected", PROTECTED_SEEN, error = "protected_page")
            val seen = summary(snap)
            if (!isEntryHop && snap.packageName != null && snap.packageName != pkg) {
                return diverged("foreground app changed to ${snap.packageName}", seen, error = "wrong_app")
            }

            // 中途若已提前落在目标页面，提前完成寻路
            val curSig = snap.resolvedScreenSig()
            if (curSig != null && !curSig.isBlank && matchesScreen(atlas, plan.targetScreen, curSig, matchThreshold)) {
                return Outcome.Arrived(plan.targetScreen, hops = i, alreadyThere = false)
            }
            if (!isEntryHop && (curSig == null || atlas.findScreen(edge.fromSigId)?.let {
                    matchesScreen(atlas, it, curSig, matchThreshold)
                } != true)) {
                return diverged("current screen no longer matches the planned start", seen, error = "screen_diverged")
            }

            val call = when {
                isEntryHop -> buildJsonObject { put("package", pkg) }
                edge.tool == "phone_tap" -> {
                    val sel = edge.target ?: return diverged("missing selector for tap edge", seen, error = "missing_target")
                    val el = AtlasEviction.findMatchingElement(snap, sel)
                        ?: return diverged("navigation target '${sel.name.ifBlank { sel.resourceId.orEmpty() }}' not found or ambiguous", seen, error = "target_not_found", markMiss = true)
                    buildJsonObject {
                        put("ref", el.ref)
                        put("snapshotId", snap.snapshotId)
                    }
                }
                edge.tool == "phone_back" -> JsonObject(emptyMap())
                else -> return diverged("unsupported navigation tool ${edge.tool}", seen, error = "unsupported_edge")
            }

            val out = bridge.executeReplay(Part.ToolCall("$callIdPrefix.${++seq}", edge.tool, call.toString()))
            val ran = when (out) {
                is StepExecOutcome.Ran -> out
                is StepExecOutcome.StoppedForConfirmation -> {
                    val reason = out.detail?.let { "${out.code}: $it" } ?: out.code
                    return Outcome.StoppedForConfirmation(i, total, reason)
                }
                is StepExecOutcome.GuardBlocked ->
                    return diverged("the action guard raised a warning (${out.code})", seen, error = out.code)
                is StepExecOutcome.Refused ->
                    return diverged("action refused (${out.code})", seen, error = out.code)
                is StepExecOutcome.Cancelled ->
                    return diverged("cancelled", seen, error = "cancelled")
            }

            val fresh = ran.seen
            val nowSummary = fresh?.let(::summary) ?: seen
            if (ran.content["ok"] != JsonPrimitive(true)) {
                val err = (ran.content["error"] as? JsonPrimitive)?.contentOrNull
                val executed = err !in AtlasReplayBridge.NOT_EXECUTED_ERRORS
                return diverged("hop failed" + err?.let { ": $it" }.orEmpty(), nowSummary, executed = executed, error = err, markMiss = executed)
            }
            if (fresh == null) {
                return diverged("could not verify screen after hop", nowSummary, executed = true, error = "unverified_hop")
            }
            if (fresh.protected) {
                return diverged("landed on a protected page", PROTECTED_SEEN, executed = true, error = "protected_page")
            }
            if (fresh.packageName != null && fresh.packageName != pkg) {
                return diverged("foreground app changed to ${fresh.packageName}", nowSummary, executed = true, error = "wrong_app", markMiss = true)
            }

            // 逐跳核验跳转目标页是否对上；若直接跳到了最终目标页同样视作成功
            val freshSig = fresh.resolvedScreenSig()
            if (freshSig == null || freshSig.isBlank || toScreen == null || toScreen.toScreenSig(pkg).isBlank) {
                return diverged("could not identify screen after hop", nowSummary, executed = true, error = "unverified_hop")
            }
            if (isEntryHop) {
                // Android 可恢复任意旧任务。启动已成功，但不能强行沿着预测的首页继续点击。
                // 仅从真实落地页重算内部路线，不再次启动，且消耗同一个跳数预算。
                return when (val next = planPath(atlas, freshSig, plan.targetScreen.sigId, matchThreshold, maxHops - 1)) {
                    is Plan.AlreadyAtTarget -> Outcome.Arrived(next.screen, hops = 1)
                    is Plan.Path -> when (val out = executePlan(atlas, next, fresh, store, "$callIdPrefix.after-launch", nowMs, maxHops - 1)) {
                        is Outcome.Arrived -> out.copy(hops = out.hops + 1)
                        is Outcome.Diverged -> out.copy(hopsCompleted = out.hopsCompleted + 1, totalHops = out.totalHops + 1, executed = true)
                        is Outcome.StoppedForConfirmation -> out.copy(hopsCompleted = out.hopsCompleted + 1, totalHops = out.totalHops + 1)
                        is Outcome.NoPath -> out
                    }
                    else -> Outcome.Diverged(1, total, plan.targetScreen, "no reliable path from the actual launch landing within the remaining hop budget", nowSummary,
                        executed = true, error = "no_path_after_launch")
                }
            }
            val hitExpected = matchesScreen(atlas, toScreen, freshSig, matchThreshold)
            val hitFinal = matchesScreen(atlas, plan.targetScreen, freshSig, matchThreshold)
            if (hitFinal && i < total - 1) {
                return Outcome.Arrived(plan.targetScreen, hops = i + 1, alreadyThere = false)
            }
            if (!hitExpected) {
                return diverged(
                    "expected '${toScreen.displayTitle()}' after hop ${i + 1}, but screen diverged",
                    nowSummary,
                    executed = true,
                    error = "screen_diverged",
                    markMiss = true,
                )
            }
        }
        return Outcome.Arrived(plan.targetScreen, hops = total, alreadyThere = false)
    }

    companion object {
        const val DEFAULT_MAX_HOPS = 8
        private val SLOT_REF = Regex("\\{(\\d+)\\}")

        fun forExecutor(
            tools: ToolDispatcher,
            executor: ActionExecutor,
            gate: suspend () -> Unit = {},
            matchThreshold: Double = ScreenSig.DEFAULT_MATCH_THRESHOLD,
        ): AtlasNavigator = AtlasNavigator(
            bridge = AtlasReplayBridge.forExecutor(tools, executor, gate),
            matchThreshold = matchThreshold,
        )

        internal fun forBridge(
            bridge: AtlasReplayBridge,
            matchThreshold: Double = ScreenSig.DEFAULT_MATCH_THRESHOLD,
        ): AtlasNavigator = AtlasNavigator(bridge, matchThreshold)

        /** 纯图算法规划：基于 [atlas] 与当前签名 [fromSig] 计算到目标页面 [targetQuery] 的带权最短安全路径。 */
        fun planPath(
            atlas: AppAtlas,
            fromSig: ScreenSig,
            targetQuery: String,
            matchThreshold: Double = ScreenSig.DEFAULT_MATCH_THRESHOLD,
            maxHops: Int = DEFAULT_MAX_HOPS,
        ): Plan {
            val startIdx = AtlasEviction.findMatchingScreenIndex(atlas.screens, atlas.packageName, fromSig, matchThreshold)
            if (startIdx < 0) return Plan.NoMatchingStart
            val startScreen = atlas.screens[startIdx]

            val targets = resolveTargets(atlas.screens, targetQuery)
            if (targets.isEmpty()) return Plan.NoMatchingTarget
            if (targets.size > 1) return Plan.AmbiguousTarget(targets)
            val targetIds = targets.mapTo(HashSet()) { it.sigId }
            if (startScreen.sigId in targetIds) return Plan.AlreadyAtTarget(startScreen)

            val screenById = atlas.screens.associateBy { it.sigId }
            val result = searchPath(atlas, screenById, startScreen.sigId, targetIds, maxHops)
                ?: return Plan.Unreachable(startScreen, targets)
            val destScreen = screenById[result.destId] ?: return Plan.Unreachable(startScreen, targets)
            return Plan.Path(startScreen, destScreen, result.edges, result.cost)
        }

        /**
         * 从入口规划：当前不在目标 App 里（或当前页没有映射）时，先启动 App 再走内部边。
         * 只有存在可靠入口边时才认为可规划。
         */
        fun planFromEntry(
            atlas: AppAtlas,
            targetQuery: String,
            matchThreshold: Double = ScreenSig.DEFAULT_MATCH_THRESHOLD,
            maxHops: Int = DEFAULT_MAX_HOPS,
        ): Plan {
            val targets = resolveTargets(atlas.screens, targetQuery)
            if (targets.isEmpty()) return Plan.NoMatchingTarget
            if (targets.size > 1) return Plan.AmbiguousTarget(targets)
            val targetIds = targets.mapTo(HashSet()) { it.sigId }
            val screenById = atlas.screens.associateBy { it.sigId }
            val hasEntry = atlas.edges.any { it.isEntry() && it.toSigId in screenById && isNavigableEdge(it) }
            if (!hasEntry) return Plan.NoMatchingStart
            val result = searchPath(atlas, screenById, ENTRY_SIG_ID, targetIds, maxHops)
                ?: return Plan.UnreachableFromEntry(targets)
            val destScreen = screenById[result.destId] ?: return Plan.UnreachableFromEntry(targets)
            return Plan.Path(
                fromScreen = null,
                targetScreen = destScreen,
                edges = result.edges,
                estimatedCost = result.cost,
                needsLaunch = true,
            )
        }

        /** 从 App 外部（桌面、另一个 App）导航：等价于从入口规划。 */
        fun planPathFromOutside(
            atlas: AppAtlas,
            targetQuery: String,
            matchThreshold: Double = ScreenSig.DEFAULT_MATCH_THRESHOLD,
            maxHops: Int = DEFAULT_MAX_HOPS,
        ): Plan = planFromEntry(atlas, targetQuery, matchThreshold, maxHops)

        private data class SearchResult(val destId: String, val edges: List<AtlasEdge>, val cost: Double)

        /** 在带权有向图上跑 Dijkstra，返回抵达任一目标页的最省路径；不可达返回 null。 */
        private fun searchPath(
            atlas: AppAtlas,
            screenById: Map<String, AtlasScreen>,
            startId: String,
            targetIds: Set<String>,
            maxHops: Int,
        ): SearchResult? {
            val navigableEdges = atlas.edges.filter {
                isNavigableEdge(it) && it.toSigId in screenById && (it.fromSigId == ENTRY_SIG_ID || it.fromSigId in screenById)
            }

            // 同一页的低成本长前缀不能覆盖较贵的短前缀：后者可能是唯一满足跳数预算的路。
            data class Position(val sigId: String, val hops: Int)
            data class State(val position: Position, val cost: Double)

            val dist = HashMap<Position, Double>()
            val parentEdge = HashMap<Position, AtlasEdge>()
            val queue = PriorityQueue<State>(compareBy<State> { it.cost }.thenBy { it.position.hops })
            val outgoing = navigableEdges.groupBy { it.fromSigId }

            val start = Position(startId, 0)
            dist[start] = 0.0
            queue.add(State(start, 0.0))

            while (queue.isNotEmpty()) {
                val cur = queue.poll()
                if (cur.cost > (dist[cur.position] ?: Double.POSITIVE_INFINITY)) continue
                if (cur.position.sigId in targetIds) {
                    val reversed = mutableListOf<AtlasEdge>()
                    var cursor = cur.position
                    while (cursor != start) {
                        val e = parentEdge[cursor] ?: return null
                        reversed += e
                        cursor = Position(e.fromSigId, cursor.hops - 1)
                    }
                    return SearchResult(cur.position.sigId, reversed.asReversed(), cur.cost)
                }
                if (cur.position.hops >= maxHops) continue

                for (edge in outgoing[cur.position.sigId].orEmpty()) {
                    val nextScreen = screenById[edge.toSigId] ?: continue
                    val weight = edgeWeight(edge, nextScreen)
                    val nextCost = cur.cost + weight
                    val next = Position(edge.toSigId, cur.position.hops + 1)
                    val prevBest = dist[next] ?: Double.POSITIVE_INFINITY
                    if (nextCost + 1e-9 < prevBest) {
                        dist[next] = nextCost
                        parentEdge[next] = edge
                        queue.add(State(next, nextCost))
                    }
                }
            }
            return null
        }

        internal fun resolveTargets(screens: List<AtlasScreen>, query: String): List<AtlasScreen> {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            val qLower = q.lowercase()

            fun rank(s: AtlasScreen): Int {
                if (s.sigId == q) return 100
                val title = s.displayTitle().trim()
                val shortAct = s.shortActivity?.trim().orEmpty()
                val act = s.activity?.trim().orEmpty()
                if (title.equals(q, ignoreCase = true) || shortAct.equals(q, ignoreCase = true) || act.equals(q, ignoreCase = true)) return 90
                if (s.anchors.any { it.trim().equals(q, ignoreCase = true) }) return 80
                if (title.lowercase().contains(qLower) || shortAct.lowercase().contains(qLower)) return 60
                if (s.anchors.any { it.lowercase().contains(qLower) }) return 40
                return 0
            }

            val ranked = screens
                .mapNotNull { s -> rank(s).takeIf { it > 0 }?.let { s to it } }
                .sortedWith(compareByDescending<Pair<AtlasScreen, Int>> { it.second }.thenByDescending { it.first.hits })
            val best = ranked.firstOrNull()?.second ?: return emptyList()
            return ranked.takeWhile { it.second == best }.map { it.first }
        }

        internal fun isNavigableEdge(edge: AtlasEdge): Boolean {
            if (edge.strength() <= 0.0 || edge.miss > edge.strength()) return false
            if (edge.fromSigId == edge.toSigId) return false
            if (edge.isEntry()) return edge.tool == ATLAS_ENTRY_TOOL && edge.launchObserved
            return when (edge.tool) {
                "phone_tap" -> edge.target?.let { !SLOT_REF.containsMatchIn(it.name) } == true
                "phone_back" -> true
                else -> false
            }
        }

        internal fun edgeWeight(edge: AtlasEdge, toScreen: AtlasScreen?): Double {
            val strength = edge.strength()
            val total = (strength + edge.miss).coerceAtLeast(1.0)
            val failureRate = edge.miss / total
            val freqPenalty = 1.0 / (1.0 + strength)
            val upgradePenalty = if (toScreen?.unverifiedAfterUpgrade == true) 0.5 else 0.0
            return 1.0 + failureRate * 4.0 + freqPenalty + upgradePenalty
        }

        private fun matchesScreen(atlas: AppAtlas, screen: AtlasScreen, sig: ScreenSig, threshold: Double): Boolean {
            val idx = AtlasEviction.findMatchingScreenIndex(atlas.screens, atlas.packageName, sig, threshold)
            return idx >= 0 && atlas.screens[idx].sigId == screen.sigId
        }
    }
}
