// Aureus is a tiny, dependency-free screen recorder for Windows, made by Troy.
//
// Capture uses plain Win32 GDI (BitBlt into a 32-bpp DIB section). The default
// GIF output is streamed to disk one frame at a time, so RAM stays flat no
// matter how long the recording runs. MP4 output is also supported when ffmpeg
// is on PATH.
//
// Control comes from three places that all drive the same engine: the console
// hotkey (default F9), and the black & gold browser "studio" served on
// localhost (-web), which also lists recordings and trims them. ESC quits.
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

const version = "1.2.0"

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
		outFlag     = flag.String("out", "", "exact output file path (overrides -outdir)")
		outDirFlag  = flag.String("outdir", "", "folder to save recordings in (default: your Videos folder)")
		monitorArg  = flag.String("monitor", "all", `"all" displays or "primary" only`)
		hotkeyArg   = flag.String("hotkey", "F9", "start/stop key: F1..F12 or a hex virtual-key code such as 0x78")
		cursorFlag  = flag.Bool("cursor", true, "include the mouse cursor in the capture")
		keytestFlag = flag.Bool("keytest", false, "print every key Windows receives, then exit (diagnose a hotkey that does nothing)")
		pauseFlag   = flag.Bool("pause", true, "wait for Enter before closing the window after an error")
		webFlag     = flag.Bool("web", true, "open the black & gold browser studio")
		trimFlag    = flag.String("trim", "", "trim an existing recording and exit (.gif or .mp4)")
		trimStart   = flag.String("start", "0", "trim start, seconds or a duration like 2.5s")
		trimEnd     = flag.String("end", "", "trim end, blank = to the end")
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

	// Trim mode needs no capture, so it works anywhere.
	if *trimFlag != "" {
		start, errS := parseClipTime(*trimStart)
		if errS != nil {
			fatalf("%v", errS)
		}
		end, errE := parseClipTime(*trimEnd)
		if errE != nil {
			fatalf("%v", errE)
		}
		out, terr := trimFile(*trimFlag, *outFlag, start, end)
		if terr != nil {
			fatalf("%v", terr)
		}
		fmt.Printf("  %s %s\n", gold("●"), bright(out))
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
	recDir, err := recordDir(*outDirFlag)
	if err != nil {
		fatalf("%v", err)
	}

	srcDesc := fmt.Sprintf("%d×%d all displays", scr.width(), scr.height())
	if !allMonitors {
		srcDesc = fmt.Sprintf("%d×%d primary display", scr.width(), scr.height())
	}
	outName := fmt.Sprintf("screen_<timestamp>.%s in %s", format, recDir)
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

	eng := newEngine(scr, format, *fpsFlag, dstW, dstH, recDir, fixedOut)

	interrupt := make(chan os.Signal, 1)
	signal.Notify(interrupt, os.Interrupt)

	// Keys are read on their own goroutine; the engine runs the frame pump on
	// another. Input only flips a switch, so capture and control never starve
	// each other and no keypress is lost to a slow frame.
	keys := startKeyPoller(keyPollInterval, keyDown, hotkey, vkEscape)
	defer keys.stop()

	if *webFlag {
		if url, werr := serveWeb(eng); werr == nil {
			info("studio", bright(url))
			fmt.Printf("  %s\n", dim("opening the studio in your browser…"))
			openBrowser(url)
		}
	}

	statTick := time.NewTicker(200 * time.Millisecond)
	defer statTick.Stop()

running:
	for {
		select {
		case <-interrupt:
			finishEngine(eng)
			fmt.Println("\nInterrupted.")
			return

		case ev := <-keys.events:
			switch ev.vk {
			case vkEscape:
				finishEngine(eng)
				break running

			case hotkey:
				if eng.status().Recording {
					finishEngine(eng)
					fmt.Printf("\nWaiting for %s to record again (ESC quits)...\n", hotName)
				} else {
					p, serr := eng.start()
					if serr != nil {
						fmt.Printf("\n  %s %s: %v\n", red("!"), gold("could not start"), serr)
						fmt.Printf("  %s\n", dim("nothing recorded — press "+hotName+" again, or ESC to quit"))
					} else {
						fmt.Printf("\n  %s %s %s%s\n",
							pulseDot(0), gold("REC"), dim("→ "), bright(p))
					}
				}
			}

		case <-statTick.C:
			if s := eng.status(); s.Recording {
				printStatusLine(s)
			}
		}
	}

	fmt.Println("\n  " + dim("bye"))
}

// finishEngine stops any in-flight recording and prints the saved line.
func finishEngine(e *engine) {
	p, f, err := e.stop()
	if p == "" {
		return
	}
	fmt.Print("\r" + strings.Repeat(" ", 76) + "\r")
	if err != nil {
		fmt.Printf("  %s %s: %v\n", red("!"), bright(p), err)
		return
	}
	size := uint64(0)
	if st, serr := os.Stat(p); serr == nil {
		size = uint64(st.Size())
	}
	fmt.Printf("  %s %s %s\n",
		gold("●"), bright(p),
		dim(fmt.Sprintf("saved · %d frames · %s", f, humanSize(size))))
}

// printStatusLine redraws the live REC line in the console.
func printStatusLine(s status) {
	extra := ""
	if n := skippedFrames(); n > 0 {
		extra = dim(fmt.Sprintf(" · %d skipped", n))
	}
	fmt.Printf("\r  %s %s %s %s %s%s   ",
		pulseDot(s.Frames),
		gold("REC"),
		bright(fmt.Sprintf("%02d:%02d", int(s.Seconds)/60, int(s.Seconds)%60)),
		dim("·"),
		dim(fmt.Sprintf("%d frames · %s", s.Frames, humanSize(s.Bytes))),
		extra)
}

// recordDir picks the folder recordings are saved to.
//
//   - an explicit -outdir wins and is created if missing; failing to create it
//     is an error, because silently recording somewhere else would lose clips.
//   - otherwise the OS Videos folder (%USERPROFILE%\Videos) is used and
//     created, which is where people expect recordings to land.
//   - if that can't be created, fall back to the current directory.
func recordDir(explicit string) (string, error) {
	if explicit != "" {
		if err := os.MkdirAll(explicit, 0o755); err != nil {
			return "", fmt.Errorf("cannot create -outdir %q: %w", explicit, err)
		}
		return explicit, nil
	}
	if home, err := os.UserHomeDir(); err == nil {
		d := filepath.Join(home, "Videos")
		if os.MkdirAll(d, 0o755) == nil {
			return d, nil
		}
	}
	return os.Getwd()
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
(no dependencies) or an MP4 (needs ffmpeg on PATH), with a black & gold
browser studio for recording, browsing and trimming clips.

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
  Aureus.exe                                  (studio opens; F9 to record)
  Aureus.exe -outdir D:\Clips                 (choose where recordings go)
  Aureus.exe -trim rec.gif -start 2 -end 5    (keep seconds 2-5 of a clip)
  Aureus.exe -format mp4 -out demo.mp4
  Aureus.exe -keytest     (F9 does nothing? see what your key sends)
`)
}
