plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.siliconleap.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.siliconleap.app"
        minSdk = 33
        // targetSdk 钉 28（Termux 同款解法）：Android 10+ 对 targetSdk >= 29 的应用强制 W^X
        // （app_data_file 的 execute_no_trans 被 SELinux 拒绝），files/usr 工具链、proot 二进制、
        // UML 内核全部无法 exec，子系统引擎全链失效（auto 模式降级原生 bash）。targetSdk 28 进
        // untrusted_app_27 域（Android 16 仍存在），应用私有目录 exec 放行；targetSdk < 29
        // 自动获得 legacy external storage，工作区访问更宽。
        targetSdk = 28
        // versionName 单一事实来源；versionCode 由它推导（必须与 UpdateManager.parseVersionCode 一致）：
        //   major*10^7 + minor*10^5 + patch*10^3，稳定版再 +5（同版本号 稳定版 > 预发布版）。
        // 例：v2.2.12-beta = 20212000，v2.2.12 = 20212005。
        // 历史说明：v2.2.11-beta 曾手写 versionCode=2022011，新scheme所有后续版本码必然更大，可正常覆盖安装。
        // 历史教训（v2.2.40）：versionCode 曾从硬编码 "v2.2.39-beta" 推导（versionName 定义在其后），
        // versionName bump 时 versionCode 停留旧版——应用 v2.2.40 装上后仍提示更新 v2.2.40（死循环）。
        // 必须：versionName 先定义，versionCode 从它推导，禁止硬编码版本号。
        versionName = "v2.2.49"
        val ver = Regex("""v(\d+)\.(\d+)\.(\d+)(?:-(\S+))?""").find(versionName!!)!!.groupValues
        versionCode = ver[1].toInt() * 10_000_000 + ver[2].toInt() * 100_000 + ver[3].toInt() * 1_000 +
            (if (ver[4].isEmpty()) 5 else 0)
         // 应用期望的运行时版本（与 runtime-builder/build_runtime.sh 的 DSH_VERSION 一致；
         // r2 修复 node-addon-require-builtin 绑定缺失；r3 修复 dsh-plugin-manager
         // operations.js 的 execa wrapper 缺失（单引号 import 未匹配）导致全部插件装配失败；
         // r4 修复 Clash redir-host（dsh web_fetch 服务端拦截 fake-ip 198.18.x.x）；
         // r5/r6 为后续累积修复；r7 修复 subprocess-local 在 process.platform="android"
         // 时 createProcessInspector 抛 "terminal inspection is unsupported on platform
         // android"（复用 LinuxProcessInspector，Android 内核即 Linux）
         buildConfigField(
             "String",
             "RUNTIME_VERSION",
             "\"${project.findProperty("runtimeVersion") ?: "0.2.0-rc.2-r7"}\"",
         )
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    val envKeystorePath = System.getenv("SILICONLEAP_KEYSTORE_PATH")
    val envKeystorePass = System.getenv("SILICONLEAP_KEYSTORE_PASS")
    val envKeyAlias = System.getenv("SILICONLEAP_KEY_ALIAS")
    val envKeyPass = System.getenv("SILICONLEAP_KEY_PASS")
    if (!envKeystorePath.isNullOrBlank() && !envKeystorePass.isNullOrBlank() && !envKeyAlias.isNullOrBlank() && !envKeyPass.isNullOrBlank()) {
        signingConfigs {
            register("release") {
                storeFile = file(envKeystorePath)
                storePassword = envKeystorePass
                keyAlias = envKeyAlias
                keyPassword = envKeyPass
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // 非 Play 分发（GitHub release 直装），targetSdk 28 是 Termux 同款 W^X 解法
        disable += "ExpiredTargetSdkVersion"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // 将 jniLibs 解包到 nativeLibraryDir，供 exec 执行（app_data_file 已被系统禁止执行）
            useLegacyPackaging = true
        }
    }

    aaptOptions {
        noCompress += "zip"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.commonmark)


    debugImplementation(libs.androidx.compose.ui.tooling)
}
