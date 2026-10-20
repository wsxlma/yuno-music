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
        versionCode = 91
        versionName = "2.1.0-r"
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-ui"))
    implementation(project(":core-glass"))
    implementation(project(":core-network"))
    implementation(project(":core-playback"))
    implementation(project(":core-rust"))
    implementation(project(":data"))

    implementation(project(":feature-home"))
    implementation(project(":feature-search"))
    implementation(project(":feature-detail"))
    implementation(project(":feature-library"))
    implementation(project(":feature-player-full"))
    implementation(project(":feature-lyrics-render"))
    implementation(project(":feature-lyrics-portrait"))
    implementation(project(":feature-lyrics-floating"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // MainShell 的内容区 hazeSource + rememberHazeState
    implementation(libs.haze)

    debugImplementation(libs.compose.ui.tooling)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
