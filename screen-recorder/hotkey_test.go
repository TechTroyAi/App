package main

import (
	"testing"
	"time"
)

const vkF9 = 0x78

// tapFn models a finger on the key: down for [start,end), up otherwise.
func tapFn(start, end time.Duration) func(sampleAt time.Duration) bool {
	return func(at time.Duration) bool { return at >= start && at < end }
}

// countPresses replays a level timeline through the real keyWatch at a fixed
// sampling interval.
func countPresses(t *testing.T, interval time.Duration, down func(time.Duration) bool, samples int) int {
	t.Helper()
	w := &keyWatch{vk: vkF9}
	presses := 0
	for i := 0; i <= samples; i++ {
		if w.sample(down(time.Duration(i) * interval)) {
			presses++
		}
	}
	return presses
}

// TestShortTapLostAtOldSamplingRate pins down the regression this file
// exists to fix. The hotkey used to be read from the capture loop at
// frameDur/2, capped at 50 ms — and while recording, the whole frame encode
// was added on top of that. GetAsyncKeyState reports a level, not a queued
// press, so a tap shorter than the gap between two reads is never observed.
func TestShortTapLostAtOldSamplingRate(t *testing.T) {
	tap := tapFn(60*time.Millisecond, 90*time.Millisecond) // a quick 30 ms tap

	if got := countPresses(t, 50*time.Millisecond, tap, 10); got != 0 {
		t.Fatalf("expected the old 50 ms cadence to miss the tap, saw %d presses", got)
	}
	if got := countPresses(t, keyPollInterval, tap, 100); got != 1 {
		t.Fatalf("at %v the same tap should register exactly once, got %d", keyPollInterval, got)
	}
}

// TestKeyWatchEdgeSemantics checks press/release transitions, including a
// long hold: holding the key down must fire once, not once per sample.
func TestKeyWatchEdgeSemantics(t *testing.T) {
	w := &keyWatch{vk: vkF9}
	for i, step := range []struct {
		down  bool
		fresh bool
	}{
		{false, false}, // idle
		{true, true},   // press
		{true, false},  // still held
		{true, false},  // still held
		{false, false}, // release
		{false, false}, // idle
		{true, true},   // press again
		{false, false}, // release
	} {
		if got := w.sample(step.down); got != step.fresh {
			t.Fatalf("sample %d (down=%v) = %v, want %v", i, step.down, got, step.fresh)
		}
	}
}

// TestKeyWatchSeedIgnoresHeldKey: a key already down when the app starts must
// not fire off a recording the instant the window opens.
func TestKeyWatchSeedIgnoresHeldKey(t *testing.T) {
	w := &keyWatch{vk: vkF9}
	w.seed(true)
	if w.sample(true) {
		t.Fatal("a key held at startup fired as a press")
	}
	if w.sample(true) {
		t.Fatal("a key held across samples fired as a press")
	}
	if w.sample(false) {
		t.Fatal("release fired as a press")
	}
	if !w.sample(true) {
		t.Fatal("the next real press was missed")
	}
}

// TestKeyPollerDeliversPressWhileConsumerIsBusy is the decoupling property:
// the main loop can be stuck encoding a frame for far longer than the tap
// lasted, and the press still has to arrive. This is what a short tap used
// to be lost to.
func TestKeyPollerDeliversPressWhileConsumerIsBusy(t *testing.T) {
	pressAt := time.Now().Add(40 * time.Millisecond)
	releaseAt := pressAt.Add(30 * time.Millisecond) // a 30 ms tap
	read := func(vk int) bool {
		if vk != vkF9 {
			return false
		}
		now := time.Now()
		return !now.Before(pressAt) && now.Before(releaseAt)
	}

	p := startKeyPoller(2*time.Millisecond, read, vkF9, vkEscape)
	defer p.stop()

	// Stand in for a slow frame: nobody is reading events for a while.
	time.Sleep(250 * time.Millisecond)

	select {
	case ev := <-p.events:
		if ev.vk != vkF9 {
			t.Fatalf("event vk = %#x, want %#x", ev.vk, vkF9)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("the 30 ms tap was lost while the consumer was busy")
	}
}

// TestKeyPollerOnlyReportsWatchedKeys checks the poller reports the keys it
// was given and nothing else.
func TestKeyPollerOnlyReportsWatchedKeys(t *testing.T) {
	// Every key goes down shortly after the poller starts; only F9 is watched.
	downAt := time.Now().Add(30 * time.Millisecond)
	read := func(vk int) bool { return !time.Now().Before(downAt) }

	p := startKeyPoller(2*time.Millisecond, read, vkF9)
	defer p.stop()

	select {
	case ev := <-p.events:
		if ev.vk != vkF9 {
			t.Fatalf("got vk %#x, want %#x", ev.vk, vkF9)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no event for the watched key")
	}

	// ESC went down too, but it was never watched: no event for it.
	time.Sleep(50 * time.Millisecond)
	select {
	case ev := <-p.events:
		t.Fatalf("unexpected event for an unwatched key: vk %#x", ev.vk)
	default:
	}
}

func TestVKName(t *testing.T) {
	for _, tc := range []struct {
		vk   int
		want string
	}{
		{0x78, "F9"}, {0x70, "F1"}, {0x7B, "F12"},
		{0x41, "A"}, {0x35, "5"}, {0x1B, "ESC"}, {0x60, "NUM0"},
		{0x01, ""}, // VK_LBUTTON: no name worth printing
	} {
		if got := vkName(tc.vk); got != tc.want {
			t.Errorf("vkName(%#x) = %q, want %q", tc.vk, got, tc.want)
		}
	}
}
