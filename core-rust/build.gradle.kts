// core-rust —— 依赖方向铁律见 settings.gradle.kts（单向，严禁反向）
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.xiaoliu.yuno.core.rust"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core-model"))

    implementation(libs.androidx.core.ktx)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
