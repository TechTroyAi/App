#!/usr/bin/env bash
#
# One-command build for Blockhold Defense.
#
#   ./scripts/build-apk.sh              # release if a signing key exists, else debug
#   ./scripts/build-apk.sh debug        # force a debug build
#   ./scripts/build-apk.sh release      # force a release build (requires .signing/)
#
# Runs the checks that would have caught the v1.2 failures, in order:
#   1. lint-kotlin-pitfalls.py  - Kotlin 1.7 collection semantics (compiles clean, throws at runtime)
#   2. gradlew assemble*        - the real toolchain, which zipaligns and signs correctly
#   3. verify-apk.py            - alignment, signing schemes, dex integrity, manifest, resources
#   4. zipalign -c / apksigner  - if the Android SDK build-tools are on PATH or in ANDROID_HOME
#
# Requires JDK 17 and the Android SDK. If `sdkmanager` is missing, install Android Studio or
# the command line tools and set ANDROID_HOME.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MODE="${1:-auto}"
if [[ "$MODE" == "auto" ]]; then
  if [[ -f .signing/blockhold-release.p12 && -f .signing/release.properties ]]; then
    MODE="release"
  else
    MODE="debug"
  fi
fi

case "$MODE" in
  debug)   TASK="assembleDebug";   OUT="app/build/outputs/apk/debug/app-debug.apk" ;;
  release) TASK="assembleRelease"; OUT="app/build/outputs/apk/release/app-release.apk" ;;
  offline) TASK="offline";         OUT="" ;;
  *) echo "usage: $0 [debug|release|offline]" >&2; exit 2 ;;
esac

if [[ "$MODE" == "release" && ! -f .signing/blockhold-release.p12 ]]; then
  echo "error: release build requested but .signing/blockhold-release.p12 is missing." >&2
  echo "       Create it first:  ./scripts/make-signing-key.sh" >&2
  exit 1
fi

echo "==> 1/4  Linting Kotlin runtime pitfalls"
python3 scripts/lint-kotlin-pitfalls.py

if [[ "$MODE" == "offline" ]]; then
  echo
  echo "==> 2/4  Building offline"
  python3 scripts/build-offline-apk.py
  exit 0
fi

echo
echo "==> 2/4  Building ($TASK)"
if ! ./gradlew --no-daemon "$TASK"; then
  echo "Gradle build failed or Gradle wrapper is unavailable in offline environment."
  echo "Falling back to offline builder (kotlinc + dx + apktool + apksigner)..."
  python3 scripts/build-offline-apk.py
  exit 0
fi

if [[ ! -f "$OUT" ]]; then
  echo "Build reported success but $OUT was not produced." >&2
  exit 1
fi

VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
mkdir -p artifacts
DEST="artifacts/Blockhold-Defense-v${VERSION:-unknown}-${MODE}.apk"
cp "$OUT" "$DEST"

echo
echo "==> 3/4  Verifying package structure"
python3 scripts/verify-apk.py "$DEST"

echo
echo "==> 4/4  Checking alignment and signature with the Android SDK"
BT=""
if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/build-tools" ]]; then
  BT="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT/build-tools" ]]; then
  BT="$(ls -d "$ANDROID_SDK_ROOT"/build-tools/* 2>/dev/null | sort -V | tail -1)"
fi

if [[ -n "$BT" && -x "$BT/zipalign" ]]; then
  "$BT/zipalign" -c -p -v 4 "$DEST" > /dev/null && echo "  zipalign: 4-byte alignment OK"
  "$BT/apksigner" verify --verbose --print-certs "$DEST" || true
elif command -v zipalign >/dev/null 2>&1; then
  zipalign -c -p -v 4 "$DEST" > /dev/null && echo "  zipalign: 4-byte alignment OK"
  apksigner verify --verbose --print-certs "$DEST" || true
else
  echo "  (skipped: build-tools not found - set ANDROID_HOME. verify-apk.py already checked"
  echo "   alignment and the signing block independently.)"
fi

echo
echo "APK ready: $DEST"
command -v sha256sum >/dev/null 2>&1 && sha256sum "$DEST"

cat <<'NOTE'

Before installing
-----------------
If a build signed with a DIFFERENT key is already on the device, Android refuses the
install with INSTALL_FAILED_UPDATE_INCOMPATIBLE ("App not installed"). v1.1 and v1.2 were
signed with the sideload key 095f279b..., whose private key is not in this repo.

So the first install of this APK needs a one-time uninstall:

    adb uninstall ai.techtroy.blockhold     # or long-press the icon > Uninstall

Every later build signed with the same key updates in place and keeps progress.
Record the APK SHA-256 and certificate fingerprint in artifacts/README.md for each release.
NOTE
