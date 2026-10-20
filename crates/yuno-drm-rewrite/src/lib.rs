//! Apple 全曲流重写 —— **cbcs fMP4 → Widevine CENC**。
//!
//! 移植自 `Yuno.old/core-playback/StreamRewriterDataSource.kt`（我们自己的实现，
//! 1.0.18 真机打通），Rust 侧只做**纯字节变换**，不碰网络、不碰播放器。
//!
//! # 为什么要重写
//!
//! Apple 网页播放器的 HLS 清单是 **cbcs（ISO-23001-7）**加密，KEY 行是网页方言，
//! Media3 的解析器不认。交给 Media3 之前必须换成标准写法：
//!
//! 1. `METHOD=ISO-23001-7` → `METHOD=SAMPLE-AES-CENC`（CENC 才走 Widevine DRM）
//! 2. KEY 行的 `data:` URI 里那 16 字节是 **key ID**（不是密钥）。
//!    Apple 的 license 服务器校验的是
//!    `pssh{ systemId=Widevine, data=WidevineCencHeader{ AESCTR + KID } }` ——
//!    **裸 16 字节直接包 pssh 会被 errorCode -42585 拒**。
//! 3. 记下 adamId / 原始 KEY URI，license 请求里 `uri` 字段要**原样回传**。
//!
//! 其余内容（分片清单等）原样透传，相对路径保持相对原始 URL 解析。

#![warn(missing_docs)]

/// Widevine 的标准 scheme UUID（Media3 以此识别 Widevine 加密流）。
pub const WIDEVINE_KEYFORMAT: &str = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed";

/// Widevine systemId 的 16 字节形式（进 pssh box 用）。
pub const WIDEVINE_UUID_BYTES: [u8; 16] = [
    0xED, 0xEF, 0x8B, 0xA9, 0x79, 0xD6, 0x4A, 0xCE, 0xA3, 0xC8, 0x27, 0xDC, 0xD5, 0x1D, 0x21,
    0xED,
];

/// 清单重写结果。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct RewriteOutcome {
    /// 重写后的清单（未加密的 preview 流会**原样返回**）。
    pub manifest: String,
    /// 从 manifest URL 里抓到的 adamId（`mzaf_A(\d+)`），可能为空。
    pub adam_id: String,
    /// KEY 行的**原始** `data:` URI —— license 请求的 `uri` 字段必须原样回传。
    pub key_uri: String,
    /// 是否真的做了重写（false = 非 cbcs 流，原样透传）。
    pub rewritten: bool,
}

// ============================================================================
// manifest 重写
// ============================================================================

/// 重写 cbcs 清单（Apple 方言 → Media3 标准写法），并顺带记录 DRM 上下文。
///
/// 非 cbcs 的清单（preview 未加密流等）**原样返回**，`rewritten = false`。
///
/// # 例子
///
/// ```
/// use yuno_drm_rewrite::rewrite_manifest;
///
/// let src = "#EXTM3U\n#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"data:text/plain;base64,AAAAAAAAAAAAAAAAAAAAAA==\"\n#EXTINF:6,\nseg.m4s\n";
/// let out = rewrite_manifest(src, "https://play.itunes.apple.com/mzaf_A12345/stream.m3u8");
/// assert!(out.rewritten);
/// assert_eq!(out.adam_id, "12345");
/// assert!(out.manifest.contains("METHOD=SAMPLE-AES-CENC"));
/// assert!(out.manifest.contains("KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\""));
/// ```
pub fn rewrite_manifest(text: &str, manifest_url: &str) -> RewriteOutcome {
    let normalized = text.replace("\r\n", "\n");
    if !normalized.contains("METHOD=ISO-23001-7") {
        return RewriteOutcome {
            manifest: normalized,
            ..Default::default()
        };
    }

    let adam_id = parse_adam_id(manifest_url).unwrap_or_default();
    let mut out = String::with_capacity(normalized.len() + 512);
    let mut key_uri = String::new();

    for line in normalized.split('\n') {
        if line.starts_with("#EXT-X-KEY") && line.contains("METHOD=ISO-23001-7") {
            let init_uri = extract_key_uri(line).unwrap_or_default();
            // 原始 URI 留给 license 请求（Chrome 请求的 "uri" 参数就是它）
            if key_uri.is_empty() {
                key_uri = init_uri.clone();
            }
            out.push_str(&build_key_line(&init_uri));
        } else {
            out.push_str(line);
        }
        out.push('\n');
    }

    RewriteOutcome {
        manifest: out,
        adam_id,
        key_uri,
        rewritten: true,
    }
}

/// 构造标准的 `SAMPLE-AES-CENC` + Widevine KEY 行。
///
/// KEY 行的 payload 就是 **key ID**（16 字节 base64）。这里把 pssh box
/// base64 后塞进 URI，Media3 会拿它当 DRM init data。
pub fn build_key_line(init_data_uri: &str) -> String {
    let kid = base64_decode(init_data_uri.split_once("base64,").map(|(_, v)| v).unwrap_or(""));
    let uri_attr = match kid {
        Some(ref k) if !k.is_empty() => {
            format!("data:text/plain;base64,{}", base64_encode(&build_cenc_pssh(k)))
        }
        _ => init_data_uri.to_string(),
    };
    format!(
        "#EXT-X-KEY:METHOD=SAMPLE-AES-CENC,URI=\"{uri_attr}\",KEYFORMAT=\"{WIDEVINE_KEYFORMAT}\",KEYFORMATVERSIONS=\"1\""
    )
}

/// 构造 Apple 认可的 Widevine pssh box。
///
/// ```text
/// box 头：size + "pssh" + ver0/flags0 + Widevine systemId(16B) + dataLen
/// data  ：WidevineCencHeader protobuf
///          08 01                = field1 algorithm = 1 (AESCTR)
///          12 10 <16B key ID>   = field2 key_ids = [KID]
///       （content_id / provider / policy 为空字符串，protobuf 省略零值）
/// ```
///
/// ⚠️ **裸 16 字节 KID 直接当 pssh 会被 errorCode -42585 拒** —— 必须走这个 box。
pub fn build_cenc_pssh(kid: &[u8]) -> Vec<u8> {
    // WidevineCencHeader：08 01 | 12 <len> <kid>
    let mut header = Vec::with_capacity(4 + kid.len());
    header.push(0x08);
    header.push(0x01);
    header.push(0x12);
    header.push(kid.len() as u8);
    header.extend_from_slice(kid);

    let mut out = Vec::with_capacity(32 + header.len());
    out.extend_from_slice(&((32 + header.len()) as u32).to_be_bytes());
    out.extend_from_slice(b"pssh");
    out.extend_from_slice(&0u32.to_be_bytes()); // version 0 + flags 0
    out.extend_from_slice(&WIDEVINE_UUID_BYTES);
    out.extend_from_slice(&(header.len() as u32).to_be_bytes());
    out.extend_from_slice(&header);
    out
}

// ============================================================================
// 从 URL / KEY 行里抠信息
// ============================================================================

/// 从 manifest URL 里抓 adamId（`mzaf_A(\d+)`）。
pub fn parse_adam_id(url: &str) -> Option<String> {
    let idx = url.find("mzaf_A")?;
    let rest = &url[idx + "mzaf_A".len()..];
    let digits: String = rest
        .chars()
        .take_while(|c| c.is_ascii_digit())
        .collect();
    if digits.is_empty() {
        None
    } else {
        Some(digits)
    }
}

/// 从 `#EXT-X-KEY` 行里抓 `URI="data:..."`。
pub fn extract_key_uri(line: &str) -> Option<String> {
    let start = line.find("URI=\"")? + "URI=\"".len();
    let rest = &line[start..];
    let end = rest.find('"')?;
    Some(rest[..end].to_string())
}

// ============================================================================
// base64（不引依赖）
// ============================================================================

const B64_ALPHABET: &[u8; 64] =
    b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// 标准 base64 编码（带 `=` 填充）。
pub fn base64_encode(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len().div_ceil(3) * 4);
    for chunk in data.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(B64_ALPHABET[(n >> 18) as usize & 0x3F] as char);
        out.push(B64_ALPHABET[(n >> 12) as usize & 0x3F] as char);
        out.push(if chunk.len() > 1 {
            B64_ALPHABET[(n >> 6) as usize & 0x3F] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            B64_ALPHABET[n as usize & 0x3F] as char
        } else {
            '='
        });
    }
    out
}

/// 标准 base64 解码。非法输入返回 `None`（**不 panic**）。
pub fn base64_decode(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(s.len() / 4 * 3);
    let mut buf = 0u32;
    let mut bits = 0u32;
    for c in s.chars() {
        if c == '=' || c == '\n' || c == '\r' || c == ' ' || c == '\t' {
            continue;
        }
        let v = B64_ALPHABET.iter().position(|&a| a == c as u8)? as u32;
        buf = (buf << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buf >> bits) as u8);
        }
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pssh_box_is_52_bytes_with_correct_header() {
        let kid = [0xAAu8; 16];
        let box_bytes = build_cenc_pssh(&kid);
        // size(4) + 'pssh'(4) + ver/flags(4) + systemId(16) + dataLen(4) + data(20) = 52
        assert_eq!(box_bytes.len(), 52);
        // size 字段自洽
        let size = u32::from_be_bytes([box_bytes[0], box_bytes[1], box_bytes[2], box_bytes[3]]);
        assert_eq!(size as usize, box_bytes.len(), "size 字段必须等于 box 实际长度");
        // 类型
        assert_eq!(&box_bytes[4..8], b"pssh");
        // version/flags
        assert_eq!(&box_bytes[8..12], &[0, 0, 0, 0]);
        // systemId
        assert_eq!(&box_bytes[12..28], &WIDEVINE_UUID_BYTES);
        // dataLen
        let dlen = u32::from_be_bytes([box_bytes[28], box_bytes[29], box_bytes[30], box_bytes[31]]);
        assert_eq!(dlen, 20);
        // WidevineCencHeader
        assert_eq!(box_bytes[32], 0x08);
        assert_eq!(box_bytes[33], 0x01);
        assert_eq!(box_bytes[34], 0x12);
        assert_eq!(box_bytes[35], 16);
        assert_eq!(&box_bytes[36..52], &kid);
    }

    #[test]
    fn pssh_rejects_bare_kid_in_favour_of_box() {
        // 回归：裸 16 字节 KID 会被 -42585 拒，必须是完整 box
        let kid = [0x11u8; 16];
        let pssh = build_cenc_pssh(&kid);
        assert!(pssh.len() > 16, "绝不能是裸 KID");
        assert_eq!(&pssh[4..8], b"pssh");
    }

    #[test]
    fn base64_roundtrip() {
        for data in [
            &b""[..],
            &b"a"[..],
            &b"ab"[..],
            &b"abc"[..],
            &b"hello world"[..],
            &[0u8, 255, 128, 1, 2][..],
        ] {
            let enc = base64_encode(data);
            let dec = base64_decode(&enc).expect("应能解回来");
            assert_eq!(dec, data, "roundtrip 失败：{data:?} -> {enc}");
        }
    }

    #[test]
    fn base64_decode_tolerates_whitespace_and_padding() {
        assert_eq!(
            base64_decode("QUJD\n"),
            Some(b"ABC".to_vec())
        );
        assert_eq!(
            base64_decode("QQ=="),
            Some(b"A".to_vec())
        );
        assert!(base64_decode("!!!").is_none(), "非法字符必须返回 None");
    }

    #[test]
    fn adam_id_extraction() {
        assert_eq!(
            parse_adam_id("https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/mzaf_A12345/stream.m3u8"),
            Some("12345".to_string())
        );
        assert_eq!(parse_adam_id("https://example.com/x.m3u8"), None);
        assert_eq!(parse_adam_id("mzaf_A"), None, "没数字不算");
    }

    #[test]
    fn key_uri_extraction() {
        let line = "#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"data:text/plain;base64,AAAA\",OTHER=x";
        assert_eq!(
            extract_key_uri(line),
            Some("data:text/plain;base64,AAAA".to_string())
        );
        assert_eq!(extract_key_uri("#EXT-X-KEY:METHOD=NONE"), None);
    }

    #[test]
    fn rewrite_cbcs_manifest() {
        let src = concat!(
            "#EXTM3U\n",
            "#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"data:text/plain;base64,AAAAAAAAAAAAAAAAAAAAAA==\"\n",
            "#EXTINF:6.0,\nseg1.m4s\n"
        );
        let out = rewrite_manifest(src, "https://x/mzaf_A777/a.m3u8");
        assert!(out.rewritten);
        assert_eq!(out.adam_id, "777");
        assert_eq!(
            out.key_uri,
            "data:text/plain;base64,AAAAAAAAAAAAAAAAAAAAAA==",
            "原始 URI 必须原样留给 license 请求"
        );
        assert!(out.manifest.contains("METHOD=SAMPLE-AES-CENC"));
        assert!(out.manifest.contains("KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\""));
        assert!(out.manifest.contains("KEYFORMATVERSIONS=\"1\""));
        assert!(!out.manifest.contains("ISO-23001-7"), "旧方法必须被替换掉");
        assert!(out.manifest.contains("seg1.m4s"), "分片行原样透传");
    }

    #[test]
    fn rewrite_key_uri_becomes_pssh() {
        let kid_b64 = base64_encode(&[0xAB; 16]);
        let src = format!(
            "#EXTM3U\n#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"data:text/plain;base64,{kid_b64}\"\n"
        );
        let out = rewrite_manifest(&src, "");
        // 重写后的 URI 里应该是 pssh box 的 base64，而不是原来的 KID
        let inner = extract_key_uri(&out.manifest).unwrap();
        let payload = inner.split_once("base64,").unwrap().1;
        let decoded = base64_decode(payload).unwrap();
        assert_eq!(&decoded[4..8], b"pssh", "URI 里装的必须是 pssh box");
        assert_eq!(decoded.len(), 52);
    }

    #[test]
    fn non_cbcs_passthrough_untouched() {
        let src = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:6,\ns.ts\n";
        let out = rewrite_manifest(src, "https://x/y.m3u8");
        assert!(!out.rewritten);
        assert_eq!(out.manifest, src.replace("\r\n", "\n"));
        assert!(out.key_uri.is_empty());
    }

    #[test]
    fn crlf_normalized() {
        let src = "#EXTM3U\r\n#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"data:text/plain;base64,AAAA\"\r\n";
        let out = rewrite_manifest(src, "");
        assert!(!out.manifest.contains('\r'));
    }

    #[test]
    fn malformed_input_never_panics() {
        let _ = rewrite_manifest("", "");
        let _ = rewrite_manifest("#EXT-X-KEY:METHOD=ISO-23001-7,URI=\"", "");
        let _ = build_key_line("not-a-data-uri");
        let _ = build_key_line("");
        assert!(base64_decode("====").unwrap_or_default().is_empty());
    }
}
