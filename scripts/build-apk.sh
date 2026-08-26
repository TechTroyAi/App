#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

./gradlew --no-daemon assembleDebug

SOURCE_APK="app/build/outputs/apk/debug/app-debug.apk"
OUTPUT_APK="artifacts/Blockhold-Defense-v1.0-debug.apk"

if [[ ! -f "$SOURCE_APK" ]]; then
  echo "Build completed, but $SOURCE_APK was not found." >&2
  exit 1
fi

mkdir -p artifacts
cp "$SOURCE_APK" "$OUTPUT_APK"

echo
echo "APK ready: $OUTPUT_APK"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$OUTPUT_APK"
fi
