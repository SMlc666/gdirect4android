//! Small, bounded HTTPS transfer diagnostic using the selected Android Network.
//! Parse HTTP/1.1 response framing instead of waiting for the TLS close_notify.
//! A missing TLS close_notify is NOT proof of a broken transfer if an HTTP
//! Content-Length or chunked termination has already established completion.
//! Conversely, never treat a truncated HTTP message as completed.
use rustls::pki_types::ServerName;
use rustls::{ClientConnection, StreamOwned};
use std::io::{Read, Write};
use std::net::{IpAddr, SocketAddr};
use std::time::{Duration, Instant};

const HOST: &str = "www.googleapis.com";
const URL: &str = "https://www.googleapis.com/discovery/v1/apis/drive/v3/rest";
const REQUEST: &str = "GET /discovery/v1/apis/drive/v3/rest HTTP/1.1\r\nHost: www.googleapis.com\r\nConnection: close\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nUser-Agent: gdirect-android-transfer-smoke/0.5\r\n\r\n";
const MAX_BODY: usize = 1_048_576;
const MAX_HEADERS: usize = 16 * 1024;
const MAX_WIRE: usize = MAX_BODY * 2 + MAX_HEADERS;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Framing {
    ContentLength(usize),
    Chunked,
    CloseDelimited,
    NoBody,
}
impl Framing {
    fn label(self) -> &'static str {
        match self {
            Self::ContentLength(_) => "Content-Length",
            Self::Chunked => "chunked",
            Self::CloseDelimited => "连接关闭",
            Self::NoBody => "无正文",
        }
    }
}

#[derive(Debug)]
struct Header {
    status: u16,
    framing: Framing,
    content_type: Option<String>,
}

#[derive(Debug)]
struct Progress {
    status: u16,
    body_bytes: usize,
    capped: bool,
    complete: bool,
    framing: Framing,
    content_type: Option<String>,
}

#[derive(Debug)]
struct Sample {
    progress: Progress,
    elapsed: Duration,
}

// The header parser only accepts a complete CRLF-terminated header section.
// It is intentionally conservative: conflicting framing information is an
// error rather than a misleading count or a request-smuggling ambiguity.
fn parse_headers(wire: &[u8]) -> Result<Option<(Header, usize)>, String> {
    let Some(end) = wire.windows(4).position(|v| v == b"\r\n\r\n") else {
        if wire.len() > MAX_HEADERS {
            return Err("HTTP 响应头超过 16 KiB".into());
        }
        return Ok(None);
    };
    let boundary = end + 4;
    if boundary > MAX_HEADERS {
        return Err("HTTP 响应头超过 16 KiB".into());
    }
    let header = std::str::from_utf8(&wire[..end])
        .map_err(|_| "HTTP 响应头编码异常".to_owned())?;
    let mut lines = header.split("\r\n");
    let status_line = lines.next().unwrap_or("");
    if !status_line.starts_with("HTTP/1.") {
        return Err(format!("不是 HTTP/1.x 状态行：{status_line}"));
    }
    let status = status_line
        .split_whitespace()
        .nth(1)
        .and_then(|v| v.parse::<u16>().ok())
        .filter(|v| (100..600).contains(v))
        .ok_or_else(|| format!("无效 HTTP 状态：{status_line}"))?;
    let mut content_length = None;
    let mut transfer_encoding = None;
    let mut content_type = None;
    for line in lines {
        let (name, value) = line
            .split_once(':')
            .ok_or_else(|| "HTTP 响应头缺少冒号".to_owned())?;
        let value = value.trim();
        if name.eq_ignore_ascii_case("content-length") {
            if content_length.is_some() {
                return Err("重复的 Content-Length".into());
            }
            content_length = Some(value.parse::<usize>()
                .map_err(|_| "无效 Content-Length".to_owned())?);
        } else if name.eq_ignore_ascii_case("transfer-encoding") {
            if transfer_encoding.replace(value.to_ascii_lowercase()).is_some() {
                return Err("重复的 Transfer-Encoding".into());
            }
        } else if name.eq_ignore_ascii_case("content-type") {
            content_type = Some(value.chars().take(96).collect());
        }
    }
    if transfer_encoding.is_some() && content_length.is_some() {
        return Err("同时收到 Content-Length 和 Transfer-Encoding，拒绝不明确的消息边界".into());
    }
    let framing = if (100..200).contains(&status) || status == 204 || status == 304 {
        Framing::NoBody
    } else if let Some(coding) = transfer_encoding {
        if coding.trim() != "chunked" {
            return Err(format!("暂不支持的 Transfer-Encoding：{coding}"));
        }
        Framing::Chunked
    } else if let Some(n) = content_length {
        Framing::ContentLength(n)
    } else {
        Framing::CloseDelimited
    };
    Ok(Some((Header { status, framing, content_type }, boundary)))
}

/// Count only decoded chunk data, not the chunk-size lines or delimiters.
/// Returns (decoded bytes observed, final zero chunk/trailer reached).
/// This parser is intentionally stateless because samples are <= 1 MiB.
fn chunked_progress(body: &[u8]) -> Result<(usize, bool), String> {
    let mut offset = 0usize;
    let mut decoded = 0usize;
    loop {
        let remaining = &body[offset..];
        let Some(line_end) = remaining.windows(2).position(|v| v == b"\r\n") else {
            if remaining.len() > 128 {
                return Err("HTTP chunk size 行过长".into());
            }
            return Ok((decoded, false));
        };
        if line_end > 128 {
            return Err("HTTP chunk size 行过长".into());
        }
        let size_text = remaining[..line_end]
            .split(|v| *v == b';')
            .next().unwrap_or(&[]);
        let size_text = std::str::from_utf8(size_text)
            .map_err(|_| "HTTP chunk size 编码错误".to_owned())?;
        let size = usize::from_str_radix(size_text, 16)
            .map_err(|_| "HTTP chunk size 不是合法十六进制".to_owned())?;
        offset += line_end + 2;
        if size == 0 {
            // The last chunk ends in CRLF, possibly with trailers first.
            let tail = &body[offset..];
            if tail.starts_with(b"\r\n") || tail.windows(4).any(|v| v == b"\r\n\r\n") {
                return Ok((decoded, true));
            }
            if tail.len() > MAX_HEADERS {
                return Err("HTTP trailers 过长".into());
            }
            return Ok((decoded, false));
        }
        let available = body.len().saturating_sub(offset);
        let taken = available.min(size);
        decoded = decoded.saturating_add(taken);
        if decoded >= MAX_BODY {
            return Ok((decoded, false));
        }
        if taken < size {
            return Ok((decoded, false));
        }
        offset += size;
        if body.len().saturating_sub(offset) < 2 {
            return Ok((decoded, false));
        }
        if &body[offset..offset + 2] != b"\r\n" {
            return Err("HTTP chunk 数据后缺少 CRLF".into());
        }
        offset += 2;
    }
}

/// Parse the received bytes without interpreting an unclean TLS close as
/// an HTTP body boundary. Clean TLS closure is necessary for close-delimited
/// bodies but NOT for length-delimited or correctly chunked responses.
fn response_progress(wire: &[u8], clean_close: bool) -> Result<Option<Progress>, String> {
    let Some((header, start)) = parse_headers(wire)? else {
        return Ok(None);
    };
    let body = &wire[start..];
    let (received, complete) = match header.framing {
        Framing::ContentLength(expected) => (body.len().min(expected), body.len() >= expected),
        Framing::Chunked => chunked_progress(body)?,
        Framing::CloseDelimited => (body.len(), clean_close),
        Framing::NoBody => (0, true),
    };
    let capped = received >= MAX_BODY && !complete;
    Ok(Some(Progress {
        status: header.status,
        body_bytes: received.min(MAX_BODY),
        capped,
        complete,
        framing: header.framing,
        content_type: header.content_type,
    }))
}

fn sample(ip: IpAddr, handle: u64) -> Result<Sample, String> {
    let started = Instant::now();
    let socket = crate::android_network::connect(
        SocketAddr::new(ip, 443), handle, Duration::from_secs(3),
    ).map_err(|e| format!("TCP：{e}"))?;
    socket.set_read_timeout(Some(Duration::from_secs(4)))
        .map_err(|e| format!("设置接收超时：{e}"))?;
    socket.set_write_timeout(Some(Duration::from_secs(4)))
        .map_err(|e| format!("设置发送超时：{e}"))?;

    let server = ServerName::try_from(HOST.to_owned())
        .map_err(|e| format!("TLS 主机名无效：{e}"))?;
    let conn = ClientConnection::new(
        crate::verify::config().map_err(|e| format!("根证书：{e}"))?, server,
    ).map_err(|e| format!("TLS 初始化：{e}"))?;
    let mut stream = StreamOwned::new(conn, socket);
    stream.write_all(REQUEST.as_bytes())
        .map_err(|e| format!("TLS 握手/发送失败：{e}"))?;

    // Retain at most ~2 MiB of wire bytes for chunk framing. No file writes,
    // secrets or private Drive access; all memory is dropped after each IP.
    let mut wire = Vec::with_capacity(32 * 1024);
    let mut buffer = [0u8; 8192];
    loop {
        if started.elapsed() > Duration::from_secs(15) {
            return Err("达到 15 秒测试时间上限".into());
        }
        match stream.read(&mut buffer) {
            Ok(0) => {
                let progress = response_progress(&wire, true)?
                    .ok_or_else(|| "TLS 正常关闭，但未收到完整 HTTP 响应头".to_owned())?;
                if progress.complete || progress.capped {
                    return Ok(Sample { progress, elapsed: started.elapsed() });
                }
                return Err(format!(
                    "HTTP 正文截断：HTTP {}，已收到 {} 字节，{} 未结束（TLS 正常关闭）",
                    progress.status, progress.body_bytes, progress.framing.label()
                ));
            }
            Ok(n) => {
                wire.extend_from_slice(&buffer[..n]);
                if wire.len() > MAX_WIRE {
                    return Err("HTTP 数据包头/分块开销超过诊断上限".into());
                }
                if let Some(progress) = response_progress(&wire, false)? {
                    // Non-200 statuses need no download; still surface the
                    // actual verified HTTPS response status to the user.
                    if progress.status != 200 || progress.complete || progress.capped {
                        return Ok(Sample { progress, elapsed: started.elapsed() });
                    }
                }
            }
            Err(err) if err.kind() == std::io::ErrorKind::UnexpectedEof => {
                // Rustls reports an unclean TLS close when a peer omits
                // close_notify. An explicitly framed, completed response
                // is safe to accept; incomplete framing remains an error.
                if let Some(progress) = response_progress(&wire, false)? {
                    if progress.complete || progress.capped {
                        return Ok(Sample { progress, elapsed: started.elapsed() });
                    }
                    return Err(format!(
                        "HTTP {} 已收到 {} 字节，但 {} 尚未结束；对端未发送 TLS close_notify，不能认定内容完整",
                        progress.status, progress.body_bytes, progress.framing.label()
                    ));
                }
                return Err("TLS 意外关闭，尚未收到完整 HTTP 响应头".into());
            }
            Err(err) => {
                let status = match response_progress(&wire, false)? {
                    Some(progress) => format!(
                        "HTTP {} 已接收 {} 字节，但 {} 未结束",
                        progress.status, progress.body_bytes, progress.framing.label()
                    ),
                    None => "没有完整 HTTP 响应头".into(),
                };
                return Err(format!("HTTPS 接收失败：{err}；{status}"));
            }
        }
    }
}

pub fn run(handle: u64, verified: &[IpAddr]) -> String {
    if verified.is_empty() {
        return "ERR\n当前网络没有已验证 Google IP；请先扫描".into();
    }
    let mut report = format!(
        "Drive API 公开文档传输测试\nURL：{URL}\nNetwork handle：{handle}\n每个 IP 最多取样 1 MiB，最多 {} 个用户验证 IP，串行测试\n\n",
        verified.len().min(8)
    );
    for &parsed in verified.iter().take(8) {
        let ip=parsed;
        match sample(parsed, handle) {
            Ok(sample) => {
                let p = sample.progress;
                let millis = sample.elapsed.as_millis();
                if p.status == 200 && p.body_bytes > 0 {
                    let speed = (p.body_bytes as f64 / 1024.0)
                        / sample.elapsed.as_secs_f64().max(0.001);
                    report.push_str(&format!(
                        "✅ {ip} · HTTPS 200 · 载荷 {} 字节 · {:.1} KiB/s · {millis} ms · {}{} · Content-Type: {}\n",
                        p.body_bytes, speed, p.framing.label(),
                        if p.capped { "（达到采样上限，未下载完整文档）" }
                        else if p.complete { "（响应完整）" }
                        else { "（响应完整性待确认）" },
                        p.content_type.as_deref().unwrap_or("未声明")
                    ));
                } else {
                    report.push_str(&format!(
                        "— {ip} · HTTPS {} · 收到 {} 字节 · {} · 不是有效的 200 数据响应\n",
                        p.status, p.body_bytes, p.framing.label()
                    ));
                }
            }
            Err(err) => report.push_str(&format!("— {ip} · {err}\n")),
        }
    }
    report.push_str(
        "\n注：速率包括建连/TLS 时间，不能代表长时间持续下载速度。\
数据只用于诊断，不访问私人文件，也不保存数据。\
缺少 close_notify 只有在 HTTP 消息完整性得到确认时才不会导致失败。\n"
    );
    if handle == 0 {
        report.push_str("⚠️ 系统默认路径可能经过 VPN；请选 Wi-Fi 测试直连。\n");
    }
    report
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(bytes: &[u8], clean: bool) -> Progress {
        response_progress(bytes, clean).unwrap().unwrap()
    }

    #[test]
    fn explicit_content_length_finishes_without_tls_close_notify() {
        let p = parse(b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello", false);
        assert_eq!(p.status, 200);
        assert_eq!(p.body_bytes, 5);
        assert!(p.complete);
        assert!(!p.capped);
        assert_eq!(p.framing, Framing::ContentLength(5));
    }

    #[test]
    fn content_length_truncation_does_not_finish() {
        let p = parse(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nhello", false);
        assert_eq!(p.body_bytes, 5);
        assert!(!p.complete);
        assert!(!p.capped);
        assert!(!parse(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nhello", true).complete);
    }

    #[test]
    fn completed_chunked_body_needs_no_close_notify() {
        let p = parse(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n", false);
        assert_eq!(p.body_bytes, 11);
        assert!(p.complete);
        assert_eq!(p.framing, Framing::Chunked);
    }

    #[test]
    fn incomplete_chunked_body_never_passes() {
        let p = parse(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n wor", false);
        assert_eq!(p.body_bytes, 9);
        assert!(!p.complete);
    }

    #[test]
    fn chunked_trailers_are_supported() {
        let p = parse(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\nX-End: yes\r\n\r\n", false);
        assert_eq!(p.body_bytes, 3);
        assert!(p.complete);
    }

    #[test]
    fn close_delimited_requires_clean_tls_close() {
        let wire = b"HTTP/1.1 200 OK\r\n\r\npartial";
        assert!(!parse(wire, false).complete);
        assert!(parse(wire, true).complete);
    }

    #[test]
    fn ambiguous_cl_and_te_is_rejected() {
        let wire = b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n";
        assert!(response_progress(wire, false).is_err());
    }

    #[test]
    fn body_cap_is_reported_as_sample_not_complete_document() {
        let mut wire = b"HTTP/1.1 200 OK\r\nContent-Length: 10000000\r\n\r\n".to_vec();
        wire.extend(std::iter::repeat_n(b'x', MAX_BODY));
        let p = parse(&wire, false);
        assert_eq!(p.body_bytes, MAX_BODY);
        assert!(p.capped);
        assert!(!p.complete);
    }

    #[test]
    fn headers_may_be_split_across_reads() {
        assert!(response_progress(b"HTTP/1.1 200 OK\r\nContent-Leng", false).unwrap().is_none());
        let p = parse(b"HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\na", false);
        assert_eq!(p.body_bytes, 1);
        assert!(p.complete);
    }
}
