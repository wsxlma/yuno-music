# Yuno

做了一个第三方Apple Music客户端For Android
- 修改了原版过于美观的bug（bushi）
- 修复了原版过于省电的bug
- 加入了Liquid Glass材质
- 加入了全屏沉浸播放模式
- 加入了Material 3 元素
- 相比官方客户端没有任何优势

## 概述

Yuno 使用您个人 Apple 账号访问 Apple Music 的订阅、资料库、推荐与歌词数据

2.0 版本完成了重构（基于Rust语言），界面层按功能边界拆分为十余个独立模块。

## 与官方客户端的差异

**逐字渲染。** 官方客户端的歌词高亮为整字切换。而Yuno 采用扫光式渐变（绝对不是做不出来am的漫光式）

**设计语言。** 官方为 Apple 原生排版体系，Yuno 在容器、圆角、留白与控件层面采用 Material 3 规范，整体气质与之不同。

**架构。** 采用多模块工程结构，后期使用了Rust重构，增加了稳定性和性能。

**数据边界。** 我们保障您的隐私安全，账号凭据仅存于本机，运行日志不包含令牌，无任何数据遥测。

## 特性

**歌词特效** 使用了来自 Folia 项目的歌词动画，提供了全屏沉浸式的音乐体验，并有多种特效供您选择（本功能为beta功能，可能不稳定）

**完整曲目播放** 全链路支持完整曲目，解密失败时才自动降级至试听片段（若您播放歌曲或某首歌曲时发现仅能听30s，先看自己订阅过没过期，然后带着日志去issues提反馈）

**多源歌词** 支持 Apple TTML、LRCLIB、网易云三套歌词源，可切换。TTML 源提供逐字时间戳、背景人声、翻译与音译。歌词本地缓存，切换来源时执行时间轴健康校验

**液态玻璃底栏** 主页与资料库合并为单一胶囊，按着Apple的设计语言我们将搜索独立为一个圆形控件。Liquid glass基于 Haze 实现

**音质分档** 提供无损与高解析度无损两档，另有杜比全景声独立开关

**动态背景** 歌词页背景取自专辑封面主色，径向与线性渐变叠加，覆暗罩以保证可读性，尽力在往Am的效果上靠了

**双形态歌词页** 竖屏为居中锚定列表，横屏为滚筒，二者独立成模块，参数互不影响

## 技术栈

| | |
|---|---|
| 界面 | Kotlin · Jetpack Compose · Material 3 |
| 核心逻辑 | Rust |
| 播放 | Media3 / ExoPlayer |
| Liquid glass | Haze 2.0 |
| 网络 | OkHttp |
| 图片 | Coil 3 |
| 最低系统 | Android 8.0（API 26） |
| 推荐系统 | Android 15+（API 35 +）|

## 说明

本项目为个人用途，用于访问本人的 Apple Music 订阅内容，不进行分发，不包含任何付费功能。

本项目开发过程中借助了部分AI的辅助，如您介意，可选择停止使用。

Apple Music 是 Apple Inc. 的商标。本项目与 Apple Inc. 无关联，亦未获其授权或背书。

## 致谢

感谢 Folia 项目，本项目使用了其歌词动画效果。https://github.com/chthollyphile/folia-major

感谢 Agnes 3.0 Flash，该模型以其出色且出人意料的性能辅助我完成了对Folia的搬运。

### 内置字体

为保证歌词页跨设备排版一致，仓库内包含两款字体：

| 文件 | 字体 | 版权方 |
|---|---|---|
| `core-ui/src/main/res/font/ping_fang_sc.ttf` | PingFang SC | Apple Inc. |
| `core-ui/src/main/res/font/sf_pro.otf` | SF Pro | Apple Inc. |

上述字体为 Apple Inc. 之字体产品，其再分发受 Apple 字体许可条款约束，相关权利归 Apple Inc. 所有。此处仅标注来源，不主张任何权利。

---

L.DtQSuger · [wsxlma](https://github.com/wsxlma)
