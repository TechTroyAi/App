#!/usr/bin/env bash
# Bootstrap JDK 17 + Android SDK for a Gradle rebuild of Blockhold Defense.
#
# This sandbox (checked 2026-08-28) cannot complete TLS to:
#   deb.debian.org, dl.google.com, repo1.maven.org, services.gradle.org
# so a full assembleRelease is not possible here. Signing does not need those
# hosts — use scripts/sign-apk.py (Python + OpenSSL) instead.
#
# On a normal machine (Android Studio, GitHub Actions, or a laptop with
# working Maven/Google access):
#   ./scripts/setup-build-env.sh
#   ./scripts/build-apk.sh release

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

need_java() {
  if command -v java >/dev/null 2>&1; then
    java -version 2>&1 | head -1
    return 1
  fi
  return 0
}

echo "==> JDK"
if need_java; then
  if command -v apt-get >/dev/null 2>&1; then
    echo "    trying apt openjdk-17-jdk-headless"
    sudo apt-get update && sudo apt-get install -y openjdk-17-jdk-headless || {
      echo "    apt failed (this sandbox blocks Debian mirrors)."
      echo "    Install Temurin 17 from https://adoptium.net and re-run."
      exit 1
    }
  else
    echo "error: no JDK and no apt. Install JDK 17, then re-run." >&2
    exit 1
  fi
fi

JAVA_VER="$(java -version 2>&1 | head -1)"
echo "    $JAVA_VER"

echo "==> Android SDK"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  echo "    ANDROID_HOME is not set to a cmdline-tools install."
  echo "    Download commandlinetools-linux from"
  echo "      https://developer.android.com/studio#command-line-tools-only"
  echo "    and unpack so that $SDK/cmdline-tools/latest/bin/sdkmanager exists."
  echo
  echo "    Then:"
  echo "      export ANDROID_HOME=$SDK"
  echo "      yes | \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses"
  echo "      \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \\"
  echo "        'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'"
  echo "      echo sdk.dir=$SDK > local.properties"
  exit 1
fi

echo "sdk.dir=$SDK" > local.properties
echo "    wrote local.properties"

echo "==> Kotlin pitfall lint"
python3 scripts/lint-kotlin-pitfalls.py

echo
echo "Ready. Build with:"
echo "  ./scripts/build-apk.sh release     # uses .signing/ if present"
echo "  ./scripts/build-apk.sh debug"
echo
echo "If you only need to sign the existing unsigned v1.2 APK:"
echo "  python3 scripts/sign-apk.py"
