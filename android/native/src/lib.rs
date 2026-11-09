//! Android JNI bootstrap that reuses gdirect's original network-interface code.
//! Deliberately does not pretend to provide the aria2 download engine yet.

#[path = "../../../src/netif.rs"]
mod netif;
#[path = "../../../src/store.rs"]
mod store;

use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
mod android_network;
mod verify;
mod transfer;
use jni::JNIEnv;
use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::time::{Duration, Instant};

fn to_java(env: &mut JNIEnv<'_>, text: &str) -> jstring {
    env.new_string(text)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn network_report() -> String {
    "Android 网络接口由 ConnectivityManager 提供。请在上层选择具体网络，不再将 Linux /proc/net/route 的空结果误报为无物理网络。".into()
}

fn probe(addr: SocketAddr, network_handle: u64) -> Result<String, String> {
    let start = Instant::now();
    android_network::connect(addr, network_handle, Duration::from_secs(3))
        .map_err(|e| format!("{addr} → TCP 连接失败：{e}"))?;
    Ok(format!("{addr} → TCP 443 可连接（{} ms）", start.elapsed().as_millis()))
}

fn google_probe() -> String {
    // DNS tests the system-resolved endpoint, not gdirect's IP scanning algorithm.
    // Do not mistake a successful TCP handshake for Google API or TLS verification.
    let addresses = match ("www.googleapis.com", 443).to_socket_addrs() {
        Ok(addrs) => addrs.take(8).collect::<Vec<_>>(),
        Err(e) => return format!("系统 DNS 解析失败：{e}"),
    };
    if addresses.is_empty() {
        return "DNS 没有返回任何地址".into();
    }
    let mut errors = Vec::new();
    for addr in addresses {
        match probe(addr, 0) {
            Ok(message) => return format!("{message}\n尚未验证 HTTPS、Drive API 或下载。"),
            Err(error) => errors.push(error),
        }
    }
    format!("未能连接已解析的 Google IP：\n{}", errors.join("\n"))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeVersion(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    to_java(
        &mut env,
        concat!("Rust JNI 已加载 · gdirect4android ", env!("CARGO_PKG_VERSION")),
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeNetworkStatus(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    to_java(&mut env, &network_report())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeProbeGoogle(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    to_java(&mut env, &google_probe())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeProbeIp(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input: JString<'_>,
    network_handle: jlong,
) -> jstring {
    let value: String = match env.get_string(&input) {
        Ok(value) => value.into(),
        Err(e) => return to_java(&mut env, &format!("读取输入失败：{e}")),
    };
    let value = value.trim();
    let result = match value.parse::<IpAddr>() {
        Ok(ip) => probe(SocketAddr::new(ip, 443), network_handle as u64)
            .unwrap_or_else(|err| err),
        Err(_) => "无效的 IPv4 / IPv6 地址。".into(),
    };
    to_java(&mut env, &format!("{result}\n仅 TCP 检测，不代表 Google Drive 可用。"))
}


/// TLS certificate, hostname and Google Drive API challenge for a literal IP.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeVerifyGoogleIp(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input: JString<'_>,
    network_handle: jlong,
) -> jstring {
    let value: String = match env.get_string(&input) {
        Ok(value) => value.into(),
        Err(err) => return to_java(&mut env, &format!("输入错误：{err}")),
    };
    to_java(&mut env, &verify::inspect(value.trim(), network_handle as u64))
}

/// Fixed small-sample Google frontend sweep; not the upstream exhaustive scan.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeScanGoogle(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    network_handle: jlong,
) -> jstring {
    to_java(&mut env, &verify::scan(network_handle as u64))
}


/// Fetch a bounded public Google API document through the selected Android
/// network. No filesystem write, secrets or Google account access.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeTransferSample(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    network_handle: jlong,
) -> jstring {
    to_java(&mut env, &transfer::run(network_handle as u64))
}
