package com.wangzan101.mika.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** 单次运行最多记录多少条交换。 */
const val MAX_API_TRACE_ENTRIES = 500

/** 请求体最多提取多少个顶层键。 */
const val MAX_API_BODY_KEYS = 40

// ===== authTraits 位掩码：只记「有没有」，不记值 =====

const val TRAIT_COOKIE = 1
const val TRAIT_AUTHORIZATION = 2
const val TRAIT_SIGNATURE_HINT = 4
const val TRAIT_REFERER = 8
const val TRAIT_CUSTOM_HEADER = 16

/**
 * 一次 HTTP 交换的观测记录。
 *
 * 这是接口图谱的**原始素材**，只描述结构与元信息：
 * 时间、方法、模板化后的端点、状态码、字段形状、头部**名字**列表、请求体**键名**列表。
 * 请求头与请求体的值、响应体的值，全都不在这里。
 *
 * 屏幕归因刻意不在这里做——采集线程只记 `startMs`，任务结束后由学习器用运行记录里的
 * `ActionTarget` 对齐（见 `docs/dev/app-api-graph.md` 4.5）。
 */
@Serializable
data class ApiObservation(
    val startMs: Long,
    val endMs: Long,
    val method: String,
    val endpointId: String,
    val host: String,
    val path: String,
    val queryKeys: List<String> = emptyList(),
    val bodyKeys: List<String> = emptyList(),
    val status: Int,
    val mime: String? = null,
    val durationMs: Long = 0L,
    val reqHeaderNames: List<String> = emptyList(),
    val authTraits: Int = 0,
    val fields: List<ApiField> = emptyList(),
    val fieldsTruncated: Boolean = false,
    val requestBytes: Int = 0,
    val responseBytes: Int = 0,
)

/**
 * 一次运行采到的全部交换。
 *
 * @property dropped 被过滤规则丢掉的数量（用于回看规则是否过严），等于 [droppedBy] 各项之和
 * @property droppedBy 丢弃原因 → 条数。真机验收要回答「这个接口是没抓到，还是被规则丢了」，
 *   只记总数回答不了，所以按 [ApiFilter] 给出的原因分桶
 * @property truncated 是否因 [MAX_API_TRACE_ENTRIES] 上限而截断
 */
@Serializable
data class ApiTrace(
    val packageName: String,
    val startedMs: Long,
    val endedMs: Long = 0L,
    val observations: List<ApiObservation> = emptyList(),
    val dropped: Int = 0,
    val droppedBy: Map<String, Int> = emptyMap(),
    val truncated: Boolean = false,
)

/**
 * 采集器的输入：一次交换里采集需要的全部信息。
 *
 * 刻意不引用网络层的实现类型（`CapturedTrafficEntry` 之类），
 * 这样采集逻辑可以脱离 Android 单测，也能直接用抓包文件回放。
 */
class ApiExchangeInput(
    val url: String,
    val method: String,
    val status: Int,
    val mime: String? = null,
    val durationMs: Long = 0L,
    val endMs: Long = 0L,
    val postData: String? = null,
    val headerNames: List<String> = emptyList(),
    val body: ByteArray? = null,
)

/**
 * 头部名与键名的特征判定。
 *
 * 这些标记只用于给模型提示「这个接口可能要做点什么」，不是「必须签名」的证明。
 * 起点那次是先看到 `ibex` 参数、再实测重放 402，才算证实；图谱里只写「疑似」。
 *
 * 签名线索要同时看**请求头名**：真机实测里起点的 `tstamp` / `ibex` / `borgus` / `sdksign`
 * 全在头部，query 一个都没有。只看 query 与 body 会把这类接口判成「不用签名」。
 */
object ApiTraits {

    private val SIGNATURE_KEYS = setOf(
        "sign", "signature", "_sign", "sig", "ibex", "borgus", "tstamp", "timestamp_sign", "sdksign",
    )

    fun of(headerNames: Collection<String>, queryKeys: Collection<String>, bodyKeys: Collection<String>): Int {
        var traits = 0
        val lowered = headerNames.map { it.lowercase(Locale.ROOT) }
        if ("cookie" in lowered) traits = traits or TRAIT_COOKIE
        if ("authorization" in lowered) traits = traits or TRAIT_AUTHORIZATION
        if ("referer" in lowered) traits = traits or TRAIT_REFERER
        if (lowered.any { it.startsWith("x-") }) traits = traits or TRAIT_CUSTOM_HEADER
        if (hasSignatureHint(headerNames) || hasSignatureHint(queryKeys) || hasSignatureHint(bodyKeys)) {
            traits = traits or TRAIT_SIGNATURE_HINT
        }
        return traits
    }

    /** 把位掩码翻译成可读标签，界面上直接显示。 */
    fun describe(traits: Int): List<String> = buildList {
        if (traits and TRAIT_COOKIE != 0) add("cookie")
        if (traits and TRAIT_AUTHORIZATION != 0) add("authorization")
        if (traits and TRAIT_SIGNATURE_HINT != 0) add("疑似签名")
        if (traits and TRAIT_REFERER != 0) add("referer")
        if (traits and TRAIT_CUSTOM_HEADER != 0) add("自定义头")
    }

    private fun hasSignatureHint(keys: Collection<String>): Boolean = keys.any { raw ->
        val key = raw.lowercase(Locale.ROOT)
        key in SIGNATURE_KEYS || key.startsWith("x-sign") || key.contains("signature")
    }
}

/**
 * 从请求体里只提取键名。
 *
 * 支持两种形态：JSON 对象的顶层键，以及 `a=1&b=2` 这类表单的键。
 * 解析不出来就返回空表——宁可没有键，也不要为了拿键而保存原始内容。
 */
object ApiRequestKeys {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun of(postData: String?): List<String> {
        val text = postData?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val first = text.firstOrNull()
        val keys = when {
            first == '{' -> fromJson(text)
            first == '[' -> emptyList()
            text.contains('=') -> formKeys(text)
            else -> emptyList()
        }
        return keys.distinct().take(MAX_API_BODY_KEYS)
    }

    private fun fromJson(text: String): List<String> {
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            return emptyList()
        }
        val obj = element as? JsonObject ?: return emptyList()
        return obj.keys.toList()
    }

    private fun formKeys(text: String): List<String> = text.split('&')
        .asSequence()
        .mapNotNull { pair ->
            val key = pair.substringBefore('=').trim()
            key.takeIf { it.isNotEmpty() }
        }
        .toList()
}
