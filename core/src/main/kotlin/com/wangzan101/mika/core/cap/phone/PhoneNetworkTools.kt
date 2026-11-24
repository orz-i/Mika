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

        val body = net.getResponseBody(requestId, urlPattern, asString)
        return if (body != null) {
            ToolExecution(buildJsonObject {
                put("ok", body.error == null)
                put("requestId", body.requestId)
                put("url", body.url)
                put("statusCode", body.statusCode)
                body.contentType?.let { put("contentType", it) }
                body.contentEncoding?.let { put("contentEncoding", it) }
                if (body.body != null) {
                    put("body", Untrusted.wrapWithin(body.body, 50_000))
                }
                put("truncated", body.isTruncated)
                body.error?.let { put("error", it) }
                body.hint?.let { put("hint", it) }
            })
        } else {
            ToolExecution(buildJsonObject {
                put("ok", false)
                put("error", "not_found")
                requestId?.let { put("requestId", it) }
                urlPattern?.let { put("urlPattern", it) }
                // 找不到时最有用的信息是"窗口里还有多少、挤掉了多少"：
                // 有淘汰记录就说明可能是太早了，别再拿同一个模式反复查。
                val retention = net.retentionSnapshot()
                if (retention.trimmed > 0 || retention.kept > 0) {
                    put("retention", buildJsonObject {
                        put("kept", retention.kept)
                        put("trimmed", retention.trimmed)
                        retention.oldestKeptAt?.let { put("oldestKeptAt", it) }
                    })
                }
                if (retention.trimmed > 0) {
                    put("hint", "This response is no longer in the window (${retention.trimmed} earlier request(s) evicted). Re-trigger the action to capture it again instead of querying repeatedly.")
                }
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
        val retention = status?.retention ?: net.retentionSnapshot()
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
                // 抓包在跑但缺一条能力(比如进程内解密没接上):说出来,否则下层只能看到密文
                if (it.warnings.isNotEmpty()) {
                    put("warnings", JsonArray(it.warnings.map { w -> kotlinx.serialization.json.JsonPrimitive(w) }))
                }
            }
            // 历史是滚动窗口。查询为空时，这两件事必须能分开：
            // 真的没有这样的请求，还是它发生在窗口之外。否则调用方会一直重查。
            put("retention", buildJsonObject {
                put("kept", retention.kept)
                put("capacity", retention.capacity)
                put("trimmed", retention.trimmed)
                retention.oldestKeptAt?.let { put("oldestKeptAt", it) }
            })
            if (requests.isEmpty() && retention.trimmed > 0) {
                put("hint", "No match in the current window, but ${
                    retention.trimmed
                } earlier request(s) were already evicted. Do not keep re-querying: read the data from the screen, or widen the moment you capture (re-trigger the action) instead.")
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

/**
 * 运行只读 eBPF SO 偏移探测，排查指定 App 实际可挂钩的网络库（BoringSSL / Cronet）并生成插件配置。
 */
internal object PhoneProbeEbpf : PhoneTool {
    override val verb = Verb.PhoneProbeEbpf
    override val risk = Risk.Read
    override val label = "探测抓包偏移"
    override val def = ToolDef(
        "phone_probe_ebpf",
        Prompts.tool("phone_probe_ebpf"),
        mapOf(
            "targetPackage" to str("Target Android package name (optional; defaults to current captured app)."),
            "useHeuristicsOnly" to bool("Run ELF heuristics directly without echoing saved offset rules (default true)."),
        ),
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val targetPackage = a.str("targetPackage", max = 200)
        val useHeuristicsOnly = a.bool("useHeuristicsOnly", true)
        val net = ctx.network ?: return failure("network_capture_unavailable")

        val report = net.probeEbpf(targetPackage, useHeuristicsOnly)
            ?: return failure("ebpf_probe_unsupported")

        return ToolExecution(buildJsonObject {
            put("ok", report.ok)
            put("targetPackage", report.targetPackage)
            put("uid", report.uid)
            put("arch", report.arch)
            report.nativeLibraryDir?.let { put("nativeLibraryDir", it) }
            put("apkPaths", JsonArray(report.apkPaths.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("scannedLibrariesCount", report.scannedLibrariesCount)
            put("libraries", JsonArray(report.libraries.map { lib ->
                buildJsonObject {
                    put("path", lib.path)
                    put("name", lib.name)
                    put("inAppDir", lib.inAppDir)
                    put("sslSource", lib.sslSource)
                    lib.sslWriteOffset?.let { put("sslWriteOffset", it) }
                    lib.sslReadOffset?.let { put("sslReadOffset", it) }
                    lib.sslSniOffset?.let { put("sslSniOffset", it) }
                    put("cronetSource", lib.cronetSource)
                    lib.cronetCreateOffset?.let { put("cronetCreateOffset", it) }
                    lib.cronetResponseStartedOffset?.let { put("cronetResponseStartedOffset", it) }
                    lib.cronetReadCompletedOffset?.let { put("cronetReadCompletedOffset", it) }
                    lib.cronetSucceededOffset?.let { put("cronetSucceededOffset", it) }
                }
            }))
            put("unmatchedAppLibs", JsonArray(report.unmatchedAppLibs.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            report.suggestedProfileJson?.let { put("suggestedProfileJson", it) }
            report.error?.let { put("error", it) }
        })
    }
}

private fun failure(error: String) = ToolExecution(buildJsonObject {
    put("ok", false)
    put("error", error)
})
