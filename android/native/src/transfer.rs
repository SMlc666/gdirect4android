//! Real, bounded HTTPS payload transfer over an Android-selected Network.
//! Public Google Drive v3 Discovery document: no OAuth or access tokens needed.
//! This is a path/throughput diagnostic; not yet a persistent file downloader.
use rustls::pki_types::ServerName;
use rustls::{ClientConnection, StreamOwned};
use std::io::{Read, Write};
use std::net::{IpAddr, SocketAddr};
use std::time::{Duration, Instant};

const HOST: &str = "www.googleapis.com";
const URL: &str = "https://www.googleapis.com/discovery/v1/apis/drive/v3/rest";
const REQUEST: &str = "GET /discovery/v1/apis/drive/v3/rest HTTP/1.1\r\nHost: www.googleapis.com\r\nConnection: close\r\nAccept: application/json\r\nAccept-Encoding: identity\r\nUser-Agent: gdirect-android-transfer-smoke/0.4\r\n\r\n";
const SAMPLES: [&str; 3] = ["142.250.27.100", "108.177.9.138", "64.233.160.100"];
const MAX_BODY: usize = 1_048_576;
const MAX_HEADERS: usize = 16 * 1024;

#[derive(Debug)]
struct Sample {
    status: u16,
    body_bytes: usize,
    capped: bool,
    elapsed: Duration,
}

fn sample(ip: IpAddr, handle: u64) -> Result<Sample, String> {
    let started = Instant::now();
    let socket = crate::android_network::connect(
        SocketAddr::new(ip, 443), handle, Duration::from_secs(3),
    ).map_err(|e| format!("TCP：{e}"))?;
    socket.set_read_timeout(Some(Duration::from_secs(4)))
        .map_err(|e| format!("读超时设置失败：{e}"))?;
    socket.set_write_timeout(Some(Duration::from_secs(4)))
        .map_err(|e| format!("写超时设置失败：{e}"))?;

    let server = ServerName::try_from(HOST.to_owned())
        .map_err(|e| format!("TLS 主机名错误：{e}"))?;
    let conn = ClientConnection::new(
        crate::verify::config().map_err(|e| format!("TLS 根证书：{e}"))?,
        server,
    ).map_err(|e| format!("TLS 初始化：{e}"))?;
    let mut stream = StreamOwned::new(conn, socket);
    stream.write_all(REQUEST.as_bytes())
        .map_err(|e| format!("TLS 握手或 HTTP 发送：{e}"))?;

    // Discard the response body after counting it. Avoid allocating a large
    // buffer or writing untrusted HTTP content to user-visible storage.
    let mut chunk = [0u8; 8192];
    let mut headers = Vec::<u8>::new();
    let mut status = None;
    let mut count = 0usize;
    let mut capped = false;

    loop {
        // End-to-end budget also bounds a server that keeps sending chunks.
        if started.elapsed() > Duration::from_secs(15) {
            return Err("超过 15 秒的总测试时间上限".into());
        }
        let size = stream.read(&mut chunk)
            .map_err(|e| format!("HTTPS 接收失败：{e}"))?;
        if size == 0 { break; }
        if status.is_none() {
            headers.extend_from_slice(&chunk[..size]);
            if headers.len() > MAX_HEADERS {
                return Err("HTTP 响应头过大".into());
            }
            if let Some(end) = headers.windows(4).position(|w| w == b"\r\n\r\n") {
                let line_end = headers.iter().position(|b| *b == b'\n').unwrap_or(end);
                let line = String::from_utf8_lossy(&headers[..line_end]);
                let code = line.split_whitespace().nth(1)
                    .and_then(|v| v.parse::<u16>().ok())
                    .filter(|v| (100..600).contains(v))
                    .ok_or_else(|| format!("无效 HTTP 响应行：{line}"))?;
                status = Some(code);
                count = headers.len().saturating_sub(end + 4);
                headers.clear();
                if code != 200 {
                    return Ok(Sample { status: code, body_bytes: count, capped: false, elapsed: started.elapsed() });
                }
            }
        } else {
            count = count.saturating_add(size);
        }
        if count >= MAX_BODY {
            count = MAX_BODY;
            capped = true;
            break;
        }
    }
    let status = status.ok_or_else(|| "HTTPS 连接结束但没有完整 HTTP 响应头".to_owned())?;
    Ok(Sample { status, body_bytes: count, capped, elapsed: started.elapsed() })
}

pub fn run(handle: u64) -> String {
    let mut report = format!(
        "公开 Drive API 文档 HTTPS 传输测试\nURL：{URL}\nIP：上次 Wi-Fi 验证成功的 3 个候选\nNetwork handle：{handle}\n单 IP 最多接收 1 MiB；串行测试，最多 3 个候选\n\n"
    );
    for ip in SAMPLES {
        let parsed: IpAddr = ip.parse().expect("constant Google IP");
        match sample(parsed, handle) {
            Ok(s) if s.status == 200 && s.body_bytes > 0 => {
                let secs = s.elapsed.as_secs_f64().max(0.001);
                let rate = s.body_bytes as f64 / secs / 1024.0;
                report.push_str(&format!(
                    "✅ {ip} · HTTPS 200 · 收到 {} KiB{} · {:.1} KiB/s · {:.0} ms\n",
                    s.body_bytes / 1024,
                    if s.capped { "（已达 1 MiB 上限）" } else { "" },
                    rate,
                    secs * 1000.0
                ));
            }
            Ok(s) => report.push_str(&format!(
                "— {ip} · HTTPS {} · {} 字节 · 未通过有效载荷测试\n",
                s.status, s.body_bytes
            )),
            Err(err) => report.push_str(&format!("— {ip} · {err}\n")),
        }
    }
    report.push_str(
        "\n数据只在内存中计数后丢弃，不会保存文件。速率包含建连/TLS 时间，不等于持续下载速度。\n"
    );
    if handle == 0 {
        report.push_str("⚠️ 当前为系统默认路径：成功也可能经过 VPN。\n");
    }
    report
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sample_targets_are_real_ip_literals() {
        for ip in SAMPLES {
            let value: IpAddr = ip.parse().unwrap();
            assert!(matches!(value, IpAddr::V4(_)));
        }
    }
}
