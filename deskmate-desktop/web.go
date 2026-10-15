package main

import (
	"embed"
	"encoding/json"
	"fmt"
	"io/fs"
	"net"
	"net/http"
	"strings"
)

//go:embed web
var webFiles embed.FS

// The settings page only listens on 127.0.0.1, so only someone at the PC can use it.
func serveSettings(port int) (string, error) {
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		return "", err
	}
	mux := http.NewServeMux()
	sub, _ := fs.Sub(webFiles, "web")
	mux.Handle("/", http.FileServer(http.FS(sub)))

	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		cfgMu.Lock()
		type dev struct {
			Name  string `json:"name"`
			Added string `json:"added"`
			ID    string `json:"id"`
		}
		var devices []dev
		for _, d := range cfg.Devices {
			devices = append(devices, dev{Name: d.Name, Added: d.Added.Format("Jan 2, 2006"), ID: d.Token[:8]})
		}
		tcpPort := cfg.Port
		cfgMu.Unlock()
		writeJSON(w, map[string]any{
			"code":      currentPairCode(),
			"devices":   devices,
			"ips":       localIPs(),
			"port":      tcpPort,
			"caps":      capabilities(),
			"autostart": autostartEnabled(),
		})
	})

	mux.HandleFunc("/api/config", func(w http.ResponseWriter, r *http.Request) {
		if !sameOrigin(r) {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		switch r.Method {
		case http.MethodGet:
			cfgMu.Lock()
			out := map[string]any{"pages": cfg.Pages, "devForwardPort": cfg.DevForwardPort, "cols": cfg.Cols, "rows": cfg.Rows}
			cfgMu.Unlock()
			writeJSON(w, out)
		case http.MethodPut:
			var in struct {
				Pages          []Page `json:"pages"`
				DevForwardPort int    `json:"devForwardPort"`
				Cols           int    `json:"cols"`
				Rows           int    `json:"rows"`
			}
			r.Body = http.MaxBytesReader(w, r.Body, 32<<20)
			if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			if in.Cols < 2 || in.Cols > 8 || in.Rows < 1 || in.Rows > 5 || len(in.Pages) == 0 {
				http.Error(w, "layout must be 2-8 columns and 1-5 rows with at least one page", http.StatusBadRequest)
				return
			}
			for i := range in.Pages {
				in.Pages[i].Buttons = nil
				in.Pages[i].Keys = sanitizeKeys(in.Pages[i].Keys, in.Cols*in.Rows, false)
			}
			cfgMu.Lock()
			cfg.Pages = in.Pages
			cfg.Cols, cfg.Rows = in.Cols, in.Rows
			if in.DevForwardPort > 0 && in.DevForwardPort < 65536 {
				cfg.DevForwardPort = in.DevForwardPort
			}
			saveConfigLocked()
			cfgMu.Unlock()
			broadcast(msg{"op": "deckChanged"})
			writeJSON(w, map[string]any{"ok": true})
		}
	})

	post := func(path string, fn func(r *http.Request) error) {
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodPost || !sameOrigin(r) {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
			if err := fn(r); err != nil {
				writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
				return
			}
			writeJSON(w, map[string]any{"ok": true})
		})
	}
	post("/api/test", func(r *http.Request) error {
		var a Action
		if err := json.NewDecoder(r.Body).Decode(&a); err != nil {
			return err
		}
		return runAction(a)
	})
	post("/api/revoke", func(r *http.Request) error {
		id := r.URL.Query().Get("id")
		cfgMu.Lock()
		defer cfgMu.Unlock()
		var keep []Device
		for _, d := range cfg.Devices {
			if !strings.HasPrefix(d.Token, id) || id == "" {
				keep = append(keep, d)
			}
		}
		cfg.Devices = keep
		saveConfigLocked()
		return nil
	})
	post("/api/newcode", func(r *http.Request) error { rotatePairCode(); return nil })
	post("/api/autostart", func(r *http.Request) error { return setAutostart(r.URL.Query().Get("on") == "1") })
	post("/api/reset", func(r *http.Request) error {
		cfgMu.Lock()
		defer cfgMu.Unlock()
		d := defaultConfig()
		cfg.Pages, cfg.Cols, cfg.Rows = d.Pages, d.Cols, d.Rows
		saveConfigLocked()
		go broadcast(msg{"op": "deckChanged"})
		return nil
	})

	go http.Serve(ln, mux)
	return fmt.Sprintf("http://127.0.0.1:%d", port), nil
}

// Blocks other web pages in your browser from changing settings behind your back.
func sameOrigin(r *http.Request) bool {
	o := r.Header.Get("Origin")
	if o == "" {
		return r.Header.Get("Sec-Fetch-Site") != "cross-site"
	}
	return strings.HasPrefix(o, "http://127.0.0.1:") || strings.HasPrefix(o, "http://localhost:")
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(v)
}
