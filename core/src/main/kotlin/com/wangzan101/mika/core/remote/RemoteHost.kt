package com.wangzan101.mika.core.remote

import com.agent.use.remote.ListenScope
import com.agent.use.remote.RemoteBridge
import com.agent.use.remote.RemoteConfig
import com.agent.use.remote.spi.RemoteDevicePort
import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.device.toJson
import java.net.InetSocketAddress

/**
 * Mika 的装配壳：把设置页的三个开关、设备能力实现、历史与导出的 Mika 实现接到 `aau-remote` 的门面上。
 *
 * 这一层不做任何协议判断——监听、鉴权、路由、状态码都在 `RemoteBridge` 里。
 * 留这个壳是为了让 [McpRuntime] 与既有测试不必关心 AAU 的配置模型。
 */
class RemoteHost(
    tasks: RemoteTasks,
    token: () -> String?,
    port: Int,
    allowAllInterfaces: Boolean,
    serverVersion: String,
    clock: () -> Long = System::currentTimeMillis,
    onError: (Throwable) -> Unit = {},
    loopbackToken: () -> String? = { null },
    device: RemoteDevicePort? = null,
    history: RemoteHistory? = null,
    records: RunExportSource? = null,
    endpoints: RemoteEndpoints? = null,
) {
    private val bridge = RemoteBridge(
        config = RemoteConfig(
            port = port,
            listen = if (allowAllInterfaces) ListenScope.PrivateNetworks else ListenScope.LoopbackOnly,
            serverVersion = serverVersion,
            clock = clock,
        ),
        tasks = tasks,
        token = token,
        loopbackToken = loopbackToken,
        device = device,
        history = history,
        exports = records,
        onError = onError,
        router = endpoints,
    )

    val activity get() = bridge.activity
    val state get() = bridge.state
    val boundPort: Int get() = bridge.boundPort

    fun handle(req: HttpRequest): HttpResponse = bridge.handle(req)

    /** 开始监听；端口被占等绑定失败时抛出异常。 */
    fun start() = bridge.start()

    fun stop() = bridge.stop()

    fun rebind(port: Int) = bridge.rebind(port)

    companion object {
        /** 设置页与 USB 脚本要显示「绑在哪个地址」时用；逻辑在 [RemoteBridge.bindAddress]。 */
        fun bindAddress(allowAllInterfaces: Boolean, port: Int): InetSocketAddress =
            RemoteBridge.bindAddress(if (allowAllInterfaces) ListenScope.PrivateNetworks else ListenScope.LoopbackOnly, port)
    }
}

/** 设备主屏能力：AAU 只要协议 JSON，凭据与失败封锁都留在 [DeviceAccess] 里。 */
fun DeviceAccess.toRemoteDevicePort(): RemoteDevicePort = object : RemoteDevicePort {
    override suspend fun state() = this@toRemoteDevicePort.state().toJson()

    override suspend fun wake() = this@toRemoteDevicePort.wake().toJson()

    override suspend fun unlock() = this@toRemoteDevicePort.unlock().toJson()
}
