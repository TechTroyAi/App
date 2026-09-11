//go:build windows

package main

import (
	"errors"
	"image"
	"syscall"
	"unsafe"
)

// Capture is plain Win32 GDI: BitBlt the screen into a 32-bpp top-down DIB
// section, then read the pixel buffer directly. No cgo, no external DLLs,
// no libraries shipped alongside the exe.

var (
	modUser32   = syscall.NewLazyDLL("user32.dll")
	modGdi32    = syscall.NewLazyDLL("gdi32.dll")
	modKernel32 = syscall.NewLazyDLL("kernel32.dll")

	procGetSystemMetrics = modUser32.NewProc("GetSystemMetrics")
	procGetDC            = modUser32.NewProc("GetDC")
	procReleaseDC        = modUser32.NewProc("ReleaseDC")
	procGetCursorInfo    = modUser32.NewProc("GetCursorInfo")
	procGetIconInfo      = modUser32.NewProc("GetIconInfo")
	procDrawIconEx       = modUser32.NewProc("DrawIconEx")
	procGetAsyncKeyState = modUser32.NewProc("GetAsyncKeyState")

	procCreateCompatibleDC = modGdi32.NewProc("CreateCompatibleDC")
	procCreateDIBSection   = modGdi32.NewProc("CreateDIBSection")
	procSelectObject       = modGdi32.NewProc("SelectObject")
	procBitBlt             = modGdi32.NewProc("BitBlt")
	procDeleteObject       = modGdi32.NewProc("DeleteObject")
	procDeleteDC           = modGdi32.NewProc("DeleteDC")

	procGetStdHandle   = modKernel32.NewProc("GetStdHandle")
	procGetConsoleMode = modKernel32.NewProc("GetConsoleMode")
	procSetConsoleMode = modKernel32.NewProc("SetConsoleMode")
)

const (
	srccopy            = 0x00CC0020
	diNormal           = 0x0003
	cursorShowing      = 0x00000001
	cursorSuppressed   = 0x00000002
	enableVTProcessing = 0x0004
)

type bitmapInfoHeader struct {
	Size          uint32
	Width         int32
	Height        int32
	Planes        uint16
	BitCount      uint16
	Compression   uint32
	SizeImage     uint32
	XPelsPerMeter int32
	YPelsPerMeter int32
	ClrUsed       uint32
	ClrImportant  uint32
}

type point struct{ X, Y int32 }

type cursorInfo struct {
	CbSize  uint32
	Flags   uint32
	HCursor uintptr
	Pos     point
}

type iconInfo struct {
	FIcon    int32
	XHotspot uint32
	YHotspot uint32
	HbmMask  uintptr
	HbmColor uintptr
}

// sm calls GetSystemMetrics.
func sm(index int) int {
	r, _, _ := procGetSystemMetrics.Call(uintptr(index))
	return int(int32(r))
}

// numSkipped counts frames dropped because BitBlt failed (e.g. while the
// secure desktop behind a UAC prompt is showing).
var numSkipped int

func skippedFrames() int { return numSkipped }

type screenCapture struct {
	x, y, w, h int
	stride     int
	raw        []byte      // BGRA pixels of the full capture, top-down
	rgba       *image.RGBA // downscaled RGBA output (GIF path)
	cursor     bool

	hdcSrc  uintptr
	hdcMem  uintptr
	hbmp    uintptr
	hbmpOld uintptr
	bits    unsafe.Pointer // pixel memory owned by the DIB section
}

// newScreenCapture prepares capturing. all=true grabs the whole virtual
// desktop (every monitor); otherwise only the primary display.
func newScreenCapture(all bool, includeCursor bool) (*screenCapture, error) {
	x, y, w, h := 0, 0, sm(0), sm(1) // SM_CXSCREEN / SM_CYSCREEN
	if all {
		x, y, w, h = sm(76), sm(77), sm(78), sm(79) // SM_XVIRTUALSCREEN...
	}
	if w <= 0 || h <= 0 {
		return nil, errors.New("could not determine the screen size")
	}

	hdcSrc, _, _ := procGetDC.Call(0)
	if hdcSrc == 0 {
		return nil, errors.New("GetDC(NULL) failed")
	}
	hdcMem, _, _ := procCreateCompatibleDC.Call(hdcSrc)
	if hdcMem == 0 {
		procReleaseDC.Call(0, hdcSrc)
		return nil, errors.New("CreateCompatibleDC failed")
	}

	var bi bitmapInfoHeader
	bi.Size = uint32(unsafe.Sizeof(bi))
	bi.Width = int32(w)
	bi.Height = -int32(h) // negative => top-down rows
	bi.Planes = 1
	bi.BitCount = 32
	bi.Compression = 0 // BI_RGB

	var bits unsafe.Pointer
	ret, _, _ := procCreateDIBSection.Call(
		hdcMem,
		uintptr(unsafe.Pointer(&bi)),
		0, // DIB_RGB_COLORS
		uintptr(unsafe.Pointer(&bits)),
		0, 0,
	)
	if ret == 0 || bits == nil {
		procDeleteDC.Call(hdcMem)
		procReleaseDC.Call(0, hdcSrc)
		return nil, errors.New("CreateDIBSection failed")
	}

	hbmp := ret
	hbmpOld, _, _ := procSelectObject.Call(hdcMem, hbmp)

	return &screenCapture{
		x: x, y: y, w: w, h: h,
		stride:  w * 4,
		raw:     unsafe.Slice((*byte)(bits), w*h*4),
		cursor:  includeCursor,
		hdcSrc:  hdcSrc,
		hdcMem:  hdcMem,
		hbmp:    hbmp,
		hbmpOld: hbmpOld,
		bits:    bits,
	}, nil
}

func (c *screenCapture) width() int   { return c.w }
func (c *screenCapture) height() int  { return c.h }
func (c *screenCapture) originX() int { return c.x }
func (c *screenCapture) originY() int { return c.y }

func (c *screenCapture) setOutputSize(w, h int) {
	c.rgba = image.NewRGBA(image.Rect(0, 0, w, h))
}

// grabBGRA blits the screen into the DIB and returns the raw BGRA pixels
// (top-down, 4 bytes per pixel: blue, green, red, unused).
func (c *screenCapture) grabBGRA() ([]byte, bool) {
	r, _, _ := procBitBlt.Call(c.hdcMem, 0, 0, uintptr(c.w), uintptr(c.h),
		c.hdcSrc, uintptr(int32(c.x)), uintptr(int32(c.y)), srccopy)
	if r == 0 {
		numSkipped++
		return nil, false
	}
	if c.cursor {
		c.drawCursor()
	}
	return c.raw, true
}

// grabRGBA captures a frame and returns it downscaled to the configured
// output size as RGBA. On blit failure the previous frame is reused.
func (c *screenCapture) grabRGBA() *image.RGBA {
	raw, ok := c.grabBGRA()
	if !ok || c.rgba == nil {
		return c.rgba
	}
	dst := c.rgba
	sw, sh := c.w, c.h
	dw, dh := dst.Bounds().Dx(), dst.Bounds().Dy()
	for dy := 0; dy < dh; dy++ {
		sy := dy * sh / dh
		srow := raw[sy*c.stride:]
		drow := dst.Pix[dy*dst.Stride:]
		for dx := 0; dx < dw; dx++ {
			sx := dx * sw / dw
			s := srow[sx*4:]
			d := drow[dx*4:]
			d[0] = s[2] // R
			d[1] = s[1] // G
			d[2] = s[0] // B
			d[3] = 0xFF
		}
	}
	return dst
}

// drawCursor composites the current mouse cursor (with its true hotspot) on
// top of the just-blitted frame; BitBlt alone never captures the cursor.
func (c *screenCapture) drawCursor() {
	var ci cursorInfo
	ci.CbSize = uint32(unsafe.Sizeof(ci))
	r, _, _ := procGetCursorInfo.Call(uintptr(unsafe.Pointer(&ci)))
	if r == 0 || ci.HCursor == 0 ||
		ci.Flags&cursorShowing == 0 || ci.Flags&cursorSuppressed != 0 {
		return
	}
	var ii iconInfo
	r, _, _ = procGetIconInfo.Call(ci.HCursor, uintptr(unsafe.Pointer(&ii)))
	if r == 0 {
		return
	}
	dx := ci.Pos.X - int32(ii.XHotspot) - int32(c.x)
	dy := ci.Pos.Y - int32(ii.YHotspot) - int32(c.y)
	procDrawIconEx.Call(c.hdcMem,
		uintptr(uint32(dx)), uintptr(uint32(dy)), ci.HCursor,
		0, 0, 0, 0, diNormal)
	if ii.HbmMask != 0 {
		procDeleteObject.Call(ii.HbmMask)
	}
	if ii.HbmColor != 0 {
		procDeleteObject.Call(ii.HbmColor)
	}
}

func (c *screenCapture) close() {
	if c.hbmpOld != 0 {
		procSelectObject.Call(c.hdcMem, c.hbmpOld)
	}
	if c.hbmp != 0 {
		procDeleteObject.Call(c.hbmp)
	}
	if c.hdcMem != 0 {
		procDeleteDC.Call(c.hdcMem)
	}
	if c.hdcSrc != 0 {
		procReleaseDC.Call(0, c.hdcSrc)
	}
}

// keyDown reports whether a virtual key is currently held down (global,
// regardless of which window has focus).
func keyDown(vk int) bool {
	r, _, _ := procGetAsyncKeyState.Call(uintptr(vk))
	return r&0x8000 != 0
}

// enableVT turns on ANSI escape processing in the console so the black &
// gold theme renders; returns false on consoles without VT support (the app
// then falls back to plain, colorless output).
func enableVT() bool {
	const stdOutputHandle = ^uintptr(10) // (DWORD)-11
	h, _, _ := procGetStdHandle.Call(stdOutputHandle)
	if h == 0 || h == ^uintptr(0) {
		return false
	}
	var mode uint32
	if r, _, _ := procGetConsoleMode.Call(h, uintptr(unsafe.Pointer(&mode))); r == 0 {
		return false
	}
	r, _, _ := procSetConsoleMode.Call(h, uintptr(mode)|enableVTProcessing)
	return r != 0
}
