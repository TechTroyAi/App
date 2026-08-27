# Build artifacts

## v1.2 Forgeworks signing input

`Blockhold-Defense-v1.2-unsigned.apk` is the verified v1.2 Forgeworks build.

- Package: `ai.techtroy.blockhold`
- Version: `1.2.0` (`12`)
- Minimum Android: 7.0 / API 24
- Target Android API: 35
- Orientation: landscape
- Permissions requested: none
- Offline: yes
- APK SHA-256: `a429a231b0194f3630f96736178dcbe80ddeecba2f28670f54f995af368ba8e3`
- Signing state: **unsigned**

This APK cannot be installed until it is signed. It must be signed with the permanent v1.0 Blockhold Defense key before distribution as an update. Do not generate a replacement production key; Android would reject it over an existing installation. See `docs/VERIFICATION.md` and `docs/SIGNING.md`.

## Last signed production build

`Blockhold-Defense-v1.0-release.apk` remains the last directly installable artifact in this checkout.

- Package: `ai.techtroy.blockhold`
- Version: `1.0.0` (`10`)
- APK SHA-256: `838eb6770d9d14b328be78f3f22b729de826c4601b90e31afa41c74c5517b5fc`
- Signing certificate SHA-256: `14d779957005aee528fe603c5b5df2d7ec5ad8ed66141a7f24a78736aa98795a`
- Signing identity: `CN=Blockhold Defense, OU=Game Release, O=TechTroyAi, L=Davao City, ST=Davao Region, C=PH`

The dedicated private signing material is excluded from Git. Restore `Blockhold-Defense-signing-backup.zip` only through the secure workspace attachment flow and keep it outside version control.

## First playable archive

`Blockhold-Defense-v0.1-debug.apk` is retained as the original five-wave vertical slice under the old prototype package `ai.techtroy.app`. It installs independently from Blockhold Defense.
