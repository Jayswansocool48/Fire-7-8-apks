// Deskmate: desktop companion for the Deskmate tablet app.
// Runs on Linux (any distro, X11 or Wayland) and Windows.
package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
)

const appVersion = "1.1.0"

func main() {
	setup := flag.Bool("setup", false, "Linux: allow Deskmate to press keys (asks for your password once)")
	background := flag.Bool("background", false, "start without opening the settings page")
	autostart := flag.String("autostart", "", "\"on\" or \"off\": start Deskmate when you log in")
	noBrowser := flag.Bool("no-browser", false, "don't open the settings page")
	flag.Parse()
	log.SetFlags(log.Ltime)

	if *setup {
		if err := setupInput(); err != nil {
			fmt.Println("Setup failed:", err)
			os.Exit(1)
		}
		fmt.Println("Done. Deskmate can now press keys. Start it normally: deskmate")
		return
	}
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

	if err := serveTCP(port); err != nil {
		// Already running (e.g. opened again from the app menu): show its settings page instead.
		settings := fmt.Sprintf("http://127.0.0.1:%d", port+1)
		if resp, herr := http.Get(settings + "/api/status"); herr == nil {
			resp.Body.Close()
			fmt.Println("Deskmate is already running. Opening its settings page.")
			if !*background {
				openURL(settings)
			}
			return
		}
		fmt.Printf("Couldn't listen on port %d: %v\n", port, err)
		os.Exit(1)
	}
	if firstRun && !autostartEnabled() {
		setAutostart(true) // turn off any time in the settings page
	}
	go statsLoop()
	go serveDiscovery(port)
	settingsURL, err := serveSettings(port + 1)
	if err != nil {
		log.Printf("Settings page unavailable: %v", err)
	}

	fmt.Printf("\n  Deskmate %s is running\n\n", appVersion)
	fmt.Printf("  Pairing code:   %s\n", currentPairCode())
	fmt.Printf("  This PC:        %s  (port %d)\n", strings.Join(localIPs(), ", "), port)
	if settingsURL != "" {
		fmt.Printf("  Settings:       %s\n", settingsURL)
	}
	if !inputReady() {
		fmt.Printf("\n  ! %s\n", inputHelp())
	}
	fmt.Printf("\n  Open Deskmate on your tablet; this PC shows up automatically.\n  Leave this running. Press Ctrl+C to stop.\n\n")

	if *background {
		hideConsole()
	} else if !*noBrowser && settingsURL != "" {
		openURL(settingsURL)
	}
	select {}
}
