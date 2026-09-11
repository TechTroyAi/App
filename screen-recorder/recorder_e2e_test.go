package main

import (
	"bytes"
	"image"
	"image/gif"
	"os"
	"path/filepath"
	"testing"
)

// TestRecorderEndToEnd drives the real gifRecorder (as main.go does) with a
// fake "screen" and validates the written file with the stdlib decoder.
func TestRecorderEndToEnd(t *testing.T) {
	const w, h = 160, 120
	out := filepath.Join(t.TempDir(), "e2e.gif")
	scr := &screenCapture{}
	scr.setOutputSize(w, h)

	rec, err := newGifRecorder(out, 10, w, h)
	if err != nil {
		t.Fatal(err)
	}
	for phase := 0; phase < 5; phase++ {
		scr.frame = makeTestRGBA(w, h, phase)
		if err := rec.frame(scr); err != nil {
			t.Fatal(err)
		}
	}
	if err := rec.close(); err != nil {
		t.Fatal(err)
	}

	f, err := os.Open(out)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	g, err := gif.DecodeAll(f)
	if err != nil {
		t.Fatalf("stdlib decode: %v", err)
	}
	if len(g.Image) != 5 {
		t.Fatalf("frames = %d, want 5", len(g.Image))
	}
	if g.Config.Width != w || g.Config.Height != h {
		t.Fatalf("size %dx%d", g.Config.Width, g.Config.Height)
	}
	if g.Delay[0] != 10 {
		t.Fatalf("delay = %d, want 10 (10 fps)", g.Delay[0])
	}
	if rec.bytesWritten() == 0 {
		t.Fatal("bytesWritten is zero")
	}
	// Frames must actually differ (phase shifts the test image).
	if bytes.Equal(g.Image[0].Pix, g.Image[4].Pix) {
		t.Fatal("frames did not change")
	}
}

// TestRecorderCloseNoFrames checks the error path when nothing was captured.
func TestRecorderCloseNoFrames(t *testing.T) {
	rec, err := newGifRecorder(filepath.Join(t.TempDir(), "empty.gif"), 10, 32, 32)
	if err != nil {
		t.Fatal(err)
	}
	if err := rec.close(); err == nil {
		t.Fatal("close without frames should fail")
	}
}

var _ = image.NewRGBA // keep import if helpers change
