package com.wangzan101.mika.core.net

import kotlinx.serialization.Serializable

/**
 * 抓包端口与它返回的几种模型。
 *
 * 放在这里而不是 `cap/phone`:端口是**中性类型**——界面状态([com.wangzan101.mika.core.uistate.ScreenPort])
 * 要把它交给工具层,而核心的界面状态不许认识能力包(`cap/` 下那些)。模型跟着端口一起放,
 * `cap/phone` 只保留工具上下文里的那个入口。
 */

/**
 * 捕获到的网络响应概要信息。
 */
@Serializable
data class NetworkResponseInfo(
    val requestId: String,
    val url: String,
    val method: String,
    val statusCode: Int,
    val durationMs: Long,
    val contentType: String? = null,
    val contentLength: Long = 0L,
    val timing: Long = System.currentTimeMillis(),
)

/**
 * 捕获到的 HTTP 响应体数据。
 */
@Serializable
data class NetworkResponseBody(
    val requestId: String,
    val url: String,
    val statusCode: Int,
    val contentType: String?,
    val body: String?,
    val isTruncated: Boolean = false,
    val error: String? = null,
    val contentEncoding: String? = null,
)

/**
 * 近期请求/响应列表中的单条摘要。
 */
@Serializable
data class NetworkRequestSummary(
    val requestId: String,
    val url: String,
    val method: String,
    val statusCode: Int,
    val durationMs: Long,
    val timestamp: Long,
    val contentType: String? = null,
    val hasResponseBody: Boolean = false,
    val state: String = "completed",
)

/**
 * 端侧抓包当前运行状态摘要。
 */
@Serializable
data class NetworkCaptureStatus(
    val enabled: Boolean = true,
    val capturing: Boolean = false,
    val activeMode: String = "NONE",
    val targetPackage: String? = null,
    val error: String? = null,
)

/**
 * 端侧 `--discover-only` 扫描到的单个共享库及其 BoringSSL/Cronet 偏移结果。
 */
@Serializable
data class EbpfDiscoveredLib(
    val path: String,
    val name: String,
    val inAppDir: Boolean,
    val sslSource: String,
    val sslWriteOffset: String? = null,
    val sslReadOffset: String? = null,
    val sslSniOffset: String? = null,
    val cronetSource: String,
    val cronetCreateOffset: String? = null,
    val cronetResponseStartedOffset: String? = null,
    val cronetReadCompletedOffset: String? = null,
    val cronetSucceededOffset: String? = null,
)

/**
 * 端侧对指定 App 执行 `libaau_ebpf.so --discover-only` 与 APK 布局检查的诊断结果。
 */
@Serializable
data class EbpfProbeReport(
    val ok: Boolean,
    val targetPackage: String,
    val uid: Int = -1,
    val arch: String = "unknown",
    val nativeLibraryDir: String? = null,
    val apkPaths: List<String> = emptyList(),
    val scannedLibrariesCount: Int = 0,
    val libraries: List<EbpfDiscoveredLib> = emptyList(),
    val unmatchedAppLibs: List<String> = emptyList(),
    val suggestedProfileJson: String? = null,
    val error: String? = null,
)

/**
 * 手机网络抓包端口：提供等待网络响应、提取响应体、查看近期请求列表与 eBPF 偏移探测的能力。
 * 底层可由 Root eBPF 或免 Root VpnService 驱动。
 */
interface PhoneNetworkPort {
    /**
     * 等待指定 App 匹配指定 URL 正则/子串的网络 API 响应（如订单提交、数据刷新、搜索接口）。
     * 超时未收到返回 null。
     */
    suspend fun waitForResponse(urlPattern: String, timeoutMs: Long): NetworkResponseInfo?

    /**
     * 按 requestId 或 urlPattern 直接提取捕获的 HTTP 响应体（支持 JSON/文本），无需 OCR。
     * 未找到返回 null。
     */
    suspend fun getResponseBody(requestId: String?, urlPattern: String?, asString: Boolean = true): NetworkResponseBody?

    /**
     * 查看当前 App 最近捕获的请求与响应列表。
     */
    suspend fun inspectNetwork(limit: Int = 10, filter: String? = null): List<NetworkRequestSummary>

    /**
     * 返回当前底层抓包健康与活跃状态；未实现的模拟端口可返回 null。
     */
    suspend fun captureStatus(): NetworkCaptureStatus? = null

    /**
     * 运行只读的 `--discover-only` 端侧探测，返回匹配到的 BoringSSL / Cronet 偏移及 APK 路径。
     */
    suspend fun probeEbpf(targetPackage: String? = null, useHeuristicsOnly: Boolean = true): EbpfProbeReport? = null
}
