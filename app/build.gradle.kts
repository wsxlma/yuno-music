// app：壳工程 —— 导航装配、手写 DI 图、Application
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
        versionCode = 90
        versionName = "2.0.0-r"
    }

    // 个人项目：release 复用 debug keystore 签名 —— 包可直接安装，
    // 且与 debug 包同签名，升级覆盖不用卸载。
    signingConfigs {
        create("personalRelease") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
