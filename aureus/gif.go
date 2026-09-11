package main

import (
	"bufio"
	"compress/lzw"
	"encoding/binary"
	"errors"
	"image"
	"image/color"
	"io"
	"os"
)

// This file contains a minimal *streaming* GIF89a writer. The standard
// library's image/gif.EncodeAll needs every frame in memory at once, which
// is a problem for long recordings, so frames are LZW-encoded and appended
// to the file as they are captured. The LZW stream itself comes from
// compress/lzw (LSB order, 8-bit literals) - the exact encoder image/gif
// itself uses - so output is fully standard GIF89a.

func put16(b []byte, v int) { binary.LittleEndian.PutUint16(b, uint16(v)) }

// writeGIFHeader writes the GIF header, a 256-entry global color table and
// the NETSCAPE2.0 "loop forever" extension.
func writeGIFHeader(w io.Writer, width, height int, pal color.Palette) error {
	buf := make([]byte, 0, 13+768+18)
	buf = append(buf, 'G', 'I', 'F', '8', '9', 'a')
	var desc [7]byte
	put16(desc[0:], width)
	put16(desc[2:], height)
	desc[4] = 0xF7 // global color table present, 8-bit color res, 2^(7+1)=256 entries
	// desc[5] (background index) and desc[6] (pixel aspect) stay 0.
	buf = append(buf, desc[:]...)

	table := make([]byte, 768)
	for i := 0; i < 256 && i < len(pal); i++ {
		r, g, b, _ := pal[i].RGBA()
		table[i*3+0] = byte(r >> 8)
		table[i*3+1] = byte(g >> 8)
		table[i*3+2] = byte(b >> 8)
	}
	buf = append(buf, table...)

	// Application extension: NETSCAPE2.0, loop count 0 = forever.
	buf = append(buf, 0x21, 0xFF, 0x0B,
		'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0',
		0x03, 0x01, 0x00, 0x00, 0x00)

	_, err := w.Write(buf)
	return err
}

// writeGIFFrame appends one paletted frame with the given delay
// (in hundredths of a second).
func writeGIFFrame(w io.Writer, p *image.Paletted, delayCentisec int) error {
	if delayCentisec < 0 {
		delayCentisec = 0
	}
	if delayCentisec > 0xFFFF {
		delayCentisec = 0xFFFF
	}

	// Graphic Control Extension: disposal method 1 ("do not dispose"),
	// no transparency.
	buf := make([]byte, 0, 8+10)
	buf = append(buf, 0x21, 0xF9, 0x04, 0x04,
		byte(delayCentisec), byte(delayCentisec>>8), 0x00, 0x00)

	// Image Descriptor: separator, left/top/width/height, packed flags.
	// Full frame at (0,0), no local color table, not interlaced.
	r := p.Bounds()
	desc := [10]byte{0x2C}
	put16(desc[1:], r.Min.X)
	put16(desc[3:], r.Min.Y)
	put16(desc[5:], r.Dx())
	put16(desc[7:], r.Dy())
	// desc[9] = 0x00 (packed)
	buf = append(buf, desc[:]...)

	if _, err := w.Write(buf); err != nil {
		return err
	}

	// LZW minimum code size for a 256-entry table is 8.
	if _, err := w.Write([]byte{0x08}); err != nil {
		return err
	}

	sb := &subBlockWriter{w: w}
	lw := lzw.NewWriter(sb, lzw.LSB, 8)
	if _, err := lw.Write(p.Pix); err != nil {
		return err
	}
	if err := lw.Close(); err != nil {
		return err
	}
	return sb.end()
}

func writeGIFTrailer(w io.Writer) error {
	_, err := w.Write([]byte{0x3B})
	return err
}

// subBlockWriter splits an LZW stream into GIF data sub-blocks: chunks of at
// most 255 bytes, each prefixed with its length, terminated by a 0x00 block
// from end().
type subBlockWriter struct {
	w   io.Writer
	buf [255]byte
	n   int
}

func (s *subBlockWriter) Write(p []byte) (int, error) {
	total := 0
	for len(p) > 0 {
		n := copy(s.buf[s.n:], p)
		s.n += n
		p = p[n:]
		total += n
		if s.n == len(s.buf) {
			if err := s.flushBlock(); err != nil {
				return total, err
			}
		}
	}
	return total, nil
}

func (s *subBlockWriter) flushBlock() error {
	if s.n == 0 {
		return nil
	}
	if _, err := s.w.Write([]byte{byte(s.n)}); err != nil {
		return err
	}
	if _, err := s.w.Write(s.buf[:s.n]); err != nil {
		return err
	}
	s.n = 0
	return nil
}

func (s *subBlockWriter) end() error {
	if err := s.flushBlock(); err != nil {
		return err
	}
	_, err := s.w.Write([]byte{0x00})
	return err
}

// countingWriter counts bytes that pass through (for the live status line).
type countingWriter struct {
	w io.Writer
	n uint64
}

func (c *countingWriter) Write(p []byte) (int, error) {
	n, err := c.w.Write(p)
	c.n += uint64(n)
	return n, err
}

// gifRecorder captures frames as animated GIF, streaming to disk.
type gifRecorder struct {
	file  *os.File
	count *countingWriter
	bw    *bufio.Writer

	pal    color.Palette
	cache  map[uint32]uint8
	canvas *image.Paletted
	delay  int
	dstW   int
	dstH   int

	headerWritten bool
}

func newGifRecorder(path string, fps, dstW, dstH int) (*gifRecorder, error) {
	f, err := os.Create(path)
	if err != nil {
		return nil, err
	}
	count := &countingWriter{w: f}
	delay := (100 + fps/2) / fps
	if delay < 1 {
		delay = 1
	}
	return &gifRecorder{
		file:  f,
		count: count,
		bw:    bufio.NewWriterSize(count, 1<<20),
		cache: make(map[uint32]uint8),
		delay: delay,
		dstW:  dstW,
		dstH:  dstH,
	}, nil
}

func (g *gifRecorder) frame(scr *screenCapture) error {
	src := scr.grabRGBA()
	if src == nil {
		return nil
	}
	if !g.headerWritten {
		// The palette is derived from the first frame (median cut, with a
		// grayscale ramp reserved) and stays fixed for the whole recording.
		g.pal = buildPalette(src, 256)
		g.canvas = image.NewPaletted(image.Rect(0, 0, g.dstW, g.dstH), g.pal)
		if err := writeGIFHeader(g.bw, g.dstW, g.dstH, g.pal); err != nil {
			return err
		}
		g.headerWritten = true
	}
	quantize(src, g.canvas, g.pal, g.cache)
	return writeGIFFrame(g.bw, g.canvas, g.delay)
}

func (g *gifRecorder) close() error {
	if !g.headerWritten {
		g.file.Close()
		return errors.New("no frames were captured")
	}
	if err := writeGIFTrailer(g.bw); err != nil {
		g.file.Close()
		return err
	}
	if err := g.bw.Flush(); err != nil {
		g.file.Close()
		return err
	}
	return g.file.Close()
}

func (g *gifRecorder) bytesWritten() uint64 { return g.count.n }
