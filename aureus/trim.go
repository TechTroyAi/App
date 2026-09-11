package main

import (
	"bytes"
	"fmt"
	"image/gif"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// Trimming. GIF is trimmed in pure Go (decode all frames, keep the ones whose
// timestamp falls inside [start,end), re-encode). MP4 needs ffmpeg and is
// stream-copied, so it is fast and lossless.

// parseClipTime accepts a Go duration ("2.5s", "900ms", "1m3s") or a bare
// number of seconds ("2.5").
func parseClipTime(s string) (time.Duration, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, nil
	}
	if d, err := time.ParseDuration(s); err == nil {
		return d, nil
	}
	f, err := strconv.ParseFloat(s, 64)
	if err != nil || f < 0 {
		return 0, fmt.Errorf("bad time %q (use e.g. 2.5, 2.5s or 900ms)", s)
	}
	return time.Duration(f * float64(time.Second)), nil
}

// trimFile dispatches on the extension and writes the trimmed clip to out.
// If out is empty a name like <base>.trim.<ext> is derived next to the input.
func trimFile(in, out string, start, end time.Duration) (string, error) {
	if end != 0 && end <= start {
		return "", fmt.Errorf("end (%v) must be after start (%v)", end, start)
	}
	if out == "" {
		ext := fileExt(in)
		base := strings.TrimSuffix(in, ext)
		out = base + ".trim" + ext
	}
	switch strings.ToLower(fileExt(in)) {
	case ".gif":
		return out, trimGIF(in, out, start, end)
	case ".mp4":
		return out, trimMP4(in, out, start, end)
	default:
		return "", fmt.Errorf("can only trim .gif or .mp4, not %q", in)
	}
}

func fileExt(p string) string {
	if i := strings.LastIndex(p, "."); i >= 0 {
		return p[i:]
	}
	return ""
}

// gifFrameTimes returns each frame's start time. GIF delays are in
// hundredths of a second; a delay of 0 is treated as 10 cs (100 ms), which is
// what most viewers assume.
func gifFrameTimes(g *gif.GIF) []time.Duration {
	t := make([]time.Duration, len(g.Image))
	acc := time.Duration(0)
	for i, d := range g.Delay {
		t[i] = acc
		cs := d
		if cs <= 0 {
			cs = 10
		}
		acc += time.Duration(cs) * 10 * time.Millisecond
	}
	return t
}

// trimGIF keeps the frames whose timestamp lies in [start,end). end==0 means
// "to the end of the clip".
func trimGIF(in, out string, start, end time.Duration) error {
	data, err := os.ReadFile(in)
	if err != nil {
		return err
	}
	g, err := gif.DecodeAll(bytes.NewReader(data))
	if err != nil {
		return fmt.Errorf("not a readable GIF: %w", err)
	}
	times := gifFrameTimes(g)

	out_g := &gif.GIF{}
	out_g.Config = g.Config
	out_g.LoopCount = g.LoopCount
	out_g.Disposal = make([]byte, 0, len(g.Image))
	kept := 0
	for i, img := range g.Image {
		t := times[i]
		if t < start {
			continue
		}
		if end != 0 && t >= end {
			continue
		}
		out_g.Image = append(out_g.Image, img)
		out_g.Delay = append(out_g.Delay, g.Delay[i])
		if i < len(g.Disposal) {
			out_g.Disposal = append(out_g.Disposal, g.Disposal[i])
		} else {
			out_g.Disposal = append(out_g.Disposal, 0)
		}
		kept++
	}
	if kept == 0 {
		return fmt.Errorf("nothing left after trimming %v..%v (clip is %v long)",
			start, end, clipLength(times, g.Delay))
	}

	var buf bytes.Buffer
	if err := gif.EncodeAll(&buf, out_g); err != nil {
		return err
	}
	return os.WriteFile(out, buf.Bytes(), 0o644)
}

func clipLength(times []time.Duration, delay []int) time.Duration {
	if len(times) == 0 {
		return 0
	}
	cs := delay[len(delay)-1]
	if cs <= 0 {
		cs = 10
	}
	return times[len(times)-1] + time.Duration(cs)*10*time.Millisecond
}

// trimMP4 stream-copies the [start,end) segment with ffmpeg (no re-encode).
func trimMP4(in, out string, start, end time.Duration) error {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		return fmt.Errorf("MP4 trimming needs ffmpeg on PATH (winget install Gyan.FFmpeg); GIF trimming works without it")
	}
	args := []string{"-y", "-ss", secStr(start), "-i", in}
	if end != 0 {
		args = append(args, "-to", secStr(end-start))
	}
	args = append(args, "-c", "copy", "-movflags", "+faststart", out)
	cmd := exec.Command("ffmpeg", args...)
	var eb bytes.Buffer
	cmd.Stderr = &eb
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("ffmpeg: %v\n%s", err, stderrTail(&eb))
	}
	return nil
}

func secStr(d time.Duration) string {
	return strconv.FormatFloat(d.Seconds(), 'f', 3, 64)
}
