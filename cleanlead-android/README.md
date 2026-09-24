# CleanLead — Cleaning Duty Tracker for Android

A native Android app for organizing and tracking cleaning duties, built with Kotlin + WebView.

**Package:** `ai.techtroy.cleanlead`
**Version:** 1.0.0 (versionCode 1)
**Min SDK:** Android 8.0 (API 26)
**Target SDK:** API 35
**Architecture:** Kotlin WebView shell → embedded HTML/CSS/JS app with localStorage

## Installable APK

`artifacts/CleanLead-v1.0.0-installable.apk`

| Field | Value |
|---|---|
| SHA-256 | `53be15642e46061de1120ef4cf04fea18f705563a5f293b2b913683310a2990b` |
| Size | 829,164 bytes |
| Signing | v2 + v3 (RSA 4096-bit, PKCS12) |
| Certificate CN | `CN=CleanLead, OU=Cleaning Tracker, O=TechTroyAi` |
| Certificate SHA-256 | `56:8A:96:93:64:14:02:54:F4:3E:ED:80:5D:1A:CB:B0:CB:51:75:1B:E0:27:8A:06:A1:CB:32:F2:FE:E6:B3:A3` |
| Valid until | 2056-09-16 |

## Building

### Prerequisites
- Python 3.10+
- `pip install --break-system-packages jdk4py`
- `npm install -g kotlin-compiler@1.9.25`

### Build command
```bash
cd cleanlead-android
python3 build-apk.py
```

The script:
1. Downloads `android.jar`, `dx.jar`, `apksigner.jar`, `apktool.jar` from GitHub
2. Compiles Kotlin with `kotlinc`
3. Dexes with `dx`
4. Packages resources with `apktool`
5. Zipaligns and signs with `apksigner` (v2 + v3)

### Gradle build (requires Android SDK + Gradle)
```bash
./gradlew assembleRelease
```

## Project Structure
```
cleanlead-android/
├── .signing/                       # Signing key (not committed)
│   ├── cleanlead-release.p12       # PKCS12 keystore
│   ├── release.properties          # Passwords
│   └── cleanlead-cert.pem          # Public certificate
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/ai/techtroy/cleanlead/
│       │   └── MainActivity.kt     # WebView shell
│       ├── assets/
│       │   ├── index.html           # App UI
│       │   ├── style.css            # Dark purple theme
│       │   └── script.js            # App logic + localStorage
│       └── res/
│           ├── values/
│           │   ├── strings.xml
│           │   ├── colors.xml
│           │   └── styles.xml
│           └── mipmap-*/
│               ├── ic_launcher.png
│               ├── ic_launcher_round.png
│               ├── ic_launcher_foreground.png
│               └── ic_launcher_background.png
├── artifacts/
│   └── CleanLead-v1.0.0-installable.apk
├── build-apk.py                    # Offline build script
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradlew
```

## Features
- Create cleaning sessions with area, date, time, instructions
- Add team members and track attendance
- Divide areas into zones and assign tasks
- Kanban task board (Not started → In progress → Completed → Needs review)
- 7 task statuses with color-coded badges
- Leader notes and before/after photos
- Correction requests from team members
- Session summaries with member tables
- CSV export
- Session history
- Sample data with reset button
- Keyboard shortcuts
- Fully offline — no network permission required
