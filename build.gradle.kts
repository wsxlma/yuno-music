// Yuno 2.0.0 根工程：只声明插件版本（版本目录 gradle/libs.versions.toml），不 apply。
//
// ⚠️ AGP 9 起 Kotlin 内置 —— **禁用 org.jetbrains.kotlin.android 插件**。
//    所有模块的编译设置统一走顶层 kotlin { compilerOptions {} }。

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
