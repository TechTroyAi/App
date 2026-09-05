> **📓 Notebook by Troy · with Echoes — v1.0.0 (`1`) is built and retained as `Notebook-by-Troy-v1.0.0.apk`**
> (`SHA-256 eb23c9d5bc4258032c36fdc4ddaaea63ab564178d11be71228a0170928ae8a9a`, 5,784,827 bytes).
> Package `ai.techtroy.notebook`, Android 7.0+ (minSdk 24, targetSdk 35), offline, no accounts.
> Built by the **Notebook APK** GitHub Actions workflow (AGP 8.7.3 / Kotlin 2.0.21,
> `:notebook:assembleRelease`, run [33991897355](https://github.com/TechTroyAi/App/actions/runs/33991897355),
> unsigned SHA-256 `a9c94276d027ba3d5a4a09ea15cc05c1a5a0278f954f3e154c93ff317dc31d04`), then signed locally with
> `apksigner` (v2 + v3, `apksigner verify` → Verifies). Contains 2 dex files / 7,935 classes, 16 activities,
> 3 widgets, reminders, per-note lock, Pages handwriting, PDF annotation, note links + Graph, and Echoes.
>
> **⚠️ Signing key.** Signed with a dedicated RSA 4096 release key
> (`CN=Notebook by Troy, OU=Apps, O=TechTroy AI, L=Cagayan de Oro, ST=Northern Mindanao, C=PH`, valid to 2056),
> certificate SHA-256
> `B2:C9:ED:0A:42:E6:57:29:90:DE:39:72:A4:57:61:35:13:7D:DB:FE:A6:3A:58:97:AA:C0:BE:BC:F2:5C:3C:4F`.
> Keystore lives at `.signing/notebook-release.p12` (gitignored) — back it up; every future Notebook update
> must be signed with this same key or Android will refuse to install over v1.0.0.
>
> | File | SHA-256 | Status |
> |---|---|---|
> | `Notebook-by-Troy-v1.0.0.apk` | `eb23c9d5bc4258032c36fdc4ddaaea63ab564178d11be71228a0170928ae8a9a` | **current installable** — Notebook v1, `1.0.0` (1); signed v2+v3 |
>
> ---
>
> **✅ v1.4.4 (`18`) is now built and retained as `Blockhold-Defense-v1.4.4-installable.apk`**
> (`SHA-256 e1e33918b12d2a30640b0e683ef744927822d0a8d2b0c041987a97a94b99fdea`). It contains the
> full *Fantasy Machinery* UI and HUD overhaul (brass/copper top and bottom rails, ornate skinned buttons,
> 50 HUD/interface sprites, 256 SpriteCatalog drawables), the new **Forge Overdrive** combat boost mechanic
> (+45% fire rate, +25% damage, radial charge meter, activation particle bursts), Block Generators upgrading
> to level 99, 1-minute auto next-wave countdown, and mid-wave structure inspection and interaction.
> Built from source via the offline toolchain (`kotlinc 1.9.25`, `android.jar API 35`, `dx 1.16`,
> `apktool 2.6.0`, `zipalign`, `apksigner 0.9`). `scripts/verify-apk.py` and `scripts/verify-dex-shape.py`
> report **0 failures** (1086 classes, 6 call sites, 256 SpriteCatalog drawables + 13 sounds packaged,
> 101 sprite strips square-framed, v2+v3 signed).
>
> **⚠️ Signing key.** v1.4.4 is signed with a generated RSA 4096 release key
> (`CN=Blockhold Defense, OU=Game Release, O=TechTroyAi, L=Davao City, ST=Davao Region, C=PH`),
> certificate SHA-256
> `79:4b:0c:30:22:50:37:17:7a:28:71:4c:e2:56:90:e7:be:e2:aa:c8:33:56:d1:2b:5a:d9:08:7f:a6:85:ef:bc`.
> Keystore is configured under `.signing/blockhold-release.p12`.
>
> **✅ Previous v1.4.3 (`17`) artifact: `Blockhold-Defense-v1.4.3-installable.apk`**
> (`SHA-256 478648d470c627c929844804163bde28890c7bc12b329368193d324bf9dd0fee`).
>
> | File | SHA-256 | Status |
> |---|---|---|
> | `Blockhold-Defense-v1.4.4-installable.apk` | `e1e33918b12d2a30640b0e683ef744927822d0a8d2b0c041987a97a94b99fdea` | **current installable** — Fantasy Machinery UI + Forge Overdrive + level 99 scaling, `1.4.4` (18); signed v2+v3 |
> | `Blockhold-Defense-v1.4.3-installable.apk` | `478648d470c627c929844804163bde28890c7bc12b329368193d324bf9dd0fee` | superseded — v1.4.3 art overhaul + feature patch, `1.4.3` (17) |
> | `Blockhold-Defense-v1.4.2-installable.apk` | `842e1602e05057b5b58e74a7bacabd962ca8db1dae5dca4c92dc9befe2731eea` | superseded — pressure waves + wave stacking, `1.4.2` (16) |
> | `Blockhold-Defense-v1.4.1-installable.apk` | `789c964ad702a28531386e2e75f610a87c260275a47c53bfb4caf42fba6c7c96` | superseded — canvas restore-underflow fix + launch fix, `1.4.1` (15) |
> | ~~`Blockhold-Defense-v1.4.1-installable.apk`~~ | ~~`6c3807aca6fb593962a627ab78e4b9b99c832f305c898f26e569dd9e53619063`~~ | ❌ **does not open** — superseded, discard if downloaded |
> | `Blockhold-Defense-v1.4-installable.apk` | `7533c6b5967f492b0c33ece43bea36773eac4a4460ad98e55d3deb53f2147522` | crashes on first path block (canvas restore underflow) |
> | `Blockhold-Defense-v1.2-installable.apk` | `7e43587c47f54c016a8094950fd77bd56623aeb1438c60d7d0d9edd9180340d4` | crashes on launch / unaligned |
> | `Blockhold-Defense-v1.2-unsigned.apk` | `07b51c08666ce88d1819adb7c0b377e1c031058cd5d9e4309cf8253fe8f5dbe0` | apktool rebuild, do not ship |
> | `Blockhold-Defense-v1.0-release.apk` | `838eb6770d9d14b328be78f3f22b729de826c4601b90e31afa41c74c5517b5fc` | old, different signing key |
> | `Blockhold-Defense-v0.1-debug.apk` | `b22b15123d176550c0e52e6093ce7e9c5a84b258eea21c4843a543d7b123fa47` | debug milestone |
>
> Signing certificates (`CN=Blockhold Defense, OU=Game Release, O=TechTroyAi,
> L=Davao City, ST=Davao Region, C=PH`):
>
> | Build | Certificate SHA-256 |
> |---|---|
> | v1.4 | `7e:4d:cd:c2:59:3d:75:4a:62:fb:8e:33:bd:e0:72:1b:3a:48:56:63:cb:bb:e3:cd:8f:31:6f:d8:14:80:de:f9` |
> | v1.4.1 (discarded, will not open) | `46:57:88:0e:29:1d:2b:83:cd:97:6a:d9:55:9a:ff:99:4d:d3:a3:a6:a7:f1:21:a1:3d:56:87:2d:f7:ab:4b:d9` |
> | v1.4.1 (key lost — not reusable) | `7d:59:6f:ee:b2:7d:6e:0b:46:02:bf:06:36:ea:55:1a:d7:34:26:8e:e1:16:0c:8e:50:95:0e:e9:9a:77:8f:6d` |
> | v1.4.2 (previous, key not in checkout) | `f4:48:6a:bd:ba:07:f1:16:54:36:58:0f:85:49:f8:7a:92:ce:43:2b:3a:7e:b4:0b:88:3c:2c:22:09:a8:b2:2c` |
> | v1.4.3 (superseded) | `3e:a2:30:cb:7f:ee:6b:b0:dc:38:e3:da:13:4a:85:1b:e7:5a:ae:5c:6c:45:ed:fd:f0:4f:d5:2a:35:e6:8c:fe` |
> | **v1.4.4 (current, backed up in .signing/)** | `79:4b:0c:30:22:50:37:17:7a:28:71:4c:e2:56:90:e7:be:e2:aa:c8:33:56:d1:2b:5a:d9:08:7f:a6:85:ef:bc` |
>
> Each row is a different key pair, so each transition needs a one-time uninstall. No v1.0–v1.2
> private key exists in the repo. See [`../docs/APK_V1.4_BUILD.md`](../docs/APK_V1.4_BUILD.md) for the
> reproducible offline build process and [`../docs/APK_V1.2_LAUNCH_DIAGNOSIS.md`](../docs/APK_V1.2_LAUNCH_DIAGNOSIS.md)
> for the root-cause write-up. Back the signing key up — see
> [`../docs/SIGNING.md`](../docs/SIGNING.md#keeping-the-key-alive).


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
