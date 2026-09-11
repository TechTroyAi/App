package main

import (
	"bytes"
	"image"
	"image/color"
	"image/gif"
	"io"
	"testing"
)

// makeTestRGBA builds a screen-like test image: flat panels, a gradient and
// some sharp "text" edges.
func makeTestRGBA(w, h int, phase int) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			var r, g, b uint8
			switch {
			case x < w/3: // flat panel
				r, g, b = 32+uint8(phase), 96, 160
			case x < 2*w/3: // horizontal gradient
				r = uint8((x*255)/(w/3) + phase)
				g = uint8(y * 255 / h)
				b = 128
			default: // "text": sharp checker
				if (x/4+y/4+phase)%2 == 0 {
					r, g, b = 250, 250, 245
				} else {
					r, g, b = 15, 15, 20
				}
			}
			off := y*img.Stride + x*4
			img.Pix[off+0] = r
			img.Pix[off+1] = g
			img.Pix[off+2] = b
			img.Pix[off+3] = 0xFF
		}
	}
	return img
}

func TestGIFRoundTrip(t *testing.T) {
	w, h := 97, 61
	pal := make(color.Palette, 256)
	for i := range pal {
		pal[i] = color.RGBA{
			R: uint8((i * 7) % 256),
			G: uint8((i * 13) % 256),
			B: uint8((i * 29) % 256),
			A: 0xFF,
		}
	}
	f1 := image.NewPaletted(image.Rect(0, 0, w, h), pal)
	f2 := image.NewPaletted(image.Rect(0, 0, w, h), pal)
	for i := range f1.Pix {
		f1.Pix[i] = uint8(i * 3)
		f2.Pix[i] = uint8(i*5 + 1)
	}

	var buf bytes.Buffer
	if err := writeGIFHeader(&buf, w, h, pal); err != nil {
		t.Fatal(err)
	}
	if err := writeGIFFrame(&buf, f1, 10); err != nil {
		t.Fatal(err)
	}
	if err := writeGIFFrame(&buf, f2, 12); err != nil {
		t.Fatal(err)
	}
	if err := writeGIFTrailer(&buf); err != nil {
		t.Fatal(err)
	}

	g, err := gif.DecodeAll(bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatalf("stdlib could not decode our GIF: %v", err)
	}
	if len(g.Image) != 2 {
		t.Fatalf("decoded %d frames, want 2", len(g.Image))
	}
	if g.Config.Width != w || g.Config.Height != h {
		t.Fatalf("config %dx%d, want %dx%d", g.Config.Width, g.Config.Height, w, h)
	}
	if g.LoopCount != 0 {
		t.Fatalf("loop count = %d, want 0 (forever)", g.LoopCount)
	}
	if g.Delay[0] != 10 || g.Delay[1] != 12 {
		t.Fatalf("delays = %v, want [10 12]", g.Delay)
	}
	for fi, img := range g.Image {
		p := img // g.Image is already []*image.Paletted
		if len(p.Palette) != 256 {
			t.Fatalf("frame %d palette len = %d, want 256", fi, len(p.Palette))
		}
		want := f1.Pix
		if fi == 1 {
			want = f2.Pix
		}
		if !bytes.Equal(p.Pix, want) {
			t.Fatalf("frame %d pixels differ after round trip", fi)
		}
	}
}

func TestQuantizeAndPaletteRoundTrip(t *testing.T) {
	const w, h = 160, 120
	src := makeTestRGBA(w, h, 0)

	pal := buildPalette(src, 256)
	if len(pal) != 256 {
		t.Fatalf("palette length = %d, want 256", len(pal))
	}

	canvas := image.NewPaletted(image.Rect(0, 0, w, h), pal)
	quantize(src, canvas, pal, make(map[uint32]uint8))

	var buf bytes.Buffer
	if err := writeGIFHeader(&buf, w, h, pal); err != nil {
		t.Fatal(err)
	}
	if err := writeGIFFrame(&buf, canvas, 10); err != nil {
		t.Fatal(err)
	}
	if err := writeGIFTrailer(&buf); err != nil {
		t.Fatal(err)
	}
	g, err := gif.Decode(bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatalf("decode: %v", err)
	}

	// Quantized colors must be close to their source: average per-pixel
	// error should be small for this low-color test image.
	q := g.(*image.Paletted)
	var totalErr int64
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			off := y*src.Stride + x*4
			pr, pg, pb := src.Pix[off], src.Pix[off+1], src.Pix[off+2]
			qr, qg, qb, _ := q.Palette[q.Pix[y*q.Stride+x]].RGBA()
			totalErr += int64(abs(int(pr)-int(qr>>8))) +
				int64(abs(int(pg)-int(qg>>8))) +
				int64(abs(int(pb)-int(qb>>8)))
		}
	}
	avg := totalErr / (w * h)
	if avg > 6 {
		t.Fatalf("average per-pixel RGB error %d too high", avg)
	}
}

func TestSecondFrameSamePalette(t *testing.T) {
	const w, h = 80, 50
	pal := buildPalette(makeTestRGBA(w, h, 0), 256)
	f1 := image.NewPaletted(image.Rect(0, 0, w, h), pal)
	f2 := image.NewPaletted(image.Rect(0, 0, w, h), pal)
	quantize(makeTestRGBA(w, h, 0), f1, pal, map[uint32]uint8{})
	quantize(makeTestRGBA(w, h, 3), f2, pal, map[uint32]uint8{})

	var buf bytes.Buffer
	if err := writeGIFHeader(&buf, w, h, pal); err != nil {
		t.Fatal(err)
	}
	if err := writeGIFFrame(&buf, f1, 10); err != nil {
		t.Fatal(err)
	}
	if err := writeGIFFrame(&buf, f2, 10); err != nil {
		t.Fatal(err)
	}
	if err := writeGIFTrailer(&buf); err != nil {
		t.Fatal(err)
	}
	g, err := gif.DecodeAll(bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(g.Image) != 2 {
		t.Fatalf("frames = %d", len(g.Image))
	}
}

func TestSubBlockWriter(t *testing.T) {
	// 1000 bytes must become three full 255-byte sub-blocks, one 235-byte
	// block and the terminator.
	var out bytes.Buffer
	s := &subBlockWriter{w: &out}
	payload := make([]byte, 1000)
	for i := range payload {
		payload[i] = byte(i)
	}
	if _, err := s.Write(payload); err != nil {
		t.Fatal(err)
	}
	if err := s.end(); err != nil {
		t.Fatal(err)
	}

	got := out.Bytes()
	wantLen := 1 + 255 + 1 + 255 + 1 + 255 + 1 + 235 + 1
	if len(got) != wantLen {
		t.Fatalf("encoded length = %d, want %d", len(got), wantLen)
	}
	if got[0] != 255 || got[256] != 255 || got[512] != 255 || got[768] != 235 || got[1004] != 0 {
		t.Fatal("sub-block length prefixes are wrong")
	}
	if !bytes.Equal(got[1:256], payload[:255]) ||
		!bytes.Equal(got[769:1004], payload[765:1000]) {
		t.Fatal("payload corrupted")
	}
}

func TestParseVK(t *testing.T) {
	cases := map[string]int{
		"F1": 0x70, "F9": 0x78, "f9": 0x78, "F12": 0x7B,
		"0x78": 0x78, "0x1B": 0x1B, "27": 27,
	}
	for in, want := range cases {
		got, err := parseVK(in)
		if err != nil || got != want {
			t.Fatalf("parseVK(%q) = %v, %v; want %d", in, got, err, want)
		}
	}
	for _, bad := range []string{"F13", "F0", "xyz", "F"} {
		if _, err := parseVK(bad); err == nil {
			t.Fatalf("parseVK(%q) should fail", bad)
		}
	}
}

func BenchmarkGIFFramePipeline(b *testing.B) {
	// 1920x1080-ish screen-like frame: measures palette-index conversion
	// plus LZW encoding, i.e. the per-frame CPU cost of the GIF path.
	const w, h = 1920, 1080
	src := makeTestRGBA(w, h, 0)
	pal := buildPalette(src, 256)
	canvas := image.NewPaletted(image.Rect(0, 0, w, h), pal)
	cache := make(map[uint32]uint8)

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		quantize(src, canvas, pal, cache)
		if err := writeGIFFrame(io.Discard, canvas, 10); err != nil {
			b.Fatal(err)
		}
	}
}

func abs(x int) int {
	if x < 0 {
		return -x
	}
	return x
}
