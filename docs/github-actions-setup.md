# GitHub Actions 配置指南

SiliconLeap 的 CI（`.github/workflows/build-apk.yml`）由两个 Job 组成：

1. **build-runtime**：在 ubuntu-latest 上装配 Termux 运行时（`runtime.zip`），上传 artifact
2. **build-apk**：注入运行时到 `assets/`，编译并签名 APK，上传 artifact

工作流需要 **4 个子配置 Key 项**（仓库级 Secrets），用于对 release APK 签名：

| Key 名 | 类型 | 说明 |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | Secret | release 密钥库文件（`.keystore`）的 Base64 编码 |
| `ANDROID_KEYSTORE_PASS` | Secret | 密钥库口令（storepass） |
| `ANDROID_KEY_ALIAS` | Secret | 证书别名（alias） |
| `ANDROID_KEY_PASS` | Secret | 私钥口令（keypass） |

> 若未配置这 4 个 Key，工作流会回退使用 Android 的 debug 签名（仍可构建安装，仅提示 warning）。

## 配置方法

### 第一步：准备密钥库（已生成）

Agent 已在本机生成 release 密钥库 `android/siliconleap-release.keystore`，请妥善备份该文件
（例如存放到私有网盘/密码管理器，勿提交到 git）。

- 证书别名（alias）：`siliconleap`
- 有效期：30 年（10950 天）
- 主题：`CN=SiliconLeap, OU=Mobile, O=SiliconLeap, L=Shenzhen, ST=Guangdong, C=CN`

如需自行重新生成，可在装有 JDK 的机器上执行：

```bash
keytool -genkeypair -v \
  -keystore siliconleap-release.keystore \
  -alias siliconleap \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=SiliconLeap, OU=Mobile, O=SiliconLeap, L=Shenzhen, ST=Guangdong, C=CN"
```

获取 Base64（Linux/macOS）：

```bash
base64 -w0 siliconleap-release.keystore   # Linux
base64 -i siliconleap-release.keystore -o -   # macOS
```

### 第二步：添加 4 个 Secret

1. 打开仓库页面：`https://github.com/RochelimitDawn/SiliconLeap`
2. 进入 **Settings → Secrets and variables → Actions**
3. 点击 **New repository secret**，分别新增以下 4 个 Key：

| Secret 名称 | 填入的值 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 密钥库的 Base64 文本（由 Agent 提供） |
| `ANDROID_KEYSTORE_PASS` | 密钥库口令（由 Agent 提供） |
| `ANDROID_KEY_ALIAS` | `siliconleap` |
| `ANDROID_KEY_PASS` | 私钥口令（与密钥库口令相同，由 Agent 提供） |

> 建议勾选 "Environment：Actions"（默认即为 Actions 可见）。

### 第三步：触发构建

- 推送 tag（`v*`）自动触发，例如：`git push origin v2.0.0-preview`
- 或手动触发：**Actions → Build SiliconLeap APK → Run workflow**

### 产物

- Job 输出 `siliconleap-apk` artifact，包含 `app-release.apk`（已签名，可安装）
- 另含 `runtime-android-arm64` artifact（`runtime.zip` + `metadata.json`）

## 验证

```bash
# 校验签名证书（替换实际 APK 路径）
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs app-release.apk
```

## 密钥轮换提示

- Secrets 可随时修改；修改后重跑一次 workflow 即可用新签名
- 一旦发布过正式版，签名密钥需永久保留（Android 升级安装依赖同一签名）
