package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Action is what a macro button does. Only actions defined here on the PC can run;
// the tablet just says which button was pressed.
type Action struct {
	Type  string `json:"type"`  // "keys", "run", "open", "text"
	Value string `json:"value"` // "ctrl+shift+t", "gnome-terminal", "https://…", "hello"
}

type Button struct {
	ID     string `json:"id"`
	Label  string `json:"label"`
	Color  string `json:"color"`
	Action Action `json:"action"`
}

type Page struct {
	Name    string   `json:"name"`
	Buttons []Button `json:"buttons"`
}

type Device struct {
	Name  string    `json:"name"`
	Token string    `json:"token"`
	Added time.Time `json:"added"`
}

type Config struct {
	Port           int      `json:"port"`
	DevForwardPort int      `json:"devForwardPort"`
	Pages          []Page   `json:"pages"`
	Devices        []Device `json:"devices"`
}

var (
	cfgMu   sync.Mutex
	cfg     Config
	cfgPath string
)

func configDir() string {
	d, err := os.UserConfigDir()
	if err != nil {
		d = "."
	}
	return filepath.Join(d, "deskmate")
}

func newID() string {
	b := make([]byte, 6)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func newToken() string {
	b := make([]byte, 24)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func newPairCode() string {
	n, _ := rand.Int(rand.Reader, big.NewInt(1000000))
	return fmt.Sprintf("%06d", n.Int64())
}

func defaultConfig() Config {
	b := func(label, color, typ, value string) Button {
		return Button{ID: newID(), Label: label, Color: color, Action: Action{Type: typ, Value: value}}
	}
	ba := func(label, color string, a Action) Button {
		return Button{ID: newID(), Label: label, Color: color, Action: a}
	}
	general := Page{Name: "Desktop", Buttons: []Button{
		ba("Terminal", "#3B4252", terminalAction()),
		b("Files", "#5E81AC", "open", "~"),
		b("Browser", "#4C8BF5", "open", "https://duckduckgo.com"),
		b("Screenshot", "#B48EAD", "keys", "print"),
		b("Copy", "#2E7D6B", "keys", "ctrl+c"),
		b("Paste", "#2E7D6B", "keys", "ctrl+v"),
		b("Undo", "#A3683A", "keys", "ctrl+z"),
		b("Redo", "#A3683A", "keys", "ctrl+shift+z"),
		ba("Prev workspace", "#4A5568", workspaceAction(false)),
		ba("Next workspace", "#4A5568", workspaceAction(true)),
		b("Close window", "#BF616A", "keys", "alt+f4"),
		ba("Lock screen", "#2D3748", lockAction()),
	}}
	gamedev := Page{Name: "Game dev", Buttons: []Button{
		b("Run (F5)", "#478CBF", "keys", "f5"),
		b("Run scene (F6)", "#478CBF", "keys", "f6"),
		b("Stop (F8)", "#BF616A", "keys", "f8"),
		b("Save all", "#2E7D6B", "keys", "ctrl+shift+s"),
		b("Find", "#5E81AC", "keys", "ctrl+f"),
		b("Comment line", "#4A5568", "keys", "ctrl+k"),
	}}
	return Config{Port: 47800, DevForwardPort: 47810, Pages: []Page{general, gamedev}}
}

func loadConfig() {
	cfgPath = filepath.Join(configDir(), "config.json")
	data, err := os.ReadFile(cfgPath)
	if err == nil && json.Unmarshal(data, &cfg) == nil && len(cfg.Pages) > 0 {
		if cfg.Port == 0 {
			cfg.Port = 47800
		}
		if cfg.DevForwardPort == 0 {
			cfg.DevForwardPort = 47810
		}
		return
	}
	cfg = defaultConfig()
	saveConfigLocked()
}

// saveConfigLocked writes the config; callers hold cfgMu (or are single-threaded at startup).
func saveConfigLocked() {
	os.MkdirAll(filepath.Dir(cfgPath), 0o700)
	data, _ := json.MarshalIndent(cfg, "", "  ")
	tmp := cfgPath + ".tmp"
	if os.WriteFile(tmp, data, 0o600) == nil {
		os.Rename(tmp, cfgPath)
	}
}

func findButton(id string) (Button, bool) {
	cfgMu.Lock()
	defer cfgMu.Unlock()
	for _, p := range cfg.Pages {
		for _, b := range p.Buttons {
			if b.ID == id {
				return b, true
			}
		}
	}
	return Button{}, false
}

func tokenValid(tok string) (string, bool) {
	if tok == "" {
		return "", false
	}
	cfgMu.Lock()
	defer cfgMu.Unlock()
	for _, d := range cfg.Devices {
		if d.Token == tok {
			return d.Name, true
		}
	}
	return "", false
}
