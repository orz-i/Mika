package com.wangzan101.mika.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 每个端点保留的字段路径上限。
 *
 * 取 240 是实测出来的：起点福利中心 `GET /argus/api/v2/video/adv/mainPage` 一次响应
 * （19.9KB）按同一套规则能切出 220 条路径、24 个业务模块。上限 80 时只覆盖到第 10 个模块
 * （`MoreRewardTab`），后面的 `PrivilegedTaskModule`、`ReadingPageTaskModule`、
 * `ScoreDrawModule` 等整块丢掉，而这些正是「再做一次能不能拿到」要用到的结构。
 */
const val MAX_API_FIELDS = 240

/** 形状提取的递归深度上限。 */
const val MAX_API_SHAPE_DEPTH = 6

/** 数组最多取样多少个元素来求字段并集。 */
const val MAX_API_ARRAY_SAMPLES = 20

/** 对象键数超过它才开始考虑折叠动态键。 */
const val MAX_API_OBJECT_KEYS_BEFORE_FOLD = 32

/**
 * 响应里的一个字段。
 *
 * [path] 只表达**结构**：`Data.DailyBenefitModule.TaskList[].IsFinished`。
 * 值（任务名、token、手机号、字符串长度）一律不进来，所以这份数据可以脱敏后长期保存。
 *
 * @property type int / long / double / bool / string / object / array / null / mixed
 * @property seen 这个路径被观察到多少次
 */
@Serializable
data class ApiField(
    val path: String,
    val type: String,
    val seen: Int = 1,
)

/** 形状提取结果。 */
@Serializable
data class ApiShapeResult(
    val fields: List<ApiField> = emptyList(),
    val truncated: Boolean = false,
)

/**
 * 从 JSON 响应体提取字段路径与类型。
 *
 * 规则（与 `docs/dev/app-api-graph.md` 4.6 一致）：
 * - 深度上限 [MAX_API_SHAPE_DEPTH]，字段上限 [MAX_API_FIELDS]，超限置 `truncated`；
 * - 数组取前 [MAX_API_ARRAY_SAMPLES] 个元素求并集，路径用 `[]` 表示元素；
 * - 同一路径出现不同类型记 `mixed`，不猜哪个对；
 * - 对象键数超过 [MAX_API_OBJECT_KEYS_BEFORE_FOLD] 且键名像动态值（纯数字 / UUID / 长 hex）时
 *   折叠成 `*`，避免任务 id 把字段表撑爆。
 *
 * 纯函数：同样的输入得到同样的输出，单测不需要设备。
 */
object ApiShape {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val HEX_ID_RE = Regex("^[0-9a-fA-F]{8,}$")
    private val DIGITS_RE = Regex("^[0-9]+$")

    /** 解析失败（不是 JSON、或不是对象/数组顶层）返回 null。 */
    fun extract(jsonText: String, maxFields: Int = MAX_API_FIELDS): ApiShapeResult? {
        if (jsonText.isBlank()) return null
        val element = try {
            json.parseToJsonElement(jsonText)
        } catch (_: Exception) {
            return null
        }
        if (element !is JsonObject && element !is JsonArray) return null

        val acc = Accumulator(maxFields)
        walk(element, path = "", depth = 0, acc = acc)
        return ApiShapeResult(fields = acc.fields(), truncated = acc.truncated)
    }

    /** 判断响应体是否值得当 JSON 解析：只嗅首字节，不读完整个 body。 */
    fun looksLikeJson(body: ByteArray?): Boolean {
        if (body == null || body.isEmpty()) return false
        var i = 0
        // 跳过 UTF-8 BOM 与前导空白，避免被格式化的 JSON 开头有换行
        if (body.size >= 3 && body[0] == 0xEF.toByte() && body[1] == 0xBB.toByte() && body[2] == 0xBF.toByte()) {
            i = 3
        }
        while (i < body.size) {
            when (body[i].toInt().toChar()) {
                ' ', '\t', '\r', '\n' -> i++
                else -> return body[i] == '{'.code.toByte() || body[i] == '['.code.toByte()
            }
        }
        return false
    }

    private fun walk(element: JsonElement, path: String, depth: Int, acc: Accumulator) {
        if (depth > MAX_API_SHAPE_DEPTH) return
        when (element) {
            is JsonObject -> {
                acc.record(path, "object")
                if (depth == MAX_API_SHAPE_DEPTH) return
                val fold = element.size > MAX_API_OBJECT_KEYS_BEFORE_FOLD
                var emitted = 0
                for ((key, value) in element) {
                    val segment = if (fold && isDynamicKey(key)) "*" else key
                    val childPath = join(path, segment)
                    // 折叠后同一个 `*` 只展开一个样本，避免重复遍历
                    if (segment == "*" && acc.contains(childPath)) continue
                    walk(value, childPath, depth + 1, acc)
                    emitted++
                    if (acc.exhausted && emitted > MAX_API_OBJECT_KEYS_BEFORE_FOLD) break
                }
            }

            is JsonArray -> {
                // 根数组自身没有路径（path 为空时 record 会跳过），元素统一用 `[]`
                acc.record(path, "array")
                if (depth == MAX_API_SHAPE_DEPTH) return
                val childPath = "$path[]"
                for (child in element.take(MAX_API_ARRAY_SAMPLES)) {
                    walk(child, childPath, depth + 1, acc)
                }
            }

            is JsonNull -> acc.record(path, "null")

            is JsonPrimitive -> acc.record(path, primitiveType(element))
        }
    }

    private fun primitiveType(primitive: JsonPrimitive): String {
        if (primitive.isString) return "string"
        primitive.booleanOrNull?.let { return "bool" }
        val long = primitive.longOrNull
        if (long != null) {
            return if (long >= Int.MIN_VALUE && long <= Int.MAX_VALUE) "int" else "long"
        }
        primitive.doubleOrNull?.let { return "double" }
        return "string"
    }

    private fun join(parent: String, key: String): String =
        if (parent.isEmpty()) key else "$parent.$key"

    private fun isDynamicKey(key: String): Boolean {
        if (key.isEmpty()) return false
        return DIGITS_RE.matches(key) || UUID_RE.matches(key) || HEX_ID_RE.matches(key)
    }

    /**
     * 边遍历边累积，不先建整棵树——大响应（几百 KB JSON）也能在毫秒级完成。
     */
    private class Accumulator(private val maxFields: Int) {
        private val order = LinkedHashMap<String, ApiField>()
        var truncated: Boolean = false
            private set

        val exhausted: Boolean get() = order.size >= maxFields

        fun fields(): List<ApiField> = order.values.toList()

        fun contains(path: String): Boolean = order.containsKey(path)

        fun record(path: String, type: String) {
            if (path.isEmpty()) return
            val existing = order[path]
            if (existing == null) {
                if (order.size >= maxFields) {
                    truncated = true
                    return
                }
                order[path] = ApiField(path = path, type = type, seen = 1)
                return
            }
            val merged = if (existing.type == type || existing.type == "mixed") {
                existing.type
            } else {
                "mixed"
            }
            order[path] = existing.copy(type = merged, seen = existing.seen + 1)
        }
    }
}
