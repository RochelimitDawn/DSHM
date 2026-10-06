<div align="center">

<a href="https://github.com/RochelimitDawn/DSHM">
  <img src="./docs/brand/logo-banner.svg" alt="DSHM" width="300" height="55" />
</a>

# DSHM

**Deepseek Harness Mobile**

把完整的 Debian Linux 和 AI 编程助手装进 Android 手机。免 root，独立运行，全程本地。

[![GitHub release](https://img.shields.io/github/v/release/RochelimitDawn/DSHM?style=for-the-badge&logo=github)](https://github.com/RochelimitDawn/DSHM/releases)
[![GitHub stars](https://img.shields.io/github/stars/RochelimitDawn/DSHM?style=for-the-badge&logo=github)](https://github.com/RochelimitDawn/DSHM/stargazers)
[![License GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-0ea5e9?style=for-the-badge)](./LICENSE)

</div>

---

## 这是什么

一个 Android App。装上之后，手机里就有一个完整的 Debian 12 子系统：真 bash、真 apt，node / python3 / git / ripgrep 开箱即用。子系统内运行 DeepSeek dsh CLI（Node.js 编码 Agent），通过系统浏览器使用 Web UI：终端、AI 会话、项目管理、插件生态。

- 全程免 root，用户态沙箱执行，数据落在应用私有目录（fscrypt 加密）
- 不依赖 Termux，独立运行；运行时镜像在线下载安装（约 580 MB，多源加速）
- 工作区就是手机存储里的 `DSHM` 目录，文件管理器直接打开，与子系统双向互通

## 核心能力

### tawcroot 执行引擎

基于开源 [taw](https://github.com/wmww/tawc) 项目魔改的用户态执行引擎：用 seccomp 用户态通知（systrap）拦截路径类 syscall，单进程原地翻译，替代 ptrace 型方案（proot）的两次进程间往返。路径操作快 2.5～7.5 倍（数据来自 taw 项目 benchmark），apt 安装这类 syscall 大户体感差距非常明显。老系统自动回退 proot 兼容模式。

### 国内网络优化

apt 走 USTC 镜像、npm 走 npmmirror、GitHub 下载多源回退（ghproxy 等）。目标是没梯子也能把环境装齐。

### AI 会话与插件

- DeepSeek dsh CLI：手机上直接让 AI 写代码、改项目、跑命令，需在应用内配置自己的 DeepSeek API Key
- AI 可直接读取子系统日志自我排障（`/root/dsh/logs/*` 全量落盘）
- 兼容插件以 npm tgz 分发，多源下载；内置插件市场、用量统计等

## 系统要求

| 项目 | 要求 |
|------|------|
| 架构 | arm64 |
| 系统 | 建议 Android 11+（旧系统自动回退 proot 兼容模式） |
| 存储 | 2 GB 以上可用空间 |
| 网络 | 安装阶段需要网络（多源加速，国内可直连） |

## 安装

1. 从 [Releases](https://github.com/RochelimitDawn/DSHM/releases) 下载 APK 安装
2. 首次启动按引导下载运行时镜像并完成初始化
3. 启动服务后，浏览器打开 Web UI 开始使用

## 从源码构建

```bash
# Android 端（需 Android SDK / NDK 27.2）
cd android && ./gradlew assembleRelease

# 运行时镜像构建（可选，使用官方 Releases 提供的即可）
cd runtime-builder && bash build_runtime.sh
```

执行引擎源码在 `runtime-builder/vendor/tawcroot`（含本地补丁），构建脚本 `runtime-builder/build_tawcroot.sh`，产物随 CI 打进 APK 的 jniLibs。

架构与实现细节见 **[docs/siliconleap-android.md](./docs/siliconleap-android.md)**。

## 致谢

- [taw](https://github.com/wmww/tawc)（MIT）—— tawcroot 执行引擎基础
- [Debian](https://www.debian.org/)—— 子系统根文件系统
- [DeepSeek dsh](https://www.npmjs.com/package/@deepseek-ai/dsh)—— AI 编程 Agent CLI

## 爱发电

https://afdian.com/a/Rochelimit

## License

[GPL-3.0](./LICENSE)

<div align="center">
<img src="./docs/brand/logo-banner.svg" alt="DSHM" width="160" height="29" />
</div>
