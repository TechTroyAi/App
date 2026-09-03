# v1.4.4 Build Status — Arena Session

## ✅ Completed & Verified — Signed Installable APK Ready!

### 📦 Artifact Details
- **File:** `artifacts/Blockhold-Defense-v1.4.4-installable.apk`
- **Size:** 14,572,806 bytes (~14.5 MB)
- **SHA-256:** `e1e33918b12d2a30640b0e683ef744927822d0a8d2b0c041987a97a94b99fdea`
- **Package:** `ai.techtroy.blockhold`
- **Version:** `1.4.4` (`versionCode 18`)
- **Signing Schemes:** APK Signature Scheme v2 + v3
- **Signing Certificate SHA-256:** `79:4b:0c:30:22:50:37:17:7a:28:71:4c:e2:56:90:e7:be:e2:aa:c8:33:56:d1:2b:5a:d9:08:7f:a6:85:ef:bc`
- **Certificate DN:** `CN=Blockhold Defense, OU=Game Release, O=TechTroyAi, L=Davao City, ST=Davao Region, C=PH`

### 🔍 Verification Results
- `scripts/verify-apk.py artifacts/Blockhold-Defense-v1.4.4-installable.apk`: **ALL CHECKS PASSED (0 failures, 0 warnings)**
  - ZIP layout and alignment: PASS (resources.arsc uncompressed, classes.dex STORED, all 285 uncompressed entries 4-byte aligned)
  - Signing: PASS (v2, v3 schemes verified)
  - DEX integrity: PASS (classes.dex format 038, 2,873,368 bytes, checksum valid, 1086 classes bundled, all 1447 referenced types resolve)
  - AndroidManifest: PASS (package `ai.techtroy.blockhold`, `1.4.4` (`18`), minSdk 24 / target 35, MAIN/LAUNCHER exported)
  - Resource table: PASS (all 256 SpriteCatalog drawables packaged, all 13 AudioEngine sounds packaged)
  - Sprite strip geometry: PASS (all 101 sprite strips square-framed)
- `scripts/verify-dex-shape.py`: **ALL DEX SHAPE CHECKS PASSED** (1086 classes, 6 call sites, MainActivity + lambdas present, save/restore balanced)
- `apksigner verify`: **Verification SUCCESSFUL** (v2=true, v3=true)

### 🛠️ Toolchain Used
1. **JDK 21** via `jdk4py==21.0.8.2`
2. **Kotlin compiler 1.9.25** with `-jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class`
3. **Android API 35 SDK compile stub** (`android.jar`)
4. **Dalvik Compiler** (`dx 1.16` with `--dex --min-sdk-version=26`)
5. **Apktool 2.6.0** (AAPT2 resource linking and AXML compilation)
6. **Zipalign Engine** (`repackage-with-dex.py` with 4-byte / 4096-page alignment)
7. **APKs內igner 0.9** (v2 + v3 release signing)

### 🎮 Features in v1.4.4
1. **Forge Overdrive Mechanic:**
   - Real-time charge meter (kills charge the meter: normal +4, elite +15, boss +35)
   - Player-activated combat boost (+45% fire rate, +25% damage boost for 8 seconds)
   - Dynamic UI button with radial fill arc, gold ready-state pulsing, countdown timer, and activation particle FX
2. **Fantasy Machinery HUD & UI Overhaul:**
   - Authored brass/copper top rail and dark iron build shelf
   - Skinned button states (primary, secondary, accent, warning + pressed/disabled states)
   - Full 256 SpriteCatalog drawables packaged and registered
3. **Gameplay Balance & QoL:**
   - 1-minute auto-next-wave countdown timer
   - In-combat mid-wave structure inspection and interaction (upgrade, store, imbue, recycle)
   - Level 99 Block Generator scaling with escalating outputs

