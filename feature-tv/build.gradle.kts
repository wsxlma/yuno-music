// feature-tv —— TV 版界面（遥控器交互）。依赖方向铁律见 settings.gradle.kts。
// 复用 lyrics-render 的滚筒/逐字引擎；数据直连 data 仓库，不依赖其它 feature。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xiaoliu.yuno.feature.tv"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
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
    api(project(":core-model"))
    api(project(":core-ui"))
    api(project(":core-glass"))
    api(project(":data"))
    implementation(project(":core-playback"))
    implementation(project(":feature-lyrics-render"))

    implementation(libs.haze)

    // Compose BOM：api 导出
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.compose.ui.tooling)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}