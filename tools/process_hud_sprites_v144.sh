#!/usr/bin/env bash
# Isolate and publish the ten v1.4.4 fantasy-machinery HUD controls.
# Requires ImageMagick 6+ (`convert`). Generated concepts occasionally contain
# a baked white/grey transparency checker, so the edge-connected neutral field
# is flood-filled away before the icon is normalized to a 128 px RGBA canvas.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/artwork/ui-production/v1.4.4/source"
PROCESSED="$ROOT/artwork/ui-production/v1.4.4/processed"
DRAWABLE="$ROOT/app/src/main/res/drawable-nodpi"

command -v convert >/dev/null 2>&1 || {
  echo "ImageMagick 'convert' is required" >&2
  exit 1
}

mkdir -p "$PROCESSED" "$DRAWABLE"

for source in "$SOURCE"/ui_*_source.png; do
  name="$(basename "$source" _source.png)"
  destination="$PROCESSED/$name.png"

  convert "$source" \
    -alpha on \
    -fuzz 12% \
    -fill none \
    -draw 'matte 0,0 floodfill' \
    -trim +repage \
    -resize 116x116 \
    -gravity center \
    -background none \
    -extent 128x128 \
    "$destination"

  cp "$destination" "$DRAWABLE/hud_${name#ui_}.png"
  echo "published hud_${name#ui_}.png"
done
