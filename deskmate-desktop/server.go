package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"os"
	"runtime"
	"strings"
	"sync"
	"time"
)

// Protocol: newline-delimited JSON over TCP. Every message has an "op".
// A tablet must pair once (6-digit code shown on the PC) and then sends its token in "hello".

const protocolVersion = 1

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

func rotatePairCode() {
	pairMu.Lock()
	pairCode = ""
	pairMu.Unlock()
}

type msg map[string]any

func (m msg) str(k string) string {
	s, _ := m[k].(string)
	return s
}

func (m msg) num(k string) float64 {
	f, _ := m[k].(float64)
	return f
}

type client struct {
	conn    net.Conn
	writeMu sync.Mutex
	name    string
	authed  bool
	stats   bool
	done    chan struct{}
}

func (c *client) send(m msg) {
	b, _ := json.Marshal(m)
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	c.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	c.conn.Write(append(b, '\n'))
}

func capabilities() msg {
	host, _ := os.Hostname()
	return msg{
		"os":         runtime.GOOS,
		"host":       host,
		"keys":       inputReady(),
		"keysHelp":   inputHelp(),
		"volume":     volumeSupported(),
		"nowPlaying": mediaInfoSupported(),
		"clipboard":  clipboardSupported(),
		"version":    protocolVersion,
	}
}

func serveTCP(port int) error {
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", port))
	if err != nil {
		return err
	}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				continue
			}
			go handle(conn)
		}
	}()
	return nil
}

var failedPairs sync.Map // ip -> attempts, slows down code guessing

func handle(conn net.Conn) {
	defer conn.Close()
	c := &client{conn: conn, done: make(chan struct{})}
	defer close(c.done)
	defer removeClient(c)
	ip, _, _ := net.SplitHostPort(conn.RemoteAddr().String())
	r := bufio.NewReader(conn)
	for {
		conn.SetReadDeadline(time.Now().Add(90 * time.Second)) // the app pings every 20s
		line, err := r.ReadBytes('\n')
		if err != nil {
			if c.authed {
				log.Printf("%s disconnected", c.name)
			}
			return
		}
		var m msg
		if json.Unmarshal(line, &m) != nil {
			continue
		}
		op := m.str("op")
		reqID := m["req"]
		reply := func(r msg) {
			if reqID != nil {
				r["req"] = reqID
			}
			c.send(r)
		}
		fail := func(err error) {
			reply(msg{"op": "error", "message": err.Error()})
		}

		if !c.authed {
			switch op {
			case "hello":
				if name, ok := tokenValid(m.str("token")); ok {
					c.authed, c.name = true, name
					addClient(c)
					log.Printf("%s connected from %s", name, ip)
					reply(msg{"op": "welcome", "caps": capabilities()})
				} else {
					reply(msg{"op": "needPair"})
				}
			case "pair":
				if v, _ := failedPairs.Load(ip); v != nil && v.(int) >= 5 {
					time.Sleep(3 * time.Second)
				}
				if strings.TrimSpace(m.str("code")) != currentPairCode() {
					n := 0
					if v, ok := failedPairs.Load(ip); ok {
						n = v.(int)
					}
					failedPairs.Store(ip, n+1)
					reply(msg{"op": "pairFailed", "message": "Wrong code. Check the code shown on the PC."})
					continue
				}
				failedPairs.Delete(ip)
				name := strings.TrimSpace(m.str("name"))
				if name == "" {
					name = "Tablet"
				}
				tok := newToken()
				cfgMu.Lock()
				cfg.Devices = append(cfg.Devices, Device{Name: name, Token: tok, Added: time.Now()})
				saveConfigLocked()
				cfgMu.Unlock()
				rotatePairCode()
				c.authed, c.name = true, name
				addClient(c)
				log.Printf("Paired with %s (%s)", name, ip)
				reply(msg{"op": "paired", "token": tok, "caps": capabilities()})
			case "ping":
				reply(msg{"op": "pong"})
			default:
				reply(msg{"op": "needPair"})
			}
			continue
		}

		switch op {
		case "ping":
			reply(msg{"op": "pong"})
		case "deck":
			cfgMu.Lock()
			out := msg{"op": "deck", "cols": cfg.Cols, "rows": cfg.Rows, "pages": cfg.Pages}
			cfgMu.Unlock()
			reply(out)
		case "icons":
			reply(msg{"op": "icons", "icons": iconSet()})
		case "press":
			k, ok := findKey(m.str("id"))
			if !ok {
				fail(fmt.Errorf("that key changed on the PC; reloading"))
				c.send(msg{"op": "deckChanged"})
				continue
			}
			go func() {
				if err := runAction(k.Action); err != nil {
					log.Printf("Key %q: %v", k.Title, err)
					c.send(msg{"op": "error", "message": k.Title + ": " + err.Error()})
				}
			}()
		case "stats":
			c.stats = m["on"] == true
			if c.stats {
				go c.streamStats()
			}
		case "media":
			if err := mediaControl(m.str("action")); err != nil {
				fail(err)
			}
		case "volume":
			if err := setVolume(m.num("value")); err != nil {
				fail(err)
			}
		case "volumeStep":
			step := m.num("step")
			mi := mediaInfo()
			if mi.Volume >= 0 && volumeSupported() {
				setVolume(mi.Volume + step)
			} else if step > 0 {
				pressMediaKey("volumeup")
			} else {
				pressMediaKey("volumedown")
			}
		case "mute":
			if err := toggleMute(m["mic"] == true); err != nil {
				fail(err)
			}
		case "mediaInfo":
			reply(msg{"op": "mediaInfo", "info": mediaInfo()})
		case "clipGet":
			text, err := getClipboard()
			if err != nil {
				fail(err)
				continue
			}
			reply(msg{"op": "clip", "text": text})
		case "clipSet":
			if err := setClipboard(m.str("text")); err != nil {
				fail(err)
			} else {
				reply(msg{"op": "ok"})
			}
		case "dev":
			forwardDev(m)
		default:
			fail(fmt.Errorf("unknown request %q", op))
		}
	}
}

var (
	clientsMu sync.Mutex
	clients   = map[*client]bool{}
)

func addClient(c *client)    { clientsMu.Lock(); clients[c] = true; clientsMu.Unlock() }
func removeClient(c *client) { clientsMu.Lock(); delete(clients, c); clientsMu.Unlock() }

// broadcast tells every connected tablet something changed (e.g. the deck was edited).
func broadcast(m msg) {
	clientsMu.Lock()
	list := make([]*client, 0, len(clients))
	for c := range clients {
		list = append(list, c)
	}
	clientsMu.Unlock()
	for _, c := range list {
		go c.send(m)
	}
}

func (c *client) streamStats() {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for c.stats {
		select {
		case <-c.done:
			return
		case <-t.C:
			c.send(msg{"op": "stats", "stats": currentStats()})
		}
	}
}

// ---------------------------------------------------------------- Game Dev Panel

var (
	devMu   sync.Mutex
	devConn net.Conn
	devPort int
)

// forwardDev passes a control change to the running game as one JSON line over UDP:
// {"name":"speed","value":3.5}. See the README for a Godot/Unity listener.
func forwardDev(m msg) {
	cfgMu.Lock()
	port := cfg.DevForwardPort
	cfgMu.Unlock()
	devMu.Lock()
	defer devMu.Unlock()
	if devConn == nil || devPort != port {
		if devConn != nil {
			devConn.Close()
		}
		conn, err := net.Dial("udp", fmt.Sprintf("127.0.0.1:%d", port))
		if err != nil {
			return
		}
		devConn, devPort = conn, port
	}
	b, _ := json.Marshal(msg{"name": m.str("name"), "value": m["value"]})
	devConn.Write(b)
}

// ---------------------------------------------------------------- LAN discovery

// The tablet broadcasts "DESKMATE?" on UDP; we answer with our name and TCP port.
func serveDiscovery(port int) {
	pc, err := net.ListenPacket("udp4", fmt.Sprintf(":%d", port))
	if err != nil {
		log.Printf("Discovery unavailable (%v); add the PC by IP address in the app.", err)
		return
	}
	host, _ := os.Hostname()
	buf := make([]byte, 64)
	for {
		n, addr, err := pc.ReadFrom(buf)
		if err != nil {
			continue
		}
		if strings.TrimSpace(string(buf[:n])) != "DESKMATE?" {
			continue
		}
		b, _ := json.Marshal(msg{"deskmate": protocolVersion, "name": host, "port": port, "os": runtime.GOOS})
		pc.WriteTo(b, addr)
	}
}

func localIPs() []string {
	var out []string
	addrs, _ := net.InterfaceAddrs()
	for _, a := range addrs {
		if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() != nil && !ipn.IP.IsLoopback() && !ipn.IP.IsLinkLocalUnicast() {
			out = append(out, ipn.IP.String())
		}
	}
	return out
}
