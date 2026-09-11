package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"time"
)

//go:embed studio.html
var studioHTML string

// The web studio is a tiny localhost-only HTTP server so the UI is a real
// HTML page (black & gold), not a terminal. Recording capture still happens
// in-process; the page only sends start/stop/trim commands and previews the
// files the engine writes to recDir.

type webServer struct {
	e *engine
}

type fileInfo struct {
	Name    string    `json:"name"`
	Size    int64     `json:"size"`
	ModTime time.Time `json:"modTime"`
	Kind    string    `json:"kind"` // "gif" | "mp4"
}

func (ws *webServer) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, studioHTML)
	})
	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		jsonOK(w, ws.e.status())
	})
	mux.HandleFunc("/api/record/start", func(w http.ResponseWriter, r *http.Request) {
		p, err := ws.e.start()
		if err != nil {
			jsonErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		jsonOK(w, map[string]string{"path": p})
	})
	mux.HandleFunc("/api/record/stop", func(w http.ResponseWriter, r *http.Request) {
		p, f, err := ws.e.stop()
		if err != nil {
			jsonErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		jsonOK(w, map[string]any{"path": p, "frames": f})
	})
	mux.HandleFunc("/api/files", func(w http.ResponseWriter, r *http.Request) {
		jsonOK(w, ws.listFiles())
	})
	mux.HandleFunc("/api/trim", ws.handleTrim)
	mux.HandleFunc("/api/delete", ws.handleDelete)
	mux.Handle("/files/", http.StripPrefix("/files/", http.FileServer(http.Dir(ws.e.recDir))))
	return mux
}

func (ws *webServer) listFiles() []fileInfo {
	out := []fileInfo{}
	entries, err := os.ReadDir(ws.e.recDir)
	if err != nil {
		return out
	}
	for _, en := range entries {
		if en.IsDir() {
			continue
		}
		ext := strings.ToLower(filepath.Ext(en.Name()))
		if ext != ".gif" && ext != ".mp4" {
			continue
		}
		fi, err := en.Info()
		if err != nil {
			continue
		}
		kind := "gif"
		if ext == ".mp4" {
			kind = "mp4"
		}
		out = append(out, fileInfo{Name: en.Name(), Size: fi.Size(), ModTime: fi.ModTime(), Kind: kind})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ModTime.After(out[j].ModTime) })
	return out
}

func (ws *webServer) handleTrim(w http.ResponseWriter, r *http.Request) {
	var req struct {
		File  string `json:"file"`
		Start string `json:"start"`
		End   string `json:"end"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, http.StatusBadRequest, "bad request")
		return
	}
	name := filepath.Base(req.File) // no traversal
	in := filepath.Join(ws.e.recDir, name)
	start, err1 := parseClipTime(req.Start)
	end, err2 := parseClipTime(req.End)
	if err1 != nil || err2 != nil {
		jsonErr(w, http.StatusBadRequest, "bad start/end time")
		return
	}
	outPath, err := trimFile(in, "", start, end)
	if err != nil {
		jsonErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	jsonOK(w, map[string]string{"path": filepath.Base(outPath)})
}

func (ws *webServer) handleDelete(w http.ResponseWriter, r *http.Request) {
	var req struct {
		File string `json:"file"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		jsonErr(w, http.StatusBadRequest, "bad request")
		return
	}
	name := filepath.Base(req.File)
	if err := os.Remove(filepath.Join(ws.e.recDir, name)); err != nil {
		jsonErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	jsonOK(w, map[string]string{"deleted": name})
}

func jsonOK(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func jsonErr(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

// serveWeb starts the localhost studio and returns the URL it bound to.
func serveWeb(e *engine) (string, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	url := "http://" + ln.Addr().String()
	srv := &http.Server{Handler: (&webServer{e: e}).routes()}
	go srv.Serve(ln)
	return url, nil
}

// openBrowser opens the studio in the default browser, best-effort.
func openBrowser(url string) {
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		cmd = exec.Command("rundll32", "url.dll,FileProtocolHandler", url)
	case "darwin":
		cmd = exec.Command("open", url)
	default:
		cmd = exec.Command("xdg-open", url)
	}
	_ = cmd.Start()
}
