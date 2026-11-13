# gdirect for Android

**Material 3 Google Drive client · native patched libaria2 · Android Network selection**

The first 1.0 release candidate brings the previously verified direct Google
IP/TLS download engine into an everyday Android interface.

See [User guide](USER_GUIDE.md) for onboarding, download-service limitations,
customizable settings, privacy and signing. The historical milestone notes
remain in Git history.

## Features

- Google Drive full-permission Desktop OAuth (import your own OAuth JSON,
  external browser PKCE and encrypted refresh tokens).
- Browse/search Drive, create folders, rename and move to trash.
- Native 8-way by default, configurable 1–16 connection libaria2 downloads
  into Android SAF documents, with file size and MD5 validation when available.
- 32–768 Google IP candidates, up to 64 simultaneous verifications,
  TLS/Drive challenge, short-file sampling, preferred routes and failover.
- Material 3 dynamic color, dark/light/system theme, tabs, motion,
  background download notifications, cancellation and local task history.
- Advanced diagnostics accessible through settings rather than shown upfront.

## Build

```bash
# Android SDK API 35, NDK 27.2.12479018, JDK 17, Gradle 8.13,
# Rust aarch64-linux-android and pinned libaria2 archives required.
bash android/native/build-aria2-android.sh
# Then build Rust JNI and Gradle app per .github/workflows/android.yml
```

Official CI builds are permanently signed using the original user-owned key.
Never push signing keys. If Actions Secrets are unavailable, unsigned-for-
distribution debug validation builds are not published as user updates.

**Status: 1.0.0-rc4-exp1 (experimental branch).** Download-task resilience, live device tests and
Workspace export need further validation before a final general release.

## RC2 interaction refinements

- A single Settings destination in the bottom navigation: removed redundant toolbar gear.
- Material Fade Through replaces abrupt single-page Y-slide animation for tab switches.
- Navigation pills now use the native Material 3 active indicator, subtle low-opacity ripple, and stateful icon/label colors.
- Settings remains selected after light/dark or dynamic-color change, retaining scroll location.
- Theme change uses an outgoing tonal veil and incoming content easing; no screenshots or credentials are cached for the effect.
- Developer tools are reparented into an expandable section of the same Settings ScrollView, not a nested ScrollView with fixed height. Its results stay visible locally without jumping to Routes.
- Android's animator-disabled accessibility setting overrides decorative motion.

## RC3 — Durable transfer queue and portable user settings

- Separate files are managed as durable SQLite tasks: queued, running, paused,
  failed, cancelled or completed. Multiple pending jobs are processed FIFO.
  A queued job can be moved to the front or paused; active jobs can be
  paused/cancelled. One native aria2 **file** runs at a time; each file uses
  1–16 concurrent HTTP connections, configurable in Settings.
- Incomplete `id.bin` and `id.bin.aria2` pieces are stored in the
  app's PRIVATE files directory, never in the disposable cache. aria2
  resumes via saved bitfield/HTTP Range. Changing selected Google IP does not
  destroy the local pieces. Completed/cancelled jobs release partial files;
  pause/failure preserves them.
- A process kill marks an interrupted job Paused the next time the app opens.
  It is **not** automatically restarted at boot. Press Resume after selecting
  an available network; freshly refreshed OAuth access tokens are obtained
  using the existing Android Keystore-protected refresh credential. Access
  tokens are NEVER written to the queue, Intent extras or transfer history.
- ACTION_CREATE_DOCUMENT destinations request persistable SAF write access;
  a provider that denies persistable grants may require selecting a new
  output file after reboot. Failed copy may leave a partial target document.
- Settings → Config portability uses Android SAF import/export and a
  versioned, validated JSON whitelist of non-sensitive app preferences.
  OAuth metadata/secrets, refresh tokens, signing keys, Drive account info,
  task database, partial files and ephemeral Android Network handles are
  intentionally **not included**. Import validates all fields first.
- No guarantee of resume across a changed remote file or server rejecting
  Range requests; integrity checks still validate Drive size and MD5 where
  provided. Google Workspace file export remains future work.

### exp2: Transfer UI consistency and adaptive IP telemetry

The experimental branch now renders a **single** SQLite task list, refreshes
bytes and progress in place, and deletes completed/cancelled records directly
with explicit feedback. Turbo keeps aria2's existing piece allocator and
HTTP connection reuse, tracks per-IP TLS download bytes, and uses a cautious
speed/load-based heuristic for *new* connections after warm-up. No custom
overlapping Range writes, preemptive chunk theft, or multi-file concurrency.
