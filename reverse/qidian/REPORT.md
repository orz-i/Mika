# 起点读书 7.9.486 福利中心接口分析

分析对象：`com.qidian.QDReader` v7.9.486（versionCode 2018，targetSdk 30，单 base.apk 126MB）
样本路径：`D:/Mika/reverse/qidian/apk/base.apk`
分析方式：dex 字符串池 / 类结构解析 + Flutter AOT 快照（libapp.so）字符串分析

---

## 一、结论先行

**抓不到"福利中心完成情况接口"有两个叠加原因，其中第一个是决定性的：**

1. **福利中心页面是 Flutter 实现，网络请求由 dart:io 的 `HttpClient` 直接发出。** dart:io 不读取 Android 系统代理设置，Charles / Fiddler 的 WiFi 代理模式对它完全失效。你在原生页面抓得到，一到福利中心就断流，就是这个原因。
2. **根本不存在独立的"完成情况查询接口"。** 任务进度（`process` / `total` / `isFinished` / `isReceived`）是页面聚合接口 `welfarecenter` 一次性下发的一段 JSON 字段，客户端不轮询、不单独查询。

换句话说：**你要的那个接口，在 Flutter 的请求里，而且它叫 `welfarecenter`，不叫 `getTaskProgress`。**

---

## 二、页面架构：三层混合

| 层 | 载体 | 福利相关入口 | 能否被 WiFi 代理抓到 |
|---|---|---|---|
| 原生 Java/Kotlin | classes*.dex | 书城 morphing 卡片、充值任务页、广告阅读任务页 | 能 |
| **Flutter** | `libapp.so`（20MB AOT） | **福利中心主页、阅读福利页、任务弹窗** | **不能** |
| H5 / WebView | X5 内核 | 规则页、活动页 | 能（走系统代理） |

Flutter 侧的硬证据：

- `com.qidian.QDReader.flutter.WelfareFlutterActivity` / `WelfareFlutterFragment`
- Flutter route 名：`newUserWelfareMainPage`、`newUserWelfareMainPageV2`、`newUserWelfareReadPopupPageV2`、`openNewUserWelfareSinglePageV2`
- Dart 侧 widget 报错串：`NewUserWelfareMainPageV2 reload empty/error on net available`、`NewUserWelfareMainPageV2 scrollTopAndReload refresh failed`
- 桥接对象 `WelfareBridge`（`getWelfareBridge` / `access$getWelfareBridge`）
- 降级日志：`direct launch already reported, process fallback to h5`
- **接口路径字符串直接存在于 `libapp.so`**（见下表），说明 URL 由 Dart 层拼接并发出，不经 Java 网络层

---

## 三、接口清单（按所在层拆分）

### 3.1 Flutter 层（在 libapp.so 中，走 dart:io → 抓不到）

| 接口 | 作用 |
|---|---|
| `/argus/api/v1/freshman/welfarecenter` | **福利中心主页 v1（核心）** |
| `/argus/api/v2/freshman/getfreshmanwelfarecenterpage` | **福利中心主页 v2（当前版本在用）** |
| `/argus/api/v1/freshman/getfreshmanwelfarereadingpage` | 阅读福利页 v1 |
| `/argus/api/v2/freshman/getfreshmanwelfarereadingpage` | 阅读福利页 v2 |
| `/argus/api/v1/freshman/task/recFreshmanTask` | 领取任务 |
| `/argus/api/v1/freshman/task/rewardFreshmanTask` | 任务领奖 v1 |
| `/argus/api/v3/freshman/rewardfreshmantask` | 任务领奖 v3 |
| `/argus/api/v3/freshman/autoreceive` | 自动领取 |
| `/argus/api/v1/freshman/task/calendarreport` | 任务日历上报（行为上报，非查询） |
| `/argus/api/v1/freshman/taskdialog` | 任务弹窗配置 |
| `/argus/api/v1/freshman/myQDCoinIncome` | 金币收入 |
| `/argus/api/v1/freshman/addPushPermission` | 推送权限上报 |

Host：`https://brave.if.qidian.com`（v6 走 `bravev6.if.qidian.com`）

### 3.2 Java 层（走 OkHttp → 能抓到）

| 接口 | 作用 |
|---|---|
| `/argus/api/v1/freshman/getreadingtaskprocesstips` | 阅读任务进度提示文案 |
| `/argus/api/v2/video/adv/getwelfaretaskreadingpagev2` | 广告阅读福利页 |
| `/argus/api/v4/recharge/task/list` | 充值任务列表 |
| `/argus/api/v4/recharge/task/take` | 充值任务领取 |
| `/argus/api/v1/midpage/checkreward` | 中插奖励校验 |
| `/argus/api/v1/dialog/receivetask` · `takereward` · `canceltask` | 弹窗任务 |
| `/argus/api/v1/adv/getadvlist` · `getadvlistbatch` | 广告位 |

**注意 3.1 和 3.2 有重叠接口名（如 `rewardFreshmanTask`），但分别由 Dart 和 Java 发起，是两套调用路径，不要混淆。**

---

## 四、"完成度"到底在哪个字段

页面响应体里，任务数组的元素类型是 `com.qidian.QDReader.repository.entity.TaskItem`：

| 字段 | 类型 | 含义 |
|---|---|---|
| `process` | int | **当前进度值** |
| `total` | int | **目标值** |
| `isFinished` | int | 是否达成（0/1） |
| `isReceived` | int | 是否已领奖（0/1） |
| `status` | int | 综合状态 |
| `taskType` | int | 任务类型 |
| `taskRawId` / `subTaskId` | String | 任务标识 |
| `countDownTime` | long | 倒计时 |
| `title` / `rewardDesc` / `rewardImg` / `bubbleText` | String | 展示文案 |
| `displaySort` | int | 排序 |
| `extraRewardConfigs` | List | 额外奖励档位 |

新人训练体系的第二套模型：

```
NewUserTrainingTaskBean
  ├─ taskId: Long
  ├─ taskType: Integer
  ├─ taskStatus: Integer        ← 完成状态
  ├─ dayId: int
  ├─ conditionList: ArrayList<NewUserTrainingTaskCondition>   ← 条件（type/status/desc/btnText/actionUrl）
  ├─ rewardList: ArrayList<NewUserTrainingTaskReward>         ← 奖励（count/desc/icon）
  └─ nextRewardForecast: String
```

阅读页任务模型：

```
WelfareTaskBean
  └─ readingPageTaskModule: ReadingPageTaskModule
       ├─ taskList: List<TaskItem>     ← 同上，含 process/total
       ├─ rewardCount / watchInRow / noAdvAndRewardTimes
       └─ ruleUrl / currentTime / abExpKey / abExpValue
```

**判定逻辑：`isReceived==1` → 已领；`isFinished==1 && isReceived==0` → 可领；`process<total` → 进行中。** 客户端就靠这三个字段渲染，不需要额外查询。

---

## 五、为什么 WiFi 代理抓不到（机制）

```
原生页面：App → OkHttp → 读系统代理(ProxySelector) → Charles:8888 → 目标
Flutter页：App → dart:io HttpClient → findProxyFromEnvironment(读 http_proxy 环境变量)
                                    → Android App 环境变量为空 → DIRECT 直连
                                    → 目标:443   ← Charles 完全不在链路上
```

两个必须同时满足的条件：

1. **路由**：Flutter 流量必须被强制接管（VPN 模式或 iptables 透明代理），系统代理无效。
2. **证书**：Flutter 使用内置 BoringSSL，`SecurityContext` 默认 `TrustBuiltinRoots`，**只信任系统 CA**，用户安装的 CA（`/data/misc/user/0/cacerts-added`）不生效。

只做第 1 步会看到 `HandshakeException`（libapp.so 里存在该异常类字符串），不会看到明文。

---

## 六、抓包方案（设备已 root：KernelSU / Android 17）

设备：PLK110，Android 17（SDK 37）。root 可用，这是最有利条件。

### 方案 A：Reqable（最省事，推荐先试）

Reqable 的 Android 端支持 VPN 抓包 + root 设备一键安装系统证书，能同时解决"路由"和"证书"两个问题。

1. PC 端与手机端都装 Reqable，同一 WiFi
2. 手机端开启 VPN 抓包，目标应用选 `com.qidian.QDReader`
3. root 授权后，把 Reqable CA 写入系统证书目录
4. 打开福利中心，过滤 `freshman`

### 方案 B：iptables 透明代理 + mitmproxy（最可控）

PC 端：

```bash
mitmproxy --mode transparent --listen-port 8080 --set block_global=false
# CA 在 ~/.mitmproxy/mitmproxy-ca-cert.pem
```

证书写入系统（Android 14+ 走 conscrypt apex，需 bind mount）：

```bash
HASH=$(openssl x509 -inform PEM -subject_hash_old -in mitmproxy-ca-cert.pem | head -1)
cp mitmproxy-ca-cert.pem ${HASH}.0

adb push ${HASH}.0 /data/local/tmp/
adb shell su -c "mkdir -p /data/local/tmp/cacerts"
adb shell su -c "cp /apex/com.android.conscrypt/cacerts/* /data/local/tmp/cacerts/"
adb shell su -c "cp /data/local/tmp/${HASH}.0 /data/local/tmp/cacerts/"
adb shell su -c "mount -t tmpfs tmpfs /apex/com.android.conscrypt/cacerts"
adb shell su -c "cp /data/local/tmp/cacerts/* /apex/com.android.conscrypt/cacerts/"
adb shell su -c "chmod 644 /apex/com.android.conscrypt/cacerts/*"
# 让 zygote 重新读取证书
adb shell su -c "kill -9 \$(pidof com.qidian.QDReader)"
```

流量重定向（透明模式必须保留原始目标，用 TPROXY 或 REDIRECT + 还原）：

```bash
adb shell su -c "iptables -t nat -A OUTPUT -p tcp --dport 443 -j DNAT --to-destination PC_IP:8080"
adb shell su -c "iptables -t nat -A OUTPUT -p tcp --dport 80  -j DNAT --to-destination PC_IP:8080"
# 用完清理
adb shell su -c "iptables -t nat -D OUTPUT -p tcp --dport 443 -j DNAT --to-destination PC_IP:8080"
```

### 方案 C：Frida hook BoringSSL（证书实在装不进去时）

```javascript
// 定位 libflutter.so 中的 SSL_write / SSL_read
var m = Process.findModuleByName("libflutter.so");
["SSL_write", "SSL_read"].forEach(function (sym) {
    var p = Module.findExportByName("libflutter.so", sym);
    if (!p) return;
    Interceptor.attach(p, {
        onEnter: function (args) {
            try {
                var buf = args[1], len = args[2].toInt32();
                if (len > 0 && len < 65536) {
                    var s = buf.readUtf8String(len);
                    if (s && s.indexOf("HTTP/") === 0) console.log("[" + sym + "]\n" + s);
                }
            } catch (e) {}
        }
    });
});
```

```bash
adb push frida-server /data/local/tmp/ && adb shell su -c "/data/local/tmp/frida-server &"
frida -U -f com.qidian.QDReader -l fluter_ssl.js --no-pause
```

### 方案 D：不改环境，直接看数据（最快出结论）

如果目的只是判断完成度、不需要长期抓包，可以跳过抓包：

1. 用任意能读应用私有目录的方式（root），直接读 MMKV / SharedPreferences 里缓存的福利页面 JSON
2. 或者看 `welfarecenter` 响应缓存：`/data/data/com.qidian.QDReader/files/`、`shared_prefs/`、`mmkv/`

起点用 `libmmkv.so` + `com.tencent.mmkv`，页面数据大概率缓存在 MMKV 里：

```bash
adb shell su -c "ls -la /data/data/com.qidian.QDReader/files/mmkv/"
adb shell su -c "strings /data/data/com.qidian.QDReader/files/mmkv/*.default  | grep -o '{\"taskList\".*' | head -c 4000"
```

---

## 七、验证步骤（确认上面结论）

1. 开代理，打开书城 → 有请求；点进福利中心 → 请求断流 = Flutter 直连，结论成立
2. 过滤关键词：`welfarecenter`、`freshman`、`getfreshmanwelfarecenterpage`
3. 如果连 TLS 握手都看不到（不是 502/超时），说明路由都没接管，先做 VPN 模式
4. 如果看到 CONNECT 但立刻 RST / 握手失败，说明路由通了、证书没过，处理 CA

---

## 八、给你（Mika）的直接建议

- 不要再找 "getTaskProgress" 之类的接口，**它不存在**
- 要拿完成度，抓 `POST /argus/api/v2/freshman/getfreshmanwelfarecenterpage`，读 `data.taskList[].process / total / isFinished / isReceived`
- 行为上报走 `/argus/api/v1/freshman/task/calendarreport`，是**写**不是**读**，别拿它当查询接口
- 书城首页那张新人卡片走的是 Java 层（`MorphingFreshmanWelfareCard`），能抓到，但数据和完整福利中心不是同一份，别混用

---

## 附：分析产物

| 文件 | 说明 |
|---|---|
| `apk/base.apk` | 原始样本 |
| `dex/classes*.dex` | 11 个 dex 解包 |
| `lib/lib/arm64-v8a/libapp.so` | Flutter AOT 快照 |
| `dex_fields.py` / `dex_fields2.py` | dex 类字段解析器 |
| `dex_strings.py` | dex 字符串池导出器 |
| `s4.txt` / `s7.txt` | classes4 / classes7 字符串全量 |
| `scan_boringssl.py` | BoringSSL 指令特征扫描器 |

---

# 附录 B：Mika 历史日志排查结果

排查范围：`D:/Mika/output/`、`.work/`、`docs/`、`eval/`，全量 grep `qidian` / `WelfareFlutter` / `welfare` / `福利`。

## B.1 有 Mika 流程日志

`output/atlas-device/<实验名>/run/events.jsonl` 是 Mika agent 的完整执行轨迹，14 种事件类型：

| 事件 type | 含义 |
|---|---|
| `task_started` | 任务开始（goal / model / stepLimit） |
| `prompt_version` | prompt 哈希 |
| `model_reply` | 模型返回（text / tool / argsJson / token / latency） |
| `tool_result` | 工具执行结果（resultJson / durationMs / callId） |
| `activity` | 观察/动作（kind / label / target / effect） |
| `observed` / `media` / `state` | 屏幕观察、截图、应用状态 |
| `compressed` | 上下文压缩 |
| `sources` / `criteria` | 引用来源、判据 |
| `model_calls` / `outcome` | 汇总统计、最终结果 |

同目录还有 `screens/*.jpg` 逐步截图、`run.json`、`metrics.json`、`scores.jsonl`、`summary.md`。

## B.2 有 eBPF 抓包日志

`.work/boss/cap_job.log`、`cap_uid.log` 是 eBPF uprobe 的运行时日志，头部是可读挂载信息：

```
INFO:ssl-offsets /apex/com.android.conscrypt/lib64/libssl.so source=dynsym write=0x71470 read=0x71044 sni=0x74394
ATTACHED:/apex/com.android.conscrypt/lib64/libssl.so
INFO:ssl-offsets /system/lib64/libssl.so source=dynsym write=0x6f594 read=0x6f190 sni=0x723f8
READY:/apex/com.android.conscrypt/lib64/libssl.so
```

后面紧跟二进制事件流（magic `AAUE`），`cap2_requests.txt` 是解析出的请求清单。
`cap*.bin` 是原始事件流，`hook_out.jsonl` 是 frida 抓的（不是 eBPF）。

## B.3 没有任何起点记录

| 关键词 | 命中 |
|---|---|
| `WelfareFlutter` | **0** |
| `welfare` / `福利` | **0** |
| `welfarecenter` / `freshman` | **0** |
| `qidian` | 仅出现在系统 logcat 的桌面推荐、WindowManager ActivityRecord、应用列表 JSON 中，**没有任何 HTTP 流量记录** |

`output/atlas-device/` 下 20 个实验目录全是 BOSS 直聘的 `boss-two-jobs` 任务（boss8 ~ boss18），没有起点。

## B.4 关键：Mika 当前抓不了 Flutter

`aau-client/src/main/cpp/aau_ebpf_loader.c:276`：

```c
static int path_looks_like_ssl_or_cronet(const char *path) {
    return ascii_strcasestr_contains(path, "cronet") ||
           ascii_strcasestr_contains(path, "boringssl") ||
           ascii_strcasestr_contains(path, "libssl") ||
           ascii_strcasestr_contains(path, "ttnet");
}
```

`libflutter.so` 四个都不匹配，`resolve_stripped_boringssl_offsets()` 第 440 行直接 `return -1`。
但函数内部第 467 行本来就有 `ssl/ssl_lib.cc` 的软检测分支，说明设计上留了这条路，只是被外层 gate 挡住。

### 偏移反查的常量匹配也过不了

Mika 要求同一个函数窗口内同时命中 5 个常量：

| 常量 | 含义 | libflutter.so 实测 |
|---|---|---|
| `MOV W2,#226` | `0x52801c42` | **2 处** |
| `MOV W2,#66` | `0x52800842` | **2 处** |
| `MOV W0,#16` | `0x52800200` | 318 处 |
| `MOV W2,#111` | `0x52800de2` | **1 处** |
| `MOV W2,#215` | `0x52801ae2` | **0 处** |

`MOV W2,#215` 零命中 → 即便放开白名单，判定条件依然不成立。Flutter 内置的 BoringSSL 版本与起点/BOSS 用的那套 reason 码不同。
`ssl/ssl_lib.cc` 字符串确认存在（`.text` offset `0x42b4c0`，vaddr `0x43b4c0`，size `0x5899a0`），`MOV W2,#226` 的首个落点在 vaddr `0x677e84`，紧邻 `ERR_put_error` 的调用序列。

### 要支持 Flutter，改两处

1. `path_looks_like_ssl_or_cronet()` 增加 `flutter` 分支（或直接让 `has_ssl_lib_str` 通过 gate）
2. 五常量判定改成"`MOV W2,#226` + `MOV W0,#16` 组合"或按 `ssl/ssl_lib.cc` 的字符串引用反查（`R_AARCH64_RELATIVE` 那套，见 `find_ssl_offsets.py`），不依赖固定 reason 码

改完之后 eBPF 能直接挂 `libflutter.so` 的 `SSL_read`/`SSL_write`，**不需要证书、不需要代理**，Flutter 明文直接进 Mika 的事件流。

---

# 附录 C：设备实测更正（2026-11-21）

附录 B 的结论有一半是错的。在 PLK110 上做完动态验证后，更正如下。

## C.1 福利中心不是 Flutter 页

「我的 → 福利中心」实际打开的是：

```
com.qidian.QDReader/.ui.activity.QDBrowserActivity
页面: QdReader://h5.if.qidian.com/new/welfareCenter?_viewmode=0&_smode=1
```

页面由 WebView 承载（进程内加载 WebView APK 里的 chromium，maps 中显示为
`/data/app/~~.../com.google.android.webview-.../base.apk`，没有独立的 `.so` 路径）。
`WelfareFlutterActivity` / `newUserWelfareMainPageV2` 是新人福利那条线，不是这里。

**所以 H5 页的 XHR 既不经过 `libflutter.so`，也不经过系统 `libssl.so`**，而是走 WebView
内嵌的 BoringSSL —— 这才是 Mika 抓不到它自己接口的真正原因，跟 Flutter 无关。

## C.2 起点 Dart 层不走 dart:io 的 TLS

把除 `libflutter.so` 以外的 SSL 库全部故意配错偏移后，在书城/书架做下拉刷新，
`libflutter.so` 上 **0 事件**；同一操作在放开 `libssl.so` 后立刻抓到
`/argus/api/v2/bookshelf/refresh`、`/argus/api/v2/ploy/getactivitylist` 等。

结论：起点的 App API 走的是 App 自带的 `lib/arm64/libssl.so`（OpenSSL），
Mika 本来就能抓。Flutter 那条线对起点没有收益。

## C.3 `/argus/api/v2/checkin/simpleinfo` 只能代表签到，**不能**代表福利中心完成度

> 2026-11-21 更正：本节初版写成「完成态接口」，是错的。该接口只覆盖
> 签到 + 当日阅读时长的聚合值，**不包含**福利中心里多轮任务的逐轮状态。
> 真正的多轮任务数据见 C.6。

这个是 Mika 能抓到的，响应里带签到与阅读时长（实测抓包，`qd2.bin`）：

```json
{"Data":{
  "HasCheckIn":1,              // 今日是否已签到
  "NoBrokenTime":319,          // 连续签到天数（与页面「已连续签到319天」一致）
  "MaxCount":369,              // 累计签到上限
  "CurrentReadingTime":1073,   // 今日阅读时长（秒）
  "ReadTimeRewardRemain":1,    // 阅读奖励剩余可领
  "BtnType":4,"BtnTypeTxt":"今日福利",
  "BtnTypeV2":6,"BtnTypeTxtV2":"领福利",
  "BtnUnCheckInTxt":"签到",
  "ActionUrl":"QdReader://h5.if.qidian.com/new/welfareCenter?_viewmode=0&_smode=1",
  "LotteryCount":1
}}
```

判定方式：`HasCheckIn==1` 已签到；`NoBrokenTime` 连签天数；`ReadTimeRewardRemain>0`
说明还有阅读奖励未领。但这只是**汇总态**：它说不清「三轮里哪几轮已满、哪几轮已领」，
也完全不含视频任务、宝箱、限时福利这些模块。把它当福利中心完成度会误判。

## C.3.1 真正的多轮任务模型（来自 H5 前端代码）

福利中心页面 `https://h5.if.qidian.com/new/welfareCenter/` 的
`page_common_welfareCenter_*.js` 里，页面数据是一个模块集合：

| 字段 | 含义 |
|---|---|
| `dailyTaskModule` / `dailyTaskModule2` | 日常任务两组（`NormalTaskType` 1 / 2），服务端可指定默认展示哪组 |
| `readingPageTaskModule` | 阅读页任务模块 |
| `treasureBox` | 宝箱任务 |
| `WeekRead` | 周阅读任务（`Time` 分钟阈值 + `TotalReadTime`） |
| `TaskModule` | 任务模块（`TaskList`） |
| `maxShowCount` / `serverSetTaskType` / `diffAmount` / `popFrequency` | 展示与策略参数 |

每个模块的 `TaskList[]` 单项结构（JS 里实际读取的字段）：

```
TaskId        long     任务 id
TaskRawId     string   任务原始 id
Status        int      0=去完成 1=领积分 2=已领取 3=(已完成态)
Process       int      已完成轮次
Total         int      总轮次
Title/SubTitle/Desc    文案
```

完成度判定就是两段：

```js
// 模块级：已推进到第几轮
progress = module.Process || module.TaskList.filter(t => t.IsFinished).length
// 任务级：还有没有下一轮
hasNext  = !(task.Total <= task.Process)
// 全部领完
allDone  = module.TaskList.every(t => t.Status === 3)
```

组件里还有 `steper-item_<n>` 的阶梯导航（`data.TaskList[Process]` 定位当前轮），
这就是「好几轮」在 UI 上的实现。所以单一聚合字段无法替代，必须拿 `TaskList`。

## C.3.2 这些接口现在抓不到，原因是签名

福利中心的数据接口（H5 侧实际请求）：

```
/argus/api/v2/readtime/readpage                   今日阅读 + RewardList 多轮
/argus/api/v3/checkin/getcurrentweekcheckininfo   本周签到
/argus/api/v3/checkin/detail                      签到详情
/argus/api/v3/checkin/checkinexchangepage         兑换页
/argus/api/v1/adv/getadvlistbatch?positions=...   广告任务
/argus/api/v2/video/adv/mainPage                  视频任务
```

三条实测结论：

1. **直接重放（带真实 cookie）→ 402**
   `{"Result":-3,"Message":"签名错误"}`。cookie 有效（`getlogininfo` 返回 200，
   `UserId=439985378`），缺的是签名头。
2. **签名由原生桥提供**。`request_*.js` 的拦截器里：
   `getEncryptSign()` 产出 `SDKSign/borgus/tstamp/helios`，
   `initIbex()` 走 `jssdk.invokeClient("app","riskControlInfo")` 取 `ibex`，
   support 声明 `android:"7.9.380"` —— **必须有 App 才能算**。
3. **桌面浏览器跑整页也过不去**。注入 cookie + App UA 打开同一页面，
   登录态正常、页面渲染出「福利中心 / 本周收益 / 积分商城」，
   但所有数据接口仍是 402。说明不是 UA 或 cookie 问题，是 `ibex` 缺失。

因此：**目前 Mika 拿不到福利中心的任务完成度**。`checkin/simpleinfo` 只能当
「签到 / 阅读奖励」的粗粒度信号，不能当多轮任务完成度。

## C.3.3 要拿到，只有三条路

| 方案 | 做法 | 代价 |
|---|---|---|
| A. 抓 WebView | 代理 + 证书（或 frida 绕校验），让 WebView 的 XHR 明文可读 | 一次性，能拿到全部 H5 接口；Android 14+ 需处理 conscrypt APEX 或 frida 注入 |
| B. 改 Mika | uprobe 打到 WebView APK 内的 `libwebviewchromium.so`（APK 内偏移 + ELF 偏移叠加） | 通用但要动扫描器，Chromium 符号全 stripped，需特征定位 |
| C. 读页面 | `uiautomator dump` 读渲染后的 DOM | 只能人工/半自动，拿不到逐轮 `Process/Total` |

## C.4 对 Mika 的改动（已编译、已上机）

`aau/aau-client/src/main/cpp/aau_ebpf_loader.c`

1. **去掉库名白名单**。`path_looks_like_ssl_or_cronet()` 原来只认
   cronet/boringssl/libssl/ttnet，名字不在名单里的库连尝试机会都没有。改为内容判定：
   只要能读到 `ssl/ssl_lib.cc` 就参与解析。
2. **新增实验性 `--boringssl-thunks`**。新版本 BoringSSL 把 reason 码换成了冷代码
   thunk（`adrp/add ssl_lib.cc; mov w0,#16; mov w1,#<func>; ret`），五常量判定必然失效。
   新路径按 thunk 调用点反查 `ssl_lib.cc` 内的函数，取相邻一对、错误分支多者为写。
   在起点/同程的 libflutter.so 上都定位到 `write=0x6be8c4 read=0x6befa0`。

   **默认关闭**：该启发式只能证明「这两个函数在 ssl_lib.cc 里」，读/写归属仍是推断。
   实测中曾把非 SSL 函数挂上（抓到 4096 字节全零缓冲），所以不放默认路径。
   显式开启方式：

   ```
   libaau_ebpf.so --uid <uid> --boringssl-thunks
   ```

## C.5 证据文件

| 文件 | 说明 |
|---|---|
| `qd2.bin` | 起点抓包原始 AAUE 流（含 checkin/simpleinfo 完整响应） |
| `qd5.bin` | 阅读页抓包（验证阅读页不暴露多轮任务列表） |
| `tc.bin` / `tc3.bin` | 同程 Flutter-only 对照 |
| `parse_aaue.py` | AAUE 帧解析器 |
| `flutter_ssl_offsets.py` | BoringSSL 函数/字符串引用定位工具 |
| `wc_cookies.db` | 起点 WebView Cookie 库（明文，用于验证重放）。**含登录凭证，不入库，仅本地保留** |
| `mitm/page_common_welfareCenter.js` | 福利中心前端 chunk，C.3.1 的字段依据 |
| `mitm/request_chunk.js` | 请求拦截器，C.3.2 的签名依据 |

> 设备状态：本轮实验设置过的 `http_proxy` 已删除，frida-server 已停止，
> WebView 与系统配置均未修改（未写入系统 CA、未改 mount namespace）。

# 附录 D：WebView H5 抓包已打通（2026-11-21 实证）

附录 C 的结论是「福利中心是 WebView H5，`libflutter.so` 和系统 `libssl.so` 都不经过它，
所以 Mika 抓不到」。本附录给出**打通后的实证**：不需要装 CA、不需要代理，
用 eBPF uprobe 直接打在 WebView 内嵌的 Chromium 上，即可拿到完整明文。

## D.1 为什么之前一定抓不到

WebView（Trichrome）把 Chromium 编译成 **APK 内的内嵌 .so**：

```
lib/arm64-v8a/libwebviewchromium.so   compress=STORED  header_off=0x00dd9830
                                      data_off=0x00ddc000  size=0x09c50a48 (156MB)
```

`extractNativeLibs=false`，Android 的 linker 直接从 `base.apk` 里 mmap 这一段，
所以进程 maps 里看到的是：

```
6f06200000-6f0faed000 r-xp 00ddc000  .../com.google.android.webview/base.apk
```

也就是说，**代码页的 inode 是 base.apk，不是 .so**。
eBPF uprobe 的 offset 语义是「相对被映射 inode 的绝对文件偏移」，
所以必须用：

```
uprobe_offset = APK 内成员数据起点 + .so 内部文件偏移
```

之前按 `.so` 文件偏移下探针，内核找不到对应 VMA，自然一条都抓不到。
这不是「WebView 抓不到」，而是「偏移算错了」。

## D.2 定位到的函数（Chromium 157.0.8103.50 / arm64）

`libwebviewchromium.so` 的 `.dynsym` 只有 853 个符号、**没有任何 `SSL_*`**，
所以走静态定位：

- `SSL_read` / `SSL_write` 都通过 `ssl->method` 虚表派发；
- 用 `ssl/ssl_lib.cc` 的 `ERR_put_error(ERR_LIB_SSL=16, ...)` 调用点回溯函数入口；
- 再用「调用点后紧跟 `SSL_get_error`」确认是 Chromium net 层在调用。

结果：

| 函数 | .so 内偏移 | APK 内绝对偏移 | 入口签名（前 4 条指令） |
|---|---|---|---|
| `SSL_write` | `0x3272a14` | `0x404ea14` | `d503233f d10143ff a9017bfd f90013f7` |
| `SSL_read` | `0x32784dc` | `0x40544dc` | `d503233f a9be7bfd a9014ff4 910003fd` |
| `SSL_peek`（参考） | `0x3224dac` | `0x4000dac` | — |

`SSL_read` 的实现是「先 `SSL_peek` 拷贝 `s3->rrec.data`，再前移 `rrec.data/length` 消费」，
所以退出探针读到的就是应用层明文。

## D.3 实测抓到的接口（福利中心一次打开）

```
GET /new/welfareCenter/?_viewmode=0&_smode=1        → 200, gzip, 573696 字节 HTML
GET /argus/api/v2/video/adv/mainPage                → 200, gzip, 19939 字节 JSON
GET /argus/api/v3/checkin/getcurrentweekcheckininfo → 200, gzip, 3732 字节 JSON
GET /argus/api/v1/user/getlogininfo                 → 200, 102 字节
GET /argus/api/v1/adv/getadvlistbatch?positions=newcheckin
GET /argus/api/v2/video/adv/mainPageDialog
GET /argus/api/v3/checkin/checkinexchangepage
```

`mainPage` 的 `Referer` 正是 `https://h5.if.qidian.com/new/welfareCenter/`。

## D.4 完成情况就在 mainPage 的响应里（实证）

`GET /argus/api/v2/video/adv/mainPage` 返回的 JSON（已 gzip 解压）中：

```json
"DailyBenefitModule": {
  "Count": 46,
  "Desc": "完成广告任务，多重好礼等你解锁",
  "Process": 1,
  "TaskList": [
    {"Desc":"10-3点订阅券","IsFinished":1,"IsReceived":0,"TaskRawId":"T2025090203"},
    {"Desc":"惊喜礼包",   "IsFinished":0,"IsReceived":0},
    {"Desc":"10点章节卡", "IsFinished":0,"IsReceived":0},
    {"Desc":"惊喜礼包",   "IsFinished":0,"IsReceived":0},
    {"Desc":"10-3点订阅券","IsFinished":0,"IsReceived":0},
    {"Desc":"惊喜礼包",   "IsFinished":0,"IsReceived":0},
    {"Desc":"10点章节卡", "IsFinished":0,"IsReceived":0},
    {"Desc":"惊喜礼包",   "IsFinished":0,"IsReceived":0},
    {"Desc":"20点章节卡", "IsFinished":0,"IsReceived":0}
  ]
}
```

同响应里还有 `ActivityCenterModule`、`BaizeModule`、`CharityModule`、`WeekRead` 等模块。

这直接回答了「激励任务是好几轮」的问题。可以确证的部分：

- **一轮 = 一个 `TaskList` 条目**，`IsFinished` 标记该轮是否达成；
- 本例 `TaskList` 有 **9 条**（9 轮），只有第 1 条 `IsFinished=1`；
- 模块级 `Process = 1`，与「`TaskList` 中 `IsFinished=1` 的条数 = 1」一致
  （**观测到的一致性，不是因果证明**）。

因此判断完成度应该逐条读 `TaskList[].IsFinished`，而不是看签到天数或某个单一的「完成度」字段。

> **一处更正**：本节早先版本只贴了前 3 条，并写成「三条就是三轮」。
> 那是截取响应片段时把前 1500 字符当成了全量，实际是 9 条。
> 结论方向没变（仍然是逐条读 `IsFinished`），但「几轮」的数量必须以完整
> `TaskList` 长度为准——这恰好也是「不能用单一字段概括多轮任务」的又一个例子。

`Count`（本例 46）与 `Total` 的语义**尚未验证**，不要直接当作「总轮数」使用：
`TaskList` 只有 9 条而 `Count=46`，两者对不上，说明 `Count` 至少不是「当前展示的轮数」。
`Total=0` 只说明该条目不按数值进度计算。

## D.5 aau 的治理改动

`aau-client/src/main/cpp/aau_ebpf_loader.c`：

1. **APK 内嵌 so 自动识别**（`resolve_webview_apk_offsets`）
   - 解析 zip 中央目录 + local header，算出成员数据起点；
   - 用 `pread` 校验 ELF 头与函数入口签名，不映射 156MB；
   - 输出 APK 内绝对偏移，直接交给现有 `attach_uprobe_pmu_or_tracefs`。
2. **偏移表带签名校验**（`g_webview_profiles`）
   - 版本不匹配时打印 `WARN:webview-profile-miss` 并放弃，绝不盲挂。
3. **只读模式**：`--ssl-offsets` 的 `write` 允许为 0（只挂 read 方向）。
   这是排障用的口子，**不要用于正式抓包**：只挂一半会让方向标记失去参照，
   参见 D.8 那次把 `GET` 标成 `dir=2` 的实测。

对 `base.apk` 传 `--lib` 即可全自动，无需任何手工偏移：

```
libaau_ebpf.so --uid 10434 \
  --lib /data/app/.../com.google.android.webview-.../base.apk
```

输出：

```
INFO:webview-member lib/arm64-v8a/libwebviewchromium.so off=0xddc000 size=0x9c50a48
INFO:ssl-offsets .../base.apk source=webview_apk write=0x404ea14 read=0x40544dc sni=0x0
ATTACHED:.../base.apk
```

## D.6 消费端：帧不能直接当文本读

`AAUE` 帧是「一次 `SSL_write` 调用」或「一次 `SSL_read` 返回」的切片，
一条 HTTP 响应会被切成几十个帧，而且 body 普遍是 `Transfer-Encoding: chunked` + `Content-Encoding: gzip`。
直接把帧拼起来看，得到的是 `\x94\xbf\xaa...` 这类二进制。

为此新增通用解码器 **`tools/aaue_http.py`**（无第三方依赖，brotli 可选）：

```bash
python tools/aaue_http.py app.aaue                  # 摘要表
python tools/aaue_http.py app.aaue --dump out/      # 导出每个响应体
python tools/aaue_http.py app.aaue --json out.ndjson
python tools/aaue_http.py app.aaue --body 23        # 打印第 23 条的完整 body
```

做的事：

1. 按 `(pid, ssl_ptr)` 分连接、双向重组；
2. 解析 HTTP/1.x 请求行与状态行，按 `Content-Length` / `chunked` 定界；
3. de-chunk → gunzip / inflate / brotli；
4. 同连接按 FIFO 把请求与响应配对（HTTP/1.1 无 pipeline 时严格有序）；
5. 帧序列错位时**重同步**：向后找「magic + 合理 pid/uid/direction/length」的候选头，
   并报告跳过了多少杂散字节——手工 `cp` 拼接过的抓包文件就靠这个救回来。

`--json` 输出的每条记录含 `method/target/host/status/encoding/raw_len/len/content_type`，
不解码 body，便于二次处理。

## D.7 双载体回归（2026-11-21 11:26 实测）

同一天对两类 TLS 载体各抓一次，用同一个解码器解析：

| 载体 | 探针目标 | 抓包 | 帧数 | 交换数 | 结果 |
|---|---|---|---|---|---|
| WebView H5 | `base.apk` 内嵌 `libwebviewchromium.so` | `wv/wv3.bin` | 259 | 46 | 全部解出 |
| App 原生 API | app 自带 `libssl.so`（dynsym 命中 `write=0x3d364 read=0x3d2b8`） | `app1.bin` | 88 | 20 | 20 个响应全部配对，0 解码失败；另有 7 个请求在截止时未返回 |

`app1.bin` 是「退出 H5 → 回到 `MainGroupActivity`」这一路的原生请求，解出：

```
POST /argus/api/v2/bookshelf/refresh              200     252 B
GET  /argus/api/v1/bookshelf/getHighLevelBookReadInfo  200  1502 → 7004 B
GET  /argus/api/v3/user/getaccountpage            200    2958 → 8645 B
GET  /argus/api/v2/checkin/simpleinfo             200     794 B
GET  /argus/api/v1/reddot/getdot、message/totalcount、freshman/* ...
```

`getaccountpage` 解压后含 `"Growth":6313,"Level":3,"LevelName":"玉衡"`，内容真实可核。

这条回归同时把附录 C 的一个说法落实了：**App 原生 API 确实走 app 自带的 `libssl.so`**。
在此之前，这个结论只是从 `qd*.bin` 里的 SSL 指针高位推断的；现在是挂上探针、
退出 H5、看到 88 帧真实流量的实证。

## D.8 方向标记的陷阱（实测踩到过）

`direction` 是**探针的语义方向**，不是数据本身的流向：

```c
#define DIRECTION_OUTBOUND_WRITE 1U   // uprobe/ssl_write，入口取 parm2/parm3
#define DIRECTION_INBOUND_READ   2U   // uprobe/ssl_read_enter + uretprobe，按返回值取长
```

所以一旦把 `SSL_write` 的偏移填进 `write` 位、`SSL_read` 填进 `read` 位填反了，
帧还是会有，但**方向标记整体反向**，下游会把请求当响应。

这不是假设：`wv1.bin` 就是 `--ssl-offsets base.apk:0:0x404ea14`（只有 read 位、且填的是
`SSL_write` 的地址）抓出来的，结果 `GET /new/welfareCenter` 被标成 `dir=2`。
发现方向不对才改用双探针。

因此 `aaue_http.py` 内置自校验：若入方向流以请求行开头、或出方向流以状态行开头，
直接打印 `WARN: ... write/read probes look swapped`，而不是安静地输出错位数据。

## D.9 残留限制

- **WebView 升级即失效**：偏移表按 Chromium `157.0.8103.50` 写死，靠入口 4 条指令签名兜底。
  升级后签名不匹配会打印 `WARN:webview-profile-miss` 并放弃挂载，绝不盲挂错函数；
  需要重新静态定位并补一条 profile。
- **`orphan requests`**：抓包开始时已在途的请求，其响应会被抓到但请求不在文件里，
  配对后 `method/host` 为 `null`。这是截断的必然结果，不是解析错误。
- **抓包文件不要手工拼接**：`qd1/qd2/qd5.bin` 是早期 `cp` 合并的产物，帧边界已被破坏，
  解码器只能靠重同步抢救出部分连接。新抓包请一次写一个文件。
- 未做 **HTTP/2 HPACK** 头解析与 **QUIC** 路径。实测 `h5.if.qidian.com` 走 h1.1，当前够用；
  若后续遇到 h2 站点，帧仍能抓到，但需要补 HPACK 解码。

## D.10 证据文件

| 文件 | 说明 |
|---|---|
| `tools/aaue_http.py` | AAUE → HTTP 解码器（重组 / de-chunk / gunzip / 配对 / 重同步） |
| `app1.bin` | App 原生 API 抓包（自带 `libssl.so`，88 帧 20 交换） |
| `app1.ndjson` | 上述抓包的机器可读记录 |
| `wv/base.apk` | 设备上 `com.google.android.webview` 157.0.8103.50 原包 |
| `wv/libwebviewchromium.so` | 从 APK `0xddc000` 提取的内嵌库 |
| `wv/wv_ssl_offsets.py` | 扫 `ssl/ssl_lib.cc` 的 ADRP+ADD 引用点 |
| `wv/wv_group.py` | 按函数分组错误点 |
| `wv/wv_geterr.py` | 用 `SSL_get_error` 调用点反查 `SSL_read`/`SSL_write` |
| `wv/wv_family.py` | 读写函数族序言指纹 |
| `wv/wv1.bin` ~ `wv3.bin` | 三次 WebView 实测 AAUE 流（wv3 为自动模式回归） |
| `wv/decode_wv.py` | 解码器的早期草稿，已被 `tools/aaue_http.py` 取代 |


# 附录 E：手机端 Mika 抓不到——三个真实缺陷（2026-11-21 实证）

附录 D 证明的是「手工在 `/data/local/tmp` 跑 loader、显式传 `--lib <apk>`」可行。
但在手机端 Mika 里执行抓取时仍然拿不到福利中心的数据。逐个排查后，是三个独立问题叠加。

## E.1 缺陷一：自动扫描只认 `.so`，APK 内嵌的 Chromium 被整条过滤

Mika 启动 helper 用的是 `--uid` + `--lib-dir`，不是 `--lib <apk>`：

```
libaau_ebpf.so --uid 10434 --lib-dir <目标app>/lib/arm64 \
  --ssl-offsets libssl.so:0x3d364:0x3d2b8:0x0
```

于是走的是自动扫描。`scan_and_attach_proc_maps` 里原作只接受以 `.so` 结尾的映射：

```c
if (slen < 3 || strcmp(slash + slen - 3, ".so") != 0) continue;
```

而 WebView 代码页的 inode 是 `base.apk`（见 D.1），于是 `r-xp ... base.apk` 这一行
在第一步就被丢掉了。手工路径能用、Mika 路径不能用，差别就在这里。

修复：除了 `.so`，也接受**可执行段**的 `.apk`。

```c
int is_so = (slen > 3 && strcmp(slash + slen - 3, ".so") == 0);
int is_exec_apk = 0;
if (!is_so && slen > 4 && strcmp(slash + slen - 4, ".apk") == 0) {
    char *perms = strchr(line, ' ');
    while (perms && *perms == ' ') perms++;
    /* perms 形如 "r-xp"，第 3 个字符才是可执行位 */
    is_exec_apk = (perms && strlen(perms) >= 4 && perms[2] == 'x');
}
```

用「可执行段」做筛子，是因为普通 APK 的 dex/资源都是 `r--p`、`r--s`，
只有真正承载 native 代码的段才是 `r-xp`，这样不会把每个 APK 都拿去做 ELF 解析。

> 踩过的坑：第一版把可执行位写成 `perms[3] == 'x'`，而 `perms` 是 `"r-xp"`，
> index 3 是 `p`/`s`，条件永远为假——日志看起来一切正常，就是不挂 APK。

## E.2 缺陷二：WebView 与抓包进程的启动顺序——首屏请求会漏

即使修好 E.1，`/proc/<pid>/maps` 也要等目标 app **真的用上 WebView** 才有那条映射。
Mika 的用法通常是「先开始抓包，再去操作 app」，于是：

1. 抓包启动时 chromium 还没映射 → 扫不到；
2. 用户打开 H5，首屏那批 XHR 在 2 秒一次的重扫挂上之前就发完了。

修复：主动向系统查当前 WebView provider，把探针**预挂**在它的 APK 上，
等 chromium 一被映射立即生效，不依赖「先出现在 maps 里」。

```c
popen("/system/bin/dumpsys webviewupdate")   // Current WebView package (name, version): (pkg, ver)
popen("/system/bin/pm path <pkg>")           // package:/data/app/.../base.apk
try_attach_library(apk, ...)
```

日志新增一行：

```
INFO:webview-prewarm /data/app/.../com.google.android.webview-.../base.apk (com.google.android.webview)
```

> 踩过的坑：解析包名时先写成「取第一个 `(` 到逗号」，而该行是
> `Current WebView package (name, version): (com.google..., 157...)`，
> 第一个括号里是字段说明，取到的包名是字面量 `name`。改成在 `"): ("` 之后取。

## E.3 缺陷三：插件规则按路径子串匹配，把别的库挂到了错误地址

Mika 的 `aau_capture_plugins.json` 会传 `--ssl-offsets libssl.so:0x3d364:0x3d2b8`。
原实现里规则**先于**符号表判定，且匹配方式是 `strstr(path, "libssl.so")`，
于是一条起点专用规则同时命中了：

```
/apex/com.android.conscrypt/lib64/libssl.so  → 用了 0x3d364（真实 0x71470）
/system/lib64/libssl.so                      → 用了 0x3d364（真实 0x6f594）
```

这会把探针挂到完全无关的代码上——要么抓不到，要么抓到垃圾。修复后顺序改为
**WebView APK → 符号表 → 显式规则 → 启发式**，规则只作为「没有符号表」时的兜底：

```
INFO:ssl-offsets /apex/com.android.conscrypt/lib64/libssl.so source=dynsym write=0x71470 read=0x71044
INFO:ssl-offsets /system/lib64/libssl.so                  source=dynsym write=0x6f594 read=0x6f190
```

## E.4 还有一个不是抓包问题的问题：H5 缓存会让 mainPage 返回空壳

排查中发现的重要现象：同一个账号、同一页面，两次抓取结果差别极大。

| 场景 | 页面 HTML | mainPage | TaskList |
|---|---|---|---|
| WebView HTTP 缓存命中 | `304` | `73` 字节 `{"Data":{"ConfigId":0,"DialogType":0,"TaskId":0}}` | 无 |
| 清缓存后全新加载 | `200`，573696 字节 | `19939` 字节 | 9 条 |

也就是说，**缓存的 H5 页面不会重新拉取完整聚合数据**，`mainPage` 只回一个空壳。
页面上的任务卡片此时显示的是上一次的 DOM/数据，看起来「有内容」，但接口层面什么都没有。

排查时清掉的是（可重建的纯缓存，不涉及登录态）：

```
/data/data/com.qidian.QDReader/cache/WebView/Default/HTTP Cache   (497MB)
```

这条对使用者很重要：**如果 Mika 抓到了 mainPage 却是 73 字节空壳，先清 WebView 缓存再试**，
不要误判成抓包失败。

## E.5 修复后的端到端结果（手机端 Mika 参数，缓存已清）

Mika 实际下发的完整参数（从设备 `ps` 里读到的，含插件规则）：

```
libaau_ebpf.so --uid 10434 --lib-dir <qidian>/lib/arm64 \
  --ssl-offsets libssl.so:0x3d364:0x3d2b8:0x0 \
  --cronet-abi 0x20,0x10,0 --cronet-slots 3,4,5 \
  --cronet-sig '(Ljava/lang/Object;JLjava/lang/String;IZZZIZIIJ)J'
```

挂载结果：

```
INFO:ssl-offsets .../qidian/lib/arm64/libssl.so source=dynsym write=0x3d364 read=0x3d2b8
INFO:ssl-offsets /apex/com.android.conscrypt/lib64/libssl.so source=dynsym write=0x71470 read=0x71044
INFO:ssl-offsets /system/lib64/libssl.so source=dynsym write=0x6f594 read=0x6f190
INFO:webview-prewarm .../com.google.android.webview-.../base.apk (com.google.android.webview)
INFO:ssl-offsets .../base.apk source=webview_apk write=0x404ea14 read=0x40544dc
ATTACHED:.../base.apk
```

抓到的 H5 交换（`mika6.bin`，1129 帧 / 85 连接）：

```
GET /new/welfareCenter/?_viewmode=0&_smode=1        200  92314 → 573696 (HTML)
GET /argus/api/v2/video/adv/mainPage                200   5389 →  19939 (JSON)
GET /argus/api/v3/checkin/getcurrentweekcheckininfo 200    945 →   3732
GET /argus/api/v2/readtime/readpage                 200   1139 →   2703
GET /argus/api/v1/user/getlogininfo                 200    102 →    102
GET /argus/api/v2/video/adv/mainPageDialog          200     73 →     73
```

`mainPage` 解出的任务完成情况（与本轮 E.4 中「清缓存后」一致）：

```json
"DailyBenefitModule": {"Process": 1, "Count": 46, "TaskList": [ ... 9 条 ... ]}
```

9 条逐轮状态：第 1 条 `IsFinished=1`（10-3点订阅券），其余 8 条为 0。

## E.6 本轮改动清单

`aau-client/src/main/cpp/aau_ebpf_loader.c`：

1. `scan_and_attach_proc_maps` 接受可执行段 `.apk`（E.1）；
2. 新增 `preattach_webview_provider`，启动时预挂当前 WebView provider（E.2）；
3. `inspect_library_offsets` 判定顺序改为 符号表 > 显式规则（E.3）；
4. 新增 `g_rejected_paths` 负缓存：判定过且无 TLS 的路径不再每 2 秒重解析一次
   （maps 每 2 秒重扫，`libsqlite.so` 这类常驻库原本会被反复读文件、扫字符串）。

Mika 侧无需改代码，`--lib-dir` 的行为不变。

## E.7 证据文件

| 文件 | 说明 |
|---|---|
| `mika1.bin` | 修复前（`.so` 过滤未改）Mika 模式抓取：H5 只有 6 个接口，无 base.apk |
| `mika5.bin` | 清缓存 + 预挂载：mainPage 19939 字节，9 条任务 |
| `mika6.bin` | 完整 Mika 参数（含插件规则）+ 规则修复后回归，结果同上 |
| `mika6_dump/` | 上述抓包的逐响应解码结果 |
