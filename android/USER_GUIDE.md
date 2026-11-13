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
- Notification provides ongoing status and cancellation. Up to 500
  non-sensitive task records are stored in SQLite; the download list renders
  40 cards at a time with a Load more control.
- Native aria2 reads the Google Drive media endpoint on the chosen Android
  Network; the app verifies the reported Drive file length and MD5 (when
  present) before copying into the selected Storage Access Framework URI.
- This RC permits **one active file download** at a time, with other files in
  a persistent queue. SQLite jobs and aria2 checkpoints survive process death
  or device restart, but resuming requires an explicit action after reopening.
  Google Workspace export is not supported yet. Interrupted final SAF copies
  may leave a partial destination document.
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

**Version status**: v1.0.0-rc4-exp3.1 is a testable release candidate, not yet a
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

## RC4 experiment: Multi-IP Turbo (opt-in)

Enable **设置 → 性能与扫描 → Multi-IP Turbo · 实验性** to distribute new
TCP connections for a **single file** across up to four separately verified
Google frontend IPs. This does **not** increase the 1–16 total connection
budget or download several files simultaneously. The feature is **off by
default**; the mode is recorded on each queued job and persists across restarts.
Changing the toggle does not retroactively change existing queued downloads.

The IPv4 pool is filtered by the existing Google address allowlist. The
original HTTPS hostname, SNI, certificate validation, OAuth token and chosen
Android Network remain unchanged. The patched aria2 SocketCore chooses
the next IP only when resolving a Google destination for a new TCP connection.
The single session owns the standard aria2 partial-file journal and HTTP
Range resume logic; no parallel process or competing writer touches the file.

If the initial pooled download fails, the native worker falls back to
single-IP attempts in priority order using the same partial file and
checkpoint. This is an experimental routing strategy, not a guaranteed
increase in speed: if the carrier backbone is the shared bottleneck, changing
frontend IPs may give no benefit. Test with a sufficiently large file:
same network, time window and **total** connections, comparing Turbo off
vs on, and verify file MD5 afterward.

## RC4-exp2: single source of truth and adaptive connections

The downloads page contains **one** durable SQLite-backed task list; there
is no second volatile current-download card. Running task text/bytes/progress
update in place without rebuilding the entire list. Terminal records are
deleted directly from the database in a background operation, with explicit
success/error feedback. Completed files in the user's document destination
are never deleted by a record deletion, and queued/running files cannot be
deleted using the terminal-record action.

When Multi-IP Turbo is enabled, each new TCP connection is initially spread
across the verified IP pool. Once a minimum set of connections has been
sampled, gdirect measures **actual TLS application bytes** per endpoint and
selects subsequent *new* connections by smoothed bandwidth divided by open
socket load. Active HTTP keepalive sockets and aria2's built-in piece
allocation/idle piece reassignment are left intact. The UI displays
approximate live per-IP KiB/s, open socket count and received KiB. This is a
**conservative work-allocation heuristic**, NOT preemptive theft of a range
actively written by another connection. We deliberately do not attempt
unsafe concurrent writes, manual Range overlap or forced cancellation of
active pieces; completion still requires the original size/MD5 check.

### exp2 safe connection-level work migration

After three consecutive sampled windows with a materially slower endpoint,
and only after receiving several MiB on that route, the native HTTP keepalive
pool declines to reuse a finished connection (55% relative performance
threshold). **The currently active HTTP Range is never interrupted.** aria2
keeps the checkpoint, assigns unclaimed segments using its own piece manager,
and opens a replacement TCP socket through the adaptive IP policy when needed.
This provides bounded connection-level work migration and tail opportunities,
not a risky preemptive in-flight segment takeover. Because carrier paths may
share the same bottleneck, a speed improvement is not guaranteed.

## RC4-exp3: verified-only, durable and customizable route selection

The three previously bundled "verified" download fallback IPs and all fixed
native scan/diagnostic seed lists are removed. On a fresh installation, **no
Google IP is treated as verified**. Scan the selected Android Network first.
Verified winners are committed to on-device SharedPreferences and survive
process restarts; their history remains readable after expiration, but ONLY
fresh checks from the **same active Network handle** may be used for a new
download or passed to native HTTPS diagnostics. Network handles can change on
reboot; a new network therefore requires a new scan. No OAuth token or secret
is saved or exported with the endpoint history.

The routes page has exactly one inline scan-progress meter, with a cancel
button visible only while scanning; there is no redundant "view progress"
button. Preset modes Quick (128/16/4), Standard (384/32/12) and Deep
(768/64/24) are mutually exclusive with Custom. The numbers represent
candidate IP count / parallel TLS probes / genuine throughput benchmarks.
Custom exposes all three bounded controls (32–768 / 4–64 / 4–32). The selected
mode and parameters persist across restarts and are included in JSON export.
Schema 1 configuration files remain importable; new exports are schema 2.

Sorting offers latency, measured speed and a configurable weighted heuristic
with a penalty for low-confidence throughput samples. The download fallback
pool also supports an adjustable subnet diversity preference and manual pin.
Without real measurements, an endpoint is explicitly marked "未测速": it is
not invented a speed value. New scanning retains only up to eight fresh
same-network prior verified candidates as seeds; the other candidates are
drawn across actual Google non-Cloud network ranges. Benchmarks deliberately
mix fastest-TLS candidates with distinct /24 prefixes to avoid only sampling
one edge subnet. None of these scoring heuristics guarantees improvement over
a shared backbone bottleneck, and all target addresses are independently
checked against Google's IPv4 allowlist in Rust.

### RC4-exp3.1: actual socket-dial Multi-IP fix

Real-device issue: 8 active HTTP connections were reported for the first
verified Google frontend and 0 for a second configured peer. The original
Android SocketCore hook rotated IP addresses during name resolution, but
aria2's InitiateConnectionCommand resolves the hostname first and passes
a **numeric IP** to SocketCore::establishConnection. The numeric address
bypassed that hostname-only check, so Multi-IP enabled on the UI did not
guarantee multiple frontends were actually used.

The fix selects the frontend at the **actual direct Google HTTPS socket
dial**, with the original verified Google Request hostname still available.
Proxy connections and non-Google hosts are untouched. HTTP SNI and Host
remain unchanged; the same Android Network pin, Google IPv4 allowlist, TLS
certificate checks, HTTP Range journal and aria2 piece allocator apply.
Pooled keepalive sockets are looked up by their actual remote IP across the
whole verified pool, not just the canonical resolved address; a separate
cursor avoids accidentally biasing fresh dials. The diagnostic line now
shows per-IP actual read throughput, active sockets and cumulative routing
selections. This gives users a way to distinguish a configured secondary
IP from one actually selected for connections.

The host C++ policy test reproduces 8 fresh dials with a numeric resolved
Google IP and verifies balanced initial allocation across two peers. CI
build/link/sign validation is **not** a substitute for live network testing:
a remote frontend can still refuse connections, which must be observable.
Never interpret multiple configured IPs alone as proof of Multi-IP routing.
