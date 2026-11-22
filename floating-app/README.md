# Floating Fan (prototype)

An independent Android floating-window experiment, not affiliated with Floating Apps.
It does not include or use that app's code. This is **not feature parity** with the original.

## Build

Requires JDK 17 and Android SDK 35. From the repository root:

```sh
./gradlew :floating-app:assembleDebug
```

The installable debug APK is `floating-app/build/outputs/apk/debug/floating-app-debug.apk`. The GitHub Actions Build APK workflow also uploads it as `Floating-Fan-debug`. A debug build is for testing; it is not a production-signed release.

## Use

Open Floating Fan, grant display-over-other-apps access, and choose a mini-app. Drag the title bar, resize using the lower handle, minimize using −, and close using ×. Use the launcher or ongoing notification to open more windows or close them all. Contacts and microphone permissions are optional; Android handles permission grants and denial.

## Limitations

This is an untested early prototype, not a finished clone. It has basic Notes, Calculator, Browser, Video, Music, Image, Paint, Clock/Stopwatch, Flashlight, Recorder, Files, Calendar, Translator, Contacts, Wi-Fi, a guessing game, and System info. Video, Music, and Image use URLs rather than device media pickers; Files lists only private app files; Translator uses a website; Notes has one manually saved note. Paint is not saved. Some websites or media formats may not work in a WebView or device player. Modern Android background microphone restrictions can prevent the recorder from working; Wi-Fi information can be restricted by Android. Test overlays, permissions, media, and service behavior on real devices before relying on this app. Android can terminate the service and windows are not restored automatically.
