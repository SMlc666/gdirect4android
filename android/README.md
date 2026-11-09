# gdirect for Android — milestone 1

This is the **first buildable Android bootstrap**, not a completed Google Drive
client. It builds a real `arm64-v8a` APK containing a Rust JNI library.

## What works in milestone 1

- Android application with a network diagnostics screen.
- Rust JNI called from Android on a background thread.
- Reuse of the upstream `src/netif.rs` and `src/store.rs` (same source files,
  not copied implementations).
- Inspect Rust's view of the physical / VPN interfaces.
- Test TCP port 443 on a Google DNS address or a manually entered IP.
- GitHub Actions builds the Android Rust library and a debug-signed APK.

**Not yet implemented:** Google OAuth, Drive listing, libaria2, resumable
downloads, Android-native `Network.bindSocket` and a proper VPN bypass.
A successful TCP test proves only TCP reachability, not TLS/API/download support.

## CI build and install

1. Push to a branch or open a PR with changes under `android/`; GitHub Actions
   runs **Android APK**.
2. From the workflow run's **Artifacts**, download
   `gdirect4android-arm64-debug-apk`.
3. Unzip the artifact and install `app-debug.apk` on a 64-bit Android phone.
   The APK uses the default *debug* key; do not distribute it as a trusted
   release build.

CI uses Java 17, Android Gradle Plugin 8.13.2, Gradle 8.13, Android API 35,
NDK 27.2.12479018, and the Rust `aarch64-linux-android` target.

## Rust source layout

`android/native/src/lib.rs` imports the original Rust sources by relative
path, avoiding an early cross-compiled libaria2/OpenSSL dependency. This is
temporary: once the engine is split into platform-neutral crates, depend on
those crates directly. The desktop and CLI builds remain unchanged.

## Next milestone

1. Implement Android connectivity binding via the Android `Network` APIs:
   VPN, Wi-Fi and mobile data must be tested separately.
2. Add HTTPS validation of scanned Google endpoints with the original GTS
   trust anchors.
3. NDK-build the pinned patched libaria2, and integrate real download tasks.
4. Integrate native Android OAuth and safely protected credentials.
5. Add Drive browsing, storage access framework, foreground download service,
   notification controls, pause/resume and integrity verification.
