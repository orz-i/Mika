# AAU 端侧视觉一期：纯图标定位试点

默认构建不打包模型或运行时。试点（CPU）每个 APK 只选一个后端；工具名称、模型输入输出、阈值、裁剪和坐标换算都相同。

```sh
# 原版保持不变
./gradlew :app:assembleDebug

# LiteRT 2.3.2 独立版，不用 Google Play 服务
./gradlew -PdevInstall -PaauVisionBackend=litert :app:assembleDebug

# MNN 3.6.2：先下载并解压 https://github.com/alibaba/MNN/archive/refs/tags/3.6.2.zip
# 使用 Android SDK 的 NDK 28.2.13676358 与 CMake 3.22.1
./gradlew -PdevInstall -PaauVisionBackend=mnn -PaauMnnSourceDir=/absolute/path/MNN-3.6.2 :app:assembleDebug
```

Windows 把 `./gradlew` 换成 `gradlew.bat`；带空格的 `-PaauMnnSourceDir=...` 参数整体加引号。`-PdevInstall` 会用独立的 `.dev` 应用 ID，不覆盖正常版。MNN 试点只打包 `arm64-v8a` 和 `x86_64` 后端；LiteRT 独立版另有 `armeabi-v7a`。不在支持列表的 ABI 不提供该工具。`aau` 是 Git 子模块：提交/发布时要先提交子模块改动，再更新 Mika 指针。

## 使用与边界

- 手机上已有获准的 AAU 虚拟屏且 App 已打开时，在树与现有离线 OCR 都找不到按钮之后，试 `phone_find_local_icon`，例如 `{"icon":"search","region":"top"}`。`icon` 只支持 `search`、`close` 和 `more`（水平三点），区域为 `whole`、`top`（上 1/3）或 `bottom`（下 1/3）。小图标优先缩小搜索范围。模型是自生成卷积字形模板，**不是**通用 UI 检测器，不保证识别跨 App 的各种变体。
- 只返回图标类别和一个不透明引用；没有原始像素、OCR 文本、坐标或时间戳，不会自动切到云端。歧义/未命中不发引用。拿引用做单击仍经既有动作执行入口；不能用于双击、输入或清空。显示、旋转、画面、窗口任一变化都会使引用失效；动画页如果持续重绘，可能需要先等稳定。该点击也不自动附图。
- 只处理标准非安全 AAU 虚拟屏的当前帧；不走主屏、特权截屏或保护图层采集，不处理黑帧、已知受保护窗口或密码界面。新路径本身不把像素落盘或外传。**这不是 Mika 全离线模式**：常规对话规划、其他截图工具和记录策略不随这一构建选项改变。

## 验收

用同一组代码在两种构建下各跑一次；`connectedDebugAndroidTest` 只选下列测试，不需要配置聊天模型或给模拟器开无线调试。设备上可以关闭网络。

```sh
./gradlew :core:test
./gradlew -PdevInstall -PaauVisionBackend=litert :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.wangzan101.mika.cap.phone.a11y.LocalIconPilotDeviceTest
# MNN 验收：同样的命令，改用 -PaauVisionBackend=mnn 并传 -PaauMnnSourceDir
# 端到端真机：为避免 connectedDebugAndroidTest 强停 Dev 宿主，安装 APK 后保留宿主进程，直接执行：
# adb shell am instrument --no-restart -w -r -e class com.wangzan101.mika.cap.phone.a11y.LocalIconEndToEndDeviceTest \
#   com.wangzan101.mika.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Android 测试用 Canvas 构造没有节点或文字的图像，覆盖三种图标、深浅色、横竖屏、裁剪换算、空帧和多命中；JVM 测试覆盖工具注册、保护/歧义和引用过期。设备测试还会输出 720×720 合成帧上的首次出结果耗时和热态端到端检测 P50/P95（含扫描与帧哈希）；这是后端相对比较，不代表真实 App 误检率或端到端点击延迟。发行前仍需验证真实 App 图标集、虚拟屏误点击率、断网行为、16 KB 页和发行包体；不以合成图结果决定发行默认后端。

## 当前真机记录

2026-11-20，OnePlus 8T（`KB2000`，Android 14，`arm64-v8a`）：

- LiteRT：4/4 设备测试通过；首次出结果 41.67 ms，热态 P50 12.54 ms、P95 13.41 ms。
- MNN：4/4 设备测试通过；16 KB 对齐修复后重跑，首次出结果 30.20 ms，热态 P50 28.56 ms、P95 29.24 ms。
- `LocalIconEndToEndDeviceTest` 用真实 Activity 验证 AAU 虚拟屏帧 → `findLocalIcon` → 严格帧复核点击 → 画面状态变化；另验证 `FLAG_SECURE` 页面不进入检测器、不返回截图/OCR/帧采样。窗口状态 fail-closed 加固后，重新安装 LiteRT Dev、在系统设置重新启用 Dev 无障碍服务并启动 USB helper，LiteRT 通过 2/2；再安装最终 MNN Dev 变体并复用同一 helper，MNN 也通过 2/2。本轮没有改动网络设置。
- `assemblePerf` arm64 APK：LiteRT 93,827,936 bytes，MNN 88,370,583 bytes；这是完整 Mika perf 包，不是视觉增量包。
- 两个 arm64 perf APK 均通过 `zipalign -c -P 16 4`，其中所有 `.so` 的 PT_LOAD 对齐均不低于 16,384 bytes。`libaau_ebpf.so` 的修复在 `aau/aau-client/src/main/cpp/CMakeLists.txt`。
- 上述性能耗时来自无节点/无文字的 720×720 Canvas fixture；OnePlus 当前页大小为 4 KB。另在 Android 15 Google APIs x86_64 16 KB AVD（`getconf PAGE_SIZE=16384`）上，断开 Wi-Fi/移动数据后分别运行 MNN、LiteRT `LocalIconPilotDeviceTest`，均通过 4/4，且 Mika Dev `MainActivity` 均冷启动成功。这验证了两种视觉后端在该 16 KB x86_64 环境可加载/推理，不等于 16 KB arm64 真机或虚拟屏点击 E2E 验收；16 KB 模拟器也不是第三方 App 准确率测试。
- x86_64 通用调试 APK 内 `libzstd-jni` 的 PT_LOAD 仍是 4 KB；本地视觉试点未调用 zstd 路径，因此不能据此宣称 x86_64 全部 native 依赖均已完成 16 KB 运行时验收。
- 保护规则区分三类情况：窗口管理器明确报告当前 App 窗口含 `FLAG_SECURE` 时报告 `PROTECTED`；密码字段也阻断像素读取/本地图标；明确普通窗口才允许截图/OCR/图标检测；查询失败或证据不完整时 fail closed，不读帧并报告 `CAPTURE_UNAVAILABLE`。黑帧本身仍不作为安全窗口证据。
- 仅按这台设备的合成帧数据，如果必须在两个 CPU 后端中先选一个，LiteRT 是性能优先候选（热态 P95 约快 2.2 倍），MNN 是包体优先候选（约小 5.45 MB）；这不是发行定案，默认构建仍保持 `off`，待真实 App 误检/误点击率、断网端到端及 16 KB arm64/全 native 依赖验证后再决定。

模型源代码和独立验证工具在 [`aau/aau-vision`](../../aau/aau-vision/README.md)。
