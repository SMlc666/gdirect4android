//! Pinned-IP Google Drive JSON API over a user-selected Android Network.
//! Unlike URL.openConnection this sends TCP to the literal verified Google IP.
//! TLS always verifies www.googleapis.com against upstream GTS roots.
//! An OAuth bearer token is NEVER sent to an unverified host or redirected.
use jni::{objects::{JClass, JString}, sys::{jlong, jstring}, JNIEnv};
use rustls::pki_types::ServerName;
use rustls::{ClientConnection, StreamOwned};
use serde_json::{Value, json};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::time::{Duration, Instant};

const HOST: &str = "www.googleapis.com";
const MAX_HEADER: usize = 16 * 1024;
const MAX_BODY: usize = 2 * 1024 * 1024;
const MAX_REQUEST: usize = 32 * 1024;

fn line<R: BufRead>(reader: &mut R, max: usize) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    reader.read_until(b'\n', &mut bytes)
        .map_err(|e| format!("HTTP 读取失败：{e}"))?;
    if bytes.len() > max {
        return Err("HTTP 单行超过限制".into());
    }
    if !bytes.ends_with(b"\r\n") {
        return Err("HTTP 行截断或没有 CRLF".into());
    }
    bytes.truncate(bytes.len() - 2);
    Ok(bytes)
}

fn read_body<R: BufRead>(reader: &mut R, max: usize) -> Result<(u16, Vec<u8>), String> {
    let status_line = String::from_utf8(line(reader, MAX_HEADER)?)
        .map_err(|_| "HTTP 状态行编码不合法".to_owned())?;
    if !status_line.starts_with("HTTP/1.") {
        return Err("不是 HTTP/1.x 服务器响应".into());
    }
    let status = status_line.split_ascii_whitespace()
        .nth(1).and_then(|s| s.parse::<u16>().ok())
        .filter(|s| (100..600).contains(s))
        .ok_or_else(|| "HTTP 状态码不合法".to_owned())?;

    let mut length: Option<usize> = None;
    let mut transfer: Option<String> = None;
    let mut encoding = String::new();
    let mut content_type = String::new();
    let mut total_header = status_line.len() + 2;
    loop {
        let raw = line(reader, MAX_HEADER)?;
        total_header += raw.len() + 2;
        if total_header > MAX_HEADER { return Err("HTTP 响应头过长".into()); }
        if raw.is_empty() { break; }
        let field = String::from_utf8(raw).map_err(|_| "HTTP 响应头编码异常".to_owned())?;
        let (key, value) = field.split_once(':')
            .ok_or_else(|| "HTTP 响应头缺少冒号".to_owned())?;
        let value = value.trim();
        if key.eq_ignore_ascii_case("content-length") {
            if length.is_some() { return Err("重复 Content-Length".into()); }
            length = Some(value.parse().map_err(|_| "Content-Length 无效".to_owned())?);
        } else if key.eq_ignore_ascii_case("transfer-encoding") {
            if transfer.replace(value.to_ascii_lowercase()).is_some() {
                return Err("重复 Transfer-Encoding".into());
            }
        } else if key.eq_ignore_ascii_case("content-encoding") {
            encoding = value.to_ascii_lowercase();
        } else if key.eq_ignore_ascii_case("content-type") {
            content_type = value.to_ascii_lowercase();
        }
    }
    if !encoding.is_empty() && encoding != "identity" {
        return Err("HTTP 服务返回了未请求的压缩内容".into());
    }
    if length.is_some() && transfer.is_some() {
        return Err("服务器返回冲突的 HTTP 正文长度字段".into());
    }
    if (200..300).contains(&status) && status != 204
        && !content_type.starts_with("application/json")
    {
        return Err("Google Drive API 返回了非 JSON 数据，拒绝解析".into());
    }
    // Never disclose the raw response to Java on HTTP errors, because
    // errors can include request/account details and writes aren't replayed.
    if !(200..300).contains(&status) {
        return Ok((status, Vec::new()));
    }
    if status == 204 {
        return Ok((status, Vec::new()));
    }
    let mut result = Vec::new();
    if let Some(len) = length {
        if len > max { return Err("Drive API JSON 超过 2 MiB 限制".into()); }
        result.resize(len, 0);
        reader.read_exact(&mut result)
            .map_err(|e| format!("HTTP 正文未完整接收：{e}"))?;
    } else if transfer.as_deref() == Some("chunked") {
        loop {
            let size_line = line(reader, 128)?;
            let digits = size_line.split(|b| *b == b';')
                .next().unwrap_or_default();
            let size = std::str::from_utf8(digits)
                .ok().and_then(|s| usize::from_str_radix(s.trim(), 16).ok())
                .ok_or_else(|| "无效的 HTTP 分块大小".to_owned())?;
            if size == 0 {
                let mut trailer = 0;
                loop {
                    let trail = line(reader, MAX_HEADER)?;
                    trailer += trail.len() + 2;
                    if trailer > MAX_HEADER { return Err("HTTP chunk trailers 过长".into()); }
                    if trail.is_empty() { break; }
                }
                break;
            }
            if result.len().checked_add(size).is_none_or(|n| n > max) {
                return Err("Drive API JSON 超过 2 MiB 限制".into());
            }
            let previous = result.len();
            result.resize(previous + size, 0);
            reader.read_exact(&mut result[previous..])
                .map_err(|e| format!("HTTP chunk 正文不完整：{e}"))?;
            if !line(reader, 2)?.is_empty() {
                return Err("HTTP chunk 末尾缺少 CRLF".into());
            }
        }
    } else {
        return Err("HTTP 正文未声明 Content-Length / chunked，无法确认完整性".into());
    }
    if serde_json::from_slice::<Value>(&result).is_err() {
        return Err("Drive API 载荷不是合法 JSON".into());
    }
    Ok((status, result))
}

fn validate(url: &str, method: &str, token: &str, body: &str, ip: &str)
    -> Result<(String, IpAddr), String>
{
    let path = url.strip_prefix("https://www.googleapis.com")
        .ok_or_else(|| "只允许 Google 官方 Drive API HTTPS 主机".to_owned())?;
    if !(path == "/drive/v3/files"
        || path.starts_with("/drive/v3/files?")
        || path.starts_with("/drive/v3/files/"))
        || path.len() > 8192 || !path.is_ascii() || path.contains('#')
        || path.bytes().any(|b| b < 0x20 || b >= 0x7f)
    {
        return Err("只允许 /drive/v3/files 下的合法 API 路径".into());
    }
    if !matches!(method, "GET" | "POST" | "PATCH") {
        return Err("不支持的 Drive API 请求方式".into());
    }
    if token.is_empty() || token.len() > 8192 || !token.bytes().all(|b| b.is_ascii_graphic()) {
        return Err("无效 Google OAuth 访问令牌".into());
    }
    if body.len() > MAX_REQUEST {
        return Err("Drive API 请求 JSON 过大".into());
    }
    if method != "GET" && serde_json::from_str::<Value>(body).is_err() {
        return Err("要提交的 JSON 请求体无效".into());
    }
    if method == "GET" && !body.is_empty() {
        return Err("GET 不允许请求体".into());
    }
    let address: Ipv4Addr = ip.parse()
        .map_err(|_| "优选地址不是有效 IPv4".to_owned())?;
    if !crate::verify::google_ipv4(address) {
        return Err("选定 IP 不属于仓库内 Google 官方地址段；拒绝携带 OAuth 令牌连接".into());
    }
    Ok((path.to_owned(), IpAddr::V4(address)))
}

fn attempt(
    url: &str, method: &str, token: &str, body: &str, network: u64, pinned_ip: &str,
) -> Result<(u16, String, u128), String> {
    let (path, address) = validate(url, method, token, body, pinned_ip)?;
    let start = Instant::now();
    let socket = crate::android_network::connect(
        SocketAddr::new(address, 443), network, Duration::from_millis(2500),
    ).map_err(|e| format!("选定网络 TCP 连接失败：{e}"))?;
    socket.set_read_timeout(Some(Duration::from_secs(6)))
        .map_err(|e| format!("接收超时设置失败：{e}"))?;
    socket.set_write_timeout(Some(Duration::from_secs(6)))
        .map_err(|e| format!("发送超时设置失败：{e}"))?;

    let tls = ClientConnection::new(
        crate::verify::config().map_err(|e| format!("GTS 根证书错误：{e}"))?,
        ServerName::try_from(HOST.to_owned()).map_err(|e| format!("TLS SNI 错误：{e}"))?,
    ).map_err(|e| format!("TLS 初始化失败：{e}"))?;
    let mut stream = StreamOwned::new(tls, socket);
    let mut req = format!(
        "{method} {path} HTTP/1.1\r\nHost: {HOST}\r\n\
         User-Agent: gdirect-android-pinned-api/0.9.2\r\n\
         Accept: application/json\r\nAccept-Encoding: identity\r\n\
         Connection: close\r\nAuthorization: Bearer {token}\r\n"
    );
    if method != "GET" {
        req.push_str(&format!(
            "Content-Type: application/json; charset=UTF-8\r\nContent-Length: {}\r\n",
            body.len()
        ));
    }
    req.push_str("\r\n");
    stream.write_all(req.as_bytes())
        .map_err(|e| format!("TLS 握手或 HTTP 请求失败：{e}"))?;
    if method != "GET" {
        stream.write_all(body.as_bytes())
            .map_err(|e| format!("HTTPS 请求正文发送失败：{e}"))?;
    }
    let mut reader = BufReader::new(stream);
    let (status, bytes) = read_body(&mut reader, MAX_BODY)?;
    let body = String::from_utf8(bytes)
        .map_err(|_| "Google JSON 响应 UTF-8 无效".to_owned())?;
    Ok((status, body, start.elapsed().as_millis()))
}

fn read_str(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String, String> {
    env.get_string(&value)
        .map(|s| s.into()).map_err(|_| "JNI 字符串参数读取失败".to_owned())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeDriveApiRequest(
    mut env: JNIEnv<'_>, _class: JClass<'_>, url: JString<'_>, method: JString<'_>,
    token: JString<'_>, body: JString<'_>, handle: jlong, ip: JString<'_>,
) -> jstring {
    let result = (|| -> Result<(u16, String, u128, String), String> {
        let url = read_str(&mut env, url)?;
        let method = read_str(&mut env, method)?;
        let token = read_str(&mut env, token)?;
        let body = read_str(&mut env, body)?;
        let ip = read_str(&mut env, ip)?;
        let (status, response, elapsed) =
            attempt(&url, &method, &token, &body, handle as u64, &ip)?;
        Ok((status, response, elapsed, ip))
    })();
    let message = match result {
        Ok((status, response, elapsed_ms, ip)) =>
            json!({"status":status,"body":response,"elapsed_ms":elapsed_ms,"ip":ip}).to_string(),
        Err(reason) => json!({"error":reason}).to_string(),
    };
    crate::to_java(&mut env, &message)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn http(raw: &[u8]) -> Result<(u16,Vec<u8>),String> {
        let mut reader = BufReader::new(raw);
        read_body(&mut reader, MAX_BODY)
    }
    #[test]
    fn complete_chunked_response() {
        let (code, body) = http(
            b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n"
        ).unwrap();
        assert_eq!(code, 200); assert_eq!(body, b"{}");
    }
    #[test]
    fn complete_length_without_close_notify() {
        assert_eq!(http(
            b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"
        ).unwrap().1, b"{}");
    }
    #[test]
    fn truncated_chunked_or_length_is_rejected() {
        assert!(http(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 6\r\n\r\n{}").is_err());
        assert!(http(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n5\r\n{}").is_err());
    }
    #[test]
    fn redirects_and_unauthorized_are_not_forwarded() {
        let (status, body) = http(b"HTTP/1.1 302 Found\r\nLocation: https://evil.example/\r\nContent-Length: 0\r\n\r\n").unwrap();
        assert_eq!(status, 302); assert!(body.is_empty());
    }
    #[test]
    fn no_untrusted_ip_or_endpoint_gets_bearer_token() {
        let token = "test_access_token";
        assert!(validate("https://example.com/drive/v3/files","GET",token,"","142.250.27.100").is_err());
        assert!(validate("https://www.googleapis.com/drive/v3/files","GET",token,"","198.18.1.246").is_err());
        assert!(validate("https://www.googleapis.com/drive/v3/files","GET",token,"","8.8.8.8").is_ok());
        assert!(validate("https://www.googleapis.com/evil","GET",token,"","142.250.27.100").is_err());
        assert!(validate("https://www.googleapis.com/drive/v3/files\r\nX: injection","GET",token,"","142.250.27.100").is_err());
    }
}
