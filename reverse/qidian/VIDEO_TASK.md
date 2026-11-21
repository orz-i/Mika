# 起点「看视频」任务的实现与可跳过性分析

对象：`com.qidian.QDReader`（`reverse/qidian/apk/base.apk`，11 个 dex，131 MB）
入口：福利中心「激励任务」`qd_flzx_normaltask`，以及阅读页章节末 `qd_read_chapterend`
结论一句话：**奖励闸门在广告 SDK 的回调里，不在 App 自己的领奖接口里；App 自己的
`finishWatch` 接口不携带任何"我确实看了广告"的凭证。**

---

## 1. 任务模型：一个任务，九个阶梯

`GET /argus/api/v2/video/adv/mainPage` 的 `Data.DailyBenefitModule`：

```json
{
  "Title": "激励任务",
  "Desc": "完成广告任务，多重好礼等你解锁",
  "Count": 46,
  "Process": 1,
  "NormalTaskType": 0,
  "TaskList": [ ... 9 条 ... ]
}
```

9 条 `TaskList` 的 `TaskId` / `TaskRawId` **完全相同**：

| # | TaskRawId | TaskId | Desc | IsFinished | Process | Total |
|---|---|---|---|---|---|---|
| 1 | T2025090203 | 1205501151274598400 | 10-3点订阅券 | 1 | 0 | 0 |
| 2 | T2025090203 | 1205501151274598400 | 惊喜礼包 | 0 | 0 | 0 |
| … | 同上 | 同上 | … | 0 | 0 | 0 |
| 9 | T2025090203 | 1205501151274598400 | 20点章节卡 | 0 | 0 | 0 |

所以 9 条不是 9 个任务，而是**同一个任务的 9 个阶梯奖励**：

- `DailyBenefitModule.Process` = 已完成阶梯数（本例 1）；
- `TaskList[i].IsFinished` = 第 `i+1` 阶是否达成；
- `TaskList[i].IsReceived` = 该阶奖励是否已领；
- `Total` 全为 0，说明这个任务不按数值进度算，只按"看过几个视频"。

看完一个视频 → 服务端把 `Process` 加 1，第 `Process` 阶的 `IsFinished` 置 1，
然后客户端再调一次 `receiveTaskReward` 把奖励领掉。**一格 = 一次广告 = 一个 15~30 秒**。

---

## 2. 客户端链路

### 2.1 H5 有两条路，按客户端版本分流

`reverse/qidian/h5/page_common_welfareCenter.js` 里 `Ba()` 与 `Da()` 的分叉：

```js
const i = jssdk.versionCode;
if (jssdk.isIOS && i < 741 || jssdk.isAndroid && i < 1917 || jssdk.isHarmony && i < 1487)
    return Da(e, a, t);     // 老路径：H5 自己调 showRewardVideo，再自己调 finishWatch
// 新路径：
const d = { appId: 1108323910, adPositionCode: n, advTaskId: e.TaskId,
            advMilestone: "" + (e.Process + 1), advType: c,
            customDynamicData: ..., rewardRequestInfo: ... };
jssdk.app.showRewardVideoNative(d, ({ RewardList: a }) => { ... });
```

- `appId = 1108323910`，`adPositionCode = qd_flzx_normaltask`（九个位共用一个）；
- 真正的广告位 `adCodeId` 不在这份 JS 里，由服务端配置下发，native 侧用
  `QDAppConfigHelper.U0(adPositionCode)` 查；
- `advMilestone = Process + 1`，也就是"这是第几个视频"。

### 2.2 原生侧（新版路径的唯一入口）

`com.qidian.QDReader.extras.r0#g1`（反编译里叫 `ADHelper.showRewardVideoNative`）：

```
g1(activity, posId, taskId, config, customDynamicData, milestone, failCount, requestInfo)
  ├─ 弹 loading
  ├─ UniversalRiskHelper.a(activity, "1999")          // 取风控令牌
  ├─ O0() 选平台：config.getAdPlatform() == 2 ? CsjRewardManager : GDTForQD
  ├─ 广告回调 num ──┐
  ├─ num == 5 或 11 → K0() → GET /argus/api/v1/video/adv/finishWatch
  │                    ├─ 成功且 RewardList 非空 → T0(..., 2)   // 状态 2 = satisfy
  │                    ├─ RewardList 空 → S0(..., "reward empty")
  │                    └─ 返回 riskConf → 视为风控失败
  ├─ num == 10 → Q()   // 另一条通道（type/h5 不同）
  └─ num == 8  → 关闭
```

广告平台是**两条并存**的：

| 平台 | 类 | 证据 |
|---|---|---|
| 穿山甲 Pangle | `com.qidian.QDReader.other.ad.CsjRewardManager` | `lib/arm64-v8a/libpangleflipped.so`；`com/bytedance/sdk/openadsdk` 525 处 |
| 优量汇 GDT | `com.qidian.QDReader.extras.GDTForQD`（`TangramRewardAD`） | 原生 TLS 抓包里有 `gdt.qq.com`（8 处） |

用哪个由服务端配置的 `AdPlatform` 决定，客户端两条路都能走。

### 2.3 奖励闸门：`onRewardArrived`，不是 `onRewardVerify`

`CsjRewardManager.java:107`：

```java
public void onRewardVerify(boolean z, int i, @Nullable String str, int i8, @Nullable String str2) {
}      // 空实现
```

`CsjRewardManager.java:80`：

```java
public void onRewardArrived(boolean z, int i, @Nullable Bundle bundle) {
    if (!z) { /* 奖励无效 → onError */ return; }
    ...  "奖励是否有效：" + z ...
    this.search.onNext(5);       // 5 = 奖励到达
}
```

`onRewardVerify` 是老的**客户端验证**回调，这里是空的；真正生效的是
`onRewardArrived`，它的第一个参数 `isRewardValid` 由穿山甲服务端给出，
bundle 里的 `reward_extra_key_reward_propose` / `reward_extra_key_error_code`
也是服务端奖励（S2S）模式的字段。

**这就是最关键的一点：App 不信自己算出来的"看完了"，只信 SDK 带回来的结论。**

### 2.4 领奖接口：`finishWatch` 不带广告凭证

`ya/n.java:571`：

```java
Call<ServerResponse<RewardResultBean>> j1(
    @Field("taskId") String taskId,
    @Field("gradientLevel") int gradientLevel,   // 0
    @Field("type") int type,                     // 1
    @Field("h5") int h5,                         // 0
    @Field("oneOff") int oneOff,                 // 0 = 正常，1 = 免广告兜底
    @Field("sessionKey") String sessionKey,
    @Field("banId") int banId,
    @Field("captchaTicket") String captchaTicket,
    @Field("captchaRandStr") String captchaRandStr,
    @Field("challenge") String challenge,
    @Field("validate") String validate,
    @Field("seccode") String seccode);
```

十二个参数里，**没有 adRequestId、没有 rewardToken、没有播放时长、没有 SDK 的
透传字段**。服务端能看到的只有：是哪个任务 + 一个风控令牌。

H5 那条路（`Ra()`）也一样：

```js
params: { taskId: a.TaskId, ...(a.TaskType === 400 ? { type: 1 } : {}), ...riskConf.RiskConf }
```

### 2.5 App 自己就有「不看广告也发奖」的通道

`r0.java:283` `T()`，唯一区别是把 `oneOff` 传 1：

```java
j1(subTaskId, 0, 1, 0, 1, eVar.b(), ...)      // oneOff = 1
```

它被 `r0.java:715` 的失败分支调用：

```java
Logger.w("ADHelper", "ad watch failed," + message + ",oneOf: " + search.get()
        + ",process: " + i + ",noAdvAndRewardTimes: " + extraRewardFlag
        + ",gradientLevel: " + gradientLevel + ", ... welfareCenterAdFailCount: " + i8);

if (!(th instanceof ADException) || message == null || !message.contains("102006")
        || gradientLevel + process <= 0 || extraRewardFlag <= 0) {
    // 不是 102006 / 没有阶梯 / 没有额外奖励标记
    if (!networkReachable) zT = false;
    else if (++currentErrorCount + search.get() >= failCount - 1) { ... T(...); }
} else if (search.getAndIncrement() >= extraRewardFlag) {
    ... T(...);            // 兜底发奖
}
```

触发 `T()`（也就是 `oneOff=1` 免广告发奖）的条件，四条同时成立：

1. 抛的是 `ADException`，且 message 里含 **`102006`**（广告加载/填充失败类错误）；
2. `gradientLevel + process > 0`，即当前确实有可推进的阶梯；
3. `extraRewardFlag > 0` —— 这个值来自**上一次成功广告的 passthrough**
   （`r0.java` 的 `h0`：`RewardResult.getPassthrough()` 里读 `extraRewardFlag`）；
4. 本次运行里 `search` 计数还没超过 `extraRewardFlag`。

另外还有一条"网络不可达 + 连续错误次数达到 `welfareCenterAdFailCount - 1`"的
兜底，也走 `T()`。

**这两条兜底本身就证明了服务端对 `finishWatch` 的校验不包含"广告是否真的播了"**，
否则兜底不可能工作。但它们的设计意图是"广告挂了别让用户白等"，不是给刷量留门：
计数、`welfareCenterAdFailCount`、`banId` 封禁都是拦这个的。

---

## 3. 三层闸门

| 层 | 位置 | 判什么 | 能不能绕 |
|---|---|---|---|
| L1 | 广告 SDK 内部 | 视频是否播完 / 进度事件 | 不能，除非 root + hook |
| L2 | `onRewardArrived(isRewardValid)` | 穿山甲服务端判定的奖励有效性 | 不能，伪造要同时骗过 SDK 服务端 |
| L3 | `finishWatch` 服务端 | 任务状态 + 风控令牌 | 理论上可以直连，但一次只用一格、日上限、账号风控 |

大多数人以为"卡在 L3"，实际卡在 **L1/L2**：客户端根本没机会走到 `finishWatch`，
除非 SDK 先给 `num = 5`。

---

## 4. 四种方案

### A. 真实播放 + UI 自动化（推荐）

原理：不改任何东西，用无障碍/坐标点击把"点任务 → 看广告 → 关广告 → 领奖 → 下一格"
跑完。Mika 现有的页面图谱 + 点击能力已经够用。

- 时长：9 格 × (15~30s 广告 + 3~5s 操作) ≈ **3~5 分钟/天**；
- 稳定性：高。广告位没填充时（102006）官方兜底会直接发奖，等于顺带被跳过；
- 风险：正常用户行为，不触碰协议。

### B. 等广告加载失败，吃官方兜底

原理：主动让广告加载失败（屏蔽穿山甲/优量汇的广告域名，或广告请求期间断网），
让 App 走到 `T()`。

- 现实约束：需要 `extraRewardFlag > 0`，而这个值只有**上一次广告成功**才有；
  第一次装/清缓存后无法触发。且第 4 条计数会把连续失败掐断；
- 稳定性：低，属于"碰运气"；
- 风险：仍然是刻意制造失败以获取奖励，属于绕过广告。

### C. 直连 `finishWatch`

原理：`risk/check?appId=1999` 拿风控令牌 → `finishWatch(taskId, gradientLevel, ...)`，
完全不碰广告。

- 前提：带着登录态 Cookie 和 App 的签名头（`ibex` / `tstamp` / `borgus` / `sdksign`）；
- 未知：服务端是否另有"同用户 + 同广告位 + N 分钟内收到过穿山甲奖励回调"的匹配。
  静态分析看不到，只能实测；
- 稳定性：未知。一旦服务端加了匹配或频控，直接失败；
- 风险：最高。这是明确的刷量行为，`risk/check` 的 `banId` 就是用来封这个的。

### D. Hook 广告 SDK

原理：Frida 挂在 `CsjRewardManager$search` 的 RxJava emitter，或者直接替换
`TTRewardVideoAd.RewardAdInteractionListener`，手动 `onNext(5)`。

- 前提：root + 能注入（起点有加固，需先脱壳）；
- 缺点：骗得过 L1，骗不过 L2 —— `finishWatch` 之前没有任何地方再问一次 SDK，
  所以**只要推进到 `num=5` 就够**，L2 也一起被绕过了；
- 风险：最高，且可被 SDK 的完整性校验发现。

### 对比

| | 是否要看广告 | 稳定性 | 需要 root | 合规 |
|---|---|---|---|---|
| A 真播自动化 | 是 | 高 | 否 | 正常使用 |
| B 吃兜底 | 否（但常失败） | 低 | 否（改 DNS/断网） | 绕过广告 |
| C 直连接口 | 否 | 未知 | 否 | 刷量 |
| D Hook SDK | 否 | 中 | 是 | 刷量 + 改客户端 |

---

## 5. 给 Mika 的落地方案

只做 A，B/C/D 的结论写进文档作为"为什么不做"的依据。

### 5.1 状态机

```
IDLE
 └─(任务开始) → READ_STATE
      读 /argus/api/v2/video/adv/mainPage → DailyBenefitModule.Process / TaskList
 └─→ CLAIM_PENDING        有 IsFinished=1 && IsReceived=0 的格子 → 点它领奖
 └─→ OPEN_TASK            Process < 9 → 点第 Process+1 格
 └─→ WAIT_AD              loading 弹窗出现 → 等广告
 └─→ CLOSE_AD             识别倒计时结束（右上角 X / "关闭"）→ 点
 └─→ VERIFY              重新读 mainPage，看 Process 是否 +1
      ├─ +1  → CLAIM_PENDING
      ├─ 不变 → 重试一次 → 仍不变 → 人工
      └─ 广告报错（102006）→ 等 2s 再点一次，第二次失败即停
```

### 5.2 判定用接口，不用屏幕

| 要判的事 | 用哪个 | 不要用 |
|---|---|---|
| 还剩几格 | `DailyBenefitModule.Process` | 进度条像素 |
| 这格能不能领 | `TaskList[i].IsFinished==1 && IsReceived==0` | 格子上的"✓" |
| 视频是否算完成 | `Process` 是否 +1 | 广告是否关掉 |
| 今天还有没有 | `mainPage` 里模块是否还在 / `Count` | — |

屏幕只用来**定位点击目标**，不用来做判定。这跟接口图谱的分工一致。

### 5.3 接线点

- **读状态**：直接复用接口图谱的影子采集（`ApiTraceCollector` 已经能看到
  `/argus/api/v2/video/adv/mainPage` 的 `fields`，`Process` 与
  `TaskList[].IsFinished` 都在里面）；
- **点目标**：走页面图谱（`AppAtlas`）里福利中心页的节点。福利中心是 WebView，
  H5 的 DOM 拿不到，只能靠截图/无障碍节点，这条要用现有的 UI 定位；
- **等待**：广告时长不可控（15s / 30s / 可跳过 5s），不要写死 sleep，
  用"关闭按钮出现"作为条件，超时 45s 兜底；
- **重试**：`102006` 重试一次；连续两次失败就停，别把 `welfareCenterAdFailCount` 用完
  —— 那个计数用完了当天就没有兜底了。

### 5.4 实测建议

如果要验证 C 到底行不行（**只在一个小号上做，且只做一次**）：

1. 抓一次正常流程的 `risk/check` 与 `finishWatch` 请求（已有的
   `tools/aaue_http.py` 能解出来），记下参数与返回；
2. 隔一天，同一个 `taskId` 直接重放 `finishWatch`，不给广告；
3. 看返回的 `RewardList` 是空还是非空、`mainPage.Process` 有没有动。
   - 动了 → 服务端确实不校验广告，C 可行；
   - 空 / 报错 → 说明有隐藏的匹配，C 不可行，也别再试。

---

## 6. 风险

- **协议**：起点用户协议禁止使用外挂/自动化工具获取奖励，B/C/D 都在这一条里；
- **账号**：`risk/check` 返回 `banId`，`banId == 1` 直接给 `banMessage` 并中断，
  这是现成的封禁通道；
- **广告主**：B/C/D 产生的都是无效曝光，性质上属于广告欺诈，不只是"薅羊毛"；
- **工程**：C 依赖签名头与风控令牌，任何一次风控升级都会全线失效；A 不会。

---

## 7. 附：验证用日志标签

真机看广告流程时可过滤这几个 tag（本机实测时这三条都没打出来，说明当时点击没落到
任务格上，属于 UI 定位问题，不是链路问题）：

```
adb logcat -s ADHelper:* CshRewardManager:* GDTForQD:* UniversalRiskHelper:*
```

关键行：

- `ADHelper: showRewardVideoNative taskId: ... milestone: ... adCodeId: ...`
- `CshRewardManager: Callback --> rewardVideoAd has onRewardArrived 奖励是否有效：true`
- `ADHelper: finishWatchAsync success1, rewardList: N`
- `ADHelper: ad watch failed,...102006...noAdvAndRewardTimes: ...`
- `ADHelper: finishWatchSync getRewardDefault success1`（走了 `oneOff=1` 兜底）
