package main

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func hiddenCommand(name string, args ...string) *exec.Cmd { return exec.Command(name, args...) }

// ---------------------------------------------------------------- stats

type cpuTimes struct{ idle, total uint64 }

type sampler struct {
	prev     []cpuTimes
	rx, tx   uint64
	lastNet  time.Time
	tempPath string
}

func newSampler() *sampler {
	s := &sampler{tempPath: findCPUTemp()}
	s.prev = readCPU()
	s.rx, s.tx = readNet()
	s.lastNet = time.Now()
	return s
}

func readCPU() []cpuTimes {
	f, err := os.Open("/proc/stat")
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []cpuTimes
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fields := strings.Fields(sc.Text())
		if len(fields) < 5 || !strings.HasPrefix(fields[0], "cpu") {
			continue
		}
		var t cpuTimes
		for i, v := range fields[1:] {
			n, _ := strconv.ParseUint(v, 10, 64)
			if i < 8 { // user nice system idle iowait irq softirq steal
				t.total += n
			}
			if i == 3 || i == 4 {
				t.idle += n
			}
		}
		out = append(out, t)
	}
	return out
}

func readNet() (rx, tx uint64) {
	f, err := os.Open("/proc/net/dev")
	if err != nil {
		return
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := sc.Text()
		i := strings.Index(line, ":")
		if i < 0 {
			continue
		}
		name := strings.TrimSpace(line[:i])
		if name == "lo" || strings.HasPrefix(name, "docker") || strings.HasPrefix(name, "veth") || strings.HasPrefix(name, "virbr") {
			continue
		}
		f := strings.Fields(line[i+1:])
		if len(f) >= 9 {
			r, _ := strconv.ParseUint(f[0], 10, 64)
			t, _ := strconv.ParseUint(f[8], 10, 64)
			rx += r
			tx += t
		}
	}
	return
}

func findCPUTemp() string {
	dirs, _ := filepath.Glob("/sys/class/hwmon/hwmon*")
	best := ""
	for _, d := range dirs {
		name, _ := os.ReadFile(filepath.Join(d, "name"))
		n := strings.TrimSpace(string(name))
		switch n {
		case "k10temp", "zenpower", "coretemp", "cpu_thermal", "soc_thermal":
			return filepath.Join(d, "temp1_input")
		case "acpitz":
			if best == "" {
				best = filepath.Join(d, "temp1_input")
			}
		}
	}
	return best
}

func readMilli(path string) float64 {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	v, _ := strconv.ParseFloat(strings.TrimSpace(string(b)), 64)
	return v / 1000
}

func (s *sampler) sample() Stats {
	var st Stats
	cur := readCPU()
	if len(cur) == len(s.prev) && len(cur) > 0 {
		pct := func(a, b cpuTimes) float64 {
			dt := float64(b.total - a.total)
			if dt <= 0 {
				return 0
			}
			return 100 * (1 - float64(b.idle-a.idle)/dt)
		}
		st.CPU = pct(s.prev[0], cur[0])
		for i := 1; i < len(cur); i++ {
			st.Cores = append(st.Cores, pct(s.prev[i], cur[i]))
		}
	}
	s.prev = cur

	if f, err := os.Open("/proc/meminfo"); err == nil {
		sc := bufio.NewScanner(f)
		var avail uint64
		for sc.Scan() {
			fl := strings.Fields(sc.Text())
			if len(fl) < 2 {
				continue
			}
			v, _ := strconv.ParseUint(fl[1], 10, 64)
			switch fl[0] {
			case "MemTotal:":
				st.MemTotal = v * 1024
			case "MemAvailable:":
				avail = v * 1024
			}
		}
		f.Close()
		st.MemUsed = st.MemTotal - avail
	}

	var fs syscall.Statfs_t
	if syscall.Statfs("/", &fs) == nil {
		st.DiskTotal = fs.Blocks * uint64(fs.Bsize)
		st.DiskUsed = st.DiskTotal - fs.Bavail*uint64(fs.Bsize)
	}

	rx, tx := readNet()
	now := time.Now()
	if dt := now.Sub(s.lastNet).Seconds(); dt > 0 && rx >= s.rx && tx >= s.tx {
		st.NetRx = float64(rx-s.rx) / dt
		st.NetTx = float64(tx-s.tx) / dt
	}
	s.rx, s.tx, s.lastNet = rx, tx, now

	if s.tempPath != "" {
		st.CPUTemp = readMilli(s.tempPath)
	}
	if b, err := os.ReadFile("/proc/uptime"); err == nil {
		if f := strings.Fields(string(b)); len(f) > 0 {
			v, _ := strconv.ParseFloat(f[0], 64)
			st.Uptime = uint64(v)
		}
	}
	return st
}

func sampleGPU() *GPUStats {
	if g := nvidiaGPU(); g != nil {
		return g
	}
	// AMD (amdgpu driver)
	cards, _ := filepath.Glob("/sys/class/drm/card*/device/gpu_busy_percent")
	for _, busy := range cards {
		dev := filepath.Dir(busy)
		b, err := os.ReadFile(busy)
		if err != nil {
			continue
		}
		g := &GPUStats{Name: "AMD GPU"}
		g.Util, _ = strconv.ParseFloat(strings.TrimSpace(string(b)), 64)
		if n, err := os.ReadFile(filepath.Join(dev, "product_name")); err == nil && strings.TrimSpace(string(n)) != "" {
			g.Name = strings.TrimSpace(string(n))
		}
		mib := func(file string) float64 {
			v, err := os.ReadFile(filepath.Join(dev, file))
			if err != nil {
				return 0
			}
			n, _ := strconv.ParseFloat(strings.TrimSpace(string(v)), 64)
			return n / 1024 / 1024
		}
		g.VRAMUsed = mib("mem_info_vram_used")
		g.VRAMTotal = mib("mem_info_vram_total")
		if temps, _ := filepath.Glob(filepath.Join(dev, "hwmon", "hwmon*", "temp1_input")); len(temps) > 0 {
			g.Temp = readMilli(temps[0])
		}
		return g
	}
	return nil
}

// ---------------------------------------------------------------- media & volume

func have(cmd string) bool {
	_, err := exec.LookPath(cmd)
	return err == nil
}

func mediaControl(action string) error {
	if have("playerctl") {
		arg := map[string]string{"playpause": "play-pause", "next": "next", "prev": "previous", "stop": "stop"}[action]
		if arg != "" && exec.Command("playerctl", arg).Run() == nil {
			return nil
		}
	}
	return pressMediaKey(action)
}

type MediaInfo struct {
	Title    string  `json:"title"`
	Artist   string  `json:"artist"`
	Playing  bool    `json:"playing"`
	Volume   float64 `json:"volume"` // 0..1, -1 = unknown
	Muted    bool    `json:"muted"`
	MicMuted bool    `json:"micMuted"`
}

func wpctlVolume(target string) (float64, bool, bool) {
	out, err := exec.Command("wpctl", "get-volume", target).Output()
	if err != nil {
		return -1, false, false
	}
	// "Volume: 0.45 [MUTED]"
	f := strings.Fields(string(out))
	if len(f) < 2 {
		return -1, false, false
	}
	v, err := strconv.ParseFloat(f[1], 64)
	if err != nil {
		return -1, false, false
	}
	return v, strings.Contains(string(out), "MUTED"), true
}

func mediaInfo() MediaInfo {
	m := MediaInfo{Volume: -1}
	if have("playerctl") {
		if out, err := exec.Command("playerctl", "metadata", "--format", "{{status}}\t{{artist}}\t{{title}}").Output(); err == nil {
			f := strings.SplitN(strings.TrimSpace(string(out)), "\t", 3)
			if len(f) == 3 {
				m.Playing = f[0] == "Playing"
				m.Artist, m.Title = f[1], f[2]
			}
		}
	}
	if have("wpctl") {
		if v, muted, ok := wpctlVolume("@DEFAULT_AUDIO_SINK@"); ok {
			m.Volume, m.Muted = v, muted
		}
		if _, muted, ok := wpctlVolume("@DEFAULT_AUDIO_SOURCE@"); ok {
			m.MicMuted = muted
		}
	} else if have("pactl") {
		if out, err := exec.Command("pactl", "get-sink-volume", "@DEFAULT_SINK@").Output(); err == nil {
			// "Volume: front-left: 29491 /  45% / ..."
			s := string(out)
			if i := strings.Index(s, "%"); i > 0 {
				j := strings.LastIndex(s[:i], " ")
				if v, err := strconv.ParseFloat(strings.TrimSpace(s[j+1:i]), 64); err == nil {
					m.Volume = v / 100
				}
			}
		}
		if out, err := exec.Command("pactl", "get-sink-mute", "@DEFAULT_SINK@").Output(); err == nil {
			m.Muted = strings.Contains(string(out), "yes")
		}
		if out, err := exec.Command("pactl", "get-source-mute", "@DEFAULT_SOURCE@").Output(); err == nil {
			m.MicMuted = strings.Contains(string(out), "yes")
		}
	}
	return m
}

func setVolume(v float64) error {
	if v < 0 {
		v = 0
	}
	if v > 1 {
		v = 1
	}
	if have("wpctl") {
		return exec.Command("wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", fmt.Sprintf("%.2f", v)).Run()
	}
	if have("pactl") {
		return exec.Command("pactl", "set-sink-volume", "@DEFAULT_SINK@", fmt.Sprintf("%d%%", int(v*100+0.5))).Run()
	}
	return fmt.Errorf("no volume control found (install wireplumber or pulseaudio-utils)")
}

func toggleMute(mic bool) error {
	if have("wpctl") {
		t := "@DEFAULT_AUDIO_SINK@"
		if mic {
			t = "@DEFAULT_AUDIO_SOURCE@"
		}
		return exec.Command("wpctl", "set-mute", t, "toggle").Run()
	}
	if have("pactl") {
		if mic {
			return exec.Command("pactl", "set-source-mute", "@DEFAULT_SOURCE@", "toggle").Run()
		}
		return exec.Command("pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle").Run()
	}
	if mic {
		return pressMediaKey("micmute")
	}
	return pressMediaKey("mute")
}

func volumeSupported() bool    { return have("wpctl") || have("pactl") }
func mediaInfoSupported() bool { return have("playerctl") }

// ---------------------------------------------------------------- clipboard

func clipboardTools() (get, set []string) {
	if os.Getenv("WAYLAND_DISPLAY") != "" && have("wl-paste") && have("wl-copy") {
		return []string{"wl-paste", "--no-newline"}, []string{"wl-copy"}
	}
	if have("xclip") {
		return []string{"xclip", "-selection", "clipboard", "-o"}, []string{"xclip", "-selection", "clipboard", "-i"}
	}
	if have("xsel") {
		return []string{"xsel", "--clipboard", "--output"}, []string{"xsel", "--clipboard", "--input"}
	}
	return nil, nil
}

func clipboardSupported() bool { g, _ := clipboardTools(); return g != nil }

func getClipboard() (string, error) {
	g, _ := clipboardTools()
	if g == nil {
		return "", fmt.Errorf("install wl-clipboard (Wayland) or xclip (X11) for clipboard sharing")
	}
	out, err := exec.Command(g[0], g[1:]...).Output()
	return string(out), err
}

func setClipboard(text string) error {
	_, s := clipboardTools()
	if s == nil {
		return fmt.Errorf("install wl-clipboard (Wayland) or xclip (X11) for clipboard sharing")
	}
	cmd := exec.Command(s[0], s[1:]...)
	cmd.Stdin = strings.NewReader(text)
	// wl-copy and xclip keep running to serve the clipboard, so don't wait for them.
	if err := cmd.Start(); err != nil {
		return err
	}
	go cmd.Wait()
	return nil
}

// ---------------------------------------------------------------- autostart

func autostartPath() string {
	d, _ := os.UserConfigDir()
	return filepath.Join(d, "autostart", "deskmate.desktop")
}

func autostartEnabled() bool {
	_, err := os.Stat(autostartPath())
	return err == nil
}

func setAutostart(on bool) error {
	p := autostartPath()
	if !on {
		err := os.Remove(p)
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	exe, _ = filepath.EvalSymlinks(exe)
	os.MkdirAll(filepath.Dir(p), 0o755)
	entry := fmt.Sprintf("[Desktop Entry]\nType=Application\nName=Deskmate\nComment=Companion for the Deskmate tablet app\nExec=\"%s\" --background\nTerminal=false\nX-GNOME-Autostart-enabled=true\n", exe)
	return os.WriteFile(p, []byte(entry), 0o644)
}

func openURL(u string) { exec.Command("xdg-open", u).Start() }

func hideConsole() {}
