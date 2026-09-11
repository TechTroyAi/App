# Aureus.exe — tiny dependency-free screen recorder for Windows

`Aureus.exe` records your Windows screen to an **animated GIF** or an
**MP4 video**. It is a single executable with **zero dependencies** —
no installer, no runtime, no DLLs to ship. Press **F9** to start/stop, **ESC**
to quit. The console UI uses a **black & gold minimal theme**: gold accents
on the terminal's own dark background, a thin rule under the banner, and a
softly pulsing gold ● while recording.

Since v1.2.0 Aureus also opens a **black & gold browser studio** on
localhost (a real HTML page, not a terminal) where you can start/stop,
browse every recording with a live preview, **trim** a clip, and delete it —
and you can choose **where recordings are saved** with `-outdir` (default:
your Videos folder). The studio is served from inside the exe itself, so
there is still nothing to install.

The deliverable lives at [`artifacts/Aureus-v1.2.0.exe`](../artifacts/Aureus-v1.2.0.exe)
(see `artifacts/README.md` for its SHA-256), and `.github/workflows/windows-exe.yml`
rebuilds it on a native Windows runner on every change to this folder.

## Quick start

1. Download `artifacts/Aureus-v1.2.0.exe` and put it anywhere (Desktop is fine).
2. Double-click it. A console window opens and the **studio opens in your
   browser** (a `http://127.0.0.1:<port>` page).
3. Press **F9** — or click the big gold **REC** button in the studio — to
   start recording. Press **F9** / click **STOP** again to save.
4. In the studio, hover a recording to **Trim** it (enter start/end seconds)
   or **Delete** it.
5. Press **ESC** in the console to quit.

The output file `screen_YYYYMMDD_HHMMSS.gif` is written to your **Videos**
folder by default — change it with `-outdir D:\Clips`. Windows may show a
*SmartScreen* warning because the exe is signed with a self-signed cert —
click **More info → Run anyway**.

## Console theme

The interface is themed black & gold: `◆ AUREUS` banner in gold
(ANSI 256-color 220) with dim gray rules and labels, a pulsing gold/amber ●
next to `REC` while recording, and red `!` reserved for errors. The
terminal's own background is left untouched, so it adapts to any dark
console. Colors turn off automatically on consoles without VT support
(pre-Windows 10) or when the standard `NO_COLOR` environment variable is set.

## Options

| Flag | Default | Description |
| --- | --- | --- |
| `-fps N` | `10` | Capture frames per second (1–30). |
| `-scale F` | `0` (auto) | Output scale factor; `0` auto-caps the width at 1920 px. `1` = full size. |
| `-format gif\|mp4` | `gif` | GIF needs nothing installed; **MP4 needs [ffmpeg](https://ffmpeg.org) on PATH** (`winget install Gyan.FFmpeg`, then reopen the terminal). |
| `-out PATH` | `screen_<timestamp>.<ext>` | Exact output file path (overrides `-outdir`). |
| `-outdir DIR` | your **Videos** folder | Folder recordings are saved to; created if missing. |
| `-monitor all\|primary` | `all` | Record every display as one big frame, or only the primary one. |
| `-hotkey KEY` | `F9` | Start/stop key: `F1`..`F12` or a hex virtual-key code like `0x78`. |
| `-cursor true\|false` | `true` | Include the mouse cursor (with its true hotspot) in the capture. |
| `-web true\|false` | `true` | Open the black & gold browser studio (record, browse, trim, delete). |
| `-trim FILE` | | Trim an existing `.gif`/`.mp4` and exit — no capture. GIF is trimmed in-process; MP4 trimming needs ffmpeg. |
| `-start T` | `0` | Trim start: seconds (`2.5`) or a duration (`2s500ms`). |
| `-end T` | (to end) | Trim end; blank keeps everything to the end. |
| `-keytest` | `false` | Print every key Windows receives, then exit. Use this when the hotkey seems dead. |
| `-pause true\|false` | `true` | Wait for Enter before closing the window after an error, so the message stays readable. |
| `-h` | | Show help. |

Examples:

```bat
Aureus.exe                                  (studio opens; F9 or REC to record)
Aureus.exe -outdir D:\Clips                 (choose where recordings go)
Aureus.exe -trim rec.gif -start 2 -end 5    (keep seconds 2-5 of a clip)
Aureus.exe -fps 15 -format mp4 -out demo.mp4
Aureus.exe -monitor primary -scale 0.5
Aureus.exe -hotkey F8 -out C:\Users\me\Desktop\capture.gif
Aureus.exe -web=false                       (console + hotkey only, no studio)
Aureus.exe -keytest
```

## How it works (why it's tiny)

- **Capture** is plain Win32 GDI: `BitBlt` of the (virtual) screen into a
  32-bpp top-down DIB section, cursor composited on top via `GetCursorInfo` +
  `DrawIconEx`. No cgo, no external DLLs.
- **Keys** are read with `GetAsyncKeyState` on a goroutine of their own,
  every 5 ms, and handed to the main loop as events. `GetAsyncKeyState`
  reports a level rather than a queued press, so a press only registers if a
  sample lands while the key is down — which means the sampling must be both
  fast and independent of how long a frame takes to encode. Sharing the
  capture loop for both made short taps vanish mid-recording.
- **GIF output is streamed**: frames are median-cut-quantized to a fixed
  256-color palette and LZW-encoded straight to disk as they are captured
  (`compress/lzw`, the same encoder `image/gif` uses). RAM stays flat no
  matter how long you record.
- **MP4 output** pipes raw frames into `ffmpeg` (`libx264`, `veryfast`,
  CRF 23), scaling done by ffmpeg.
- **One engine, three controllers.** Capture runs on its own goroutine
  (`engine.go`); the console hotkey and the web studio both just flip
  start/stop, so neither can starve the other or drop a keypress.
- **Browser studio** (`web.go` + embedded `studio.html`) is a tiny
  **localhost-only** HTTP server (`127.0.0.1:<random port>`) served from
  inside the exe. It exposes `/api/status`, `/api/record/start|stop`,
  `/api/files`, `/api/trim`, `/api/delete`, and serves the recordings folder
  for previews. Nothing is reachable off the machine.
- **Trim** (`trim.go`): GIF is decoded and re-encoded in pure Go, keeping
  only the frames whose cumulative time falls in `[start, end)`. MP4 trim
  shells out to `ffmpeg -ss/-to -c copy`. The same code backs both the
  `-trim` flag and the studio's Trim button.

## Branding (icon, "Made by Troy" metadata, signing)

- **Name:** the product is **Aureus** (black & gold, "made by Troy").
- **Icon:** `artwork/aureus/aureus.ico` — a black rounded square with a gold
  ring and a gold REC dot, matching the console's pulsing ●. It ships at
  16–256 px so Explorer, the title bar and the taskbar all render cleanly.
- **Version info:** the exe's Properties → Details tab shows
  *Company* "Made by Troy", *Product* "Aureus", *Copyright* "© Troy /
  TechTroyAi", plus a DPI-aware manifest. All of it comes from
  `versioninfo.json` + the icon + `aureus.exe.manifest`, compiled into the
  committed `resource.syso` that `go build` picks up automatically. To change
  any of it, edit `versioninfo.json` (or swap the icon) and regenerate:
  `goversioninfo -64 versioninfo.json`.
- **Signing:** the exe is signed with a self-signed code-signing certificate
  (`.signing/aureus-codesign.p12`, `CN=Aureus, O=Made by Troy`, SHA-256
  `62:19:1b:dd:ad:b8:5a:90:a7:e7:23:56:51:c0:8c:16:ec:2a:0c:bd:1c:01:a5:b9:28:94:d4:9a:18:4b:cf:d2`).
  This gives the file a stable named publisher but does **not** clear
  SmartScreen — only a certificate chaining to a public CA does that.

## Building from source

Source is in this folder (`main.go`, `engine.go`, `capture_windows.go`,
`gif.go`, `palette.go`, `ffmpeg.go`, `trim.go`, `web.go` + embedded
`studio.html`, plus the committed `resource.syso` for the icon and version
info). Any machine with [Go](https://go.dev/dl) can build the Windows exe —
no Windows required:

```sh
# from Linux/macOS (cross-compile):
./build.sh                      # -> ../artifacts/Aureus-v<version>.exe

# or manually:
GOOS=windows GOARCH=amd64 go build -ldflags "-s -w" -o Aureus.exe .

# on Windows:
build.bat
```

Tests (GIF writer round-trips, palette/quantizer, sub-block writer, key
parsing, recorder end-to-end, GIF trimming, the recording engine and the web
studio's HTTP API) run on any OS:

```sh
go test ./...
```

## Troubleshooting

- **F9 does nothing at all** — the keypress is not reaching Windows as F9.
  Run `Aureus.exe -keytest` and press F9: if nothing prints, the
  function row is bound to media keys (very common on laptops — hold **Fn**,
  or toggle Fn-lock) or another app has claimed it. If a *different* key name
  prints, point the recorder at a key that works: `-hotkey F8`, or any hex
  virtual-key code such as `-hotkey 0x78`.
- **SmartScreen / antivirus warning** — expected for unsigned exes. *More
  info → Run anyway*. Verify the SHA-256 in `artifacts/README.md`.
- **Black or frozen frames** — Windows blocks GDI capture of the *secure
  desktop* (UAC prompts, the lock screen); those frames are skipped and
  counted in the status line. HDR displays may show washed-out colors (a
  known BitBlt limitation).
- **MP4 says ffmpeg is missing** — install it (`winget install Gyan.FFmpeg`)
  and reopen the terminal, or just use GIF, which needs nothing.
- **Huge GIFs** — animated GIF is inefficient for long/full-screen captures;
  lower `-fps`, use `-scale`, keep clips short, or use `-format mp4`.
