# 方案：去掉 ProRoot + 引入保活/虚拟屏参考设计

来源研究：DSH-APP/DSHA（同赛道成熟项目）、zalexdev/linux-um-arm64、AAQWQ11/umnetx

## 一、ProRoot 兼容性结论：去掉

### DSHA 的 ProRoot 实现（查证）

- 上游：`coderredlab/proroot` v1.2.8（与我们的 ProRoot **同源**）
- 形态：LD_PRELOAD + 二进制补丁做进程内路径翻译，零 ptrace；5 个 .so
  （libproroot.so/-runtime/-linker/-stub-loader/-bridge）伪装进 jniLibs
- 双运行时抽象（ContainerRuntime）：proroot 默认、proot 一键切回；三层兜底
  （文件缺失降回 proot、连续 3 次启动失败强制切回、装机路径始终用 proot）
- staticLoader 开关：4 KiB / 16 KiB 页兼容（RELRO 双映射检查）

### DSHA 自己承认的兼容性问题（CHANGELOG 实录）

- **python 等依赖 Android linker 的二进制在 proroot 下找不到 libc**——他们用 proot
  跑这类命令（ProotBootstrap.java:1024 注释原文）
- proroot 目录项报告 DT_UNKNOWN 时 JSON per-record 存储写后重开为空，导致受管
  更新试运行回滚（修复记录）
- proroot 真实 `--argv0` 未被停止器识别而遗留隔离试运行进程（修复记录）
- 鉴权前确认退出时需要一次 proot 兼容重试

### 我们的设备证据

- `proroot-ldso: no offset table for glibc 2.36 — patches skipped`
- `__isoc23_strtoul` glibc 2.38+ 符号未解析 → bash exit 2 → **AI 的 bash 全挂**
- 源码缺失（上游无公开仓库），无法补 offset table

### 结论

DSHA 同源方案的兼容性同样差（靠 proot 兜底续命）；我们的 ProRoot 连兜底能力
都没有（glibc 2.36 直接失败）。**去掉 ProRoot，保留 proot 作为唯一引擎**，
设备命令兼容性问题等 UML 方案成熟后整体替换。

### 移除清单

- jniLibs：libproroot.so、libproroot-linker.so、libproroot-runtime.so、
  libproroot-stub-loader.so、libproroot-bridge.so（5 件，共 ~3MB）
- TermuxEnv：prorootArgvJson、prorootAvailable、probeProRoot、引擎 auto 翻转逻辑
- SubsystemManager：prorootBin、isProRootAvailable、proroot 分支
- RuntimeManager：bootstrap 中 ProRoot 探测降级逻辑
- Settings/SetupScreen：proroot 引擎选项（只留 proot，或引擎选择整体隐藏）
- 已保存 proroot 引擎的用户：启动时静默迁移为 proot + 一次性提示

## 二、保活参考设计（来自 DSHA HarnessService，落地到我们的壳）

| 机制 | DSHA 实现 | 我们的落地方式 |
|---|---|---|
| 前台服务 | START_STICKY 前台服务 + WakeLock/WifiLock，通知栏显示运行状态 | 已有类似；补 WakeLock/WifiLock 与常驻通知 |
| 看门狗 | 线程每 15s TCP 探测 WebUI 端口；连续 3 次失联自动重启；120s 冷却防风暴 | 新增：TcpProbe("127.0.0.1", webPort)，3 连败 → 自动重启 |
| 世代校验 | restartWebAutomatically 带 generation，探测期间手动启停不沿用旧结果；不撤销用户停止意图 | 新增：webGeneration 计数，重启前比对 |
| 慢启动保护 | 等待鉴权期间保持启动门控，60s 仅提示不强杀 | 新增：启动 60s 未就绪只报状态 |
| 顺手守护 | 同循环里检查 ADB 设备桥 isRunning，被杀拉回 | 可选：顺带检查 dsh server 进程 |

关键不变量（DSHA 注释原文可借鉴）：
- 看门狗不能撤销用户停止意图（检查与入队同一把锁）
- 覆盖安装后前台服务可能先于 MainActivity 重建，重启前必须确认运行补丁已落地

## 三、虚拟屏参考设计（来自 DSHA vscreen/ 包，~1000 行）

DSHA 用 **app 进程内 createVirtualDisplay**（非 Shizuku UserService）：

- **创建**：DisplayManager.createVirtualDisplay("DSHA-VirtualScreen", w, h, 320,
  ImageReader.surface, flags)；flags = PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH |
  DESTROY_CONTENT_ON_REMOVAL | TRUSTED | OWN_FOCUS（反射获取，缺了置 0）
- **抓帧**：ImageReader(RGBA_8888, 3 buffers) + 33ms 轮询 acquireLatestImage，
  `sameAs` 去重，JPEG 65% 缩放宽 720 编码后缓存（encodedSequence 复用）
- **尺寸策略**：16:9，短边按 144 对齐（144–1440），横竖屏自适应
- **输入双通道**：
  - ASCII：`/system/bin/input -d <displayId> tap/swipe/text/keyevent`（8s 超时 exec）
  - Unicode：无障碍服务（AccessibilityService）注入
  - keycode 白名单：HOME(3)/BACK(4)/HEADSETHOOK(66)/DEL(67)
- **启动应用**：`cmd package resolve-activity --brief` 解析组件 →
  `am start --user current --display <id> -n <component> -f 0x18000000`
- **帧号防错位**：每次动作前 `observed = -1` 消耗观察，动作执行失败或结果未知
  不可重放同一动作；fresh() 要求 observed == current

与现有 VdisplayManager（Shizuku UserService AIDL）的关系：
- 两条路线并存可作降级：Shizuku 可用时走 UserService（权限更干净）；
  无 Shizuku 时走 app 内 createVirtualDisplay（Android 11+，实验）
- DSHA 的 flags 反射方案在无 root 无 Shizuku 设备上可用（README 声称
  Standard 版 Android 11+），值得作为默认路径实测

## 四、实施顺序建议

1. **本轮（低风险）**：移除 ProRoot 全链 + proroot 用户静默迁移 proot
2. **下轮**：保活看门狗（15s/3 连败/120s 冷却/generation 世代校验）
3. **再轮**：虚拟屏 app 内 createVirtualDisplay 路径（参考 vscreen 包结构移植：
   Policy 纯逻辑先测、Core HTTP 端点、Accessibility Unicode 输入）
4. **远期**：UML（linux-um-arm64 预编译内核 + umnetx 网络栈）替换 proot 本体

## 参考文件位置

- DSHA ProRoot：`app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.java`
- DSHA 保活：`app/src/main/java/com/deepseekharness/app/HarnessService.java:222`（看门狗）、
  `core/HarnessController.java:275`（世代校验）
- DSHA 虚拟屏：`app/src/main/java/com/deepseekharness/app/vscreen/`（10 文件）、
  `util/VirtualScreenPolicy.java`
- 本地克隆：`/tmp/opencode/dsha-ref`、`/tmp/opencode/um-ref`、`/tmp/opencode/umnetx-ref`
