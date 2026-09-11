#!/usr/bin/env bash
# Cross-compiles Aureus.exe for 64-bit Windows from any OS that has the Go
# toolchain. The program is pure Go (no cgo), so no Windows machine or cross
# gcc is needed. The app icon, Windows version info ("Made by Troy") and
# manifest come from the committed resource.syso (see resource.syso note in
# README), so Go picks them up automatically.
#
#   ./build.sh            -> ../artifacts/Aureus-v<version>.exe
#
# On a Windows box you can also run build.bat for a native build.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=$(sed -n 's/^const version = "\(.*\)"$/\1/p' main.go)
OUT="../artifacts/Aureus-v${VERSION}.exe"

GOOS=windows GOARCH=amd64 CGO_ENABLED=0 \
  go build -trimpath -ldflags "-s -w" -o "$OUT" .

echo "Built $OUT"
