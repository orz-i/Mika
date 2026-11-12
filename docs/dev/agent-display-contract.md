# AgentDisplayKit 接口与数据契约设计规范 (agent-display-common)

本文档由 `@contract` 负责产出，旨在为 `agent-display-server` (运行在 Shell UID 2000 的守护进程)、`agent-display-client` (接入方 Android Library SDK) 以及 `@verifier` 提供全套跨进程通信契约、数据模型与上层开发者外观定义。

---

## 一、背景与设计目标

原有 Mika 系统中的 `IMikaShell.aidl` 承担了虚拟屏创建、输入分发、音频控制等底层能力，但同时也掺杂了大量 Mika 专属的业务特性（例如专为 Mika 内部 WebView 穿透设计的 `openDevtoolsSocket`、限定为闹钟和日历等系统动作且依赖 `asMika` PendingIntent 的 `startSystemAction`、以及守护进程拉起 Mika 的 `setRestoreWish`）。

**重构与解耦目标：**
1. **纯正的 Agent 虚拟屏原语**：剥离所有与特定 App 耦合的业务逻辑，仅暴露标准通用的虚拟屏、任务调度、输入控制与环境隔离接口；
2. **机制与策略分离**：
   - **服务端 (Server)**：专注提供 Shell UID 2000 级特权机制（VirtualDisplay Flags 降级、全屏与 IME 策略、InputManagerGlobal 注入、AppOps 音频控制、底层任务栈变动感知）；
   - **客户端 (Client)**：专注实现高性能图像流管线（RGBA 双缓冲直读、≤10fps 动态限速丢帧队列）、主屏防扰冲突避让策略与面向对象的生命周期管理；
   - **契约层 (Common)**：以 AIDL、Parcelable 及干净的 Kotlin API Facade 作为双方坚实的协议基座。
3. **高内聚的开发者外观**：为第三方 Agent 开发者提供 `AgentDisplayManager` 和 `AgentVirtualDisplay`，以纯 Kotlin 现代语言习惯（Flow、Use 块、密封类等）使用虚拟屏。

---

## 二、AIDL 规范：IAgentShell.aidl 详细比对与定义

`IAgentShell.aidl` 位于包路径 `com.agent.display.common`。

### 2.1 剔除与保留接口对比矩阵

| 原 IMikaShell 事务号与接口 | 状态 | 在 IAgentShell.aidl 中的设计与替代方案 | 理由 |
| :--- | :---: | :--- | :--- |
| `1: createDisplay(Surface, width, height, dpi)` | **重构** | `1: createDisplay(in Surface surface, in DisplayConfig config)` | 将零散的分辨率与 DPI 升级为 Parcelable 契约 `DisplayConfig`，支持未来扩展 flags 与 name。 |
| `2: releaseDisplay(int displayId)` | **保留** | `2: releaseDisplay(int displayId)` | 基础屏幕释放原语。 |
| `3: startApp(int displayId, String packageName)` | **保留** | `5: startApp(int displayId, String packageName)` | 启动指定应用，保持主屏冲突防扰。 |
| `4: topTaskId(int displayId)` | **保留** | `12: topTaskId(int displayId)` | 任务栈查询原语。 |
| `5: moveTask(int taskId, int displayId)` | **保留** | `19: moveTask(int taskId, int displayId)` | 跨屏任务迁移（主屏任务拉回虚拟屏或沉底回捞）。 |
| `6: injectKey(int displayId, int keyCode)` | **保留** | `25: injectKey(int displayId, int keyCode)` | 基础按键分发（受白名单保护）。 |
| `7: injectTap(int displayId, int x, int y)` | **保留** | `23: injectTap(int displayId, int x, int y)` | 基础触摸单击。 |
| `8: setAudioMuted(String packageName, boolean muted)` | **保留** | `28: setAudioMuted(String packageName, boolean muted)` | AppOps PLAY_AUDIO 静音控制。 |
| `9: restartApp(int displayId, String packageName)` | **保留** | `6: restartApp(int displayId, String packageName)` | 冷启动保护逻辑。 |
| `10-11: getAudioMode / setAudioMode` | **保留** | `29-30: getAudioMode / setAudioMode` | 音频模式读写。 |
| `12-13: displayOfTask / packageOfTask` | **保留** | `14-15: packageOfTask / displayOfTask` | 任务所有权查询。 |
| `14-15: setClipText / restoreClip` | **保留** | `34-35: setClipText / restoreClip` | 敏感剪贴板与静默写入（SUPPRESS_CLIPBOARD_OVERLAY）。 |
| `17: startView(int, action, uri, packageName)` | **保留** | `9: startView(int, action, uri, packageName)` | 安全网页 / DeepLink 跳转。 |
| `18: displayOfPackage(String packageName)` | **保留** | `16: displayOfPackage(String packageName)` | 查重与冲突避让。 |
| `19-20: savedClipText / restoreClipText` | **保留** | `36-37: savedClipText / restoreClipText` | 剪贴板条件还原与备份。 |
| `21: identity()` | **保留** | `47: identity()` | 返回 [uid, pid] 身份校验。 |
| `22: releaseAll()` | **保留** | `3: releaseAll()` | 全局会话清理与还原。 |
| `23: openDevtoolsSocket(String name)` | **剔除** | **彻底删除** | Mika 专用 WebView DevTools 抽象套接字穿透，与通用虚拟屏无任何关系。 |
| `24: runSystemCommand(int displayId, String command)` | **保留** | `49: runSystemCommand(int displayId, String command)` | 注入 `AGENT_DISPLAY_ID` 环境变量执行命令。 |
| `25: topActivity(int displayId)` | **保留** | `13: topActivity(int displayId)` | 获取顶层 Activity 类名。 |
| `26: injectCtrlKey(int displayId, int keyCode)` | **保留** | `26: injectCtrlKey(int displayId, int keyCode)` | Ctrl+A, Ctrl+V 等快捷键投递。 |
| `27-29: listUsers / listPackages / isSystemPackage` | **保留** | `38-40: listUsers / listPackages / isSystemPackage` | 多用户与包元数据查询。 |
| `30: startShare(int displayId, String packageName, String text)` | **保留** | `10: startShare(int displayId, String packageName, String text)` | ACTION_SEND 纯文本分享启动。 |
| `31-32: watchMainScreen / mainArrivals` | **保留** | `21-22: watchMainScreen / mainArrivals` | **关键机制保留**：客户端 MainScreenConflictManager 必需的监控原语。 |
| `33: userOfTask(int taskId)` | **保留** | `18: userOfTask(int taskId)` | 查询任务所属用户。 |
| `34: injectStroke(int displayId, int[] xs, int[] ys, long[] times)` | **保留** | `24: injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times)` | 高精度单指平滑多点轨迹输入。 |
| `35: startSystemAction(int displayId, Intent intent, PendingIntent asMika)` | **重构** | `8: startIntent(int displayId, in Intent intent)` | 剥离 Mika 专属的 PendingIntent 与闹钟白名单，替换为通用的标准 Intent 启动器。 |
| `36: runCommand(String command)` | **保留** | `48: runCommand(String command)` | Shell 命令行执行。 |
| `37: setRestoreWish(int flags)` | **剔除** | **彻底删除** | Mika 专用的守护进程拉活心愿标记，通用 Agent 连接池通过 DeathRecipient 重新拉起。 |
| `38-40: appCopyUsers / startAppInUser / displayOfPackageInUser` | **保留** | `41, 7, 17` | 分身与多用户（Dual App / Work Profile）全面支持。 |
| `41-43: wakeDevice / dismissDeviceKeyguard / enterDeviceCredential` | **保留** | `42, 43, 46` | 设备准备与安全凭据输入通道。 |
| `44-47: getAudioModeInUser / setAudioMutedInUser / setAudioModeInUser / visibleOnMainScreen` | **保留** | `31, 32, 33, 20` | 多用户音频 AppOps 与主屏可见性校验。 |
| `48: finishDisplay(int displayId, String[] keep, String[] close)` | **保留** | `11: finishDisplay(int displayId, in String[] keep, in String[] close)` | 任务优雅收尾与沉底迁移。 |
| `49, 51: revealDeviceCredential / revealDeviceCredentialTap` | **保留** | `44, 45` | 锁屏滑动与回退点击。 |
| `50: injectAsciiText(int displayId, int taskId, String packageName, String text)` | **保留** | `27: injectAsciiText(int displayId, int taskId, String packageName, String text)` | 针对特定任务的前台键盘输入。 |
| `16777114: destroy()` | **保留** | `4: destroy()` | 守护进程自毁。 |

---

## 三、通用数据契约模型

### 3.1 DisplayConfig (Parcelable)
```kotlin
package com.agent.display.common

data class DisplayConfig(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val flags: Int = 0,
    val name: String = "agent-virtual-display"
) : Parcelable
```
- **核心 Flag 常量**：
  - `FLAG_PUBLIC (1)`、`FLAG_OWN_CONTENT_ONLY (8)`、`FLAG_SUPPORTS_TOUCH (64)`、`FLAG_ROTATES_WITH_CONTENT (128)`、`FLAG_DESTROY_CONTENT_ON_REMOVAL (256)`
  - Android 13+ 特有：`FLAG_TOUCH_FEEDBACK_DISABLED (8192)`、`FLAG_TRUSTED (1024)`、`FLAG_OWN_DISPLAY_GROUP (2048)`、`FLAG_ALWAYS_UNLOCKED (4096)`
  - Android 14+ 核心焦点隔离：`FLAG_OWN_FOCUS (16384)`、`FLAG_STEAL_TOP_FOCUS_DISABLED (65536)`
- **自适应方法**：
  `resolveRecommendedFlags(sdk: Int, trusted: Boolean): Int`
  当系统缺少 `ADD_TRUSTED_DISPLAY` 权限抛出 `SecurityException` 时，自动剔除依赖 trusted 的标志重新安全建屏。

### 3.2 DisplayGeometry
```kotlin
package com.agent.display.common

data class DisplayGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val rotation: Int = Surface.ROTATION_0
)
```
- 动态属性：
  - `isSideways: Boolean`：当 `rotation == 1 || rotation == 3` 时为 true；
  - `logicalWidth` / `logicalHeight`：横竖屏四分之一圈时自动对调宽高。
- 坐标投影算法：
  - `mapLogicalToBuffer(x, y)`：将 Agent 发出的逻辑点击坐标无缝转换为底层 Surface 的物理像素坐标；
  - `calculatePreviewSize(maxWidth)`：等比缩放测算，确保横竖屏切换时预览视图不会发生拉伸或变形。

### 3.3 LaunchResult (状态机枚举)
```kotlin
package com.agent.display.common

enum class LaunchResult(val code: Int, val description: String) {
    SUCCESS(0, "启动成功"),
    IN_USE_ON_MAIN(1, "目标应用正在主屏使用，已触发防打扰避让"),
    NOT_FOUND(2, "未找到目标应用入口"),
    ERROR(3, "系统底层执行异常"),
    COLD_IGNORED_WARM_STARTED(4, "冷启动已转为热启动（其他屏幕存在任务）"),
    UNRESOLVED_INTENT(5, "未找到可处理目标链接或 Intent 的组件"),
    INVALID_LINK(6, "链接或动作不安全，已被拦截"),
    IN_USE_BY_OTHER_DISPLAY(7, "应用已被其他虚拟屏占用"),
    USER_NOT_ALLOWED(8, "指定用户无权启动目标应用");

    val isSuccess: Boolean get() = this == SUCCESS || this == COLD_IGNORED_WARM_STARTED
    val isConflict: Boolean get() = this == IN_USE_ON_MAIN || this == IN_USE_BY_OTHER_DISPLAY
}
```

### 3.4 AudioMode 与音频恢复策略
- 针对 AppOps 的四个状态：`ALLOW ("allow")`、`IGNORE ("ignore")`、`DENY ("deny")`、`DEFAULT ("default")`。
- **关键底层安全保证**：`calculateRestoreMode(prior: AudioMode?): AudioMode`。Android 系统音频驱动在处理 PLAY_AUDIO 时，若写入未设置时的原始 `default` 字符串，会导致应用在某些 OEM（如 MIUI/OriginOS）上持续失去音频混音权限；因此当 `prior` 为 null 或 DEFAULT 时，还原模式**必须明确写回 "allow"**。

---

## 四、上层开发者 API 外观 (AgentDisplayManager & AgentVirtualDisplay)

### 4.1 AgentVirtualDisplay
`AgentVirtualDisplay` 实现了 `java.io.Closeable`，将底层 ImageReader、HandlerThread、旋转反向纠偏和 AIDL 调用完整封装：
1. **高性能帧捕获**：
   - 内部维护 `ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)` 双缓冲；
   - 监听器通过 `FRAME_INTERVAL_MS = 100L` 实现严格的 ≤10fps 动态限速节流；
   - 丢弃未消费旧帧，每次通过 `acquireLatestImage()` 提取最新一帧并记录纳秒时间戳；
   - 暴露 `frames: StateFlow<Bitmap?>` 供 Compose / UI 悬浮窗或调试组件响应式收集；
   - 暴露 `latestBitmap(): Bitmap?` 与 `latestPng(): ByteArray?` 供 OCR 和大模型直接消费。
2. **免转换输入注入**：
   - `injectTap(x, y)`、`injectStroke(xs, ys, times)` 直接输入应用逻辑坐标，内部由 `DisplayGeometry` 或服务端适配转屏。
   - `injectAsciiText(text)` 定向投递给该屏顶层任务，不依赖第三方软键盘。
3. **优雅收尾**：
   - `finish(keepPackages, closePackages)`：触发服务端 WindowContainerTransaction 事务将保活应用隐藏并沉入主屏任务栈底，清理发声或待销毁应用。

### 4.2 AgentDisplayManager
作为全局单例或会话管理器：
- `createVirtualDisplay(config)`：便捷生产虚拟屏实例；
- `isVisibleOnMainScreen(pkg, user)`：主屏防打扰前置探测；
- `watchMainScreen(windowMs)` / `getMainArrivals(sinceUptimeMs)`：配合客户端执行溢出任务拉回回捞；
- `linkToDeath`：自动注册服务端崩溃监听，及时清理本地资源。

---

## 五、对各模块协作的落盘汇报指南

1. **对 @server 的对齐要求**：
   - 实现 `com.agent.display.common.IAgentShell.Stub`；
   - 在 `createDisplay` 中直接读取 `DisplayConfig.aidl`，调用 `DisplayConfig.resolveRecommendedFlags(sdk, trusted)` 完成 Flags 适配与降级；
   - 保留 `watchMainScreen`, `mainArrivals`, `moveTask`, `visibleOnMainScreen` 机制支撑；
   - 执行音频恢复时严格遵循 `AudioMode.calculateRestoreMode` 规则。
2. **对 @client 的对齐要求**：
   - 在 SDK 中引入 `agent-display-common` 依赖；
   - `AgentDisplay` 图像管线直接对齐 `DisplayGeometry` 与 `DisplayFrameProcessor` 算法；
   - 在 `MainScreenConflictManager` 中使用 `IAgentShell` 提供的监控原语完成自动回捞闭环；
   - 统一使用 `LaunchResult` 与 `FinishDisplayResult` 处理调用结果。
3. **对 @verifier 的验证要点**：
   - 校验 AIDL 方法编号 1~49 的稳定性与二进制兼容性；
   - 审查 Android 12~15 下 `FLAG_STEAL_TOP_FOCUS_DISABLED`、`FLAG_OWN_DISPLAY_GROUP` 的生效情况；
   - 审查 Binder 通道在 Shizuku 与 app_process 下加载 Parcelable 的 ClassLoader 表现。
