package main

import (
	"os"
	"path/filepath"
)

func autostartFile() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		dir = filepath.Join(os.Getenv("HOME"), ".config")
	}
	return filepath.Join(dir, "autostart", "glow.desktop")
}

func autostartEnabled() bool {
	_, err := os.Stat(autostartFile())
	return err == nil
}

func setAutostart(on bool) error {
	f := autostartFile()
	if !on {
		if err := os.Remove(f); err != nil && !os.IsNotExist(err) {
			return err
		}
		return nil
	}
	exe, err := os.Executable()
	if err != nil {
		exe = "glow"
	}
	if exe == "/usr/bin/glow" {
		exe = "glow"
	}
	os.MkdirAll(filepath.Dir(f), 0o755)
	return os.WriteFile(f, []byte("[Desktop Entry]\nType=Application\nName=Glow\nComment=LED light control\nExec="+exe+" --background\nIcon=glow\nTerminal=false\nX-GNOME-Autostart-enabled=true\n"), 0o644)
}
