// Yuno 2.0.0 —— 个人用第三方 Apple Music 安卓客户端
//
// 架构：Rust 核心（纯计算，可单测） + Kotlin/Compose UI（平台 + I/O + 展示）
//
// 依赖方向铁律（单向，严禁反向）：
//
//     feature-* ──▶ core-ui / core-glass / core-model / data
//     data ──▶ core-network / core-playback / core-rust
//     core-playback ──▶ core-network / core-rust
//     core-ui / core-glass ──▶ core-model
//     core-rust ──▶ (Rust .so，FFI 出口)
//
//   任何模块都不许反向依赖上层；core-* 之间也不许互相环。
//   加功能前先把涉及代码重构到合规；改 bug 顺手清坏味道。

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Yuno"

// ── 壳 ────────────────────────────────────────────────
include(":app")

// ── Rust 核心的 FFI 出口（唯一允许加载 .so 的模块）──────
include(":core-rust")

// ── 跨层模型 ─────────────────────────────────────────
include(":core-model")

// ── 视觉基元（主题/字体/噪点/玻璃底）─────────────────
include(":core-ui")

// ── 液态玻璃组件（同类项合并：迷你控制器 + 胶囊 tab 导航栏
//    + 播放控制器 + 进度条）───────────────────────────
include(":core-glass")

// ── 传输 / 播放 / 仓库 ───────────────────────────────
include(":core-network")
include(":core-playback")
include(":data")

// ── 功能页（Material 3 质感）─────────────────────────
include(":feature-home")
include(":feature-search")
include(":feature-detail")
include(":feature-library")

// ── 播放与歌词（各自独立模块）────────────────────────
include(":feature-player-full")      // 全屏播放
include(":feature-lyrics-render")    // 歌词渲染引擎（逐字/行级/等待球/字格）
include(":feature-lyrics-portrait")  // 竖屏歌词页（照搬 Apple Music）
include(":feature-lyrics-floating")  // 浮动歌词
