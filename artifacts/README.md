> **✅ Use `Blockhold-Defense-v1.4-installable.apk` (built 2026-08-28).** It is the first
> artifact built from the fixed source tree: zipaligned (`classes.dex` page-aligned at 4096),
> signed with APK Signature Scheme v2 + v3, manifest stamped `1.4.0` / `versionCode 14`, and
> passes `scripts/verify-apk.py` with **0 failures**. The v1.2 installable below is the
> crashing apktool build reported by players — kept for reference only.
>
> | File | SHA-256 | Status |
> |---|---|---|
> | `Blockhold-Defense-v1.4-installable.apk` | `7533c6b5967f492b0c33ece43bea36773eac4a4460ad98e55d3deb53f2147522` | **✅ install this** |
> | `Blockhold-Defense-v1.2-installable.apk` | `7e43587c47f54c016a8094950fd77bd56623aeb1438c60d7d0d9edd9180340d4` | crashes on launch / unaligned |
> | `Blockhold-Defense-v1.2-unsigned.apk` | `07b51c08666ce88d1819adb7c0b377e1c031058cd5d9e4309cf8253fe8f5dbe0` | apktool rebuild, do not ship |
> | `Blockhold-Defense-v1.0-release.apk` | `838eb6770d9d14b328be78f3f22b729de826c4601b90e31afa41c74c5517b5fc` | old, different signing key |
> | `Blockhold-Defense-v0.1-debug.apk` | `b22b15123d176550c0e52e6093ce7e9c5a84b258eea21c4843a543d7b123fa47` | debug milestone |
>
> v1.4 signing certificate: `CN=Blockhold Defense, OU=Game Release, O=TechTroyAi,
> L=Davao City, ST=Davao Region, C=PH`, certificate SHA-256
> `7e:4d:cd:c2:59:3d:75:4a:62:fb:8e:33:bd:e0:72:1b:3a:48:56:63:cb:bb:e3:cd:8f:31:6f:d8:14:80:de:f9`.
> This key is new (no v1.0–v1.2 private key exists in the repo), so installing v1.4 needs a
> one-time uninstall of the old build; every later build signed with this keystore then
> updates in place. See [`../docs/APK_V1.4_BUILD.md`](../docs/APK_V1.4_BUILD.md) for the
> reproducible offline build process and [`../docs/APK_V1.2_LAUNCH_DIAGNOSIS.md`](../docs/APK_V1.2_LAUNCH_DIAGNOSIS.md)
> for the root-cause write-up.


## Current (F3 Toolkit — 1.4)
- **Installable:** `Blockhold-Defense-v1.2-installable.apk`
- **SHA-256:** `058f8a7da7e08e565e340467ecc866248850b367b1d19b18258f85b8d8a938db`
- **Contents:** F0 board + F1 blood + F2 named threats + **F3 toolkit** (Ward Beacon, Battle Banner, Essence Still, Trap Lattice; Overcharge Cell, Focus Lens, Snap Spring; Ward/Leech/Surge imbuements)
- Signing: APK Signature Scheme **v2 + v3**

# Build artifacts

## Download this one (installable v1.2)

**`Blockhold-Defense-v1.2-installable.apk`** — signed sideload with 1.3 + C1 + F0 Wide Hold + F1 New blood + **F2 Named threats**.

- Package: `ai.techtroy.blockhold`
- Version: `1.2.0` (`12`) — 1.3 + C1 + F0 + F1 + F2 Named threats
- Minimum Android: 7.0 / API 24
- Target Android API: 35
- Orientation: landscape
- Permissions requested: none
- Offline: yes
- Size: ~2.5 MB
- APK SHA-256: `c7c05ff6037f49d4b99b892fca5e4cc393c96a7452739b91814dbe5dfb1da216`
- Multi-frame strips: **40** (35 prior + 5 impacts: bolt/frost/cannon/ember/beacon)
- Signing: APK Signature Scheme **v2 + v3** (verified with `apksigner`)
- Signing identity: `CN=Blockhold Defense Debug, OU=Sideload, O=TechTroyAi, L=Davao City, ST=Davao Region, C=PH`
- Certificate SHA-256: `dbcf288d01f8114d8e38a63c6c6fa059521bb49c4ad30a664a9625d0371da55a`

### Install on Android

1. Copy `Blockhold-Defense-v1.2-installable.apk` to the phone (download, USB, Drive, etc.).
2. Open the file and allow **Install unknown apps** for your file manager/browser if prompted.
3. Tap Install.

**Note:** This uses a **sideload/debug key**, not the permanent v1.0 production release key (which is not present in this workspace). If you already have production `ai.techtroy.blockhold` installed from the v1.0 release APK, uninstall that first — Android will reject an update signed with a different key.

## F2 Named threats (this build)

- Elites: **Grave Mender**, **Pyre Wight**
- Bosses: **Iron Monarch**, **Spore Sovereign** (plus Overgrowth)
- Wind-up tells + banner stings on ability charge / spawn
- Alias also at `Blockhold-Defense-v1.2-f2-installable.apk`

## v1.2 Forgeworks signing input

`Blockhold-Defense-v1.2-unsigned.apk` is the rebuilt v1.2 Forgeworks + 1.3 content payload before signing (from the latest apktool rebuild).

- APK SHA-256: `ca4356610999b284103674c166ed65d3bcf2981684cc7a17bb0650c11c399b47`
- Signing state: **unsigned** (cannot install until signed)

For a production update compatible with existing v1.0 installs, re-sign the unsigned APK with the permanent Blockhold Defense key. See `docs/VERIFICATION.md` and `docs/SIGNING.md`.

## Last signed production build

`Blockhold-Defense-v1.0-release.apk` remains the last production-key-signed artifact in this checkout.

- Package: `ai.techtroy.blockhold`
- Version: `1.0.0` (`10`)
- APK SHA-256: `838eb6770d9d14b328be78f3f22b729de826c4601b90e31afa41c74c5517b5fc`
- Signing certificate SHA-256: `14d779957005aee528fe603c5b5df2d7ec5ad8ed66141a7f24a78736aa98795a`
- Signing identity: `CN=Blockhold Defense, OU=Game Release, O=TechTroyAi, L=Davao City, ST=Davao Region, C=PH`

The dedicated private signing material is excluded from Git. Restore `Blockhold-Defense-signing-backup.zip` only through the secure workspace attachment flow and keep it outside version control.

## First playable archive

`Blockhold-Defense-v0.1-debug.apk` is retained as the original five-wave vertical slice under the old prototype package `ai.techtroy.app`. It installs independently from Blockhold Defense.
