package com.wangzan101.mika.core.atlas

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.atlas.AtlasReplayBridge.Companion.PROTECTED_SEEN
import com.wangzan101.mika.core.atlas.AtlasReplayBridge.Companion.summary
import com.wangzan101.mika.core.atlas.AtlasReplayBridge.StepExecOutcome
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.uistate.ScreenSig
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.PriorityQueue

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
            val fromScreen: AtlasScreen,
            val targetScreen: AtlasScreen,
            val edges: List<AtlasEdge>,
            val estimatedCost: Double,
        ) : Plan

        data object NoMatchingStart : Plan
        data object NoMatchingTarget : Plan
        data class Unreachable(val fromScreen: AtlasScreen, val targets: List<AtlasScreen>) : Plan
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
        if (initialPkg != null && initialPkg != atlas.packageName) {
            return Outcome.NoPath("foreground app $initialPkg does not match ${atlas.packageName}", summary(initial))
        }
        val fromSig = initial.resolvedScreenSig() ?: return Outcome.NoPath("current screen signature unavailable", summary(initial))
        val plan = planPath(atlas, fromSig, targetQuery, matchThreshold, maxHops)
        return when (plan) {
            is Plan.AlreadyAtTarget -> Outcome.Arrived(plan.screen, hops = 0, alreadyThere = true)
            Plan.NoMatchingStart -> Outcome.NoPath("current screen is not mapped in atlas for ${atlas.packageName}", summary(initial))
            Plan.NoMatchingTarget -> Outcome.NoPath("target '$targetQuery' not found in atlas for ${atlas.packageName}", summary(initial))
            is Plan.Unreachable -> Outcome.NoPath("no reliable path from '${plan.fromScreen.displayTitle()}' to '$targetQuery'", summary(initial))
            is Plan.Path -> executePlan(atlas, plan, initial, store, callIdPrefix, nowMs)
        }
    }

    private suspend fun executePlan(
        atlas: AppAtlas,
        plan: Plan.Path,
        initial: ScreenSnapshot,
        store: AtlasStore?,
        callIdPrefix: String,
        nowMs: Long,
    ): Outcome {
        val total = plan.edges.size
        val pkg = atlas.packageName
        var seq = 0

        for ((i, edge) in plan.edges.withIndex()) {
            val toScreen = atlas.findScreen(edge.toSigId)
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
            if (snap.packageName != null && snap.packageName != pkg) {
                return diverged("foreground app changed to ${snap.packageName}", seen, error = "wrong_app")
            }

            // 中途若已提前落在目标页面，提前完成寻路
            val curSig = snap.resolvedScreenSig()
            if (curSig != null && !curSig.isBlank && matchesScreen(plan.targetScreen, pkg, curSig, matchThreshold)) {
                return Outcome.Arrived(plan.targetScreen, hops = i, alreadyThere = false)
            }

            val call = when (edge.tool) {
                "phone_tap" -> {
                    val sel = edge.target ?: return diverged("missing selector for tap edge", seen, error = "missing_target")
                    val el = AtlasEviction.findMatchingElement(snap, sel)
                        ?: return diverged("navigation target '${sel.name.ifBlank { sel.resourceId.orEmpty() }}' not found or ambiguous", seen, error = "target_not_found", markMiss = true)
                    buildJsonObject {
                        put("ref", el.ref)
                        put("snapshotId", snap.snapshotId)
                    }
                }
                "phone_back" -> JsonObject(emptyMap())
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
            if (toScreen != null && toScreen.fingerprint != 0L && freshSig != null && !freshSig.isBlank) {
                val hitExpected = matchesScreen(toScreen, pkg, freshSig, matchThreshold)
                val hitFinal = matchesScreen(plan.targetScreen, pkg, freshSig, matchThreshold)
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
            val targetIds = targets.mapTo(HashSet()) { it.sigId }
            if (startScreen.sigId in targetIds) return Plan.AlreadyAtTarget(startScreen)

            val screenById = atlas.screens.associateBy { it.sigId }
            val navigableEdges = atlas.edges.filter { isNavigableEdge(it) && it.fromSigId in screenById && it.toSigId in screenById }

            data class State(val sigId: String, val cost: Double, val hops: Int)

            val dist = HashMap<String, Double>()
            val parentEdge = HashMap<String, AtlasEdge>()
            val queue = PriorityQueue<State>(compareBy<State> { it.cost }.thenBy { it.hops })

            dist[startScreen.sigId] = 0.0
            queue.add(State(startScreen.sigId, 0.0, 0))

            var bestTargetId: String? = null
            var bestCost = Double.POSITIVE_INFINITY

            while (queue.isNotEmpty()) {
                val cur = queue.poll()
                if (cur.cost > (dist[cur.sigId] ?: Double.POSITIVE_INFINITY)) continue
                if (cur.sigId in targetIds) {
                    bestTargetId = cur.sigId
                    bestCost = cur.cost
                    break
                }
                if (cur.hops >= maxHops) continue

                for (edge in navigableEdges) {
                    if (edge.fromSigId != cur.sigId) continue
                    val nextScreen = screenById[edge.toSigId] ?: continue
                    val weight = edgeWeight(edge, nextScreen)
                    val nextCost = cur.cost + weight
                    val prevBest = dist[edge.toSigId] ?: Double.POSITIVE_INFINITY
                    if (nextCost + 1e-9 < prevBest) {
                        dist[edge.toSigId] = nextCost
                        parentEdge[edge.toSigId] = edge
                        queue.add(State(edge.toSigId, nextCost, cur.hops + 1))
                    }
                }
            }

            val destId = bestTargetId ?: return Plan.Unreachable(startScreen, targets)
            val destScreen = screenById.getValue(destId)
            val reversed = mutableListOf<AtlasEdge>()
            var cursor = destId
            while (cursor != startScreen.sigId) {
                val e = parentEdge[cursor] ?: break
                reversed += e
                cursor = e.fromSigId
            }
            return Plan.Path(startScreen, destScreen, reversed.asReversed(), bestCost)
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

            return screens
                .mapNotNull { s -> rank(s).takeIf { it > 0 }?.let { s to it } }
                .sortedWith(compareByDescending<Pair<AtlasScreen, Int>> { it.second }.thenByDescending { it.first.hits })
                .map { it.first }
        }

        internal fun isNavigableEdge(edge: AtlasEdge): Boolean {
            if (edge.ok <= 0 || edge.miss > edge.ok) return false
            if (edge.fromSigId == edge.toSigId) return false
            return when (edge.tool) {
                "phone_tap" -> edge.target != null && !SLOT_REF.containsMatchIn(edge.target.name)
                "phone_back" -> true
                else -> false
            }
        }

        internal fun edgeWeight(edge: AtlasEdge, toScreen: AtlasScreen?): Double {
            val total = (edge.ok + edge.miss).coerceAtLeast(1).toDouble()
            val failureRate = edge.miss.toDouble() / total
            val freqPenalty = 1.0 / (1.0 + edge.ok.toDouble())
            val upgradePenalty = if (toScreen?.unverifiedAfterUpgrade == true) 0.5 else 0.0
            return 1.0 + failureRate * 4.0 + freqPenalty + upgradePenalty
        }

        private fun matchesScreen(screen: AtlasScreen, pkg: String, sig: ScreenSig, threshold: Double): Boolean =
            AtlasEviction.findMatchingScreenIndex(listOf(screen), pkg, sig, threshold) >= 0
    }
}
