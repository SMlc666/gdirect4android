//! Native JNI bindings to the original patched libaria2 1.37.0 C++ bridge.
//! The libaria2 socket hook binds each pinned TCP socket to the specified
//! Android Network handle before connecting. No proxy fallback on bind failure.
use jni::{objects::{JClass, JString}, sys::{jlong, jstring}, JNIEnv};
use std::{
    ffi::{CString, c_char, c_void},
    fs,
    path::Path,
    sync::{Mutex, atomic::{AtomicBool, AtomicU64, AtomicI32, Ordering}},
};

static SESSION: Mutex<()> = Mutex::new(());
static CANCEL: AtomicBool = AtomicBool::new(false);
static DONE: AtomicU64 = AtomicU64::new(0);
static TOTAL: AtomicU64 = AtomicU64::new(0);
static SPEED: AtomicI32 = AtomicI32::new(0);
static CONNECTIONS: AtomicI32 = AtomicI32::new(0);

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn gdirect_download_ex(
        url: *const c_char, dir: *const c_char, name: *const c_char,
        ip: *const c_char, sni: *const c_char, token: *const c_char,
        ca: *const c_char, connections: i32,
        callback: Option<extern "C" fn(i64, i64, i32, i32, *mut c_void) -> i32>,
        data: *mut c_void, signals: bool, ifindex: u32,
        network_handle: u64, ip_pool: *const c_char,
    ) -> i32;
}

#[cfg(target_os = "android")]
unsafe extern "C" {
    fn gdirect_android_ip_stats(out: *mut c_char, capacity: usize) -> usize;
}

/// Last sampled per-IP TLS application data / open socket count. Neither
/// Google OAuth tokens nor filenames are carried across this diagnostic.
fn route_status() -> String {
    #[cfg(target_os = "android")]
    {
        let mut buffer = [0 as c_char; 1024];
        let length = unsafe {
            gdirect_android_ip_stats(buffer.as_mut_ptr(), buffer.len())
        }.min(buffer.len().saturating_sub(1));
        let bytes: Vec<u8> = buffer[..length].iter().map(|b| *b as u8).collect();
        return String::from_utf8_lossy(&bytes).into_owned();
    }
    #[cfg(not(target_os = "android"))]
    String::new()
}

extern "C" fn progress(
    done: i64, total: i64, speed: i32, conns: i32, _data: *mut c_void,
) -> i32 {
    DONE.store(done.max(0) as u64, Ordering::Relaxed);
    TOTAL.store(total.max(0) as u64, Ordering::Relaxed);
    SPEED.store(speed.max(0), Ordering::Relaxed);
    CONNECTIONS.store(conns, Ordering::Relaxed);
    i32::from(CANCEL.load(Ordering::Relaxed))
}

fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 256 &&
        id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}
fn valid_name(name: &str) -> bool {
    !name.is_empty() && name.len() <= 96 && name != "." && name != ".." &&
    !name.contains("..") &&
    name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_' || b == b'.')
}

/// Distribute a single aria2 session's connections on the first attempt.
fn make_attempts(ips: &[String], connections: i32, multi_ip: bool)
    -> Vec<(String, String)>
{
    let mut result = Vec::new();
    let count = ips.len().min(4).min(connections.clamp(1, 16) as usize);
    if multi_ip && count >= 2 {
        result.push((ips[0].clone(), ips[..count].join(",")));
    }
    result.extend(ips.iter().map(|ip| (ip.clone(), String::new())));
    result
}

fn run(id: &str, token: &str, ca: &str, dir: &str, name: &str,
       network: u64, route_ips: &str, connections: i32, multi_ip: bool) -> String {
    if !valid_id(id) || !valid_name(name) {
        return "ERR\n文件 ID 或临时文件名非法".into();
    }
    if token.is_empty() || token.len() > 8192 ||
        !token.bytes().all(|b| b.is_ascii_graphic())
    {
        return "ERR\n无有效 Google OAuth access token".into();
    }
    let path = Path::new(dir);
    if !path.is_absolute() || !path.is_dir() || !Path::new(ca).is_file() {
        return "ERR\n应用私有目录或 Google CA 根证书文件无效".into();
    }
    let _guard = match SESSION.try_lock() {
        Ok(value) => value,
        Err(_) => return "ERR\n已有 libaria2 下载任务在执行".into(),
    };
    CANCEL.store(false, Ordering::SeqCst);
    DONE.store(0, Ordering::SeqCst);
    TOTAL.store(0, Ordering::SeqCst);
    SPEED.store(0, Ordering::SeqCst);
    let url = format!("https://www.googleapis.com/drive/v3/files/{id}?alt=media&supportsAllDrives=true");
    let output = path.join(name);
    let mut failures = Vec::new();
    let mut ips = Vec::new();
    let mut seen = std::collections::HashSet::new();
    for item in route_ips.split(',').take(16) {
        let Ok(ip) = item.trim().parse::<std::net::Ipv4Addr>() else { continue; };
        if crate::verify::google_ipv4(ip) && seen.insert(ip) {
            ips.push(ip.to_string());
        }
        if ips.len() >= 8 { break; }
    }
    if ips.is_empty() { return "ERR\n没有可用的优选 Google IP".into(); }
    for (ip, ip_pool) in make_attempts(&ips, connections, multi_ip) {
        if CANCEL.load(Ordering::Relaxed) {
            return "ERR\n用户取消 libaria2 下载".into();
        }
        // Persistent transfer queue: NEVER erase the partial payload or
        // aria2 bitfield before failover. Reopen the same output+control file
        // with HTTP Range requests. Old data is retained after cancellation
        // and Android process death, in app-private filesDir.
        //
        // A separate explicit task deletion is responsible for cleanup.
        let arguments = (
            CString::new(url.as_str()), CString::new(path.to_string_lossy().as_bytes()),
            CString::new(name), CString::new(ip.as_str()),
            CString::new(token), CString::new(ca), CString::new(ip_pool.as_str()),
        );
        let (Ok(url), Ok(dir), Ok(name), Ok(ip), Ok(token), Ok(ca), Ok(pool)) = arguments else {
            return "ERR\n路径或 OAuth 令牌含无效字节".into();
        };
        #[cfg(target_os = "android")]
        let result = unsafe {
            gdirect_download_ex(
                url.as_ptr(), dir.as_ptr(), name.as_ptr(), ip.as_ptr(),
                std::ptr::null(), token.as_ptr(), ca.as_ptr(), connections.clamp(1, 16),
                Some(progress), std::ptr::null_mut(), false, 0, network, pool.as_ptr(),
            )
        };
        #[cfg(not(target_os = "android"))]
        let result = { let _ = &pool; -100 };
        if result == 0 {
            let len = fs::metadata(&output).map(|m| m.len()).unwrap_or_default();
            if len > 0 {
                return format!(
                    "OK\n原版 libaria2 1.37.0 下载成功\n前端路由：{}\n实际文件：{len} 字节\n设定：{} 条 HTTP 分段连接",
                    if ip_pool.is_empty() { ip.to_string_lossy().to_string() }
                    else { format!("Multi-IP ({})", ip_pool) },
                    connections.clamp(1,16)
                );
            }
        }
        failures.push(format!("{}：libaria2 状态码 {result}", ip.to_string_lossy()));
    }
    format!("ERR\nlibaria2 下载失败（{} 个优选 IP 均未成功）：\n{}", ips.len(), failures.join("\n"))
}

fn arg(env: &mut JNIEnv<'_>, input: JString<'_>) -> Result<String, String> {
    env.get_string(&input).map(|s| s.into()).map_err(|e| e.to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeDownloadDrive(
    mut env: JNIEnv<'_>, _class: JClass<'_>,
    id: JString<'_>, token: JString<'_>, ca: JString<'_>,
    dir: JString<'_>, name: JString<'_>, network: jlong, ips: JString<'_>, connections: jni::sys::jint,
    multi_ip: jni::sys::jboolean,
) -> jstring {
    let args = (|| Ok::<_, String>((
        arg(&mut env, id)?, arg(&mut env, token)?,
        arg(&mut env, ca)?, arg(&mut env, dir)?, arg(&mut env, name)?,
        arg(&mut env, ips)?,
    )))();
    let report = match args {
        Ok((id, token, ca, dir, name, ips)) =>
            run(&id, &token, &ca, &dir, &name, network as u64, &ips, connections, multi_ip != 0),
        Err(e) => format!("ERR\nJNI 参数读取失败：{e}"),
    };
    crate::to_java(&mut env, &report)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeDownloadProgress(
    mut env: JNIEnv<'_>, _class: JClass<'_>,
) -> jstring {
    let mut report = format!(
        "下载：{}/{} 字节 · {} KiB/s · {} 连接",
        DONE.load(Ordering::Relaxed),
        TOTAL.load(Ordering::Relaxed),
        SPEED.load(Ordering::Relaxed) / 1024,
        CONNECTIONS.load(Ordering::Relaxed),
    );
    let routes = route_status();
    if !routes.is_empty() {
        report.push_str("\n各 IP：");
        report.push_str(&routes);
    }
    crate::to_java(&mut env, &report)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeCancelDownload(
    _env: JNIEnv<'_>, _class: JClass<'_>,
) {
    CANCEL.store(true, Ordering::SeqCst);
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn multi_ip_uses_one_session_and_keeps_single_ip_fallbacks() {
        let ips = vec!["142.250.0.1".into(), "142.250.0.2".into(),
            "142.250.0.3".into(), "142.250.0.4".into()];
        let modes = make_attempts(&ips, 3, true);
        assert_eq!(modes[0], (ips[0].clone(), ips[0..3].join(",")));
        assert_eq!(modes.len(), ips.len()+1);
        assert!(modes[1..].iter().all(|(_, pool)| pool.is_empty()));
        assert_eq!(make_attempts(&ips, 1, true).len(), ips.len());
        assert_eq!(make_attempts(&ips, 16, false).len(), ips.len());
    }

    #[test]
    fn rejects_path_traversal_and_invalid_id() {
        assert!(valid_id("abc_123-"));
        assert!(!valid_id("../a"));
        assert!(valid_name("download.bin"));
        assert!(!valid_name("../../etc/passwd"));
    }
}
