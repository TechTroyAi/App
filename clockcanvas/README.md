# ClockCanvas

A native Android clock studio: design a clock (type, colours, background, border, layout),
then put that exact design on your home screen as a widget, run it full-screen as a
screensaver with video behind it, or set it as a live wallpaper. Kotlin + framework Views,
no accounts, no network, no ads, nothing leaves the device.

- **Package:** `ai.techtroy.clockcanvas` · **versionName** 1.0.0 (versionCode 1)
- **Requires:** Android 8.0 / API 26+ (see [Why API 26](#why-api-26))
- **Installable APK:** [artifacts/ClockCanvas-v1.0.0-installable.apk](artifacts/ClockCanvas-v1.0.0-installable.apk)
  (4,004,698 bytes, v2+v3 signed — SHA-256 in
  [artifacts/ClockCanvas-v1.0.0-installable.apk.sha256](artifacts/ClockCanvas-v1.0.0-installable.apk.sha256))
- **Published release:** [v1.0.0-clockcanvas](https://github.com/TechTroyAi/App/releases/tag/v1.0.0-clockcanvas)
  (notes, install steps and the byte-stable payload hashes; the binary itself is served from
  the repo, because release-asset upload is blocked from the build sandbox)
- **Design document** (architecture, surfaces, file map, storage, refresh, QA list):
  [../docs/CLOCKCANVAS_DESIGN.md](../docs/CLOCKCANVAS_DESIGN.md)

## What's in v1

| | |
|---|---|
| **Clock styles** | digital · time only · time+date · time+date+day · analog (ticks, optional numerals) · minimal · large-number · split (hour/minute) · vertical (one digit per line) · custom text |
| **Appearance** | font (5 families, variable weights), text scale, weight, letter spacing, line height, alignment, time/date colour, clock opacity, shadow, glow, outline (colour + thickness) |
| **Backgrounds** | solid · 2-stop gradient (linear/radial/sweep, rotatable) · your photo (blur + darken + crop aware) · video poster frame · transparent |
| **Media wall** | a design that shows *only* your photos/videos — no clock on top — stepping through a reel you add in the Background tab |
| **Border** | colour, thickness, corner radius, inner padding |
| **Layout** | gravity (9 positions), orientation (side-by-side vs stacked), padding, tap action (full-screen / editor / nothing) |
| **Responsive** | every design re-derives font size, date visibility, padding and border weight from the *actual* cell size (small / medium / large / wide / tall / huge) |
| **Presets** | 5 sample designs ship on first run; save, duplicate, rename, delete, export, import |
| **Widgets** | unlimited widgets, each with its own design (or a shared one) and its own remembered size |
| **Full-screen** | clock over a real playing video (`VideoView`), immersive, screen-on control, design swipe, tap for controls |
| **Live wallpaper** | the same design painted behind the launcher, with opt-in frame-stepped video motion |

Editor tabs: `Design · Text · Background · Border · Layout · Preview · Widgets`
(the Widgets tab lists your installed widgets and binds this design to any of them).

**Media wall** = `Background → Media wall` → "Show the media only", then *Add photo* /
*Add video* to build a reel and *Rotate the reel* to pick 30 s / 1 min / 5 min / 15 min. The
same design works as a widget, in full screen and as a live wallpaper, and because the reel
position is derived from the time of day rather than stored, all three show the same item at
the same moment. Full screen adds Previous / Next; the wallpaper sleeps exactly until the
next boundary. See `docs/CLOCKCANVAS_DESIGN.md` §2 for why it is built that way.

## Building it

Two independent paths, same source tree, same `app/src/main`.

**Android Studio / Gradle (normal):**

```bash
cd clockcanvas
./gradlew :app:assembleDebug        # or assembleRelease (needs .signing/, see below)
```

**Offline (`kotlinc`, no SDK, no Maven) — the path this app was written and first verified with:**

```bash
bash tools/setup-offline-toolchain.sh   # venv + jdk4py + aapt2 + kotlinc + android.jar/dx/apksigner
python3 tools/build-offline-apk.py      # link → kotlinc → dx → align → sign → verify
```

That produces `artifacts/ClockCanvas-v1.0.0-installable.apk` and prints
per-check verification (signature schemes, dex format/checksums, every referenced type
resolvable, manifest, fonts, alignment). The Compose preview screen
(`app/src/main/java/ai/techtroy/clockcanvas/ui/compose/`) is excluded there — it needs the
Compose compiler plugin — and the builder removes its `<activity>` from the linked manifest,
so an offline APK never advertises a component it cannot start.

### Why API 26

Three things, not preference: notification channels (the opt-in "keep time exact" ticker
needs one), `AppWidgetManager` options reliability across resizes, and the fact that
`dx` — the only dexer available without Android's Maven repositories — refuses Kotlin's
`invokedynamic` helper classes below API 26. Building for API 24 via Gradle would need a
one-line change (`minSdk = 24`) plus D8 desugaring, which AGP does automatically.

### Release signing

`clockcanvas/.signing/` (git-ignored, as everywhere in this repo) holds
`clockcanvas-release.p12` + `release.properties`; Gradle reads them, and
`tools/build-offline-apk.py` refuses to mint a key unless you set
`CLOCKCANVAS_ALLOW_NEW_KEY=1` — because a silently new certificate means the APK cannot
update an installed ClockCanvas.

Current release certificate (record so CI can prove it restored *this* key):

```
SHA-256  edef1c4ea317447a4a6e36553e5629c26393e70638ca71284370a5ceec52541a
Subject  CN=ClockCanvas, OU=Clock Widgets, O=TechTroyAi, L=Cagayan de Oro, ST=Northern Mindanao, C=PH
Key      RSA 4096 · v2 + v3 enabled, v1 disabled (minSdk 26 makes JAR signing redundant)
```

> **Key history.** The certificate above replaced `eb1a34a3…28a951` on 2026-09-25, when the
> sandbox that held `clockcanvas/.signing/` was recycled and the keystore — deliberately not
> in git — went with it. The milestone APK had not been published as a release yet, so no
> installed ClockCanvas is stranded on the old identity; from this APK forward, `edef1c4e…52541a`
> is the one to keep and back up. Any build signed with the earlier key must be uninstalled
> before this one installs.

To install the *shipped* APK you do not need the key; to build updates that replace it you
do. In CI the key comes from the `CLOCKCANVAS_KEYSTORE_BASE64` / `CLOCKCANVAS_STORE_PASSWORD`
secrets; without them the Gradle job still builds and uploads, just unsigned
(`-PskipSigning=true`) with a workflow warning, and the offline builder refuses to run at all
until you explicitly allow it to mint a new identity.

## Fonts and licences

Five families ship in `app/src/main/assets/fonts/`, all SIL Open Font License 1.1, with the
licence texts in `assets/licenses/` (also readable in-app: Settings → Font licences):
Inter, Space Grotesk, Bebas Neue, Playfair Display, Source Code Pro. Four are variable
(`wght`, Inter also `opsz`) and are driven through one `Typeface` per weight. On Android 8.0
and 8.1 `FontVariationSettings` does not exist, so those releases get the font's default
weight plus synthetic bold — heavier or lighter custom weights need Android 9+.
Nothing proprietary or unredistributable is bundled, and no font subset was invented:
the files are the upstream `google/fonts` copies (`tools/fetch_clockcanvas_fonts.py` re-fetches them).

## Honest limitations

- **Widgets cannot play video.** A `RemoteViews` payload has no decode surface, so a widget
  shows a frame from your clip; real playback is the full-screen clock, and the live
  wallpaper gets frame-stepped motion. Do not read this as a bug to fix in the widget.
- **A launcher owns widget size.** `minWidth/minHeight/targetCellWidth` are hints; the
  launcher computes the final bounds (and OEM launchers ignore `resizeMode`). ClockCanvas
  adapts to whatever it gets instead of pretending it can force 16:9.
- **`updatePeriodMillis` is clamped to 15 minutes by Android.** Minute-accurate widgets come
  from a dynamic `ACTION_TIME_TICK` receiver; with "keep time exact" on, a low-importance
  ongoing notification keeps that process alive. Both are opt-in and battery-labelled.
- **Restoring after reboot** relies on the widget host re-binding the `appWidgetId`; the
  binding lives in the app's own preferences, so `onUpdate` re-renders any id it has a
  design for and falls back to the default design for unknown ones (e.g. a device-to-device
  restore hands out new ids).
- **The live wallpaper cannot host a `VideoView`**; see the design document's
  "why the wallpaper decodes frames instead" section for the mechanism and the v2 plan.

## Layout of the module

```
clockcanvas/
  settings.gradle.kts · gradle.properties · build.gradle.kts   Gradle roots (`-Pclockcanvas.offlineOnly` skips the Compose layer)
  app/build.gradle.kts                                          AGP 8.7, Kotlin 2.0.21, Compose optional
  app/src/main/AndroidManifest.xml                              7 activities, 2 services, 1 receiver, 1 provider
  app/src/main/java/ai/techtroy/clockcanvas/                    33 Kotlin files (see design doc for the map)
  app/src/main/res/{values,layout,xml,drawable*,mipmap*}        palette, widget layout, wallpapers xml, icons
  app/src/main/assets/{fonts,licenses}                          5 OFL fonts + texts
  artwork/                                                       icon master + store render
  tools/                                                         toolchain setup, offline builder, font/icon scripts
  artifacts/                                                     signed APK + checksum
```
