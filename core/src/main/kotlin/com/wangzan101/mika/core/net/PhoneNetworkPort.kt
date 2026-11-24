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
 * 抓包在跑、但这个 App 的进程内解密一次也没接上:响应体里只有密文。
 *
 * 这不是"没抓到",是"抓到了但解不开"——明文只在 App 自己进程里出现,得让它重起一次才蹲得到注册。
 */
const val ERROR_PLAINTEXT_BRIDGE_MISSING = "plaintext_bridge_not_installed"

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
    /** 拿不到明文时给模型的一句可执行建议(没有问题时为 null)。 */
    val hint: String? = null,
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
    /** 抓包在跑、但有一条能力是缺的(比如进程内解密没接上):说明白,别让调用方把密文当明文。 */
    val warnings: List<String> = emptyList(),
    /** 历史留存的窗口。查不到请求时先看这里，别把"被挤出窗口"当成"没抓到"。 */
    val retention: CaptureRetention? = null,
)

/**
 * 抓包历史保留了多少、丢了多久以前的。
 *
 * 历史是滚动窗口而不是全量日志：长任务里请求一多，早期条目会被挤出。
 * 把这个数报给调用方，是为了让"查不到"能被区分为"确实没发生"和"发生得太早"。
 */
@Serializable
data class CaptureRetention(
    /** 当前可检索的条数。 */
    val kept: Int = 0,
    /** 窗口上限。 */
    val capacity: Int = 0,
    /** 本次抓包以来被挤出窗口的条数。 */
    val trimmed: Int = 0,
    /** 仍在窗口里的最早一条的时间戳(毫秒)；空窗口为 null。 */
    val oldestKeptAt: Long? = null,
)

/**
 * 打开 App 之前先挂抓包的准备结果。
 *
 * 有的 App 在 TLS 之上再套一层自己的加密,明文只在**进程内**出现,端侧只能靠在 natives 注册时
 * 挂上去的观察点拿到。注册只发生一次、且早于"App 已经显示在屏上",所以必须在启动之前就把抓包挂好;
 * 挂晚了就只能等下一次冷启动。
 */
enum class CapturePreheat {
    /** 不用管:抓包没开、不是这个端口的事,或者这个 App 不需要进程内通道。 */
    NOT_NEEDED,

    /** 已经挂好了,接下来这个 App 启动时进程内通道会在注册那一刻接上。 */
    READY,

    /** 挂是挂上了,但这个 App 已经在跑、注册已经过去了:再打开一次得冷启动,才拿得到明文。 */
    RESTART_REQUIRED,

    /** 想挂但没挂上(没 root、助手没连上):照常打开,只是拿不到明文。 */
    FAILED,
}

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
     * 在启动 [packageName] 之前先把抓包挂到它身上,返回这次要不要冷启动才拿得到明文。
     *
     * 打开 App 的动作必须在**启动之前**调它:端侧能拿到明文的那条通道挂在目标进程的 natives 注册上,
     * 注册只发生一次。挂晚了不会报错,只会一路拿密文——所以宁可在这里先挂,也不要等 App 显示出来再挂。
     *
     * 不实现的端口(网页、测试替身)返回 [CapturePreheat.NOT_NEEDED],调用方照常启动。
     */
    suspend fun prepareCapture(packageName: String): CapturePreheat = CapturePreheat.NOT_NEEDED

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
     * 历史窗口现状：保留了多少条、本次抓包淘汰了多少条、最早一条是什么时候。
     *
     * 列表为空时必须能和这个一起看：`kept=0` 与 `trimmed>0` 是两种完全不同的结论。
     * 不实现的端口返回空窗口。
     */
    fun retentionSnapshot(): CaptureRetention = CaptureRetention()

    /**
     * 返回当前底层抓包健康与活跃状态；未实现的模拟端口可返回 null。
     */
    suspend fun captureStatus(): NetworkCaptureStatus? = null

    /**
     * 运行只读的 `--discover-only` 端侧探测，返回匹配到的 BoringSSL / Cronet 偏移及 APK 路径。
     */
    suspend fun probeEbpf(targetPackage: String? = null, useHeuristicsOnly: Boolean = true): EbpfProbeReport? = null
}
