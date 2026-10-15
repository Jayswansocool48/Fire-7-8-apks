package main

import (
	"encoding/json"
	"sync"
)

var (
	iconsOnce sync.Once
	icons     map[string][]string
)

// iconSet returns the built-in key icons (web/icons.json), shared by the settings page and tablets.
func iconSet() map[string][]string {
	iconsOnce.Do(func() {
		raw := map[string]json.RawMessage{}
		data, _ := webFiles.ReadFile("web/icons.json")
		json.Unmarshal(data, &raw)
		icons = map[string][]string{}
		for name, v := range raw {
			var parts []string
			if json.Unmarshal(v, &parts) == nil {
				icons[name] = parts
			}
		}
	})
	return icons
}
