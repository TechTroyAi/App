//go:build !windows

// Engine + web-studio tests drive the non-Windows stub capture, so they are
// excluded from the Windows build (the stub's frame/setOutputSize hooks do not
// exist in capture_windows.go). CI runs them on Linux.

package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// newTestScreen returns the non-Windows stub capture primed with a frame the
// engine can actually record.
func newTestScreen() *screenCapture {
	s := &screenCapture{}
	s.setOutputSize(32, 24)
	s.frame = makeTestRGBA(32, 24, 7)
	return s
}

func TestEngineRecordsAndStops(t *testing.T) {
	dir := t.TempDir()
	e := newEngine(newTestScreen(), "gif", 30, 32, 24, dir, "")
	if _, err := e.start(); err != nil {
		t.Fatalf("start: %v", err)
	}
	time.Sleep(250 * time.Millisecond) // let the pump capture some frames

	p, f, err := e.stop()
	if p == "" || err != nil {
		t.Fatalf("stop: path=%q err=%v", p, err)
	}
	if !strings.HasSuffix(p, ".gif") {
		t.Fatalf("expected a .gif recording, got %q", p)
	}
	if f == 0 {
		t.Fatal("no frames were captured")
	}
	if g := decodeFrames(t, p); len(g.Image) == 0 {
		t.Fatal("recording GIF has no frames")
	}
}

func TestWebStudioAPI(t *testing.T) {
	dir := t.TempDir()
	e := newEngine(newTestScreen(), "gif", 30, 32, 24, dir, "")
	srv := httptest.NewServer((&webServer{e: e}).routes())
	defer srv.Close()

	var st status
	if err := getJSON(srv, "/api/status", &st); err != nil {
		t.Fatal(err)
	}
	if st.Recording {
		t.Fatal("should start idle")
	}

	var m map[string]string
	if err := postJSON(srv, "/api/record/start", nil, &m); err != nil {
		t.Fatal(err)
	}
	if m["path"] == "" {
		t.Fatal("start returned no path")
	}
	time.Sleep(200 * time.Millisecond)

	var ms map[string]any
	if err := postJSON(srv, "/api/record/stop", nil, &ms); err != nil {
		t.Fatal(err)
	}

	var files []fileInfo
	if err := getJSON(srv, "/api/files", &files); err != nil {
		t.Fatal(err)
	}
	if len(files) != 1 {
		t.Fatalf("files=%d, want 1", len(files))
	}
	name := files[0].Name

	resp, err := http.Get(srv.URL + "/files/" + name)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("GET /files/ status=%d", resp.StatusCode)
	}

	var tm map[string]string
	body := map[string]string{"file": name, "start": "0", "end": ""}
	if err := postJSON(srv, "/api/trim", body, &tm); err != nil {
		t.Fatal(err)
	}
	if tm["path"] == "" || !strings.HasSuffix(tm["path"], ".trim.gif") {
		t.Fatalf("trim path=%q", tm["path"])
	}
	if _, err := os.Stat(filepath.Join(dir, tm["path"])); err != nil {
		t.Fatalf("trimmed file missing: %v", err)
	}
}

func getJSON(srv *httptest.Server, path string, out any) error {
	resp, err := http.Get(srv.URL + path)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return json.NewDecoder(resp.Body).Decode(out)
}

func postJSON(srv *httptest.Server, path string, in any, out any) error {
	var body *strings.Reader = strings.NewReader("")
	if in != nil {
		b, _ := json.Marshal(in)
		body = strings.NewReader(string(b))
	}
	resp, err := http.Post(srv.URL+path, "application/json", body)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if out == nil {
		return nil
	}
	return json.NewDecoder(resp.Body).Decode(out)
}
