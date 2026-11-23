package com.wangzan101.mika.cap.phone.network

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.agent.use.client.network.AgentNetworkManager
import com.agent.use.client.network.DeviceEbpfDetector
import com.agent.use.client.network.ebpf.EbpfDiscoveryResult
import com.agent.use.client.network.ebpf.LocalRootEbpfCaptureEngine
import com.agent.use.common.IAgentShell
import com.agent.use.common.network.CaptureMode
import com.agent.use.common.network.HttpRequestEvent
import com.agent.use.common.network.HttpResponseEvent
import com.agent.use.common.network.INetworkCapture
import com.agent.use.common.network.NetworkCaptureConfig
import com.agent.use.common.network.parser.GrpcDecoder
import com.agent.use.common.network.plugin.EbpfProbeProfile
import com.wangzan101.mika.core.net.EbpfDiscoveredLib
import com.wangzan101.mika.core.net.EbpfProbeReport
import com.wangzan101.mika.core.net.NetworkCaptureStatus
import com.wangzan101.mika.core.net.NetworkResponseBody
import com.wangzan101.mika.core.net.NetworkResponseInfo
import com.wangzan101.mika.core.net.NetworkRequestSummary
import com.wangzan101.mika.core.net.PhoneNetworkPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
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
    coroutineContext: kotlin.coroutines.CoroutineContext = Dispatchers.Default,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val discoveryRunner: ((String, Boolean) -> EbpfDiscoveryResult)? = null
) : PhoneNetworkPort, Closeable, AutoCloseable {

    companion object {
        private const val TAG = "PhoneNetworkManager"
        const val MAX_HISTORY_ENTRIES = 250
        const val MAX_LOCAL_BODY_ENTRIES = 250
        const val MAX_DISK_BODY_FILES = 500
        const val MAX_PERSISTED_BODY_BYTES = 1024 * 1024
        const val MIN_REATTACH_INTERVAL_MS = 1000L
        const val BODY_CACHE_DIR_NAME = "mika_network_bodies"
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
        val isTls: Boolean = true,
        val grpcEncoding: String? = null,
        val grpcStatus: String? = null,
        val grpcMessage: String? = null,
        val contentEncoding: String? = null,
        val hasResponseBody: Boolean = false
    )

    private val isClosed = AtomicBoolean(false)
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + coroutineContext)

    @Volatile
    private var internalAgentManager: AgentNetworkManager? = null

    @Volatile
    private var desiredTargetPackage: String? = null

    @Volatile
    private var desiredMode: CaptureMode = CaptureMode.AUTO

    @Volatile
    private var lastAttachAttemptMs: Long = 0L

    private val subscriptionHandles = CopyOnWriteArrayList<AutoCloseable>()

    /**
     * 交换级回调：请求事件、合并后的条目、以及**已解压**的响应体。
     *
     * 与 `responseFlow` 的区别是时机：这里在 body 拼完、chunked 与 gzip 都处理完之后才触发，
     * 订阅方能直接拿到明文。接口图谱的影子采集依赖这个时机。
     */
    private val exchangeSinks =
        CopyOnWriteArrayList<(HttpRequestEvent?, CapturedTrafficEntry, ByteArray?) -> Unit>()

    // 环形缓存与在途请求追踪
    private val trafficHistory = ConcurrentLinkedDeque<CapturedTrafficEntry>()
    private val inFlightRequests = ConcurrentHashMap<String, HttpRequestEvent>()
    private val pendingChunkBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val localBodyCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
                return size > MAX_LOCAL_BODY_ENTRIES
            }
        }
    )

    /**
     * 获取或懒加载底层 [AgentNetworkManager] 实例。
     */
    @Synchronized
    fun getOrCreateAgentManager(): AgentNetworkManager {
        internalAgentManager?.let { return it }

        runCatching { CapturePluginProfileStore.forContext(context).loadIntoRegistry() }

        val shell = shellProvider?.invoke()
        val manager = agentManagerFactory?.invoke(context, shell) ?: AgentNetworkManager(context = context, shell = shell)
        internalAgentManager = manager

        startEventCollection(manager)
        return manager
    }

    private fun startEventCollection(manager: AgentNetworkManager) {
        subscriptionHandles.forEach { runCatching { it.close() } }
        subscriptionHandles.clear()

        subscriptionHandles += manager.onDataChunk { requestId, chunk ->
            handleBodyChunk(requestId, chunk)
        }
        subscriptionHandles += manager.onError { requestId, _ ->
            if (requestId.isNotBlank()) {
                inFlightRequests.remove(requestId)
                pendingChunkBuffers.remove(requestId)
            }
        }
        subscriptionHandles += manager.onRequest { request ->
            handleRequestEvent(request)
        }
        subscriptionHandles += manager.onResponse { response ->
            handleResponseEvent(response)
        }
    }

    private fun handleBodyChunk(requestId: String, chunk: ByteArray) {
        if (requestId.isBlank() || chunk.isEmpty()) return
        val buf = pendingChunkBuffers.getOrPut(requestId) { ByteArrayOutputStream() }
        synchronized(buf) {
            if (buf.size() < MAX_PERSISTED_BODY_BYTES) {
                val allowed = (MAX_PERSISTED_BODY_BYTES - buf.size()).coerceAtLeast(0)
                val toWrite = minOf(chunk.size, allowed)
                if (toWrite > 0) {
                    buf.write(chunk, 0, toWrite)
                }
            }
        }
        // 若 chunkFlow 晚于 responseFlow 被调度，补写持久化缓存并刷新条目状态
        val completedEntry = trafficHistory.firstOrNull { it.requestId == requestId && it.statusCode != null }
        if (completedEntry != null) {
            val bytes = synchronized(buf) { buf.toByteArray() }
            if (bytes.isNotEmpty()) {
                synchronized(localBodyCache) { localBodyCache[requestId] = bytes }
                persistBodyToDisk(requestId, bytes)
                if (!completedEntry.hasResponseBody) {
                    recordEntry(completedEntry.copy(hasResponseBody = true))
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
        // 同一条响应会被派发两次：先到的密文一次，进程内解出的明文补发一次。
        // 第二次已经没有 in-flight 请求了，方法名和耗时要从已有记录里继承，
        // 否则那条记录会退化成 UNKNOWN / 0ms。
        val previous = trafficHistory.firstOrNull { it.requestId == response.requestId }
        val startTime = request?.timestamp ?: previous?.let { it.timestamp } ?: response.timestamp
        val duration = if (request != null) {
            (response.timestamp - startTime).coerceAtLeast(0L)
        } else {
            previous?.durationMs ?: (response.timestamp - startTime).coerceAtLeast(0L)
        }

        val chunkedBytes = pendingChunkBuffers.remove(response.requestId)?.let { buf ->
            synchronized(buf) { buf.toByteArray() }
        }
        val bodyBytes = when {
            chunkedBytes != null && chunkedBytes.isNotEmpty() -> chunkedBytes
            else -> internalAgentManager?.getResponseBody(response.requestId) ?: chunkedBytes
        }
        if (bodyBytes != null) {
            val capped = if (bodyBytes.size > MAX_PERSISTED_BODY_BYTES) {
                bodyBytes.copyOf(MAX_PERSISTED_BODY_BYTES)
            } else {
                bodyBytes
            }
            synchronized(localBodyCache) {
                localBodyCache[response.requestId] = capped
            }
            if (capped.isNotEmpty()) {
                persistBodyToDisk(response.requestId, capped)
            }
        }

        val updated = CapturedTrafficEntry(
            requestId = response.requestId,
            timestamp = response.timestamp,
            url = response.url,
            method = request?.method ?: previous?.method ?: "UNKNOWN",
            statusCode = response.statusCode,
            statusText = response.statusText,
            durationMs = duration,
            mimeType = response.mimeType,
            contentLength = response.contentLength,
            isTls = request?.isTls ?: response.url.startsWith("https://", ignoreCase = true),
            grpcEncoding = response.headers.lastOrNull { it.name.equals("grpc-encoding", ignoreCase = true) }?.value?.trim(),
            grpcStatus = response.headers.lastOrNull { it.name.equals("grpc-status", ignoreCase = true) }?.value?.trim(),
            grpcMessage = response.headers.lastOrNull { it.name.equals("grpc-message", ignoreCase = true) }?.value?.trim(),
            contentEncoding = response.headers.lastOrNull { it.name.equals("content-encoding", ignoreCase = true) }?.value?.trim(),
            hasResponseBody = bodyBytes != null && bodyBytes.isNotEmpty()
        )
        recordEntry(updated)
        dispatchExchange(request, updated, bodyBytes)
    }

    /**
     * 注册交换级回调，返回的句柄用来卸载。
     *
     * 回调抛异常不会影响抓包主链路——采集是旁路，坏一个订阅方不该让工具看不到网络。
     */
    fun addExchangeSink(
        sink: (request: HttpRequestEvent?, entry: CapturedTrafficEntry, body: ByteArray?) -> Unit
    ): AutoCloseable {
        exchangeSinks += sink
        return AutoCloseable { exchangeSinks -= sink }
    }

    private fun dispatchExchange(request: HttpRequestEvent?, entry: CapturedTrafficEntry, body: ByteArray?) {
        if (exchangeSinks.isEmpty()) return
        for (sink in exchangeSinks) {
            try {
                sink(request, entry, body)
            } catch (t: Throwable) {
                Log.w(TAG, "exchange sink failed for ${entry.url}", t)
            }
        }
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

        desiredTargetPackage = target
        desiredMode = mode

        val current = internalAgentManager?.targetPackage
        if (current == target && isCapturing()) {
            Log.d(TAG, "Already capturing for package: $target")
            return true
        }

        lastAttachAttemptMs = clockMs()
        Log.i(TAG, "Attaching network capture to target package: $target (mode=$mode)")
        val manager = getOrCreateAgentManager()
        return manager.enable(target, mode)
    }

    /**
     * 若曾经设置过目标包但当前抓取已断开（例如首次 attach 时目标进程尚未加载或临时失败），按冷却间隔自动尝试恢复。
     */
    @Synchronized
    fun ensureCapturing(): Boolean {
        if (isClosed.get()) return false
        val target = desiredTargetPackage ?: return isCapturing()
        if (isCapturing()) return true

        val now = clockMs()
        if (now - lastAttachAttemptMs < MIN_REATTACH_INTERVAL_MS) {
            return false
        }
        lastAttachAttemptMs = now
        Log.i(TAG, "Re-attaching dropped network capture for $target (mode=$desiredMode)")
        val manager = getOrCreateAgentManager()
        return manager.enable(target, desiredMode)
    }

    /**
     * 停止当前抓包并重置目标应用。
     */
    @Synchronized
    fun detach() {
        Log.i(TAG, "Detaching network capture...")
        desiredTargetPackage = null
        internalAgentManager?.disable()
        inFlightRequests.clear()
        pendingChunkBuffers.clear()
    }

    // ==========================================
    // 高级 CDP 响应等待与报文获取 (实现 PhoneNetworkPort)
    // ==========================================

    /**
     * 等待指定 App 匹配指定 URL 正则/子串的网络 API 响应（如订单提交、数据刷新、搜索接口）。
     * 超时未收到返回 null。
     */
    override suspend fun waitForResponse(urlPattern: String, timeoutMs: Long): NetworkResponseInfo? {
        ensureCapturing()
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
        val bodyStr = if (asString) {
            GrpcDecoder.formatResponseBodyAsString(
                body = bodyBytes,
                contentType = entry?.mimeType,
                grpcEncoding = entry?.grpcEncoding,
                grpcStatus = entry?.grpcStatus,
                grpcMessage = entry?.grpcMessage,
                contentEncoding = entry?.contentEncoding
            )
        } else {
            bodyBytes?.let { java.util.Base64.getEncoder().encodeToString(it) }
        }
        val error = when {
            bodyBytes != null && bodyBytes.isEmpty() && bodyStr == null ->
                "empty_response_body"
            bodyBytes != null || bodyStr != null -> null
            entry?.isTls == true && entry.method == "CONNECT" && getActiveMode() == CaptureMode.VPN_SERVICE ->
                "tls_encrypted_in_vpn_mode_use_root_ebpf"
            entry != null && entry.statusCode == null ->
                "response_pending_in_flight"
            entry != null && (entry.statusCode == 204 || entry.statusCode == 304 || entry.contentLength == 0L) ->
                "empty_response_body"
            !isCapturing() && entry == null ->
                lastCaptureError() ?: "capture_not_active"
            else ->
                "response_body_not_found_or_evicted"
        }

        return NetworkResponseBody(
            requestId = targetRequestId,
            url = entry?.url ?: "",
            statusCode = entry?.statusCode ?: 0,
            contentType = entry?.mimeType,
            body = bodyStr,
            isTruncated = (bodyBytes?.size ?: 0) >= MAX_PERSISTED_BODY_BYTES,
            error = error,
            contentEncoding = entry?.contentEncoding
        )
    }

    /**
     * 查看当前 App 最近捕获的请求与响应列表。
     */
    override suspend fun inspectNetwork(limit: Int, filter: String?): List<NetworkRequestSummary> {
        ensureCapturing()
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
                timestamp = entry.timestamp,
                contentType = entry.mimeType,
                hasResponseBody = entry.hasResponseBody || hasCachedResponseBody(entry.requestId),
                state = if (entry.statusCode != null) "completed" else "in_flight"
            )
        }
    }

    override suspend fun captureStatus(): NetworkCaptureStatus {
        val mgr = internalAgentManager
        val capturing = mgr?.isCapturing == true
        val modeName = when {
            !capturing -> "NONE"
            mgr?.getActiveMode() == CaptureMode.EBPF -> "EBPF"
            mgr?.getActiveMode() == CaptureMode.VPN_SERVICE -> "VPN_SERVICE"
            else -> "AUTO"
        }
        return NetworkCaptureStatus(
            enabled = !isClosed.get(),
            capturing = capturing,
            activeMode = modeName,
            targetPackage = mgr?.targetPackage,
            error = mgr?.lastError ?: if (!capturing) "capture_not_active" else null
        )
    }

    fun lastCaptureError(): String? = internalAgentManager?.let { it.lastError ?: it.lastEbpfFallbackError }

    /**
     * 按 requestId 获取对应请求的完整响应体字节数组（优先查本地 LRU、进行中分片、底层引擎、磁盘缓存）。
     */
    fun getResponseBody(requestId: String): ByteArray? {
        synchronized(localBodyCache) {
            localBodyCache[requestId]?.let { return it }
        }
        pendingChunkBuffers[requestId]?.let { buf ->
            val bytes = synchronized(buf) { buf.toByteArray() }
            if (bytes.isNotEmpty()) return bytes
        }
        internalAgentManager?.getResponseBody(requestId)?.let { engineBytes ->
            val capped = if (engineBytes.size > MAX_PERSISTED_BODY_BYTES) {
                engineBytes.copyOf(MAX_PERSISTED_BODY_BYTES)
            } else {
                engineBytes
            }
            synchronized(localBodyCache) {
                localBodyCache[requestId] = capped
            }
            if (capped.isNotEmpty()) {
                persistBodyToDisk(requestId, capped)
            }
            return capped
        }
        readBodyFromDisk(requestId)?.let { diskBytes ->
            synchronized(localBodyCache) {
                localBodyCache[requestId] = diskBytes
            }
            return diskBytes
        }
        return null
    }

    /**
     * 按 requestId 获取对应请求的响应体文本字符串（如 JSON、HTML、XML）。
     */
    fun getResponseBodyAsString(requestId: String): String? {
        return getResponseBody(requestId)?.toString(Charsets.UTF_8)
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
     * 清理内存和磁盘中的历史流量及响应体缓存。
     */
    fun clearHistory() {
        trafficHistory.clear()
        inFlightRequests.clear()
        pendingChunkBuffers.clear()
        synchronized(localBodyCache) {
            localBodyCache.clear()
        }
        clearDiskBodyCache()
    }

    private fun hasCachedResponseBody(requestId: String): Boolean {
        synchronized(localBodyCache) {
            val cached = localBodyCache[requestId]
            if (cached != null) return cached.isNotEmpty()
        }
        pendingChunkBuffers[requestId]?.let { buf ->
            synchronized(buf) {
                if (buf.size() > 0) return true
            }
        }
        if (internalAgentManager?.getResponseBody(requestId)?.isNotEmpty() == true) {
            return true
        }
        return bodyCacheFile(requestId)?.let { it.exists() && it.length() > 0L } == true
    }

    private fun bodyCacheDir(): File? {
        val base = runCatching { context.cacheDir }.getOrNull() ?: return null
        return File(base, BODY_CACHE_DIR_NAME)
    }

    private fun bodyCacheFile(requestId: String): File? {
        val dir = bodyCacheDir() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(requestId.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$hex.bin")
    }

    private fun persistBodyToDisk(requestId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        runCatching {
            val file = bodyCacheFile(requestId) ?: return
            val dir = file.parentFile ?: return
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.writeBytes(bytes)
                tmp.delete()
            }
            pruneDiskCacheIfNeeded(dir)
        }
    }

    private fun readBodyFromDisk(requestId: String): ByteArray? {
        return runCatching {
            val file = bodyCacheFile(requestId) ?: return null
            if (file.isFile && file.length() > 0L) file.readBytes() else null
        }.getOrNull()
    }

    private fun pruneDiskCacheIfNeeded(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") } ?: return
        if (files.size <= MAX_DISK_BODY_FILES) return
        val excess = files.size - MAX_DISK_BODY_FILES
        files.sortedBy { it.lastModified() }
            .take(excess)
            .forEach { it.delete() }
    }

    private fun clearDiskBodyCache() {
        runCatching {
            val dir = bodyCacheDir() ?: return
            dir.listFiles()?.forEach { it.delete() }
        }
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

    override suspend fun probeEbpf(targetPackage: String?, useHeuristicsOnly: Boolean): EbpfProbeReport {
        val pkg = targetPackage?.trim()?.takeIf { it.isNotEmpty() } ?: currentPackage ?: desiredTargetPackage
            ?: return EbpfProbeReport(ok = false, targetPackage = "", error = "missing_target_package")

        var nativeLibDir: String? = null
        val apkPaths = ArrayList<String>()
        runCatching {
            val pm = context.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(pkg, 0)
            }
            nativeLibDir = appInfo.nativeLibraryDir
            appInfo.publicSourceDir?.takeIf { it.isNotBlank() }?.let { apkPaths.add(it) }
            appInfo.splitSourceDirs?.forEach { split ->
                if (!split.isNullOrBlank() && split !in apkPaths) apkPaths.add(split)
            }
        }

        val result = withContext(Dispatchers.IO) {
            discoveryRunner?.invoke(pkg, useHeuristicsOnly)
                ?: LocalRootEbpfCaptureEngine.discoverAppProfile(context, pkg, useHeuristicsOnly)
        }
        val report = result.report
        val suggestedJson = result.suggestedProfile?.let { EbpfProbeProfile.toJson(mapOf(pkg to it)) }

        return EbpfProbeReport(
            ok = result.ok,
            targetPackage = pkg,
            uid = report?.uid ?: -1,
            arch = report?.arch ?: "unknown",
            nativeLibraryDir = nativeLibDir,
            apkPaths = apkPaths,
            scannedLibrariesCount = report?.scannedLibrariesCount ?: 0,
            libraries = report?.libraries.orEmpty().map { lib ->
                EbpfDiscoveredLib(
                    path = lib.path,
                    name = lib.name,
                    inAppDir = lib.inAppDir,
                    sslSource = lib.sslSource,
                    sslWriteOffset = lib.sslWriteOffset?.let(EbpfProbeProfile::formatOffset),
                    sslReadOffset = lib.sslReadOffset?.let(EbpfProbeProfile::formatOffset),
                    sslSniOffset = lib.sslSniOffset?.let(EbpfProbeProfile::formatOffset),
                    cronetSource = lib.cronetSource,
                    cronetCreateOffset = lib.cronetCreateOffset?.let(EbpfProbeProfile::formatOffset),
                    cronetResponseStartedOffset = lib.cronetResponseStartedOffset?.let(EbpfProbeProfile::formatOffset),
                    cronetReadCompletedOffset = lib.cronetReadCompletedOffset?.let(EbpfProbeProfile::formatOffset),
                    cronetSucceededOffset = lib.cronetSucceededOffset?.let(EbpfProbeProfile::formatOffset),
                )
            },
            unmatchedAppLibs = report?.unmatchedAppLibs.orEmpty(),
            suggestedProfileJson = suggestedJson,
            error = result.error,
        )
    }

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
            desiredTargetPackage = null
            subscriptionHandles.forEach { runCatching { it.close() } }
            subscriptionHandles.clear()
            detach()
            internalAgentManager?.close()
            internalAgentManager = null
            trafficHistory.clear()
            inFlightRequests.clear()
            pendingChunkBuffers.clear()
            synchronized(localBodyCache) {
                localBodyCache.clear()
            }
            supervisorJob.cancel()
        }
    }
}
