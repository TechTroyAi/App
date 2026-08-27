# Build artifacts

## Download this one (installable v1.2)

**`Blockhold-Defense-v1.2-installable.apk`** — signed sideload build of the verified v1.2 Forgeworks game, plus Animation Batch 06 (Ember/Beacon tops), Batch 07 (utilities), Batch 15 Option B (projectile flight strips), and **1.3 Phase A–B (impacts, SFX, enemy death dissolve)** (tower impact bursts). Install this on an Android phone.

- Package: `ai.techtroy.blockhold`
- Version: `1.2.0` (`12`) — content includes early 1.3 combat FX
- Minimum Android: 7.0 / API 24
- Target Android API: 35
- Orientation: landscape
- Permissions requested: none
- Offline: yes
- Size: ~2.5 MB
- APK SHA-256: `ff4a9f81ccc4ae1497d9ebe79a15bc9d7e851132f2460db3ab7835e80d2b1ea7`
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

- APK SHA-256: `ff4a9f81ccc4ae1497d9ebe79a15bc9d7e851132f2460db3ab7835e80d2b1ea7`
- Signing state: **unsigned** (cannot install until signed)

For a production update compatible with existing v1.0 installs, re-sign the unsigned APK with the permanent Blockhold Defense key. See `docs/VERIFICATION.md` and `docs/SIGNING.md`.

## Last signed production build

`Blockhold-Defense-v1.0-release.apk` remains the last production-key-signed artifact in this checkout.

- Package: `ai.techtroy.blockhold`
- Version: `1.0.0` (`10`)
- APK SHA-256: `ff4a9f81ccc4ae1497d9ebe79a15bc9d7e851132f2460db3ab7835e80d2b1ea7`
- Signing certificate SHA-256: `14d779957005aee528fe603c5b5df2d7ec5ad8ed66141a7f24a78736aa98795a`
- Signing identity: `CN=Blockhold Defense, OU=Game Release, O=TechTroyAi, L=Davao City, ST=Davao Region, C=PH`

The dedicated private signing material is excluded from Git. Restore `Blockhold-Defense-signing-backup.zip` only through the secure workspace attachment flow and keep it outside version control.

## First playable archive

`Blockhold-Defense-v0.1-debug.apk` is retained as the original five-wave vertical slice under the old prototype package `ai.techtroy.app`. It installs independently from Blockhold Defense.
