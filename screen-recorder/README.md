# ScreenRecorder.exe — tiny dependency-free screen recorder for Windows

`ScreenRecorder.exe` records your Windows screen to an **animated GIF** or an
**MP4 video**. It is a single ~2 MB executable with **zero dependencies** —
no installer, no runtime, no DLLs to ship. Press **F9** to start/stop, **ESC**
to quit.

The deliverable lives at [`artifacts/ScreenRecorder-v1.0.0.exe`](../artifacts/ScreenRecorder-v1.0.0.exe)
(see `artifacts/README.md` for its SHA-256), and `.github/workflows/windows-exe.yml`
rebuilds it on a native Windows runner on every change to this folder.

## Quick start

1. Download `artifacts/ScreenRecorder-v1.0.0.exe` and put it anywhere (Desktop is fine).
2. Double-click it. A console window opens showing the capture region and keys.
3. Press **F9** — recording starts immediately. Press **F9** again to stop and save.
4. Press **ESC** to quit.

The output file `screen_YYYYMMDD_HHMMSS.gif` is written to the folder the
console is in. Windows may show a *SmartScreen* warning because the exe is
unsigned — click **More info → Run anyway**.

## Options

| Flag | Default | Description |
| --- | --- | --- |
| `-fps N` | `10` | Capture frames per second (1–30). |
| `-scale F` | `0` (auto) | Output scale factor; `0` auto-caps the width at 1920 px. `1` = full size. |
| `-format gif\|mp4` | `gif` | GIF needs nothing installed; **MP4 needs [ffmpeg](https://ffmpeg.org) on PATH** (`winget install Gyan.FFmpeg`, then reopen the terminal). |
| `-out PATH` | `screen_<timestamp>.<ext>` | Where to write the recording. |
| `-monitor all\|primary` | `all` | Record every display as one big frame, or only the primary one. |
| `-hotkey KEY` | `F9` | Start/stop key: `F1`..`F12` or a hex virtual-key code like `0x78`. |
| `-cursor true\|false` | `true` | Include the mouse cursor (with its true hotspot) in the capture. |
| `-h` | | Show help. |

Examples:

```bat
ScreenRecorder.exe
ScreenRecorder.exe -fps 15 -format mp4 -out demo.mp4
ScreenRecorder.exe -monitor primary -scale 0.5
ScreenRecorder.exe -hotkey F8 -out C:\Users\me\Desktop\capture.gif
```

## How it works (why it's tiny)

- **Capture** is plain Win32 GDI: `BitBlt` of the (virtual) screen into a
  32-bpp top-down DIB section, cursor composited on top via `GetCursorInfo` +
  `DrawIconEx`. Hotkeys are polled with `GetAsyncKeyState`. No cgo, no
  external DLLs.
- **GIF output is streamed**: frames are median-cut-quantized to a fixed
  256-color palette and LZW-encoded straight to disk as they are captured
  (`compress/lzw`, the same encoder `image/gif` uses). RAM stays flat no
  matter how long you record.
- **MP4 output** pipes raw frames into `ffmpeg` (`libx264`, `veryfast`,
  CRF 23), scaling done by ffmpeg.

## Building from source

Source is in this folder (`main.go`, `capture_windows.go`, `gif.go`,
`palette.go`, `ffmpeg.go`). Any machine with [Go](https://go.dev/dl) can build
the Windows exe — no Windows required:

```sh
# from Linux/macOS (cross-compile):
./build.sh                      # -> ../artifacts/ScreenRecorder-v<version>.exe

# or manually:
GOOS=windows GOARCH=amd64 go build -ldflags "-s -w" -o ScreenRecorder.exe .

# on Windows:
build.bat
```

Tests (GIF writer round-trips, palette/quantizer, sub-block writer, key
parsing, recorder end-to-end) run on any OS:

```sh
go test ./...
```

## Troubleshooting

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
