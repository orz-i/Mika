# 微信被拉进虚拟屏：机制调研与修复建议

> 范围：`app`（DisplayCustodian / AndroidScreen）、`core`（CustodyAttribution）、`aau`（AgentShellEngine / CustodyEngine / LaunchOriginResolver）。
> 方法：静态代码走查 + 状态机推演。结论均标注文件与行号，真机日志取证步骤见第 5 节。
> 结论先行：**这不是微信特例，而是"任何在虚拟屏上运行过的 App"都会中的通用缺陷**；微信只是最常用、主屏后台常驻、最容易触发的那一个。

---

## 1. 结论（TL;DR）

"打开微信就被拉进虚拟屏"由**两层机制叠加**造成，任一层单独存在都不至于这么稳定复现：

1. **启动侧：`setLaunchDisplayId(vd)` 对已存在的 task 是"搬移"而非"新建"。**
   Android 规定同一 task 只能属于一块 display。Mika 在虚拟屏启动微信时，如果主屏已有微信 task（哪怕在后台、不可见），系统会把这个 task 整体搬到虚拟屏，用户主屏上的微信连同聊天位置一起消失。
   代码里已明确写过这个前提：`app/src/main/java/com/wangzan101/mika/cap/phone/a11y/AndroidScreen.kt:1099`
   > `// 同一个 App 不能同时在两块屏上:系统会把整个任务挪过来,等于从另一个任务手里抢走它。`

2. **托管侧：主屏上的微信被归因为"越屏"，然后 `moveTask` 搬回虚拟屏。**
   `DisplayCustodian` 的 R1"InUse（用户正在主屏使用）"对普通 App 失效，R3/R5 会把微信在主屏的任何页面跳转判成 Agent 引起的越屏，直接 `moveTask(taskId, vd)` 把**整个微信 root task** 搬到虚拟屏，且一次误判后状态机无法自愈，形成"打开就被拉走"的锁定。

一句话：**启动时把用户的微信抢进虚拟屏 → 用户再打开时托管器又把它搬回去 → 循环。**

---

## 2. 三条可触发路径

### 路径 A（最常见）：任务在虚拟屏打开微信，搬走主屏后台实例

```
用户主屏有微信 task（后台、isVisible=false）
        │
        │  Agent 调用 openApp("com.tencent.mm")
        ▼
AgentShellEngine.start()  AgentShellEngine.kt:453-475
        │  inUseOnMainScreen(pkg,user) 只统计 isVisible == true 的 task
        │  → 后台微信 isVisible=false → 判定"主屏没在用"
        ▼
ActivityOptions.setLaunchDisplayId(vd)  AgentShellEngine.kt:465
        │
        ▼
系统复用同一 task（微信 LauncherUI 是 singleTask/同 affinity）
        │  task 从 display 0 被整体移动到 display vd
        ▼
主屏微信消失，虚拟屏上出现微信（用户的聊天草稿、滚动位置都在）
```

关键证据：

| 位置 | 内容 |
| --- | --- |
| `aau/aau-common/.../AgentShellEngine.kt:587-592` | `inUseOnMainScreen` 用 `isVisible` 过滤，后台 task 不算"在用" |
| `aau/aau-common/.../AgentShellEngine.kt:453-475` | `start()` 只做这一道检查，随后 `setLaunchDisplayId` |
| `aau/aau-server/.../TaskManagerFacade.kt:150-157` | 另一条启动实现同样只查 `visibleOnMainScreen` |
| `app/.../a11y/AndroidScreen.kt:1099` | 代码自己的注释承认"系统会把整个任务挪过来" |

**补充触发点：`resumeKept`。**

任务收尾时，`AppsAfterTask.decide` 对未标记的普通 App 默认 `keep = true`（`aau/aau-common/.../AppsAfterTask.kt:61-72`，`else -> Fate(t, true, Why.Default)`）。于是微信被 `keepBehind` 移到主屏并 `setHidden(true)` 沉底（`AgentShellEngine.kt:833-849`），taskId 记进常驻 shell 进程的 `kept` 表（`AgentShellEngine.kt:109`）。

下一次任务再打开微信时：

```kotlin
// AgentShellEngine.kt:939-949
private fun resumeKept(displayId: Int, packageName: String, userId: Int): Boolean {
    val row = keptOnMain(packageName, userId).firstOrNull() ?: return false
    val id = TASK_INFO.getField("taskId").getInt(row)
    if (!moveTask(id, displayId)) return false   // ← 直接搬回虚拟屏
    ...
}
```

`keptOnMain` 只挑 `isVisible != true` 的 task（`AgentShellEngine.kt:925-937`），也就是说**用户刚用过、退到后台的微信**正好命中：下一次 Agent 打开微信，第一件事就是把它从主屏搬回虚拟屏。

### 路径 B：用户在主屏用微信，被托管器判成"越屏"搬走

```
用户在主屏点开微信 / 在微信内切页
        │
        ▼
CustodyTracker 差分发现"主屏顶部 task 的 topActivity 变化"
        │  sameTask = true，newTask = false   CustodyEngine.kt:228-236
        ▼
CustodyAttribution.attribute()  CustodyAttribution.kt:170-240
        │  R1: taskId == previousMainTopTaskId ?
        │      → 微信 task 从未被登记为"用户任务"，不成立
        │  R1: sameTask && taskId in existingMainUserTaskIds ?
        │      → 同样不成立（见下）
        │  R3: origin.uid ∈ managed(D) && 1.5s 内无桌面/通知/最近任务点击证据
        │      → 成立（微信 origin 几乎总是微信自身 uid）
        ▼
AttributionResult.Spill(MANAGED_ORIGIN_NO_USER_INTENT)
        │
        ▼
pullTaskBackToVd()  DisplayCustodian.kt:1926-2010
        │  displayOfTask == DEFAULT → runtime.moveTask(taskId, vd)
        ▼
整个微信 root task 被搬到虚拟屏
```

两个决定性缺陷：

**（1）`existingMainUserTaskIds` 从不登记普通 App 的 task。**

```kotlin
// DisplayCustodian.kt:2122-2130
private fun updateMainTopTracker(newTaskId: Int, newPkg: String) {
    if (newTaskId >= 0 && newTaskId != currentMainTopTaskId) {
        previousMainTopTaskId = currentMainTopTaskId
        currentMainTopTaskId = newTaskId
        if (newPkg in launcherPackages || newPkg == selfPackage) {   // ← 只有桌面和 Mika 自己
            existingMainUserTaskIds += newTaskId
        }
    }
}
```

写入点只有三处：`start()` 启动瞬间的主屏 top（`DisplayCustodian.kt:248-253`）、`onAgentActionStarting()` 瞬间的主屏 top（`:316-323`）、以及上面的 `updateMainTopTracker`。微信既不是桌面也不是 Mika，**永远进不了这个集合**。于是 R1 的第二分支对微信永久失效。

**（2）R3 完全不看"最近有没有 Agent 动作"，只看"有没有用户意图证据"。**

```kotlin
// CustodyAttribution.kt:190-201
val managedDisplayForOrigin = findManagedDisplayForUid(origin.uid, context.managedDisplays)
if (!origin.isUnknown && managedDisplayForOrigin != null) {
    return if (hasRecentUserIntent(arrival.at, context)) {
        AttributionResult.Ignore(IgnoreReason.USER_INTENT_EVIDENCE)
    } else {
        AttributionResult.Spill(managedDisplayForOrigin, SpillReason.MANAGED_ORIGIN_NO_USER_INTENT)
    }
}
```

而"用户意图证据"只认三种来源（`DisplayCustodian.kt:622-631`）：桌面、SystemUI、最近任务。**用户在微信内部的点击不算证据**——但恰恰是这次点击触发了微信的 activity 跳转，也就是这次 arrival。

**（3）一次误判后无法自愈。**

`handleArrival` 在 `Spill` / 已处理的 `Unattributed` 分支里**不更新** `currentMainTopTaskId`（`DisplayCustodian.kt:693-747`）。所以微信被搬走一次后，`previousMainTopTaskId` 仍停在桌面，下一次 arrival 依旧过不了 R1，依旧 R3 → 又搬。这就是"只要打开"的由来。

### 路径 C：任务运行中，任意主屏到达被 3 秒窗口"连坐"

```kotlin
// DisplayCustodian.kt:1002-1010
if (recentAgentAction && !isSysPkg && arrival.taskId >= 0) {
    pullTaskBackToVd(
        taskId = arrival.taskId,
        pkg = arrival.packageName,
        userId = arrival.userId,
        vd = vd,
        fromJumpConfirm = null,
    )
    return true
}
```

`recentAgentAction` 的窗口是 3 秒（`DisplayCustodian.kt:2216` `AGENT_ACTION_WINDOW_MS = 3_000L`），但 Agent 每次 `openApp` / 点击 / 输入都会 `onAgentActionStarting()` 刷新时间戳（`AndroidScreen.kt:1107,1149,1443,1607`）。**任务执行期间这个窗口几乎常开**。此时只要 `origin` 解析成 `UNKNOWN`（ColorOS 等 ROM 很常见），主屏上任何非系统 App 的到达都会走到这里被直接搬走。

`origin` 解析为什么经常是微信自己：`LaunchOriginResolver` 会优先找"非自启动、非系统、非桌面"的外部发起方，找不到时**保留栈顶自启动作为 origin**：

```kotlin
// LaunchOriginResolver.kt:97-111
val top = records.first().origin
if (top != null && !isSelfOrSystemOrigin(top, targetPackage) && !isLauncherLikePackage(top.packageName)) { ... }
...
// 若栈顶是目标自身自启动（如 WXBizEntryActivity）且栈底为历史桌面 Launcher，保留栈顶自启动
if (top != null) return LaunchOrigin(top.uid, top.packageName, LaunchOrigin.CONFIDENCE_HIGH)
```

微信内部页面跳转全是"自己启动自己"，所以 `origin = com.tencent.mm / 微信 uid` 是高置信度结果。

---

## 3. 为什么偏偏感觉是微信

1. 微信是 singleTask + 固定 affinity，**全机只有一个 task**，跨屏只能搬、不能复制；
2. 微信几乎常驻后台（用户按 Home 而非退出），`isVisible=false` 正好绕过启动侧防打扰；
3. 微信内部页面跳转极其频繁（聊天↔列表↔朋友圈），每次都产生 `sameTask` arrival，持续喂给 R3；
4. 微信 task 一旦在虚拟屏出现过，微信 uid 就进入 `managedUidsByDisplay`，而该集合**只增不减**：

```kotlin
// DisplayCustodian.kt:216-217
private val managedUidsByDisplay = ConcurrentHashMap<Int, MutableSet<Int>>()
private val vdPackages = ConcurrentHashMap.newKeySet<String>()
```

全文件没有任何 `remove` / 过期逻辑，只有 custodian 对象销毁（任务结束）才随之释放。单次任务内，微信会一直被视为"虚拟屏的 App"。

同样的机制对淘宝、支付宝、抖音、小红书完全成立——只要 Mika 在虚拟屏上开过它们一次。

---

## 4. 判定矩阵（哪条路径命中）

| 观察到的现象 | 对应路径 | 关键日志 |
| --- | --- | --- |
| 主屏微信后台 → 任务一开微信就消失，虚拟屏出现完整微信 | A（`start` / `resumeKept`） | 无 `arrival`，只有 `startApp`；`dumpsys` 里 taskId 的 `displayId` 从 0 变 vd |
| 用户正在主屏用微信，切个页面就被搬走 | B（R3） | `MikaCustody: arrival task=N pkg=com.tencent.mm disp=0 ... -> Spill(displayId=vd, reason=MANAGED_ORIGIN_NO_USER_INTENT)` |
| 任务执行中打开任意 App 都被搬走 | C（3s 窗口） | `-> Unattributed(recentAgentAction=true)` + `mainSpill classify ... outcome=null` + `moveTask` |
| 任务结束后微信在主屏"点不开/闪一下" | A（`keepBehind` + `resumeKept`） | `AppsAfterTask: kept 1 ...`；`keptOnMain` 命中 |

---

## 5. 现场取证（真机）

### 5.1 抓日志

```bash
adb logcat -c
# 复现：主屏打开微信 → 按 Home → 让 Mika 执行一个会打开微信的任务 → 再点微信图标
adb logcat -v time -s MikaCustody AgentCustody AppsAfterTask MikaShell | tee wechat-pull.log
```

关注三类行：

```
arrival task=<id> pkg=com.tencent.mm disp=0 origin=<uid>/<pkg> -> Spill(...)
mainSpill classify task=<id> pkg=com.tencent.mm outcome=null
windowScan(...) ... vdPkgs=[...com.tencent.mm...] lastAct=<ts>
```

### 5.2 看 task 归属变化

```bash
adb shell dumpsys activity activities | grep -E "Task\{|displayId=|com.tencent.mm" | head -40
```

对比操作前后微信 task 的 `displayId`：

- `0 → <vd>`：被搬进虚拟屏（路径 A/B/C 的最终动作）
- `<vd> → 0`：被用户或 launcher 拉回主屏（随后可能又被搬回去）

### 5.3 最小复现（不依赖 Mika 任务）

```bash
VD=<虚拟屏 id，可在 dumpsys displays 里看到>
# 1) 主屏开微信，退到后台
adb shell am start -n com.tencent.mm/.ui.LauncherUI
adb shell input keyevent KEYCODE_HOME
# 2) 在虚拟屏启动微信（等价于 Mika 的 setLaunchDisplayId）
adb shell am start --display $VD -n com.tencent.mm/.ui.LauncherUI
# 3) 看 task 的 displayId
adb shell dumpsys activity activities | grep -A2 "com.tencent.mm"
```

若第 3 步显示微信 task 已从 `displayId=0` 变为 `displayId=$VD`，即证明**启动侧就是"搬移"语义**，与托管逻辑无关。

### 5.4 验证 R3 误判

临时把 `AGENT_ACTION_WINDOW_MS` 改成 0 或给 R3 加一行日志打印 `origin.uid` / `managedUids`，复现一次"用户在微信内切页"，确认日志出现 `reason=MANAGED_ORIGIN_NO_USER_INTENT`。

---

## 6. 修复建议

按优先级排序，P0 先止血，P1 修根因，P2 加护栏。

### P0-1 启动侧：不要把主屏后台的 App 抢进虚拟屏

`AgentShellEngine.inUseOnMainScreen` 目前只认 `isVisible`。建议改为"主屏存在该包的任何 task 即视为占用"，并区分返回码：

```kotlin
// AgentShellEngine.kt:587
private fun inUseOnMainScreen(packageName: String, userId: Int? = null): Boolean =
    tasksOn(Display.DEFAULT_DISPLAY).any { isCopy(it, packageName, userId) }   // 去掉 isVisible 过滤
```

如果产品上必须允许"接管后台实例"，至少要走显式确认（用户点一下"把微信移到 Mika 屏"），而不是静默 `moveTask`。

同时 `resumeKept` 加一道否决：

```kotlin
// AgentShellEngine.kt:939
private fun resumeKept(...): Boolean {
    if (inUseOnMainScreen(packageName, userId)) return false   // 用户刚用过，别搬
    ...
}
```

### P0-2 托管侧：把"从虚拟屏迁出"与"越屏新开"区分开

`CustodyAttribution` 完全没用 `arrival.fromDisplayId`。加一条 R0.5（放在 R1 之前）：

```kotlin
// CustodyAttribution.kt:178 附近
// R0.5: 任务是"从托管屏迁到主屏"的整栈移动（非新开），视为用户/系统把任务叫回主屏，不拉回
val movedOutOfManaged = !arrival.newTask && !arrival.sameTask &&
    arrival.fromDisplay == context.managedDisplayForTask(arrival.taskId)
if (movedOutOfManaged) return AttributionResult.InUse(InUseReason.MOVED_FROM_MANAGED_DISPLAY)
```

这条能一刀切断"搬来搬去"的循环：用户把微信叫回主屏，托管器不再抢。

### P0-3 托管侧：R3 增加"Agent 动作"前置条件

R3 现在的语义是"origin 属于虚拟屏 → 一定是越屏"，但用户在主屏使用同一个 App 时 origin 也属于虚拟屏。建议：

```kotlin
// CustodyAttribution.kt:190
if (!origin.isUnknown && managedDisplayForOrigin != null) {
    // 没有近期 Agent 动作，且到达前该 task 已在主屏（非新开）→ 用户在用自己的 App
    if (!context.hasRecentAgentAction(arrival.at) && !arrival.newTask) {
        return AttributionResult.Ignore(IgnoreReason.USER_INTENT_EVIDENCE)
    }
    ...
}
```

### P1-1 修 `existingMainUserTaskIds` 语义

```kotlin
// DisplayCustodian.kt:2126
// 任何进入主屏顶部的普通 task 都算"用户主屏任务"，不再只限桌面/Mika
existingMainUserTaskIds += newTaskId
```

并在 `CustodyTracker` 侧维护一个"曾出现在主屏的 taskId"集合，通过 `CustodyArrival` 下发，避免 App 侧采样时机错过。

### P1-2 给托管归属加 TTL / 改按 task 记账

`managedUidsByDisplay` 与 `vdPackages` 只增不减，导致"上过一次虚拟屏 = 永久虚拟屏 App"。两个选项：

- 最小改动：给集合加时间戳，30s 未在虚拟屏快照中出现即移除（与 `CustodyTracker.leftUids` 的 30s 保留期对齐）；
- 更彻底：把 `managedUids` 换成 `managedTaskIds`，归属判定按 task 而不是 UID，天然免疫"同一 App 在主屏另有 task"的情况。

### P1-3 收尾默认策略改为"不保留"

```kotlin
// AppsAfterTask.kt:70
else -> Fate(t, true, Why.Default)   // → Fate(t, false, Why.Default)
```

未标记的 App 直接随屏关闭，避免在主屏留下 hidden task 被 `resumeKept` 二次搬运。若产品需要保留，至少在 `finishDisplay` 返回里明确告知用户"微信已保留在主屏后台"。

### P2 护栏

1. `pullTaskBackToVd` 前记录主屏最近一次任意包的 `TYPE_TOUCH_INTERACTION_START`，2s 内不搬（现在只认桌面/SystemUI/最近任务）。
2. `tryClassifyMainWindowForArrival` 的直接 pull 增加 `origin.uid ∈ managed(D)` 或 `fromDisplayId == vd` 的硬条件，`recentAgentAction` 单独不足以下结论。
3. 加 `FeatureFlags` 开关：自动拉回普通第三方 App 默认关闭，只保留系统选择器/授权弹窗处理；出问题时用户可自救。
4. 补回归测试：`CustodyAttributionTest` 增加三个用例——"sameTask 且 task 曾在主屏"、"fromDisplay=vd 的整栈迁出"、"managed uid 但无 Agent 动作"——都断言 `Ignore/InUse`，不是 `Spill`。

---

## 7. 代码位置索引

| 文件 | 关键行 | 作用 |
| --- | --- | --- |
| `app/.../a11y/DisplayCustodian.kt` | 216-217 | 托管 UID/包集合（只增不减） |
| 同上 | 693-747 | arrival 归因分发 |
| 同上 | 1002-1010 | 3s 窗口内直接 pull |
| 同上 | 1926-2010 | `pullTaskBackToVd` 实现 |
| 同上 | 2122-2130 | 用户任务登记（只登记桌面/Mika） |
| 同上 | 2216 | `AGENT_ACTION_WINDOW_MS = 3000` |
| `core/.../custody/CustodyAttribution.kt` | 141-144 | 1.5s / 3s 时间窗 |
| 同上 | 178-201 | R1 / R3 |
| 同上 | 250-258 | `r1InUseReason` |
| `aau/.../shell/CustodyEngine.kt` | 204-237 | 主屏 arrival 差分 |
| `aau/.../shell/AgentShellEngine.kt` | 453-475 | `start()` |
| 同上 | 587-592 | `inUseOnMainScreen`（isVisible 过滤） |
| 同上 | 833-849 | `keepBehind`（隐藏沉底） |
| 同上 | 925-949 | `keptOnMain` / `resumeKept` |
| `aau/.../shell/AppsAfterTask.kt` | 61-72 | 默认 keep |
| `aau/.../shell/LaunchOriginResolver.kt` | 97-111 | 自启动遮蔽 → origin 取目标自身 |
| `aau/aau-server/.../TaskManagerFacade.kt` | 150-192 | 另一条启动实现 |
| `app/.../a11y/AndroidScreen.kt` | 1099 | "系统会把整个任务挪过来" |

---

## 8. 实际修复与验证

### 8.1 采取的方案

没有按 P0-1 去改 `inUseOnMainScreen` 的 `isVisible` 过滤。原因：单实例应用（微信）全机只有一个 task，Agent 要操作它就必须把 task 挪进虚拟屏，这是 Android 的硬约束（`AndroidScreen.kt:1099` 自己写了这句）。把 `isVisible` 去掉只会让"微信在后台"时任务直接以 `START_IN_USE_ON_MAIN` 失败，用户拿不到结果。

真正要断的是**"搬回来又被抢走"的循环**，所以改在判定层。

#### 改动一：归因器新增 R1.5（`core/.../CustodyAttribution.kt`）

```kotlin
// R1.5: 发起方这个 App 自己还留在主屏上 -> 这是用户在主屏内的跳转（小程序等），不是越屏
if (context.originOnMain) {
    return AttributionResult.Ignore(IgnoreReason.ORIGIN_ON_MAIN)
}
```

位置在 R1 之后、R2 之前。语义是：**只要发起这次跳转的 App 此刻仍在主屏当顶部任务，这次到达就是用户在主屏里点出来的**——微信开小程序、内置浏览器、扫一扫结果页都符合这个形状。它不是 Agent 越屏，把用户界面搬走没有任何收益。

`AttributionContext` 增加 `originOnMain: Boolean = false` 字段，`IgnoreReason` 增加 `ORIGIN_ON_MAIN`。

#### 改动二：发起方判定（`app/.../a11y/DisplayCustodian.kt`）

```kotlin
private fun originWasMainTop(origin: LaunchOrigin, vd: Int): Boolean {
    if (vd <= Display.DEFAULT_DISPLAY || origin.isUnknown) return false
    if (origin.uid < android.os.Process.FIRST_APPLICATION_UID) return false
    val pkg = origin.pkg?.takeIf { it.isNotBlank() } ?: return false
    if (pkg == selfPackage || pkg in launcherPackages) return false
    val top = currentMainTopTaskId
    if (top < 0) return false
    return runCatching { runtime.packageOfTask(top) }.getOrNull() == pkg
}
```

三个关键约束：

- `uid < 10000` 排除——`shell(2000)` / `android(1000)` 是 Agent 或系统框架在起任务，不是"用户在某个 App 里点了什么"；
- 排除 Mika 自身与桌面；
- 比较对象是 `currentMainTopTaskId`，而调用点在 `updateMainTopTracker()` **之前**（`DisplayCustodian.kt:690` vs `700/704/745`），所以拿到的是"到达前的主屏顶部"，正是发起方。

#### 改动三：托管 UID 账本按实况校验（`app/.../a11y/DisplayCustodian.kt`）

`managedUidsByDisplay` 只增不减，App 被挪回主屏后旧账还在，导致 `origin.uid ∈ managed(D)` 对微信永远成立。新增：

```kotlin
private fun liveManagedUids(vd: Int): Set<Int> {
    val managed = managedUidsByDisplay[vd].orEmpty()
    if (managed.isEmpty()) return emptySet()
    return managed.filterTo(HashSet()) { uid ->
        val pkg = findPackageByUid(uid) ?: return@filterTo true   // 查不到包名时保守保留
        runtime.displayOfPackage(pkg) == vd
    }
}
```

`buildAttributionContext` 与 `buildClassificationContext` 都改用这个实况集合。判定依据从"曾在这块屏上出现过"变成"此刻确实还在这块屏上"。

Runtime 接口相应增加 `displayOfPackage(pkg)`，走 `IAgentShell.displayOfPackage = 16`（既有事务，无 AIDL 变更）。

#### 改动四：kept 认领带时间戳（`aau/.../shell/AgentShellEngine.kt`）

`finishDisplay` 把未标记 App 摆到主屏后台并记进 `kept`（`AppsAfterTask` 默认 `keep=true` 保持不变）。问题在于用户之后自己点开它，认领仍然有效，下次任务 `resumeKept` 直接搬走。

```kotlin
private class KeptClaim(val key: String, val atMs: Long)

private fun takenOverByUser(taskId: Int, lastActiveAtMs: Long?): Boolean {
    if (lastActiveAtMs == null) return false
    val claimedAt = synchronized(kept) { kept[taskId]?.atMs } ?: return false
    if (lastActiveAtMs <= claimedAt) return false
    synchronized(kept) { kept.remove(taskId) }
    Log.i(tag, "task $taskId was activated by the user after we parked it; dropping our kept claim")
    return true
}
```

`keptOnMain` 里对候选 task 逐个跑这个检查。`lastActiveTime` 与 `SystemClock.uptimeMillis()` 同为开机基准，可直接比较。用户动过 → 认领作废 → 走正常的"主屏占用"判定，而不是偷偷搬走。

### 8.2 单元测试

`core/.../CustodyAttributionTest.kt` 新增两条：

| 用例 | 输入 | 期望 |
| --- | --- | --- |
| `R1_5 leaves alone a task the user opened inside an app that is still on the main screen` | 小程序 task，origin=微信，`originOnMain=true`，`recentAgentAction=true` | `Ignore(ORIGIN_ON_MAIN)` |
| `R1_5 does not shield arrivals once the origin app has left the main screen` | 同上但 `originOnMain=false` | `Spill(vd, MANAGED_ORIGIN_NO_USER_INTENT)` |

第二条是关键护栏：微信只在虚拟屏上（Agent 正在操作）时，它冒到主屏的新任务仍然要被拉回——R1.5 不能变成万能豁免。

顺带补齐了 `MikaAgentDisplayIntegrationTest.FakeMikaShell` 缺失的 `capturePng` / `captureLongPng` / `captureCapability`（事务 56-58），此前一直阻断测试源集编译。

执行结果：

```
:core:test --tests "*CustodyAttributionTest"        BUILD SUCCESSFUL
:app:testDebugUnitTest --tests "*DisplayCustodianTest"  BUILD SUCCESSFUL
```

### 8.3 真机验证

设备 PLK110，屏幕 1272×2772，虚拟屏为 `mika-agent`。

**场景 1：用户在主屏抢回微信**

主屏开微信 → Home → 让 Mika 执行「打开微信，进入通讯录，依次打开前三个联系人的资料页」→ 虚拟屏 157 创建 → 按 Home → 点桌面微信图标。

```
09:24:29.469 I/MikaCustody: arrival task=49817 pkg=com.tencent.mm disp=0 \
             origin=com.android.shell/2000 onMain=false -> InUse(reason=WAS_MAIN_TOP_TASK)
```

微信在 display 0 连续 10 秒不动，用户可以正常浏览联系人。

**场景 2：用户在主屏微信里打开小程序（原始故障）**

微信在主屏 → 下拉唤起小程序面板 → 点「瑜小九」。

```
09:27:00.751 I/MikaCustody: arrival task=49819 pkg=com.tencent.mm disp=0 \
             origin=com.tencent.mm/10433 onMain=true -> Ignore(reason=ORIGIN_ON_MAIN)
```

小程序 task `#49819 AppBrandUI00` 稳稳停在 display 0，未出现任何 `Spill`。界面正常渲染，用户可直接使用。

对照修复前同一操作的真机记录：

```
19:08:21 arrival task=49722 pkg=com.tencent.mm disp=0 \
         origin=com.tencent.mm/10433 -> Spill(displayId=149, reason=MANAGED_ORIGIN_NO_USER_INTENT)
19:09:09 arrival task=49723 pkg=com.tencent.mm disp=0 \
         origin=com.tencent.mm/10433 -> Spill(displayId=149, reason=MANAGED_ORIGIN_NO_USER_INTENT)
```

同样的 origin、同样的包名，旧代码整条搬到虚拟屏，新代码放行。`onMain` 从隐式判断变成日志里的显式字段，出问题可直接分辨是哪条规则生效。

### 8.4 收尾路径加固

`finishDisplay` 原来把 `releaseDisplay` 放在 `closeOut` 的最后一行，一旦 `settleBehind` / `sortAndMove` 抛出，这块屏就再没人归还，作为空屏永久挂着。

改成 `try/finally`，并把 `closeOut` 里那行释放移出来，让释放点唯一：

```kotlin
fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle? {
    synchronized(launchLock) { if (!ownDisplay(displayId)) return null }
    return try {
        val sorting = synchronized(launchLock) { sortAndMove(displayId, keep, close) }
        val settled = settleBehind(sorting.moved.mapTo(LinkedHashSet()) { it.task.id })
        synchronized(launchLock) { closeOut(displayId, sorting, settled) }
    } finally {
        releaseDisplay(displayId)
    }
}
```

细节：

- `!ownDisplay` 的 `return null` 留在 `try` 之外——屏根本不归我们时不释放，这是语义边界，不是遗漏；
- `releaseDisplay` 本身幂等：`displays.remove(displayId)` 取不到 VD 时 `vd?.release()` 不执行，`stopCustody` 在 `listeners.remove` 返回 null 时直接退出。重复调用无副作用；
- 释放挪到 `closeOut` 之后，`Bundle` 组装时 VD 仍然存活，不会读到已释放的屏。

顺带补了上游同款缺口：`AgentVirtualDisplay.finish()` 里 `shell.finishDisplay` 抛出时不会走到 `closeLocalResources()`，本地 reader 线程与全尺寸帧缓冲会一直挂着。

```kotlin
val resultBundle = try {
    shell.finishDisplay(displayId, keepPackages.toTypedArray(), closePackages.toTypedArray())
} finally {
    closeLocalResources()
}
```

`AppsAfterTaskEnd.finish` 的 catch 注释一直写着 "apps close with the display"，但此前并未真的做到；现在这句描述才成立。

真机回归：任务运行中抢走微信 → 任务进入"已暂停"（设计内行为，屏保留是必要的）→ 点「取消任务」→ 虚拟屏 158 当场释放，`dumpsys window displays` 只剩 `mDisplayId=0`。

### 8.5 其余遗留

1. `managedUidsByDisplay` 仍未在屏释放时清理。当前靠 `liveManagedUids` 在使用点兜底，功能正确，只是内存上按 displayId 累积（新屏都是新 ID，不会误命中旧条目）。
2. 路径 A 的"启动侧搬移"保持原样——这是单实例应用的必然行为。用户可在搬走后自行点回主屏，现在会被 R1/R1.5 接住，不再来回搬。
