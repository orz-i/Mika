package com.wangzan101.mika.cap.phone.network

import android.content.Context
import android.util.Log
import com.agent.use.client.network.AgentNetworkManager
import com.agent.use.client.network.DeviceEbpfDetector
import com.agent.use.common.IAgentShell
import com.agent.use.common.network.CaptureMode
import com.agent.use.common.network.HttpRequestEvent
import com.agent.use.common.network.HttpResponseEvent
import com.wangzan101.mika.core.cap.phone.NetworkResponseBody
import com.wangzan101.mika.core.cap.phone.NetworkResponseInfo
import com.wangzan101.mika.core.cap.phone.NetworkRequestSummary
import com.wangzan101.mika.core.cap.phone.PhoneNetworkPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机侧原生网络嗅探与流量捕获服务管理器。
 *
 * 核心职责：
 * 1. 桥接并封装 `aau-client` 的 [AgentNetworkManager]（Root eBPF 优选 + 非 Root VpnService 兜底）；
 * 2. 在任务启动或切换前台目标 App 时，自适应为目标包名激活网络抓包；
 * 3. 维护近期请求与响应的环形缓存 (Ring Buffer)，支持 Agent 工具快速检索；
 * 4. 提供 `waitForResponse(pattern, timeout)` 与 `getResponseBody(requestId)` 等高层 CDP 能力；
 * 5. 与 Mika 的 [com.wangzan101.mika.cap.phone.PhoneServices] 及 [com.wangzan101.mika.task.TaskRun] 统一生命周期协同。
 */
class PhoneNetworkManager(
    val context: Context,
    private val shellProvider: (() -> IAgentShell?)? = null,
    private val agentManagerFactory: ((Context, IAgentShell?) -> AgentNetworkManager)? = null,
    coroutineContext: kotlin.coroutines.CoroutineContext = Dispatchers.Default
) : PhoneNetworkPort, Closeable, AutoCloseable {

    companion object {
        private const val TAG = "PhoneNetworkManager"
        const val MAX_HISTORY_ENTRIES = 100
        const val DEFAULT_TIMEOUT_MS = 5000L
    }

    /**
     * 捕获的网络流量条目模型（供 Agent 工具查看分析）。
     */
    data class CapturedTrafficEntry(
        val requestId: String,
        val timestamp: Long,
        val url: String,
        val method: String,
        val statusCode: Int? = null,
        val statusText: String? = null,
        val durationMs: Long? = null,
        val mimeType: String? = null,
        val contentLength: Long = -1L,
        val isTls: Boolean = true
    )

    private val isClosed = AtomicBoolean(false)
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + coroutineContext)

    @Volatile
    private var internalAgentManager: AgentNetworkManager? = null

    private var eventCollectionJob: Job? = null

    // 环形缓存与在途请求追踪
    private val trafficHistory = ConcurrentLinkedDeque<CapturedTrafficEntry>()
    private val inFlightRequests = ConcurrentHashMap<String, HttpRequestEvent>()

    /**
     * 获取或懒加载底层 [AgentNetworkManager] 实例。
     */
    @Synchronized
    fun getOrCreateAgentManager(): AgentNetworkManager {
        internalAgentManager?.let { return it }

        val shell = shellProvider?.invoke()
        val manager = agentManagerFactory?.invoke(context, shell) ?: AgentNetworkManager(context = context, shell = shell)
        internalAgentManager = manager

        startEventCollection(manager)
        return manager
    }

    private fun startEventCollection(manager: AgentNetworkManager) {
        eventCollectionJob?.cancel()
        eventCollectionJob = scope.launch {
            launch {
                manager.requestFlow.collect { request ->
                    handleRequestEvent(request)
                }
            }
            launch {
                manager.responseFlow.collect { response ->
                    handleResponseEvent(response)
                }
            }
        }
    }

    private fun handleRequestEvent(request: HttpRequestEvent) {
        inFlightRequests[request.requestId] = request
        val entry = CapturedTrafficEntry(
            requestId = request.requestId,
            timestamp = request.timestamp,
            url = request.url,
            method = request.method,
            isTls = request.isTls
        )
        recordEntry(entry)
    }

    private fun handleResponseEvent(response: HttpResponseEvent) {
        val request = inFlightRequests.remove(response.requestId)
        val startTime = request?.timestamp ?: response.timestamp
        val duration = (response.timestamp - startTime).coerceAtLeast(0L)

        val updated = CapturedTrafficEntry(
            requestId = response.requestId,
            timestamp = response.timestamp,
            url = response.url,
            method = request?.method ?: "UNKNOWN",
            statusCode = response.statusCode,
            statusText = response.statusText,
            durationMs = duration,
            mimeType = response.mimeType,
            contentLength = response.contentLength,
            isTls = request?.isTls ?: response.url.startsWith("https://", ignoreCase = true)
        )
        recordEntry(updated)
    }

    @Synchronized
    private fun recordEntry(entry: CapturedTrafficEntry) {
        // 若已存在相同 requestId 的占位条目（即先前的请求记录），则替换为更新后的完整记录
        val existing = trafficHistory.firstOrNull { it.requestId == entry.requestId }
        if (existing != null) {
            trafficHistory.remove(existing)
        }

        trafficHistory.addFirst(entry)

        while (trafficHistory.size > MAX_HISTORY_ENTRIES) {
            trafficHistory.removeLast()
        }
    }

    // ==========================================
    // 目标应用关联与控制
    // ==========================================

    /**
     * 针对指定目标应用包名开启或切换网络抓包。
     *
     * @param packageName 目标应用包名，为 null 或空时停止抓包
     * @param mode 抓包模式（默认 [CaptureMode.AUTO] 优先 Root eBPF，失败回退 VPN）
     * @return 是否成功附加并启动抓包
     */
    @Synchronized
    fun attachToPackage(packageName: String?, mode: CaptureMode = CaptureMode.AUTO): Boolean {
        if (isClosed.get()) {
            Log.w(TAG, "Cannot attachToPackage: PhoneNetworkManager is closed")
            return false
        }

        val target = packageName?.trim()
        if (target.isNullOrEmpty()) {
            detach()
            return true
        }

        val current = internalAgentManager?.targetPackage
        if (current == target && isCapturing()) {
            Log.d(TAG, "Already capturing for package: $target")
            return true
        }

        Log.i(TAG, "Attaching network capture to target package: $target (mode=$mode)")
        val manager = getOrCreateAgentManager()
        return manager.enable(target, mode)
    }

    /**
     * 停止当前抓包并重置目标应用。
     */
    @Synchronized
    fun detach() {
        Log.i(TAG, "Detaching network capture...")
        internalAgentManager?.disable()
        inFlightRequests.clear()
    }

    // ==========================================
    // 高级 CDP 响应等待与报文获取 (实现 PhoneNetworkPort)
    // ==========================================

    /**
     * 等待指定 App 匹配指定 URL 正则/子串的网络 API 响应（如订单提交、数据刷新、搜索接口）。
     * 超时未收到返回 null。
     */
    override suspend fun waitForResponse(urlPattern: String, timeoutMs: Long): NetworkResponseInfo? {
        return try {
            val res = getOrCreateAgentManager().waitForResponse(urlPattern, timeoutMs)
            val entry = findRecentTraffic(urlPattern)
            NetworkResponseInfo(
                requestId = res.requestId,
                url = res.url,
                method = entry?.method ?: "UNKNOWN",
                statusCode = res.statusCode,
                durationMs = entry?.durationMs ?: 0L,
                contentType = res.mimeType,
                contentLength = res.contentLength,
                timing = res.timestamp
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 挂起等待指定 URL 匹配模式的原生响应事件到达或超时。
     */
    suspend fun waitForResponseEvent(pattern: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): HttpResponseEvent {
        val manager = getOrCreateAgentManager()
        return manager.waitForResponse(pattern, timeoutMs)
    }

    /**
     * 按 requestId 或 urlPattern 直接提取捕获的 HTTP 响应体（支持 JSON/文本），无需 OCR。
     * 未找到返回 null。
     */
    override suspend fun getResponseBody(requestId: String?, urlPattern: String?, asString: Boolean): NetworkResponseBody? {
        val targetRequestId = requestId ?: urlPattern?.let { findRecentTraffic(it)?.requestId } ?: return null
        val entry = trafficHistory.firstOrNull { it.requestId == targetRequestId }
        val bodyBytes = getResponseBody(targetRequestId)
        val bodyStr = if (asString) bodyBytes?.toString(Charsets.UTF_8) else bodyBytes?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }

        return NetworkResponseBody(
            requestId = targetRequestId,
            url = entry?.url ?: "",
            statusCode = entry?.statusCode ?: 200,
            contentType = entry?.mimeType,
            body = bodyStr,
            isTruncated = false,
            error = if (bodyBytes == null) "response_body_not_found_or_evicted" else null
        )
    }

    /**
     * 查看当前 App 最近捕获的请求与响应列表。
     */
    override suspend fun inspectNetwork(limit: Int, filter: String?): List<NetworkRequestSummary> {
        val regex = filter?.let { runCatching { Regex(it) }.getOrNull() }
        val entries = trafficHistory.filter { entry ->
            if (filter == null) true
            else if (regex != null && runCatching { regex.containsMatchIn(entry.url) }.getOrDefault(false)) true
            else entry.url.contains(filter, ignoreCase = true)
        }.take(limit.coerceAtLeast(1))

        return entries.map { entry ->
            NetworkRequestSummary(
                requestId = entry.requestId,
                url = entry.url,
                method = entry.method,
                statusCode = entry.statusCode ?: 0,
                durationMs = entry.durationMs ?: 0L,
                timestamp = entry.timestamp
            )
        }
    }

    /**
     * 按 requestId 获取对应请求的完整响应体字节数组。
     */
    fun getResponseBody(requestId: String): ByteArray? {
        val manager = internalAgentManager ?: return null
        return manager.getResponseBody(requestId)
    }

    /**
     * 按 requestId 获取对应请求的响应体文本字符串（如 JSON、HTML、XML）。
     */
    fun getResponseBodyAsString(requestId: String): String? {
        val manager = internalAgentManager ?: return null
        return manager.getResponseBodyAsString(requestId)
    }

    /**
     * 根据 URL 正则或子串在近期缓存中匹配最新的响应，并提取其响应体文本。
     *
     * @param urlPattern URL 正则表达式或子串
     * @return 提取到的响应体文本，若未匹配到或无响应体则返回 null
     */
    fun getResponseBodyForUrl(urlPattern: String): String? {
        val entry = findRecentTraffic(urlPattern) ?: return null
        return getResponseBodyAsString(entry.requestId)
    }

    // ==========================================
    // 流量历史检索与状态查询
    // ==========================================

    /**
     * 获取最近捕获的网络流量列表（最新在前）。
     *
     * @param limit 最多返回条数（默认 20 条）
     */
    fun getRecentTraffic(limit: Int = 20): List<CapturedTrafficEntry> {
        return trafficHistory.take(limit.coerceAtLeast(1))
    }

    /**
     * 在近期历史记录中检索满足 URL 条件的最新条目。
     */
    fun findRecentTraffic(pattern: String): CapturedTrafficEntry? {
        val regex = runCatching { Regex(pattern) }.getOrNull()
        return trafficHistory.firstOrNull { entry ->
            if (regex != null && runCatching { regex.containsMatchIn(entry.url) }.getOrDefault(false)) {
                true
            } else {
                entry.url.contains(pattern, ignoreCase = true)
            }
        }
    }

    /**
     * 清理内存中的历史流量缓存。
     */
    fun clearHistory() {
        trafficHistory.clear()
        inFlightRequests.clear()
    }

    /**
     * 当前是否正在进行抓包。
     */
    fun isCapturing(): Boolean = internalAgentManager?.isCapturing == true

    /**
     * 当前绑定的目标包名。
     */
    val currentPackage: String?
        get() = internalAgentManager?.targetPackage

    /**
     * 检查当前设备是否满足 Root eBPF 抓包条件（不提前创建并缓存 Manager）。
     */
    fun isEbpfSupported(): Boolean = internalAgentManager?.isEbpfSupported()
        ?: (DeviceEbpfDetector.isDeviceRooted(shellProvider?.invoke()) && DeviceEbpfDetector.isKernelSupported())

    /**
     * 当前内存中缓存的历史网络请求条数。
     */
    fun cachedHistoryCount(): Int = trafficHistory.size

    /**
     * 当前实际生效的抓包模式。
     */
    fun getActiveMode(): CaptureMode = internalAgentManager?.getActiveMode() ?: CaptureMode.AUTO

    /**
     * 获取原生请求流。
     */
    val requestFlow: SharedFlow<HttpRequestEvent>?
        get() = internalAgentManager?.requestFlow

    /**
     * 获取原生响应流。
     */
    val responseFlow: SharedFlow<HttpResponseEvent>?
        get() = internalAgentManager?.responseFlow

    // ==========================================
    // 资源释放
    // ==========================================

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            Log.i(TAG, "Closing PhoneNetworkManager...")
            eventCollectionJob?.cancel()
            detach()
            internalAgentManager?.close()
            internalAgentManager = null
            trafficHistory.clear()
            inFlightRequests.clear()
            supervisorJob.cancel()
        }
    }
}
