#!/usr/bin/env bash
# Isolate and publish the v1.4.4 fantasy-machinery HUD controls and title assets.
# Batch 01 contains ten 128 px controls. Batch 02 contains eight generated menu
# assets: a backdrop, a logo, three full button plates, and three record badges.
# Batch 03 adds three complete sprite-backed record plates for the title stack.
# Requires ImageMagick 6+ (`convert`). Generated concepts occasionally contain
# a baked white/grey transparency field, so the edge-connected neutral field is
# flood-filled away before each transparent sprite is normalized.
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
    -strip \
    -depth 8 \
    "$destination"

  cp "$destination" "$DRAWABLE/hud_${name#ui_}.png"
  echo "published hud_${name#ui_}.png"
done

# Batch 02's full-bleed background stays opaque and is reduced to a practical
# Android texture size. The generated source is already a true 16:9 scene.
convert "$SOURCE/title_background_source.png" \
  -resize '1280x720^' \
  -gravity center \
  -extent 1280x720 \
  -strip \
  -depth 8 \
  "$PROCESSED/title_background.png"
cp "$PROCESSED/title_background.png" "$DRAWABLE/title_background.png"
echo "published title_background.png"

# The remaining Batch 02 art was generated on a neutral field. Isolate it,
# normalize related assets to identical canvases, and retain transparent edges.
isolate() {
  local source="$1"
  local destination="$2"
  local resize="$3"
  local extent="$4"
  convert "$source" \
    -alpha on \
    -fuzz 12% \
    -fill none \
    -draw 'matte 0,0 floodfill' \
    -trim +repage \
    -resize "$resize" \
    -gravity center \
    -background none \
    -extent "$extent" \
    -strip \
    -depth 8 \
    "$destination"
}

isolate "$SOURCE/title_logo_source.png" "$PROCESSED/title_logo.png" '620x268!' '640x288'
for name in title_button_new_run title_button_continue title_button_challenges; do
  isolate "$SOURCE/${name}_source.png" "$PROCESSED/${name}.png" '612x184!' '640x208'
  cp "$PROCESSED/${name}.png" "$DRAWABLE/${name}.png"
  echo "published ${name}.png"
done
cp "$PROCESSED/title_logo.png" "$DRAWABLE/title_logo.png"
echo "published title_logo.png"

for name in menu_badge_best menu_badge_wave menu_badge_daily; do
  isolate "$SOURCE/${name}_source.png" "$PROCESSED/${name}.png" '112x112' '128x128'
  cp "$PROCESSED/${name}.png" "$DRAWABLE/${name}.png"
  echo "published ${name}.png"
done

for name in menu_record_best_plate menu_record_daily_plate menu_record_wave_plate; do
  isolate "$SOURCE/${name}_source.png" "$PROCESSED/${name}.png" '492x140!' '512x160'
  cp "$PROCESSED/${name}.png" "$DRAWABLE/${name}.png"
  echo "published ${name}.png"
done
