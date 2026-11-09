//! Android JNI bootstrap that reuses gdirect's original network-interface code.
//! Deliberately does not pretend to provide the aria2 download engine yet.

#[path = "../../../src/netif.rs"]
mod netif;
#[path = "../../../src/store.rs"]
mod store;

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::time::{Duration, Instant};

fn to_java(env: &mut JNIEnv<'_>, text: &str) -> jstring {
    env.new_string(text)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn network_report() -> String {
    let status = netif::status();
    let physical = status
        .physical
        .as_ref()
        .map(|i| format!("{} (index {})", i.name, i.index))
        .unwrap_or_else(|| "未检测到".into());
    let tun = status.tun.as_deref().unwrap_or("未检测到");
    let route = status.path().unwrap_or_else(|| "未知".into());
    let bind = if !status.enabled {
        "已禁用".to_string()
    } else if let Some(reason) = status.denied.as_ref() {
        format!("绑定失败：{reason}")
    } else if status.bound().is_some() {
        "绑定测试已通过（仍需实际网络验证）".to_string()
    } else {
        "没有可绑定的物理网卡".to_string()
    };
    format!(
        "gdirect Rust 网络状态\n物理网卡：{physical}\n虚拟网卡：{tun}\n路径：{route}\n绕开 TUN：{bind}\n\nAndroid 的网络路由与 Linux 不完全相同；这里不能替代 Android Network API 的测试。"
    )
}

fn probe(addr: SocketAddr) -> Result<String, String> {
    let start = Instant::now();
    netif::connect(&addr, Duration::from_secs(3))
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
        match probe(addr) {
            Ok(message) => return format!("{message}\n尚未验证 HTTPS、Drive API 或下载。"),
            Err(error) => errors.push(error),
        }
    }
    format!("未能连接已解析的 Google IP：\n{}", errors.join("\n"))
}

#[no_mangle]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeVersion(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    to_java(
        &mut env,
        concat!("Rust JNI 已加载 · gdirect4android ", env!("CARGO_PKG_VERSION")),
    )
}

#[no_mangle]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeNetworkStatus(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    to_java(&mut env, &network_report())
}

#[no_mangle]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeProbeGoogle(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    to_java(&mut env, &google_probe())
}

#[no_mangle]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeProbeIp(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input: JString<'_>,
) -> jstring {
    let value: String = match env.get_string(&input) {
        Ok(value) => value.into(),
        Err(e) => return to_java(&mut env, &format!("读取输入失败：{e}")),
    };
    let value = value.trim();
    let result = match value.parse::<IpAddr>() {
        Ok(ip) => probe(SocketAddr::new(ip, 443))
            .unwrap_or_else(|err| err),
        Err(_) => "无效的 IPv4 / IPv6 地址。".into(),
    };
    to_java(&mut env, &format!("{result}\n仅 TCP 检测，不代表 Google Drive 可用。"))
}
