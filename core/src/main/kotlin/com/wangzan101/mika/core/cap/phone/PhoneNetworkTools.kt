package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Entry
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.bool
import com.wangzan101.mika.core.tool.int
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 等待指定 App 匹配指定 URL 正则/子串的网络 API 响应（如订单提交、数据刷新、搜索接口）。
 * 在操作产生网络请求后使用，避免固定间隔盲目读屏。
 */
internal object PhoneWaitNetwork : PhoneTool {
    override val verb = Verb.PhoneWaitNetwork
    override val risk = Risk.Read
    override val label = "等待网络响应"
    override val yieldsToSteer = true
    override val def = ToolDef(
        "phone_wait_network",
        Prompts.tool("phone_wait_network"),
        mapOf(
            "urlPattern" to str("URL pattern (regular expression or substring) to match (e.g. 'order/submit', 'api/search')."),
            "timeoutMs" to int("Maximum wait in milliseconds, 100-30000 (default 10000).", 100, 30000),
        ),
        listOf("urlPattern"),
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val pattern = a.str("urlPattern", max = 500) ?: throw ArgError("urlPattern", "required")
        val timeout = (a.int("timeoutMs", 100, 30000) ?: 10000).toLong()
        val net = ctx.network ?: return failure("network_capture_unavailable")

        val status = net.captureStatus()
        if (status != null && !status.capturing) {
            return ToolExecution(buildJsonObject {
                put("ok", false)
                put("error", status.error ?: "capture_not_active")
                put("capturing", false)
                put("activeMode", status.activeMode)
                status.targetPackage?.let { put("targetPackage", it) }
                put("urlPattern", pattern)
            })
        }

        val response = net.waitForResponse(pattern, timeout)
        return if (response != null) {
            ToolExecution(buildJsonObject {
                put("ok", true)
                put("requestId", response.requestId)
                put("url", response.url)
                put("method", response.method)
                put("statusCode", response.statusCode)
                put("durationMs", response.durationMs)
                response.contentType?.let { put("contentType", it) }
                put("contentLength", response.contentLength)
                put("timing", response.timing)
            })
        } else {
            ToolExecution(buildJsonObject {
                put("ok", false)
                put("error", "timeout")
                put("urlPattern", pattern)
                put("timeoutMs", timeout)
            })
        }
    }
}

/**
 * 按 requestId 或 URL 正则直接提取捕获的 HTTP 响应体（支持 JSON/文本），无需 OCR 截屏。
 */
internal object PhoneGetNetworkResponse : PhoneTool {
    override val verb = Verb.PhoneGetNetworkResponse
    override val risk = Risk.Read
    override val label = "获取网络响应体"
    override val def = ToolDef(
        "phone_get_network_response",
        Prompts.tool("phone_get_network_response"),
        mapOf(
            "requestId" to str("Unique request ID returned from phone_wait_network or phone_inspect_network."),
            "urlPattern" to str("URL substring or regex to find matching response if requestId is omitted."),
            "asString" to bool("Whether to return decoded text body, default true."),
        ),
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val requestId = a.str("requestId", max = 100)
        val urlPattern = a.str("urlPattern", max = 500)
        if (requestId == null && urlPattern == null) {
            throw ArgError("requestId", "either requestId or urlPattern must be provided")
        }
        val asString = a.bool("asString", true)
        val net = ctx.network ?: return failure("network_capture_unavailable")

        val respBody = net.getResponseBody(requestId, urlPattern, asString)
        return if (respBody != null) {
            ToolExecution(buildJsonObject {
                put("ok", respBody.error == null)
                put("requestId", respBody.requestId)
                put("url", respBody.url)
                put("statusCode", respBody.statusCode)
                respBody.contentType?.let { put("contentType", it) }
                if (respBody.body != null) {
                    put("body", Untrusted.wrapWithin(respBody.body, 50_000))
                }
                put("truncated", respBody.isTruncated)
                respBody.error?.let { put("error", it) }
            })
        } else {
            ToolExecution(buildJsonObject {
                put("ok", false)
                put("error", "not_found")
                requestId?.let { put("requestId", it) }
                urlPattern?.let { put("urlPattern", it) }
            })
        }
    }
}

/**
 * 查看当前 App 最近捕获的网络请求与响应列表。
 */
internal object PhoneInspectNetwork : PhoneTool {
    override val verb = Verb.PhoneInspectNetwork
    override val risk = Risk.Read
    override val entry = Entry.Opens
    override val label = "查看近期网络请求"
    override val def = ToolDef(
        "phone_inspect_network",
        Prompts.tool("phone_inspect_network"),
        mapOf(
            "limit" to int("Maximum number of recent network requests to return, 1-50 (default 10).", 1, 50),
            "filter" to str("Optional keyword or URL regex filter."),
        ),
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val limit = a.int("limit", 1, 50) ?: 10
        val filter = a.str("filter", max = 200)
        val net = ctx.network ?: return failure("network_capture_unavailable")

        val status = net.captureStatus()
        val requests = net.inspectNetwork(limit, filter)
        val capturing = status?.capturing ?: true
        val effectiveError = status?.error ?: if (!capturing) "capture_not_active" else null
        return ToolExecution(buildJsonObject {
            put("ok", capturing)
            if (!capturing && effectiveError != null) {
                put("error", effectiveError)
            }
            put("count", requests.size)
            status?.let {
                put("capturing", it.capturing)
                put("activeMode", it.activeMode)
                it.targetPackage?.let { pkg -> put("targetPackage", pkg) }
                effectiveError?.let { err -> put("captureError", err) }
            }
            put("requests", JsonArray(requests.map { r ->
                buildJsonObject {
                    put("requestId", r.requestId)
                    put("url", r.url)
                    put("method", r.method)
                    put("statusCode", r.statusCode)
                    put("durationMs", r.durationMs)
                    put("timestamp", r.timestamp)
                    r.contentType?.let { ct -> put("contentType", ct) }
                    put("hasResponseBody", r.hasResponseBody)
                    put("state", r.state)
                }
            }))
        })
    }
}

private fun failure(error: String) = ToolExecution(buildJsonObject {
    put("ok", false)
    put("error", error)
})
