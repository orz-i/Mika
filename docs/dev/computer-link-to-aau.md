# 「连接电脑」功能集 → AAU 沉淀方案

这份文档分析 Mika 的「连接电脑」功能集到底是什么、它的价值在哪、哪些部分能直接沉进 AAU、哪些必须抽象、怎么分期落地。目标读者是准备动手改 AAU 的人。

结论先说：

1. **它不是投屏，是任务委派 API。** 电脑（或电脑上的 AI Agent）只提交「要办成什么」，手机自己决定怎么做。这条语义与 AAU 的「提供手机动作能力」正交，不重复。
2. **可沉淀的资产是骨架，不是业务。** HTTP/MCP/鉴权/USB 引导/出站网关这一整套约有 3000 行、与 Mika 领域无关的代码，可以做成零 Android 依赖的纯 JVM 模块 `aau-remote`；任务编排（Agent、Run、工具）留在接入方 App。
3. **协议不要动。** 保持 API v1 + MCP 不变，`mika` CLI、`mika-feishu` 就能零成本驱动 AAU 接入方——这既是验证手段，也是现成的生态。

---

## 1 功能集全景

「连接电脑」由四个入口、三种传输、两套协议、一个任务模型组成。它解决的问题是：**手机之外的东西，怎么把活交给手机，并拿回结果**。

```
  电脑侧入口                     传输                    手机侧（App 进程）
  ─────────────────────────────────────────────────────────────────────
  Skill (SKILL.md + 脚本) ─┐
  CLI (mika 命令)         ─┼─ USB: adb forward ─────┐
  MCP 客户端               ─┤   (回环 127.0.0.1)     │
  裸 HTTP 调用             ─┘                        ├─ RemoteHost (MiniHttpServer + RemoteAccess)
                                                     │      │
                       Wi-Fi / 私有 VPN (明文 HTTP) ──┤      ├─→ /mcp        McpRpc → McpTaskTools
                                                     │      └─→ /api/v1     RemoteTaskApi
  自托管网关 (HTTPS)  ←── 出站 WSS ───────────────────┘            ↓
  tools/relay (Go)                                          RemoteTaskService（唯一业务层）
                                                                   ↓
                                                            RemoteTasks（任务端口，按 owner 隔离）
                                                                   ↓
                                                    宿主 Agent：TaskService / TaskCenter / Run
```

| 层 | 内容 | 归属 |
|---|---|---|
| 入口 | Skill 脚本、`mika` CLI、MCP 服务器、裸 HTTP | 电脑侧（`tools/mika`、`tools/mika-feishu`） |
| 传输 | USB（adb forward 回环）、局域网/私有 VPN（明文 HTTP + 令牌）、自托管网关（出站 WSS + TLS） | 手机侧 + `tools/relay` |
| 协议 | MCP Streamable HTTP（双代）、任务 API v1（REST + JSON） | 手机侧 `core/mcp`、`core/remote` |
| 业务 | 任务提交/查询/等待/回答/插话/取消/列表、附件上下行、运行记录导出、历史会话读取、设备唤醒解锁 | 手机侧 `core/remote` + 宿主 |
| 权限 | 令牌、来源地址白名单、失败冷却、owner 隔离、能力开关（历史/设备/导出） | 手机侧 `core/remote` |
| 引导 | USB 一键脚本、连接信息文件、`mika connect` 自动转发、网关配对 | 手机侧 + 电脑侧 |
| 观测 | 设置页连接状态与最近请求、任务台 `mika ui`、运行事件导出 | 手机侧 `app/ui` + `tools/mika/scripts/mika_desk` |

**没有 Mika 服务器的含义要说清**：局域网/USB 是点对点；公网只有用户自己部署的网关，网关只做透明转发，令牌由手机校验。这条设计直接决定了 AAU 的沉淀范围——不需要多租户，不需要账号体系。

---

## 2 现有代码地图

| 文件 | 行数 | 职责 |
|---|---|---|
| `core/remote/HttpServer.kt` | 247 | `MiniHttpServer`：HTTP/1.1、每连接一线程、16 连接、16 KiB 头 / 256 KiB 正文、chunked 与 `Expect: 100-continue`、双端地址 `accept` 过滤 |
| `core/remote/RemoteAccess.kt` | 117 | 鉴权门口：Origin 拒绝、常量时间比令牌、按来源失败冷却、双令牌（网络/回环）、私有网段判定 |
| `core/remote/RemoteHost.kt` | 62 | 组合入口：绑回环或全网卡、把 `handle` 串起来、暴露 `RemoteActivity` |
| `core/remote/RemoteEndpoints.kt` | 49 | 每次请求按 owner 构造 `RemoteTaskService`，再分派到 `/mcp` 或 `/api/v1` |
| `core/remote/RemoteTaskApi.kt` | 285 | REST 适配器：任务、wait/answer/steer/cancel、附件、导出、历史 |
| `core/remote/RemoteTaskService.kt` | 143 | 业务用例，HTTP 与 MCP 共用；错误类型 → 状态码 |
| `core/remote/RemoteTasks.kt` | 155 | 任务端口接口 + 状态对外的 9 态映射 + owner 摘要 |
| `core/remote/RemoteTaskFiles.kt` | 134 | 附件分块上传/按 offset 下载 |
| `core/remote/RunRecordExport.kt` | 104 | 运行记录脱敏导出（JSON / JSONL） |
| `core/remote/RelayClient.kt` | 185 | 出站 WSS 客户端、v2 帧协议、8 并发、请求编号去重、退避重连 |
| `core/remote/RelayGateway.kt` | 77 | 网关地址校验与配对/解绑 |
| `core/mcp/McpRpc.kt` | 246 | JSON-RPC 双代协议（modern/legacy）、头一致性校验、无状态无 SSE |
| `core/mcp/McpTaskTools.kt` | 206 | 7 个任务工具的定义与调用适配 |
| `core/mcp/McpDeviceTools.kt` | 40 | 3 个设备工具（state/wake/unlock） |
| `core/mcp/McpServer.kt` | 99 | 本机传输桩：`McpToolSession` + `McpTaskHost`（**Mika 专用，不搬**） |
| `core/mcp/McpUsb.kt` | 93 | USB 连接信息文件 + `connect-computer.sh` 的生成 |
| `core/device/DeviceAccess.kt` | 173 | 主屏唤醒/解锁状态机（凭据只在端侧、失败即封） |
| `app/mcp/McpRuntime.kt` | 254 | 进程级单例：开关、热重绑、脚本发布、网关装配 |
| `app/mcp/McpService.kt` | 88 | 前台服务 + 常驻通知（关了就地退出） |
| `app/mcp/McpModes.kt` | 39 | 三个开关 → 监听范围 |
| `app/settings/McpSettings.kt` | 99 | 开关/端口/历史开关 + 加密令牌 |
| `app/ui/McpScreen.kt` | 370 | 设置页：客户端 × 传输 两维引导 + 高级区 |
| `tools/mika/scripts/mika_cli.py` | 775 | CLI：devices/connect/doctor/ui/task/notification-doctor |
| `tools/mika/SKILL.md` | 99 | Skill 契约：连接步骤、等待规则、退出码语义 |
| `tools/relay/*.go` | 1842 | 自托管网关（console/gateway/store/web）+ Caddy/Nginx 两种部署 |

合计约 6000 行（手机侧 ~3300，电脑侧 ~2700）。

---

## 3 七条不可丢的契约

这些是踩过坑沉淀下来的硬约束，沉进 AAU 时逐条对齐，别重新发明。

### 3.1 鉴权顺序固定

`Origin` 头存在 → 403（浏览器的请求总带，挡 DNS rebinding）；同一来源认证失败 ≥5 次/60s → 429 并锁 60s；令牌不符 → 401。**冷却期内正确令牌也不放行**——放行等于告诉猜的人"这回猜对了"。失败表按 `source`（网关转发时是网关看到的调用方，IPv4 或 IPv6 /64）分开记，上限 256 条。

### 3.2 令牌比较用摘要，归属用摘要

```kotlin
fun same(a: String, b: String): Boolean {
    val d = MessageDigest.getInstance("SHA-256")
    return MessageDigest.isEqual(d.digest(a.toByteArray()), d.digest(b.toByteArray()))
}
```
先各自摘要成等长再逐字节比，耗时不随"前面对上了几个字符"变化。owner 也是 `SHA-256(token)` 的十六进制——令牌原文不进任务层、不进日志、不进导出。

### 3.3 双令牌，两个作用域

| 令牌 | 认谁 | 用途 |
|---|---|---|
| 网络令牌（`McpSettings.token()`） | 任何来源 | 局域网、网关远程 |
| 回环令牌（`McpUsbSettings.token()`） | **仅** `remote.isLoopbackAddress` | USB（`adb forward` 进来就是回环） |

两个都比，不因对上第一个就少比一次。USB 令牌不能走网关（网关请求的来源是伪造的非回环地址），这是设计出来的，不是遗漏。

### 3.4 监听范围由开关推导，不是开关直接绑

`Listen = Off | LoopbackOnly | PrivateNetworks`。只有 USB 开 → 绑 `127.0.0.1`（写死 IPv4，因为 `adb forward` 连的是 127.0.0.1，而 Android 上 `getLoopbackAddress()` 可能给 `::1`）。局域网开 → 绑全网卡，但 `accept` 回调要求**两端都是私有地址**：127/8、10/8、172.16/12、192.168/16、100.64/10、169.254/16、IPv6 `fc00::/7` 与 `fe80::/10`。公网地址当场关连接，不回任何字节。

### 3.5 状态机只有 9 个对外值

`starting / running / queued / paused / waiting_for_answer / waiting_on_phone / done / failed / cancelled`。

映射的关键是**"要不要人"**：`WaitingConfirmation`、`WaitingTakeover`、`reason.needsYou` 的 `Held` 全部落到 `waiting_on_phone`，并附一句固定的话——"这一步需要用户在手机上处理，远端回答不了"。`waiting_on_phone` 的任务拒绝 `answer` 和 `steer`（409）。付款、确认、接管永远只能由人在手机上完成，这条是产品底线，也是安全边界。

### 3.6 限额是协议的一部分

| 项 | 值 | 为什么 |
|---|---|---|
| 端口 | 1024–65535，默认 8765 | 8765 会被系统服务占用（issue #392） |
| `goal` / `steer` | 4000 字符 | |
| `answer` | 2000 字符 | |
| `list` | 1–50，默认 10 | |
| `wait` | 1–50 秒，默认 30 | 要压在 gateway 65s 超时之内 |
| 本地 HTTP | 头 16 KiB / 正文 256 KiB / 16 连接 / 30s 读超时 | 分块上传单块上限由此决定（≈190 KB 原始字节） |
| 网关 | 正文 1 MiB / 单连接 8 并发 / 8 KiB query & header / 65s 超时 | |
| 令牌长度 | ≥32 字节，base64url 无填充 | |

### 3.7 USB 引导：脚本不带秘密，靠开机计数认新鲜

连接信息写进 `getExternalFilesDir/mcp-usb.json`（Android 11 起别的 App 读不到，shell 能读），内容含 `api_version`、`api_url`、`transport`、`url`、`headers.Authorization`、`port`、`forward`、`boot`。

`connect-computer.sh` 里**没有任何口令或令牌**，可以一直放在手机上：

1. `id -u` 不是 2000 就退出（只接受 shell 身份）；
2. 开：先跑手机助手脚本（没有就提示"手机操作还没准备好"），记下 `settings get global boot_count`，`am start-foreground-service` 起宿主服务，最多 15 次 ×1s 等 `mcp-usb.json` 里出现本次 `boot`；
3. 关：起同一服务带 off 动作，等文件被删（令牌随之作废）。

挡普通 App 的不是脚本，是清单里的 `INTERACT_ACROSS_USERS_FULL`——shell 有，普通 App 拿不到，所以只有 `adb shell` 起得动这个前台服务。

---

## 4 与 AAU 的对撞：能搬 / 要抽象 / 不能搬

### 4.1 能直接搬（零领域耦合）

`MiniHttpServer`、`HttpRequest`/`HttpResponse`、`RemoteAccess`、`RemoteHost`、`RemoteEndpoints` 的路由壳、`McpRpc`、`McpUsb`、`RelayClient`、`RelayGateway`、`RemoteOwnership`、`RemoteStatus` 的映射表、`RemoteTaskError` 的错误分类。

这批代码**只依赖 JDK + kotlinx.serialization + okhttp**，不碰任何 Mika 类型（除了 `RunSpec`/`TaskState`，见下）。

### 4.2 必须抽象成端口的（Mika 领域类型出镜处）

| Mika 类型 | 出现在 | 端口化方式 |
|---|---|---|
| `RemoteTasks`（含 `TaskState`） | 业务层、状态映射 | 已是接口，只需把 `TaskState` 换成 AAU 自己的枚举 |
| `RemoteTaskFiles` | 附件 | 已是接口，原样保留 |
| `DeviceAccess` | 设备工具 | 已是 `port + store` 双接口，AAU 直接用 `DeviceUnlockInput` 实现 |
| `RemoteHistory` / `RunExportSource` | 历史与导出 | 已是接口（`RunExportLookup` 三态），保留 |
| `McpServer` / `McpToolSession` | MCP 本机桩 | **不搬**：它把 `ToolDispatcher`/`ActionExecutor` 拉进来，是 Mika Agent 的直连通道，与"电脑连接"是两件事 |

### 4.3 不能搬

- `McpRuntime`（App 级单例 + 前台服务 + 三个设置对象）：改写为宿主持有的 `RemoteBridge`。
- `McpScreen`（Compose 设置页）：AAU 是库，无 UI；只保留 `ComputerAccessUi` 这种**纯数据快照**给宿主渲染。
- `TaskService`/`TaskCenter`/`AgentLoop`：宿主 Agent，AAU 不该有。
- `mika ui` 任务台：可选移植，属电脑侧工具。

### 4.4 契合点（AAU 已有的、正好能接上的）

| AAU 现有 | 对应 |
|---|---|
| `aau-common/.../shell/DeviceUnlockInput.kt` | `DeviceAccessPort` 的 `wake/dismiss/reveal/enter` 实现，连"严格只对 systemui UID 注入"这条都一致 |
| `aau-setup/.../PrivilegeManager.kt` + `WirelessAdbLauncher.kt` | USB/无线调试引导可以复用，比 Mika 的纯 adb 方案更完整 |
| `gradle/libs.versions.toml` 已有 `kotlin-jvm`、`kotlin-serialization`、`okhttp`、`coroutines` | `aau-remote` 可以直接做纯 JVM 模块，不需要新增任何依赖 |
| 模块化 + 单向依赖纪律 | 与 Mika `:core` 的纯 Kotlin 做法同构，测试策略可照搬 |

### 4.5 一个必须明确的架构判断

**`aau-remote` 不进 `aau-server`（UID 2000 守护进程），跑在接入方 App 进程。**

理由：任务编排要访问宿主的模型、工具、UI 状态，放进守护进程会让 `aau-server` 变成 agent runtime，违背它"特权原语服务"的定位；而且令牌与加密存储本来就在宿主。守护进程保持只提供动作原语。

---

## 5 目标设计：`aau-remote`

### 5.1 模块与依赖方向

```
aau-remote (纯 JVM: kotlin-jvm + serialization + okhttp)
  ├─ com.agent.use.remote.http      MiniHttpServer / HttpRequest / HttpResponse / RemoteAccess
  ├─ com.agent.use.remote.api       RemoteTaskApi / RemoteEndpoints / TaskApiError
  ├─ com.agent.use.remote.mcp       McpRpc / McpToolCatalog
  ├─ com.agent.use.remote.usb       UsbBridge（信息文件 + 脚本生成）
  ├─ com.agent.use.remote.relay     RelayClient / RelayGateway / RelayFrame
  └─ com.agent.use.remote.spi       RemoteTaskPort / RemoteDevicePort / RemoteHistoryPort / RemoteExportPort
                                    + RemoteTask / RemoteStatus / RemoteError（AAU 自己的模型）

依赖：aau-remote ← aau-client（可选，做设备端口的现成实现）
      aau-remote 不依赖 aau-common / aau-server / 任何 Android 类
```

### 5.2 SPI（这是沉淀的核心产物）

```kotlin
package com.agent.use.remote.spi

/** 宿主 Agent 的一个任务。state 为 null 表示已受理、还没登记上。 */
data class RemoteTask(
    val id: String,
    val conversation: String,
    val goal: String,
    val createdAtMs: Long,
    val state: RemoteTaskState?,        // AAU: 9 态枚举，替代 Mika 的 TaskState
    val steers: List<RemoteSteer> = emptyList(),
    val runId: String? = null,
    val files: List<RemoteFile> = emptyList(),
)

enum class RemoteTaskState {
    Starting, Running, Queued, Paused, WaitingForAnswer, WaitingOnPhone, Done, Failed, Cancelled
}

/** 每次已认证请求拿到自己的命名空间；实现方负责按 owner 隔离。 */
interface RemoteTaskPort {
    val fileAccess: RemoteFileAccess? get() = null
    fun scope(owner: String): RemoteTaskPort = this
    fun start(goal: String, conversation: String?, requestId: String?, attachments: List<String>): StartOutcome
    fun get(id: String): RemoteTask?
    fun list(limit: Int): List<RemoteTask>
    fun page(limit: Int, before: String?): RemoteTaskPage = RemoteTaskPage(list(limit))
    fun answer(id: String, text: String): Boolean      // 只在 waiting_for_answer 时被调用
    fun steer(id: String, text: String): String?       // 返回 messageId，拒绝为 null
    fun cancel(id: String): Boolean
}

interface RemoteDevicePort { suspend fun state(): DeviceState; suspend fun wake(): Boolean; suspend fun unlock(): Boolean }
interface RemoteHistoryPort { /* conversations / turns / run / body，全部带 offset+limit */ }
interface RemoteExportPort { fun load(id: String): ExportLookup }   // Found / Missing / Gone
```

### 5.3 门面

```kotlin
class RemoteBridge(
    private val config: RemoteConfig,        // port, listen: Off|Loopback|PrivateNetworks, token, loopbackToken
    private val tasks: RemoteTaskPort,
    private val device: RemoteDevicePort? = null,
    private val history: RemoteHistoryPort? = null,
    private val exports: RemoteExportPort? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
) {
    val state: StateFlow<BridgeState>        // Off / Listening(port) / Failed(msg)
    val activity: StateFlow<RemoteActivity>  // recentClients / lastRequestAtMs
    fun start()                              // 绑定失败抛异常，由宿主显示"端口被占"
    fun stop()
    fun rebind(port: Int)                    // 热重绑 + 通知宿主重发连接信息
    fun handle(req: HttpRequest): HttpResponse   // 供网关适配器复用
}
```

`RemoteConfig` 里**不放**任何 Android 对象：令牌通过 `() -> String?` 拉取，宿主自己决定从哪读（Keystore、EncryptedSharedPreferences 都行）。

### 5.4 协议保持不变

- 路径：`/mcp`、`/api/v1`、`/api/v1/...`
- MCP：modern `2026-07-28`（`_meta` + `MCP-Protocol-Version`/`Mcp-Method`/`Mcp-Name` 三头一致性，`Mcp-Name` 支持 `=?base64?…?=` 编码）；legacy `2025-11-25` / `2025-06-18` / `2025-03-26`（`initialize` 握手）；无 SSE、无 session、`tools/list` 与 `server/discover` 带 `ttlMs: 300000` + `cacheScope: private`
- 工具名：`mika_*` 保持不变（见 5.5 的取舍）
- API v1 端点集合与 `capabilities` 数组保持不变，`limits` 字段照旧

**为什么保持 `mika_*` 前缀**：省掉一次协议分叉，`mika` CLI / `mika-feishu` / 已有 Skill 都能直接驱动 AAU 接入方。若确实要去掉品牌，做一次 `tool_prefix` 配置项即可，别在协议层分家。

### 5.5 宿主必须自己实现的三件事

| 事项 | 说明 |
|---|---|
| 前台服务 + 常驻通知 | `aau-remote` 不碰 `Service`；宿主起服务、拿 `ForegroundServiceType`、放"关闭"动作 |
| 设置页 | 用 `ComputerAccessUi` 这种纯数据快照渲染，两维引导（客户端 × 传输）照旧 |
| 令牌存储 | `aau-remote` 只要字符串；宿主用 Keystore 加密落盘，换令牌时旧的当场失效 |

USB 引导脚本的 `component` 必须参数化（接入方包名不定）：`UsbBridge.script(component = "${pkg}/${serviceClass}")`，这一点 Mika 的实现已经支持。

---

## 6 迁移映射

| Mika | AAU | 改动量 | 备注 |
|---|---|---|---|
| `MiniHttpServer` + `HttpRequest/Response` | `aau-remote/http/` | 原样 | 可考虑换线程池，见 §7 |
| `RemoteAccess` | `aau-remote/http/AccessGate` | 原样 | 双令牌回调保留 |
| `RemoteHost` | `aau-remote/RemoteBridge` | 小改 | 装配 + `rebind` |
| `RemoteEndpoints` | `aau-remote/api/EndpointRouter` | 小改 | owner 作用域改 AAU 端口 |
| `RemoteTaskApi` | `aau-remote/api/TaskApi` | 小改 | `RemoteTaskError` → `RemoteError` |
| `RemoteTaskService` | `aau-remote/api/TaskUseCases` | 原样 | 纯逻辑，直接搬 |
| `RemoteTasks` | `aau-remote/spi/RemoteTaskPort` | 换 `TaskState` | 状态映射表照抄 |
| `RemoteTaskFiles` | `aau-remote/spi/RemoteFileAccess` | 原样 | |
| `RunRecordExport` | `aau-remote/api/RunExport` | 原样 | 脱敏字段保留 |
| `McpRpc` | `aau-remote/mcp/McpRpc` | 原样 | 版本常量与错误码照抄 |
| `McpTaskTools` / `McpDeviceTools` | `aau-remote/mcp/ToolCatalog` | 小改 | 端口注入 |
| `McpUsb` | `aau-remote/usb/UsbBridge` | 原样 | `component` 参数化 |
| `RelayClient` / `RelayGateway` | `aau-remote/relay/` | 原样 | 协议改名 `aau-relay.v1`，帧格式不变，网关侧直接取代 `mika-relay.v2`（旧版 426 拒绝） |
| `DeviceAccess` | `aau-remote/spi/RemoteDevicePort` | 只留接口 | 实现放 `aau-client`，用 `DeviceUnlockInput` |
| `McpRuntime` | 宿主 | 重写 | 拆成 `RemoteBridge` + 宿主服务 |
| `McpService` | 宿主 | 重写 | 参考 Mika 的启动/退出条件 |
| `McpSettings` | 宿主 | 重写 | 只需端口/开关/令牌三个值 |
| `McpScreen` | 宿主 | 参考 | 交互结构可复用，代码不行 |
| `mika_cli.py` / `SKILL.md` | 不在 AAU | 无 | 电脑侧；协议不变则直接可用 |
| `tools/relay` | 可选独立仓 | 无 | Go 网关不必进 AAU 的 Gradle 构建 |

估算：`aau-remote` 新代码约 2600–3000 行（含测试），其中 80% 是搬迁。

---

## 7 分期

**P0 骨架（1 周量级）**
建 `aau-remote` 模块（`kotlin-jvm` + `kotlin-serialization` + `okhttp`），搬 http/access/host/api/mcp/usb 六块，`spi` 全部落成接口，写一个内存版 `RemoteTaskPort` 假实现。验收：JVM 单测跑通 `/api/v1` 全端点 + MCP 双代工具调用 + 鉴权 6 种拒绝路径。

**P1 端口适配与设备能力（1 周）**
`aau-client` 里加 `AauRemoteDeviceAdapter`（用 `DeviceUnlockInput`）；`aau-setup` 补 USB 引导的 `component` 注入。验收：真机 USB 一次 `adb shell sh` 起服务，`mika doctor` 通过，`mika task run` 能派任务。

**P2 网关（3–5 天）**
搬 relay 客户端；网关侧把 `mika-relay.v2` 直接换成通用 `aau-relay.v1`（帧格式与新协议名一致，不做双协议并存，旧客户端由网关 426 拒绝并提示升级）。验收：跨语言测试——真 Go 网关 + 真 Kotlin 隧道 + 真 Python CLI，覆盖令牌轮换、USB 令牌经网关被拒、解除配对。

**P3 任务台与文档（可选）**
`mika ui` 的 `mika_desk` 是纯前端 + 本地 HTTP 壳，改品牌即可。验收：浏览器派任务、看进程、回答追问、断线恢复按 request_id 核对。

---

## 8 风险与取舍

1. **每连接一线程（已修）**。本地 16 连接够用，但 `wait` 最长 50s，8 个并发等待就占掉一半线程。`aau-remote` 的 `MiniHttpServer` 已换成 `Executors.newCachedThreadPool` + `Semaphore(16)` 限流，超限当场 503（不排队），对外接口未变。
2. **明文 HTTP**。只在回环与私有网段可接受。AAU 若要在企业环境用，得加 TLS 或强制走网关；不要悄悄放宽 `privateAddress`。
3. **令牌模型与 AAU 的 UID 校验叠加**。`aau-server` 校验调用方 UID 与授权包名，`aau-remote` 校验 Bearer 令牌。两者不冲突但要写清顺序：令牌在接入方进程先过，UID 校验在守护进程再过——别让某一层以为另一层做了。
4. **历史与导出是隐私面**。默认关、开关独立、授权范围包含所有对话与运行记录正文。沉淀时保留这个默认值，不要因为"方便调试"默认打开。
5. **`outcome_unknown` 语义别简化**。写请求超时/5xx 不能自动重发，要靠预先分配的 `request_id` 去核对。这是分布式不确定性的正确处理，很多实现会在这里犯错。
6. **MCP modern 版本会漂**。`MODERN_VERSIONS` 目前只有 `2026-07-28`，将来必然增加；把版本列表做成配置常量的单一来源，别散落在测试里。
7. **别把 Agent 塞进守护进程**（§4.5 已述）。这是最容易走偏的一步，走偏就失去模块边界。

---

## 9 沉淀后 AAU 得到什么

一句话：**AAU 从"能被本地代码调用的能力库"变成"能被任何外部编排器驱动的手机运行时"。**

- 对外多了一条稳定协议（API v1 + MCP），接入方不必理解虚拟屏、a11y、注入；
- 已有的 `DeviceUnlockInput`、`PrivilegeManager`、`CustodyEngine` 从"内部实现"升级为"可远程调度的能力"；
- `mika` CLI / Skill / 飞书连接器成为现成的参考客户端，验证成本几乎为零；
- 与 Mika 的分工变清晰：AAU 管"怎么在手机上把事做成"，接入方的 Agent 管"要做什么、做到什么程度"。

---

## 10 落地记录（P0 + P2 已完成）

代码已按本文方案落地，`aau/aau-remote` 为新增模块（17 个源文件 + 2 个测试文件，约 3100 行）。

**AAU 侧**（`aau/aau-remote/src/main/java/com/agent/use/remote/`）

| 目录 | 内容 |
| --- | --- |
| `spi/` | `Model.kt`（`RemoteTask`/`RemoteTaskDetail`/`RemoteTaskState` 九态/`RemoteTaskPort`/`StartOutcome`/`RemoteOwnership`/`RemoteStatus`）、`Files.kt`（`RemoteFile`/`RemoteFileAccess`/`RemoteUploads`/`PendingUploads`）、`Ports.kt`（`RemoteDevicePort`/`RemoteHistoryPort`/`RemoteExportPort`/`ExportLookup`）、`Errors.kt`（`RemoteTaskError` 五种码） |
| `http/` | `Http.kt`（线程池版 `MiniHttpServer`）、`AccessGate.kt`（Origin 屏蔽 → 失败锁定 → 令牌） |
| `api/` | `TaskUseCases.kt`（纯逻辑 + 限额常量）、`TaskApi.kt`（REST v1 全端点）、`EndpointRouter.kt`（owner 作用域 + `RemoteActivity`） |
| `mcp/` | `McpRpc.kt`（双代协议）、`ToolCatalog.kt`（7 任务 + 3 设备工具） |
| `relay/` | `RelayClient.kt`（`aau-relay.v1`）、`RelayGateway.kt` |
| `usb/` | `UsbBridge.kt`（`mcp-usb.json` + `connect-computer.sh`，component 参数化） |
| 根 | `RemoteBridge.kt`（门面：`start`/`stop`/`rebind`/`handle`）、`RemoteBrand.kt`（`appName`/`toolPrefix`） |

**Mika 侧**：`core/src/main/kotlin/.../core/remote/` 下原有 15 个文件全部降级为 `typealias` 指向 AAU 类型（调用点零改动），仅保留真正有 Mika 业务的 `RunRecordExport`（`RunExportRecord` 渲染）；`RemoteHost.kt` 重写成装配壳；新增 `MikaTaskStates.kt` 做 `TaskState` → `RemoteTaskState` 映射。`app/` 侧 `RemoteTaskHub`、`RemoteTaskReceipts`、`RemoteRunExport`、`McpRuntime` 改到 AAU 端口上。

**协议保持不变的硬约定**（保证 `mika` CLI / Skill / 飞书连接器零成本驱动）：路径 `/mcp`、`/api/v1/*`；MCP 工具名仍为 `mika_*`（由 `RemoteBrand.toolPrefix` 生成，可改）；客户端头 `x-mika-client`；`capabilities`/`limits` 字段名不变。唯一变更是 relay 子协议 `mika-relay.v2` → `aau-relay.v1`，帧格式未动。

**验证**

- `:aau-remote:test` 25 项 0 失败（2 项因本机透明代理污染 loopback 用 `Assume` 跳过）。
- `:core:relayTest` 通过：真 Go 网关（`relayGatewayBinary` 现编）+ 真 Kotlin `RelayClient` 以 `aau-relay.v1` 握手成功。
- `tools/relay` 的 `go test ./...` 通过。
- `:core:test` 全量 114 项（remote+mcp 子集）3 失败、`:app:testDebugUnitTest` 全量 1930 项 63 失败——已用 `git worktree` 在 HEAD 基线跑同一批用例，失败项与失败名字**完全一致**（`HttpServerTest.keepsServingWhenConnectionOptionsDoNotIncludeClose`、`HttpServerTest.rejectsUnauthenticatedOverflowBeforeTheHandlerAndKeepsServing`、`McpHostHttpTest.chunkedBodyAfterExpectContinue`），判定为环境既有问题（本机 AdGuard 类透明代理往 loopback 注入阻塞页），非本次引入。

**未做**：任务台 UI 壳（`mika ui` / `mika_desk`）按决策不迁；`McpRuntime`/`McpService`/`McpSettings`/`McpScreen` 作为宿主层留在 Mika。

