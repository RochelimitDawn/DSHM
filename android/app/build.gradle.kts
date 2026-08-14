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
        minSdk = 26
        targetSdk = 34
        versionCode = 2000200
        versionName = "v2.0.2"
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

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
