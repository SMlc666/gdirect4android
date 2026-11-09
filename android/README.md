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


## Milestone 2: Android per-network routing diagnostics

- Android ConnectivityManager now lists VPN, Wi-Fi, cellular and other networks with interface and DNS details.
- Selecting a network passes its Network.getNetworkHandle() to Rust; android_setsocknetwork() binds the native TCP socket before connect.
- Tests Cloudflare 1.1.1.1:443 as a reference, plus up to two IPv4 and two IPv6 addresses returned by system or selected-network DNS for www.googleapis.com.
- A refused bind never silently falls back to VPN. The system-default option deliberately remains unbound.
- VPNs must enable allowBypass; Android lockdown may prohibit direct traffic. A TCP success does not confirm TLS, Google Drive API, or actual downloads.
- This is still a diagnostic APK, not a downloader; the libaria2 NDK port is the next development step.


## Milestone 3 — authenticated Google TLS diagnostics

- Uses the upstream gdirect Google Trust Services roots in
  \`src/gts-roots.pem\`, full rustls chain and SNI hostname validation.
- Sends an unauthenticated HTTPS GET to \`/drive/v3/about?fields=user\`.
  HTTP 401/403 is the expected authentication challenge; that does **not**
  mean user login or Drive downloads work.
- The UI can verify a network-specific DNS IP, a manually entered IP, or
  scan exactly sixteen embedded Google frontend candidates with four workers.
  Each connection is bound to the selected Android Network before TCP connect.
- 198.18.0.0/15 Fake-IP is explicitly flagged and never counted as genuine.
- A fallback SNI \`g.cn\` is attempted only after a TLS failure, with its own
  hostname check, mirroring the upstream gdirect routing strategy.
- **No TLS verification is disabled**. Scanner traffic is user initiated and
  bounded; it never performs an unbounded Internet-wide sweep.
- Even a valid TLS+HTTP response over the system default or VPN network does
  not establish *direct* physical-network reachability. Test physical network
  selection and VPN-disabled scenarios separately.
- Still no OAuth, Drive listing, libaria2, downloads, upload, or foreground
  download service. A complete app requires all of those components.



## Milestone 4 — bounded real Google HTTPS payload transfer

- From the real-device user's v0.3 scan, test \`142.250.27.100\`,
  \`108.177.9.138\` and \`64.233.160.100\` independently.
- Connect through the selected Android Network, validate GTS TLS and SNI
  \`www.googleapis.com\`, fetch the public Drive REST Discovery document
  at \`https://www.googleapis.com/discovery/v1/apis/drive/v3/rest\`.
- Require HTTP 200 and actual response bytes; report sample size and rough
  throughput including TCP/TLS setup time. A bounded 1 MiB per IP is read
  into small in-memory chunks and immediately discarded.
- Sequential test, maximum three IPs, no background work. This doesn't
  require OAuth or touch private files. It does not yet write a downloaded
  file or use libaria2. Avoid interpreting these short-sample rates as
  sustained multi-GiB download performance.
- CI remains GitHub Actions, unchanged. To continue, integrate real Android
  native Network bindings into libaria2's patched socket layer, cross-compile
  libaria2 with NDK/OpenSSL, and add authenticated Google Drive downloads.



## Milestone 5 — HTTP framing and TLS close_notify correctness

- The Android v0.4 transfer test falsely failed on all three real Google endpoints
  when Google closed TCP without a TLS close_notify. The code incorrectly read
  to connection closure even when HTTP already supplied an explicit body length.
- v0.5 tracks Content-Length and Transfer-Encoding: chunked; it stops as soon
  as the HTTP message is framed completely. A 1 MiB capped sample is labeled
  as partial rather than a completed document.
- Unexpected TLS EOF is *not* globally ignored. Incomplete length-delimited,
  incomplete chunked, or uncleanly closed close-delimited responses remain
  failures, with any observed HTTP status and body bytes reported.
- The test reports content type, framing, actual payload bytes, and total
  TCP+TLS+HTTP elapsed time. It does not save any data.
- The Android Actions workflow now runs the native Rust unit tests on Linux
  before cross-compiling the arm64-v8a JNI library.
- The stand-alone host unit test build has an Android-only NDK binding gate:
  Linux tests do not attempt to link libandroid. Android builds continue using
  android_setsocknetwork before TCP connect.


## Milestone 6 — actual Google HTTPS file saved to Android Documents

- The user selects Wi-Fi/VPN/another available Android Network and taps
  **真实保存文件**. Android ACTION_CREATE_DOCUMENT opens the SAF picker and
  lets the user select where the JSON file is stored. No broad storage
  permissions or Android root access are needed.
- Rust downloads Google's public Drive REST Discovery JSON via verified GTS
  TLS / SNI and the selected Android Network, trying three previously
  verified Google frontends in order on failures. No login or secret token.
- A Rust streaming HTTP/1.1 reader decodes Content-Length/chunked into an
  app-private temporary file and checks response completeness, HTTP 200,
  content-type, max 8 MiB and SHA-256. It rejects truncated bodies,
  error pages, gzip or ambiguous framing.
- A JNI-passed ParcelFileDescriptor is duplicated in Rust to avoid closing a
  file owned by Java. Rust resets the temporary file between IP retries.
- After a **complete and verified** download, Java copies it into the
  selected SAF URI, then deletes temporary data. Incomplete network results
  are never copied. If a provider fails during the last copy, the target
  may be partial and the app explicitly reports this.
- This is an end-to-end real file save but **not** authenticated Google Drive
  files, foreground downloads, resume, multi-connection libaria2 or OAuth.
- CI builds the arm64 APK and tests the Rust stream decoder on Linux.



## Combined milestone 0.8 + 0.9 — actual Drive + patched libaria2

- Google Identity Services native Android Authorization API
  (\`play-services-auth:22.0.0\`) requests Drive **read-only** permission.
  No OAuth client secret or refresh/access token stored in the APK/database.
  Token only lives in application memory and is refreshed by Google Play services.
- Drive v3 files.list: root folders, subfolders, name search and pagination.
  Non-Workspace Drive files can be selected and saved via Android SAF.
- **Google Cloud setup required** for real user sign-in: enable Drive API,
  configure OAuth consent/test users, and create an **Android OAuth client**
  with package \`com.smlc.gdirectandroid\` and the exact SHA-1 from the APK
  signing certificate. CI's ephemeral debug signing key changes between builds;
  configure stable release signing before reliable reuse. Drive readonly is a
  restricted OAuth scope (Google verification may be required to publish).
- Native patched aria2 1.37.0 is NDK-cross-compiled with static OpenSSL 3.5.9.
  The existing upstream patch is re-applied; Android adaptation calls
  \`android_setsocknetwork\` inside **aria2 SocketCore**, before connect, and
  fails if an explicit network bind is denied.
- Rust JNI calls original C++ \`gdirect_download_ex\`, using 8 connections,
  progress counters and cancel callback. Temporary files are stored in private
  cache and copied to the user-selected SAF URI after successful download;
  when available, Drive's md5Checksum and size are verified.
- Auth/file listing uses Google Play services / Android HTTPS on the default
  network, which may go through VPN. The pinned media-download sockets use
  the chosen Android Network handle. Login cannot be forced to use an IP by
  altering the Android socket hook.
- Google Workspace documents require an export path, which is not yet wired
  into this media-download action. Foreground service, persistent pause/resume,
  and production signing also remain to be completed.
- Google OAuth client registration and Android on-device testing cannot be
  performed by CI alone. Use the GitHub Actions artifact and follow up with
  actual-device results.



### OAuth Android client setup for testing the integrated Drive browser

1. In Google Cloud Console, create a project, enable **Google Drive API**.
2. Set up the OAuth consent screen and add your Google Account under test users
   if the application is still in Testing.
3. Create OAuth client type **Android**, package
   **com.smlc.gdirectandroid**. Register the SHA-1 shown by the **actual APK
   signing certificate** printed in this workflow's *Display actual APK
   signing SHA-1* job step, not a random Android Studio development key.
4. Install the APK built by that exact CI run and tap **授权只读访问 Drive**.
   It uses Google Play services AuthorizationClient and holds the short-lived
   access token only in RAM. Google Play services and a Google account must
   be present on the phone.
5. Because CI creates a different debug signing certificate across fresh
   runners, repeat registration for a different APK until stable release
   signing with GitHub Actions secrets is configured.
6. The requested \`drive.readonly\` scope is restricted. Personal testing with
   test users is different from publishing; public availability may require
   restricted-scope verification. The app never embeds a client secret.

