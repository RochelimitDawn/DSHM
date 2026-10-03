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
        targetSdk = 34
        // versionCode 由 versionName 推导（单一事实来源，必须与 UpdateManager.parseVersionCode 一致）：
        //   major*10^7 + minor*10^5 + patch*10^3，稳定版再 +5（同版本号 稳定版 > 预发布版）。
        // 例：v2.2.12-beta = 20212000，v2.2.12 = 20212005。
        // 历史说明：v2.2.11-beta 曾手写 versionCode=2022011，新scheme所有后续版本码必然更大，可正常覆盖安装。
        val ver = Regex("""v(\d+)\.(\d+)\.(\d+)(?:-(\S+))?""").find("v2.2.13-beta")!!.groupValues
        versionCode = ver[1].toInt() * 10_000_000 + ver[2].toInt() * 100_000 + ver[3].toInt() * 1_000 +
            (if (ver[4].isEmpty()) 5 else 0)
        versionName = "v2.2.13-beta"
         // 应用期望的运行时版本（与 runtime-builder/build_runtime.sh 的 DSH_VERSION 一致；
         // r2 修复 node-addon-require-builtin 绑定缺失；r3 修复 dsh-plugin-manager
         // operations.js 的 execa wrapper 缺失（单引号 import 未匹配）导致全部插件装配失败）
         buildConfigField(
             "String",
             "RUNTIME_VERSION",
             "\"${project.findProperty("runtimeVersion") ?: "0.2.0-rc.2-r3"}\"",
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
