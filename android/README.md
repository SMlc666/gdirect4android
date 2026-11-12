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

**Status: 1.0.0-rc1.** Download-task resilience, live device tests and
Workspace export need further validation before a final general release.
