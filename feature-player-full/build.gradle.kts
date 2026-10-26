// feature-player-full —— 依赖方向铁律见 settings.gradle.kts（单向，严禁反向）
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xiaoliu.yuno.feature.player.full"
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
    api(project(":core-playback"))
    api(project(":data"))
    api(project(":feature-lyrics-render"))
    api(project(":feature-lyrics-portrait"))
    api(project(":feature-lyrics-floating"))

    // Compose BOM：api 导出，feature 模块不必重复声明
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
    api(libs.haze)
    api(libs.haze.blur)
    api(libs.haze.glass)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
