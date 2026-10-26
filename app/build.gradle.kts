// app：壳工程 —— 导航装配、手写 DI 图、Application
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xiaoliu.yuno"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xiaoliu.yuno"
        minSdk = 26
        targetSdk = 37
        versionCode = 94
        versionName = "2.2.0-r"
    }

    // 个人版签名：优先用 D:\Yuno\keys\release-keystore.properties 指向的正式
    // keystore；缺文件时退回 debug keystore，保证换机器仍能出包。
    val releaseProps = Properties()
    val propsFile = File("D:/Yuno/keys/release-keystore.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { releaseProps.load(it) }
    }
    val usePersonal = releaseProps.getProperty("storeFile") != null

    signingConfigs {
        create("personalRelease") {
            if (usePersonal) {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            } else {
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // 不开 R8：Media3 / kotlinx-serialization 的反射路径不折腾
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personalRelease")
        }
    }

    // ── 双专版（用户钦定 2026-10-26）──
    // phone / tv 两个 flavor，各自的源码集与依赖完全隔离：
    //   手机包里没有 feature-tv，电视包里没有任何手机 feature。
    flavorDimensions += "device"
    productFlavors {
        create("phone")
        create("tv")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // ── 两侧共用 ──
    implementation(project(":core-model"))
    implementation(project(":core-ui"))
    implementation(project(":core-glass"))
    implementation(project(":core-network"))
    implementation(project(":core-playback"))
    implementation(project(":core-rust"))
    implementation(project(":data"))
    // 歌词渲染引擎（手机竖屏引擎 + 横屏滚筒/TV 共享件）
    implementation(project(":feature-lyrics-render"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // ── 仅手机专版 ──
    "phoneImplementation"(project(":feature-home"))
    "phoneImplementation"(project(":feature-search"))
    "phoneImplementation"(project(":feature-detail"))
    "phoneImplementation"(project(":feature-library"))
    "phoneImplementation"(project(":feature-player-full"))
    "phoneImplementation"(project(":feature-lyrics-portrait"))
    "phoneImplementation"(project(":feature-lyrics-floating"))
    "phoneImplementation"(libs.androidx.navigation.compose)
    "phoneImplementation"(libs.haze)

    // ── 仅电视专版 ──
    "tvImplementation"(project(":feature-tv"))
    "tvImplementation"(libs.haze)

    debugImplementation(libs.compose.ui.tooling)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
