#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODELS=app/src/main/java/ai/techtroy/blockhold/GameModels.kt
VIEW=app/src/main/java/ai/techtroy/blockhold/GameView.kt
CATALOG=app/src/main/java/ai/techtroy/blockhold/SpriteCatalog.kt

assert_contains() {
  local file="$1" pattern="$2"
  grep -Eq "$pattern" "$file" || { echo "Missing assertion '$pattern' in $file" >&2; exit 1; }
}

assert_contains app/build.gradle.kts 'versionCode = 12'
assert_contains app/build.gradle.kts 'versionName = "1\.2\.0"'
assert_contains "$VIEW" 'putInt\("run_save_version", 3\)'
assert_contains "$MODELS" 'enum class BuildPage'
for category in TOWERS TRAPS UTILITIES CACHE; do assert_contains "$MODELS" "$category"; done
for utility in BLOCK_GENERATOR CACHE_DEPOT FORGE_WORKSHOP PURIFIER_TOTEM SURVEYOR_STATION REFORGE_ANCHOR SALVAGE_YARD; do assert_contains "$MODELS" "$utility"; done
for item in CORE_PATCH RECOVERY_WRAP PURIFIER_VIAL REFORGE_COUPLER UTILITY_GEARSET SURVEY_LENS TRAP_REFIT_KIT BLANK_SIGIL; do assert_contains "$MODELS" "$item"; done
for sigil in MIGHT TEMPO REACH CLARITY ECHOES CONSERVATION; do assert_contains "$MODELS" "$sigil"; done
assert_contains "$VIEW" 'StoredTrap\(trap\.kind, trap\.level, trap\.overcharge, trap\.imbuement\)'
assert_contains "$VIEW" 'private fun cacheCapacity\(\)'
assert_contains "$VIEW" 'private fun trapStorageCost\(trap: SpikeTrap\)'
assert_contains "$VIEW" 'private fun drawWorkshop\(canvas: Canvas\)'
assert_contains "$CATALOG" 'fun corruption\(kind: CorruptionKind\)'
assert_contains "$CATALOG" 'fun craftedItem\(kind: CraftedItem\)'

if grep -q '<uses-permission' app/src/main/AndroidManifest.xml; then
  echo 'Android manifest unexpectedly requests a permission.' >&2
  exit 1
fi
if grep -REn 'android\.permission\.INTERNET|https?://|localhost|127\.0\.0\.1' app/src/main/java; then
  echo 'Runtime source unexpectedly contains network access.' >&2
  exit 1
fi

art_count=0
for pattern in 'utility_*.png' 'corruption_*.png' 'evolution_*.png' 'item_*.png' 'sigil_*.png' 'icon_*.png'; do
  count=$(find app/src/main/res/drawable-nodpi -maxdepth 1 -type f -name "$pattern" | wc -l)
  art_count=$((art_count + count))
done
[[ "$art_count" -eq 40 ]] || { echo "Expected 40 Forgeworks images, found $art_count" >&2; exit 1; }

if command -v identify >/dev/null 2>&1; then
  identify app/src/main/res/drawable-nodpi/*.png >/dev/null
fi

bash -n scripts/build-apk.sh tools/generate_forgeworks_art.sh ci/verify-forgeworks.sh
python3 -m py_compile tools/generate_sfx.py
git diff --check

echo "Forgeworks source assertions passed: version 1.2, save v3, 7 Utilities, 8 Supplies, 6 imbuements, 40 new images, offline/no permissions."
