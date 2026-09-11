package main

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"strconv"
	"strings"
)

// ffmpegRecorder pipes raw BGRA frames into an ffmpeg process, which scales
// them and encodes H.264/yuv420p MP4. ffmpeg is only needed for MP4 output;
// the GIF path has zero external dependencies.

type ffmpegRecorder struct {
	cmd   *exec.Cmd
	stdin io.WriteCloser
	errB  *bytes.Buffer
	outN  uint64
}

func newFFmpegRecorder(path string, fps, dstW, dstH, srcW, srcH int) (*ffmpegRecorder, error) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		return nil, errors.New("MP4 output needs ffmpeg on PATH - install it with: winget install Gyan.FFmpeg (then reopen the terminal)")
	}
	// yuv420p requires even dimensions.
	ew, eh := dstW&^1, dstH&^1
	args := []string{
		"-y",
		"-f", "rawvideo",
		"-pixel_format", "bgr0",
		"-video_size", fmt.Sprintf("%dx%d", srcW, srcH),
		"-framerate", strconv.Itoa(fps),
		"-i", "pipe:0",
		"-vf", fmt.Sprintf("scale=%d:%d", ew, eh),
		"-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
		"-pix_fmt", "yuv420p",
		"-movflags", "+faststart",
		path,
	}
	cmd := exec.Command("ffmpeg", args...)
	errB := &bytes.Buffer{}
	cmd.Stderr = errB
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("starting ffmpeg: %w", err)
	}
	return &ffmpegRecorder{cmd: cmd, stdin: stdin, errB: errB}, nil
}

func (r *ffmpegRecorder) frame(scr *screenCapture) error {
	buf, ok := scr.grabBGRA()
	if !ok {
		return nil // e.g. the secure desktop behind a UAC prompt briefly blocked capture
	}
	n, err := r.stdin.Write(buf)
	r.outN += uint64(n)
	if err != nil {
		if msg := stderrTail(r.errB); msg != "" {
			return fmt.Errorf("ffmpeg: %w\n%s", err, msg)
		}
	}
	return err
}

func (r *ffmpegRecorder) close() error {
	r.stdin.Close()
	if err := r.cmd.Wait(); err != nil {
		if msg := stderrTail(r.errB); msg != "" {
			return fmt.Errorf("ffmpeg failed: %w\n%s", err, msg)
		}
		return fmt.Errorf("ffmpeg failed: %w", err)
	}
	return nil
}

func (r *ffmpegRecorder) bytesWritten() uint64 { return r.outN }

func stderrTail(buf *bytes.Buffer) string {
	msg := strings.TrimSpace(buf.String())
	if len(msg) > 500 {
		msg = msg[len(msg)-500:]
	}
	return msg
}
