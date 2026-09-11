//go:build !windows

// Stub build for non-Windows systems so `go vet` and `go test` (GIF writer
// and palette unit tests) can run anywhere. The shipped exe is always built
// with GOOS=windows, which uses capture_windows.go instead.

package main

import (
	"errors"
	"image"
)

type screenCapture struct {
	w, h  int
	rgba  *image.RGBA
	frame *image.RGBA // test hook: the frame grabRGBA returns
}

func newScreenCapture(all bool, includeCursor bool) (*screenCapture, error) {
	return nil, errors.New("screen capture requires Windows")
}

func (c *screenCapture) width() int   { return c.w }
func (c *screenCapture) height() int  { return c.h }
func (c *screenCapture) originX() int { return 0 }
func (c *screenCapture) originY() int { return 0 }

func (c *screenCapture) setOutputSize(w, h int) {
	c.w, c.h = w, h
	c.rgba = image.NewRGBA(image.Rect(0, 0, w, h))
}

func (c *screenCapture) grabBGRA() ([]byte, bool) { return nil, false }
func (c *screenCapture) grabRGBA() *image.RGBA {
	if c.frame != nil {
		return c.frame
	}
	return c.rgba
}
func (c *screenCapture) close()                   {}

func keyDown(vk int) bool { return false }

func enableVT() {}

func skippedFrames() int { return 0 }
