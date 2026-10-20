package com.xiaoliu.yuno.core.ui.theme

import androidx.compose.ui.graphics.Color

// ============ Yuno 色板 ============
// 底色基调来自三张参考图：Apple Music 歌词页的深色底 + Yuno 概念图的玻璃质感。
// 原则：背景不纯黑（带一点冷调），前景靠半透明白分层而不是靠颜色层级。

/** 全局背景：接近纯黑的冷调深蓝灰 */
val YunoInk = Color(0xFF08080D)

/** 抬升表面（底部弹层、列表行、控制栏底板） */
val YunoInkSoft = Color(0xFF12121A)

val YunoTextPrimary = Color(0xFFF6F6F8)
val YunoTextSecondary = Color(0xFFB7B7C0)
val YunoTextTertiary = Color(0xFF7A7A85)

/** Apple Music 品牌红（选中态、强调、最爱心形） */
val YunoAccent = Color(0xFFFA586A)
val YunoAccentSoft = Color(0xFFFF7E90)

/** 玻璃组件高光用白（统一从这里取，别处不要写死 Color.White） */
val YunoGlassWhite = Color(0xFFFFFFFF)
