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
        network_handle: u64,
    ) -> i32;
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

fn run(id: &str, token: &str, ca: &str, dir: &str, name: &str, network: u64) -> String {
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
    const IPS: [&str; 3] = ["142.250.27.100", "108.177.9.138", "64.233.160.100"];
    for ip in IPS {
        if CANCEL.load(Ordering::Relaxed) {
            return "ERR\n用户取消 libaria2 下载".into();
        }
        // Delete incomplete state before trying a different Google endpoint.
        // Per-file resume/restart journal will be integrated with download jobs.
        let _ = fs::remove_file(&output);
        let _ = fs::remove_file(path.join(format!("{name}.aria2")));
        let arguments = (
            CString::new(url.as_str()), CString::new(path.to_string_lossy().as_bytes()),
            CString::new(name), CString::new(ip),
            CString::new(token), CString::new(ca),
        );
        let (Ok(url), Ok(dir), Ok(name), Ok(ip), Ok(token), Ok(ca)) = arguments else {
            return "ERR\n路径或 OAuth 令牌含无效字节".into();
        };
        #[cfg(target_os = "android")]
        let result = unsafe {
            gdirect_download_ex(
                url.as_ptr(), dir.as_ptr(), name.as_ptr(), ip.as_ptr(),
                std::ptr::null(), token.as_ptr(), ca.as_ptr(), 8,
                Some(progress), std::ptr::null_mut(), false, 0, network,
            )
        };
        #[cfg(not(target_os = "android"))]
        let result = -100;
        if result == 0 {
            let len = fs::metadata(&output).map(|m| m.len()).unwrap_or_default();
            if len > 0 {
                return format!(
                    "OK\n原版 libaria2 1.37.0 下载成功\n实际 IP：{}\n实际文件：{len} 字节\n设定：8 条 HTTP 分段连接",
                    ip.to_string_lossy()
                );
            }
        }
        failures.push(format!("{}：libaria2 状态码 {result}", ip.to_string_lossy()));
    }
    format!("ERR\nlibaria2 下载失败（3 个 Google IP 均未成功）：\n{}", failures.join("\n"))
}

fn arg(env: &mut JNIEnv<'_>, input: JString<'_>) -> Result<String, String> {
    env.get_string(&input).map(|s| s.into()).map_err(|e| e.to_string())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeDownloadDrive(
    mut env: JNIEnv<'_>, _class: JClass<'_>,
    id: JString<'_>, token: JString<'_>, ca: JString<'_>,
    dir: JString<'_>, name: JString<'_>, network: jlong,
) -> jstring {
    let args = (|| Ok::<_, String>((
        arg(&mut env, id)?, arg(&mut env, token)?,
        arg(&mut env, ca)?, arg(&mut env, dir)?, arg(&mut env, name)?,
    )))();
    let report = match args {
        Ok((id, token, ca, dir, name)) => run(&id, &token, &ca, &dir, &name, network as u64),
        Err(e) => format!("ERR\nJNI 参数读取失败：{e}"),
    };
    crate::to_java(&mut env, &report)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_smlc_gdirectandroid_MainActivity_nativeDownloadProgress(
    mut env: JNIEnv<'_>, _class: JClass<'_>,
) -> jstring {
    crate::to_java(&mut env, &format!(
        "下载：{}/{} 字节 · {} KiB/s · {} 连接",
        DONE.load(Ordering::Relaxed),
        TOTAL.load(Ordering::Relaxed),
        SPEED.load(Ordering::Relaxed) / 1024,
        CONNECTIONS.load(Ordering::Relaxed),
    ))
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
    fn rejects_path_traversal_and_invalid_id() {
        assert!(valid_id("abc_123-"));
        assert!(!valid_id("../a"));
        assert!(valid_name("download.bin"));
        assert!(!valid_name("../../etc/passwd"));
    }
}
