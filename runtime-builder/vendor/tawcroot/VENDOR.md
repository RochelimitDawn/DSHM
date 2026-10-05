# tawcroot（vendored）

来源：https://github.com/wmww/tawc（Tess's Android Wayland Compositor 项目中的
tawcroot 子项目），MIT 许可（见本目录 LICENSE.MIT，Copyright (c) 2026 Sophie Winter）。

- Pin 提交：`255cbc14b6bf5f7559452b62033913db56de4469`（2026-10 拉取，Refresh screenshots）
- **本地补丁**：`src/supervisor.c` bind 失败降级——`tawcroot_path_add_bind` 失败时跳过该
  bind 并打警告（原实现 `tawc_exit_group(93)` 整体中止）。可选 `-b` bind（如
  `/dev/urandom:/dev/random`）失败不致命：guest 仍经外层 `/dev` bind 到达该路径
  （宿主 /dev/random 是真设备）。该失败曾导致 DSHM 会话 bash 命令整体 exit 93
- 仅 vendor 生产源码：`src/`（C 实现 + arch 汇编）与 `include/`；测试层（cleat 编排）
  未纳入——DSHM 通过运行时验证（host smoke + 设备会话）代替其单测套件
- 上游设计文档在 tawc 仓库 `notes/tawcroot/`（sigsys-handler.md、path-translation.md、
  link-emulation.md 等），维护时先读

## 是什么

proot 风格的 fake chroot，用 **seccomp RET_TRAP + in-process SIGSYS handler**
（gVisor 的 systrap 技术）替代 ptrace：单进程、无 tracer、无 glue rootfs。
路径翻译、fake-root uid/stat、Android syscall fixup 全部在发出 syscall 的同一线程
内完成。路径 syscall 性能比 proot 快 2.5-7.5 倍（上游 perf/results.md 基线）。

## CLI

```
tawcroot -r ROOTFS [-b SRC:DST[:ro]]... -- CMD [ARGS...]
```

仅 `-r` / `-b` / `--` 三个参数。fake-root 恒开；hardlink 仿真（link store）内建；
无 `--link2symlink` / `--kill-on-exit` / `--cwd` / `-0`（proot 对应物不存在）。

**cwd 语义**：guest cwd = tawcroot 进程自身 cwd（re-exec 继承，按 rootfs/bind
反向翻译）。宿主 cwd 必须落在 rootfs 或某个 bind 源内，否则 guest `getcwd()` 失败。
Kotlin 侧通过 ProcessBuilder.directory() 控制。

## Android 打包

`runtime-builder/build_tawcroot.sh` 用 NDK r27 的
`aarch64-linux-android29-clang` 编译，`-static -nostdlib` 静态非 PIE ET_EXEC，
以 `libtawcroot.so` 名义进 jniLibs（Android 只解包 `lib*.so`，不校验 ELF 类型），
运行时经 `nativeLibraryDir` 执行（SELinux 允许区）。seccomp 过滤器按 IP 白名单
固定 `&tawcroot_raw_syscall_ret`，二进制地址必须跨 re-exec 稳定（aarch64 base
`0x2000000000`），PIE/ASLR 会破坏该不变量。

## DSHM 集成

`TermuxEnv.prootArgvJson` / `assemblyArgv` / `SubsystemManager.ensureMihomoRunning`
在 `libtawcroot.so` 存在时优先用 tawcroot（同 rootfs、同 bind 列表），缺失时回退
proot（Termux 构建）。二者语义兼容：proot 的 `--link2symlink` 对应 tawcroot 内建
link 仿真，`-0` 对应恒开的 fake-root。
