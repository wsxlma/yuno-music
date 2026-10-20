//! 歌词解析 —— **TTML**（Apple 私有 `syllable-lyrics`）+ **LRC / 增强 LRC**（LRCLIB）。
//!
//! 移植自 `Yuno.old/core-network/.../LyricsParser.kt`（我们自己的代码），
//! 但有一个关键改动：**产出的 [`Word`] 内部按字切开**（[`yuno_lyrics_model::glyphs_from_span`]）。
//!
//! 1.0.75 的 Kotlin 版把一个 `<span>` 的全部文本塞进一个 `Word`，
//! 于是 "AB" 两个字共用一个时间域 → 渲染时一起亮、一起放大（真机 bug 1）。
//! 这里从解析层就杜绝：**span 只是时间域，字才是渲染单位**。
//!
//! # TTML 规范要点
//!
//! * 每行一个 `<p begin end>`，字级 `<span begin end>`，可能嵌套 → 摊平
//! * 时间格式 `00:00:01.234` / `01:02.345` / `12.34` / `1234ms` / `1.2s`
//! * 背景人声：`role="x-bg"` / `class` 含 background / `agent` 含 background
//! * 命名空间若干（`ttml:` `itunes:`）—— **一律按 localName 匹配**，忽略命名空间

#![warn(missing_docs)]

use yuno_lyrics_model::{word_from_span, Line, Word};

/// 解析产物。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Parsed {
    /// 歌词行，按起唱时刻升序。
    pub lines: Vec<Line>,
    /// 是否**逐字**（true 走卡拉 OK 渲染，false 走行级渲染）。
    pub word_level: bool,
}

impl Parsed {
    /// 是否空。
    pub fn is_empty(&self) -> bool {
        self.lines.is_empty()
    }
}

// ============================================================================
// 时间解析
// ============================================================================

/// 时间字符串 → 毫秒。
///
/// 支持 `00:00:01.234`（时:分:秒）、`01:02.345`（分:秒）、`12.34`（秒）、
/// `1234ms`、`1.2s`。解析不了返回 `None`。
///
/// # 例子
///
/// ```
/// use yuno_lyrics_ttml::parse_time_ms;
/// assert_eq!(parse_time_ms("00:00:01.234"), Some(1234));
/// assert_eq!(parse_time_ms("01:02.345"), Some(62_345));
/// assert_eq!(parse_time_ms("1234ms"), Some(1234));
/// ```
pub fn parse_time_ms(raw: &str) -> Option<u32> {
    let s = raw.trim();
    if s.is_empty() {
        return None;
    }
    // 毫秒后缀
    if let Some(body) = s.strip_suffix("ms").or_else(|| s.strip_suffix("MS")) {
        return body.trim().parse::<f64>().ok().map(|v| v.max(0.0) as u32);
    }
    // 秒后缀（注意别把 "ms" 的 m 误判成 s）
    let body = match s.strip_suffix('s').or_else(|| s.strip_suffix('S')) {
        Some(b) if !b.ends_with('m') && !b.ends_with('M') => b,
        _ => s,
    };

    let parts: Vec<&str> = body.split(':').collect();
    let ms = match parts.len() {
        3 => {
            let h: f64 = parts[0].trim().parse().ok()?;
            let m: f64 = parts[1].trim().parse().ok()?;
            let sec: f64 = parts[2].trim().parse().ok()?;
            ((h * 3600.0 + m * 60.0) * 1000.0) + sec * 1000.0
        }
        2 => {
            let m: f64 = parts[0].trim().parse().ok()?;
            let sec: f64 = parts[1].trim().parse().ok()?;
            (m * 60.0 * 1000.0) + sec * 1000.0
        }
        1 => {
            let sec: f64 = parts[0].trim().parse().ok()?;
            sec * 1000.0
        }
        _ => return None,
    };
    if !ms.is_finite() || ms < 0.0 {
        return None;
    }
    Some(ms as u32)
}

// ============================================================================
// 极简 XML 扫描（不引依赖）
// ============================================================================

#[derive(Debug, Clone, PartialEq)]
enum Token {
    /// 开标签（含自闭合，自闭合的 `self_closing = true`）
    Start {
        name: String,
        attrs: Vec<(String, String)>,
        self_closing: bool,
    },
    /// 闭标签
    End {
        name: String,
    },
    /// 文本
    Text(String),
}

/// 取 localName（忽略命名空间前缀）。
fn local_name(name: &str) -> &str {
    match name.rfind(':') {
        Some(i) => &name[i + 1..],
        None => name,
    }
}

/// 解析单个标签体（`<` 和 `>` 之间那段）。
fn parse_tag(inner: &str) -> Option<Token> {
    let s = inner.trim();
    if let Some(rest) = s.strip_prefix('/') {
        let name = local_name(rest.trim()).to_string();
        return Some(Token::End { name });
    }
    let (body, self_closing) = match s.strip_suffix('/') {
        Some(b) => (b.trim_end(), true),
        None => (s, false),
    };
    // 名字 = 第一个空白前
    let name_end = body.find(char::is_whitespace).unwrap_or(body.len());
    let name = local_name(&body[..name_end]).to_string();
    let mut attrs = Vec::new();
    let mut rest = &body[name_end..];
    while !rest.is_empty() {
        rest = rest.trim_start();
        if rest.is_empty() {
            break;
        }
        // 属性名
        let eq = match rest.find('=') {
            Some(i) => i,
            None => break,
        };
        let key_raw = rest[..eq].trim();
        let key = local_name(key_raw).to_ascii_lowercase();
        rest = rest[eq + 1..].trim_start();
        // 属性值
        let (val, next) = if let Some(c) = rest.chars().next() {
            if c == '"' || c == '\'' {
                let q = c;
                let after = &rest[c.len_utf8()..];
                match after.find(q) {
                    Some(e) => (after[..e].to_string(), &after[e + c.len_utf8()..]),
                    None => break,
                }
            } else {
                let e = rest.find(char::is_whitespace).unwrap_or(rest.len());
                (rest[..e].to_string(), &rest[e..])
            }
        } else {
            break;
        };
        if !key.is_empty() {
            attrs.push((key, val));
        }
        rest = next;
    }
    Some(Token::Start {
        name,
        attrs,
        self_closing,
    })
}

/// 把 XML 切成标签/文本流。容错：注释、`<?xml?>`、`<!DOCTYPE>` 直接跳过。
fn tokenize(xml: &str) -> Vec<Token> {
    let mut out = Vec::new();
    let mut rest = xml;
    loop {
        let Some(i) = rest.find('<') else {
            if !rest.trim().is_empty() {
                out.push(Token::Text(rest.to_string()));
            }
            break;
        };
        if i > 0 && !rest[..i].trim().is_empty() {
            out.push(Token::Text(rest[..i].to_string()));
        }
        let after = &rest[i..];
        if let Some(tail) = after.strip_prefix("<!--") {
            match tail.find("-->") {
                Some(e) => {
                    rest = &tail[e + 3..];
                    continue;
                }
                None => break,
            }
        }
        if after.starts_with("<?") || after.starts_with("<!") {
            match after.find('>') {
                Some(e) => {
                    rest = &after[e + 1..];
                    continue;
                }
                None => break,
            }
        }
        let Some(e) = after.find('>') else { break };
        if let Some(tok) = parse_tag(&after[1..e]) {
            out.push(tok);
        }
        rest = &after[e + 1..];
    }
    out
}

fn attr<'a>(attrs: &'a [(String, String)], target: &str) -> Option<&'a str> {
    attrs
        .iter()
        .find(|(k, _)| k.eq_ignore_ascii_case(target))
        .map(|(_, v)| v.as_str())
}

fn is_background(attrs: &[(String, String)]) -> bool {
    let role = attr(attrs, "role").unwrap_or("");
    let class = attr(attrs, "class").unwrap_or("");
    let agent = attr(attrs, "agent").unwrap_or("");
    role.to_ascii_lowercase().contains("x-bg")
        || class.to_ascii_lowercase().contains("background")
        || agent.to_ascii_lowercase().contains("background")
}

// ============================================================================
// TTML
// ============================================================================

/// 解析 Apple 私有 TTML 歌词。失败返回 `None`（**不 panic**）。
pub fn parse_ttml(xml: &str) -> Option<Parsed> {
    let tokens = tokenize(xml);
    if tokens.is_empty() {
        return None;
    }

    let mut lines: Vec<Line> = Vec::new();

    let mut in_line = false;
    let mut line_start = 0u32;
    let mut line_end = 0u32;
    let mut line_agent: Option<String> = None;
    let mut line_bg = false;
    let mut line_plain = String::new();
    let mut line_words: Vec<Word> = Vec::new();

    let mut in_span = false;
    let mut span_start = 0u32;
    let mut span_end = 0u32;
    let mut span_text = String::new();

    for tok in &tokens {
        match tok {
            Token::Start {
                name,
                attrs,
                self_closing,
            } => match name.as_str() {
                "p" => {
                    // 上一行收尾
                    finish_line(
                        &mut lines,
                        in_line,
                        line_start,
                        line_end,
                        &line_agent,
                        line_bg,
                        &line_plain,
                        &line_words,
                    );
                    in_line = true;
                    line_start = parse_time_ms(attr(attrs, "begin").unwrap_or("")).unwrap_or(0);
                    line_end = parse_time_ms(attr(attrs, "end").unwrap_or("")).unwrap_or(0);
                    line_agent = attr(attrs, "agent").map(|s| s.to_string());
                    line_bg = is_background(attrs);
                    line_plain.clear();
                    line_words.clear();
                    if *self_closing {
                        finish_line(
                            &mut lines,
                            in_line,
                            line_start,
                            line_end,
                            &line_agent,
                            line_bg,
                            &line_plain,
                            &line_words,
                        );
                        in_line = false;
                    }
                }
                "span" if in_line => {
                    // 嵌套 span 直接摊平：新 span 开始时把上一个欠账收掉
                    if in_span {
                        finish_span(
                            &mut line_words,
                            &span_text,
                            span_start,
                            span_end,
                            line_start,
                        );
                    }
                    in_span = true;
                    span_start = parse_time_ms(attr(attrs, "begin").unwrap_or("")).unwrap_or(line_start);
                    span_end = parse_time_ms(attr(attrs, "end").unwrap_or("")).unwrap_or(0);
                    if is_background(attrs) {
                        line_bg = true;
                    }
                    if line_agent.is_none() {
                        line_agent = attr(attrs, "agent").map(|s| s.to_string());
                    }
                    span_text.clear();
                    if *self_closing {
                        finish_span(
                            &mut line_words,
                            &span_text,
                            span_start,
                            span_end,
                            line_start,
                        );
                        in_span = false;
                    }
                }
                _ => {}
            },
            Token::End { name } => match name.as_str() {
                "span" => {
                    if in_span {
                        finish_span(
                            &mut line_words,
                            &span_text,
                            span_start,
                            span_end,
                            line_start,
                        );
                        in_span = false;
                    }
                }
                "p" => {
                    finish_line(
                        &mut lines,
                        in_line,
                        line_start,
                        line_end,
                        &line_agent,
                        line_bg,
                        &line_plain,
                        &line_words,
                    );
                    in_line = false;
                }
                _ => {}
            },
            Token::Text(t) => {
                if in_span {
                    span_text.push_str(t);
                } else if in_line {
                    line_plain.push_str(t);
                }
            }
        }
    }
    // 文档末尾的欠账
    if in_span {
        finish_span(&mut line_words, &span_text, span_start, span_end, line_start);
    }
    finish_line(
        &mut lines,
        in_line,
        line_start,
        line_end,
        &line_agent,
        line_bg,
        &line_plain,
        &line_words,
    );

    if lines.is_empty() {
        return None;
    }
    let word_level = lines.iter().any(|l| !l.is_line_level);
    let lines = normalize(lines);
    Some(Parsed { lines, word_level })
}

fn finish_span(
    line_words: &mut Vec<Word>,
    text: &str,
    start: u32,
    end: u32,
    line_start: u32,
) {
    let t = text.trim();
    if t.is_empty() {
        return;
    }
    let start = if end > start { start } else { start.max(line_start) };
    let end = if end > start { end } else { start + 300 };
    // 关键：span 的文本按字切开，每个字有自己的时间域
    line_words.push(word_from_span(t, start, end));
}

#[allow(clippy::too_many_arguments)]
fn finish_line(
    lines: &mut Vec<Line>,
    in_line: bool,
    line_start: u32,
    line_end: u32,
    line_agent: &Option<String>,
    line_bg: bool,
    line_plain: &str,
    line_words: &[Word],
) {
    if !in_line {
        return;
    }
    let bg = line_bg
        || line_agent
            .as_deref()
            .map(|a| a.to_ascii_lowercase().contains("background"))
            .unwrap_or(false);
    let agent = line_agent.clone();

    let line = if !line_words.is_empty() {
        let mut l = Line::from_words(line_words.to_vec());
        l.start_ms = line_start;
        l.is_background = bg;
        l.agent = agent;
        Some(l)
    } else {
        let text = line_plain.trim();
        if text.is_empty() {
            None
        } else {
            let end = if line_end > line_start {
                line_end
            } else {
                line_start + 3_000
            };
            let mut l = Line::from_line_level(text, line_start, end);
            l.is_background = bg;
            l.agent = agent;
            Some(l)
        }
    };
    if let Some(l) = line {
        lines.push(l);
    }
}

// ============================================================================
// LRC / 增强 LRC
// ============================================================================

/// 解析 LRC 文本。
///
/// * 行级：`[00:12.34]歌词`
/// * 增强（逐字）：`[00:12.34]<00:12.34>终<00:12.79>于`
///
/// 自动识别；[`Parsed::word_level`] 表示整首歌是否有逐字。
pub fn parse_lrc(text: &str) -> Option<Parsed> {
    #[derive(Clone)]
    struct Pending {
        start_ms: u32,
        words: Vec<Word>,
        line_text: String,
        enhanced: bool,
    }

    let mut pending: Vec<Pending> = Vec::new();
    let mut any_enhanced = false;

    for raw in text.lines() {
        let line_tags = find_line_tags(raw);
        if line_tags.is_empty() {
            continue; // [ar:] [ti:] 等元数据行天然跳过
        }
        let starts: Vec<u32> = line_tags.iter().map(|t| t.0).collect();
        let content_start = line_tags.last().unwrap().1;
        let content = &raw[content_start..];
        let word_tags = find_word_tags(content);

        if word_tags.is_empty() {
            let line_text = content.trim().to_string();
            if line_text.is_empty() {
                continue;
            }
            for st in starts {
                pending.push(Pending {
                    start_ms: st,
                    words: Vec::new(),
                    line_text: line_text.clone(),
                    enhanced: false,
                });
            }
        } else {
            any_enhanced = true;
            let mut raws: Vec<(u32, &str)> = Vec::new();
            for (i, (w_start, text_start, tag_start)) in word_tags.iter().enumerate() {
                // 文本到下一个标签的 '<' 为止，绝不能把标签本身吃进来
                let text_end = word_tags
                    .get(i + 1)
                    .map(|(_, _, next_tag_start)| *next_tag_start)
                    .unwrap_or(content.len());
                if *text_start > text_end {
                    continue;
                }
                let w_text = &content[*text_start..text_end];
                if !w_text.is_empty() {
                    raws.push((*w_start, w_text));
                }
                let _ = tag_start;
            }
            if raws.is_empty() {
                continue;
            }
            let words: Vec<Word> = raws
                .iter()
                .enumerate()
                .map(|(i, (st, txt))| {
                    let end = raws
                        .get(i + 1)
                        .map(|(n, _)| *n)
                        .unwrap_or(st.saturating_add(300));
                    word_from_span(txt, *st, end)
                })
                .collect();
            // 一行多个行级 tag（副歌复用）：把词表整体平移到各起点
            let base = raws[0].0;
            for start in starts {
                let shift = start as i64 - base as i64;
                let shifted = if shift == 0 {
                    words.clone()
                } else {
                    words
                        .iter()
                        .map(|w| {
                            Word::new(
                                w.glyphs
                                    .iter()
                                    .map(|g| {
                                        Glyph::new(
                                            g.ch,
                                            (g.start_ms as i64 + shift).max(0) as u32,
                                            (g.end_ms as i64 + shift).max(1) as u32,
                                        )
                                    })
                                    .collect(),
                            )
                        })
                        .collect()
                };
                pending.push(Pending {
                    start_ms: start,
                    words: shifted,
                    line_text: String::new(),
                    enhanced: true,
                });
            }
        }
    }

    if pending.is_empty() {
        return None;
    }
    pending.sort_by_key(|p| p.start_ms);

    let mut lines = Vec::with_capacity(pending.len());
    for (i, p) in pending.iter().enumerate() {
        let next_start = pending
            .get(i + 1)
            .map(|n| n.start_ms)
            .unwrap_or(p.start_ms.saturating_add(6_000));
        if p.enhanced && !p.words.is_empty() {
            // 逐字：最后一个字若 end 不合法，用下一行起点兜底
            let last = p.words.len() - 1;
            let fixed: Vec<Word> = p
                .words
                .iter()
                .enumerate()
                .map(|(wi, w)| {
                    if wi == last {
                        let g = w.glyphs.last().cloned();
                        match g {
                            Some(g) if g.end_ms <= g.start_ms => {
                                let end = next_start.max(g.start_ms.saturating_add(300));
                                let mut glyphs = w.glyphs.clone();
                                *glyphs.last_mut().unwrap() = Glyph::new(g.ch, g.start_ms, end);
                                Word::new(glyphs)
                            }
                            _ => w.clone(),
                        }
                    } else {
                        w.clone()
                    }
                })
                .collect();
            lines.push(Line::from_words(fixed));
        } else {
            let end = next_start.max(p.start_ms.saturating_add(500));
            lines.push(Line::from_line_level(&p.line_text, p.start_ms, end));
        }
    }
    let word_level = any_enhanced;
    Some(Parsed {
        lines: normalize(lines),
        word_level,
    })
}

/// 行级 tag `[mm:ss.frac]` → `(毫秒, 内容起始偏移)`。
fn find_line_tags(s: &str) -> Vec<(u32, usize)> {
    let mut out = Vec::new();
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'[' {
            if let Some(e) = s[i..].find(']') {
                let inner = &s[i + 1..i + e];
                if let Some(ms) = parse_lrc_time(inner) {
                    out.push((ms, i + e + 1));
                    i += e + 1;
                    continue;
                }
            }
        }
        i += 1;
    }
    out
}

/// 逐字 tag `<mm:ss.frac>` → `(毫秒, 文本起始偏移, 标签起始偏移)`。
///
/// `标签起始偏移` 是为了让上一段文本**在标签前截断** ——
/// 否则 `终<00:12.79>于` 会把 `<00:12.79>` 当成歌词文本吃进去。
fn find_word_tags(s: &str) -> Vec<(u32, usize, usize)> {
    let mut out = Vec::new();
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'<' {
            if let Some(e) = s[i..].find('>') {
                let inner = &s[i + 1..i + e];
                if let Some(ms) = parse_lrc_time(inner) {
                    out.push((ms, i + e + 1, i));
                    i += e + 1;
                    continue;
                }
            }
        }
        i += 1;
    }
    out
}

/// LRC 时间 `mm:ss[.:frac]` → 毫秒。
fn parse_lrc_time(inner: &str) -> Option<u32> {
    let parts: Vec<&str> = inner.split(':').collect();
    if parts.len() != 2 {
        return None;
    }
    let mm: u32 = parts[0].trim().parse().ok()?;
    let (ss_str, frac_str) = match parts[1].find(['.', ':']) {
        Some(i) => (&parts[1][..i], &parts[1][i + 1..]),
        None => (parts[1], ""),
    };
    let ss: u32 = ss_str.trim().parse().ok()?;
    let frac = match frac_str.len() {
        0 => 0,
        1 => frac_str.parse::<u32>().ok()? * 100,
        2 => frac_str.parse::<u32>().ok()? * 10,
        n => frac_str[..n.min(frac_str.len())].parse::<u32>().ok()?,
    };
    Some(mm * 60_000 + ss * 1000 + frac)
}

// ============================================================================
// 后处理
// ============================================================================

/// 排序 + 修补时间戳：单调递增、end 缺失回填、行级 end 对齐下一行。
fn normalize(mut lines: Vec<Line>) -> Vec<Line> {
    lines.sort_by_key(|l| l.start_ms);
    for i in 0..lines.len() {
        let next_start = lines.get(i + 1).map(|l| l.start_ms);
        let is_line_level = lines[i].is_line_level;
        let mut new_words: Vec<Word> = Vec::with_capacity(lines[i].words.len());
        for (wi, w) in lines[i].words.iter().enumerate() {
            let is_last = wi + 1 == lines[i].words.len();
            let mut glyphs = w.glyphs.clone();
            if let Some(last) = glyphs.last_mut() {
                let mut end = last.end_ms;
                if end <= last.start_ms {
                    end = if !is_last {
                        w.glyphs.last().map(|g| g.end_ms).unwrap_or(last.start_ms + 300)
                    } else {
                        next_start.unwrap_or(last.start_ms + 300)
                    };
                }
                if is_line_level {
                    if let Some(ns) = next_start {
                        if ns > last.start_ms {
                            end = ns;
                        }
                    }
                }
                *last = Glyph::new(last.ch, last.start_ms, end.max(last.start_ms + 80));
            }
            new_words.push(Word::new(glyphs));
        }
        lines[i].words = new_words;
    }
    lines
}

// 让 normalize 能用 Glyph
use yuno_lyrics_model::Glyph;

/// 便捷：从任意已知格式的文本猜测并解析（TTML 或 LRC）。
///
/// 以 `<tt` / `<?xml` 开头（或含 `<p `）判为 TTML，否则按 LRC。
pub fn parse_any(text: &str) -> Option<Parsed> {
    let head = text.trim_start();
    if head.starts_with("<?xml") || head.starts_with("<tt") || head.contains("<p ") || head.contains("<p>") {
        parse_ttml(text).or_else(|| parse_lrc(text))
    } else {
        parse_lrc(text).or_else(|| parse_ttml(text))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn time_formats() {
        assert_eq!(parse_time_ms("00:00:01.234"), Some(1234));
        assert_eq!(parse_time_ms("01:02.345"), Some(62_345));
        assert_eq!(parse_time_ms("12.34"), Some(12_340));
        assert_eq!(parse_time_ms("1234ms"), Some(1234));
        assert_eq!(parse_time_ms("1.2s"), Some(1200));
        assert_eq!(parse_time_ms("00:00:00.000"), Some(0));
        assert_eq!(parse_time_ms(""), None);
        assert_eq!(parse_time_ms("abc"), None);
    }

    #[test]
    fn ttml_basic_lines_and_spans() {
        let xml = r#"<?xml version="1.0" encoding="utf-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
          <body><div>
            <p begin="00:01.000" end="00:03.000">
              <span begin="00:01.000" end="00:01.500">终</span>
              <span begin="00:01.500" end="00:02.000">于</span>
            </p>
          </div></body>
        </tt>"#;
        let p = parse_ttml(xml).expect("应解析成功");
        assert_eq!(p.lines.len(), 1);
        assert!(p.word_level);
        let l = &p.lines[0];
        assert_eq!(l.plain_text(), "终于");
        assert_eq!(l.glyph_count(), 2);
        assert_eq!(l.glyph_at(0).unwrap().start_ms, 1000);
        assert_eq!(l.glyph_at(1).unwrap().end_ms, 2000);
    }

    #[test]
    fn ttml_span_with_multiple_chars_splits_per_char() {
        // 这是 bug 1 的解析层回归：span "AB" 必须拆成两个字，各有时间域
        // 注：TTML 里 "0.4" = 0.4 秒 = 400ms（裸 "400" 是 400 秒）
        let xml = r#"<tt><body><div>
            <p begin="0" end="0.4"><span begin="0" end="0.4">AB</span></p>
        </div></body></tt>"#;
        let p = parse_ttml(xml).unwrap();
        let l = &p.lines[0];
        assert_eq!(l.glyph_count(), 2, "span 内的字必须逐字切开");
        assert_eq!(l.glyph_at(0).unwrap().ch, 'A');
        assert_eq!(l.glyph_at(1).unwrap().ch, 'B');
        assert_eq!(l.glyph_at(0).unwrap().end_ms, 200, "字内均分");
        assert_eq!(l.glyph_at(1).unwrap().start_ms, 200);
    }

    #[test]
    fn ttml_background_vocals() {
        let xml = r#"<tt><body><div>
            <p begin="0" end="1000" ttm:role="x-bg">背景</p>
            <p begin="1000" end="2000">主唱</p>
        </div></body></tt>"#;
        let p = parse_ttml(xml).unwrap();
        assert_eq!(p.lines.len(), 2);
        assert!(p.lines[0].is_background, "x-bg 必须识别成背景人声");
        assert!(!p.lines[1].is_background);
    }

    #[test]
    fn ttml_nested_spans_flatten() {
        let xml = r#"<tt><body><div>
            <p begin="0" end="1000">
              <span begin="0" end="500"><span begin="0" end="250">你</span><span begin="250" end="500">好</span></span>
              <span begin="500" end="1000">吗</span>
            </p>
        </div></body></tt>"#;
        let p = parse_ttml(xml).unwrap();
        let l = &p.lines[0];
        assert_eq!(l.plain_text(), "你好吗");
        assert_eq!(l.glyph_count(), 3);
    }

    #[test]
    fn ttml_line_without_spans_is_line_level() {
        let xml = r#"<tt><body><div>
            <p begin="0" end="2000">整行歌词</p>
        </div></body></tt>"#;
        let p = parse_ttml(xml).unwrap();
        assert!(!p.word_level);
        assert!(p.lines[0].is_line_level);
        assert_eq!(p.lines[0].plain_text(), "整行歌词");
    }

    #[test]
    fn ttml_malformed_never_panics() {
        assert!(parse_ttml("").is_none());
        assert!(parse_ttml("<<<>>>").is_none() || parse_ttml("<<<>>>").is_some());
        assert!(parse_ttml("<tt><body><div><p begin='bad' end='worse'>x</p></div></body></tt>").is_some());
        let _ = parse_ttml("<p><span>没闭合");
        let _ = parse_ttml("<!-- 注释 -->");
    }

    #[test]
    fn lrc_line_level() {
        let lrc = "[00:12.34]第一句\n[00:15.00]第二句\n[ar:歌手]\n";
        let p = parse_lrc(lrc).expect("LRC 应解析成功");
        assert_eq!(p.lines.len(), 2);
        assert!(!p.word_level);
        assert_eq!(p.lines[0].plain_text(), "第一句");
        assert_eq!(p.lines[0].start_ms, 12_340);
    }

    #[test]
    fn lrc_enhanced_word_level() {
        let lrc = "[00:12.34]<00:12.34>终<00:12.79>于";
        let p = parse_lrc(lrc).unwrap();
        assert!(p.word_level);
        let l = &p.lines[0];
        assert_eq!(l.plain_text(), "终于", "逐字 tag 本身绝不能混进歌词文本");
        assert_eq!(l.glyph_count(), 2);
        assert_eq!(l.glyph_at(0).unwrap().start_ms, 12_340);
        assert_eq!(l.glyph_at(1).unwrap().start_ms, 12_790);
    }

    #[test]
    fn lrc_enhanced_splits_multichar_words() {
        // bug 1 回归：增强 LRC 里 <00:12.34>AB 也要拆成两个字
        let lrc = "[00:12.34]<00:12.34>AB<00:12.90>C";
        let p = parse_lrc(lrc).unwrap();
        let l = &p.lines[0];
        assert_eq!(l.glyph_count(), 3);
    }

    #[test]
    fn lrc_multiple_line_tags_shift_words() {
        // 副歌复用同一行词表，两个起点都要平移
        let lrc = "[00:10.00][00:30.00]<00:10.00>副<00:10.50>歌";
        let p = parse_lrc(lrc).unwrap();
        assert_eq!(p.lines.len(), 2);
        assert_eq!(p.lines[0].start_ms, 10_000);
        assert_eq!(p.lines[1].start_ms, 30_000);
        assert_eq!(p.lines[1].glyph_at(0).unwrap().start_ms, 30_000, "第二遍必须平移");
    }

    #[test]
    fn lrc_metadata_lines_skipped() {
        let lrc = "[ar:某歌手]\n[ti:某歌]\n[al:某专辑]\n[00:01.00]只有这一句";
        let p = parse_lrc(lrc).unwrap();
        assert_eq!(p.lines.len(), 1);
    }

    #[test]
    fn parse_any_routes_correctly() {
        assert!(parse_any("<tt><body><div><p begin='0' end='1'>x</p></div></body></tt>").is_some());
        assert!(parse_any("[00:01.00]歌词").is_some());
    }

    #[test]
    fn normalize_fixes_zero_length_word_ends() {
        let xml = r#"<tt><body><div>
            <p begin="0" end="1000"><span begin="0">字</span></p>
        </div></body></tt>"#;
        let p = parse_ttml(xml).unwrap();
        let g = p.lines[0].glyph_at(0).unwrap();
        assert!(g.end_ms > g.start_ms, "end 缺失必须回填成非零时长");
    }
}
