//! Real Google HTTPS file save over an Android-selected Network.
//! First verify a complete public Drive Discovery JSON body in app cache,
//! then let Java copy it to the user-selected SAF document. No OAuth needed.
use rustls::pki_types::ServerName;
use rustls::{ClientConnection, StreamOwned};
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Seek, SeekFrom, Write};
use std::net::{IpAddr, SocketAddr};
use std::os::fd::FromRawFd;
use std::time::{Duration, Instant};

const HOST: &str = "www.googleapis.com";
const PATH: &str = "/discovery/v1/apis/drive/v3/rest";
const REQUEST: &str = "GET /discovery/v1/apis/drive/v3/rest HTTP/1.1\r\nHost: www.googleapis.com\r\nConnection: close\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nUser-Agent: gdirect-android-file-save/0.6\r\n\r\n";
const IP_POOL: [&str; 3] = ["142.250.27.100", "108.177.9.138", "64.233.160.100"];
const MAX_BODY: u64 = 8 * 1024 * 1024;
const MAX_HEADERS: usize = 16 * 1024;

#[derive(Debug, PartialEq, Eq)]
enum Framing { Chunked, ContentLength(u64) }

#[derive(Debug)]
struct Receipt {
    bytes: u64,
    sha256: String,
    framing: Framing,
}

fn read_line<R: BufRead>(reader: &mut R, limit: usize) -> Result<Vec<u8>, String> {
    let mut line = Vec::new();
    reader.read_until(b'\n', &mut line)
        .map_err(|e| format!("HTTP 响应读取失败：{e}"))?;
    if line.len() > limit { return Err("HTTP 响应行超长".into()); }
    if !line.ends_with(b"\r\n") {
        return Err("HTTP 响应行不完整或缺少 CRLF".into());
    }
    line.truncate(line.len() - 2);
    Ok(line)
}

fn headers<R: BufRead>(reader: &mut R) -> Result<Framing, String> {
    let mut consumed = 0usize;
    let mut lines = Vec::new();
    loop {
        let line = read_line(reader, MAX_HEADERS)?;
        consumed += line.len() + 2;
        if consumed > MAX_HEADERS { return Err("HTTP 响应头超过 16 KiB".into()); }
        if line.is_empty() { break; }
        lines.push(String::from_utf8(line).map_err(|_| "HTTP 响应头编码异常".to_owned())?);
    }
    let status = lines.first().ok_or_else(|| "HTTP 缺少状态行".to_owned())?;
    if !status.starts_with("HTTP/1.") {
        return Err(format!("不是 HTTP/1.x 响应：{status}"));
    }
    let code = status.split_whitespace().nth(1)
        .and_then(|v| v.parse::<u16>().ok())
        .ok_or_else(|| format!("无法解析状态行：{status}"))?;
    if code != 200 { return Err(format!("HTTPS 响应 HTTP {code}，不是 200")); }

    let mut length: Option<u64> = None;
    let mut transfer: Option<String> = None;
    let mut json = false;
    let mut compressed = false;
    for entry in lines.iter().skip(1) {
        let (name, value) = entry.split_once(':')
            .ok_or_else(|| "HTTP 响应头缺少冒号".to_owned())?;
        let value = value.trim();
        if name.eq_ignore_ascii_case("content-length") {
            if length.is_some() { return Err("重复 Content-Length".into()); }
            length = Some(value.parse::<u64>().map_err(|_| "Content-Length 非法".to_owned())?);
        } else if name.eq_ignore_ascii_case("transfer-encoding") {
            if transfer.replace(value.to_ascii_lowercase()).is_some() {
                return Err("重复 Transfer-Encoding".into());
            }
        } else if name.eq_ignore_ascii_case("content-type") {
            json = value.to_ascii_lowercase().starts_with("application/json");
        } else if name.eq_ignore_ascii_case("content-encoding") {
            compressed = !value.eq_ignore_ascii_case("identity");
        }
    }
    if !json { return Err("响应不是 application/json，不保存可能的错误页".into()); }
    if compressed { return Err("不支持的 HTTP 内容压缩".into()); }
    if length.is_some() && transfer.is_some() {
        return Err("同时出现 Content-Length 和 Transfer-Encoding".into());
    }
    match (length, transfer.as_deref()) {
        (Some(n), None) if n <= MAX_BODY => Ok(Framing::ContentLength(n)),
        (Some(_), None) => Err("超过 8 MiB 单次保存限制".into()),
        (None, Some("chunked")) => Ok(Framing::Chunked),
        (None, Some(_)) => Err("暂不支持该 Transfer-Encoding".into()),
        (None, None) => Err("缺少内容长度或 chunked 结束标志，无法验证完整性".into()),
        _ => unreachable!(),
    }
}

fn copy_exact<R: Read, W: Write>(
    reader: &mut R, writer: &mut W, bytes: u64, hash: &mut Sha256,
) -> Result<(), String> {
    let mut left = bytes;
    let mut buf = [0u8; 16 * 1024];
    while left > 0 {
        let count = left.min(buf.len() as u64) as usize;
        reader.read_exact(&mut buf[..count])
            .map_err(|e| format!("HTTPS 正文不完整：{e}"))?;
        writer.write_all(&buf[..count])
            .map_err(|e| format!("写入私有临时文件失败：{e}"))?;
        hash.update(&buf[..count]);
        left -= count as u64;
    }
    Ok(())
}

fn receive<R: BufRead, W: Write>(reader: &mut R, output: &mut W) -> Result<Receipt, String> {
    let framing = headers(reader)?;
    let mut hash = Sha256::new();
    let mut total = 0u64;
    match framing {
        Framing::ContentLength(n) => {
            copy_exact(reader, output, n, &mut hash)?;
            total = n;
        }
        Framing::Chunked => loop {
            let line = read_line(reader, 128)?;
            let digits = line.split(|b| *b == b';').next().unwrap_or_default();
            let digits = std::str::from_utf8(digits)
                .map_err(|_| "HTTP chunk 长度编码错误".to_owned())?;
            let n = u64::from_str_radix(digits, 16)
                .map_err(|_| "HTTP chunk 长度非法".to_owned())?;
            if n == 0 {
                let mut trailer_bytes = 0usize;
                loop {
                    let trailer = read_line(reader, MAX_HEADERS)?;
                    trailer_bytes += trailer.len() + 2;
                    if trailer_bytes > MAX_HEADERS { return Err("HTTP trailer 过长".into()); }
                    if trailer.is_empty() { break; }
                }
                break;
            }
            if total.checked_add(n).is_none_or(|sum| sum > MAX_BODY) {
                return Err("超过 8 MiB 单次保存限制".into());
            }
            copy_exact(reader, output, n, &mut hash)?;
            total += n;
            if !read_line(reader, 2)?.is_empty() {
                return Err("HTTP chunk 正文末尾缺少 CRLF".into());
            }
        },
    }
    output.flush().map_err(|e| format!("刷新临时文件失败：{e}"))?;
    let digest = hash.finalize();
    let hex = digest.iter().map(|byte| format!("{byte:02x}")).collect();
    Ok(Receipt { bytes: total, sha256: hex, framing })
}

fn download_one(ip: IpAddr, handle: u64, output: &mut File) -> Result<(Receipt, Duration), String> {
    let start = Instant::now();
    let conn = crate::android_network::connect(
        SocketAddr::new(ip, 443), handle, Duration::from_secs(3),
    ).map_err(|e| format!("网络绑定/TCP 连接失败：{e}"))?;
    conn.set_read_timeout(Some(Duration::from_secs(5)))
        .map_err(|e| format!("设置读取超时失败：{e}"))?;
    conn.set_write_timeout(Some(Duration::from_secs(5)))
        .map_err(|e| format!("设置写入超时失败：{e}"))?;
    let tls = ClientConnection::new(
        crate::verify::config().map_err(|e| format!("Google 根证书错误：{e}"))?,
        ServerName::try_from(HOST.to_owned()).map_err(|e| format!("SNI 错误：{e}"))?,
    ).map_err(|e| format!("TLS 初始化失败：{e}"))?;
    let mut stream = StreamOwned::new(tls, conn);
    stream.write_all(REQUEST.as_bytes())
        .map_err(|e| format!("TLS 握手/HTTP 请求失败：{e}"))?;
    let mut reader = BufReader::new(stream);
    let receipt = receive(&mut reader, output)?;
    output.sync_all().map_err(|e| format!("保存临时文件失败：{e}"))?;
    Ok((receipt, start.elapsed()))
}

/// Small public Drive Discovery payload for endpoint selection.
/// Authenticated Drive files are NOT fetched here. The public JSON is
/// written to std::io::sink(), and full HTTP framing is verified.
pub(crate) fn benchmark(ip: IpAddr, handle: u64) -> Result<(u64, u128), String> {
    let begun = Instant::now();
    let tcp = crate::android_network::connect(
        SocketAddr::new(ip, 443), handle, Duration::from_millis(2500),
    ).map_err(|e| format!("TCP：{e}"))?;
    tcp.set_read_timeout(Some(Duration::from_secs(5)))
        .map_err(|e| format!("Read timeout：{e}"))?;
    tcp.set_write_timeout(Some(Duration::from_secs(5)))
        .map_err(|e| format!("Write timeout：{e}"))?;
    let tls = ClientConnection::new(
        crate::verify::config().map_err(|e| format!("Google GTS：{e}"))?,
        ServerName::try_from(HOST.to_owned()).map_err(|e| format!("Google SNI：{e}"))?,
    ).map_err(|e| format!("TLS：{e}"))?;
    let mut stream = StreamOwned::new(tls, tcp);
    stream.write_all(REQUEST.as_bytes())
        .map_err(|e| format!("HTTPS：{e}"))?;
    let mut reader = BufReader::new(stream);
    let mut sink = std::io::sink();
    let receipt = receive(&mut reader, &mut sink)?;
    if receipt.bytes < 4096 {
        return Err("Google 测速文档数据太短".into());
    }
    Ok((receipt.bytes, begun.elapsed().as_millis()))
}

pub fn save(handle: u64, source_fd: i32) -> String {
    if source_fd < 0 { return "ERR\n无效文件描述符".into(); }
    let fd = unsafe { libc::dup(source_fd) };
    if fd < 0 {
        return format!("ERR\n复制 JNI 文件描述符失败：{}", std::io::Error::last_os_error());
    }
    // The duplicate belongs to Rust; the original ParcelFileDescriptor
    // continues to belong to Java and must be closed by Java.
    let mut file = unsafe { File::from_raw_fd(fd) };
    let mut failures = Vec::new();
    for candidate in IP_POOL {
        if let Err(e) = file.set_len(0).and_then(|()| file.seek(SeekFrom::Start(0)).map(|_| ())) {
            return format!("ERR\n重置临时文件失败：{e}");
        }
        let ip: IpAddr = candidate.parse().expect("fixed trusted IP");
        match download_one(ip, handle, &mut file) {
            Ok((receipt, spent)) if receipt.bytes > 0 => {
                return format!(
                    "OK\nGoogle TLS 和 HTTP 完整性验证通过\n源 IP：{ip}\nNetwork handle：{handle}\nURL：https://{HOST}{PATH}\n保存：{} 字节\n响应封装：{:?}\nSHA-256：{}\n耗时：{} ms",
                    receipt.bytes, receipt.framing, receipt.sha256, spent.as_millis()
                );
            }
            Ok(_) => failures.push(format!("{ip}：HTTP 200 但正文为空")),
            Err(e) => failures.push(format!("{ip}：{e}")),
        }
    }
    let _ = file.set_len(0);
    format!("ERR\n三个候选均无法完成下载（用户文档未写入数据）：\n{}", failures.join("\n"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn load(data: &[u8]) -> Result<(Vec<u8>, Receipt), String> {
        let mut reader = BufReader::new(Cursor::new(data));
        let mut out = Vec::new();
        let receipt = receive(&mut reader, &mut out)?;
        Ok((out, receipt))
    }
    #[test]
    fn complete_chunked_json_body_is_saved_without_close_notify() {
        let (data, info) = load(b"HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n").unwrap();
        assert_eq!(data, b"hello world");
        assert_eq!(info.bytes, 11);
        assert_eq!(info.framing, Framing::Chunked);
        assert_eq!(info.sha256, "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
    }
    #[test]
    fn exact_content_length_is_saved() {
        let (data, info) = load(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 5\r\n\r\nhello").unwrap();
        assert_eq!(data, b"hello");
        assert_eq!(info.bytes, 5);
    }
    #[test]
    fn truncated_responses_are_rejected() {
        assert!(load(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 10\r\n\r\nabc").is_err());
        assert!(load(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n8\r\nabc").is_err());
    }
    #[test]
    fn does_not_save_html_or_401_error_page() {
        assert!(load(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 2\r\n\r\nhi").is_err());
        assert!(load(b"HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}").is_err());
    }
    #[test]
    fn ambiguous_or_unknown_framing_is_rejected() {
        assert!(load(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n{}").is_err());
        assert!(load(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{}").is_err());
    }
    #[test]
    fn chunked_extensions_and_trailers_supported() {
        let (data, _) = load(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n2;foo=1\r\n{}\r\n0\r\nX-End: yes\r\n\r\n").unwrap();
        assert_eq!(data, b"{}");
    }
    #[test]
    fn compressed_payload_rejected() {
        assert!(load(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\nContent-Length: 2\r\n\r\nxx").is_err());
    }
}
