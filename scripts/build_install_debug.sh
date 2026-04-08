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

SDK_DIR=""
if [[ -f "$ROOT/local.properties" ]]; then
  SDK_DIR="$(grep -E '^sdk\\.dir=' "$ROOT/local.properties" | head -n1 | cut -d= -f2-)"
  SDK_DIR="${SDK_DIR//\\:/:}"
fi

if [[ -z "$SDK_DIR" || ! -d "$SDK_DIR" ]]; then
  echo "Android SDK not found. Check $ROOT/local.properties (sdk.dir=...)." >&2
  exit 1
fi

ADB="$SDK_DIR/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then
  echo "adb not found at: $ADB" >&2
  echo "Install platform-tools in Android SDK Manager and try again." >&2
  exit 1
fi

cd "$ROOT"
./gradlew :app:assembleDebug

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  echo "Debug APK not found at: $APK" >&2
  exit 1
fi

echo "Installing debug APK..."
"$ADB" install -r "$APK"
