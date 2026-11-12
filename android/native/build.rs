use std::{env, path::PathBuf};
fn main() {
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let os = env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    println!("cargo:rerun-if-changed=src/aria2_android_bridge.cc");
    if os != "android" { return; }
    let aria = manifest.join("target/android-aria2");
    let ssl = manifest.join("target/android-openssl");
    assert!(
        aria.join("lib/libaria2.a").is_file() && ssl.join("lib/libssl.a").is_file(),
        "Run android/native/build-aria2-android.sh before Android cargo build"
    );
    cc::Build::new()
        .cpp(true)
        .std("c++11")
        .file(manifest.join("src/aria2_android_bridge.cc"))
        .include(aria.join("include"))
        .include(manifest.join("../../vendor"))
        .cpp_link_stdlib("c++_shared")
        .compile("gdirect_bridge_android");
    println!("cargo:rustc-link-search=native={}", aria.join("lib").display());
    println!("cargo:rustc-link-search=native={}", ssl.join("lib").display());
    println!("cargo:rustc-link-lib=static=aria2");
    println!("cargo:rustc-link-lib=static=ssl");
    println!("cargo:rustc-link-lib=static=crypto");
    println!("cargo:rustc-link-lib=android");
    println!("cargo:rustc-link-lib=log");
    // Supply the NDK libc++ exception / RTTI runtime at the JNI link step.
    println!("cargo:rustc-link-lib=dylib=c++_shared");
}
