package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

func runAction(a Action) error {
	switch a.Type {
	case "keys":
		combo, err := parseCombo(a.Value)
		if err != nil {
			return err
		}
		return pressCombo(combo)
	case "text":
		return typeText(a.Value)
	case "run":
		if strings.TrimSpace(a.Value) == "" {
			return fmt.Errorf("no command set")
		}
		return runCommand(a.Value)
	case "open":
		return openTarget(expandHome(a.Value))
	}
	return fmt.Errorf("unknown action type %q", a.Type)
}

func expandHome(p string) string {
	p = strings.TrimSpace(p)
	if p == "~" || strings.HasPrefix(p, "~/") || strings.HasPrefix(p, `~\`) {
		if h, err := os.UserHomeDir(); err == nil {
			return filepath.Join(h, p[1:])
		}
	}
	return p
}

// pressMedia sends a media/volume key ("playpause", "next", "prev", "mute", "volumeup", "volumedown").
func pressMediaKey(name string) error {
	k, ok := keyTable[name]
	if !ok {
		return fmt.Errorf("unknown media key %q", name)
	}
	return pressCombo([]keyDef{k})
}
