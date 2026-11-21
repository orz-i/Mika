package com.wangzan101.mika.core.reward

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 一次「看广告领奖」任务此刻的状态，从 App 自己的接口读出来，不看屏幕。
 *
 * 起点读书的福利中心是这个形状：`GET /argus/api/v2/video/adv/mainPage` 的
 * `Data.DailyBenefitModule` 里 `Process` 是已完成阶梯数，`TaskList` 是每一阶的奖励，
 * 阶梯共用同一个 `TaskId`（详见 `reverse/qidian/VIDEO_TASK.md`）。
 * 别的 App 只要也是「一个任务多个阶梯、每阶一个 IsFinished/IsReceived」，同一套读法就能用。
 *
 * @property title 任务标题（`激励任务` 这类），可能没有
 * @property process 已完成阶梯数
 * @property total 阶梯总数（`TaskList` 长度）
 * @property claimable 已达成的阶梯里还没领奖的条数
 */
data class RewardTaskState(
    val title: String?,
    val process: Int,
    val total: Int,
    val claimable: Int,
) {
    /** 还能不能往下做：要么有奖可领，要么还有没走完的阶梯。 */
    val hasWork: Boolean get() = claimable > 0 || process < total
}

/** 下一步该做什么。 */
enum class RewardTaskMove {
    /** 有已达成的阶梯没领奖，先领。 */
    Claim,

    /** 还有阶梯没走完，去看下一个广告。 */
    Watch,

    /** 今天的阶梯都走完了。 */
    Done,
}

/**
 * 广告任务的纯逻辑：读接口、算下一步。
 *
 * 放在这里而不是写在工具里，是因为它不碰屏幕也不碰网络，能直接对着真响应做单测。
 */
object RewardTaskPlan {

    /** 响应里任务模块的默认键名。 */
    const val MODULE_KEY = "DailyBenefitModule"

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /**
     * 从 `mainPage` 响应体读出任务状态；读不出来（不是 JSON、没有这个模块、结构不认识）返回 null。
     *
     * @param module 任务模块在 `Data` 下的键名
     */
    fun readMainPage(body: String, module: String = MODULE_KEY): RewardTaskState? {
        val root = parse(body) ?: return null
        val data = root.obj("Data") ?: root
        val block = data.obj(module) ?: findBlock(root, module) ?: return null
        val tasks = (block["TaskList"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        if (tasks.isEmpty()) return null
        val process = block.int("Process") ?: 0
        val claimable = tasks.count { it.int("IsFinished") == 1 && it.int("IsReceived") != 1 }
        return RewardTaskState(
            title = (block["Title"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            process = process,
            total = tasks.size,
            claimable = claimable,
        )
    }

    /** 下一步。 */
    fun nextMove(state: RewardTaskState): RewardTaskMove = when {
        state.claimable > 0 -> RewardTaskMove.Claim
        state.process < state.total -> RewardTaskMove.Watch
        else -> RewardTaskMove.Done
    }

    /**
     * `finishWatch` 响应里发下来的奖励条数。
     *
     * 这是「这次广告算不算数」唯一可信的信号：接口返回了非空 `RewardList` 才说明服务端
     * 认了这一阶。返回 -1 表示响应里没有这个字段（不是这个接口的响应）。
     */
    fun rewardCount(body: String): Int {
        val root = parse(body) ?: return -1
        val data = root.obj("Data") ?: return -1
        val list = data["RewardList"] as? JsonArray ?: return -1
        return list.size
    }

    /** 响应说没说成功（`Result == 0`）；没有这个字段时当作没说。 */
    fun succeeded(body: String): Boolean? {
        val root = parse(body) ?: return null
        return when (root.int("Result")) {
            0 -> true
            null -> null
            else -> false
        }
    }

    private fun parse(body: String): JsonObject? {
        val text = body.trim().removePrefix("\uFEFF").trim()
        if (!text.startsWith("{")) return null
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
    }

    /** 模块不在 `Data` 下时，退一步在整个响应里找第一个带 `TaskList` 的对象。 */
    private fun findBlock(root: JsonElement, module: String, depth: Int = 0): JsonObject? {
        if (depth > 6) return null
        val obj = root as? JsonObject ?: return null
        obj[module]?.let { if (it is JsonObject && it["TaskList"] != null) return it }
        for ((_, value) in obj) {
            val found = findBlock(value, module, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
