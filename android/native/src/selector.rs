//! Opt-in expanded Google frontend discovery and endpoint ranking.
//! Reuses upstream gdirect core /24 generation and Google address allowlist.
//! Bounded scans only; no unbounded /0 sweep or background probes.
//! Each accepted endpoint passed Google TLS hostname verification and the
//! actual Drive HTTP 401/403 challenge on the selected Android Network.
use jni::{objects::{JClass, JString}, sys::{jint, jlong, jstring}, JNIEnv};
use rustls::pki_types::ServerName;
use rustls::{ClientConnection, StreamOwned};
use serde_json::{json, Value};
use std::collections::HashSet;
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::{Mutex, atomic::{AtomicBool, AtomicUsize, Ordering}};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const CORE: &str = "142.250.0.0/15 172.217.0.0/16 172.253.0.0/16 \
    173.194.0.0/16 74.125.0.0/16 192.178.0.0/15 64.233.160.0/19 \
    66.102.0.0/20 108.177.0.0/17 209.85.128.0/17 216.58.192.0/19 \
    216.239.32.0/19 108.170.192.0/18 72.14.192.0/18";
const OFFSETS: [u8; 7] = [100, 113, 155, 132, 138, 91, 123];
const HOST: &str = "www.googleapis.com";
const PROBE: &str = "GET /drive/v3/about?fields=user HTTP/1.1\r\nHost: www.googleapis.com\r\nAccept: application/json\r\nConnection: close\r\nUser-Agent: gdirect-android-endpoint-optimize/0.9.2\r\n\r\n";
const MAX_CANDIDATES: usize = 768;
const MAX_WORKERS: usize = 64;
const MAX_BENCHMARK_COUNT: usize = 32;

static BUSY: Mutex<()> = Mutex::new(());
static RUNNING: AtomicBool = AtomicBool::new(false);
static CANCELLED: AtomicBool = AtomicBool::new(false);
static CHECKED: AtomicUsize = AtomicUsize::new(0);
static GOOD: AtomicUsize = AtomicUsize::new(0);
static TOTAL: AtomicUsize = AtomicUsize::new(0);

#[derive(Clone, Debug)]
struct Endpoint {
    ip: Ipv4Addr,
    latency_ms: u128,
    throughput_kib_s: f64,
    benchmark_bytes: u64,
}

/// Benchmark more than the lowest-latency frontends: diversify by /24
/// and reserve half the sample for low-RTT peers. This avoids one subnet
/// dominating the throughput ranking without granting unverified IPs.
fn benchmark_targets(winners: &[Endpoint], budget: usize) -> Vec<Endpoint> {
    let goal = budget.min(winners.len());
    let mut targets = Vec::with_capacity(goal);
    let mut chosen = HashSet::new();
    let mut subnets = HashSet::new();
    for item in winners.iter().take(goal.div_ceil(2)) {
        if chosen.insert(item.ip) {
            subnets.insert(u32::from(item.ip) >> 8);
            targets.push(item.clone());
        }
    }
    for item in winners {
        if targets.len() >= goal { break; }
        if !subnets.insert(u32::from(item.ip) >> 8) { continue; }
        if chosen.insert(item.ip) { targets.push(item.clone()); }
    }
    for item in winners {
        if targets.len() >= goal { break; }
        if chosen.insert(item.ip) { targets.push(item.clone()); }
    }
    targets
}

fn shuffle<T>(values: &mut [T], mut seed: u64) {
    seed |= 1;
    for i in (1..values.len()).rev() {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        values.swap(i, (seed as usize) % (i + 1));
    }
}

/// Generates evenly distributed /24 candidates inside the original gdirect
/// Google's GENERAL frontend blocks, never arbitrary Internet addresses.
/// Always check the full included goog-minus-cloud IPv4 allowlist too.
pub(crate) fn candidates(limit: usize, seed: u64, confirmed: &[Ipv4Addr]) -> Vec<Ipv4Addr> {
    let mut bases = Vec::new();
    for cidr in CORE.split_ascii_whitespace() {
        let Some((raw, mask)) = cidr.split_once('/') else { continue; };
        let Ok(base) = raw.parse::<Ipv4Addr>() else { continue; };
        let Ok(prefix) = mask.parse::<u32>() else { continue; };
        if prefix > 24 { continue; }
        let mask = if prefix == 0 { 0 } else { u32::MAX << (32 - prefix) };
        let first = u32::from(base) & mask;
        let subnets = 1usize << (24 - prefix);
        for n in 0..subnets {
            bases.push(first + ((n as u32) << 8));
        }
    }
    shuffle(&mut bases, seed);
    let mut out = Vec::with_capacity(limit);
    let mut unique = HashSet::new();
    let mut add = |ip: Ipv4Addr, list: &mut Vec<Ipv4Addr>| {
        if list.len() < limit && crate::verify::google_ipv4(ip) && unique.insert(ip) {
            list.push(ip);
        }
    };
    // Only user-earned, still fresh, same-Network verified IPs may be
    // seeded. Do not pre-authorize any hardcoded "known good" endpoints.
    for &ip in confirmed.iter().take(8) { add(ip, &mut out); }
    // Sample /24s uniformly across core ranges. Distribute the same
    // OFFSETS across /24s rather than exhaust one huge subnet.
    for (i, &base) in bases.iter().enumerate() {
        if out.len() >= limit { break; }
        let host = OFFSETS[(i + (seed as usize) % OFFSETS.len()) % OFFSETS.len()];
        add(Ipv4Addr::from(base | u32::from(host)), &mut out);
    }
    if out.len() < limit {
        for (i, &base) in bases.iter().enumerate() {
            if out.len() >= limit { break; }
            for &offset in &OFFSETS[(i % OFFSETS.len())..] {
                add(Ipv4Addr::from(base | u32::from(offset)), &mut out);
                if out.len() >= limit { break; }
            }
        }
    }
    out
}

fn verify(ip: Ipv4Addr, handle: u64) -> Result<u128, String> {
    let started = Instant::now();
    let tcp = crate::android_network::connect(
        SocketAddr::new(IpAddr::V4(ip), 443), handle, Duration::from_millis(1500),
    ).map_err(|e| format!("TCP：{e}"))?;
    tcp.set_read_timeout(Some(Duration::from_secs(3)))
        .map_err(|e| format!("TCP 读取超时配置：{e}"))?;
    tcp.set_write_timeout(Some(Duration::from_secs(3)))
        .map_err(|e| format!("TCP 写入超时配置：{e}"))?;
    let tls = ClientConnection::new(
        crate::verify::config().map_err(|e| format!("GTS 根证书：{e}"))?,
        ServerName::try_from(HOST.to_owned()).map_err(|e| e.to_string())?,
    ).map_err(|e| format!("TLS：{e}"))?;
    let mut stream = StreamOwned::new(tls, tcp);
    stream.write_all(PROBE.as_bytes())
        .map_err(|e| format!("TLS 握手：{e}"))?;
    let mut read = [0u8; 2048];
    let n = stream.read(&mut read).map_err(|e| format!("HTTP：{e}"))?;
    let data = std::str::from_utf8(&read[..n]).map_err(|_| "HTTP 字符编码错误".to_owned())?;
    let first = data.lines().next().unwrap_or_default();
    let code = first.split_ascii_whitespace().nth(1)
        .and_then(|s| s.parse::<u16>().ok())
        .ok_or_else(|| "无法读取 Google HTTP 状态".to_owned())?;
    if !matches!(code, 401 | 403) {
        return Err(format!("TLS 通过，但 Drive API HTTP {code} 不是身份挑战"));
    }
    Ok(started.elapsed().as_millis())
}

fn scan(handle: u64, max_candidates: usize, workers: usize,
        sample_count: usize, confirmed: &[Ipv4Addr], mode: &str) -> Value {
    let Ok(_one_scan) = BUSY.try_lock() else {
        return json!({"error":"已有 Google IP 扫描运行中","running":true});
    };
    RUNNING.store(true, Ordering::SeqCst);
    CANCELLED.store(false, Ordering::SeqCst);
    CHECKED.store(0, Ordering::SeqCst);
    GOOD.store(0, Ordering::SeqCst);
    let max = max_candidates.clamp(32, MAX_CANDIDATES);
    let threads = workers.clamp(4, MAX_WORKERS).min(max);
    let seed = SystemTime::now().duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64).unwrap_or(12345) ^ handle;
    let candidates = candidates(max, seed, confirmed);
    TOTAL.store(candidates.len(), Ordering::SeqCst);
    let idx = AtomicUsize::new(0);
    let winners: Mutex<Vec<Endpoint>> = Mutex::new(Vec::new());
    std::thread::scope(|s| {
        for _ in 0..threads {
            let ips = &candidates;
            let idx = &idx;
            let winners = &winners;
            s.spawn(move || loop {
                if CANCELLED.load(Ordering::Relaxed) { break; }
                let i = idx.fetch_add(1, Ordering::Relaxed);
                if i >= ips.len() { break; }
                let ip = ips[i];
                if let Ok(ms) = verify(ip, handle) {
                    if let Ok(mut list) = winners.lock() {
                        list.push(Endpoint {
                            ip, latency_ms: ms, throughput_kib_s: 0.0,
                            benchmark_bytes: 0,
                        });
                    }
                    GOOD.fetch_add(1, Ordering::Relaxed);
                }
                CHECKED.fetch_add(1, Ordering::Relaxed);
            });
        }
    });
    let scanned = CHECKED.load(Ordering::SeqCst);
    let cancelled = CANCELLED.load(Ordering::Relaxed);
    let mut winners = winners.into_inner().unwrap_or_default();
    winners.sort_by_key(|e| e.latency_ms);
    // Sample both fast-to-respond and subnet-diverse verified endpoints.
    // Cap benchmarking at 32 and four simultaneous TLS streams.
    if !cancelled {
        let benchmarked = benchmark_targets(&winners,
            sample_count.clamp(4,MAX_BENCHMARK_COUNT));
        let cursor = AtomicUsize::new(0);
        let results = Mutex::new(Vec::<(Ipv4Addr, u64, u128)>::new());
        std::thread::scope(|scope| {
            for _ in 0..4.min(benchmarked.len()) {
                let results = &results;
                let cursor = &cursor;
                let benchmarked = &benchmarked;
                scope.spawn(move || loop {
                    if CANCELLED.load(Ordering::Relaxed) { break; }
                    let i = cursor.fetch_add(1, Ordering::Relaxed);
                    if i >= benchmarked.len() { break; }
                    let ip = benchmarked[i].ip;
                    if let Ok((bytes, elapsed)) = crate::save::benchmark(IpAddr::V4(ip), handle) {
                        if let Ok(mut records) = results.lock() {
                            records.push((ip, bytes, elapsed));
                        }
                    }
                });
            }
        });
        let results = results.into_inner().unwrap_or_default();
        for item in &mut winners {
            if let Some((_, bytes, elapsed)) = results.iter().find(|v| v.0 == item.ip) {
                item.throughput_kib_s =
                    *bytes as f64 / 1024.0 / (*elapsed as f64 / 1000.0).max(0.001);
                item.benchmark_bytes = *bytes;
            }
        }
    }
    if mode == "speed" {
        winners.sort_by(|a,b| b.throughput_kib_s.total_cmp(&a.throughput_kib_s)
            .then(a.latency_ms.cmp(&b.latency_ms)));
    } else {
        winners.sort_by(|a,b| a.latency_ms.cmp(&b.latency_ms)
            .then(b.throughput_kib_s.total_cmp(&a.throughput_kib_s)));
    }
    RUNNING.store(false, Ordering::SeqCst);
    json!({
        "mode":mode,
        "network_handle":handle,
        "probed":scanned,
        "requested":candidates.len(),
        "concurrency":threads,
        "success":winners.len(),
        "cancelled":cancelled,
        "winners":winners.into_iter().take(16).map(|w|json!({
            "ip":w.ip.to_string(),
            "latency_ms":w.latency_ms,
            "speed_kib_s":(w.throughput_kib_s * 10.0).round() / 10.0,
            "benchmark_bytes":w.benchmark_bytes
        })).collect::<Vec<_>>(),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeOptimizeGoogle(
    mut env: JNIEnv<'_>, _class: JClass<'_>,
    handle: jlong, max: jint, workers: jint, speed_mode: jint,
    sample_count: jint, confirmed_ips: JString<'_>,
) -> jstring {
    let mode = if speed_mode != 0 { "speed" } else { "latency" };
    let text = match env.get_string(&confirmed_ips) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(_) => String::new(),
    };
    let confirmed: Vec<Ipv4Addr> = text.split(',').take(8)
        .filter_map(|item| item.trim().parse::<Ipv4Addr>().ok())
        .filter(|ip| crate::verify::google_ipv4(*ip))
        .collect();
    crate::to_java(&mut env,
        &scan(handle as u64,max.max(32) as usize, workers.max(4) as usize,
            sample_count.max(4) as usize,&confirmed,mode).to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeGoogleScanProgress(
    mut env: JNIEnv<'_>, _class: JClass<'_>,
) -> jstring {
    let text = json!({
        "running":RUNNING.load(Ordering::Relaxed),
        "checked":CHECKED.load(Ordering::Relaxed),
        "good":GOOD.load(Ordering::Relaxed),
        "total":TOTAL.load(Ordering::Relaxed),
    }).to_string();
    crate::to_java(&mut env, &text)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeCancelGoogleScan(
    _env: JNIEnv<'_>, _class: JClass<'_>,
) {
    CANCELLED.store(true, Ordering::SeqCst);
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn all_scan_candidates_are_real_google_range() {
        let list = candidates(512, 123456, &[]);
        assert_eq!(list.len(), 512);
        // No hardcoded "previously verified" IP should be preferred.
        let mut seen = HashSet::new();
        for ip in list {
            assert!(crate::verify::google_ipv4(ip));
            assert!(seen.insert(ip));
        }
    }
    #[test]
    fn scan_never_creates_unbounded_targets() {
        assert_eq!(candidates(128, 5, &[]).len(), 128);
    }
    #[test]
    fn seeds_only_user_confirmed_candidates_and_deduplicates() {
        let known: Ipv4Addr = "142.250.27.100".parse().unwrap();
        let untrusted: Ipv4Addr = "1.1.1.1".parse().unwrap();
        let list = candidates(64, 456, &[known,known,untrusted]);
        assert_eq!(list[0],known);
        assert_eq!(list.len(),64);
        assert_eq!(list.iter().filter(|ip| **ip==known).count(),1);
        assert!(!list.contains(&untrusted));
    }
    #[test]
    fn benchmark_targets_diversify_while_respecting_budget() {
        let values: Vec<Endpoint> = ["142.250.0.1","142.250.0.2",
                "142.250.1.1","142.250.2.1","142.250.3.1"]
            .iter().enumerate().map(|(i,s)|Endpoint{
                ip:s.parse().unwrap(),latency_ms:10+i as u128,
                throughput_kib_s:0.0,benchmark_bytes:0,
            }).collect();
        let sampled=benchmark_targets(&values,4);
        assert_eq!(sampled.len(),4);
        assert_eq!(sampled[0].ip,values[0].ip);
        assert_eq!(sampled[1].ip,values[1].ip);
        assert_eq!(sampled[2].ip,values[2].ip);
    }
}
