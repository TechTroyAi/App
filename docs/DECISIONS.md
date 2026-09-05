# Notebook by Troy · with Echoes — Decisions

Architecture and product decisions for the `notebook/` module (`ai.techtroy.notebook`).
Written so a future contributor (or a future Troy) can see *why* things are the way they are.

## Product

| # | Decision | Why |
|---|----------|-----|
| P1 | One app, offline, no accounts, no subscription | The brief. Nothing leaves the phone; there is no server to shut down. |
| P2 | Keep-style **composable notes**: title + body (text *or* checklist *or* handwriting pages) + sketches + attachments + links, one folder, colour, pin, reminder, lock | One mental model instead of note "types". A grocery list can grow a photo and a link without becoming something else. |
| P3 | **Black & Gold** is the default theme; Ivory & Gold and Pure AMOLED are the only alternatives. Material You dropped | Brand identity over system tint. Three deliberate palettes beat 200 accidental ones. |
| P4 | Icon = concept #7, the gold bookmark ribbon, as adaptive + monochrome layers, plus legacy PNGs for API 24/25 | Chosen by Troy; the ribbon reappears as the pin marker and in the easter egg. |
| P5 | **Pages** handwriting is phone-and-finger first; stylus pressure is read when present but nothing depends on it | The target device is a phone. Speed-based thickness gives finger writing character without a stylus. |
| P6 | PDF annotation ships in v1 as an ink layer over `PdfRenderer`; text search is only available on Android 15+ | The platform renderer only exposes `searchText` from API 35. Below that the page counter/thumbnails do the job; no third-party PDF engine (size, licences). |
| P7 | **Echoes** (Echo card, Replay, Time Capsule, About easter egg) are part of v1 | Troy asked for one feature of the assistant's own as a "memory of us". They cost no new permissions and no new data; they read the history the app already keeps. |
| P8 | Permissions are requested at first use, never at launch; no `READ_MEDIA_*` / storage permissions at all | Attachments come in through SAF/`OpenDocument`, the camera through `TakePicture`, so the app never needs to read the user's library. |
| P9 | Trash keeps notes 30 days, then purges on app start | Predictable, no background job needed. |
| P10 | Automated tests are deferred (Troy: "let's just do this later") | Unit test task still runs in CI and is non-blocking. |

## Data

| # | Decision | Why |
|---|----------|-----|
| D1 | Plain **SQLite** via `SQLiteOpenHelper` with explicit migrations (`NotebookDb`), no Room | Zero annotation processing, full control over FTS and migrations, small APK, easy backup (the DB *is* the export). |
| D2 | `checklist_items` is its own table (not JSON in the body) | Reorder, check/uncheck and per-item history are row updates; FTS indexes items individually. |
| D3 | Text saves are debounced (~0.7 s) and each save is one **undo chunk** and one `history` row (`text` kind, payload `titleLen|bodyLen`) | Undo per keystroke is noise; per pause is how people think. The same rows drive Replay. |
| D4 | Sketches, Pages and PDF ink are **vector JSON** (`StrokeDoc`: normalised 0..1 coordinates, per-point pressure + timestamp) plus a rendered PNG/JPG thumbnail | Resolution independent, tiny, replayable, exportable at any DPI. Thumbnails keep the Home grid fast. |
| D5 | Attachments are files under `files/notebook/attachments/<uuid>.<ext>`; the DB stores relative paths | Backup zips the folder; ids can be remapped on import without touching files. |
| D6 | Note links are `[[id:123]]` tokens in the body plus a `note_links` table; the editor renders tokens as gold chips | Renaming a note never breaks links; deleted targets render as grey "(deleted)" chips instead of vanishing. |
| D7 | Search = FTS4 prefix match with a `LIKE` fallback | FTS4 exists on every Android since 4.x; the fallback covers the odd device with a stripped SQLite. |
| D8 | `history(note_id, at, kind, payload)` is append-only and cheap (`create/text/type/check/uncheck/attach/sketch/annotate/ink/link/lock/unlock/capsule/trash/restore`) | Feeds Replay and the Echo picker; trimmed with the note when it's deleted forever. |
| D9 | Time capsules are a `capsule_until` column; the note stays searchable-by-existence but its content is not shown until the date | Simple, reversible, no crypto needed — the point is a promise to yourself, not security. |
| D10 | Locked notes are gated by the app PIN/biometric (`Lock`), hidden from widgets/previews, and `FLAG_SECURE` in the editor. Attachments are **not** encrypted (v2) | Honest scope: this is privacy from shoulder-surfers and the launcher, not from forensics. |

## Build & delivery

| # | Decision | Why |
|---|----------|-----|
| B1 | Real build = **GitHub Actions** (`.github/workflows/notebook.yml`), AGP 8.7 / Kotlin 2.0 / JDK 17, AndroidX + Material 1.12 | The authoring sandbox cannot reach Maven/Google hosts; CI can. |
| B2 | CI publishes the APK as an **Actions artifact and as a git blob**, and posts a commit comment with the blob SHA, checksum and log tails | The sandbox can read blobs and comments through the API but cannot download Actions artifacts. |
| B3 | Local pre-flight = `kotlinc` against `android.jar` + prebuilt AndroidX jars with a generated `R`/`BuildConfig` | Catches ~all compile errors before spending a CI run. |
| B4 | Release APK is signed **locally** with `.signing/notebook-release.p12` (gitignored) using `apksigner` v2+v3; CI signs only if repo secrets exist | Keys never enter the repo or the logs. |
| B5 | The delivered file is `artifacts/Notebook-by-Troy-v1.0.0.apk`; no checkpoint APKs | Troy asked for one finished APK. |
| B6 | No coroutines dependency; a 3-thread executor + main `Handler` (`App.async`) | One less 2 MB dependency for what is a dozen background calls. |
| B7 | `minSdk 24`, `targetSdk 35` | Android 7+ covers every phone Troy is likely to hand this to; 35 keeps it installable on new devices for years. |

## Things deliberately left for v1.1

Split view, handwriting-to-text search, stylus pressure/palm rejection, pinch-zoom font, serif/mono body fonts,
resizable widgets, face unlock, voice transcription, audio trim, attachment encryption.
