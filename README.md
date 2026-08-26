# TechTroy Android Starter

A native Android starter application written in Kotlin. The repository includes a polished phone-ready screen, the Gradle wrapper, Android build configuration, GitHub Actions, and a compiled APK for immediate testing.

## Install now

Download [`artifacts/TechTroy-debug.apk`](artifacts/TechTroy-debug.apk) to an Android phone and open it.

If Android asks for permission, allow **Install unknown apps** for the browser or file manager you used. This debug APK is intended for direct testing and may trigger the normal Play Protect sideload warning.

### APK details

| Item | Value |
| --- | --- |
| Application ID | `ai.techtroy.app` |
| Version | `1.0.0` (`1`) |
| Minimum Android | Android 7.0 / API 24 |
| Target SDK | API 35 |
| Build type | Debug, installable |
| Permissions | None requested |

The checked-in APK SHA-256 is:

```text
064638822c597a10616834dedebff93353f348a14bbdae6f230127c605d8cfce
```

## Build from source

### Requirements

- JDK 17
- Android SDK Platform 35 and Build Tools
- Internet access for the first Gradle dependency download

Android Studio can open this repository directly. From a terminal:

```bash
./gradlew assembleDebug
```

The resulting APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

To build and refresh the convenient checked-in artifact in one command:

```bash
./scripts/build-apk.sh
```

On Windows, run `gradlew.bat assembleDebug` and copy the resulting APK manually.

### Enable GitHub Actions

The build workflow recipe is stored at `ci/android.yml`. Copy it to `.github/workflows/android.yml` from a GitHub account or App with workflow-write permission to activate automatic APK artifacts on every push.

## What is prepared

- Native Kotlin `Activity` and native Android UI components
- Responsive, scrollable starter interface
- Adaptive and legacy launcher icons
- Android 7.0+ compatibility with API 35 targeting
- Gradle 8.9 wrapper and Android Gradle Plugin configuration
- Debug and release build types
- GitHub Actions recipe ready to upload a fresh debug APK
- No app permissions and no third-party runtime dependencies

## Project map

```text
app/src/main/java/ai/techtroy/app/MainActivity.kt  Main screen and interaction
app/src/main/res/                                 Theme, colors, and app icons
ci/android.yml                                    Ready-to-enable APK workflow
scripts/build-apk.sh                              One-command local build
artifacts/TechTroy-debug.apk                      Installable test build
```

## Production release note

The included APK is debug-signed. Before publishing to Google Play or distributing production updates, create a private release keystore outside Git, configure secure CI secrets, increase `versionCode`, and build a signed release APK or Android App Bundle.
