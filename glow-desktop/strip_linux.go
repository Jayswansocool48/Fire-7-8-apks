package main

import (
	"errors"
	"log"
	"math"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/godbus/dbus/v5"
)

// The Bluetooth strip driver, through BlueZ (Linux's Bluetooth service) over D-Bus.

type stripDevice struct {
	mu        sync.Mutex
	connected bool
	busy      string // what we're doing right now, shown in the UI
	status    string
	devPath   dbus.ObjectPath
	charPath  dbus.ObjectPath
	proto     string

	liveMu   sync.Mutex
	live     *RGB // latest screen color waiting to be sent
	lastLive RGB
	wake     chan struct{}
	applyReq chan struct{}
}

var strip = &stripDevice{status: "Not set up yet", wake: make(chan struct{}, 1), applyReq: make(chan struct{}, 1)}

type FoundStrip struct {
	Name    string `json:"name"`
	Address string `json:"address"`
	RSSI    int    `json:"rssi"`
	Likely  bool   `json:"likely"`
}

type managed map[dbus.ObjectPath]map[string]map[string]dbus.Variant

func bluez() (*dbus.Conn, managed, error) {
	conn, err := dbus.SystemBus()
	if err != nil {
		return nil, nil, errors.New("can't reach the Bluetooth service (BlueZ) — is Bluetooth turned on?")
	}
	var objs managed
	if err := conn.Object("org.bluez", "/").Call("org.freedesktop.DBus.ObjectManager.GetManagedObjects", 0).Store(&objs); err != nil {
		return nil, nil, errors.New("Bluetooth isn't available on this computer")
	}
	return conn, objs, nil
}

func adapterPath(objs managed) (dbus.ObjectPath, error) {
	var paths []string
	for p, ifs := range objs {
		if _, ok := ifs["org.bluez.Adapter1"]; ok {
			paths = append(paths, string(p))
		}
	}
	if len(paths) == 0 {
		return "", errors.New("no Bluetooth adapter found. Turn Bluetooth on in Settings")
	}
	sort.Strings(paths)
	return dbus.ObjectPath(paths[0]), nil
}

func str(v dbus.Variant) string { s, _ := v.Value().(string); return s }

func likelyStrip(name string) bool {
	n := strings.ToUpper(name)
	for _, k := range []string{"LEDBLE", "ELK", "BLEDOM", "MELK", "TRIONES", "LEDBLUE", "QHM", "DREAM", "LEDNET", "HAPPYLIGHT", "LED-", "LAMP"} {
		if strings.Contains(n, k) {
			return true
		}
	}
	return false
}

// scanStrips looks for Bluetooth LE devices for a few seconds.
func scanStrips(seconds int) ([]FoundStrip, error) {
	conn, objs, err := bluez()
	if err != nil {
		return nil, err
	}
	ad, err := adapterPath(objs)
	if err != nil {
		return nil, err
	}
	a := conn.Object("org.bluez", ad)
	if v, err := a.GetProperty("org.bluez.Adapter1.Powered"); err == nil {
		if on, _ := v.Value().(bool); !on {
			a.Call("org.freedesktop.DBus.Properties.Set", 0, "org.bluez.Adapter1", "Powered", dbus.MakeVariant(true))
			time.Sleep(time.Second)
		}
	}
	a.Call("org.bluez.Adapter1.SetDiscoveryFilter", 0, map[string]dbus.Variant{"Transport": dbus.MakeVariant("le")})
	started := a.Call("org.bluez.Adapter1.StartDiscovery", 0).Err == nil
	time.Sleep(time.Duration(seconds) * time.Second)
	if started {
		a.Call("org.bluez.Adapter1.StopDiscovery", 0)
	}
	_, objs, err = bluez()
	if err != nil {
		return nil, err
	}
	var out []FoundStrip
	for _, ifs := range objs {
		d, ok := ifs["org.bluez.Device1"]
		if !ok {
			continue
		}
		name := str(d["Name"])
		if name == "" {
			name = str(d["Alias"])
		}
		addr := str(d["Address"])
		if name == "" || strings.ReplaceAll(name, "-", ":") == addr {
			continue
		}
		rssi := -999
		if v, ok := d["RSSI"]; ok {
			if r, ok := v.Value().(int16); ok {
				rssi = int(r)
			}
		}
		out = append(out, FoundStrip{Name: name, Address: addr, RSSI: rssi, Likely: likelyStrip(name)})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Likely != out[j].Likely {
			return out[i].Likely
		}
		return out[i].RSSI > out[j].RSSI
	})
	return out, nil
}

func (s *stripDevice) setBusy(b string) {
	s.mu.Lock()
	s.busy = b
	s.mu.Unlock()
}

func (s *stripDevice) setStatus(st string) {
	s.mu.Lock()
	if s.status != st {
		log.Printf("Strip: %s", st)
	}
	s.status = st
	s.mu.Unlock()
}

// connect opens the GATT connection to the strip at addr.
func (s *stripDevice) connect(addr string) error {
	s.setBusy("Connecting…")
	defer s.setBusy("")
	conn, objs, err := bluez()
	if err != nil {
		return err
	}
	ad, err := adapterPath(objs)
	if err != nil {
		return err
	}
	devPath := dbus.ObjectPath(string(ad) + "/dev_" + strings.ReplaceAll(strings.ToUpper(addr), ":", "_"))
	if _, ok := objs[devPath]; !ok {
		// BlueZ forgets devices it hasn't seen lately: look for it again.
		a := conn.Object("org.bluez", ad)
		a.Call("org.bluez.Adapter1.SetDiscoveryFilter", 0, map[string]dbus.Variant{"Transport": dbus.MakeVariant("le")})
		a.Call("org.bluez.Adapter1.StartDiscovery", 0)
		for i := 0; i < 20; i++ {
			time.Sleep(500 * time.Millisecond)
			if _, objs, _ = bluez(); objs != nil {
				if _, ok := objs[devPath]; ok {
					break
				}
			}
		}
		a.Call("org.bluez.Adapter1.StopDiscovery", 0)
		if _, ok := objs[devPath]; !ok {
			return errors.New("can't find the strip. Make sure it's plugged in and not connected to a phone (close Lotus Lantern)")
		}
	}
	dev := conn.Object("org.bluez", devPath)
	if call := dev.Call("org.bluez.Device1.Connect", 0); call.Err != nil && !strings.Contains(call.Err.Error(), "Already") {
		return errors.New("the strip didn't accept the connection (" + call.Err.Error() + "). If a phone is connected to it, close Lotus Lantern there")
	}
	var char dbus.ObjectPath
	var proto string
	for i := 0; i < 40 && char == ""; i++ {
		_, objs, _ = bluez()
		for p, ifs := range objs {
			c, ok := ifs["org.bluez.GattCharacteristic1"]
			if !ok || !strings.HasPrefix(string(p), string(devPath)+"/") {
				continue
			}
			if pr, ok := stripWriteUUIDs[strings.ToLower(str(c["UUID"]))]; ok {
				char, proto = p, pr
				break
			}
		}
		if char == "" {
			time.Sleep(250 * time.Millisecond)
		}
	}
	if char == "" {
		dev.Call("org.bluez.Device1.Disconnect", 0)
		return errors.New("connected, but this doesn't look like an LED strip controller Glow knows")
	}
	s.mu.Lock()
	s.devPath, s.charPath, s.proto, s.connected = devPath, char, proto, true
	s.status = "Connected"
	s.mu.Unlock()
	log.Printf("Strip connected: %s (%s protocol)", addr, proto)
	return nil
}

func (s *stripDevice) disconnect() {
	s.mu.Lock()
	dp := s.devPath
	s.connected = false
	s.charPath = ""
	s.mu.Unlock()
	if dp != "" {
		if conn, err := dbus.SystemBus(); err == nil {
			conn.Object("org.bluez", dp).Call("org.bluez.Device1.Disconnect", 0)
		}
	}
}

func (s *stripDevice) write(b []byte) error {
	if b == nil {
		return nil
	}
	s.mu.Lock()
	cp, ok := s.charPath, s.connected
	s.mu.Unlock()
	if !ok {
		return errors.New("not connected")
	}
	conn, err := dbus.SystemBus()
	if err != nil {
		return err
	}
	ch := conn.Object("org.bluez", cp)
	err = ch.Call("org.bluez.GattCharacteristic1.WriteValue", 0, b, map[string]dbus.Variant{"type": dbus.MakeVariant("command")}).Err
	if err != nil {
		err = ch.Call("org.bluez.GattCharacteristic1.WriteValue", 0, b, map[string]dbus.Variant{}).Err
	}
	if err != nil {
		s.mu.Lock()
		s.connected = false
		s.mu.Unlock()
		s.setStatus("Lost the connection. Reconnecting…")
	}
	return err
}

// apply sends the whole saved state (power, brightness, color or effect).
func (s *stripDevice) apply() error {
	cfgMu.Lock()
	c := cfg.Strip
	cfgMu.Unlock()
	s.mu.Lock()
	proto := s.proto
	s.mu.Unlock()
	if !c.On {
		return s.write(stripPower(proto, false))
	}
	if err := s.write(stripPower(proto, true)); err != nil {
		return err
	}
	time.Sleep(40 * time.Millisecond)
	bri := stripBrightness(proto, c.Brightness)
	switch c.Mode {
	case "effect":
		id := atoiDefault(c.Effect, int(stripEffects(proto)[0].ID))
		s.write(stripEffect(proto, id, c.Speed))
		time.Sleep(40 * time.Millisecond)
		s.write(stripSpeed(proto, c.Speed))
	case "screen", "music":
		// colors stream in through sendLive
	default:
		col := parseHex(c.Color)
		if bri == nil {
			col = scale(col, float64(c.Brightness)/100)
		}
		s.write(stripColor(proto, col))
	}
	if bri != nil {
		time.Sleep(40 * time.Millisecond)
		s.write(bri)
	}
	return nil
}

func atoiDefault(s string, d int) int {
	n := 0
	if s == "" {
		return d
	}
	for _, ch := range s {
		if ch < '0' || ch > '9' {
			return d
		}
		n = n*10 + int(ch-'0')
	}
	return n
}

// requestApply asks the worker to resend the state (coalesces fast slider drags).
func (s *stripDevice) requestApply() {
	select {
	case s.applyReq <- struct{}{}:
	default:
	}
}

// sendLive queues a screen color; only the newest one is sent.
func (s *stripDevice) sendLive(c RGB) {
	s.liveMu.Lock()
	s.live = &c
	s.liveMu.Unlock()
	select {
	case s.wake <- struct{}{}:
	default:
	}
}

// stripLoop owns the Bluetooth connection: reconnects, applies changes and streams live colors.
func stripLoop() {
	retry := time.Now()
	lastCheck := time.Now()
	tick := time.NewTicker(70 * time.Millisecond) // at most ~14 live colors a second
	defer tick.Stop()
	for {
		select {
		case <-strip.applyReq:
			if strip.isConnected() {
				strip.apply()
			}
		case <-tick.C:
		case <-strip.wake:
		}
		cfgMu.Lock()
		addr := cfg.Strip.Address
		c := cfg.Strip
		cfgMu.Unlock()
		if addr == "" {
			strip.setStatus("Not set up yet")
			continue
		}
		if strip.isConnected() && time.Since(lastCheck) > 15*time.Second {
			lastCheck = time.Now()
			if !strip.stillConnected() {
				strip.mu.Lock()
				strip.connected = false
				strip.mu.Unlock()
				strip.setStatus("The strip went away (unplugged?). Reconnecting…")
			}
		}
		if !strip.isConnected() {
			if time.Now().Before(retry) {
				continue
			}
			if err := strip.connect(addr); err != nil {
				strip.setStatus(err.Error())
				retry = time.Now().Add(10 * time.Second)
				continue
			}
			strip.apply()
		}
		strip.liveMu.Lock()
		l := strip.live
		strip.live = nil
		strip.liveMu.Unlock()
		if l != nil && c.On && (c.Mode == "screen" || c.Mode == "music") {
			col := *l
			strip.mu.Lock()
			proto := strip.proto
			strip.mu.Unlock()
			if stripBrightness(proto, 100) == nil {
				col = scale(col, float64(c.Brightness)/100)
			}
			if colorDist(col, strip.lastLive) > 6 {
				strip.lastLive = col
				strip.write(stripColor(proto, col))
			}
		}
	}
}

func colorDist(a, b RGB) float64 {
	dr, dg, db := float64(a.R)-float64(b.R), float64(a.G)-float64(b.G), float64(a.B)-float64(b.B)
	return math.Sqrt(dr*dr + dg*dg + db*db)
}

func (s *stripDevice) stillConnected() bool {
	s.mu.Lock()
	dp := s.devPath
	s.mu.Unlock()
	conn, err := dbus.SystemBus()
	if err != nil || dp == "" {
		return false
	}
	v, err := conn.Object("org.bluez", dp).GetProperty("org.bluez.Device1.Connected")
	if err != nil {
		return false
	}
	on, _ := v.Value().(bool)
	return on
}

func (s *stripDevice) isConnected() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.connected
}
