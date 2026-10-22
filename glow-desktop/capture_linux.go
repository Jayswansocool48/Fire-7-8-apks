package main

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/godbus/dbus/v5"
)

// Screen capture that keeps running in the background, on Wayland (COSMIC, GNOME, KDE) and X11:
// the desktop's screen-sharing portal hands us a PipeWire stream, and GStreamer shrinks it
// to a tiny picture that we read the edge colors from.

const capW, capH = 64, 36

type capturer struct {
	mu      sync.Mutex
	state   string // off, starting, on, error
	message string
	cmd     *exec.Cmd
	session dbus.ObjectPath
	stopped bool
}

var capture = &capturer{state: "off"}

func (c *capturer) info() map[string]any {
	c.mu.Lock()
	defer c.mu.Unlock()
	return map[string]any{"state": c.state, "message": c.message, "available": captureToolsMissing() == ""}
}

func (c *capturer) set(state, msg string) {
	c.mu.Lock()
	c.state, c.message = state, msg
	c.mu.Unlock()
	if msg != "" {
		log.Printf("Screen sync: %s", msg)
	}
}

func captureToolsMissing() string {
	if _, err := exec.LookPath("gst-launch-1.0"); err != nil {
		return "Screen sync needs GStreamer: sudo apt install gstreamer1.0-tools gstreamer1.0-pipewire"
	}
	return ""
}

func (c *capturer) start() error {
	c.mu.Lock()
	if c.state == "starting" || c.state == "on" {
		c.mu.Unlock()
		return nil
	}
	c.state, c.message, c.stopped = "starting", "Choose your screen in the window that pops up on the PC…", false
	c.mu.Unlock()
	if m := captureToolsMissing(); m != "" {
		c.set("error", m)
		return errors.New(m)
	}
	go func() {
		if err := c.run(); err != nil {
			c.mu.Lock()
			stopped := c.stopped
			c.mu.Unlock()
			if stopped {
				c.set("off", "")
			} else {
				c.set("error", err.Error())
			}
		}
	}()
	return nil
}

func (c *capturer) stop() {
	c.mu.Lock()
	c.stopped = true
	cmd, sess := c.cmd, c.session
	c.cmd, c.session = nil, ""
	c.state, c.message = "off", ""
	c.mu.Unlock()
	if cmd != nil && cmd.Process != nil {
		cmd.Process.Kill()
	}
	if sess != "" {
		if conn, err := dbus.SessionBus(); err == nil {
			conn.Object("org.freedesktop.portal.Desktop", sess).Call("org.freedesktop.portal.Session.Close", 0)
		}
	}
}

type portal struct {
	conn    *dbus.Conn
	obj     dbus.BusObject
	sender  string
	signals chan *dbus.Signal
	n       int
}

func newPortal() (*portal, error) {
	conn, err := dbus.SessionBus()
	if err != nil {
		return nil, errors.New("can't reach the desktop session")
	}
	p := &portal{conn: conn, obj: conn.Object("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop")}
	p.sender = strings.ReplaceAll(strings.TrimPrefix(conn.Names()[0], ":"), ".", "_")
	p.signals = make(chan *dbus.Signal, 10)
	conn.Signal(p.signals)
	conn.AddMatchSignal(dbus.WithMatchInterface("org.freedesktop.portal.Request"), dbus.WithMatchMember("Response"))
	return p, nil
}

// call runs a portal method that answers through a Request object, and waits for the answer.
func (p *portal) call(method string, timeout time.Duration, args ...any) (map[string]dbus.Variant, error) {
	p.n++
	token := fmt.Sprintf("glow%d_%d", os.Getpid(), p.n)
	path := dbus.ObjectPath("/org/freedesktop/portal/desktop/request/" + p.sender + "/" + token)
	opts := args[len(args)-1].(map[string]dbus.Variant)
	opts["handle_token"] = dbus.MakeVariant(token)
	if err := p.obj.Call("org.freedesktop.portal.ScreenCast."+method, 0, args...).Err; err != nil {
		return nil, fmt.Errorf("screen sharing isn't available on this desktop (%v)", err)
	}
	deadline := time.After(timeout)
	for {
		select {
		case s := <-p.signals:
			if s.Path != path || len(s.Body) < 2 {
				continue
			}
			code, _ := s.Body[0].(uint32)
			res, _ := s.Body[1].(map[string]dbus.Variant)
			switch code {
			case 0:
				return res, nil
			case 1:
				return nil, errors.New("screen sharing was cancelled")
			default:
				return nil, errors.New("the desktop refused screen sharing")
			}
		case <-deadline:
			return nil, errors.New("no answer from the screen-sharing window")
		}
	}
}

func (c *capturer) run() error {
	p, err := newPortal()
	if err != nil {
		return err
	}
	res, err := p.call("CreateSession", 30*time.Second, map[string]dbus.Variant{"session_handle_token": dbus.MakeVariant(fmt.Sprintf("glowsess%d", os.Getpid()))})
	if err != nil {
		return err
	}
	sess := dbus.ObjectPath(str(res["session_handle"]))
	c.mu.Lock()
	c.session = sess
	c.mu.Unlock()

	cfgMu.Lock()
	restore := cfg.CaptureRestore
	cfgMu.Unlock()
	sel := map[string]dbus.Variant{
		"types":        dbus.MakeVariant(uint32(1)), // a monitor
		"multiple":     dbus.MakeVariant(false),
		"cursor_mode":  dbus.MakeVariant(uint32(1)), // hide the cursor
		"persist_mode": dbus.MakeVariant(uint32(2)), // remember the choice, so next time there's no popup
	}
	if restore != "" {
		sel["restore_token"] = dbus.MakeVariant(restore)
	}
	if _, err := p.call("SelectSources", 30*time.Second, sess, sel); err != nil {
		// Older portals don't know persist_mode/cursor_mode.
		delete(sel, "persist_mode")
		delete(sel, "restore_token")
		delete(sel, "cursor_mode")
		if _, err = p.call("SelectSources", 30*time.Second, sess, sel); err != nil {
			return err
		}
	}
	res, err = p.call("Start", 5*time.Minute, sess, "", map[string]dbus.Variant{})
	if err != nil {
		return err
	}
	if t := str(res["restore_token"]); t != "" {
		cfgMu.Lock()
		cfg.CaptureRestore = t
		saveConfigLocked()
		cfgMu.Unlock()
	}
	node, err := firstNode(res["streams"])
	if err != nil {
		return err
	}
	var fd dbus.UnixFD
	if err := p.obj.Call("org.freedesktop.portal.ScreenCast.OpenPipeWireRemote", 0, sess, map[string]dbus.Variant{}).Store(&fd); err != nil {
		return fmt.Errorf("couldn't open the screen stream: %v", err)
	}
	pw := os.NewFile(uintptr(fd), "pipewire")
	defer pw.Close()

	cmd := exec.Command("gst-launch-1.0", "-q",
		"pipewiresrc", "fd=3", "path="+strconv.Itoa(int(node)), "always-copy=true", "do-timestamp=true", "!",
		"videorate", "max-rate=30", "!",
		"videoconvert", "!", "videoscale", "method=bilinear", "!",
		fmt.Sprintf("video/x-raw,format=RGB,width=%d,height=%d,pixel-aspect-ratio=1/1", capW, capH), "!",
		"fdsink", "fd=1", "sync=false")
	cmd.ExtraFiles = []*os.File{pw}
	out, _ := cmd.StdoutPipe()
	errOut, _ := cmd.StderrPipe()
	if err := cmd.Start(); err != nil {
		return err
	}
	c.mu.Lock()
	c.cmd = cmd
	c.state, c.message = "on", ""
	c.mu.Unlock()
	log.Printf("Screen sync started (PipeWire node %d)", node)

	var stderr strings.Builder
	go io.Copy(&stderr, io.LimitReader(errOut, 4096))
	readFrames(bufio.NewReaderSize(out, capW*capH*3*2))
	cmd.Wait()
	c.mu.Lock()
	stopped := c.stopped
	c.mu.Unlock()
	if stopped {
		return errors.New("stopped")
	}
	msg := strings.TrimSpace(stderr.String())
	if strings.Contains(msg, "no element") && strings.Contains(msg, "pipewiresrc") {
		return errors.New("Screen sync needs the PipeWire plugin for GStreamer: sudo apt install gstreamer1.0-pipewire")
	}
	if msg == "" {
		msg = "the screen stream ended"
	}
	return errors.New("Screen sync stopped: " + firstLine(msg))
}

func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i > 0 {
		return s[:i]
	}
	return s
}

func firstNode(v dbus.Variant) (uint32, error) {
	// streams: a(ua{sv})
	if list, ok := v.Value().([][]any); ok && len(list) > 0 && len(list[0]) > 0 {
		if n, ok := list[0][0].(uint32); ok {
			return n, nil
		}
	}
	var streams []struct {
		Node  uint32
		Props map[string]dbus.Variant
	}
	if err := dbus.Store([]any{v.Value()}, &streams); err == nil && len(streams) > 0 {
		return streams[0].Node, nil
	}
	return 0, errors.New("the desktop didn't share a screen")
}

func readFrames(r io.Reader) {
	buf := make([]byte, capW*capH*3)
	for {
		if _, err := io.ReadFull(r, buf); err != nil {
			return
		}
		cfgMu.Lock()
		l := cfg.Aito.Layout
		follow := cfg.Strip.Mode == "screen"
		cfgMu.Unlock()
		leds := sampleZones(buf, capW, capH, l)
		avg := frameAverage(buf)
		aito.setScreen(leds, avg)
		if follow {
			strip.sendLive(avg)
		}
	}
}

// sampleZones averages the strip of screen next to each LED.
func sampleZones(px []byte, w, h int, l Layout) []RGB {
	zones := l.zones()
	out := make([]RGB, len(zones))
	depth := float64(max(3, min(40, l.Depth))) / 100
	counts := map[string]int{"left": l.Left, "right": l.Right, "top": l.Top, "bottom": l.Bottom}
	for i, z := range zones {
		n := float64(max(1, counts[z.side]))
		a, b := z.pos-0.5/n, z.pos+0.5/n
		var x0, x1, y0, y1 float64
		switch z.side {
		case "top":
			x0, x1, y0, y1 = a, b, 0, depth
		case "bottom":
			x0, x1, y0, y1 = a, b, 1-depth, 1
		case "left":
			x0, x1, y0, y1 = 0, depth*float64(h)/float64(w), a, b
		default:
			x0, x1, y0, y1 = 1-depth*float64(h)/float64(w), 1, a, b
		}
		out[i] = avgRect(px, w, h, x0, y0, x1, y1)
	}
	return out
}

func avgRect(px []byte, w, h int, x0, y0, x1, y1 float64) RGB {
	ix0, ix1 := int(math.Floor(x0*float64(w))), int(math.Ceil(x1*float64(w)))
	iy0, iy1 := int(math.Floor(y0*float64(h))), int(math.Ceil(y1*float64(h)))
	ix0, iy0 = max(0, ix0), max(0, iy0)
	ix1, iy1 = min(w, max(ix0+1, ix1)), min(h, max(iy0+1, iy1))
	var r, g, b, n int
	for y := iy0; y < iy1; y++ {
		for x := ix0; x < ix1; x++ {
			o := (y*w + x) * 3
			r, g, b = r+int(px[o]), g+int(px[o+1]), b+int(px[o+2])
			n++
		}
	}
	if n == 0 {
		return RGB{}
	}
	return RGB{uint8(r / n), uint8(g / n), uint8(b / n)}
}

// frameAverage favors colorful pixels so a mostly-gray picture with a red logo still reads red.
func frameAverage(px []byte) RGB {
	var r, g, b, wsum float64
	for o := 0; o+2 < len(px); o += 3 {
		pr, pg, pb := float64(px[o]), float64(px[o+1]), float64(px[o+2])
		mx := math.Max(pr, math.Max(pg, pb))
		mn := math.Min(pr, math.Min(pg, pb))
		wt := 1 + (mx-mn)/16
		r, g, b, wsum = r+pr*wt, g+pg*wt, b+pb*wt, wsum+wt
	}
	if wsum == 0 {
		return RGB{}
	}
	return saturate(RGB{uint8(r / wsum), uint8(g / wsum), uint8(b / wsum)}, 140)
}
