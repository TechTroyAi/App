package main

import (
	"fmt"
	"strconv"
	"time"
)

// Key handling, split out of main so it can be tested.
//
// GetAsyncKeyState reports a *level* ("is this key held right now"), not a
// queue of presses, so a press is only visible if a sample happens to land
// while the finger is still on the key. That makes the sampling rate part of
// the correctness story: sample too slowly and short taps simply never
// register. It also means key reading must not share a loop with anything
// slow — one long frame encode would stretch the gap between samples by the
// whole encode time and swallow the keypress that happened during it.
//
// So: sample often, on a goroutine of its own, and hand discrete press
// events to the main loop over a channel. Nothing can be dropped, because a
// press that arrives mid-encode waits in the channel until the encode ends.

// keyPollInterval is how often keys are sampled. A deliberate keypress lasts
// tens of milliseconds at the very shortest, so 5 ms sees it many times over.
// GetAsyncKeyState reads a per-key word out of a shared structure, so this
// costs a fraction of a percent of one core.
const keyPollInterval = 5 * time.Millisecond

// keyWatch turns repeated level samples of one virtual key into discrete
// press events.
type keyWatch struct {
	vk      int
	wasDown bool
}

// seed records the key's current state without counting it as a press, so a
// key that happens to be held down when the app starts does not fire off a
// recording immediately.
func (w *keyWatch) seed(down bool) { w.wasDown = down }

// sample reports whether the key went down since the previous sample, and
// remembers the new level either way.
func (w *keyWatch) sample(down bool) bool {
	fresh := down && !w.wasDown
	w.wasDown = down
	return fresh
}

// keyEvent is one keypress that reached the main loop.
type keyEvent struct{ vk int }

// keyPoller samples a set of virtual keys in the background and publishes
// every fresh press on events.
type keyPoller struct {
	events chan keyEvent
	stopCh chan struct{}
	done   chan struct{}
}

// sampler reads one key's level; keyDown on Windows, and anything a test
// wants to inject elsewhere.
type sampler func(vk int) bool

// startKeyPoller begins watching vks. The returned poller must be stopped
// with stop().
func startKeyPoller(interval time.Duration, read sampler, vks ...int) *keyPoller {
	p := &keyPoller{
		events: make(chan keyEvent, 16),
		stopCh: make(chan struct{}),
		done:   make(chan struct{}),
	}
	go func() {
		defer close(p.done)
		watches := make([]*keyWatch, len(vks))
		for i, vk := range vks {
			watches[i] = &keyWatch{vk: vk}
			watches[i].seed(read(vk))
		}
		t := time.NewTicker(interval)
		defer t.Stop()
		for {
			select {
			case <-p.stopCh:
				return
			case <-t.C:
				for _, w := range watches {
					if !w.sample(read(w.vk)) {
						continue
					}
					// Blocking here rather than dropping is deliberate: the
					// main loop may be busy encoding a frame, and a press
					// that waits in this send still gets delivered a moment
					// later. A dropped press is the bug this file exists to
					// fix.
					p.events <- keyEvent{vk: w.vk}
				}
			}
		}
	}()
	return p
}

func (p *keyPoller) stop() {
	close(p.stopCh)
	<-p.done
}

// keytestVKLow/keytestVKHigh bound the range -keytest scans. It covers every
// key Windows gives a virtual-key code to: letters, digits, punctuation, the
// F row, modifiers and the numpad.
const (
	keytestVKLow  = 0x08 // VK_BACK
	keytestVKHigh = 0xFF // VK_OEM_CLEAR + 1
)

// vkName renders a virtual-key code for humans, or "" when there is no short
// name worth printing.
func vkName(vk int) string {
	switch {
	case vk >= 0x70 && vk <= 0x7B:
		return "F" + strconv.Itoa(vk-0x70+1)
	case vk >= 0x30 && vk <= 0x39, vk >= 0x41 && vk <= 0x5A:
		return string(rune(vk))
	case vk == 0x08:
		return "BACKSPACE"
	case vk == 0x09:
		return "TAB"
	case vk == 0x0D:
		return "ENTER"
	case vk == 0x10:
		return "SHIFT"
	case vk == 0x11:
		return "CTRL"
	case vk == 0x12:
		return "ALT"
	case vk == 0x1B:
		return "ESC"
	case vk == 0x20:
		return "SPACE"
	case vk == 0x2C:
		return "PRINTSCREEN"
	case vk == 0x5B, vk == 0x5C:
		return "WIN"
	case vk >= 0x60 && vk <= 0x69:
		return "NUM" + strconv.Itoa(vk-0x60)
	}
	return ""
}

// runKeyTest prints every keypress Windows reports until ESC. It answers the
// question "is the key I am pressing actually reaching Windows as F9?" — on a
// laptop with the function row bound to media keys, F9 alone sends a media
// code and the recorder can never see it.
func runKeyTest(hotkey int) {
	fmt.Println()
	fmt.Println("  " + gold("◆ KEY TEST") + dim("  press keys, see what Windows receives"))
	fmt.Println("  " + rule(42))
	fmt.Printf("  %s\n", dim("watching for "+gold(keyName(vkName(hotkey))+" / "+hexVK(hotkey))+" — press "+gold("ESC")+" to finish"))
	fmt.Println()

	scan := make([]int, 0, keytestVKHigh-keytestVKLow)
	for vk := keytestVKLow; vk < keytestVKHigh; vk++ {
		scan = append(scan, vk)
	}
	p := startKeyPoller(keyPollInterval, keyDown, scan...)
	defer p.stop()

	sawHotkey := false
	for ev := range p.events {
		if ev.vk == vkEscape {
			fmt.Println()
			break
		}
		if ev.vk == hotkey {
			sawHotkey = true
		}
		name := vkName(ev.vk)
		if name == "" {
			name = dim("(no name)")
		}
		fmt.Printf("  %-12s %s\n", bright(name), dim(hexVK(ev.vk)))
	}

	fmt.Println()
	switch {
	case sawHotkey:
		fmt.Printf("  %s %s\n", gold("✓"), "Windows reports your hotkey — recording should start.")
	default:
		fmt.Printf("  %s %s\n", red("!"), "Your hotkey never arrived as that key.")
		fmt.Printf("    %s\n", dim("If nothing printed when you pressed it, the key is bound to"))
		fmt.Printf("    %s\n", dim("something else (Fn-lock media keys, or another app owns it)."))
		fmt.Printf("    %s\n", dim("Try holding Fn, or pick a free key: -hotkey F8, -hotkey 0x78"))
	}
	fmt.Println()
}

// hexVK formats a virtual-key code the way -hotkey accepts it.
func hexVK(vk int) string { return "vk 0x" + strconv.FormatUint(uint64(vk), 16) }
