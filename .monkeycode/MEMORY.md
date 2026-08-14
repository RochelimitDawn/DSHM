# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[User Instruction Summary]
- Date: 2026-08-14
- Context: 用户要求分析 DeepSeek Harness 仓库并产出文档
- Instructions:
  - 项目中所有文档统一放置于根目录下的 `docs/` 文件夹，后续新增文档也放置于 `docs/` 目录下
  - 文档内容可以使用二级分点整理

[User Instruction Summary]
- Date: 2026-08-14
- Context: SiliconLeap（硅基跃迁）移动端项目的版本号命名规则
- Instructions:
  - 基础版本号为 `v2.N.E` 格式：大版本升级递增 `N`，小版本升级递增 `E`
  - 当前版本为 `v2.0.5`（N=0、E=5）
  - 产品名称为 SiliconLeap（硅基跃迁），Android `versionName` 与 `versionCode` 均按此规则映射

[Project Knowledge Summary]
- Date: 2026-08-14
- Context: Discovered by Agent while assembling the Termux Android runtime for SiliconLeap
- Category: Build Methods
- Instructions:
  - Termux 二进制的动态库 RUNPATH 为绝对路径 `/data/data/com.termux/files/usr/lib`，但 `LD_LIBRARY_PATH` 优先级高于 `DT_RUNPATH`，设置 `LD_LIBRARY_PATH=$PREFIX/lib` 即可让运行时重定位到任意应用私有目录
  - Termux bootstrap 的符号链接记录在 `SYMLINKS.txt`（格式 `绝对目标←./相对链接路径`），需按官方 TermuxInstaller 语义重建为相对符号链接
  - 官方 bootstrap 下载地址：`https://github.com/termux/termux-packages/releases/download/bootstrap-<ver>/bootstrap-aarch64.zip`
  - Android bionic 缺少 `<pty.h>` 的 `openpty/forkpty/login_tty`，node-pty 需用 `posix_openpt` 兼容头交叉编译
