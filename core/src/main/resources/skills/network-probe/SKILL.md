---
name: network-probe
display: 端侧网络库与偏移排查
description: 用 Root 查进程网络库和 Socket，并用 Python 扫描 ELF/APK 偏移
aliases: ["网络分析", "找 SO 偏移", "抓包排查"]
terms: ["eBPF", "Cronet", "BoringSSL", "SO 偏移", "APK", "抓包", "readelf", "maps", "QUIC"]
---
## 适用场景

抓包遇到漏抓、全 `unknown-host`、目标 App 升级了 Cronet/自研网络库，或确认是否走 UDP/QUIC 时，直接在手机本地完成调研与偏移定位，无需连电脑。

## 第一步：先跑只读端侧探测

1. 先调用 `phone_probe_ebpf`（只传包名如 `com.xingin.xhs`）：
   - 会自动调用 `libaau_ebpf.so --discover-only` 扫描当前应用 `nativeLibraryDir` 和运行中进程 `/proc/<pid>/maps`，返回匹配到的 BoringSSL / Cronet 偏移、`apkPaths`、`unmatchedAppLibs` 以及可直接使用的 `suggestedProfileJson`。
   - 如果 `phone_probe_ebpf` 未命中，重点看它返回的 `apkPaths` 与 `unmatchedAppLibs`。

## 第二步：用 Root 只读查进程真实映射与 Socket

若需确认目标 App 运行时到底加载了哪个 SO、走了 TCP 还是 UDP/QUIC，用 `root_run_command` 分步执行只读命令（避免命令替换 `$()`，确保免确认直接运行）：

1. 先查进程 PID 与安装包路径：
```sh
pidof com.xingin.xhs; pm path com.xingin.xhs
```
2. 用查到的 `<pid>` 和 `.so` 路径读取内存映射、活跃 Socket 与符号表（`/system/bin/readelf`、`/proc/<pid>/net/udp6`）：
```sh
cat /proc/<pid>/maps | grep '\.so'; cat /proc/<pid>/net/udp6; cat /proc/<pid>/net/tcp6
/system/bin/readelf -sW /data/app/.../lib/arm64/libcronet.so
```

## 第三步：用 Python 分析套件深入解剖（`run_bundled_script`）

把 Root 采到的文本快照 via `write_file` 存到 `/workspace/`（如 `proc.txt` 或 `syms.txt`），或者在获得用户同意后将目标 `.so` / `split_config.arm64_v8a.apk` 拷入 `/workspace/probe/`，用 `run_bundled_script` 调用 `scripts/analyze_so.py`（`source` 填 `skills/network-probe`）：

- **总结进程映射与 Socket（判断是否走 QUIC UDP:443）**：
  - `args: ["proc-summary", "proc.txt"]`
- **从 `readelf -sW` 文本直接提取偏移并生成配置 JSON**：
  - `args: ["analyze-readelf", "syms.txt", "--lib", "libcronet.so", "--pkg", "com.xingin.xhs", "--out-plugin-json", "plugin.json"]`
- **静态解析 ELF64 `.so`（节表、动态符号、BoringSSL/Cronet/QUIC 指纹、AArch64 ADRP+ADD 字串交叉引用）**：
  - `args: ["inspect", "probe/libcronet.so", "--pkg", "com.xingin.xhs", "--out-plugin-json", "plugin.json"]`
- **直读未解压的 APK / split APK（针对 `extractNativeLibs=false`）**：
  - `args: ["inspect-apk", "probe/split_config.arm64_v8a.apk", "--abi", "arm64-v8a", "--extract-dir", "probe/out", "--pkg", "com.xingin.xhs", "--out-plugin-json", "plugin.json"]`

## 结果落盘与反馈

- 拿到新的 SO 匹配名与偏移后，在终端输出完整 `aau_capture_plugins.json` 配置结构，并引导用户在 **设置 › 高级与其他 › 端侧网络抓包 › eBPF 探测规则** 中「编辑 / 自动扫描应用」应用生效。
- 若分析显示流量走自研私有协议或未匹配标准 BoringSSL/Cronet，如实说明识别到的 SO 名称（如 `ttquic`、`xquic`、`mars` 等）与 UDP/TCP Socket 证据。
