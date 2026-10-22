package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// LightState is what the PC page and the tablet app control. Both lights use it.
type LightState struct {
	On         bool   `json:"on"`
	Mode       string `json:"mode"` // color, effect, screen, music
	Color      string `json:"color"`
	Effect     string `json:"effect"` // AITOLED: effect name; strip: built-in effect id as a string
	Brightness int    `json:"brightness"`
	Speed      int    `json:"speed"`
}

type AitoConfig struct {
	LightState
	Layout     Layout `json:"layout"`
	Port       string `json:"port"`       // optional: force a serial port like /dev/ttyUSB0
	Smoothing  int    `json:"smoothing"`  // 0-95 %
	Saturation int    `json:"saturation"` // 50-200 %
}

type StripConfig struct {
	LightState
	Address string `json:"address"`
	Name    string `json:"name"`
	Proto   string `json:"proto"`
}

type Device struct {
	Name  string    `json:"name"`
	Token string    `json:"token"`
	Added time.Time `json:"added"`
}

type Config struct {
	Port    int         `json:"port"`
	Aito    AitoConfig  `json:"aito"`
	Strip   StripConfig `json:"strip"`
	Devices []Device    `json:"devices"`
	// Screen sync starts by itself when Glow starts (after the first time you pick a screen).
	CaptureAuto    bool   `json:"captureAuto"`
	CaptureRestore string `json:"captureRestore"`
}

var (
	cfgMu    sync.Mutex
	cfg      Config
	firstRun bool
)

func defaultConfig() Config {
	return Config{
		Port: 47820,
		Aito: AitoConfig{
			LightState: LightState{On: true, Mode: "screen", Color: "#ff7a1a", Effect: "rainbow", Brightness: 100, Speed: 50},
			Layout:     Layout{Start: "bottom-left", Clockwise: true, Depth: 12},
			Smoothing:  55,
			Saturation: 120,
		},
		Strip: StripConfig{
			LightState: LightState{On: true, Mode: "color", Color: "#7a5cff", Effect: "137", Brightness: 100, Speed: 50},
		},
	}
}

func configPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		dir = "."
	}
	return filepath.Join(dir, "glow", "config.json")
}

func loadConfig() {
	cfg = defaultConfig()
	b, err := os.ReadFile(configPath())
	if err != nil {
		firstRun = true
		return
	}
	if err := json.Unmarshal(b, &cfg); err != nil {
		fmt.Println("Config file is damaged, starting fresh:", err)
		cfg = defaultConfig()
	}
	if cfg.Port == 0 {
		cfg.Port = 47820
	}
}

func saveConfigLocked() {
	p := configPath()
	os.MkdirAll(filepath.Dir(p), 0o755)
	b, _ := json.MarshalIndent(cfg, "", "  ")
	tmp := p + ".tmp"
	if os.WriteFile(tmp, b, 0o600) == nil {
		os.Rename(tmp, p)
	}
}

// Saving is debounced so dragging a slider doesn't write the file 60 times a second.
var saveTimer *time.Timer

func saveConfigSoon() {
	if saveTimer != nil {
		saveTimer.Stop()
	}
	saveTimer = time.AfterFunc(800*time.Millisecond, func() {
		cfgMu.Lock()
		saveConfigLocked()
		cfgMu.Unlock()
	})
}

func parseHex(s string) RGB {
	s = strings.TrimPrefix(strings.TrimSpace(s), "#")
	if len(s) == 3 {
		s = string([]byte{s[0], s[0], s[1], s[1], s[2], s[2]})
	}
	v, err := strconv.ParseUint(s, 16, 32)
	if err != nil || len(s) != 6 {
		return RGB{255, 255, 255}
	}
	return RGB{uint8(v >> 16), uint8(v >> 8), uint8(v)}
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

func tokenValid(t string) bool {
	if t == "" {
		return false
	}
	cfgMu.Lock()
	defer cfgMu.Unlock()
	for _, d := range cfg.Devices {
		if d.Token == t {
			return true
		}
	}
	return false
}
