# Build artifacts

## Download this one (Arena-signed v1.2)

**`Blockhold-Defense-v1.2-arena-signed.apk`** — zipaligned, APK Signature Scheme **v2 + v3**, signed with a **new Arena key** that does not match v1.0 or the old sideload key.

| Item | Value |
| --- | --- |
| Package | `ai.techtroy.blockhold` |
| Version | `1.2.0` (`12`) |
| Size | 3,539,355 bytes |
| APK SHA-256 | `886e169dd791d1232c1d19322c6ec8f0c6c7b2ac1e847f0582a3a8e18e0287d3` |
| Signing | v2 + v3 (no v1) |
| Signing identity | `CN=Blockhold Defense Arena Key, OU=Arena Sideload 2026, O=TechTroyAi, L=Davao City, ST=Davao Region, C=PH` |
| Certificate SHA-256 | `aa7ea2f6c74ca8adaaaaf05e64837ab9a7aa798e1c2dd9fe29a117c75bb0e178` |
| Four-byte ZIP alignment | passed (218/218 uncompressed entries, including `classes.dex` and `resources.arsc`) |
| Public certificate | `docs/blockhold-arena-certificate.txt` |

Re-sign or rebuild with the same key via:

```bash
python3 scripts/sign-apk.py artifacts/Blockhold-Defense-v1.2-unsigned.apk artifacts/Blockhold-Defense-v1.2-arena-signed.apk
python3 scripts/verify-apk.py artifacts/Blockhold-Defense-v1.2-arena-signed.apk
```

### Install on Android (required once)

This APK uses a **new certificate**. Android identifies an app as `(package name + signing certificate)`, so it **cannot** update an existing Blockhold Defense install signed by v1.0 or the old sideload key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / "App not installed").

1. Uninstall **Blockhold Defense** if it is already on the phone (long-press the icon → Uninstall, or Settings → Apps).
2. Copy `Blockhold-Defense-v1.2-arena-signed.apk` to the phone.
3. Open the file and allow **Install unknown apps** for your file manager if prompted.
4. Tap Install.

Every later APK signed with `.signing/blockhold-release.p12` (gitignored private key) will update in place and keep progress.

Uninstalling also clears leftover v1.1 SharedPreferences. That is the launch crash the previous investigation found: `getInt` throwing `ClassCastException` on keys written with a different type.

## Why the unsigned v1.2 was not installable

`Blockhold-Defense-v1.2-unsigned.apk` failed three packaging checks:

1. **No APK Signing Block** — Android 11+ rejects `targetSdk >= 30` APKs that only have (or lack) v1 JAR signatures.
2. **Never zipaligned** — 162 of 218 uncompressed entries, including `classes.dex` at offset 41, were not 4-byte aligned.
3. **Assembled with apktool**, not Gradle, so alignment and signing were skipped.

Those three are fixed in `Blockhold-Defense-v1.2-arena-signed.apk`. The payload (DEX, resources, version `1.2.0`) is the unsigned v1.2 content after align+sign.

The Kotlin 1.7 `maxBy`/`minBy` empty-collection crashes described in `docs/APK_V1.2_LAUNCH_DIAGNOSIS.md` live in that DEX. They are already fixed in source (`GameView.kt` uses `maxByOrNull` / `minByOrNull`; `MainActivity.kt` has a recovery screen). This sandbox cannot reach Maven Central or `dl.google.com` to run Gradle, so that fixed source is not in this APK. Rebuild with Android Studio / `./gradlew assembleRelease` on a machine with JDK 17 to ship those code fixes under the same Arena key.

## Other files in this folder

| File | SHA-256 | Notes |
| --- | --- | --- |
| `Blockhold-Defense-v1.2-unsigned.apk` | `07b51c08666ce88d1819adb7c0b377e1c031058cd5d9e4309cf8253fe8f5dbe0` | Signing input. Do not install. |
| `Blockhold-Defense-v1.2-installable.apk` | `7e43587c47f54c016a8094950fd77bd56623aeb1438c60d7d0d9edd9180340d4` | Old sideload key `095f279b…`, never zipaligned. Prefer the Arena-signed APK. |
| `Blockhold-Defense-v1.0-release.apk` | `838eb6770d9d14b328be78f3f22b729de826c4601b90e31afa41c74c5517b5fc` | Production key `14d77995…` (private key absent). |
| `Blockhold-Defense-v0.1-debug.apk` | `b22b15123d176550c0e52e6093ce7e9c5a84b258eea21c4843a543d7b123fa47` | Prototype package `ai.techtroy.app`. |

Private signing material is **not** in Git. It lives at `.signing/` (ignored). Back up `blockhold-release.p12` and `release.properties` if you want later updates to this install.
