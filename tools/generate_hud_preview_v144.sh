#!/usr/bin/env bash
# Build the small GitHub-facing v1.4.4 HUD layout preview from packaged sprites.
# Requires ImageMagick 6+ (`convert`).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DRAWABLE="$ROOT/app/src/main/res/drawable-nodpi"
OUTPUT="$ROOT/docs/V1.4.4_HUD_PREVIEW.png"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

command -v convert >/dev/null 2>&1 || {
  echo "ImageMagick 'convert' is required" >&2
  exit 1
}

# Representative frames for this static (non-device-screenshot) layout preview.
convert "$DRAWABLE/landmark_gate.png" -crop 64x64+64+0 +repage "$TMP/gate.png"
convert "$DRAWABLE/landmark_core.png" -crop 64x64+64+0 +repage "$TMP/core.png"
convert "$DRAWABLE/tower_bolt_turret.png" -crop 64x64+0+0 +repage "$TMP/bolt_top.png"
convert "$DRAWABLE/tower_frost_turret.png" -crop 64x64+0+0 +repage "$TMP/frost_top.png"
convert "$DRAWABLE/enemy_mosser.png" -crop 64x64+0+0 +repage "$TMP/enemy.png"
convert "$DRAWABLE/trap_spike_bed.png" -crop 64x64+0+0 +repage "$TMP/trap.png"
convert "$DRAWABLE/utility_block_generator.png" -crop 64x64+64+0 +repage "$TMP/utility.png"

# A tiny representative board assembled from the real terrain and entity art.
convert -size 724x239 "tile:$DRAWABLE/terrain_grass.png" "$TMP/board.png"
convert "$DRAWABLE/terrain_path.png" -resize 48x48! "$TMP/road.png"
for point in \
  0,96 48,96 96,96 144,96 144,48 192,48 240,48 288,48 \
  336,48 336,96 384,96 432,96 480,96 528,96 576,96 624,96 672,96; do
  x="${point%,*}"; y="${point#*,}"
  composite -geometry "+$x+$y" "$TMP/road.png" "$TMP/board.png" "$TMP/board-next.png"
  mv "$TMP/board-next.png" "$TMP/board.png"
done

MVG="$TMP/preview.mvg"
cat >"$MVG" <<MVG
# Background and document header
fill '#07100c' rectangle 0,0 1600,900
fill '#101a14' polygon 0,0 1600,0 1600,900
fill '#07100c' polygon 0,0 820,0 430,900 0,900
stroke '#3e3525' stroke-width 2 line 0,118 1600,118 line 0,780 1600,780
stroke none
font 'DejaVu-Sans-Bold' font-size 37 fill '#eef0e8' text 74,72 'V1.4.4  HUD REFIT'
font-size 17 fill '#bef44e' text 1262,70 'FANTASY x MACHINERY'
font-size 15 fill '#7d9685' text 75,151 'TITLE MENU'
font-size 15 fill '#7d9685' text 797,151 'IN-GAME HUD'

# Title menu card + soft shadow
fill '#020604' roundrectangle 60,180 740,560 22,22
fill '#080e0b' stroke '#493e2a' stroke-width 2 roundrectangle 70,170 730,541 18,18
stroke '#536557' stroke-width 1 opacity 0.13
line 70,244 730,244 line 70,318 730,318 line 70,392 730,392 line 70,466 730,466
line 152,170 152,541 line 234,170 234,541 line 316,170 316,541 line 398,170 398,541 line 480,170 480,541 line 562,170 562,541 line 644,170 644,541
opacity 1 stroke none
image over 88,185 45,45 '$DRAWABLE/hud_menu.png'
font-size 11 fill '#82998a' text 146,214 'V1.4.4'
fill '#111f18' stroke '#47604d' stroke-width 1 roundrectangle 667,184 714,231 11,11
stroke none image over 673,190 35,35 '$DRAWABLE/hud_sound_on.png'
image over 150,258 75,75 '$TMP/gate.png'
image over 575,258 75,75 '$TMP/core.png'
font-size 43 fill '#eef0e8' text 286,296 'BLOCKHOLD'
font-size 15 fill '#bef44e' text 358,327 'DEFENSE'
fill '#bef44e' opacity 0.45 rectangle 337,341 463,343
opacity 1

# Title actions
fill '#13251b' stroke '#91bd42' stroke-width 2 roundrectangle 93,399 288,482 13,13
stroke none image over 111,413 55,55 '$DRAWABLE/hud_play.png'
font-size 15 fill '#bef44e' text 189,449 'NEW RUN'
fill '#12231b' stroke '#4f9eaa' stroke-width 2 roundrectangle 302,399 497,482 13,13
stroke none image over 320,413 55,55 '$DRAWABLE/hud_continue.png'
font-size 15 fill '#5ddcff' text 395,449 'CONTINUE'
fill '#211a12' stroke '#a87540' stroke-width 2 roundrectangle 511,399 706,482 13,13
stroke none image over 529,413 55,55 '$DRAWABLE/hud_challenge.png'
font-size 14 fill '#ffbe68' text 601,449 'CHALLENGES'
font-size 10 fill '#6f8978' text 285,518 'BEST W18   -   DAILY W9   -   CUSTOM W14'

# In-game card + top dock
fill '#020604' roundrectangle 780,180 1540,605 22,22
fill '#080f0c' stroke '#493e2a' stroke-width 2 roundrectangle 790,170 1530,586 18,18
fill '#09110d' stroke none roundrectangle 790,170 1530,232 17,17
fill '#695735' rectangle 790,230 1530,232

# Three compact stats
fill '#101d16' roundrectangle 798,178 886,223 9,9
image over 805,184 33,33 '$DRAWABLE/icon_forge_cache.png'
font-size 16 fill '#bef44e' text 844,207 '420'
fill '#101d16' roundrectangle 891,178 979,223 9,9
image over 898,184 33,33 '$TMP/core.png'
font-size 15 fill '#ff7060' text 939,207 '12/12'
fill '#101d16' roundrectangle 984,178 1058,223 9,9
image over 991,185 31,31 '$DRAWABLE/hud_challenge.png'
font-size 16 fill '#5ddcff' text 1032,207 '7'
image over 1068,187 29,29 '$DRAWABLE/hud_reforge.png'
font-size 13 fill '#d5b6ff' text 1102,207 '2'

# Wave, sound, pause controls
fill '#1c2c1d' stroke '#759638' stroke-width 1 roundrectangle 1220,178 1357,223 9,9
stroke none image over 1227,184 34,34 '$DRAWABLE/hud_play.png'
font-size 14 fill '#bef44e' text 1276,207 'WAVE 8'
fill '#101d16' stroke '#364c3c' stroke-width 1 roundrectangle 1364,178 1412,223 9,9
stroke none image over 1372,185 32,32 '$DRAWABLE/hud_sound_on.png'
fill '#101d16' stroke '#364c3c' stroke-width 1 roundrectangle 1418,178 1466,223 9,9
stroke none image over 1426,185 32,32 '$DRAWABLE/hud_pause.png'

# Real board art
image over 798,238 724,239 '$TMP/board.png'
fill none stroke '#755f38' stroke-width 4 roundrectangle 798,238 1522,477 8,8
stroke none
image over 806,328 66,66 '$TMP/gate.png'
image over 1457,328 66,66 '$TMP/core.png'
image over 1031,246 57,57 '$DRAWABLE/tower_bolt_base.png'
image over 1031,245 57,57 '$TMP/bolt_top.png'
image over 1256,390 57,57 '$DRAWABLE/tower_frost_base.png'
image over 1256,389 57,57 '$TMP/frost_top.png'
image over 1222,334 61,61 '$TMP/enemy.png'
image over 1111,281 60,60 '$TMP/trap.png'

# Bottom category rail
fill '#08100c' roundrectangle 790,483 1530,586 17,17
fill '#5a4c32' rectangle 790,483 1530,485
fill '#182a1e' stroke '#78993b' stroke-width 1 roundrectangle 799,492 845,530 8,8
stroke none image over 808,497 29,29 '$DRAWABLE/tower_bolt_base.png'
fill '#0e1914' stroke '#344b3b' stroke-width 1 roundrectangle 799,536 845,574 8,8
stroke none image over 808,541 29,29 '$TMP/utility.png'
fill '#0e1914' stroke '#344b3b' stroke-width 1 roundrectangle 850,492 896,530 8,8
stroke none image over 859,497 29,29 '$TMP/trap.png'
fill '#0e1914' stroke '#344b3b' stroke-width 1 roundrectangle 850,536 896,574 8,8
stroke none image over 859,541 29,29 '$DRAWABLE/icon_forge_cache.png'

# Bottom sprite cards
fill '#17271d' stroke '#83a841' stroke-width 2 roundrectangle 907,492 1047,574 11,11
stroke none image over 951,497 51,51 '$DRAWABLE/tower_bolt_base.png'
image over 951,496 51,51 '$TMP/bolt_top.png'
font-size 11 fill '#bef44e' text 944,565 'BOLT - 70'
fill '#0e1914' stroke '#344b3b' stroke-width 1 roundrectangle 1055,492 1195,574 11,11
stroke none image over 1099,497 51,51 '$DRAWABLE/tower_frost_base.png'
image over 1099,496 51,51 '$TMP/frost_top.png'
font-size 11 fill '#dce8df' text 1087,565 'FROST - 90'
fill '#0e1914' stroke '#344b3b' stroke-width 1 roundrectangle 1203,492 1343,574 11,11
stroke none image over 1247,497 51,51 '$DRAWABLE/tower_cannon_base.png'
font-size 11 fill '#dce8df' text 1227,565 'CANNON - 125'
fill '#0e1914' stroke '#344b3b' stroke-width 1 roundrectangle 1351,492 1491,574 11,11
stroke none image over 1395,497 51,51 '$DRAWABLE/tower_ember_base.png'
font-size 11 fill '#dce8df' text 1377,565 'EMBER - 145'

# First 10-sprite production batch
font-size 15 fill '#7d9685' text 75,628 'AI CONTROL SPRITES  -  BATCH 01 / 10'
fill '#0d1812' stroke '#3f392a' stroke-width 1 roundrectangle 72,652 1528,757 16,16
stroke none
image over 97,665 80,80 '$DRAWABLE/hud_play.png'
image over 243,665 80,80 '$DRAWABLE/hud_continue.png'
image over 389,665 80,80 '$DRAWABLE/hud_challenge.png'
image over 535,665 80,80 '$DRAWABLE/hud_sound_on.png'
image over 681,665 80,80 '$DRAWABLE/hud_sound_off.png'
image over 827,665 80,80 '$DRAWABLE/hud_pause.png'
image over 973,665 80,80 '$DRAWABLE/hud_reforge.png'
image over 1119,665 80,80 '$DRAWABLE/hud_confirm.png'
image over 1265,665 80,80 '$DRAWABLE/hud_back.png'
image over 1411,665 80,80 '$DRAWABLE/hud_menu.png'
font 'DejaVu-Sans' font-size 14 fill '#8da293' text 421,827 'Minimal copy  -  sprite-first actions  -  more battlefield space  -  unified brass-and-rune chrome'
MVG

convert -size 1600x900 xc:'#07100c' -draw "@$MVG" -strip -depth 8 "$OUTPUT"
echo "$OUTPUT"
