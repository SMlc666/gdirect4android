# gdirect for Android 1.0 RC

A user-focused Material 3 Google Drive client that preserves the original
gdirect/libaria2 downloader, verified Google TLS/SNI and explicitly bound
Android Network selection.

## First launch

1. Open **文件** and expand **账号设置与权限管理**. Import the Desktop
   OAuth `installed` JSON created in your own Google Cloud project.
2. Authorize **full Drive scope** in the external browser. The desktop OAuth
   loopback scheme on Android is experimental and may not work for all browsers.
3. Open **线路**; choose Wi-Fi, cellular, VPN, or intentionally the default.
4. Run an opt-in Google frontend scan; use latency or sample throughput
   ranking, or manually prioritize an authenticated Google frontend.
5. Return to **文件**; browse, search, create/rename/trash folders or select
   a file and choose a destination through the Android document picker.
6. Follow the task in **下载**, or the foreground-service notification.
   You can switch pages while native libaria2 is busy.

## Modern interface

- Material 3 color and shape system with wallpaper-derived Dynamic Color on
  Android 12+, system/light/dark themes, responsive bottom navigation and
  animated page transitions (can be disabled).
- Separate destinations: Files, Transfers, Routes, Settings.
- Per-user choices: scanning volume (32–768 IP), concurrency (4–64), original
  libaria2 connections (1–16), endpoint cache TTL (5–240 min), alternate IPs,
  haptic feedback, motion, theme, and opening download queue after selection.
- Network diagnostics remain available under **设置 → 高级诊断** rather than
  overloading the main experience.

## Download lifecycle and limitations

- Uses an Android `dataSync` foreground service so downloads do not stop
  just because the Activity is recreated or the user switches tabs.
- Notification provides ongoing status and cancellation; the task history
  stores at most 30 non-sensitive records locally.
- Native aria2 reads the Google Drive media endpoint on the chosen Android
  Network; the app verifies the reported Drive file length and MD5 (when
  present) before copying into the selected Storage Access Framework URI.
- This RC permits **one active file download** at a time. It is not yet a
  persistent queue, does not resume across process death/device restart, and
  does not include Google Workspace export. Interrupted final SAF copies may
  leave a partial destination document.
- Android 15+ limits `dataSync` foreground service time to six hours
  cumulatively in 24 hours while the app is backgrounded. The app handles
  Android 15 timeout; a long-running transfer may need to be restarted.
- No OAuth token or private client secret is stored in transfer history,
  notification or logs. Desktop OAuth refresh tokens and imported client
  credentials remain protected by Android Keystore.

## Network trust

- The selected Android Network is applied via `android_setsocknetwork`
  before aria2's TCP connects. Drive metadata uses the same pinned-IP pool,
  checked against bundled Google address ranges and verified with TLS SNI
  `www.googleapis.com`; no certificate bypass or default VPN fallback.
- Desktop OAuth external browser and OAuth token exchange/refresh intentionally
  use the *system default network*, which can be a VPN.
- IP selection is time-bounded: a Google frontend that worked previously can
  stop responding; try another verified IP when that happens.
- The scanner is opt-in and can open at most 64 concurrent sockets.

## Build and signing

GitHub Actions cross-compiles Android ARM64 NDK OpenSSL, original patched
libaria2 1.37.0 and the Rust JNI layer, runs Rust unit tests, assembles the
Material 3 Java UI, verifies the bundled C++ ABI, then uses the original
fixed-signing keystore injected via GitHub Secrets. CI refuses to distribute
an APK unless its SHA-256 signing certificate matches the pinned repository
fingerprint. Never commit or share private signing keys.

**Version status**: v1.0.0-rc3 is a testable release candidate, not yet a
production-stability claim. Real-device validation of Material 3 theme, SAF,
foreground service, background cancellation, route changes and OAuth remains
required before a final 1.0 label.

## RC2 motion, navigation and diagnostics

Material 3 bottom navigation has **one** Settings entry. The top toolbar
contains branding only. Switching peer destinations uses Material Fade Through
rather than abruptly hiding the old view and sliding the new view up.

The selected tab and the scroll positions are preserved across Activity
recreation, including manual day/night and dynamic-color changes. With
animations enabled, the old theme dissolves into a tonal surface veil and
the new palette eases into place. When Android accessibility preferences
turn off animator scale, the app switches instantly instead.

Settings → Advanced Diagnostics expands *within the same scrollable page*.
TCP/IP, DNS, TLS, public Google Drive tests and libaria2 status tools use
ordinary Material cards and display results in that panel. There is no
fixed-height nested scroll view that traps touch gestures.

## RC3: download manager and resuming

The Downloads tab now lists **every pending and recent file job**. Add several
files from Drive; they queue and download sequentially through the existing
single libaria2 engine. You can move a waiting job to the front, pause a
queued or running job, cancel it, or explicitly resume an interrupted job.
The number of HTTP connections **within one file** is configurable (1–16);
this is not yet simultaneous multi-file transfers.

Task metadata is saved in a private SQLite database. Each job has a stable
UUID and durable `filesDir/transfers/<uuid>.bin` and `<uuid>.bin.aria2`
files. The latter is aria2's progress/bitfield journal, saved every 2 seconds
and when the session stops. Pausing and changing IP must not delete it.

After a process death or device restart, open the app, select an available
network, then tap **继续** for an interrupted task (or **继续全部**). We
intentionally do not auto-start a forbidden foreground service from boot.
The app fetches a fresh OAuth access token via the already encrypted refresh
token. A Testing OAuth refresh token may expire; reauthorize if prompted.

SAF saves are verified for file size/MD5 when provided, but not every
Android document provider supports persistent URI grants. An interrupted
copy can leave a partial destination document. If it cannot be reopened,
create a new destination using the file picker.

## RC3: export and import settings

Settings → **配置备份与迁移** has **导出设置 JSON** and **从 JSON 导入设置**.
Files are created/read using Android's Storage Access Framework. The JSON
contains only theme, color, animation, haptics, scanning and download
preferences with a stable schema version. The importer validates values
before applying settings. Tokens, OAuth client secrets, Google account data,
Google Drive content, task database/partial downloads, APK keystore and
ephemeral Network handles are excluded deliberately. Reconfigure authorization
on a new device. Never confuse a settings export with a full app backup.
