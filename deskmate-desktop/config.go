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

// Action is what a key does.
//
// Run on the PC (only actions saved here can run; the tablet just names the key):
//
//	keys  "ctrl+shift+t"     run  "code ~/game"     open  "https://…" or "~/Downloads"     text  "hello"
//
// Handled by the tablet:
//
//	folder (contents in Key.Keys), page "next"/"prev", media "playpause|next|prev|volumeup|volumedown|mute|micmute",
//	stat "cpu|ram|gpu|gputemp|cputemp|net|disk", clock, timer "<minutes>", tool "monitor|media|notes|dev"
type Action struct {
	Type  string `json:"type"`
	Value string `json:"value"`
}

// Key is one Stream Deck-style key at a grid position (row*cols + col).
type Key struct {
	ID     string `json:"id"`
	Pos    int    `json:"pos"`
	Title  string `json:"title"`
	Icon   string `json:"icon,omitempty"`  // built-in icon name (icons.json)
	Image  string `json:"image,omitempty"` // custom image as a data: URL, 144×144
	Color  string `json:"color,omitempty"` // key background
	Action Action `json:"action"`
	Keys   []Key  `json:"keys,omitempty"` // folder contents; position 0 is the Back key
}

type Page struct {
	Name string `json:"name"`
	Keys []Key  `json:"keys"`
	// Buttons is the pre-1.1 format, read once and converted.
	Buttons []legacyButton `json:"buttons,omitempty"`
}

type legacyButton struct {
	ID     string `json:"id"`
	Label  string `json:"label"`
	Color  string `json:"color"`
	Action Action `json:"action"`
}

type Device struct {
	Name  string    `json:"name"`
	Token string    `json:"token"`
	Added time.Time `json:"added"`
}

type Config struct {
	Version        int      `json:"version"`
	Port           int      `json:"port"`
	DevForwardPort int      `json:"devForwardPort"`
	Cols           int      `json:"cols"`
	Rows           int      `json:"rows"`
	Pages          []Page   `json:"pages"`
	Devices        []Device `json:"devices"`
}

const configVersion = 2

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

func newKey(pos int, title, icon, color string, a Action) Key {
	return Key{ID: newID(), Pos: pos, Title: title, Icon: icon, Color: color, Action: a}
}

func act(t, v string) Action { return Action{Type: t, Value: v} }

func defaultPages() []Page {
	gameDev := newKey(4, "Game dev", "gamepad", "#1F3A5F", act("folder", ""))
	gameDev.Keys = []Key{
		newKey(1, "Run", "play", "#2D6A4F", act("keys", "f5")),
		newKey(2, "Run scene", "video", "#2D6A4F", act("keys", "f6")),
		newKey(3, "Stop", "record", "#9B2C2C", act("keys", "f8")),
		newKey(4, "Save all", "save", "#2B2B33", act("keys", "ctrl+shift+s")),
		newKey(5, "Find", "search", "#2B2B33", act("keys", "ctrl+f")),
		newKey(6, "Comment", "code", "#2B2B33", act("keys", "ctrl+k")),
		newKey(7, "Dev panel", "sliders", "#1F3A5F", act("tool", "dev")),
		newKey(8, "Terminal", "terminal", "#2B2B33", terminalAction()),
	}
	tools := newKey(14, "Tools", "grid", "#3B2F5C", act("folder", ""))
	tools.Keys = []Key{
		newKey(1, "Monitor", "chart", "#134E4A", act("tool", "monitor")),
		newKey(2, "Media", "music", "#702459", act("tool", "media")),
		newKey(3, "Notes", "notes", "#744210", act("tool", "notes")),
		newKey(4, "Dev panel", "sliders", "#1F3A5F", act("tool", "dev")),
		newKey(5, "Volume -", "volume-down", "#2B2B33", act("media", "volumedown")),
		newKey(6, "Volume +", "volume-up", "#2B2B33", act("media", "volumeup")),
		newKey(7, "Net", "network", "#1A1A1E", act("stat", "net")),
		newKey(8, "Disk", "disk", "#1A1A1E", act("stat", "disk")),
		newKey(9, "Lock", "lock", "#2B2B33", lockAction()),
	}
	main := Page{Name: "Main", Keys: []Key{
		newKey(0, "Terminal", "terminal", "#2B2B33", terminalAction()),
		newKey(1, "Files", "folder", "#2B4C7E", act("open", "~")),
		newKey(2, "Browser", "globe", "#2B6CB0", act("open", "https://duckduckgo.com")),
		newKey(3, "Screenshot", "camera", "#553C9A", act("keys", "print")),
		gameDev,
		newKey(5, "Previous", "prev", "#1A1A1E", act("media", "prev")),
		newKey(6, "Play/Pause", "play", "#1A1A1E", act("media", "playpause")),
		newKey(7, "Next", "next", "#1A1A1E", act("media", "next")),
		newKey(8, "Sound", "volume", "#1A1A1E", act("media", "mute")),
		newKey(9, "Mic", "mic", "#1A1A1E", act("media", "micmute")),
		newKey(10, "CPU", "cpu", "#1A1A1E", act("stat", "cpu")),
		newKey(11, "RAM", "memory", "#1A1A1E", act("stat", "ram")),
		newKey(12, "Clock", "clock", "#1A1A1E", act("clock", "")),
		newKey(13, "Focus", "timer", "#1A1A1E", act("timer", "25")),
		tools,
	}}
	edit := Page{Name: "Edit", Keys: []Key{
		newKey(0, "Copy", "copy", "#22543D", act("keys", "ctrl+c")),
		newKey(1, "Paste", "paste", "#22543D", act("keys", "ctrl+v")),
		newKey(2, "Undo", "undo", "#7B341E", act("keys", "ctrl+z")),
		newKey(3, "Redo", "redo", "#7B341E", act("keys", "ctrl+shift+z")),
		newKey(4, "Close", "close", "#9B2C2C", act("keys", "alt+f4")),
		newKey(5, "Workspace up", "arrow-up", "#2B2B33", workspaceAction(false)),
		newKey(6, "Workspace down", "arrow-down", "#2B2B33", workspaceAction(true)),
		newKey(7, "Save", "save", "#2B2B33", act("keys", "ctrl+s")),
		newKey(8, "Find", "search", "#2B2B33", act("keys", "ctrl+f")),
		newKey(9, "Lock", "lock", "#2B2B33", lockAction()),
	}}
	return []Page{main, edit}
}

func defaultConfig() Config {
	return Config{Version: configVersion, Port: 47800, DevForwardPort: 47810, Cols: 5, Rows: 3, Pages: defaultPages()}
}

// migrate turns the old button lists into keys laid out in order.
func migrate(c *Config) {
	if c.Cols == 0 || c.Rows == 0 {
		c.Cols, c.Rows = 5, 3
	}
	for pi := range c.Pages {
		p := &c.Pages[pi]
		if len(p.Buttons) > 0 && len(p.Keys) == 0 {
			for i, b := range p.Buttons {
				p.Keys = append(p.Keys, Key{ID: b.ID, Pos: i, Title: b.Label, Color: b.Color, Icon: guessIcon(b.Action), Action: b.Action})
			}
		}
		p.Buttons = nil
	}
	c.Version = configVersion
}

func guessIcon(a Action) string {
	switch a.Type {
	case "run":
		return "terminal"
	case "open":
		return "globe"
	case "text":
		return "keyboard"
	}
	return "keyboard"
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
		if cfg.Version < configVersion {
			migrate(&cfg)
			saveConfigLocked()
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

func findIn(keys []Key, id string) (Key, bool) {
	for _, k := range keys {
		if k.ID == id {
			return k, true
		}
		if k, ok := findIn(k.Keys, id); ok {
			return k, true
		}
	}
	return Key{}, false
}

func findKey(id string) (Key, bool) {
	cfgMu.Lock()
	defer cfgMu.Unlock()
	for _, p := range cfg.Pages {
		if k, ok := findIn(p.Keys, id); ok {
			return k, true
		}
	}
	return Key{}, false
}

// sanitize fixes up a deck sent by the settings page: fills in IDs, keeps keys inside the grid.
func sanitizeKeys(keys []Key, cells int, folder bool) []Key {
	out := keys[:0]
	used := map[int]bool{}
	for _, k := range keys {
		if k.Pos < 0 || k.Pos >= cells || used[k.Pos] || (folder && k.Pos == 0) {
			continue
		}
		used[k.Pos] = true
		if k.ID == "" {
			k.ID = newID()
		}
		if k.Action.Type == "folder" {
			k.Keys = sanitizeKeys(k.Keys, cells, true)
		} else {
			k.Keys = nil
		}
		out = append(out, k)
	}
	return out
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
