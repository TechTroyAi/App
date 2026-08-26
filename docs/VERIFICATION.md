# v1.0 verification record

Artifact: `artifacts/Blockhold-Defense-v1.0-release.apk`

## Identity

- Package: `ai.techtroy.blockhold`
- Version: `1.0.0` (`10`)
- Application label: Blockhold Defense
- Launch activity: `ai.techtroy.blockhold.MainActivity`
- Minimum SDK: 24
- Target/compile SDK: 35
- Orientation: landscape
- Requested permissions: none

## Integrity and signing

- APK SHA-256: `838eb6770d9d14b328be78f3f22b729de826c4601b90e31afa41c74c5517b5fc`
- Signing certificate SHA-256: `14d779957005aee528fe603c5b5df2d7ec5ad8ed66141a7f24a78736aa98795a`
- RSA key size: 3072 bits
- APK Signature Scheme v2: verified
- APK Signature Scheme v3: verified
- ZIP archive test: passed with no compressed-data errors

## Build checks

- Android resources compiled and linked with AAPT2.
- Kotlin sources compiled against Android API 35.
- D8 produced the Android dex payload for API 24+.
- APK was zip-aligned before signing.
- Both embedded CC0 license texts and all gameplay sprites are present in the archive.
- Launcher metadata resolves the adaptive API 33 icon family, including monochrome artwork.
- `git diff --check` and shell syntax validation passed.
- Original audio regeneration remains deterministic through `tools/generate_sfx.py`.

## Gameplay structural checks

Static checks confirm:

- Five tower kinds and five upgradeable trap kinds
- Five regular enemies and five named elite enemies
- Elite cadence, sabotage inserts, and recurring ten-wave boss cadence
- Bounded procedural queue size (at most 42 authored spawn entries per generated wave)
- Repeatable post-level-three Overcharge progression
- Between-wave serialization of route, defenses, upgrades, Blocks, core health, score, and wave
- Persistent best score and highest wave

## Runtime limitation

No Android emulator or physical Android device is available in the build environment. Installation, device rendering, touch ergonomics, long-session balance, and vendor-specific lifecycle behavior require device feedback. The static build and package checks above do not replace that playtest.
