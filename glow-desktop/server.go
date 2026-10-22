package main

import (
	"embed"
	"encoding/json"
	"errors"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// One web server for everything:
//   - the Glow website (this PC's browser, and any phone/tablet browser on your Wi-Fi)
//   - the JSON API the tablet app uses
//   - screen-sync frames from the capture page (this PC only)
// Requests from this PC are trusted. Others must pair once with the 6-digit code.

//go:embed web
var webFiles embed.FS

var (
	pairMu      sync.Mutex
	pairCode    string
	pairExpires time.Time
)

func currentPairCode() string {
	pairMu.Lock()
	defer pairMu.Unlock()
	if pairCode == "" || time.Now().After(pairExpires) {
		pairCode = newPairCode()
		pairExpires = time.Now().Add(10 * time.Minute)
	}
	return pairCode
}

func isLocal(r *http.Request) bool {
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// Blocks other web pages in your browser from changing things behind your back.
func sameOrigin(r *http.Request) bool {
	o := r.Header.Get("Origin")
	if o == "" {
		return r.Header.Get("Sec-Fetch-Site") != "cross-site"
	}
	return strings.HasPrefix(o, "http://127.0.0.1:") || strings.HasPrefix(o, "http://localhost:")
}

func bearer(r *http.Request) string {
	return strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
}

func allowed(r *http.Request) bool {
	if isLocal(r) {
		return r.Method == http.MethodGet || sameOrigin(r)
	}
	return tokenValid(bearer(r))
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(v)
}

func stateJSON(local bool) map[string]any {
	cfgMu.Lock()
	ac, sc := cfg.Aito, cfg.Strip
	var devices []map[string]string
	for _, d := range cfg.Devices {
		devices = append(devices, map[string]string{"name": d.Name, "id": d.Token[:8], "added": d.Added.Format("Jan 2, 2006")})
	}
	cfgMu.Unlock()

	aito.mu.Lock()
	ai := map[string]any{
		"connected": aito.connected, "status": aito.status, "port": aito.path,
		"model": aito.info.Model, "sides": aito.info.Sides, "ledCount": aito.info.Total(),
		"fps": int(aito.fps + 0.5),
	}
	aito.mu.Unlock()
	ai["config"] = ac
	ai["zones"] = ac.Layout.zones()
	ai["effects"] = aitoEffects

	strip.mu.Lock()
	st := map[string]any{"connected": strip.connected, "status": strip.status, "busy": strip.busy, "proto": strip.proto}
	proto := strip.proto
	strip.mu.Unlock()
	st["config"] = sc
	st["effects"] = stripEffects(proto)

	host, _ := os.Hostname()
	out := map[string]any{
		"app": "glow", "version": appVersion, "host": host,
		"aito": ai, "strip": st, "screen": map[string]any{"active": aito.screenActive(), "capture": capture.info()},
		"local": local,
	}
	if local {
		out["pairCode"] = currentPairCode()
		out["devices"] = devices
		out["ips"] = localIPs()
	}
	return out
}

// mergeJSON decodes a partial update on top of an existing struct.
func mergeJSON(r *http.Request, into any) error {
	r.Body = http.MaxBytesReader(nil, r.Body, 1<<20)
	return json.NewDecoder(r.Body).Decode(into)
}

func clampInt(v *int, lo, hi int) {
	if *v < lo {
		*v = lo
	}
	if *v > hi {
		*v = hi
	}
}

func serve(port int) error {
	ln, err := net.Listen("tcp", net.JoinHostPort("", strconv.Itoa(port)))
	if err != nil {
		return err
	}
	mux := http.NewServeMux()
	sub, _ := fs.Sub(webFiles, "web")
	files := http.FileServer(http.FS(sub))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-cache")
		files.ServeHTTP(w, r)
	})

	api := func(path string, local bool, fn func(w http.ResponseWriter, r *http.Request) (any, error)) {
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			if !allowed(r) || (local && !isLocal(r)) {
				w.WriteHeader(http.StatusUnauthorized)
				writeJSON(w, map[string]any{"ok": false, "error": "pair", "message": "Pair this device first"})
				return
			}
			if r.Method != http.MethodGet && r.Method != http.MethodPost {
				http.Error(w, "method", http.StatusMethodNotAllowed)
				return
			}
			v, err := fn(w, r)
			if err != nil {
				writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
				return
			}
			if v == nil {
				v = stateJSON(isLocal(r))
			}
			writeJSON(w, v)
		})
	}

	// Anyone on the network may ask who this is (for the tablet's "find my PC").
	mux.HandleFunc("/api/hello", func(w http.ResponseWriter, r *http.Request) {
		host, _ := os.Hostname()
		writeJSON(w, map[string]any{"app": "glow", "version": appVersion, "host": host, "paired": isLocal(r) || tokenValid(bearer(r))})
	})

	var pairFails sync.Map
	mux.HandleFunc("/api/pair", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method", http.StatusMethodNotAllowed)
			return
		}
		ip, _, _ := net.SplitHostPort(r.RemoteAddr)
		if n, _ := pairFails.Load(ip); n != nil && n.(int) >= 5 {
			time.Sleep(3 * time.Second) // slows down code guessing
		}
		var in struct{ Code, Name string }
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&in) != nil || strings.TrimSpace(in.Code) != currentPairCode() {
			n, _ := pairFails.LoadOrStore(ip, 0)
			pairFails.Store(ip, n.(int)+1)
			writeJSON(w, map[string]any{"ok": false, "error": "That code doesn't match. Check the code on the PC."})
			return
		}
		pairFails.Delete(ip)
		name := strings.TrimSpace(in.Name)
		if name == "" {
			name = "Tablet"
		}
		if len(name) > 40 {
			name = name[:40]
		}
		tok := newToken()
		cfgMu.Lock()
		cfg.Devices = append(cfg.Devices, Device{Name: name, Token: tok, Added: time.Now()})
		saveConfigLocked()
		cfgMu.Unlock()
		pairMu.Lock()
		pairCode = "" // one code, one tablet
		pairMu.Unlock()
		log.Printf("Paired %s (%s)", name, ip)
		writeJSON(w, map[string]any{"ok": true, "token": tok})
	})

	api("/api/state", false, func(w http.ResponseWriter, r *http.Request) (any, error) { return nil, nil })

	api("/api/aito", false, func(w http.ResponseWriter, r *http.Request) (any, error) {
		if r.Method != http.MethodPost {
			return nil, nil
		}
		cfgMu.Lock()
		next := cfg.Aito
		cfgMu.Unlock()
		if err := mergeJSON(r, &next); err != nil {
			return nil, err
		}
		clampInt(&next.Brightness, 0, 100)
		clampInt(&next.Speed, 0, 100)
		clampInt(&next.Smoothing, 0, 95)
		clampInt(&next.Saturation, 50, 200)
		l := &next.Layout
		for _, v := range []*int{&l.Left, &l.Top, &l.Right, &l.Bottom} {
			clampInt(v, 0, 300)
		}
		clampInt(&l.Depth, 3, 40)
		clampInt(&l.Offset, 0, 100)
		cfgMu.Lock()
		cfg.Aito = next
		cfgMu.Unlock()
		saveConfigSoon()
		return nil, nil
	})

	api("/api/aito/test", false, func(w http.ResponseWriter, r *http.Request) (any, error) {
		aito.mu.Lock()
		aito.test, aito.testAt = "order", time.Now()
		aito.mu.Unlock()
		return nil, nil
	})

	api("/api/aito/use-device-layout", false, func(w http.ResponseWriter, r *http.Request) (any, error) {
		aito.mu.Lock()
		info, ok := aito.info, aito.connected
		aito.mu.Unlock()
		if !ok {
			return nil, errors.New("connect the light first")
		}
		cfgMu.Lock()
		useDeviceLayoutLocked(info)
		saveConfigLocked()
		cfgMu.Unlock()
		return nil, nil
	})

	api("/api/strip", false, func(w http.ResponseWriter, r *http.Request) (any, error) {
		if r.Method != http.MethodPost {
			return nil, nil
		}
		cfgMu.Lock()
		next := cfg.Strip.LightState
		cfgMu.Unlock()
		if err := mergeJSON(r, &next); err != nil {
			return nil, err
		}
		clampInt(&next.Brightness, 0, 100)
		clampInt(&next.Speed, 0, 100)
		cfgMu.Lock()
		cfg.Strip.LightState = next
		cfgMu.Unlock()
		saveConfigSoon()
		strip.requestApply()
		return nil, nil
	})

	// The tablet's music mode and color wheel stream colors here.
	api("/api/strip/live", false, func(w http.ResponseWriter, r *http.Request) (any, error) {
		var in struct{ Color string }
		if err := mergeJSON(r, &in); err != nil {
			return nil, err
		}
		strip.sendLive(parseHex(in.Color))
		return map[string]any{"ok": true}, nil
	})

	api("/api/strip/scan", false, func(w http.ResponseWriter, r *http.Request) (any, error) {
		strip.setBusy("Searching…")
		defer strip.setBusy("")
		found, err := scanStrips(6)
		if err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "devices": found}, nil
	})

	api("/api/strip/connect", false, func(w http.ResponseWriter, r *http.Request) (any, error) {
		var in struct{ Address, Name string }
		if err := mergeJSON(r, &in); err != nil || in.Address == "" {
			return nil, errors.New("pick a strip")
		}
		strip.disconnect()
		if err := strip.connect(in.Address); err != nil {
			return nil, err
		}
		cfgMu.Lock()
		cfg.Strip.Address, cfg.Strip.Name = in.Address, in.Name
		cfg.Strip.Proto = strip.proto
		saveConfigLocked()
		cfgMu.Unlock()
		strip.requestApply()
		return nil, nil
	})

	api("/api/strip/forget", false, func(w http.ResponseWriter, r *http.Request) (any, error) {
		strip.disconnect()
		cfgMu.Lock()
		cfg.Strip.Address, cfg.Strip.Name = "", ""
		saveConfigLocked()
		cfgMu.Unlock()
		return nil, nil
	})

	// Screen-sync frames: {"leds":[r,g,b,r,g,b,…], "avg":[r,g,b]}.
	api("/api/screen", true, func(w http.ResponseWriter, r *http.Request) (any, error) {
		var in struct {
			Leds []int `json:"leds"`
			Avg  []int `json:"avg"`
		}
		if err := mergeJSON(r, &in); err != nil {
			return nil, err
		}
		leds := make([]RGB, len(in.Leds)/3)
		for i := range leds {
			leds[i] = RGB{byteOf(in.Leds[i*3]), byteOf(in.Leds[i*3+1]), byteOf(in.Leds[i*3+2])}
		}
		var avg RGB
		if len(in.Avg) == 3 {
			avg = RGB{byteOf(in.Avg[0]), byteOf(in.Avg[1]), byteOf(in.Avg[2])}
		}
		aito.setScreen(leds, avg)
		cfgMu.Lock()
		follow := cfg.Strip.Mode == "screen"
		cfgMu.Unlock()
		if follow {
			strip.sendLive(avg)
		}
		return map[string]any{"ok": true}, nil
	})

	api("/api/capture", false, func(w http.ResponseWriter, r *http.Request) (any, error) {
		on := r.URL.Query().Get("on") == "1"
		cfgMu.Lock()
		cfg.CaptureAuto = on
		saveConfigLocked()
		cfgMu.Unlock()
		if on {
			return nil, capture.start()
		}
		capture.stop()
		return nil, nil
	})

	api("/api/devices/revoke", true, func(w http.ResponseWriter, r *http.Request) (any, error) {
		id := r.URL.Query().Get("id")
		cfgMu.Lock()
		defer cfgMu.Unlock()
		var keep []Device
		for _, d := range cfg.Devices {
			if id == "" || !strings.HasPrefix(d.Token, id) {
				keep = append(keep, d)
			}
		}
		cfg.Devices = keep
		saveConfigLocked()
		return map[string]any{"ok": true}, nil
	})

	api("/api/newcode", true, func(w http.ResponseWriter, r *http.Request) (any, error) {
		pairMu.Lock()
		pairCode = ""
		pairMu.Unlock()
		return nil, nil
	})

	api("/api/autostart", true, func(w http.ResponseWriter, r *http.Request) (any, error) {
		if r.Method == http.MethodPost {
			if err := setAutostart(r.URL.Query().Get("on") == "1"); err != nil {
				return nil, err
			}
		}
		return map[string]any{"ok": true, "on": autostartEnabled()}, nil
	})

	go http.Serve(ln, cors(mux))
	return nil
}

func byteOf(v int) uint8 {
	if v < 0 {
		return 0
	}
	if v > 255 {
		return 255
	}
	return uint8(v)
}

// The website is served from here, so cross-origin access is never needed.
func cors(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		h.ServeHTTP(w, r)
	})
}

func localIPs() []string {
	var out []string
	ifs, _ := net.Interfaces()
	for _, i := range ifs {
		if i.Flags&net.FlagUp == 0 || i.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := i.Addrs()
		for _, a := range addrs {
			if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil && !n.IP.IsLinkLocalUnicast() {
				if strings.HasPrefix(i.Name, "docker") || strings.HasPrefix(i.Name, "br-") || strings.HasPrefix(i.Name, "virbr") {
					continue
				}
				out = append(out, n.IP.String())
			}
		}
	}
	return out
}
