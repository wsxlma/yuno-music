# Yuno

第三方 Apple Music 安卓客户端。界面由 Jetpack Compose 自绘，歌词页支持逐字卡拉 OK 渲染。

## 概述

Yuno 使用本人 Apple 账号访问 Apple Music 的订阅、资料库、推荐与歌词数据，界面与交互完全独立实现，不复用官方客户端的任何视图资源。

项目以歌词渲染为起点，逐步扩展至完整播放链路、播放器界面与播放控制。2.0 版本完成了架构重构，业务核心迁移至 Rust，界面层按功能边界拆分为十余个独立模块。

## 与官方客户端的差异

**逐字渲染。** 官方客户端的歌词高亮为整字切换。Yuno 采用扫光式渐变，亮区自字形左侧推进至右侧，边界经羽化处理。演唱中的字同步产生轻微拉伸，唱毕锁定，待整句结束随滚动归位。

**亮度分层。** 当前句、已唱句、未唱句三档亮度独立控制。已唱句在换行后回落至与未唱句一致的亮度。

**设计语言。** 官方为 Apple 原生排版体系，Yuno 在容器、圆角、留白与控件层面采用 Material 3 规范，整体气质与之不同。

**架构。** 采用多模块工程结构，依赖方向由构建图强制单向。歌词时间轴、TTML 解析、清单重写等纯计算逻辑位于 Rust 侧，由单元测试覆盖。

**数据边界。** 账号凭据仅存于本机，运行日志不包含令牌，无任何数据上报。

## 特性

**逐字卡拉 OK。** 每个汉字具有独立时间域；源数据仅提供词级时间戳时，词内按字均分，确保字级推进的连续性。折行场景下跨行字符单独处理，不产生裁剪缺失或瞬时闪现。字间距、放大比例与羽化宽度均为可调参数。

**等待球。** 前奏与长间奏期间显示于标题栏与歌词之间的留白区域，三点依次点亮。间隔不足 4.75 秒时不介入。

**完整曲目播放。** 全链路支持完整曲目，解密失败时自动降级至试听片段，播放不中断。

**多源歌词。** 支持 Apple TTML、LRCLIB、网易云三套歌词源，可切换。TTML 源提供逐字时间戳、背景人声、翻译与音译。歌词本地缓存，切换来源时执行时间轴健康校验。

**地区歌曲榜。** 搜索页展示各地区 Top 25 歌曲榜，地区可切换，榜单名沿用 Apple 原文。

**液态玻璃底栏。** 主页与资料库合并为单一胶囊，搜索为独立圆形控件。基于 Haze 实现，较原生更浑浊、通透度更低，折射效果保持一致。

**音质分档。** 提供无损与高解析度无损两档，另有杜比全景声独立开关。清单流按所选档位排序。

**动态背景。** 歌词页背景取自专辑封面主色，径向与线性渐变叠加，覆暗罩以保证可读性。横屏为滚筒式布局，行距与字号随距离衰减。

**双形态歌词页。** 竖屏为居中锚定列表，横屏为滚筒，二者独立成模块，参数互不影响。

## 技术栈

| | |
|---|---|
| 界面 | Kotlin · Jetpack Compose · Material 3 |
| 核心逻辑 | Rust |
| 播放 | Media3 / ExoPlayer |
| 玻璃效果 | Haze 2.0 |
| 网络 | OkHttp |
| 图片 | Coil 3 |
| 最低系统 | Android 8.0（API 26） |

## 工程结构

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

crates/
  yuno-lyrics-model     字级歌词模型
  yuno-lyrics-timeline  时间轴与渲染契约
  yuno-lyrics-ttml      TTML / LRC 解析
  yuno-drm-rewrite      清单重写与 DRM 头构造
```

## 说明

本项目为个人用途，用于访问本人的 Apple Music 订阅内容，不进行分发，不包含任何付费功能。

Apple Music 是 Apple Inc. 的商标。本项目与 Apple Inc. 无关联，亦未获其授权或背书。

### 内置字体

为保证歌词页跨设备排版一致，仓库内包含两款字体：

| 文件 | 字体 | 权利方 |
|---|---|---|
| `core-ui/src/main/res/font/ping_fang_sc.ttf` | PingFang SC | Apple Inc. |
| `core-ui/src/main/res/font/sf_pro.otf` | SF Pro | Apple Inc. |

上述字体为 Apple Inc. 之字体产品，其再分发受 Apple 字体许可条款约束，相关权利归 Apple Inc. 所有。此处仅标注来源，不主张任何权利。

---

L.DtQSuger · [wsxlma](https://github.com/wsxlma)
