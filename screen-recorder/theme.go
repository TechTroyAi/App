package main

import (
	"fmt"
	"strings"
)

// Black & gold minimal theme: gold accents on the terminal's own dark
// background, dim gray structure lines, red reserved for errors. Everything
// is ANSI 256-color, which the Windows console supports once VT processing
// is enabled (Windows 10+). Older consoles or NO_COLOR get plain output.

// colorEnabled is turned off when the console does not support ANSI escape
// sequences or when the user sets the NO_COLOR environment variable.
var colorEnabled = true

// setColorEnabled toggles the theme (used by main after enableVT/NO_COLOR).
func setColorEnabled(on bool) { colorEnabled = on }

func tint(code, s string) string {
	if !colorEnabled {
		return s
	}
	return "\x1b[" + code + "m" + s + "\x1b[0m"
}

func gold(s string) string   { return tint("38;5;220", s) }
func amber(s string) string  { return tint("38;5;214", s) }
func dim(s string) string    { return tint("38;5;240", s) }
func bright(s string) string { return tint("38;5;255", s) }
func red(s string) string    { return tint("38;5;203", s) }

// rule draws a thin horizontal separator.
func rule(n int) string { return dim(strings.Repeat("─", n)) }

// pulseDot alternates between two golds so the REC indicator subtly breathes
// while recording.
func pulseDot(frames int) string {
	if frames%2 == 0 {
		return gold("●")
	}
	return amber("●")
}

var _ = fmt.Sprintf // keep fmt imported for future theme helpers
