package main

import (
	"image"
	"image/color"
	"sort"
)

// buildPalette derives a 256-color palette from a frame using median-cut
// quantization over a pixel sample. The last 16 slots are reserved for a
// grayscale ramp so text-heavy UI stays readable even when the scene changes
// completely later in the recording.
func buildPalette(img *image.RGBA, maxColors int) color.Palette {
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	total := w * h
	step := total / 64000
	if step < 1 {
		step = 1
	}

	pix := img.Pix
	stride := img.Stride
	samples := make([]uint32, 0, total/step+1)
	for i := 0; i < total; i += step {
		off := (i/w)*stride + (i%w)*4
		samples = append(samples,
			uint32(pix[off])<<16|uint32(pix[off+1])<<8|uint32(pix[off+2]))
	}

	// Median cut: repeatedly split the box with the widest channel range at
	// its median sample. Reserve 16 slots for the grayscale ramp.
	boxes := [][]uint32{samples}
	for len(boxes) < maxColors-16 {
		best, bestChan, bestRange := -1, 0, 0
		for i, box := range boxes {
			if len(box) < 2 {
				continue
			}
			ch, rng := widestChannel(box)
			if rng > bestRange {
				best, bestChan, bestRange = i, ch, rng
			}
		}
		if best < 0 || bestRange == 0 {
			break
		}
		box := boxes[best]
		c := uint(bestChan)
		sort.Slice(box, func(a, z int) bool {
			return (box[a]>>(16-8*c))&0xFF < (box[z]>>(16-8*c))&0xFF
		})
		mid := len(box) / 2
		boxes[best] = box[:mid]
		boxes = append(boxes, box[mid:])
	}

	pal := make(color.Palette, 0, maxColors)
	for _, box := range boxes {
		if len(box) == 0 {
			continue
		}
		var rs, gs, bs uint64
		for _, s := range box {
			rs += uint64(s >> 16)
			gs += uint64(s>>8) & 0xFF
			bs += uint64(s) & 0xFF
		}
		n := uint64(len(box))
		pal = append(pal, color.RGBA{
			R: byte((rs + n/2) / n),
			G: byte((gs + n/2) / n),
			B: byte((bs + n/2) / n),
			A: 0xFF,
		})
	}
	for len(pal) < maxColors {
		v := byte(255 * len(pal) / (maxColors - 1))
		pal = append(pal, color.RGBA{R: v, G: v, B: v, A: 0xFF})
	}
	return pal
}

// widestChannel reports which of R/G/B has the largest range in the box.
func widestChannel(box []uint32) (channel, rng int) {
	mins := [3]uint32{0xFFFFFF, 0xFFFFFF, 0xFFFFFF}
	maxs := [3]uint32{}
	for _, s := range box {
		for c := 0; c < 3; c++ {
			v := (s >> uint(16-8*c)) & 0xFF
			if v < mins[c] {
				mins[c] = v
			}
			if v > maxs[c] {
				maxs[c] = v
			}
		}
	}
	best := 0
	for c := 1; c < 3; c++ {
		if maxs[c]-mins[c] > maxs[best]-mins[best] {
			best = c
		}
	}
	return best, int(maxs[best] - mins[best])
}

// quantize writes palette indices for src into dst. Screen content has few
// unique colors, so the memoized color->index map keeps the per-frame cost
// at one map lookup per pixel.
func quantize(src *image.RGBA, dst *image.Paletted, pal color.Palette, cache map[uint32]uint8) {
	sp, dp := src.Pix, dst.Pix
	for i, j := 0, 0; i+3 < len(sp) && j < len(dp); i, j = i+4, j+1 {
		key := uint32(sp[i])<<16 | uint32(sp[i+1])<<8 | uint32(sp[i+2])
		idx, ok := cache[key]
		if !ok {
			idx = uint8(pal.Index(color.RGBA{R: sp[i], G: sp[i+1], B: sp[i+2], A: 0xFF}))
			if len(cache) < 1<<20 { // bound memory on pathological content
				cache[key] = idx
			}
		}
		dp[j] = idx
	}
}
