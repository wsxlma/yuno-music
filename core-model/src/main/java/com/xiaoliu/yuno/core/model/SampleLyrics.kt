package com.xiaoliu.yuno.core.model

/**
 * 内置示例歌词（需求文档钦定的预览数据）——《勇气》副歌三行。
 *
 * 用途：
 *  1. LyricsView 在没有任何歌词数据时的预览/占位渲染
 *  2. 无网络环境也能完整演示卡拉OK逐字效果
 */
object SampleLyrics {

    val lines: List<LyricLine> = listOf(
        LyricLine(
            startMs = 1000,
            words = listOf(
                Word("终", 1000, 1200),
                Word("于", 1200, 1400),
                Word("做", 1400, 1600),
                Word("了", 1600, 1800),
                Word("这", 1800, 2000),
                Word("个", 2000, 2200),
                Word("决", 2200, 2400),
                Word("定", 2400, 2600)
            )
        ),
        LyricLine(
            startMs = 3000,
            words = listOf(
                Word("别", 3000, 3200),
                Word("人", 3200, 3400),
                Word("怎", 3400, 3600),
                Word("么", 3600, 3800),
                Word("说", 3800, 4000),
                Word("我", 4000, 4200),
                Word("不", 4200, 4400),
                Word("理", 4400, 4600)
            )
        ),
        LyricLine(
            startMs = 5000,
            words = listOf(
                Word("只", 5000, 5200),
                Word("要", 5200, 5400),
                Word("你", 5400, 5600),
                Word("也", 5600, 5800),
                Word("一", 5800, 6000),
                Word("样", 6000, 6200),
                Word("的", 6200, 6400),
                Word("肯", 6400, 6600),
                Word("定", 6600, 6800)
            )
        )
    )

    val timeline: LyricsTimeline = LyricsTimeline(lines)
}
