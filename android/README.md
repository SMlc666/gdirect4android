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



## v0.9.1-alpha.1 — optional desktop OAuth + full Google Drive scope

**This section supersedes the previous native Android OAuth login setup.**
The opt-in credential type is now **Desktop app**. Import the JSON downloaded
from Google Cloud Console (a file containing an \`installed\` object) via the
Android document picker, or paste its JSON into the configuration field.
Neither the desktop client ID/secret nor tokens are shipped in the APK.

- OAuth permission requested: \`https://www.googleapis.com/auth/drive\`,
  which grants **full access** to files in the authorized Google Drive
  account, including reading, creating, editing and deleting them. This is a
  **restricted** scope. The UI currently exposes browse, search, download,
  create folder, rename and move to trash; full OAuth permission does not
  mean all Drive API methods are already implemented in this UI.
- Opens the **external default browser**, NEVER an embedded WebView.
- OAuth Desktop-client loopback redirect binds only \`127.0.0.1\` with a random
  port, PKCE S256, cryptographic CSRF \`state\`, 3-minute deadline and bounded
  callback request size. No manual OOB copy/paste flow, no custom URI scheme.
- The authorization code is exchanged via \`https://oauth2.googleapis.com/token\`;
  refresh tokens and imported desktop-client credentials are encrypted with
  Android Keystore AES-256-GCM and private preferences. Access tokens stay
  only in RAM and expire/refresh as needed; revoke clears the local token
  even if the remote revoke endpoint is unavailable. No token logs.
- Authorization/browser/Drive JSON API calls follow **system default
  network**, potentially a VPN. The original aria2 downloads continue to
  use the explicit Android-selected network handle.
- Android/Desktop loopback is **experimental and NOT Google's recommended
  Android authorization integration**. Google officially keeps loopback
  supported for OAuth Desktop *client types*, but recommends native Google
  Identity Services on Android. If the browser rejects this configuration,
  switch to the native Android client flow instead of weakening PKCE/state.
- A registered desktop OAuth app requesting the restricted \`drive\` scope may
  trigger an unverified app warning, testing-user restrictions or further
  verification for public distribution.

### Setup

1. Enable Drive API and create/configure the OAuth consent screen in Google Cloud.
   If status is Testing, add the personal Google account as a test user.
2. Create a new OAuth client type **Desktop app**, download its JSON.
3. Install the alpha APK. In the new Drive section, tap **从本机文件导入
   Desktop OAuth JSON**, then **使用外部浏览器授权完整 Drive 权限**.
4. On return to the app, browse root folders and try a small binary download
   with libaria2. Long-press entries to rename or move to trash.
5. No package-name/SHA-1 registration is required for this desktop OAuth client
   variant, unlike the native Android client in previous alpha builds.

Sources:
- https://developers.google.com/identity/protocols/oauth2/native-app
- https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration
- https://developers.google.com/workspace/drive/api/guides/api-specific-auth


## Permanent APK signing for seamless updates (v0.9.1-alpha.2+)

**This supersedes the old ephemeral GitHub runner Debug signature.**
Android requires the *same certificate/private key* for an installed APK to
be updated in place. Reprinting an old SHA-1 is NOT enough. The keystore
must be generated **once** on your own trusted machine and kept private.

The repository intentionally contains NO keystore or signing passwords.
The GitHub connector currently cannot write Actions Secrets for you, so
the owner must perform this **one-time** setup with GitHub CLI:

```bash
gh auth login
bash android/scripts/setup-stable-signing.sh
```

Requirements: `gh` (GitHub CLI), Java JDK `keytool`, OpenSSL and coreutils.
In Termux, install `gh`, `openssl`, `openjdk-17`, `coreutils` if absent.

The setup script creates and reuses (not rotates):

- Private PKCS12 keystore:
  `~/.config/gdirect4android/signing/gdirect4android-stable.p12`
- Private password file:
  `~/.config/gdirect4android/signing/store-password`

It uploads **four encrypted GitHub Actions Secrets**:

- `GDIRECT_ANDROID_KEYSTORE_B64`
- `GDIRECT_ANDROID_STORE_PASSWORD`
- `GDIRECT_ANDROID_KEY_ALIAS`
- `GDIRECT_ANDROID_KEY_PASSWORD`

And one *public* certificate fingerprint repository variable:
`GDIRECT_ANDROID_SIGNING_SHA256`.

The workflow:
1. For trusted **push** and **workflow_dispatch** runs, restores the key from
   Secrets and signs the Debug APK with the **permanent** certificate.
2. Verifies the actual APK signing certificate SHA-256 against the pinned
   repository variable and **refuses artifact publication on mismatch**.
3. Uploads artifact `gdirect4android-arm64-stable-signed-apk` only when
   secret signing is configured and the certificate check passed.
4. PR workflows and unconfigured runners still compile and test an ephemeral
   debug APK, but **do not upload it as a compatible Android update**.

**Backup both private files securely.** Never add them to source code, public
GitHub Actions artifacts, chat uploads, or publicly shared cloud folders.
If the files are lost *and* the repository secrets become inaccessible, a
replacement key will not update the installed app in place.

**One unavoidable transition:** Earlier alpha builds used ephemeral Android
Debug keys. Their certs are not preserved in this repository. Moving to the
first permanent signed APK therefore normally requires uninstalling the old
package and reinstalling, losing its local app preferences/tokens unless
backed up. **After** installing the first permanent-signed build, future
versions from the same key can update without a reinstall.

For users of Desktop OAuth, this change is independent of Desktop Client ID
configuration; the signer's SHA-1 is no longer needed for OAuth client
registration, but it remains essential to Android APK update compatibility.
