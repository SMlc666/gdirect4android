#!/usr/bin/env bash
# Build pinned libaria2 1.37.0 + OpenSSL 3.5.9 for ARM64 Android NDK.
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT="$PWD"
test -n "$ANDROID_HOME"
NDK="$ANDROID_HOME/ndk/27.2.12479018"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export PATH="$BIN:$PATH"
export ANDROID_NDK_HOME="$NDK" ANDROID_NDK_ROOT="$NDK"
export CC="$BIN/aarch64-linux-android26-clang"
export CXX="$BIN/aarch64-linux-android26-clang++"
export AR="$BIN/llvm-ar" RANLIB="$BIN/llvm-ranlib"
OUT="$ROOT/android/native/target"
SSL="$OUT/android-openssl"
ARIA="$OUT/android-aria2"
SRC="$OUT/vendor"
mkdir -p "$SRC" "$SSL" "$ARIA"
VER=3.5.9
TAR="$SRC/openssl-$VER.tar.gz"
URL="https://github.com/openssl/openssl/releases/download/openssl-$VER/openssl-$VER.tar.gz"
if [ ! -s "$TAR" ]; then curl -fLsS --retry 3 "$URL" -o "$TAR"; fi
curl -fLsS --retry 3 "$URL.sha256" -o "$TAR.sha256"
HASH="$(grep -Eo '[0-9a-fA-F]{64}' "$TAR.sha256" | head -n 1)"
test -n "$HASH"
printf '%s  %s\n' "$HASH" "$TAR" | sha256sum -c -
if [ ! -s "$SSL/lib/libssl.a" ]; then
  tar -xzf "$TAR" -C "$SRC"
  cd "$SRC/openssl-$VER"
  ./Configure android-arm64 -D__ANDROID_API__=26 \
    no-shared no-tests no-module no-legacy no-weak-ssl-ciphers \
    --libdir=lib --prefix="$SSL" --openssldir="$SSL/ssl"
  make -j4 build_libs
  make install_sw
fi
cd "$ROOT"
ARIA_TAR="$SRC/aria2-1.37.0.tar.xz"
if [ ! -s "$ARIA_TAR" ]; then
  for SRCURL in \
    "https://github.com/aria2/aria2/releases/download/release-1.37.0/aria2-1.37.0.tar.xz" \
    "https://src.fedoraproject.org/repo/pkgs/aria2/aria2-1.37.0.tar.xz/sha512/df3b8b4de8fa8d78f203ea00c059e43585e18a229009f202e42e6a9e59db67d09df0dbba8a016e99ed73c82f59e4f8b26f86c2288afdbb96a6807cbe2c56e6b3/aria2-1.37.0.tar.xz"; do
    if curl -fLsS --retry 3 "$SRCURL" -o "$ARIA_TAR.part"; then
      mv "$ARIA_TAR.part" "$ARIA_TAR"
      break
    fi
  done
fi
printf '%s  %s\n' 60a420ad7085eb616cb6e2bdf0a7206d68ff3d37fb5a956dc44242eb2f79b66b "$ARIA_TAR" | sha256sum -c -
rm -rf "$SRC/aria2-1.37.0"
tar -xf "$ARIA_TAR" -C "$SRC"
cd "$SRC/aria2-1.37.0"
patch -p1 < "$ROOT/vendor/aria2-1.37.0.patch"
cp "$ROOT/vendor/gdirect_route.h" src/
cp "$ROOT/native/bridge.cc" android-bridge.cc.original
python3 "$ROOT/android/native/patch_aria2_android.py"
export OPENSSL_CFLAGS="-I$SSL/include"
export OPENSSL_LIBS="-L$SSL/lib -lssl -lcrypto"
export am_cv_func_iconv=no ac_cv_header_iconv_h=no
./configure \
  --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
  --prefix="$ARIA" --enable-libaria2 --enable-static --disable-shared \
  --with-openssl --without-wintls --without-appletls --without-gnutls \
  --without-libnettle --without-libgmp --without-libgcrypt \
  --without-libcares --without-libssh2 --without-sqlite3 \
  --without-libxml2 --without-libexpat --without-libz \
  --disable-bittorrent --disable-metalink --disable-websocket \
  --disable-nls --disable-dependency-tracking \
  CPPFLAGS="-I$SSL/include" LDFLAGS="-L$SSL/lib"
make -j4 -C src libaria2.la
make -C src install-libLTLIBRARIES
mkdir -p "$ARIA/include/aria2"
cp src/includes/aria2/aria2.h "$ARIA/include/aria2/"
cp android-bridge.cc "$ROOT/android/native/src/aria2_android_bridge.cc"
test -s "$ARIA/lib/libaria2.a"
test -s "$SSL/lib/libssl.a"
test -s "$SSL/lib/libcrypto.a"
echo "Android ARM64 libaria2 and OpenSSL archives built"
