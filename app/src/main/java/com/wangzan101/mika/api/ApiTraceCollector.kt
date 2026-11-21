package com.wangzan101.mika.api

import com.wangzan101.mika.cap.phone.network.PhoneNetworkManager
import com.wangzan101.mika.core.api.ApiExchangeInput
import com.wangzan101.mika.core.api.ApiFilter
import com.wangzan101.mika.core.api.ApiObservation
import com.wangzan101.mika.core.api.ApiRequestKeys
import com.wangzan101.mika.core.api.ApiShape
import com.wangzan101.mika.core.api.ApiTrace
import com.wangzan101.mika.core.api.ApiTraits
import com.wangzan101.mika.core.api.ApiUrl
import com.wangzan101.mika.core.api.MAX_API_TRACE_ENTRIES
import java.util.Locale

/**
 * 接口图谱的影子采集器（P0）。
 *
 * 挂在 [PhoneNetworkManager.addExchangeSink] 上，把一次运行里经过目标 App 的 HTTP 交换
 * 收敛成一串 [ApiObservation]。**只落结构与键名，不落值**：响应的字段路径与类型留下，
 * 头部与请求体只留名字，响应体原文在 [onExchange] 返回前就丢掉了。
 *
 * 采集期不做屏幕归因，只记时间戳；归因发生在学习阶段（用运行记录里的动作时刻对齐），
 * 这样采集线程不用读屏，也不会因为读不到屏而丢数据。
 */
class ApiTraceCollector(
    private val packageName: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = MAX_API_TRACE_ENTRIES,
) {

    private companion object {
        /**
         * 超过这个大小的响应体不解析字段。
         * 500KB 以上的 JSON 通常是列表页整包，解析耗时不值得，端点本身仍然记下来。
         */
        const val MAX_SHAPE_BYTES = 512 * 1024

        /** 头部名与键名各自的保留上限，防止异常请求把内存撑爆。 */
        const val MAX_HEADER_NAMES = 32
    }

    private val lock = Any()
    private val observations = ArrayList<ApiObservation>()
    private val startedMs = now()
    private var dropped = 0
    /** 丢弃原因 → 条数，与 [ApiTrace.droppedBy] 同源。 */
    private val droppedBy = LinkedHashMap<String, Int>()
    private var truncated = false

    /** 挂到网络管理器上。返回的句柄必须关闭，否则一次运行结束后回调还在。 */
    fun attach(manager: PhoneNetworkManager): AutoCloseable =
        manager.addExchangeSink { request, entry, body ->
            onExchange(
                ApiExchangeInput(
                    url = entry.url,
                    method = entry.method,
                    status = entry.statusCode ?: 0,
                    mime = entry.mimeType,
                    durationMs = entry.durationMs ?: 0L,
                    endMs = entry.timestamp,
                    postData = request?.postData,
                    headerNames = request?.headers?.map { it.name }.orEmpty(),
                    body = body,
                )
            )
        }

    /**
     * 记录一次交换。抓包链路与回放共用这一条路径。
     */
    fun onExchange(input: ApiExchangeInput) {
        val view = ApiUrl.normalize(input.url) ?: return markDropped(ApiFilter.DROP_INVALID)
        val method = input.method.uppercase(Locale.ROOT)
        val status = input.status
        val body = input.body
        val bodySize = body?.size ?: 0
        val bodyKeys = ApiRequestKeys.of(input.postData)
        val hasStructuredRequest = bodyKeys.isNotEmpty() || looksStructured(input.postData)

        val dropReason = ApiFilter.classify(view, method, status, input.mime, bodySize, hasStructuredRequest)
        if (dropReason != null) return markDropped(dropReason)

        val shape = if (body != null && bodySize in 1..MAX_SHAPE_BYTES && ApiShape.looksLikeJson(body)) {
            ApiShape.extract(String(body, Charsets.UTF_8))
        } else {
            null
        }

        val duration = input.durationMs.coerceAtLeast(0L)
        val endMs = input.endMs
        val startMs = if (duration > 0) endMs - duration else endMs

        val observation = ApiObservation(
            startMs = startMs,
            endMs = endMs,
            method = method,
            endpointId = ApiUrl.endpointId(method, view),
            host = view.host,
            path = view.path,
            queryKeys = view.queryKeys,
            bodyKeys = bodyKeys,
            status = status,
            mime = normalizeMime(input.mime),
            durationMs = duration,
            reqHeaderNames = input.headerNames
                .map { it.lowercase(Locale.ROOT) }
                .distinct()
                .take(MAX_HEADER_NAMES),
            authTraits = ApiTraits.of(input.headerNames, view.queryKeys, bodyKeys),
            fields = shape?.fields ?: emptyList(),
            fieldsTruncated = shape?.truncated ?: false,
            requestBytes = input.postData?.toByteArray(Charsets.UTF_8)?.size ?: 0,
            responseBytes = bodySize,
        )

        synchronized(lock) {
            if (observations.size >= maxEntries) {
                truncated = true
                return
            }
            observations += observation
        }
    }

    /** 收束成可直接序列化的 trace。多次调用返回不同快照是正常的（采集还在继续）。 */
    fun snapshot(endedMs: Long = now()): ApiTrace = synchronized(lock) {
        ApiTrace(
            packageName = packageName,
            startedMs = startedMs,
            endedMs = endedMs,
            observations = observations.toList(),
            dropped = dropped,
            droppedBy = droppedBy.toMap(),
            truncated = truncated,
        )
    }

    /** 清空，便于一次进程内多次运行复用同一实例。 */
    fun reset() = synchronized(lock) {
        observations.clear()
        dropped = 0
        droppedBy.clear()
        truncated = false
    }

    private fun markDropped(reason: String) {
        synchronized(lock) {
            dropped++
            droppedBy[reason] = (droppedBy[reason] ?: 0) + 1
        }
    }

    private fun looksStructured(postData: String?): Boolean {
        val text = postData?.trim().orEmpty()
        if (text.isEmpty()) return false
        return text.startsWith("{") || text.startsWith("[") || text.contains('=')
    }

    private fun normalizeMime(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        // 只留类型本身，去掉 charset 之类的参数，避免每个接口都存一份无用的尾巴
        return value.substringBefore(';').trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
    }
}
