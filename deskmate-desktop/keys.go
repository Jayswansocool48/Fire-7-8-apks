package main

import (
	"fmt"
	"strings"
)

// Key names shared by both platforms. Each maps to a Linux evdev code and a Windows
// virtual-key code (0 = not available on that platform).
type keyDef struct {
	linux    uint16
	win      uint16
	extended bool // Windows: needs KEYEVENTF_EXTENDEDKEY
}

var keyTable = map[string]keyDef{
	"esc": {1, 0x1B, false}, "escape": {1, 0x1B, false},
	"1": {2, 0x31, false}, "2": {3, 0x32, false}, "3": {4, 0x33, false}, "4": {5, 0x34, false}, "5": {6, 0x35, false},
	"6": {7, 0x36, false}, "7": {8, 0x37, false}, "8": {9, 0x38, false}, "9": {10, 0x39, false}, "0": {11, 0x30, false},
	"minus": {12, 0xBD, false}, "-": {12, 0xBD, false}, "equal": {13, 0xBB, false}, "=": {13, 0xBB, false},
	"backspace": {14, 0x08, false}, "tab": {15, 0x09, false},
	"q": {16, 0x51, false}, "w": {17, 0x57, false}, "e": {18, 0x45, false}, "r": {19, 0x52, false}, "t": {20, 0x54, false},
	"y": {21, 0x59, false}, "u": {22, 0x55, false}, "i": {23, 0x49, false}, "o": {24, 0x4F, false}, "p": {25, 0x50, false},
	"leftbracket": {26, 0xDB, false}, "[": {26, 0xDB, false}, "rightbracket": {27, 0xDD, false}, "]": {27, 0xDD, false},
	"enter": {28, 0x0D, false}, "return": {28, 0x0D, false},
	"ctrl": {29, 0xA2, false}, "control": {29, 0xA2, false},
	"a": {30, 0x41, false}, "s": {31, 0x53, false}, "d": {32, 0x44, false}, "f": {33, 0x46, false}, "g": {34, 0x47, false},
	"h": {35, 0x48, false}, "j": {36, 0x4A, false}, "k": {37, 0x4B, false}, "l": {38, 0x4C, false},
	"semicolon": {39, 0xBA, false}, ";": {39, 0xBA, false}, "apostrophe": {40, 0xDE, false}, "'": {40, 0xDE, false},
	"grave": {41, 0xC0, false}, "`": {41, 0xC0, false},
	"shift": {42, 0xA0, false}, "backslash": {43, 0xDC, false}, "\\": {43, 0xDC, false},
	"z": {44, 0x5A, false}, "x": {45, 0x58, false}, "c": {46, 0x43, false}, "v": {47, 0x56, false}, "b": {48, 0x42, false},
	"n": {49, 0x4E, false}, "m": {50, 0x4D, false},
	"comma": {51, 0xBC, false}, ",": {51, 0xBC, false}, "period": {52, 0xBE, false}, ".": {52, 0xBE, false},
	"slash": {53, 0xBF, false}, "/": {53, 0xBF, false},
	"alt": {56, 0xA4, false}, "space": {57, 0x20, false}, "capslock": {58, 0x14, false},
	"f1": {59, 0x70, false}, "f2": {60, 0x71, false}, "f3": {61, 0x72, false}, "f4": {62, 0x73, false},
	"f5": {63, 0x74, false}, "f6": {64, 0x75, false}, "f7": {65, 0x76, false}, "f8": {66, 0x77, false},
	"f9": {67, 0x78, false}, "f10": {68, 0x79, false}, "f11": {87, 0x7A, false}, "f12": {88, 0x7B, false},
	"rightctrl": {97, 0xA3, true}, "print": {99, 0x2C, false}, "printscreen": {99, 0x2C, false}, "rightalt": {100, 0xA5, true}, "altgr": {100, 0xA5, true},
	"home": {102, 0x24, true}, "up": {103, 0x26, true}, "pageup": {104, 0x21, true}, "left": {105, 0x25, true},
	"right": {106, 0x27, true}, "end": {107, 0x23, true}, "down": {108, 0x28, true}, "pagedown": {109, 0x22, true},
	"insert": {110, 0x2D, true}, "delete": {111, 0x2E, true}, "del": {111, 0x2E, true},
	"mute": {113, 0xAD, true}, "volumedown": {114, 0xAE, true}, "volumeup": {115, 0xAF, true},
	"pause": {119, 0x13, false},
	"super": {125, 0x5B, true}, "win": {125, 0x5B, true}, "meta": {125, 0x5B, true}, "cmd": {125, 0x5B, true},
	"menu":     {127, 0x5D, true},
	"nextsong": {163, 0xB0, true}, "next": {163, 0xB0, true}, "playpause": {164, 0xB3, true},
	"previoussong": {165, 0xB1, true}, "prev": {165, 0xB1, true}, "stop": {166, 0xB2, true},
	"micmute": {248, 0, false},
	"f13":     {183, 0x7C, false}, "f14": {184, 0x7D, false}, "f15": {185, 0x7E, false}, "f16": {186, 0x7F, false},
}

// parseCombo turns "ctrl+shift+t" into key definitions, modifiers first.
func parseCombo(s string) ([]keyDef, error) {
	var out []keyDef
	for _, part := range strings.Split(strings.ToLower(strings.ReplaceAll(s, " ", "")), "+") {
		if part == "" {
			continue
		}
		k, ok := keyTable[part]
		if !ok {
			return nil, fmt.Errorf("unknown key %q", part)
		}
		out = append(out, k)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no keys in %q", s)
	}
	return out, nil
}

// asciiKey maps a printable character to a key and whether Shift is needed (US layout).
func asciiKey(r rune) (keyDef, bool, bool) {
	if r >= 'a' && r <= 'z' {
		k := keyTable[string(r)]
		return k, false, true
	}
	if r >= 'A' && r <= 'Z' {
		k := keyTable[strings.ToLower(string(r))]
		return k, true, true
	}
	plain := map[rune]string{' ': "space", '\n': "enter", '\t': "tab", '-': "minus", '=': "equal", '[': "[", ']': "]",
		';': ";", '\'': "'", '`': "`", '\\': "\\", ',': ",", '.': ".", '/': "/"}
	if r >= '0' && r <= '9' {
		return keyTable[string(r)], false, true
	}
	if n, ok := plain[r]; ok {
		return keyTable[n], false, true
	}
	shifted := map[rune]string{'!': "1", '@': "2", '#': "3", '$': "4", '%': "5", '^': "6", '&': "7", '*': "8", '(': "9", ')': "0",
		'_': "minus", '+': "equal", '{': "[", '}': "]", ':': ";", '"': "'", '~': "`", '|': "\\", '<': ",", '>': ".", '?': "/"}
	if n, ok := shifted[r]; ok {
		return keyTable[n], true, true
	}
	return keyDef{}, false, false
}
