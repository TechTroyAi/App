# ClockCanvas — design and build notes

Companion to [clockcanvas/README.md](../clockcanvas/README.md). This is the part that
explains *why* the code is shaped the way it is: the architecture, the three surfaces and
what each may ask of the platform, the file map, and where per-widget state lives.

---

## 1. Architecture

One module, one process, four layers, no third-party runtime dependencies on the verified
build path. Everything is framework Android (`android.*`, `android.widget.*`) plus the Kotlin
standard library — chosen so the app builds and is *proven to build* in an environment with no
Android SDK and no Maven access, and so a widget update never pays for a Compose recomposition
or a DI graph.

```
 Models.kt            ClockDesign (immutable data class + Builder), ClockStyle, ContentMode,
   "the contract"     ClockGravity, OrientationMode, WidgetBucket, BackgroundKind, GradientKind,
                      BackgroundMedia, WidgetConfiguration, ClockSettings
        │
        ▼
 data/                DesignStore (JSON files per design + per-widget bindings),
                      DesignCodec (stable on-disk keys), AppPrefs (ClockSettings),
                      SamplePresets (6 seed designs)
        │
        ▼
 render/ClockRenderer  ONE renderer, pure Canvas, used by every surface:
   + TextPainter        · drawBackground (solid/gradient/photo, blur, darken, poster override)
   + BitmapUtils        · drawClock (10 styles, fit-to-box text, analog hands)
   + FontRegistry       · drawBorder (colour, thickness, corner radius)
        │              · budgetSize/renderForWidget (pixel and RemoteViews-size caps)
        ▼
 surfaces             widget/ (AppWidgetProvider + RemoteViews + one ImageView)
                      ui/     (Views screens: home, editor, config, full-screen, settings,
                               wallpaper setup, live preview in the editor)
                      wallpaper/ (WallpaperService.Engine, own surface, same renderer)
```

Deliberate choices:

- **A design is one immutable value object.** `ClockDesign` is a data class of ~40 plain
  fields (colours as ARGB ints, enums as names). The editor mutates through
  `ClockDesign.Builder` via `copyWith { }`, so "Save"/"Revert"/"unsaved" is one object
  comparison (`DesignBinding`), and the *same* object serialises to disk, into the export
  file, and into the widget render call. Nothing about a clock is stored twice.
- **Renderer knows nothing about UI.** It takes `(Canvas, wPx, hPx, design, bucket,
  fullscreen, mediaOverride, skipBackground)`. That is what lets the widget bitmap, the
  editor preview, the full-screen clock and the live wallpaper be pixel-identical for the
  same JSON — and it is why a design cannot "look right in the editor and wrong on the
  home screen", the usual failure of widget apps that duplicate their drawing code.
- **Persistence is `SharedPreferences` + JSON files, not Room/DataStore.** Two stores, ~4 KB
  of code, zero migration risk, and it survives the offline build path (no AndroidX).
  Designs are one file each under `files/clockcanvas/designs/<id>.json`, so export is a
  directory read and a corrupt file loses one design, not the library. DataStore/Room buy
  nothing at this data size and both would tie the app to a dependency set the sandbox cannot
  verify; if v2 grows a media library (thumbnails, durations, per-clip crop rects), Room
  becomes the right call and `DesignStore` is the seam.
- **Hand-written JSON (`io/MiniJson.kt`)** for the same reason: ~200 lines, no reflection,
  and the encoder is the format definition. Field names are stable on purpose — renaming one
  is a migration, and `DesignCodec.SCHEMA` exists for exactly that day.
- **`io/LocalFileProvider.kt`** is an in-house `ContentProvider` with the same contract as
  `androidx.core.content.FileProvider` (grants, path-traversal guard, two roots:
  `captures/` and `exports/`). It exists so camera captures and export shares work without
  AndroidX on the offline path.
- **Video is `VideoView`, not Media3.** Media3/ExoPlayer is the right player, but it is an
  AndroidX artifact set the verified build path cannot fetch. `VideoView` covers the one
  requirement (loop a clip behind a clock, full-screen, with an error path that degrades to a
  poster frame). Swapping to Media3 is a single-file change: `FullscreenClockActivity` owns
  all playback; `build.gradle.kts` already has a `buildFeatures.offlineOnly` switch to gate it.
- **Compose is one optional screen, not the app.** `ui/compose/PreviewActivity.kt` is the
  responsive size-comparison screen. The Gradle build compiles it when the Compose plugin is
  applied; the offline builder excludes the package *and* strips its `<activity>` from the
  linked manifest, so no build ever advertises a component it cannot start.

## 2. Widget vs full-screen clock vs live wallpaper

Three surfaces, three different platform owners. Confusing them is the most common way a
clock app ships something that cannot work.

| | **Widget** | **Full-screen clock** | **Live wallpaper** |
|---|---|---|---|
| Who draws it | the *launcher* process, from a `RemoteViews` parcel we build | our `Activity` | our `WallpaperService.Engine`, composited by the system behind the launcher |
| Custom drawing | impossible in-process → we rasterise on our side into one `ImageView` | free (`ClockCanvasView`) | free, into the engine's `SurfaceHolder` |
| Live video | **no** — no decode surface in a RemoteViews payload; we show a poster/frame | **yes** — `VideoView` + `MediaPlayer`, looping, muted, error→poster | partial — frame-stepped 1 fps (`MediaMetadataRetriever`); see below |
| Refresh | system `updatePeriodMillis` (clamped to 15 min) + `ACTION_TIME_TICK` in our process + optional ticker service | our own minute/second timer while resumed | engine timer, slept to the minute boundary (or 500 ms for seconds, 1 s for motion) |
| Input | `PendingIntent` on the root only (tap → full-screen or editor) | everything: tap, swipe, long-press, system UI | none by default (`setTouchEventsEnabled(false)`) |
| Size owner | **the launcher.** `minWidth`/`targetCell*`/`minResize*` are hints; final bounds arrive via `onAppWidgetOptionsChanged` | us | the system (screen + parallax overscan) |
| Survives app kill | yes (launcher keeps the last bitmap; we re-render on update) | n/a | engine restarted by the system |

Two consequences, both honoured in code:

1. **No forced aspect ratio.** The provider declares 1-cell `minWidth/minHeight` with
   `resizeMode="horizontal|vertical"`, then classifies whatever geometry arrives into a
   `WidgetBucket` by dp thresholds (`SMALL` under 120 dp wide or 48 dp tall, `MEDIUM` at
   180 dp, `LARGE` at 250 dp, `HUGE` at 340 dp, plus the `WIDE` wide-and-short and `TALL`
   narrow-and-tall shapes) and derives type size, date visibility, padding and
   border weight from it. A 4x1 wide widget and a 2x2 square of the same design are two
   different, both-sane layouts — that is the "responsive" requirement, done where the size
   is actually known instead of at design time.
2. **Video in a widget is a still.** `bg_video_widget_note` says so in the picker UI rather
   than letting the user pick a clip and wonder why it does not move. Motion is available
   where the platform permits it: full-screen (real playback) and wallpaper (frame-stepped).

**Why the wallpaper decodes frames instead of playing video.** A wallpaper is exactly one
`SurfaceView` the engine owns; `WallpaperService` gives us `onSurfaceCreated(holder)` and a
canvas, not a place to host a second decode surface, and `CanvasEngine`/`lockCanvas` are not
even present in the compile stub this repo builds against. The options were: (a) add a
`WindowManager` child of type `TYPE_WALLPAPER` behind the icons to host a `VideoView` — works
on some OEMs, breaks on others, and fights the launcher for the same layer; (b) decode into a
`SurfaceTexture` and blit through GLES — 30 fps but a private GL pipeline, ~500 lines, and a
battery cliff; (c) `MediaMetadataRetriever.getFrameAtTime()` per tick — one API call, no
permissions, degrades to a poster frame if the clip becomes unreadable. v1 ships (c), with
motion off by default and the cost stated in the toggle's own hint. (b) is the v2 path if
demand is real; the call sites are already isolated in `ClockEngine.backgroundFor()`.

## 3. Files

```
clockcanvas/
  settings.gradle.kts · build.gradle.kts · gradle.properties      Gradle roots, pluginManagement, -PofflineOnly
  app/build.gradle.kts                              AGP 8.7.3 · Kotlin 2.0.21 · minSdk 26 / target 35 ·
                                                    R8 off (see app/proguard-rules.pro) · release signingConfig from .signing/
  app/src/main/AndroidManifest.xml                  no storage permission; CAMERA optional;
                                                    7 activities, 2 services, 1 receiver, 1 provider
  app/src/main/java/ai/techtroy/clockcanvas/
    Models.kt              ClockDesign + Builder + enums + ClockSettings + WidgetConfiguration
    ClockCanvasApp.kt      Application: dynamic ACTION_TIME_TICK/ACTION_TIMEZONE_CHANGED/ACTION_LOCALE_CHANGED
                           receivers → re-render widgets; store/renderer warm-up
    FontRegistry.kt        5 OFL families, one Typeface per weight, variable-axis settings,
                           usable() probe, synthetic-bold fallback
    io/MiniJson.kt         parse + write, objects/arrays/ints/floats/bools/strings
    io/LocalFileProvider.kt  FileProvider-equivalent, roots: captures/ + exports/
    data/DesignCodec.kt    ClockDesign ⇄ JSON (stable keys, SCHEMA=1)
    data/DesignStore.kt    file-per-design library, listeners, import/export, AND the
                           appWidgetId ⇄ design bindings (one prefs store, five keys per id)
    data/AppPrefs.kt       "clockcanvas_settings": ClockSettings (hour format, date format, precise refresh, last design)
    data/SamplePresets.kt  5 seed designs (midnight purple, minimal white, neon digital,
                           study timer, editorial serif) so first run is never an empty list
    media/MediaAccess.kt   Photo Picker intents, persistable grants, poster frames,
                           decode-with-sample-size, camera capture target
    media/MediaHandler.kt  design media resolution + recents + session (editor pick ≠ saved pick)
    render/ClockRenderer.kt background/clock/border, bucket math, bitmap+image caches,
                           pixel budget, clearCaches()
    render/TextPainter.kt  fit-to-width multi-line blocks, letter spacing, outline, glow, shadow
    render/BitmapUtils.kt  box-blur, alpha multiply, budget(width,height,maxPixels)
    widget/ClockCanvasWidgetProvider.kt AppWidgetProvider: update / options / enable / disable / restore
    widget/ClockWidgetUpdater.kt        background render → RemoteViews, PendingIntents, ticker start/stop
    widget/WidgetThreads.kt             one shared HandlerThread (rasterising off main, serialised)
    widget/WidgetTickerService.kt       opt-in foreground minute ticker (low-importance channel)
    ui/ClockActivity.kt                 shared chrome (header, back, scroll body, optional top preview,
                                        keep-screen-on, immersive)
    ui/Ui.kt                            the whole widget kit: cards, chips, toggles, sliders, colour dots,
                                        48dp targets, TalkBack labels, palette + hex parser
    ui/HomeActivity.kt                  dashboard: create, design list (long-press → rename/duplicate/
                                        delete/share/preview), installed-widget count, fullscreen, wallpaper, settings
    ui/EditorActivity.kt                tabbed editor + footer (save/revert/status), widget-config mode,
                                        back-with-unsaved confirm, EditorHost implementation
    ui/EditorSections.kt                the 6 tab bodies as functions of (EditorHost, container)
    ui/DesignBinding.kt                 dirty-tracking mutation of one design
    ui/WidgetConfigActivity.kt          the APPWIDGET_CONFIGURE entry point → EditorActivity in bind mode
    ui/BackgroundPicker.kt              pick photo/video/take photo round-trip, returns (uri, isVideo)
    ui/ClockCanvasView.kt               live View wrapper of the renderer (editor preview, fullscreen)
    ui/FullscreenClockActivity.kt       immersive clock, VideoView layer, controls, swipe designs, wallpaper jump
    ui/SettingsActivity.kt              hour/date format, refresh + battery, export/import/reset, licences, about
    ui/WallpaperSetupActivity.kt        wallpaper content + preview + system-sheet hand-off
    ui/ComposeBridge.kt                 class-name indirection for the optional Compose screen
    ui/compose/PreviewActivity.kt       Gradle-only responsive comparison screen
    wallpaper/ClockWallpaperService.kt  Engine that paints the design (and stepped video) full-bleed
  app/src/main/res/
    values/colors.xml        the black/violet palette, one place
    values/dimens.xml        text sizes, corner radii, widget insets
    values/strings.xml       115 strings (all UI text, incl. every caveat shown to the user)
    values/styles.xml        Theme.ClockCanvas, Theme.ClockCanvas.Fullscreen
    layout/widget_clock.xml  FrameLayout + ImageView (the whole widget)
    layout/widget_preview.xml previewLayout for the picker sheet
    xml/widget_clock.xml     provider info (cells, resize, period, configure activity)
    xml/wallpaper.xml        WallpaperService settings activity + thumbnail
    xml/file_paths.xml       LocalFileProvider roots
    xml/data_extraction_rules.xml  cloud backup disabled without device encryption; nothing synced
    drawable/*.xml           cards, chips, buttons, seekbar, input, stat icon, wallpaper thumb
    drawable-nodpi/ + mipmap-*/  adaptive + legacy launcher icons (AI-generated master in artwork/)
  tools/    setup-offline-toolchain.sh · build-offline-apk.py · fetch_clockcanvas_fonts.py ·
            make_clockcanvas_icons.py
  artwork/  clockcanvas-icon-source.png (1024²) · clockcanvas-store-icon-512.png
  artifacts/ ClockCanvas-v1.0.0-installable.apk (+ .sha256)
```

7,405 lines of Kotlin across 33 files.

## 4. Where per-widget configuration lives

`appWidgetId` is the key, and it is the *only* thing that makes "several widgets with
different configurations" work.

```
files/clockcanvas/designs/<designId>.json      the design library (shared, editable, exportable)
SharedPreferences "clockcanvas_widgets"   (five rows per widget id, `unbind` clears all five)
    widget.<id>       → designId                       (the binding itself)
    configured.<id>   → has the user chosen it yet     (drives the "open config on add" flow)
    w.<id> / h.<id>   → last min width/height in dp from the host (survives process death)
    host.<id>         → launcher package               (diagnostics only)
SharedPreferences "clockcanvas_settings"        app-wide ClockSettings (hour/date format, ticker, last design)
SharedPreferences "clockcanvas_wallpaper"       design, media, isVideo, clockOnly, seconds, motion
```

- **A binding is a reference, not a copy.** Two widgets can share one design (change it once,
  both update) *and* one widget can hold its own design (the config screen creates a copy on
  first edit — `EditorActivity` in bind mode offers "bind existing" vs "duplicate then edit").
- `onAppWidgetOptionsChanged` persists the host's min width/height in dp *before* rendering,
  so the size survives a process death and the next `onUpdate` (which does not carry options)
  still knows the cell it is filling.
- `onDeleted` calls `DesignStore.unbind(id)`, which clears all five rows. That matters
  because launchers *recycle* `appWidgetId`s: a stale row would make a brand-new widget show
  someone else's clock.
- Restoration: `onUpdate` for unknown ids falls back to `ClockSettings.lastDesignId` and then
  to the default sample design, which is also what a fresh install or a device-to-device
  restore (new ids, prefs not transferred) needs. No cloud, no backup agent required;
  `allowBackup` + `data_extraction_rules.xml` additionally carry the prefs and files if the
  user enables backups.
- Widgets never read from the Activity-side caches. The updater resolves
  `designFor(appWidgetId)` from `DesignStore` on its own thread, which is what keeps a widget
  rendering correctly when the app was killed mid-edit (unsaved edits are simply not in the
  store — nothing half-written can reach the home screen).

## 5. Refresh, in full

| mechanism | when it fires | cost |
|---|---|---|
| `android:updatePeriodMillis="60000"` | system-declared, **clamped to 15 min** | free |
| `ACTION_TIME_TICK` receiver in `ClockCanvasApp` | every minute, while our process is alive (it usually is, because a launcher holds widget views) | free-ish |
| `WidgetTickerService` (opt-in "Keep time exact") | a foreground service holding its *own* `ACTION_TIME_TICK` receiver behind a low-importance notification, so the process is neither trimmed nor stopped between broadcasts | a persistent notification and some battery — the Settings row says exactly that, and it is off by default |
| editor / full-screen / wallpaper clocks | their own `Handler` loop, slept to the next `:00` boundary (never a fixed poll, which drifts), shortened to 500 ms when a design shows seconds and the user opted in, 1 s when wallpaper video motion is on | while visible only (`onVisibilityChanged` gates the wallpaper engine) |
| the same receiver's `ACTION_DATE_CHANGED` / `ACTION_TIMEZONE_CHANGED` / `ACTION_LOCALE_CHANGED` / `ACTION_SCREEN_ON` | date, zone and locale-critical re-render (long date names change with locale, not with time), and an immediate catch-up when the screen wakes | free |

Seconds are a per-design toggle (`showSeconds`) that the *widget* deliberately ignores unless
"Keep time exact" is on: repainting a RemoteViews bitmap every second is a battery
conversation the user should opt into, not one the app picks for them. `ClockRenderer` also
caps total pixels (`budgetSize`) and caches images, so a 12 MP photo background does not turn a
widget update into an `OutOfMemoryError` — the failure mode in the QA list.

## 6. Accessibility and theming rules actually enforced

- Palette in one file; violet is an *accent* (selection, primary button, slider progress,
  focus, active nav) — cards stay `#1D1730`/`#251C3D` on `#09070F`, so it reads as a premium
  tool rather than a neon template.
- Every selectable control is a `TextView`-based chip with a `✓`/`○` glyph and
  `contentDescription` carrying the state, so meaning is never colour-only and TalkBack
  announces it (`Ui.describeSelectable`, `Ui.chip`).
- Touch targets ≥ 48 dp (`Ui.MIN_TOUCH`, `setMinimumHeight` on rows, 36 dp swatches inside
  48 dp rows), sliders use `SeekBar` with the value in the label text, and hex entry is a real
  `EditText` with `#AARRGGBB` documented in the dialog.
- Body copy ≥ 12 sp, captions 12.5 sp, nothing at 10 sp; text on the clock is user-coloured
  against a user-chosen background; the opacity control spans 10–100 % and the renderer
  clamps an imported value to a 5 % floor, so a clock can go subtle but cannot be designed
  into literal invisibility.
- No storage permission is requested anywhere; the only permission is optional `CAMERA`
  (`required="false"`), used only if the user taps "Take photo".

## 7. QA checklist → where it is handled

| test | code |
|---|---|
| small / medium / large widget | `ClockRenderer.bucketFor` + `renderForWidget` caps; editor Preview tab renders all buckets from real sizes |
| portrait / landscape | widget: options change → re-render; activities declare `configChanges` for rotation and re-measure; no dimension assumptions in the renderer |
| with / without background image | `BackgroundKind` + `mediaUri == null` path; `transparentBackground` for wallpaper clock-only |
| very long date text | `TextPainter.fitting` shrinks to `minSizePx`, then ellipsizes per line; `EEEE\nd MMMM` preset exists for narrow cells |
| 12h / 24h | `ClockDesign.use24Hour` (null = follow system via `DateFormat.is24HourFormat`) + Settings override + `suppressLeadingZeroHour` |
| multiple widgets, different configs | `appWidgetId`-keyed bindings (§4) |
| restore after reboot | `onUpdate` re-binds from prefs; `MediaAccess.persist` grants survive reboot |
| image + video-thumbnail selection | `BackgroundPicker` + `MediaAccess.posterFor`, `mediaIsVideo` carried in the design |
| memory with large images | `decodeScaled` sample-size maths, `imageCache`(6)/`bitmapCache`(10) with LRU recycle, `budgetSize` cap, `clearCaches()` on trim |
| media deleted / moved | `MediaAccess.canRead` probe → `bg_media_missing` string, gradient fallback. `applyDirect` catches `OutOfMemoryError` and *skips* the update, so the launcher keeps showing the last bitmap it accepted (held in `heldBitmaps`, ≤12) rather than blanking to a broken image |

## 8. V2 candidates (deliberately not in v1)

- GLES/MediaCodec frame pipeline for the wallpaper (§2), or a `WallpaperStyle` pre-baked
  sprite-sheet mode for short loops.
- Media3/ExoPlayer in full-screen (seek, speed, subtitle-free but buffered), replacing
  `VideoView` in `FullscreenClockActivity`.
- DataStore + Room only if the media library grows per-clip metadata; `DesignStore`'s
  file-per-design layout already suits a migration.
- Clock "layers" (a second, independently positioned time zone), which the current model
  supports by making `ClockDesign` a list — a schema change, so it wants its own design pass.
