#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_HOME_DEFAULT="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"

if [[ ! -d "$JAVA_HOME" ]]; then
  echo "JAVA_HOME not found at: $JAVA_HOME" >&2
  echo "Set JAVA_HOME to a valid JDK (Android Studio's JBR is recommended)." >&2
  exit 1
fi

KEYSTORE_PROPS="$ROOT/keystore.properties"
if [[ ! -f "$KEYSTORE_PROPS" ]]; then
  echo "Missing $KEYSTORE_PROPS. Release signing will fail." >&2
  exit 1
fi

missing_key=false
for key in storeFile storePassword keyAlias keyPassword; do
  if ! grep -q "^${key}=" "$KEYSTORE_PROPS"; then
    echo "Missing '${key}' in keystore.properties." >&2
    missing_key=true
  fi
done
if [[ "$missing_key" == "true" ]]; then
  exit 1
fi

STORE_FILE="$(grep -E '^storeFile=' "$KEYSTORE_PROPS" | head -n1 | cut -d= -f2-)"
if [[ -z "$STORE_FILE" ]]; then
  echo "storeFile is empty in keystore.properties." >&2
  exit 1
fi
if [[ "$STORE_FILE" != /* ]]; then
  STORE_FILE="$ROOT/$STORE_FILE"
fi
if [[ ! -f "$STORE_FILE" ]]; then
  echo "Keystore file not found at: $STORE_FILE" >&2
  exit 1
fi

VERSION_NAME="$(grep -E 'versionName[[:space:]]*=' "$ROOT/app/build.gradle.kts" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/' || true)"
VERSION_CODE="$(grep -E 'versionCode[[:space:]]*=' "$ROOT/app/build.gradle.kts" | head -n1 | sed -E 's/.*=[[:space:]]*([0-9]+).*/\1/' || true)"
if [[ -z "$VERSION_NAME" ]]; then
  VERSION_NAME="unknown"
fi
if [[ -z "$VERSION_CODE" ]]; then
  VERSION_CODE="0"
fi

cd "$ROOT"
# Separate runs: per-CPU APK splits and the App Bundle can't be built in one Gradle invocation.
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease

APK_DIR="$ROOT/app/build/outputs/apk/release"
AAB_SRC="$ROOT/app/build/outputs/bundle/release/app-release.aab"
shopt -s nullglob
APKS=("$APK_DIR"/app-*-release.apk)
if [[ ${#APKS[@]} -eq 0 ]]; then
  echo "No release APKs found in: $APK_DIR" >&2
  exit 1
fi
if [[ ! -f "$AAB_SRC" ]]; then
  echo "Release AAB not found at: $AAB_SRC" >&2
  exit 1
fi

DIST_DIR="$ROOT/dist"
mkdir -p "$DIST_DIR"
AAB_DEST="$DIST_DIR/babeltrout-v${VERSION_NAME}-c${VERSION_CODE}-release.aab"
cp "$AAB_SRC" "$AAB_DEST"

echo "Release artifacts ready:"
for apk in "${APKS[@]}"; do
  abi="$(basename "$apk" | sed -E 's/^app-(.*)-release\.apk$/\1/')"
  dest="$DIST_DIR/babeltrout-v${VERSION_NAME}-c${VERSION_CODE}-${abi}.apk"
  cp "$apk" "$dest"
  echo "  $dest"
done
echo "  $AAB_DEST"
echo "Most phones need the arm64-v8a APK; armeabi-v7a is for older 32-bit phones."
