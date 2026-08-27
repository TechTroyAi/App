# Build artifacts

## Download this one (installable v1.2)

**`Blockhold-Defense-v1.2-installable.apk`** — signed sideload build of v1.2 Forgeworks plus Batches 06–07, 15, and **1.3 Phases A–D + C** (projectile flight, impact bursts, dedicated SFX, enemy death dissolve, UI banner/resource pops, board ambient life). Install this on an Android phone.

- Package: `ai.techtroy.blockhold`
- Version: `1.2.0` (`12`) — content includes 1.3 A–D combat/UI FX + Phase C board ambient
- Minimum Android: 7.0 / API 24
- Target Android API: 35
- Orientation: landscape
- Permissions requested: none
- Offline: yes
- Size: ~2.5 MB
- APK SHA-256: `8a81e7bc51b067020accb5c61cb6ad2fc34d239a830b9301a173f97c95a42b19`
- Multi-frame strips: **40** (35 prior + 5 impacts: bolt/frost/cannon/ember/beacon)
- Signing: APK Signature Scheme **v2 + v3** (verified with `apksigner`)
- Signing identity: `CN=Blockhold Defense Debug, OU=Sideload, O=TechTroyAi, L=Davao City, ST=Davao Region, C=PH`
- Certificate SHA-256: `dbcf288d01f8114d8e38a63c6c6fa059521bb49c4ad30a664a9625d0371da55a`

### Install on Android

1. Copy `Blockhold-Defense-v1.2-installable.apk` to the phone (download, USB, Drive, etc.).
2. Open the file and allow **Install unknown apps** for your file manager/browser if prompted.
3. Tap Install.

**Note:** This uses a **sideload/debug key**, not the permanent v1.0 production release key (which is not present in this workspace). If you already have production `ai.techtroy.blockhold` installed from the v1.0 release APK, uninstall that first — Android will reject an update signed with a different key.

## v1.2 Forgeworks signing input

`Blockhold-Defense-v1.2-unsigned.apk` is the verified v1.2 Forgeworks build before signing.

- APK SHA-256: `8a81e7bc51b067020accb5c61cb6ad2fc34d239a830b9301a173f97c95a42b19`
- Signing state: **unsigned** (cannot install until signed)

For a production update compatible with existing v1.0 installs, re-sign the unsigned APK with the permanent Blockhold Defense key. See `docs/VERIFICATION.md` and `docs/SIGNING.md`.

## Last signed production build

`Blockhold-Defense-v1.0-release.apk` remains the last production-key-signed artifact in this checkout.

- Package: `ai.techtroy.blockhold`
- Version: `1.0.0` (`10`)
- APK SHA-256: `a96bec7b30195b6fed49353742b468f747a31db33014973c9c98b0f84715fc30`
- Signing certificate SHA-256: `14d779957005aee528fe603c5b5df2d7ec5ad8ed66141a7f24a78736aa98795a`
- Signing identity: `CN=Blockhold Defense, OU=Game Release, O=TechTroyAi, L=Davao City, ST=Davao Region, C=PH`

The dedicated private signing material is excluded from Git. Restore `Blockhold-Defense-signing-backup.zip` only through the secure workspace attachment flow and keep it outside version control.

## First playable archive

`Blockhold-Defense-v0.1-debug.apk` is retained as the original five-wave vertical slice under the old prototype package `ai.techtroy.app`. It installs independently from Blockhold Defense.
