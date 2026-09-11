package main

import (
	"fmt"
	"path/filepath"
	"sync"
	"time"
)

// engine owns the recording pipeline so the console hotkey loop and the web
// studio both drive the same recorder. The frame pump runs on its own
// goroutine; input only ever flips a switch, so capture and control never
// starve each other.
type engine struct {
	mu       sync.Mutex
	scr      *screenCapture
	format   string
	fps      int
	dstW     int
	dstH     int
	recDir   string
	fixedOut string

	rec     recorder
	path    string
	frames  int
	started time.Time
	lastErr string

	stopPump chan struct{}
}

func newEngine(scr *screenCapture, format string, fps, dstW, dstH int, recDir, fixedOut string) *engine {
	e := &engine{
		scr: scr, format: format, fps: fps, dstW: dstW, dstH: dstH,
		recDir: recDir, fixedOut: fixedOut,
		stopPump: make(chan struct{}),
	}
	go e.pumpLoop()
	return e
}

// pumpLoop captures on a ticker.
func (e *engine) pumpLoop() {
	frameDur := time.Second / time.Duration(e.fps)
	t := time.NewTicker(frameDur)
	defer t.Stop()
	for {
		select {
		case <-e.stopPump:
			return
		case <-t.C:
			e.pump()
		}
	}
}

func (e *engine) pump() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.rec == nil {
		return
	}
	if err := e.rec.frame(e.scr); err != nil {
		e.rec.close()
		e.rec = nil
		e.lastErr = fmt.Sprintf("recording failed: %v", err)
		return
	}
	e.frames++
}

// start begins a recording; returns the output path. Starting while already
// recording is a no-op that reports the current path.
func (e *engine) start() (string, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.rec != nil {
		return e.path, nil
	}
	path := e.fixedOut
	if path == "" {
		path = filepath.Join(e.recDir, fmt.Sprintf("screen_%s.%s",
			time.Now().Format("20060102_150405"), e.format))
	}
	r, err := startRecorder(e.format, path, e.fps, e.dstW, e.dstH, e.scr)
	if err != nil {
		return "", err
	}
	e.rec, e.path, e.frames, e.started = r, path, 0, time.Now()
	e.lastErr = ""
	return path, nil
}

// stop finalises the current recording. Returns the path and frame count;
// err is the recorder's close error, if any. Stopping while idle is a no-op.
func (e *engine) stop() (path string, frames int, err error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.rec == nil {
		return "", 0, nil
	}
	r, p, f := e.rec, e.path, e.frames
	e.rec = nil
	e.path = ""
	return p, f, r.close()
}

// status is a point-in-time snapshot for the console and the web studio.
type status struct {
	Recording bool    `json:"recording"`
	Path      string  `json:"path,omitempty"`
	Frames    int     `json:"frames"`
	Bytes     uint64  `json:"bytes"`
	Seconds   float64 `json:"seconds"`
	LastErr   string  `json:"lastErr,omitempty"`
	Format    string  `json:"format"`
	RecDir    string  `json:"recDir"`
	Version   string  `json:"version"`
}

func (e *engine) status() status {
	e.mu.Lock()
	defer e.mu.Unlock()
	s := status{
		Recording: e.rec != nil,
		Path:      e.path,
		Frames:    e.frames,
		LastErr:   e.lastErr,
		Format:    e.format,
		RecDir:    e.recDir,
		Version:   version,
	}
	if e.rec != nil {
		s.Seconds = time.Since(e.started).Seconds()
		s.Bytes = e.rec.bytesWritten()
	}
	return s
}

// close shuts the pump down and finalises any in-flight recording.
func (e *engine) close() (string, int, error) {
	close(e.stopPump)
	return e.stop()
}
