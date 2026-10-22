package main

// AITOLED screen-sync light protocol, worked out from the official Windows app
// (AITOLED.exe 6.3). The light is a CH340 USB-serial chip at 115200 baud, 8N1.
//
// Every packet the PC sends is framed as
//
//	A5  <total length, low byte>  <payload…>  5A
//
// LED frames: the payload is blue, green, red for each LED (in strip order)
// followed by the sum of those bytes as a 4-byte big-endian number.
//
// Handshake: the PC sends probeAito; the light answers with a packet that starts
// AC 1F EF, then its model, then the LED count of each side and its saved settings.
//
// State packets (16 bytes, A5 10 …) set the light's own mode and power.

const (
	aitoBaud = 115200

	aitoModeScreen = 1 // the PC streams colors (this is what Glow uses)
	aitoModeMusic  = 2 // the light makes its own effect from a sound level
	aitoModeStatic = 4
	aitoModeFlow   = 5
)

var probeAito = []byte{0xA5, 0x10, 0xEE, 0x01, 0x0A, 0x00, 0x00, 0xBB, 0xFF, 0x3D, 0x00, 0x00, 0x00, 0x00, 0x00, 0x5A}

var aitoModels = map[byte]string{0x10: "A24", 0x11: "A27", 0x12: "A32", 0x13: "A34", 0x14: "A49"}

type RGB struct{ R, G, B uint8 }

// frameAito wraps a payload as A5 len … 5A, exactly like the official app
// (the length byte is the low byte of the whole packet's size).
func frameAito(payload []byte) []byte {
	out := make([]byte, 0, len(payload)+3)
	out = append(out, 0xA5, byte(len(payload)+3))
	out = append(out, payload...)
	return append(out, 0x5A)
}

// aitoColorPacket builds a full LED frame.
func aitoColorPacket(leds []RGB) []byte {
	p := make([]byte, 0, len(leds)*3+4)
	var sum uint32
	for _, c := range leds {
		p = append(p, c.B, c.G, c.R)
		sum += uint32(c.B) + uint32(c.G) + uint32(c.R)
	}
	p = append(p, byte(sum>>24), byte(sum>>16), byte(sum>>8), byte(sum))
	return frameAito(p)
}

// AitoInfo is what the light reports in its handshake reply.
type AitoInfo struct {
	Model string `json:"model"`
	// LED counts of the strip's sections, in the order the light reports them.
	// The light lights Sides[0]+Sides[1]+Sides[2] LEDs; Sides[3] is reported but unused for colors.
	Sides [4]int `json:"sides"`
	Mode  int    `json:"mode"`
	Power bool   `json:"power"`
	raw   []byte
}

func (i AitoInfo) Total() int { return i.Sides[0] + i.Sides[1] + i.Sides[2] }

// findAitoReply looks for a complete handshake reply in buf.
func findAitoReply(buf []byte) (AitoInfo, bool) {
	for i := 0; i+25 <= len(buf); i++ {
		if buf[i] != 0xAC || buf[i+1] != 0x1F || buf[i+2] != 0xEF {
			continue
		}
		b := buf[i:]
		info := AitoInfo{raw: append([]byte(nil), b[:25]...)}
		info.Model = aitoModels[b[3]]
		if info.Model == "" {
			info.Model = "Unknown"
		}
		for s := 0; s < 4; s++ {
			info.Sides[s] = int(b[5+s])
		}
		info.Mode = int(b[11])
		info.Power = b[12] == 1
		return info, true
	}
	return AitoInfo{}, false
}

// aitoStatePacket switches the light into PC-streaming (screen) mode and turns it on,
// keeping the settings it reported. Field order matches the official app's mode-1 packet.
func aitoStatePacket(reply []byte) []byte {
	g := func(i int) byte {
		if i < len(reply) {
			return reply[i]
		}
		return 0
	}
	bright := g(13)
	if bright == 0 {
		bright = 100
	}
	return []byte{0xA5, 0x10,
		aitoModeScreen, 1, bright, 0, g(18), g(15), g(16), g(17), g(9), g(10),
		0, 0, 0, 0x5A}
}
