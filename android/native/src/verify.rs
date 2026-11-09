//! Bounded, opt-in Google endpoint verification on Android physical/VPN networks.
//! Performs actual TLS certificate + hostname verification against the Google
//! Trust Services roots shipped by upstream gdirect. Never trusts Fake-IP as
//! Google and never disables TLS verification for diagnosis.
use rustls::pki_types::ServerName;
use rustls::{ClientConfig, ClientConnection, RootCertStore, StreamOwned};
use std::io::{Read, Write};
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

const CERTS: &str = include_str!("../../../src/gts-roots.pem");
const TARGET: &str = "www.googleapis.com";
const REQUEST: &str = "GET /drive/v3/about?fields=user HTTP/1.1\r\nHost: www.googleapis.com\r\nConnection: close\r\nUser-Agent: gdirect-android-diagnostics/0.3\r\nAccept: application/json\r\n\r\n";

/// A deliberately small sample from Google's published non-Cloud service
/// space (source: upstream gdirect src/google-ranges.txt and src/scan.rs).
/// Do not extrapolate a success rate or perform a background range sweep.
const SEEDS: [&str; 16] = [
    "142.250.27.100", "142.250.27.113",
    "142.250.72.100", "142.250.72.113",
    "172.217.114.100", "172.217.115.100",
    "172.253.0.100", "173.194.0.100",
    "74.125.0.100", "108.177.9.138",
    "192.178.0.100", "216.58.192.100",
    "209.85.128.100", "64.233.160.100",
    "172.217.1.113", "142.250.1.155",
];

static CONFIG: OnceLock<Result<Arc<ClientConfig>, String>> = OnceLock::new();

fn config() -> Result<Arc<ClientConfig>, String> {
    CONFIG.get_or_init(|| {
        let mut roots = RootCertStore::empty();
        let mut reader = std::io::BufReader::new(CERTS.as_bytes());
        for certificate in rustls_pemfile::certs(&mut reader) {
            let certificate = certificate.map_err(|e| format!("GTS PEM 错误：{e}"))?;
            roots.add(certificate).map_err(|e| format!("GTS 证书错误：{e}"))?;
        }
        if roots.is_empty() {
            return Err("没有加载任何 GTS 根证书".to_owned());
        }
        // The explicit provider prevents ambiguity when reqwest enables a
        // second rustls crypto backend through upstream dependencies.
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let client = ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .map_err(|e| format!("TLS 配置错误：{e}"))?
            .with_root_certificates(roots)
            .with_no_client_auth();
        Ok(Arc::new(client))
    }).clone()
}

fn fake_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let parts = v4.octets();
            parts[0] == 198 && (parts[1] == 18 || parts[1] == 19)
        }
        IpAddr::V6(_) => false,
    }
}

type Failed = (&'static str, String);

/// Connect to the literal IP on the selected Android network, perform full
/// TLS verification of SNI/hostname, and request an unauthenticated Drive API
/// endpoint. HTTP 401 is an expected authentication challenge.
fn verify_one(ip: IpAddr, network: u64, identity: &str) -> Result<(u16, u128), Failed> {
    let start = Instant::now();
    let conn = crate::android_network::connect(
        SocketAddr::new(ip, 443), network, Duration::from_millis(1900),
    ).map_err(|e| ("TCP", e.to_string()))?;
    conn.set_read_timeout(Some(Duration::from_secs(3)))
        .map_err(|e| ("TCP", e.to_string()))?;
    conn.set_write_timeout(Some(Duration::from_secs(3)))
        .map_err(|e| ("TCP", e.to_string()))?;

    let server_name = ServerName::try_from(identity.to_owned())
        .map_err(|e| ("TLS", format!("域名无效：{e}")))?;
    let tls_conn = ClientConnection::new(
        config().map_err(|e| ("TLS", e))?,
        server_name,
    ).map_err(|e| ("TLS", e.to_string()))?;
    let mut stream = StreamOwned::new(tls_conn, conn);
    stream.write_all(REQUEST.as_bytes())
        .map_err(|e| ("TLS", format!("TLS 握手或 HTTP 写入失败：{e}")))?;
    let mut bytes = [0u8; 4096];
    let size = stream.read(&mut bytes)
        .map_err(|e| ("HTTP", format!("TLS 读取失败：{e}")))?;
    let status = std::str::from_utf8(&bytes[..size])
        .map_err(|_| ("HTTP", "非 UTF-8 HTTP 响应头".into()))?
        .lines()
        .next()
        .unwrap_or("");
    if !status.starts_with("HTTP/1.") {
        return Err(("HTTP", format!("无效的 HTTP 状态行：{status}")));
    }
    let code = status.split_whitespace().nth(1)
        .and_then(|part| part.parse::<u16>().ok())
        .filter(|code| (100..600).contains(code))
        .ok_or_else(|| ("HTTP", format!("无法解析 HTTP 状态：{status}")))?;
    Ok((code, start.elapsed().as_millis()))
}

fn trial(ip: IpAddr, network: u64) -> (bool, String) {
    if fake_ip(ip) {
        return (false, format!("{ip} · Fake-IP (198.18.0.0/15)，不是 Google 真实地址；不作为直连验证"));
    }
    // The upstream downloader tries its forged Google identity when the
    // original TLS identity fails, but it does not double up TCP timeouts.
    let first = verify_one(ip, network, TARGET);
    let (answer, identity) = match first {
        Ok(ok) => (Ok(ok), TARGET),
        Err(("TLS", problem)) => {
            match verify_one(ip, network, "g.cn") {
                Ok(ok) => (Ok(ok), "g.cn"),
                Err((stage, alt)) => (Err(format!("原始 TLS：{problem}；备用 g.cn {stage}：{alt}")), "g.cn"),
            }
        }
        Err((stage, reason)) => (Err(format!("{stage}：{reason}")), TARGET),
    };
    match answer {
        Ok((code, ms)) => {
            let api = match code {
                401 | 403 => "Drive API 返回身份验证响应（可达）",
                _ => "已验证 Google HTTPS；此状态码不足以确认 Drive API 可用",
            };
            (true, format!("{ip} · HTTPS 证书/主机名验证成功 · SNI {identity} · HTTP {code} · {ms} ms · {api}"))
        }
        Err(problem) => (false, format!("{ip} · {problem}")),
    }
}

pub fn inspect(ip: &str, handle: u64) -> String {
    let ip: IpAddr = match ip.parse() {
        Ok(ip) => ip,
        Err(_) => return "请输入有效的 IPv4 或 IPv6 字面地址".into(),
    };
    trial(ip, handle).1
}

pub fn scan(handle: u64) -> String {
    let cursor = AtomicUsize::new(0);
    let records: Mutex<Vec<(bool, String)>> =
        Mutex::new(vec![(false, String::new()); SEEDS.len()]);
    // Four threads, at most 16 destinations, no background sweeping.
    std::thread::scope(|scope| {
        for _ in 0..4 {
            let cursor = &cursor;
            let records = &records;
            scope.spawn(move || loop {
                let index = cursor.fetch_add(1, Ordering::Relaxed);
                if index >= SEEDS.len() { break; }
                let ip: IpAddr = SEEDS[index].parse().expect("trusted static Google IP");
                let result = trial(ip, handle);
                if let Ok(mut guard) = records.lock() {
                    guard[index] = result;
                }
            });
        }
    });
    let records = match records.into_inner() {
        Ok(value) => value,
        Err(_) => return "扫描状态锁异常".into(),
    };
    let passed = records.iter().filter(|(ok, _)| *ok).count();
    let mut summary = format!(
        "Google 官方前端候选扫描（16 个 IP / 最多 4 并发）\nTLS+HTTP 通过：{passed}/16\n所选 Network handle：{handle}\n\n"
    );
    for (ok, line) in records {
        summary.push_str(if ok { "✅ " } else { "— " });
        summary.push_str(&line);
        summary.push('\n');
    }
    summary.push_str("\n只有明确选择 Wi-Fi/移动数据且绑定成功，才可以把该路径作为直连候选；系统默认/VPN 成功不等于绕过代理。");
    summary
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn fake_ip_is_never_a_google_origin() {
        assert!(fake_ip("198.18.1.246".parse().unwrap()));
        assert!(fake_ip("198.19.255.255".parse().unwrap()));
        assert!(!fake_ip("142.250.27.100".parse().unwrap()));
    }
}
