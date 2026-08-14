# SiliconLeap 硅基跃迁

轻简随行，插件随心。GenUI 2.0 实践。

SiliconLeap 是基于 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）的移动端封装：原生 Android WebView 壳 + Termux Linux 运行时，安装即用（即装即用），数据本地持久化。

## 版本

- 基础版本：`v2.N.E`（大版本升 `N`，小版本升 `E`）
- 当前：**`v2.0.5`**

## 结构

| 目录 | 说明 |
|---|---|
| `android/` | Android 工程：Miuix 壳 UI + WebView + 运行时管理 |
| `runtime-builder/` | Termux 运行时装配脚本（bootstrap + node + dsh + 补丁） |
| `.github/workflows/build-apk.yml` | GitHub Actions：装配运行时并打包 APK |
| `docs/` | 项目文档（设计、分析等） |
| `deepseek-harness/` | DeepSeek Harness 源码克隆（vendored，参考/构建源） |

## 构建

在 GitHub Actions 触发（tag `v*` 或手动 dispatch）后自动产出 APK：

1. **build-runtime**：装配 `runtime.zip`（Termux bootstrap + nodejs + `@deepseek-ai/dsh` + node-pty 交叉编译 + 补丁）
2. **build-apk**：注入运行时到 assets，`./gradlew assembleRelease` 产出 APK

本地构建（需 Android SDK + JDK17）：

```bash
# 1. 装配运行时
./runtime-builder/build_runtime.sh
cp runtime-builder/out/runtime.zip android/app/src/main/assets/runtime.zip

# 2. 构建 APK
cd android
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease
```

## 数据目录（应用私有）

- `filesDir/usr` — Termux 运行时（只读）
- `filesDir/dsh-home` — `DSH_HOME`：会话、凭据、设置（持久）
- `filesDir/workspace` — 默认工作区（持久）

详见 [docs/siliconleap-android.md](docs/siliconleap-android.md)。
