#!/usr/bin/env bash
# Run once on your own trusted computer/Termux environment.
# Generates a reusable private Android signing key and uploads its encrypted
# bytes to GitHub Actions Secrets. It NEVER prints private key or password.
set -euo pipefail
umask 077

REPO="${GDIRECT_REPO:-SMlc666/gdirect4android}"
DIR="${XDG_CONFIG_HOME:-$HOME/.config}/gdirect4android/signing"
KEYSTORE="$DIR/gdirect4android-stable.p12"
PASSWORD_FILE="$DIR/store-password"
ALIAS="gdirect4android"

for tool in keytool gh openssl base64; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing dependency: $tool (install a JDK, GitHub CLI, OpenSSL and coreutils)" >&2
    exit 1
  fi
done
if ! gh auth status >/dev/null 2>&1; then
  echo "Authenticate first using: gh auth login" >&2
  exit 1
fi

mkdir -p "$DIR"
chmod 700 "$DIR"
if [[ -f "$KEYSTORE" && -f "$PASSWORD_FILE" ]]; then
  echo "Existing permanent signing key found; reusing it without rotation."
elif [[ -e "$KEYSTORE" || -e "$PASSWORD_FILE" ]]; then
  echo "Key or password file missing; refusing to rotate a partial installation." >&2
  echo "Recover both from your private backup." >&2
  exit 1
else
  PASSWORD="$(openssl rand -hex 32)"
  printf '%s' "$PASSWORD" > "$PASSWORD_FILE"
  chmod 600 "$PASSWORD_FILE"
  keytool -genkeypair -noprompt \
    -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
    -keyalg RSA -keysize 3072 -validity 12000 \
    -dname "CN=GDirect Android Stable Key, OU=Personal, O=GDirect, C=US" \
    -storepass "$PASSWORD" -keypass "$PASSWORD"
  chmod 600 "$KEYSTORE"
fi
PASSWORD="$(cat "$PASSWORD_FILE")"
if [[ ${#PASSWORD} -lt 16 ]]; then
  echo "Password unexpectedly short; refusing to upload." >&2
  exit 1
fi
keytool -list -keystore "$KEYSTORE" -storetype PKCS12 \
  -alias "$ALIAS" -storepass "$PASSWORD" >/dev/null
FINGERPRINT="$(keytool -list -v -keystore "$KEYSTORE" -storetype PKCS12 \
  -alias "$ALIAS" -storepass "$PASSWORD" |
  sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p' |
  head -n1 | tr -d ':' | tr '[:upper:]' '[:lower:]')"
if [[ ! "$FINGERPRINT" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Cannot identify certificate SHA-256." >&2
  exit 1
fi

echo "Uploading signing key to GitHub Secrets for $REPO"
base64 < "$KEYSTORE" | tr -d '\r\n' |
  gh secret set GDIRECT_ANDROID_KEYSTORE_B64 --repo "$REPO"
printf '%s' "$PASSWORD" |
  gh secret set GDIRECT_ANDROID_STORE_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS" |
  gh secret set GDIRECT_ANDROID_KEY_ALIAS --repo "$REPO"
printf '%s' "$PASSWORD" |
  gh secret set GDIRECT_ANDROID_KEY_PASSWORD --repo "$REPO"
gh variable set GDIRECT_ANDROID_SIGNING_SHA256 --repo "$REPO" --body "$FINGERPRINT"

echo "Stable signing configured. Certificate SHA-256: $FINGERPRINT"
echo "Private keystore: $KEYSTORE"
echo "Private password: $PASSWORD_FILE"
echo "BACK UP both private files somewhere offline. Do not upload them into"
echo "the repository, a public artifact, a shared folder, or this chat."
echo "Re-running this script safely reuses the same key."
