# Yuno

一个个人使用的第三方 Apple Music 安卓客户端。用 Jetpack Compose 重写了整套界面，歌词页做到了逐字卡拉 OK 的演唱跟随效果。

---

## 这是什么

Yuno 是一个绑定自己 Apple 账号的音乐播放器。它从 Apple Music 读取订阅、资料库、推荐与歌词数据，界面全部由 Compose 自绘，不使用任何官方客户端的视图资源。

项目起因是一次对歌词渲染的重做：把"整行高亮"换成"逐字跟随演唱的渐亮"。之后逐步扩展到完整播放器、歌词页动画、液态玻璃底栏，最终在 2.0.0 做了一次全量重构，把业务核心迁到 Rust。

---

## 做了什么

### 歌词渲染引擎

- **逐字卡拉 OK**：每个汉字有独立时间域，唱到时从左到右渐渐亮起，边界用羽化渐变而非硬切
- **一句话分两行时**的跨行字符单独处理，不会闪现也不会被裁掉
- **花开不谢**：字唱完放大，只要该句还在播放就保持，换句后随滚动缩回原尺寸
- **等待球**：前奏与长间奏时出现在标题栏与歌词之间的空白区，三个点依次渐亮
- 歌词源三选一（Apple TTML / LRCLIB / 网易云），支持逐字、行级、翻译、音译与背景人声
- 竖屏歌词页与横屏滚筒歌词各自独立成模块

### 播放

- 完整曲目播放，失败自动降级到 30 秒试听
- Media3/ExoPlayer 承载播放，MediaSession 负责锁屏、通知与蓝牙控制
- HLS 清单实时重写与 Widevine DRM 授权链路
- 音质分档（无损 / 高解析度无损）与杜比全景声开关

### 界面

- 液态玻璃底栏：双段胶囊 + 独立搜索钮，比原生更浑浊、折射不变
- 专辑封面色调的歌词页背景（Palette 取色 + 径向/线性渐变）
- 浮动歌词：长按歌词浮出，圆角卡片 + 反色光晕
- 设置面板、登录页、搜索页均按 Material 3 重写

### 架构

2.0.0 做了一次全量重构，拆成 16 个 Gradle 模块 + 一个 Rust 工作区：

```
app                     壳 / 导航 / DI
core-rust               FFI 出口
core-model              跨层模型
core-ui                 主题 / 字体 / 玻璃基元
core-glass              液态玻璃组件
core-network            传输 / 认证
core-playback           播放服务 / Media3 / DRM
data                    仓库层
feature-home / search / detail / library
feature-player-full     全屏播放
feature-lyrics-render   歌词渲染引擎
feature-lyrics-portrait 竖屏歌词页
feature-lyrics-floating 浮动歌词
```

```
crates/
  yuno-lyrics-model     字级粒度歌词模型
  yuno-lyrics-timeline  时间轴换算与渲染契约
  yuno-lyrics-ttml      TTML / LRC 解析
  yuno-drm-rewrite      清单重写与 DRM 头构造
```

依赖方向严格单向（`feature-* → core-* → data → core-*`），由 Gradle 构建图强制，反向依赖无法编译。歌词的时间轴与解析逻辑放在 Rust 侧，共 52 个单元测试覆盖，其中包括"任意时刻最多只有一个字处于放大中"这类不变式。

---

## 技术选择

| | |
|---|---|
| 界面 | Kotlin · Jetpack Compose · Material 3 |
| 核心逻辑 | Rust（uniffi FFI） |
| 播放 | Media3 / ExoPlayer |
| 玻璃效果 | Haze 2.0 |
| 网络 | OkHttp |
| 图片 | Coil 3 |
| 最低系统 | Android 8.0（API 26） |

---

## 说明

这是一个个人项目，用于访问**自己的** Apple Music 订阅内容，不面向分发、不接入支付。

Apple Music 是 Apple Inc. 的商标。本项目与 Apple Inc. 无任何关联，也未获其认可或赞助。

### 关于内置字体

为了让歌词页在不同设备上保持一致的排版观感，仓库内打包了两款字体：

| 文件 | 字体 | 权利方 |
|---|---|---|
| `core-ui/src/main/res/font/ping_fang_sc.ttf` | 苹方 PingFang SC | Apple Inc. |
| `core-ui/src/main/res/font/sf_pro.otf` | SF Pro | Apple Inc. |

这两个字体均为 Apple Inc. 的字体产品，其再分发受 Apple 的字体许可条款约束，权利归 Apple Inc. 所有。此处仅标注来源，不主张任何权利。使用者应自行确认其使用方式符合相应许可。

---

**L.DtQSuger**（GitHub ID：[wsxlmyy](https://github.com/wsxlmyy)）
