// Aureus is a tiny, dependency-free screen recorder for Windows, made by Troy.
//
// Capture uses plain Win32 GDI (BitBlt into a 32-bpp DIB section). The
// default GIF output is streamed to disk one frame at a time, so RAM use
// stays flat no matter how long the recording runs. MP4 output is also
// supported when ffmpeg is on PATH: raw frames are piped straight into x264.
//
// Keys: the hotkey (default F9) starts and stops a recording, ESC quits.
// They are sampled on a goroutine of their own, well away from the capture
// loop, so no keypress can be lost to a slow frame.
package main

import (
	"bufio"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const version = "1.1.0"

// vkEscape is the Win32 virtual-key code for ESC.
const vkEscape = 0x1B

func main() {
	if runtime.GOOS != "windows" {
		fmt.Fprintln(os.Stderr, "Aureus.exe only runs on Windows.")
		os.Exit(1)
	}

	var (
		fpsFlag     = flag.Int("fps", 10, "capture frames per second (1-30)")
		scaleFlag   = flag.Float64("scale", 0, "output scale factor (0 = auto: cap the width at 1920)")
		formatFlag  = flag.String("format", "gif", `output format: "gif" or "mp4" (mp4 needs ffmpeg on PATH)`)
		outFlag     = flag.String("out", "", "output file path (default: screen_<timestamp>.<ext> in the working directory)")
		monitorArg  = flag.String("monitor", "all", `"all" displays or "primary" only`)
		hotkeyArg   = flag.String("hotkey", "F9", "start/stop key: F1..F12 or a hex virtual-key code such as 0x78")
		cursorFlag  = flag.Bool("cursor", true, "include the mouse cursor in the capture")
		keytestFlag = flag.Bool("keytest", false, "print every key Windows receives, then exit (diagnose a hotkey that does nothing)")
		pauseFlag   = flag.Bool("pause", true, "wait for Enter before closing the window after an error")
	)
	flag.Usage = usage
	flag.Parse()
	pauseOnExit = *pauseFlag && runtime.GOOS == "windows"

	if *fpsFlag < 1 || *fpsFlag > 30 {
		fatalf("-fps must be between 1 and 30")
	}
	if *scaleFlag < 0 || *scaleFlag > 1 {
		fatalf("-scale must be 0 (auto) or between 0 and 1")
	}
	format := strings.ToLower(*formatFlag)
	if format != "gif" && format != "mp4" {
		fatalf(`-format must be "gif" or "mp4"`)
	}
	hotkey, err := parseVK(*hotkeyArg)
	if err != nil {
		fatalf("%v", err)
	}
	if hotkey == vkEscape {
		fatalf("-hotkey cannot be ESC — that key quits the app")
	}
	allMonitors := !strings.EqualFold(*monitorArg, "primary")

	setColorEnabled(enableVT() && os.Getenv("NO_COLOR") == "")

	if *keytestFlag {
		runKeyTest(hotkey)
		return
	}

	scr, err := newScreenCapture(allMonitors, *cursorFlag)
	if err != nil {
		fatalf("%v", err)
	}
	defer scr.close()

	scale := *scaleFlag
	if scale == 0 && scr.width() > 1920 {
		scale = 1920 / float64(scr.width())
	}
	if scale == 0 {
		scale = 1
	}
	dstW := clamp(int(float64(scr.width())*scale+0.5), 16, scr.width())
	dstH := clamp(int(float64(scr.height())*scale+0.5), 16, scr.height())
	scr.setOutputSize(dstW, dstH)

	fixedOut := *outFlag
	outDir, err := os.Getwd()
	if err != nil {
		outDir, _ = filepath.Abs(filepath.Dir(os.Args[0]))
	}

	srcDesc := fmt.Sprintf("%d×%d all displays", scr.width(), scr.height())
	if !allMonitors {
		srcDesc = fmt.Sprintf("%d×%d primary display", scr.width(), scr.height())
	}
	outName := "screen_<timestamp>." + format + " in this folder"
	if fixedOut != "" {
		outName = fixedOut
	}
	hotName := keyName(*hotkeyArg)

	fmt.Println()
	fmt.Println("  " + gold("◆ AUREUS") + dim("  v"+version))
	fmt.Println("  " + rule(42))
	info := func(label, value string) {
		fmt.Printf("  %-9s %s\n", gold(label), value)
	}
	info("capture", fmt.Sprintf("%s → %s @ %d fps · %s",
		srcDesc, fmt.Sprintf("%d×%d", dstW, dstH), *fpsFlag, strings.ToUpper(format)))
	info("output", outName)
	info("keys", fmt.Sprintf("%s start/stop · %s quit", gold(hotName), gold("ESC")))
	if format == "mp4" {
		if _, err := exec.LookPath("ffmpeg"); err != nil {
			info("note", amber("ffmpeg not on PATH — MP4 recording will fail"))
			info("", dim("fix: winget install Gyan.FFmpeg, then reopen this window"))
		}
	}
	fmt.Println()
	fmt.Printf("  %s%s\n", dim("ready — press "), gold(hotName))
	if runtime.GOOS == "windows" {
		fmt.Printf("  %s\n", dim("key doing nothing? run: Aureus.exe -keytest"))
	}

	var rec recorder
	recPath := ""
	var frames int
	var started time.Time

	interrupt := make(chan os.Signal, 1)
	signal.Notify(interrupt, os.Interrupt)

	// Keys are read on their own goroutine. Reading them from this loop
	// instead would tie the sampling rate to how long a frame takes to
	// capture and encode, and a press landing inside a slow frame would be
	// invisible: GetAsyncKeyState reports a level, not a queued event.
	keys := startKeyPoller(keyPollInterval, keyDown, hotkey, vkEscape)
	defer keys.stop()

	// A ticker, not a hand-rolled deadline: when a frame runs long the
	// missed ticks collapse into one instead of burst-catching-up.
	frameDur := time.Second / time.Duration(*fpsFlag)
	frameTick := time.NewTicker(frameDur)
	defer frameTick.Stop()

running:
	for {
		select {
		case <-interrupt:
			if rec != nil {
				finishRecording(rec, recPath, frames)
			}
			fmt.Println("\nInterrupted.")
			return

		case ev := <-keys.events:
			switch ev.vk {
			case vkEscape:
				if rec != nil {
					finishRecording(rec, recPath, frames)
				}
				break running

			case hotkey:
				if rec == nil {
					path := fixedOut
					if path == "" {
						path = filepath.Join(outDir, fmt.Sprintf("screen_%s.%s",
							time.Now().Format("20060102_150405"), format))
					}
					r, err := startRecorder(format, path, *fpsFlag, dstW, dstH, scr)
					if err != nil {
						// Keep running: a window that vanishes hides the
						// reason, and the user can just try again.
						fmt.Printf("\n  %s %s: %v\n", red("!"), gold("could not start"), err)
						fmt.Printf("  %s\n", dim("nothing recorded — press "+hotName+" again, or ESC to quit"))
						continue
					}
					rec, recPath, frames = r, path, 0
					started = time.Now()
					fmt.Printf("\n  %s %s %s%s\n",
						pulseDot(0), gold("REC"), dim("→ "), bright(recPath))
				} else {
					finishRecording(rec, recPath, frames)
					rec = nil
					fmt.Printf("\nWaiting for %s to record again (ESC quits)...\n", hotName)
				}
			}

		case <-frameTick.C:
			if rec == nil {
				continue
			}
			if err := rec.frame(scr); err != nil {
				broken, path := rec, recPath
				rec = nil
				broken.close()
				fmt.Printf("\n  %s %s: %v\n", red("!"), bright(path), err)
				fmt.Printf("  %s\n", dim("recording stopped — press "+hotName+" to start a new one"))
				continue
			}
			frames++
			printStatus(rec, frames, started)
		}
	}

	fmt.Println("\n  " + dim("bye"))
}

// keyName normalizes the hotkey for display: F-keys upper-case, hex codes
// shown as typed.
func keyName(s string) string {
	s = strings.TrimSpace(s)
	if strings.HasPrefix(strings.ToUpper(s), "F") {
		return strings.ToUpper(s)
	}
	return s
}

// recorder consumes captured frames and writes the output file.
type recorder interface {
	frame(*screenCapture) error
	close() error
	// bytesWritten reports the bytes produced so far, for the live status
	// line only; the final size is read from disk after close.
	bytesWritten() uint64
}

func startRecorder(format, path string, fps, dstW, dstH int, scr *screenCapture) (recorder, error) {
	switch format {
	case "gif":
		return newGifRecorder(path, fps, dstW, dstH)
	case "mp4":
		return newFFmpegRecorder(path, fps, dstW, dstH, scr.width(), scr.height())
	}
	return nil, fmt.Errorf("unknown format %q", format)
}

func finishRecording(rec recorder, path string, frames int) {
	fmt.Print("\r" + strings.Repeat(" ", 76) + "\r")
	err := rec.close()
	if err != nil {
		fmt.Printf("  %s %s: %v\n", red("!"), bright(path), err)
		return
	}
	size := uint64(0)
	if st, err := os.Stat(path); err == nil {
		size = uint64(st.Size())
	}
	fmt.Printf("  %s %s %s\n",
		gold("●"), bright(path),
		dim(fmt.Sprintf("saved · %d frames · %s", frames, humanSize(size))))
}

func printStatus(rec recorder, frames int, started time.Time) {
	d := time.Since(started)
	extra := ""
	if n := skippedFrames(); n > 0 {
		extra = dim(fmt.Sprintf(" · %d skipped", n))
	}
	fmt.Printf("\r  %s %s %s %s %s%s   ",
		pulseDot(frames),
		gold("REC"),
		bright(fmt.Sprintf("%02d:%02d", int(d.Minutes()), int(d.Seconds())%60)),
		dim("·"),
		dim(fmt.Sprintf("%d frames · %s", frames, humanSize(rec.bytesWritten()))),
		extra)
}

// parseVK converts "F1".."F12" or "0x.." to a Win32 virtual-key code.
func parseVK(s string) (int, error) {
	s = strings.TrimSpace(strings.ToUpper(s))
	if strings.HasPrefix(s, "F") {
		if vk, ok := fKeyVK(s); ok {
			return vk, nil
		}
		return 0, fmt.Errorf(`unsupported key %q (use F1..F12 or a hex code like 0x78)`, s)
	}
	v, err := strconv.ParseUint(s, 0, 32)
	if err != nil {
		return 0, fmt.Errorf(`unsupported key %q (use F1..F12 or a hex code like 0x78)`, s)
	}
	return int(v), nil
}

// fKeyVK maps "F1".."F12" to VK_F1 (0x70) .. VK_F12 (0x7B).
func fKeyVK(s string) (int, bool) {
	if len(s) == 2 && s[1] >= '1' && s[1] <= '9' {
		return 0x70 + int(s[1]-'0') - 1, true
	}
	if len(s) == 3 && s[1] == '1' && s[2] >= '0' && s[2] <= '2' {
		return 0x70 + 10 + int(s[2]-'0') - 1, true
	}
	return 0, false
}

func humanSize(n uint64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := uint64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(n)/float64(div), "KMGTPE"[exp])
}

func clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

// pauseOnExit keeps the console window open after a failure. A double-clicked
// exe closes the instant the process exits, which would take the error
// message with it — the single worst way for this app to fail, since the
// user is left staring at a window that flashed and vanished.
var pauseOnExit = runtime.GOOS == "windows"

func pauseBeforeExit() {
	if !pauseOnExit {
		return
	}
	fmt.Print("\nPress Enter to close this window...")
	bufio.NewReader(os.Stdin).ReadString('\n')
	fmt.Println()
}

func fatalf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "\nerror: "+format+"\n", args...)
	pauseBeforeExit()
	os.Exit(1)
}

func usage() {
	fmt.Fprintf(os.Stderr, `Aureus %s - records the Windows screen to an animated GIF
(no dependencies) or an MP4 (needs ffmpeg on PATH).

Usage:
  Aureus.exe [options]

Options:
`, version)
	flag.PrintDefaults()
	fmt.Fprintf(os.Stderr, `
Keys:
  F9   start / stop recording (change with -hotkey)
  ESC  quit

Examples:
  Aureus.exe
  Aureus.exe -fps 15 -format mp4 -out demo.mp4
  Aureus.exe -monitor primary -scale 0.5
  Aureus.exe -hotkey F8 -out C:\Users\me\Desktop\capture.gif
  Aureus.exe -keytest     (F9 does nothing? see what your key sends)
`)
}
