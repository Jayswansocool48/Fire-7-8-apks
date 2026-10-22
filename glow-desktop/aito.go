package main

import (
	"log"
	"math"
	"sync"
	"time"
)

// The AITOLED driver: finds the light, does the handshake, then streams frames.

type aitoDevice struct {
	mu        sync.Mutex
	port      *serialPort
	path      string
	info      AitoInfo
	connected bool
	status    string

	screen   []RGB // latest colors from the screen-sync page, in strip order
	avg      RGB
	screenAt time.Time

	smooth []float64
	test   string
	testAt time.Time
	fps    float64
}

var aito = &aitoDevice{status: "Looking for the light…"}

func (a *aitoDevice) setStatus(s string) {
	a.mu.Lock()
	if a.status != s {
		log.Printf("AITOLED: %s", s)
	}
	a.status = s
	a.mu.Unlock()
}

func aitoLoop() {
	for {
		if !aito.connect() {
			time.Sleep(2 * time.Second)
			continue
		}
		aito.stream()
	}
}

func (a *aitoDevice) connect() bool {
	cfgMu.Lock()
	forced := cfg.Aito.Port
	cfgMu.Unlock()
	ports := findAitoPorts()
	if forced != "" {
		ports = []string{forced}
	}
	if len(ports) == 0 {
		a.setStatus(serialHint(nil))
		return false
	}
	var lastErr error
	for _, path := range ports {
		p, err := openSerial(path)
		if err != nil {
			lastErr = err
			continue
		}
		info, ok := handshake(p)
		if !ok {
			p.Close()
			lastErr = nil
			a.setStatus("Found a USB serial device at " + path + " but it didn't answer like an AITOLED. Unplug and replug the light.")
			continue
		}
		p.Write(aitoStatePacket(info.raw))
		a.mu.Lock()
		a.port, a.path, a.info, a.connected = p, path, info, true
		a.smooth = nil
		a.status = "Connected"
		a.mu.Unlock()
		log.Printf("AITOLED %s connected on %s: sides %v (%d LEDs)", info.Model, path, info.Sides, info.Total())

		cfgMu.Lock()
		if cfg.Aito.Layout.Count() == 0 {
			useDeviceLayoutLocked(info)
			saveConfigLocked()
		}
		cfgMu.Unlock()
		return true
	}
	if lastErr != nil {
		a.setStatus(serialHint(lastErr))
	}
	return false
}

// The light reports its sections in order; on the monitor kits they are left, top and right.
func useDeviceLayoutLocked(info AitoInfo) {
	l := &cfg.Aito.Layout
	l.Left, l.Top, l.Right, l.Bottom = info.Sides[0], info.Sides[1], info.Sides[2], 0
	l.Offset = 0
}

func handshake(p *serialPort) (AitoInfo, bool) {
	buf := make([]byte, 0, 256)
	tmp := make([]byte, 256)
	for attempt := 0; attempt < 10; attempt++ {
		if _, err := p.Write(probeAito); err != nil {
			return AitoInfo{}, false
		}
		deadline := time.Now().Add(300 * time.Millisecond)
		for time.Now().Before(deadline) {
			n, _ := p.Read(tmp) // returns within 0.1 s
			buf = append(buf, tmp[:n]...)
			if info, ok := findAitoReply(buf); ok {
				return info, true
			}
		}
		if len(buf) > 200 {
			buf = buf[len(buf)-100:]
		}
	}
	return AitoInfo{}, false
}

func (a *aitoDevice) disconnect(reason string) {
	a.mu.Lock()
	if a.port != nil {
		a.port.Close()
	}
	a.port, a.connected = nil, false
	a.mu.Unlock()
	a.setStatus(reason)
}

func (a *aitoDevice) stream() {
	start := time.Now()
	var frames int
	fpsAt := time.Now()
	for {
		a.mu.Lock()
		p := a.port
		n := a.info.Total()
		a.mu.Unlock()
		if p == nil {
			return
		}
		cfgMu.Lock()
		c := cfg.Aito
		cfgMu.Unlock()
		if n == 0 {
			n = c.Layout.Count() + c.Layout.Offset
		}
		leds := a.render(c, n, time.Since(start).Seconds())
		pkt := aitoColorPacket(leds)
		if _, err := p.Write(pkt); err != nil {
			a.disconnect("The light was unplugged. Waiting for it…")
			return
		}
		frames++
		if el := time.Since(fpsAt); el > 2*time.Second {
			a.mu.Lock()
			a.fps = float64(frames) / el.Seconds()
			a.mu.Unlock()
			frames, fpsAt = 0, time.Now()
		}
		// Don't send faster than the 115200-baud line can carry (10 bits per byte), max ~40 fps.
		wire := time.Duration(float64(len(pkt)*10)/aitoBaud*1.15*float64(time.Second)) + 2*time.Millisecond
		if wire < 25*time.Millisecond {
			wire = 25 * time.Millisecond
		}
		time.Sleep(wire)
	}
}

func (a *aitoDevice) render(c AitoConfig, n int, t float64) []RGB {
	base := parseHex(c.Color)
	out := make([]RGB, n)

	a.mu.Lock()
	test, testAt := a.test, a.testAt
	screen, screenAt := a.screen, a.screenAt
	a.mu.Unlock()

	if test != "" && time.Since(testAt) < 8*time.Second {
		return testPattern(c.Layout, n)
	}
	if !c.On {
		return out
	}
	off := c.Layout.Offset
	switch c.Mode {
	case "color":
		for i := range out {
			out[i] = base
		}
	case "effect":
		copy(out[min(off, n):], renderEffect(c.Effect, max(0, n-off), t, c.Speed, base))
	case "screen":
		if time.Since(screenAt) < 3*time.Second && len(screen) > 0 {
			for i := 0; i < len(screen) && off+i < n; i++ {
				out[off+i] = saturate(screen[i], c.Saturation)
			}
			a.smoothen(out, c.Smoothing)
		} else {
			// Sync page isn't open: a slow dim pulse says "waiting for the screen".
			v := 0.06 + 0.06*math.Sin(t*1.5)
			for i := range out {
				out[i] = scale(base, v)
			}
		}
	}
	b := float64(c.Brightness) / 100
	for i := range out {
		out[i] = scale(out[i], b)
	}
	return out
}

func (a *aitoDevice) smoothen(out []RGB, pct int) {
	k := float64(max(0, min(95, pct))) / 100
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.smooth) != len(out)*3 {
		a.smooth = make([]float64, len(out)*3)
		k = 0
	}
	for i, c := range out {
		for j, v := range [3]uint8{c.R, c.G, c.B} {
			s := &a.smooth[i*3+j]
			*s = *s*k + float64(v)*(1-k)
		}
		out[i] = RGB{uint8(a.smooth[i*3]), uint8(a.smooth[i*3+1]), uint8(a.smooth[i*3+2])}
	}
}

func saturate(c RGB, pct int) RGB {
	if pct == 100 || pct == 0 {
		return c
	}
	f := float64(pct) / 100
	gray := 0.299*float64(c.R) + 0.587*float64(c.G) + 0.114*float64(c.B)
	ch := func(v uint8) uint8 {
		x := gray + (float64(v)-gray)*f
		return uint8(math.Max(0, math.Min(255, x)))
	}
	return RGB{ch(c.R), ch(c.G), ch(c.B)}
}

// testPattern: LED #1 red, #2 orange, #3 yellow, last LED blue, the first LED of each side green,
// everything else dim white. Shows where the strip starts and which way it runs.
func testPattern(l Layout, n int) []RGB {
	out := make([]RGB, n)
	for i := range out {
		out[i] = RGB{25, 25, 25}
	}
	off := l.Offset
	zones := l.zones()
	for i := 1; i < len(zones); i++ {
		if zones[i].side != zones[i-1].side && off+i < n {
			out[off+i] = RGB{0, 255, 0}
		}
	}
	for i, c := range []RGB{{255, 0, 0}, {255, 90, 0}, {255, 200, 0}} {
		if off+i < n {
			out[off+i] = c
		}
	}
	if n > 3 {
		out[n-1] = RGB{0, 60, 255}
	}
	return out
}

// setScreen receives one frame from the screen-sync page.
func (a *aitoDevice) setScreen(leds []RGB, avg RGB) {
	a.mu.Lock()
	a.screen, a.avg, a.screenAt = leds, avg, time.Now()
	a.mu.Unlock()
}

func (a *aitoDevice) screenActive() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return time.Since(a.screenAt) < 2*time.Second
}
