# runtime-builder

为 SiliconLeap 装配「Termux 风格」Android arm64 运行时，产出 `runtime.zip`（由 GitHub Actions 注入 APK 的 `assets/`，应用首次启动解压到 `filesDir/usr`）。

## 原理

- **Termux bootstrap**：下载官方 `bootstrap-aarch64.zip`（termux-packages releases），提供最小 bionic 用户环境（bash/apt/coreutils 等）
- **Termux 包离线装配**：`deps.py` 读取 Termux `Packages` 索引，沿 `Depends` 求解依赖闭包，逐个下载 `.deb`；解包时用 `dpkg-deb --fsys-tarfile | tar --strip-components=6` 去掉编译前缀 `data/data/com.termux/files/usr`（纯 tar 提取，无需模拟执行 arm64）
- **可重定位**：Termux 二进制动态库 RUNPATH 为绝对路径，但 `LD_LIBRARY_PATH=$PREFIX/lib` 优先级更高；`SYMLINKS.txt` 的符号链接按官方 TermuxInstaller 语义重建，`.deb` 内指向旧前缀的绝对符号链接被重写为相对链接
- **dsh**：宿主 x64 上 `npm install @deepseek-ai/dsh`，再打 Android 补丁
- **node-pty**：NDK 交叉编译（bionic 缺 `<pty.h>`/`openpty`/`forkpty`，用 `pty_compat.h` 提供 `posix_openpt` 实现；移除 `-lutil`；修复 `target=es5` tsconfig）；失败时应用降级（懒加载补丁保证总能启动）
- **ripgrep**：用 Termux `rg` 替代 `@vscode/ripgrep`（`DSH_RG_PATH` 覆盖）

## 用法

```bash
# 需要：python3 / dpkg-deb / zip / unzip / npm(node)
WORK=/tmp/sl-runtime OUT=$PWD/out ./build_runtime.sh
```

产物：`out/runtime.zip`（存储模式 zip，配合 AAPT `noCompress "zip"`）、`out/metadata.json`。

## 关键环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `WORK` | `/tmp/sl-runtime` | 工作目录 |
| `OUT` | `$PWD/out` | 输出目录 |
| `DSH_VERSION` | `0.1.0-rc.6` | `@deepseek-ai/dsh` 版本 |
| `NODE_VER` | `v22.19.0` | node-pty 交叉编译使用的 Node headers 版本 |
| `BUILD_PTY` | `1` | 是否交叉编译 node-pty（`0` 跳过，纯降级模式） |
| `DEB_CACHE` | 自动 | .deb 缓存目录，避免重复下载 |

## 目录

- `build_runtime.sh` — 主装配脚本（bootstrap → 依赖 → dsh → 补丁 → zip）
- `deps.py` — Termux 依赖闭包求解与 .deb 下载/解包
- `build_node_pty.sh` — node-pty Android arm64 交叉编译
- `patch_runtime.js` — dsh 运行时补丁（node-pty 懒加载、DSH_RG_PATH）
- `patches/pty_compat.h` — bionic `openpty/forkpty/login_tty` 兼容实现
- `patches/node-pty-android.patch` — node-pty 引入 pty_compat.h 的补丁
- `launcher/run-dsh.sh` — prefix 内的服务启动器（供排查）

## 已知限制

- 仅 `aarch64`（现代 Android 主流）
- node-pty 交叉编译为 best-effort；失败时 PTY/持久终端降级不可用，应用仍可启动
- `@vscode/ripgrep` 原生二进制不可用，`grep/glob` 走 Termux `rg`（`DSH_RG_PATH`）
