//! Yuno 歌词时间轴 —— `positionMs → 行 / 字 / 渲染状态` 的**唯一换算入口**。
//!
//! # 铁律
//!
//! UI 层不许自己算时间、不许自己算进度，一律走这里。
//! 1.0.75 的闪退（`wordIndex` 越界）和「多字一起放大」都是 UI 各处自算进度导致的。
//!
//! # 卡拉 OK 渲染契约
//!
//! [`Timeline::paint`] 返回行内**每个字**的渲染状态（[`GlyphPaint`]），
//! 三条规则就是官方 Apple Music 的 `notifyWordHighlight(lineId, wordId, duration, isSung)` 的等价物：
//!
//! | 字的状态 | `reveal` | `scale` | `locked` |
//! |---|---|---|---|
//! | 还没唱到 | `0.0` | `1.0` | `false` |
//! | **正在唱** | `0.0 → 1.0` 字内线性 | `1.0 → 1.08` 同步 | `false` |
//! | 已唱完 | `1.0` | `1.08` | `true` |
//!
//! 「正在唱」那一行就是**从左到右渐渐亮**：`reveal` 是字内的揭示比例，
//! 渲染层按它切从左到右的裁剪宽度；`scale` 只跟着当前字长，
//! 所以永远不会出现「AB 一起放大」——A 长的时候 B 还是 1.0。

#![warn(missing_docs)]

use yuno_lyrics_model::{Glyph, Line, Lyrics};

/// 已唱字的锁定缩放（「花开不谢」：唱过就保持放大，不回弹）。
pub const SUNG_SCALE: f32 = 1.08;

/// 未唱字的缩放。
pub const BASE_SCALE: f32 = 1.0;

/// 一个字在某时刻的渲染状态。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GlyphPaint {
    /// 该字在行内的**扁平下标**（与 [`Line::glyph_at`] 一致）。
    pub index: usize,
    /// 从左到右的揭示比例 `0.0..=1.0` —— 渲染层按它切字内的裁剪宽度。
    pub reveal: f32,
    /// 当前缩放：未唱 1.0，正在唱 `1.0 → 1.08`，已唱锁定 1.08。
    pub scale: f32,
    /// 是否已完整唱过（锁定态，之后不再变化）。
    pub locked: bool,
}

impl GlyphPaint {
    /// 未唱状态。
    #[inline]
    pub fn unsung(index: usize) -> Self {
        Self {
            index,
            reveal: 0.0,
            scale: BASE_SCALE,
            locked: false,
        }
    }

    /// 是否正在唱（有渐变动画在跑）。
    #[inline]
    pub fn is_active(&self) -> bool {
        !self.locked && self.reveal > 0.0
    }
}

/// 歌词时间轴。
///
/// 构造时把行按起唱时刻排序，之后所有查询都是二分（`O(log n)`）。
#[derive(Debug, Clone)]
pub struct Timeline {
    lines: Vec<Line>,
}

impl Timeline {
    /// 造一个时间轴。行会被按起唱时刻排序。
    pub fn new(lines: Vec<Line>) -> Self {
        let mut lines = lines;
        lines.sort_by_key(|l| l.start_ms);
        Self { lines }
    }

    /// 从 [`Lyrics`] 构造。
    #[inline]
    pub fn from_lyrics(lyrics: &Lyrics) -> Self {
        Self::new(lyrics.lines.clone())
    }

    /// 行数。
    #[inline]
    pub fn len(&self) -> usize {
        self.lines.len()
    }

    /// 是否空。
    #[inline]
    pub fn is_empty(&self) -> bool {
        self.lines.is_empty()
    }

    /// 按序号取行；越界返回 `None`（**不 panic**）。
    #[inline]
    pub fn line(&self, index: usize) -> Option<&Line> {
        self.lines.get(index)
    }

    /// 全部行（已排序）。
    #[inline]
    pub fn lines(&self) -> &[Line] {
        &self.lines
    }

    // ------------------------------------------------------------------
    // 行级查询
    // ------------------------------------------------------------------

    /// 二分：`pos_ms` 落在哪一行（最后一个 `start_ms <= pos_ms` 的行）。
    ///
    /// 还没唱到第一行返回 `None` —— **不再用 -1 表示**，
    /// 从类型上消灭 1.0.75 那类 `-1` 越界闪退。
    pub fn line_index_at(&self, pos_ms: u32) -> Option<usize> {
        if self.lines.is_empty() {
            return None;
        }
        let mut lo = 0usize;
        let mut hi = self.lines.len() - 1;
        let mut ans: Option<usize> = None;
        while lo <= hi {
            let mid = (lo + hi) / 2;
            if self.lines[mid].start_ms <= pos_ms {
                ans = Some(mid);
                lo = mid + 1;
            } else {
                if mid == 0 {
                    break;
                }
                hi = mid - 1;
            }
        }
        ans
    }

    /// 行结束时刻：优先下一行起唱，兜底行内最后一个字唱完，再兜底 `start + 5s`。
    pub fn line_end_ms(&self, index: usize) -> u32 {
        let Some(line) = self.lines.get(index) else {
            return 0;
        };
        let word_end = line.end_ms();
        if word_end > line.start_ms {
            return word_end;
        }
        match self.lines.get(index + 1) {
            Some(next) if next.start_ms > line.start_ms => next.start_ms,
            _ => line.start_ms.saturating_add(5_000),
        }
    }

    /// 行整体进度 `0.0..=1.0`（行级降级歌词的整行渐亮用）。
    pub fn line_progress(&self, index: usize, pos_ms: u32) -> f32 {
        let Some(line) = self.lines.get(index) else {
            return 0.0;
        };
        let start = line.start_ms;
        let end = self.line_end_ms(index).max(start + 1);
        if pos_ms <= start {
            return 0.0;
        }
        if pos_ms >= end {
            return 1.0;
        }
        (pos_ms - start) as f32 / (end - start) as f32
    }

    // ------------------------------------------------------------------
    // 字级查询
    // ------------------------------------------------------------------

    /// 行内二分：`pos_ms` 落在第几个字（扁平下标）。
    ///
    /// 还没唱到第一个字返回 `None`。
    pub fn glyph_index_at(&self, line: &Line, pos_ms: u32) -> Option<usize> {
        let n = line.glyph_count();
        if n == 0 {
            return None;
        }
        let mut lo = 0usize;
        let mut hi = n - 1;
        let mut ans: Option<usize> = None;
        while lo <= hi {
            let mid = (lo + hi) / 2;
            match line.glyph_at(mid) {
                Some(g) if g.start_ms <= pos_ms => {
                    ans = Some(mid);
                    lo = mid + 1;
                }
                _ => {
                    if mid == 0 {
                        break;
                    }
                    hi = mid - 1;
                }
            }
        }
        ans
    }

    /// 行内已揭示的字数（含正在揭示的字按小数计）。
    ///
    /// 0.0 = 一个都没亮；`n` = 整行全亮。
    pub fn revealed_glyph_count(&self, line: &Line, pos_ms: u32) -> f32 {
        let Some(idx) = self.glyph_index_at(line, pos_ms) else {
            return 0.0;
        };
        let Some(g) = line.glyph_at(idx) else {
            return 0.0;
        };
        idx as f32 + g.progress_at(pos_ms)
    }

    // ------------------------------------------------------------------
    // 渲染契约
    // ------------------------------------------------------------------

    /// 算出一行里**每个字**的渲染状态 —— 卡拉 OK 的唯一真身。
    ///
    /// 返回的 Vec 顺序与 [`Line::glyph_at`] 的扁平下标一致，
    /// 长度恒等于 [`Line::glyph_count`]。
    pub fn paint(&self, line: &Line, pos_ms: u32) -> Vec<GlyphPaint> {
        let n = line.glyph_count();
        let mut out = Vec::with_capacity(n);
        for i in 0..n {
            let Some(g) = line.glyph_at(i) else {
                out.push(GlyphPaint::unsung(i));
                continue;
            };
            out.push(paint_glyph(i, g, pos_ms));
        }
        out
    }

    /// 只算「当前字」的索引与其渐变进度 —— 渲染层要快速拿这两个值时用。
    ///
    /// 返回 `(字下标, 字内进度)`；还没唱到第一个字返回 `None`。
    pub fn active_glyph(&self, line: &Line, pos_ms: u32) -> Option<(usize, f32)> {
        let idx = self.glyph_index_at(line, pos_ms)?;
        let g = line.glyph_at(idx)?;
        Some((idx, g.progress_at(pos_ms)))
    }

    // ------------------------------------------------------------------
    // 空档（前奏 / 长间奏）—— 等待球
    // ------------------------------------------------------------------

    /// `pos_ms` 是否落在歌词空档（前奏 / 长间奏）。
    ///
    /// * 前奏：第一行在 0.8s 之后起唱，且 `pos` 还没到第一行
    /// * 间奏：相邻两行间隔 ≥ 2.5s，且 `pos` 落在间隔中段（前后各留余量防闪烁）
    pub fn is_in_gap(&self, pos_ms: u32) -> bool {
        let Some(first) = self.lines.first() else {
            return false;
        };
        if pos_ms < first.start_ms {
            return first.start_ms > 800;
        }
        for i in 0..self.lines.len().saturating_sub(1) {
            // 行的真实结束 = 行内最后一个字唱完；空行（没字）不算空档
            let end = self.lines[i].end_ms();
            if end <= self.lines[i].start_ms {
                continue;
            }
            let next_start = self.lines[i + 1].start_ms;
            if next_start <= end {
                continue;
            }
            let gap = next_start - end;
            // 真机 Beta13：间奏不足 4.75 秒不显示等待球（与 Kotlin 侧 LyricsTimeline 保持一致）
            if gap >= 4_750 && pos_ms >= end.saturating_add(400) && pos_ms < next_start.saturating_sub(600) {
                return true;
            }
        }
        false
    }
}

/// 单个字的渲染状态（纯函数，便于单测）。
fn paint_glyph(index: usize, g: &Glyph, pos_ms: u32) -> GlyphPaint {
    let p = g.progress_at(pos_ms);
    if p >= 1.0 {
        GlyphPaint {
            index,
            reveal: 1.0,
            scale: SUNG_SCALE,
            locked: true,
        }
    } else if p <= 0.0 {
        GlyphPaint::unsung(index)
    } else {
        GlyphPaint {
            index,
            reveal: p,
            // 只有当前字在长，其它字不动 —— 根治「AB 一起放大」
            scale: BASE_SCALE + (SUNG_SCALE - BASE_SCALE) * p,
            locked: false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use yuno_lyrics_model::{glyphs_from_span, word_from_span, Line, Lyrics, Source};

    fn sample_line() -> Line {
        // "AB" 在 0..400（每字 200ms），"CDE" 在 400..1000（每字 200ms）
        Line::from_words(vec![
            word_from_span("AB", 0, 400),
            word_from_span("CDE", 400, 1000),
        ])
    }

    #[test]
    fn line_index_none_before_first_line() {
        let tl = Timeline::new(vec![
            Line::from_words(vec![word_from_span("一", 5000, 6000)]),
            Line::from_words(vec![word_from_span("二", 7000, 8000)]),
        ]);
        assert_eq!(tl.line_index_at(0), None, "还没唱到第一行要 None，不是 -1");
        assert_eq!(tl.line_index_at(5000), Some(0));
        assert_eq!(tl.line_index_at(6500), Some(0));
        assert_eq!(tl.line_index_at(7000), Some(1));
        assert_eq!(tl.line_index_at(99999), Some(1));
    }

    #[test]
    fn empty_timeline_is_safe() {
        let tl = Timeline::new(vec![]);
        assert!(tl.is_empty());
        assert_eq!(tl.line_index_at(0), None);
        assert_eq!(tl.line(0), None);
        assert_eq!(tl.glyph_index_at(&Line::default(), 0), None);
        assert!(!tl.is_in_gap(0));
    }

    #[test]
    fn paint_gives_every_glyph_a_slot() {
        let tl = Timeline::new(vec![sample_line()]);
        let line = tl.line(0).unwrap();
        let paints = tl.paint(line, 500);
        assert_eq!(paints.len(), 5);
        for (i, p) in paints.iter().enumerate() {
            assert_eq!(p.index, i);
        }
    }

    #[test]
    fn paint_sweeps_left_to_right_within_a_word() {
        // 这是 bug 1 + bug 2 的回归测试：词组 "AB" 必须逐字亮，不许一起亮
        let tl = Timeline::new(vec![sample_line()]);
        let line = tl.line(0).unwrap();

        // t=100：A 正在唱（0→200），B 还没唱
        let p = tl.paint(line, 100);
        assert!((p[0].reveal - 0.5).abs() < 1e-4, "A 应揭示一半，实际 {}", p[0].reveal);
        assert_eq!(p[1].reveal, 0.0, "B 还没唱，不许提前亮");
        assert!(p[0].scale > 1.0 && p[0].scale < SUNG_SCALE, "只有 A 在长");
        assert_eq!(p[1].scale, 1.0, "B 不许跟着放大");

        // t=300：A 唱完锁定，B 正在唱
        let p = tl.paint(line, 300);
        assert_eq!(p[0].reveal, 1.0);
        assert_eq!(p[0].scale, SUNG_SCALE);
        assert!(p[0].locked);
        assert!((p[1].reveal - 0.5).abs() < 1e-4);
        assert!(!p[1].locked);
    }

    #[test]
    fn sung_glyphs_stay_locked() {
        let tl = Timeline::new(vec![sample_line()]);
        let line = tl.line(0).unwrap();

        // t=900：A B C D 唱完，E 正在唱（800..1000 → 0.5）
        let p = tl.paint(line, 900);
        for i in 0..4 {
            assert_eq!(p[i].reveal, 1.0);
            assert_eq!(p[i].scale, SUNG_SCALE, "唱过的字保持 1.08，不回弹");
            assert!(p[i].locked);
        }
        assert!((p[4].reveal - 0.5).abs() < 1e-4, "E 正在唱，应揭示一半");
        assert!(!p[4].locked);

        // t=1100（已过行末）：全锁定，且不许回弹
        let p = tl.paint(line, 1100);
        for (i, x) in p.iter().enumerate() {
            assert_eq!(x.reveal, 1.0, "字 {i} 该全亮");
            assert_eq!(x.scale, SUNG_SCALE, "字 {i} 该保持 1.08");
            assert!(x.locked);
        }
    }

    #[test]
    fn future_glyphs_stay_dark() {
        let tl = Timeline::new(vec![sample_line()]);
        let line = tl.line(0).unwrap();
        // t=100：A 在唱，B C D E 全暗
        let p = tl.paint(line, 100);
        for i in 1..5 {
            assert_eq!(p[i].reveal, 0.0, "字 {i} 还没唱到，必须全暗");
            assert_eq!(p[i].scale, 1.0);
        }
    }

    #[test]
    fn never_more_than_one_glyph_growing() {
        // 核心不变式：任意时刻，处于「生长中」的字最多 1 个
        let tl = Timeline::new(vec![sample_line()]);
        let line = tl.line(0).unwrap();
        for t in 0..1100 {
            let paints = tl.paint(line, t);
            let growing = paints
                .iter()
                .filter(|p| !p.locked && p.reveal > 0.0)
                .count();
            assert!(growing <= 1, "t={t} 有 {growing} 个字同时在长（应 ≤1）");
        }
    }

    #[test]
    fn revealed_count_includes_partial() {
        let tl = Timeline::new(vec![sample_line()]);
        let line = tl.line(0).unwrap();
        assert_eq!(tl.revealed_glyph_count(line, 0), 0.0);
        assert!((tl.revealed_glyph_count(line, 100) - 0.5).abs() < 1e-4);
        assert_eq!(tl.revealed_glyph_count(line, 1000), 5.0);
    }

    #[test]
    fn active_glyph_is_exactly_the_singing_one() {
        let tl = Timeline::new(vec![sample_line()]);
        let line = tl.line(0).unwrap();
        assert_eq!(tl.active_glyph(line, 0), Some((0, 0.0)));
        assert_eq!(tl.active_glyph(line, 300), Some((1, 0.5)));
        assert_eq!(tl.active_glyph(line, 500), Some((2, 0.5)));
    }

    #[test]
    fn line_level_lyrics_also_sweep() {
        // 行级降级歌词也要能从左到右渐亮（bug 2 不能只在逐字源上修好）
        let tl = Timeline::new(vec![Line::from_words(vec![yuno_lyrics_model::line_level_word(
            "勇气", 0, 1000,
        )])]);
        let line = tl.line(0).unwrap();
        let p = tl.paint(line, 250);
        assert_eq!(p.len(), 2);
        assert!(p[0].reveal > 0.0 && p[1].reveal < 1.0, "行级也要逐字推进");
    }

    #[test]
    fn gap_detection_for_intro_and_interlude() {
        let tl = Timeline::new(vec![
            Line::from_words(vec![word_from_span("一", 3000, 3500)]),
            Line::from_words(vec![word_from_span("二", 9000, 9500)]),
        ]);
        assert!(tl.is_in_gap(1000), "前奏（3s 后才起唱）");
        assert!(!tl.is_in_gap(3200), "正在唱");
        assert!(tl.is_in_gap(6000), "长间奏中段");
    }

    #[test]
    fn timeline_from_lyrics_matches_direct() {
        let lyrics = Lyrics::new(
            "s",
            vec![
                Line::from_words(vec![word_from_span("后", 5000, 6000)]),
                Line::from_words(vec![word_from_span("先", 1000, 2000)]),
            ],
            Source::AppleTtml,
        );
        let tl = Timeline::from_lyrics(&lyrics);
        assert_eq!(tl.len(), 2);
        assert_eq!(tl.line(0).unwrap().plain_text(), "先");
    }

    #[test]
    fn glyph_index_out_of_bounds_is_none_not_panic() {
        let tl = Timeline::new(vec![sample_line()]);
        let line = tl.line(0).unwrap();
        assert_eq!(tl.glyph_index_at(line, 0), Some(0));
        // 空行
        let empty = Line::default();
        assert_eq!(tl.glyph_index_at(&empty, 0), None);
    }

    #[test]
    fn span_regression_word_group_must_not_light_together() {
        // 直接对着 1.0.75 的症状："唱到 A 时 AB 一起放大"
        let glyphs = glyphs_from_span("AB", 1000, 1400);
        let l = Line::from_words(vec![yuno_lyrics_model::Word::new(glyphs)]);
        let tl = Timeline::new(vec![l]);
        let line = tl.line(0).unwrap();
        let p = tl.paint(line, 1100);
        assert!(p[0].reveal > 0.0, "A 该在亮");
        assert_eq!(p[1].reveal, 0.0, "B 不许跟着亮");
    }
}
