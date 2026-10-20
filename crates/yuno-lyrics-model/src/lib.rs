//! Yuno 歌词域模型 —— **字级粒度**。
//!
//! # 为什么是「字」不是「词」
//!
//! 1.0.75 的 `Word { text, startMs, endMs }` 把「字」和「词」混成一个东西：
//! `text` 可以是词组（"AB"），于是渲染器一个 Word 一个格子、一个点亮时刻，
//! 唱到 A 就连着 B 一起亮一起放大。三个真机 bug 的根都在这里：
//!
//! 1. 逐字歌词「按词组整组亮」—— 粒度错了
//! 2. 卡拉 OK「从左到右渐渐亮」丢了 —— 字内没有时间域，无法渐变
//! 3. 有些字唱到后消失 —— 词组跨折行时裁剪矩形反转
//!
//! 本 crate 的模型把最小单位钉死成 [`Glyph`]（一个字符 + 自己的时间域），
//! 词只是字的**分组**，不再决定点亮粒度。UI 层拿到的永远是字。
//!
//! # 分层
//!
//! ```text
//! Glyph（字，渲染最小单位）→ Word（词，字的分组）→ Line（行）→ Lyrics（歌）
//! ```
//!
//! 时间戳一律相对歌曲起点，单位毫秒（`u32` 足够 70 分钟的歌）。
//!
//! # 铁律
//!
//! * 一个 [`Glyph`] **只含一个字符**，且有独立的 `[start_ms, end_ms)`。
//! * 源数据只给词级时间戳时，用 [`glyphs_from_span`] 在词内均分，
//!   这样「从左到右渐渐亮」在词内部也能逐字推进。
//! * UI 层不算时间、不算进度，一律走 `yuno-lyrics-timeline`。

#![warn(missing_docs)]

use std::fmt;

/// 一个字 —— 渲染的最小单位。
///
/// 中文/日文一个汉字是一个 [`Glyph`]；拉丁字母则是每个字母一个 [`Glyph`]，
/// 一个英文词会产出多个 [`Glyph`]，各自有各自的时间域。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Glyph {
    /// 该字的字符。
    pub ch: char,
    /// 开始唱的时刻（毫秒）。半开区间的左端。
    pub start_ms: u32,
    /// 唱完的时刻（毫秒）。半开区间的右端，恒 `> start_ms`。
    pub end_ms: u32,
}

impl Glyph {
    /// 造一个字。`end_ms` 会被夹到至少 `start_ms + 1`，保证时间域非空。
    #[inline]
    pub fn new(ch: char, start_ms: u32, end_ms: u32) -> Self {
        let end_ms = end_ms.max(start_ms.saturating_add(1));
        Self { ch, start_ms, end_ms }
    }

    /// 该字的时长（毫秒）。
    #[inline]
    pub fn duration_ms(&self) -> u32 {
        self.end_ms.saturating_sub(self.start_ms)
    }

    /// `pos_ms` 是否已经唱过这个字（`pos_ms >= end_ms`）。
    #[inline]
    pub fn is_sung_at(&self, pos_ms: u32) -> bool {
        pos_ms >= self.end_ms
    }

    /// `pos_ms` 是否正落在这个字上（`start_ms <= pos_ms < end_ms`）。
    #[inline]
    pub fn is_active_at(&self, pos_ms: u32) -> bool {
        pos_ms >= self.start_ms && pos_ms < self.end_ms
    }

    /// 字内进度 0.0..=1.0 —— 卡拉 OK「从左到右渐渐亮」的唯一输入。
    ///
    /// 还没唱到返回 0.0，唱完返回 1.0，正在唱返回线性插值。
    #[inline]
    pub fn progress_at(&self, pos_ms: u32) -> f32 {
        if pos_ms <= self.start_ms {
            return 0.0;
        }
        if pos_ms >= self.end_ms {
            return 1.0;
        }
        let span = self.duration_ms() as f32;
        ((pos_ms - self.start_ms) as f32 / span).clamp(0.0, 1.0)
    }
}

impl fmt::Display for Glyph {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.ch)
    }
}

/// 一个词 —— 只是字的**分组**，不决定点亮粒度。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Word {
    /// 词内的字，按演唱顺序排列。
    pub glyphs: Vec<Glyph>,
}

impl Word {
    /// 造一个词。
    #[inline]
    pub fn new(glyphs: Vec<Glyph>) -> Self {
        Self { glyphs }
    }

    /// 词内字数。
    #[inline]
    pub fn len(&self) -> usize {
        self.glyphs.len()
    }

    /// 词是否为空。
    #[inline]
    pub fn is_empty(&self) -> bool {
        self.glyphs.is_empty()
    }

    /// 词开始时刻；空词返回 `u32::MAX`。
    #[inline]
    pub fn start_ms(&self) -> u32 {
        self.glyphs.first().map(|g| g.start_ms).unwrap_or(u32::MAX)
    }

    /// 词结束时刻；空词返回 0。
    #[inline]
    pub fn end_ms(&self) -> u32 {
        self.glyphs.last().map(|g| g.end_ms).unwrap_or(0)
    }

    /// 词的纯文本。
    pub fn plain_text(&self) -> String {
        self.glyphs.iter().map(|g| g.ch).collect()
    }
}

/// 一行歌词。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Line {
    /// 行内词语，按演唱顺序排列。
    pub words: Vec<Word>,
    /// 行的起唱时刻（毫秒）。
    pub start_ms: u32,
    /// 是否背景人声（Apple TTML 的 `ttm:role="x-bg"`）。
    pub is_background: bool,
    /// 演唱者标记（agent），可空。
    pub agent: Option<String>,
    /// 翻译行，可空。
    pub translation: Option<String>,
    /// 音译（罗马音）行，可空。
    pub pronunciation: Option<String>,
    /// 是否**行级**歌词（普通 LRC / 无逐字时间戳）。
    ///
    /// 这种行的时间戳是估出来的（行内均分），不是真逐字；
    /// 字级粒度保留是为了「从左到右渐渐亮」照样能逐字推进，
    /// 但 UI 层可以据此降级渲染（例如不做逐字放大）。
    pub is_line_level: bool,
}

impl Line {
    /// 用词语造一行。`start_ms` 取首个字的起唱时刻。
    pub fn from_words(words: Vec<Word>) -> Self {
        let start_ms = words.iter().map(|w| w.start_ms()).min().unwrap_or(0);
        let start_ms = if start_ms == u32::MAX { 0 } else { start_ms };
        Self {
            words,
            start_ms,
            is_background: false,
            agent: None,
            translation: None,
            pronunciation: None,
            is_line_level: false,
        }
    }

    /// 行级歌词（普通 LRC / 无逐字）→ 一行。
    ///
    /// 时间戳在行内均分成字级，所以「从左到右渐渐亮」照样逐字推进，
    /// 只是时间是估的 —— [`Line::is_line_level`] 会标成 `true`。
    pub fn from_line_level(text: &str, start_ms: u32, end_ms: u32) -> Self {
        let mut l = Self::from_words(vec![word_from_span(text, start_ms, end_ms)]);
        l.is_line_level = true;
        l
    }

    /// 行内**扁平**的字序列 —— 渲染层只认这个。
    ///
    /// 用 `flat_index` 索引它，与 [`Line::glyph_count`] / [`Line::glyph_at`] 保持一致。
    pub fn glyphs(&self) -> impl Iterator<Item = &Glyph> {
        self.words.iter().flat_map(|w| w.glyphs.iter())
    }

    /// 行内字的总数。
    pub fn glyph_count(&self) -> usize {
        self.words.iter().map(|w| w.glyphs.len()).sum()
    }

    /// 按扁平下标取字；越界返回 `None`。
    pub fn glyph_at(&self, flat_index: usize) -> Option<&Glyph> {
        let mut acc = 0usize;
        for w in &self.words {
            if flat_index < acc + w.glyphs.len() {
                return w.glyphs.get(flat_index - acc);
            }
            acc += w.glyphs.len();
        }
        None
    }

    /// 字的扁平下标区间 → 词序号，便于「按词点击/长按」。
    pub fn word_index_of_glyph(&self, flat_index: usize) -> Option<usize> {
        let mut acc = 0usize;
        for (i, w) in self.words.iter().enumerate() {
            if flat_index < acc + w.glyphs.len() {
                return Some(i);
            }
            acc += w.glyphs.len();
        }
        None
    }

    /// 行的纯文本。
    pub fn plain_text(&self) -> String {
        self.glyphs().map(|g| g.ch).collect()
    }

    /// 行结束时刻：最后一个字唱完的时刻；空行回退到 `start_ms`。
    pub fn end_ms(&self) -> u32 {
        self.words.iter().map(|w| w.end_ms()).max().unwrap_or(self.start_ms)
    }

    /// 行是否空（没有任何字）。
    pub fn is_empty(&self) -> bool {
        self.glyph_count() == 0
    }
}

/// 歌词来源。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    /// Apple 私有 `syllable-lyrics` TTML（逐字、x-bg、翻译、音译）。
    AppleTtml,
    /// LRCLIB 增强 LRC（逐字）。
    LrcLibEnhanced,
    /// LRCLIB 普通 LRC（行级）。
    LrcLibPlain,
    /// 网易云 YRC。
    NetEaseYrc,
    /// 无歌词。
    None,
}

impl fmt::Display for Source {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Source::AppleTtml => "apple-ttml",
            Source::LrcLibEnhanced => "lrclib-enhanced",
            Source::LrcLibPlain => "lrclib-plain",
            Source::NetEaseYrc => "netease-yrc",
            Source::None => "none",
        })
    }
}

/// 一首歌的完整歌词。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Lyrics {
    /// 歌曲标识。
    pub song_id: String,
    /// 歌词行，按起唱时刻升序。
    pub lines: Vec<Line>,
    /// 来源。
    pub source: Source,
    /// 时间轴健康标记（入库前校验过：不倒挂、不超时长、不全 0）。
    pub valid_timeline: bool,
}

impl Default for Source {
    fn default() -> Self {
        Source::None
    }
}

impl Lyrics {
    /// 造一首歌的歌词。
    pub fn new(song_id: impl Into<String>, lines: Vec<Line>, source: Source) -> Self {
        let mut lines = lines;
        lines.sort_by_key(|l| l.start_ms);
        Self {
            song_id: song_id.into(),
            lines,
            source,
            valid_timeline: true,
        }
    }

    /// 行数。
    pub fn line_count(&self) -> usize {
        self.lines.len()
    }

    /// 是否为空。
    pub fn is_empty(&self) -> bool {
        self.lines.is_empty()
    }

    /// 全歌字数。
    pub fn glyph_count(&self) -> usize {
        self.lines.iter().map(|l| l.glyph_count()).sum()
    }

    /// 是否含**真**逐字时间戳。
    ///
    /// 行级降级的歌词（[`Line::is_line_level`]）虽然也被均分成了字，
    /// 但那时间是估的，不算逐字 —— 这里必须诚实报告。
    pub fn has_word_timing(&self) -> bool {
        self.lines
            .iter()
            .any(|l| !l.is_line_level && l.glyph_count() > 1)
    }
}

// ============================================================================
// 构造辅助：词级时间戳 → 字级时间戳
// ============================================================================

/// 把一段文本按字切开，字内均分 `[start_ms, end_ms)`。
///
/// 这是「从左到右渐渐亮」在词内部也能逐字推进的关键：
/// 源数据（尤其网易云 YRC、增强 LRC）常给的是词级时间戳，
/// 直接用会退化成「词组一起亮」——1.0.75 的 bug 1。
///
/// # 例子
///
/// ```
/// use yuno_lyrics_model::glyphs_from_span;
///
/// // 词级时间戳 1000..1400，文本 "AB" → 两个字各 200ms
/// let glyphs = glyphs_from_span("AB", 1000, 1400);
/// assert_eq!(glyphs.len(), 2);
/// assert_eq!(glyphs[0].start_ms, 1000);
/// assert_eq!(glyphs[0].end_ms, 1200);
/// assert_eq!(glyphs[1].start_ms, 1200);
/// assert_eq!(glyphs[1].end_ms, 1400);
/// ```
///
/// 空文本返回空 Vec；`end_ms` 会被夹到至少 `start_ms + 字数`，
/// 保证每个字的时间域都非空。
pub fn glyphs_from_span(text: &str, start_ms: u32, end_ms: u32) -> Vec<Glyph> {
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    if n == 0 {
        return Vec::new();
    }
    // 每个字至少 1ms，避免 0 时长导致 progress 恒为 1
    let end_ms = end_ms.max(start_ms.saturating_add(n as u32));
    let span = u64::from(end_ms - start_ms);
    let n64 = n as u64;
    chars
        .into_iter()
        .enumerate()
        .map(|(i, ch)| {
            let s = start_ms + (span * i as u64 / n64) as u32;
            let e = start_ms + (span * (i as u64 + 1) / n64) as u32;
            Glyph::new(ch, s, e.max(s.saturating_add(1)))
        })
        .collect()
}

/// 词级时间戳 + 文本 → [`Word`]。
#[inline]
pub fn word_from_span(text: &str, start_ms: u32, end_ms: u32) -> Word {
    Word::new(glyphs_from_span(text, start_ms, end_ms))
}

/// 行级歌词（普通 LRC / 无逐字）→ 一个覆盖整行的 [`Word`]。
///
/// 字级时间域在行内均分，因此即使是行级歌词，
/// 「从左到右渐渐亮」也能逐字推进（只是时间戳是估的，不是真逐字）。
#[inline]
pub fn line_level_word(text: &str, start_ms: u32, end_ms: u32) -> Word {
    word_from_span(text, start_ms, end_ms)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn glyph_time_domain_is_never_empty() {
        let g = Glyph::new('终', 1000, 1000);
        assert_eq!(g.end_ms, 1001, "end 必须被夹到 start+1");
        assert_eq!(g.duration_ms(), 1);
    }

    #[test]
    fn glyph_progress_sweeps_left_to_right() {
        let g = Glyph::new('于', 1000, 2000);
        assert_eq!(g.progress_at(0), 0.0);
        assert_eq!(g.progress_at(1000), 0.0);
        assert_eq!(g.progress_at(1500), 0.5);
        assert_eq!(g.progress_at(1999), 0.999);
        assert_eq!(g.progress_at(2000), 1.0);
        assert_eq!(g.progress_at(9999), 1.0);
    }

    #[test]
    fn span_splits_evenly_per_char() {
        let gs = glyphs_from_span("ABCDE", 1000, 2000);
        assert_eq!(gs.len(), 5);
        // 每字 200ms
        for (i, g) in gs.iter().enumerate() {
            assert_eq!(g.start_ms, 1000 + 200 * i as u32);
            assert_eq!(g.end_ms, 1200 + 200 * i as u32);
        }
    }

    #[test]
    fn span_spanans_zero_duration() {
        // 0 时长也要给每个字留时间域，否则 progress 恒 1 → 整组瞬间点亮
        let gs = glyphs_from_span("AB", 500, 500);
        assert_eq!(gs.len(), 2);
        assert!(gs[0].end_ms > gs[0].start_ms);
        assert!(gs[1].end_ms > gs[1].start_ms);
        assert!(gs[0].progress_at(500) < 1.0);
    }

    #[test]
    fn span_handles_empty_text() {
        assert!(glyphs_from_span("", 0, 100).is_empty());
    }

    #[test]
    fn span_handles_multibyte_cjk() {
        let gs = glyphs_from_span("勇气", 0, 1000);
        assert_eq!(gs.len(), 2);
        assert_eq!(gs[0].ch, '勇');
        assert_eq!(gs[1].ch, '气');
    }

    #[test]
    fn line_flat_index_is_stable_across_words() {
        let l = Line::from_words(vec![
            word_from_span("AB", 0, 400),
            word_from_span("CDE", 400, 1000),
        ]);
        assert_eq!(l.glyph_count(), 5);
        assert_eq!(l.glyph_at(0).unwrap().ch, 'A');
        assert_eq!(l.glyph_at(1).unwrap().ch, 'B');
        assert_eq!(l.glyph_at(2).unwrap().ch, 'C');
        assert_eq!(l.glyph_at(4).unwrap().ch, 'E');
        assert!(l.glyph_at(5).is_none(), "越界必须返回 None，不许 panic");
        // 词序号映射
        assert_eq!(l.word_index_of_glyph(0), Some(0));
        assert_eq!(l.word_index_of_glyph(1), Some(0));
        assert_eq!(l.word_index_of_glyph(2), Some(1));
        assert_eq!(l.word_index_of_glyph(5), None);
    }

    #[test]
    fn line_end_is_last_glyph_end() {
        let l = Line::from_words(vec![word_from_span("AB", 0, 400), word_from_span("C", 400, 900)]);
        assert_eq!(l.start_ms, 0);
        assert_eq!(l.end_ms(), 900);
    }

    #[test]
    fn lyrics_sorted_and_counts() {
        let l = Lyrics::new(
            "s1",
            vec![
                Line::from_words(vec![word_from_span("后", 5000, 6000)]),
                Line::from_words(vec![word_from_span("先", 1000, 2000)]),
            ],
            Source::AppleTtml,
        );
        assert_eq!(l.lines[0].plain_text(), "先", "必须按起唱时刻排序");
        assert_eq!(l.lines[1].plain_text(), "后");
        assert_eq!(l.line_count(), 2);
        assert_eq!(l.glyph_count(), 2);
    }

    #[test]
    fn word_timing_detection() {
        // 行级降级：时间戳是估的，必须诚实报 false
        let plain = Lyrics::new(
            "s",
            vec![Line::from_line_level("整行文本", 0, 5000)],
            Source::LrcLibPlain,
        );
        assert!(!plain.has_word_timing(), "行级不算逐字时间戳");
        assert!(plain.lines[0].is_line_level);
        assert_eq!(plain.glyph_count(), 4, "但仍按字切开，供渐亮用");

        // 真逐字
        let karaoke = Lyrics::new(
            "s",
            vec![Line::from_words(vec![word_from_span("逐字", 0, 500)])],
            Source::AppleTtml,
        );
        assert!(karaoke.has_word_timing());
    }

    #[test]
    fn empty_line_does_not_panic() {
        let l = Line::default();
        assert!(l.is_empty());
        assert_eq!(l.plain_text(), "");
        assert_eq!(l.end_ms(), 0);
        assert_eq!(l.glyph_at(0), None);
    }
}
