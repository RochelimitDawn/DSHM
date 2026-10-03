# 子系统预装工具集 + Clash（mihomo）代理

Feature Name: proxy-clash-subsystem
Updated: 2026-10-03

## Description

默认安装的 UML 子系统内置常用工具集（curl/git/python3/openssh 等，apt 源默认国内镜像），并内嵌 mihomo（Clash.Meta）核心：guest 内 TUN 模式透明接管全部流量，按规则智能分流（国外域名走代理节点、下载慢/不通时 url-test 与 fallback 组自动切换、国内直连）。代理在应用设置页「代理」卡片配置（开关/订阅/模式/状态测速）。

## EARS Requirements

**预装工具集**
- WHEN 构建 UML rootfs THE SYSTEM SHALL 预装基础（curl/wget/unzip/ca-certificates/tzdata）、开发（git/python3/openssh-client）、效率（jq/ripgrep/fd-find）、网络（mihomo/dnsutils）工具，apt 源默认国内镜像。
- mihomo aarch64 二进制（MetaCubeX/mihomo android-arm64）置入镜像 `/usr/local/bin/mihomo`。

**代理生命周期**
- WHEN guest 启动且代理开关开启且订阅配置存在 THE SYSTEM SHALL 启动 mihomo（TUN 模式）后再启动 umarm-daemon。
- WHEN agent 命令执行 THE SYSTEM SHALL 预置 `http_proxy/https_proxy/all_proxy=http://127.0.0.1:7890`（TUN 下的显式兜底）。
- WHEN UML 停止 THE SYSTEM SHALL mihomo 随 guest 收口，无独立进程管理。

**配置流（设置页「代理」卡片，子系统分区）**
- WHEN 用户开启代理 THE SYSTEM SHALL 写入 share 下 `clash/enabled` 标记（下次 guest 启动生效）。
- WHEN 用户提交订阅 URL THE SYSTEM SHALL 下载 → 校验 YAML → 与内置最小规则模板合并（python，guest 侧 merge 脚本）→ 写 `share/clash/config.yaml`。
- WHEN 用户切换分流模式 THE SYSTEM SHALL 覆盖 config 的 mode 字段（rule/global/direct，默认 rule）。
- WHEN 订阅解析失败 THE SYSTEM SHALL 提示并保留上一份可用配置。

**智能分流**
- 规则分流：GEOSITE CN → DIRECT；GEOSITE github / GEOIP 非 CN → 代理组。
- 节点组：`url-test` 自动选最低延迟；可选 `fallback`（主节点超时切备）。

**降级**
- WHEN proot 回退引擎 THE SYSTEM SHALL 降级为显式代理模式（无 TUN）。
- WHEN 子系统未安装 THE SYSTEM SHALL 代理卡片置灰提示。

**内核配置**
- `uml-base.config` 增加 `CONFIG_TUN=y`。
