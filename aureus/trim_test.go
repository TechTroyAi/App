package main

import (
	"bytes"
	"image"
	"image/color"
	"image/gif"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestParseClipTime(t *testing.T) {
	for _, tc := range []struct {
		in   string
		want time.Duration
		ok   bool
	}{
		{"", 0, true},
		{"2.5", 2500 * time.Millisecond, true},
		{"900ms", 900 * time.Millisecond, true},
		{"1s", time.Second, true},
		{"-1", 0, false},
		{"abc", 0, false},
	} {
		got, err := parseClipTime(tc.in)
		if tc.ok && (err != nil || got != tc.want) {
			t.Errorf("parseClipTime(%q) = %v, %v; want %v", tc.in, got, err, tc.want)
		}
		if !tc.ok && err == nil {
			t.Errorf("parseClipTime(%q) should error", tc.in)
		}
	}
}

// makeGIF writes an animated GIF of n frames, each delay 10cs (100ms).
func makeGIF(t *testing.T, path string, n int) {
	t.Helper()
	g := &gif.GIF{}
	pal := color.Palette{color.RGBA{0, 0, 0, 255}, color.RGBA{255, 255, 255, 255}}
	for i := 0; i < n; i++ {
		p := image.NewPaletted(image.Rect(0, 0, 8, 8), pal)
		for j := range p.Pix {
			p.Pix[j] = uint8((i + j) % 2)
		}
		g.Image = append(g.Image, p)
		g.Delay = append(g.Delay, 10)
		g.Disposal = append(g.Disposal, 0)
	}
	var buf bytes.Buffer
	if err := gif.EncodeAll(&buf, g); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
		t.Fatal(err)
	}
}

func decodeFrames(t *testing.T, path string) *gif.GIF {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	g, err := gif.DecodeAll(bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	return g
}

func TestTrimGIFKeepsWindow(t *testing.T) {
	dir := t.TempDir()
	in := filepath.Join(dir, "a.gif")
	makeGIF(t, in, 6) // frames at 0,100,...,500 ms

	out := filepath.Join(dir, "a.trim.gif")
	if err := trimGIF(in, out, 200*time.Millisecond, 500*time.Millisecond); err != nil {
		t.Fatal(err)
	}
	g := decodeFrames(t, out)
	if len(g.Image) != 3 { // t=200,300,400
		t.Fatalf("kept %d frames, want 3", len(g.Image))
	}
}

func TestTrimGIFOpenEnd(t *testing.T) {
	dir := t.TempDir()
	in := filepath.Join(dir, "b.gif")
	makeGIF(t, in, 6)
	out := filepath.Join(dir, "b.trim.gif")
	if err := trimGIF(in, out, 300*time.Millisecond, 0); err != nil {
		t.Fatal(err)
	}
	g := decodeFrames(t, out)
	if len(g.Image) != 3 { // t=300,400,500
		t.Fatalf("kept %d frames, want 3", len(g.Image))
	}
}

func TestTrimGIFEmptyWindowErrors(t *testing.T) {
	dir := t.TempDir()
	in := filepath.Join(dir, "c.gif")
	makeGIF(t, in, 3) // 0..300ms
	out := filepath.Join(dir, "c.trim.gif")
	if err := trimGIF(in, out, 5*time.Second, 0); err == nil {
		t.Fatal("trimming past the end should error, not write an empty GIF")
	}
}

func TestTrimFileRejectsBadInputs(t *testing.T) {
	dir := t.TempDir()
	bad := filepath.Join(dir, "x.txt")
	os.WriteFile(bad, []byte("hi"), 0o644)
	if _, err := trimFile(bad, "", 0, 0); err == nil {
		t.Fatal("non gif/mp4 should be rejected")
	}
	good := filepath.Join(dir, "d.gif")
	makeGIF(t, good, 3)
	if _, err := trimFile(good, "", 500*time.Millisecond, 100*time.Millisecond); err == nil {
		t.Fatal("end before start should be rejected")
	}
}
