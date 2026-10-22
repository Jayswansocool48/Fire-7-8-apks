// Glow: controls the AITOLED screen-sync light and the LEDBLE / Lotus Lantern
// Bluetooth strip from Pop!_OS (or any Linux), and serves the Glow website
// that the tablet app and browsers use.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

const appVersion = "1.0.0"

// Tablets find this PC by broadcasting "glow?" to this UDP port.
const discoveryPort = 47821

func main() {
	background := flag.Bool("background", false, "start without opening the page")
	autostart := flag.String("autostart", "", "\"on\" or \"off\": start Glow when you log in")
	flag.Parse()
	log.SetFlags(log.Ltime)
	loadConfig()

	if *autostart != "" {
		if err := setAutostart(*autostart == "on"); err != nil {
			fmt.Println("Couldn't change autostart:", err)
			os.Exit(1)
		}
		fmt.Println("Autostart", *autostart)
		return
	}

	cfgMu.Lock()
	port := cfg.Port
	cfgMu.Unlock()
	page := "http://127.0.0.1:" + strconv.Itoa(port)

	if err := serve(port); err != nil {
		if resp, herr := http.Get(page + "/api/hello"); herr == nil {
			resp.Body.Close()
			fmt.Println("Glow is already running. Opening its page.")
			if !*background {
				openURL(page)
			}
			return
		}
		fmt.Printf("Couldn't listen on port %d: %v\n", port, err)
		os.Exit(1)
	}
	if firstRun && !autostartEnabled() {
		setAutostart(true) // switch off any time on the page
	}
	go serveDiscovery(port)
	go aitoLoop()
	go stripLoop()
	cfgMu.Lock()
	autoCapture := cfg.CaptureAuto && cfg.CaptureRestore != ""
	cfgMu.Unlock()
	if autoCapture {
		go func() {
			time.Sleep(3 * time.Second) // let the desktop finish starting
			capture.start()
		}()
	}

	fmt.Printf("\n  Glow %s is running\n\n", appVersion)
	fmt.Printf("  On this PC:     %s\n", page)
	for _, ip := range localIPs() {
		fmt.Printf("  On your Wi-Fi:  http://%s:%d\n", ip, port)
	}
	fmt.Printf("  Pairing code:   %s\n\n  Leave this running. Press Ctrl+C to stop.\n\n", currentPairCode())
	if !*background {
		openURL(page)
	}
	select {}
}

func serveDiscovery(port int) {
	conn, err := net.ListenUDP("udp4", &net.UDPAddr{Port: discoveryPort})
	if err != nil {
		log.Printf("Tablets can't find this PC automatically (UDP %d busy): %v", discoveryPort, err)
		return
	}
	host, _ := os.Hostname()
	reply, _ := json.Marshal(map[string]any{"app": "glow", "host": host, "port": port, "version": appVersion})
	buf := make([]byte, 64)
	for {
		n, from, err := conn.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		if strings.HasPrefix(string(buf[:n]), "glow?") {
			conn.WriteToUDP(reply, from)
		}
	}
}

func openURL(u string) {
	cmd := exec.Command("xdg-open", u)
	cmd.Stdout, cmd.Stderr = nil, nil
	cmd.Start()
}
