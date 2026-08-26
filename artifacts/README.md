# Installable builds

## Current release

`Blockhold-Defense-v1.0-release.apk` is the complete endless expansion of Blockhold Defense.

- Package: `ai.techtroy.blockhold`
- Version: `1.0.0` (`10`)
- Minimum Android: 7.0 / API 24
- Target Android API: 35
- Orientation: landscape
- Permissions requested: none
- Offline: yes
- APK SHA-256: `838eb6770d9d14b328be78f3f22b729de826c4601b90e31afa41c74c5517b5fc`
- Signing certificate SHA-256: `14d779957005aee528fe603c5b5df2d7ec5ad8ed66141a7f24a78736aa98795a`
- Signature schemes: APK Signature Scheme v2 and v3 verified
- Signing identity: `CN=Blockhold Defense, OU=Game Release, O=TechTroyAi, L=Davao City, ST=Davao Region, C=PH`

The dedicated private signing material is excluded from Git. See `docs/SIGNING.md` and securely back up the private signing bundle before publishing updates.

## First playable archive

`Blockhold-Defense-v0.1-debug.apk` is retained as the original five-wave vertical slice.

- Package: `ai.techtroy.app`
- Version: `0.1.0` (`2`)
- APK SHA-256: `b22b15123d176550c0e52e6093ce7e9c5a84b258eea21c4843a543d7b123fa47`
- Debug certificate SHA-256: `d2fb0a472f5e24a48f06a60da9ad2b2545ee0f0e41fdb82955d68aa726ee6a9f`

Because v1.0 has a new final package ID, it installs independently from the archived prototype rather than replacing it.
