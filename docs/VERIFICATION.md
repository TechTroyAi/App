# v1.2 Forgeworks verification record

Artifact: `artifacts/Blockhold-Defense-v1.2-unsigned.apk`

Verification date: 2026-08-27

## Identity

- Package: `ai.techtroy.blockhold`
- Version: `1.2.0` (`12`)
- Application label: Blockhold Defense
- Launch activity: `ai.techtroy.blockhold.MainActivity`
- Minimum SDK: 24 (Android 7.0)
- Target/compile SDK: 35
- Orientation: landscape
- Requested permissions: none
- Runtime network endpoints: none; fully offline

## Artifact integrity and signing state

- APK SHA-256: `be84a01d8034ca58c3c7caffb9d94df04aa7e466afe49a519e568361b2d814cf`
- Size: 2,298,790 bytes
- ZIP archive test: passed with no compressed-data errors
- Four-byte ZIP alignment: passed
- DEX: version 037, one `classes.dex`, 706 class definitions
- APK entries: 92
- Native libraries: none
- Signature state: **unsigned by design**

The v1.0 production certificate SHA-256 remains `14d779957005aee528fe603c5b5df2d7ec5ad8ed66141a7f24a78736aa98795a`. The corresponding permanent private key was not available in this workspace, so v1.2 was not signed with a replacement identity. The unsigned APK is a signing input, not a directly installable update.

## Build checks

- The Gradle wrapper could not download Gradle 8.9 because the remote TLS handshake closed in this sandbox; the equivalent offline manual Android toolchain was used.
- AAPT2 compiled and linked all resources and embedded asset-license texts.
- Java-generated resource bindings and all Kotlin sources compiled against Android API 35.
- D8 produced the Android API 24+ DEX payload. It emitted legacy Kotlin local-variable metadata notices but completed successfully.
- `aapt2 dump badging` confirmed package, version, SDK levels, label, launch activity, and landscape feature.
- `aapt2 dump permissions` reported only the package line and no requested permissions.
- `unzip -t` and `zipalign -c -p -v 4` passed.
- DEX string inspection found no HTTP URL, localhost endpoint, or Internet permission.
- Both embedded Kenney CC0 license texts are present.
- All sampled Utility, corruption, evolution, Supply, imbuement, material, and Cache images are present in the APK.
- All 70 active drawable PNGs are packaged and original to Blockhold Defense: seamless terrain, gate/core landmarks, all five layered base towers, all ten tower-evolution emblems, all eight crafted supplies, all six bound sigils, all three resources, all five traps, all five regular enemies, all five elites, The Overgrowth boss, all seven Utilities, and all six corruption mutations.
- The drawable roster exactly matches the 70 runtime `SpriteCatalog` loads. Fifteen obsolete unreferenced Kenney drawables were removed after the final original replacements were integrated.
- The complete enemy roster—five regular enemies, five elites, and The Overgrowth—now packages anchored 192×64 A/B/C movement strips. The renderer selects square source frames in an A → B → C → B loop at movement-linked cadence, with deterministic per-enemy phase offsets.
- Enemy Gate and Player Core package anchored 192×64 ambient strips. All five traps package 192×64 armed/neutral/triggered strips driven by each placed trap's existing activation pulse; toolbar, status, and cached-trap previews remain on armed frame A.
- The original Forgeworks family still contains exactly 40 scoped images; all repository PNGs pass ImageMagick decoding.
- `ci/verify-forgeworks.sh`, `git diff --check`, shell syntax checks, and Python syntax compilation passed.

## Gameplay structural checks

Static source assertions and compilation confirm:

- Four build categories: Towers, Traps, Utilities, and Cache
- Seven three-level Utilities, a four-Utility active cap, one-per-kind limits, and a second Block Generator allowance
- Exact cached traps preserving kind, normal level, Overcharge, and imbuement
- Paid manual storage, free cached redeployment, bounded capacity, and paid/capacity-checked Reforge recovery
- Eight bounded Supply recipes and contextual between-wave use
- Six one-slot structure imbuements with replacement semantics and deterministic activation counters
- Salvage Parts and Growth Essence production and bounded persistence
- Full Forgeworks Craft, Supplies, and Imbue views
- Version-three bounded saves and legacy aggregate-inventory migration
- Exact Reforge Forge/recovery/Cache previews and Purifier-adjusted cleanse previews
- Existing tower/trap rosters, enemies, perks, synergies, corruption, evolutions, challenges, seeded wave behavior, and endless director remain compiled into the package

## Runtime limitation

No Android emulator or physical Android device is available in the build environment. Installation, actual landscape rendering, touch ergonomics, lifecycle transitions, long-session balance, and vendor-specific behavior remain device-QA items. Static compilation and package checks do not replace a playtest. Production update testing also remains blocked until the permanent v1.0 signing backup is restored.
