// feature-lyrics-fx —— 依赖方向铁律见 settings.gradle.kts（单向，严禁反向）
// 歌词特效层（glow / 弹簧 / 呼吸 / 涟漪）：
//   · 只依赖 feature-lyrics-render（复用 LyricChar / WordCell / expandChars / drawChar）
//   · feature-lyrics-render 管「歌词正确显示」，本模块管「让歌词好看」
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xiaoliu.yuno.feature.lyrics.fx"
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
    api(project(":feature-lyrics-render"))

    // Ksoup：路线 B 的 Kotlin 侧 HTML 解析（fx_glow.html 的 DOM 树）
    api(libs.ksoup)

    // Compose BOM：api 导出，feature 模块不必重复声明
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    // WebView：Folia 特效渲染层（路线 B）
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.compose.ui.tooling)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
