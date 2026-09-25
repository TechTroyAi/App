#!/usr/bin/env bash
# Provisions the Android toolchain ClockCanvas was built with, for sandboxes that
# have *no* JDK, no Android SDK and no access to Google's Maven repo (which is how
# this app was authored). Everything comes from PyPI / npm / the GitHub contents
# API, all of which are usually reachable when dl.google.com is not.
#
# Idempotent: run it again after a sandbox reset, it only fills in what is missing.
set -u

TOOLS="${CC_TOOLS:-$HOME/.cc-tools}"
mkdir -p "$TOOLS"
export PATH="$TOOLS/node/bin:$PATH"

say() { printf '\033[1;35m==>\033[0m %s\n' "$*"; }

# ---------------------------------------------------------------- Python side
if [ ! -x "$TOOLS/venv/bin/python" ]; then
  say "creating venv (jdk4py + aapt2 + pillow)"
  python3 -m venv "$TOOLS/venv" >/dev/null 2>&1 || { echo "python3 -m venv failed"; exit 1; }
  "$TOOLS/venv/bin/pip" install -q --disable-pip-version-check jdk4py==21.0.8.2 aapt2 pillow \
    || { echo "pip install failed (is pypi.org reachable?)"; exit 1; }
fi
JAVA_BIN="$("$TOOLS/venv/bin/python" -c 'import jdk4py;print(jdk4py.JAVA)' 2>/dev/null)"
if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
  say "reinstalling jdk4py"
  "$TOOLS/venv/bin/pip" install -q --disable-pip-version-check --force-reinstall jdk4py==21.0.8.2
  JAVA_BIN="$("$TOOLS/venv/bin/python" -c 'import jdk4py;print(jdk4py.JAVA)')"
fi
AAPT2="$TOOLS/venv/lib/python3.*/site-packages/aapt2/bin/Linux/aapt2"
AAPT2="$(ls $AAPT2 2>/dev/null | head -1)"
[ -n "$AAPT2" ] && chmod +x "$AAPT2"
echo "$JAVA_BIN" > "$TOOLS/java.path"
echo "$AAPT2" > "$TOOLS/aapt2.path"
say "java:   $JAVA_BIN"
say "aapt2:  $AAPT2"

# ---------------------------------------------------------------- Kotlin side
if [ ! -x "$TOOLS/kotlinc/bin/kotlinc" ]; then
  say "installing kotlin-compiler 1.9.25 (npm)"
  if command -v npm >/dev/null 2>&1; then
    npm install -g --prefix "$TOOLS" kotlin-compiler@1.9.25 >/dev/null 2>&1 || {
      echo "npm install kotlin-compiler failed"; exit 1; }
  else
    echo "node/npm not available on this machine"; exit 1
  fi
fi
KOTLINC="$(ls "$TOOLS"/lib/node_modules/kotlin-compiler/bin/kotlinc 2>/dev/null | head -1)"
[ -z "$KOTLINC" ] && KOTLINC="$TOOLS/kotlinc/bin/kotlinc"
[ -x "$KOTLINC" ] || { echo "kotlinc missing at $KOTLINC"; exit 1; }
chmod +x "$KOTLINC"
echo "$KOTLINC" > "$TOOLS/kotlinc.path"
say "kotlinc: $KOTLINC"

# ------------------------------------------------------- Jar/blob fetch helper
fetch_blob() { # fetch_blob <out-file> <repo> <blob-sha>
  local out="$1" repo="$2" sha="$3"
  if [ -s "$out" ]; then return 0; fi
  say "fetch $(basename "$out") from $repo"
  curl -fsS -H "Accept: application/vnd.github.v3.raw" -o "$out" \
    "https://api.github.com/repos/$repo/git/blobs/$sha" || { echo "fetch failed: $out"; exit 1; }
}

# android.jar API-35 compile stub (Sable/android-platforms is the mirror this repo
# already uses for Blockhold; its resources.arsc is a stub, so it is a compile
# classpath only - aapt2 links against apktool's framework table instead).
fetch_blob "$TOOLS/android.jar" Sable/android-platforms \
  2d6652edf0fb6046a23638f3a2f2c6246c745b08
fetch_blob "$TOOLS/dx.jar" screetsec/TheFatRat \
  ef90a14d8f2a6deda26da6ca50682e3384e74dfe
fetch_blob "$TOOLS/apksigner.jar" screetsec/TheFatRat \
  335cc2cb9acb3244c0ac79979cb77053304d3658

# A framework resource table for aapt2 link: the modern aapt2 cannot read the
# compile stub's arsc, so link against apktool's framework 1.apk instead. It only
# needs to resolve @android:... references.
if [ ! -s "$TOOLS/1.apk" ]; then
  say "extracting aapt2 + framework from apktool"
  fetch_blob "$TOOLS/apktool.jar" screetsec/TheFatRat \
    53d723d86f2a1f58fc63d7d918a50ce7e22a08ce
  ( cd "$TOOLS" && "$JAVA_BIN" -jar apktool.jar if 4.1.1.4 >/dev/null 2>&1 || true )
  for candidate in "$HOME/.local/share/apktool/framework/1.apk" "$HOME/.apktool/framework/1.apk"; do
    if [ -f "$candidate" ]; then cp "$candidate" "$TOOLS/1.apk"; break; fi
  done
  if [ ! -s "$TOOLS/1.apk" ]; then
    # Last resort: ask the framework installer to fetch it, then re-check.
    ( cd "$TOOLS" && "$JAVA_BIN" -jar apktool.jar if >/dev/null 2>&1 || true )
    for candidate in "$HOME/.local/share/apktool/framework/1.apk" "$HOME/.apktool/framework/1.apk"; do
      if [ -f "$candidate" ]; then cp "$candidate" "$TOOLS/1.apk"; break; fi
    done
  fi
fi
[ -s "$TOOLS/1.apk" ] && say "framework table: $(wc -c < "$TOOLS/1.apk") bytes" || say "WARNING: no framework 1.apk - aapt2 link will need -I android.jar fallback"

say "toolchain ready in $TOOLS"
