#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
D="$ROOT/app/src/main/res/drawable-nodpi"

# Small original vector-style sprite compositions generated with ImageMagick.
sprite() { convert -size 64x64 xc:none "$@"; }

# Corruption mutations.
sprite -fill '#55c96d' -stroke '#245f35' -strokewidth 2 -draw "circle 24,34 24,23 circle 39,29 39,19 circle 42,42 42,34" -fill '#d8f18b' -stroke none -draw "circle 21,20 21,17 circle 36,16 36,13 circle 48,31 48,28" "$D/corruption_spore_path.png"
sprite -fill '#738b9a' -stroke '#293a45' -strokewidth 2 -draw "polygon 11,39 17,20 31,12 47,18 54,37 44,51 23,52" -fill '#b9cad4' -stroke none -draw "polygon 20,34 24,22 33,18 42,24 45,36 37,44 27,43" "$D/corruption_carapace_growth.png"
sprite -fill '#b854d2' -stroke '#53215f' -strokewidth 2 -draw "circle 32,32 32,21 path 'M 32,21 C 18,5 11,17 23,28 C 7,23 7,41 25,37 C 14,52 31,58 34,40 C 43,55 57,45 40,35 C 57,32 52,14 38,27 C 44,8 26,7 32,21 Z'" -fill '#f1bbff' -stroke none -draw "circle 32,32 32,27" "$D/corruption_hex_bloom.png"
sprite -fill '#7259c4' -stroke '#30235e' -strokewidth 2 -draw "path 'M 10,43 C 18,38 19,28 14,21 C 28,27 34,20 32,9 C 43,20 50,17 55,12 C 51,28 45,34 35,34 C 29,34 28,45 31,55 C 22,48 18,46 10,43 Z'" -fill '#c8b8ff' -stroke none -draw "polygon 34,19 44,26 36,30 45,36 29,33 33,27 25,23" "$D/corruption_blink_root.png"
sprite -fill '#3f9650' -stroke '#174924' -strokewidth 2 -draw "polygon 8,47 18,26 25,39 32,10 39,38 47,22 56,48" -fill '#87db7f' -stroke none -draw "polygon 20,43 25,29 29,44 38,24 42,45" "$D/corruption_thorn_soil.png"
sprite -fill '#d96f9c' -stroke '#672e48' -strokewidth 2 -draw "ellipse 32,37 23,17 0,360" -fill '#f5b1cb' -draw "circle 23,34 23,29 circle 36,29 36,24 circle 43,39 43,34" -fill '#5d273e' -stroke none -draw "circle 23,34 23,31 circle 36,29 36,26 circle 43,39 43,36" "$D/corruption_brood_nest.png"

# Tower evolution overlay badges.
sprite -fill '#d8ff72' -stroke '#45651d' -strokewidth 2 -draw "circle 16,32 16,27 circle 32,19 32,14 circle 48,32 48,27 circle 32,47 32,42 line 20,29 28,22 line 36,22 44,29 line 44,36 36,44 line 28,44 20,36" "$D/evolution_chain_conductor.png"
sprite -fill '#e7ff9a' -stroke '#4e6628' -strokewidth 2 -draw "polygon 7,28 45,28 45,20 58,32 45,44 45,36 7,36 line 18,24 18,40 line 30,24 30,40" "$D/evolution_rail_spire.png"
sprite -fill '#8fe8ff' -stroke '#28657a' -strokewidth 2 -draw "line 32,7 32,57 line 10,19 54,45 line 10,45 54,19 line 26,12 32,18 line 32,18 38,12 line 26,52 32,46 line 32,46 38,52 line 13,24 21,25 line 21,25 19,17 line 45,47 43,39 line 43,39 51,40" "$D/evolution_blizzard_lens.png"
sprite -fill '#b8f3ff' -stroke '#39748a' -strokewidth 2 -draw "polygon 32,6 49,24 42,54 20,54 14,25" -fill '#ffffff' -stroke none -draw "polygon 32,13 38,29 33,26 29,47 25,29 20,31" "$D/evolution_shatter_crystal.png"
sprite -fill '#ffa34d' -stroke '#713d18' -strokewidth 2 -draw "ellipse 25,35 17,11 0,360 rectangle 22,13 51,25 polygon 47,9 59,19 48,30" "$D/evolution_siege_mortar.png"
sprite -fill '#bb83ee' -stroke '#4b2867' -strokewidth 2 -draw "circle 32,32 32,10 circle 32,32 32,18 path 'M 12,32 C 21,17 43,17 52,32 C 43,47 21,47 12,32 Z'" -fill '#f0d7ff' -stroke none -draw "circle 32,32 32,27" "$D/evolution_gravity_cannon.png"
sprite -fill '#ff6b35' -stroke '#792810' -strokewidth 2 -draw "path 'M 32,6 C 48,23 53,34 45,47 C 38,59 20,58 14,45 C 9,34 20,29 19,17 C 26,21 29,14 32,6 Z'" -fill '#ffd85a' -stroke none -draw "path 'M 31,26 C 39,35 40,43 34,48 C 27,52 20,45 24,38 C 27,34 29,31 31,26 Z'" "$D/evolution_inferno_engine.png"
sprite -fill '#f18046' -stroke '#69301e' -strokewidth 2 -draw "circle 32,33 32,19 circle 32,33 32,28 line 32,15 32,8 line 32,58 32,51 line 14,33 7,33 line 57,33 50,33 line 19,20 13,14 line 51,52 45,46 line 45,20 51,14 line 19,46 13,52" "$D/evolution_cinder_reactor.png"
sprite -fill '#c579ff' -stroke '#50266f' -strokewidth 2 -draw "circle 32,32 32,12 circle 32,32 32,20 path 'M 8,42 C 17,31 24,27 32,32 C 40,37 47,33 56,22'" -fill '#f0dcff' -stroke none -draw "circle 32,32 32,28" "$D/evolution_storm_choir.png"
sprite -fill '#e7d7ff' -stroke '#60477f' -strokewidth 2 -draw "circle 24,32 24,12 circle 40,32 40,20 circle 24,32 24,20 line 31,18 34,18 line 31,46 34,46" "$D/evolution_harmony_nexus.png"

# Crafted supplies.
sprite -fill '#e8eee8' -stroke '#425047' -strokewidth 2 -draw "roundrectangle 12,18 52,48 6,6" -fill '#ff6d64' -stroke none -draw "rectangle 27,23 37,43 rectangle 21,29 43,37" "$D/item_core_patch.png"
sprite -fill '#d9bd86' -stroke '#5f4b2e' -strokewidth 2 -draw "roundrectangle 12,18 52,46 8,8 line 18,22 46,42 line 46,22 18,42" -fill '#f4e4b2' -stroke none -draw "rectangle 28,17 36,47" "$D/item_recovery_wrap.png"
sprite -fill '#7de39b' -stroke '#285e3a' -strokewidth 2 -draw "polygon 23,8 41,8 39,18 45,49 19,49 25,18" -fill '#d9ffe3' -stroke none -draw "rectangle 25,11 39,16 ellipse 32,36 7,8 0,360" "$D/item_purifier_vial.png"
sprite -fill '#bb83ee' -stroke '#4d2d68' -strokewidth 2 -draw "circle 22,32 22,17 circle 42,32 42,17 rectangle 22,27 42,37 line 32,8 32,20 line 32,44 32,56" "$D/item_reforge_coupler.png"
sprite -fill '#f3c557' -stroke '#664d1c' -strokewidth 2 -draw "circle 32,32 32,17 circle 32,32 32,27 line 32,13 32,7 line 32,57 32,51 line 13,32 7,32 line 57,32 51,32 line 19,19 14,14 line 50,50 45,45 line 45,19 50,14 line 19,45 14,50" "$D/item_utility_gearset.png"
sprite -fill '#91caff' -stroke '#31566f' -strokewidth 2 -draw "circle 27,27 27,13 line 37,38 52,53" -fill '#dff3ff' -stroke none -draw "circle 24,24 24,18" "$D/item_survey_lens.png"
sprite -fill '#c8aa7b' -stroke '#59462d' -strokewidth 2 -draw "roundrectangle 10,15 54,49 5,5" -fill '#eff4ef' -draw "polygon 17,40 26,24 34,34 43,19 49,40" -fill '#7dbbd3' -stroke none -draw "rectangle 14,12 50,19" "$D/item_trap_refit_kit.png"
sprite -fill '#d7c5ef' -stroke '#554269' -strokewidth 2 -draw "polygon 32,7 51,18 51,45 32,57 13,45 13,18" -fill '#ffffff' -stroke none -draw "circle 32,32 32,22 line 32,17 32,47 line 17,32 47,32" "$D/item_blank_sigil.png"

# Imbuement sigils.
sprite -fill '#ff6f5c' -stroke '#6f251d' -strokewidth 2 -draw "circle 32,32 32,9 polygon 32,13 39,26 54,28 42,38 46,53 32,45 18,53 22,38 10,28 25,26" "$D/sigil_might.png"
sprite -fill '#ffd75a' -stroke '#705514' -strokewidth 2 -draw "circle 32,32 32,9 polygon 12,25 34,25 28,15 52,30 30,30 36,40 12,25 polygon 18,40 43,40 37,50 55,35 30,35" "$D/sigil_tempo.png"
sprite -fill '#72ddff' -stroke '#285e72' -strokewidth 2 -draw "circle 32,32 32,9 circle 32,32 32,18 circle 32,32 32,27 line 32,5 32,16 line 32,48 32,59 line 5,32 16,32 line 48,32 59,32" "$D/sigil_reach.png"
sprite -fill '#eef6ff' -stroke '#647485' -strokewidth 2 -draw "circle 32,32 32,9 path 'M 32,14 L 48,20 L 46,38 C 43,48 36,53 32,55 C 28,53 21,48 18,38 L 16,20 Z'" -fill '#92c9ff' -stroke none -draw "polygon 24,33 30,40 42,24 29,34" "$D/sigil_clarity.png"
sprite -fill '#c27dff' -stroke '#522773' -strokewidth 2 -draw "circle 32,32 32,9 circle 26,32 26,18 circle 38,32 38,18" "$D/sigil_echoes.png"
sprite -fill '#7ce39a' -stroke '#285f39' -strokewidth 2 -draw "circle 32,32 32,9 path 'M 17,35 C 23,18 39,15 48,17 C 45,35 33,48 17,35 Z' line 20,40 44,20" "$D/sigil_conservation.png"

# Materials and cache UI.
sprite -fill '#bba178' -stroke '#4d402d' -strokewidth 2 -draw "polygon 9,42 17,21 30,27 38,13 55,25 50,49 25,53" -fill '#e3d3ad' -stroke none -draw "polygon 18,39 23,29 32,34 39,22 47,28 44,42" "$D/icon_salvage_parts.png"
sprite -fill '#7de39b' -stroke '#285e3a' -strokewidth 2 -draw "path 'M 32,5 C 43,20 51,29 48,41 C 45,54 34,59 24,54 C 13,49 12,37 18,28 C 24,20 28,12 32,5 Z'" -fill '#d9ffe4' -stroke none -draw "ellipse 28,35 4,10 0,360" "$D/icon_growth_essence.png"
sprite -fill '#64cbe8' -stroke '#285565' -strokewidth 2 -draw "roundrectangle 8,18 56,50 6,6 rectangle 14,24 50,45 line 32,18 32,50 line 8,33 56,33" -fill '#f0fbff' -stroke none -draw "rectangle 28,25 36,31" "$D/icon_forge_cache.png"

echo "Forgeworks art generated in $D"
