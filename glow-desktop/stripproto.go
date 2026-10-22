package main

// Bluetooth LED strip protocols.
//
// "elk": the LEDBLE / ELK-BLEDOM / MELK controllers that the Lotus Lantern app drives.
// Write characteristic 0000fff3-…; 9-byte commands 7E … EF.
//
// "triones": older Triones / LEDBlue / HappyLighting controllers (ffd9 / ffe9).

const (
	protoELK     = "elk"
	protoTriones = "triones"
)

var stripWriteUUIDs = map[string]string{
	"0000fff3-0000-1000-8000-00805f9b34fb": protoELK,
	"0000ffd9-0000-1000-8000-00805f9b34fb": protoTriones,
	"0000ffe9-0000-1000-8000-00805f9b34fb": protoTriones,
}

// StripEffect is one of the strip's built-in animations.
type StripEffect struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
}

var elkEffects = []StripEffect{
	{0x87, "Jump (RGB)"}, {0x88, "Jump (7 colors)"},
	{0x89, "Fade (RGB)"}, {0x8a, "Fade (7 colors)"},
	{0x8b, "Pulse red"}, {0x8c, "Pulse green"}, {0x8d, "Pulse blue"},
	{0x8e, "Pulse yellow"}, {0x8f, "Pulse cyan"}, {0x90, "Pulse purple"}, {0x91, "Pulse white"},
	{0x92, "Fade red ↔ green"}, {0x93, "Fade red ↔ blue"}, {0x94, "Fade green ↔ blue"},
	{0x95, "Strobe (7 colors)"},
	{0x96, "Strobe red"}, {0x97, "Strobe green"}, {0x98, "Strobe blue"},
	{0x99, "Strobe yellow"}, {0x9a, "Strobe cyan"}, {0x9b, "Strobe purple"}, {0x9c, "Strobe white"},
}

var trionesEffects = []StripEffect{
	{0x25, "Fade (7 colors)"}, {0x26, "Pulse red"}, {0x27, "Pulse green"}, {0x28, "Pulse blue"},
	{0x29, "Pulse yellow"}, {0x2a, "Pulse cyan"}, {0x2b, "Pulse purple"}, {0x2c, "Pulse white"},
	{0x2d, "Fade red ↔ green"}, {0x2e, "Fade red ↔ blue"}, {0x2f, "Fade green ↔ blue"},
	{0x30, "Strobe (7 colors)"}, {0x31, "Strobe red"}, {0x32, "Strobe green"}, {0x33, "Strobe blue"},
	{0x34, "Strobe yellow"}, {0x35, "Strobe cyan"}, {0x36, "Strobe purple"}, {0x37, "Strobe white"},
	{0x38, "Jump (7 colors)"},
}

func stripEffects(proto string) []StripEffect {
	if proto == protoTriones {
		return trionesEffects
	}
	return elkEffects
}

func clamp100(v int) byte {
	if v < 0 {
		return 0
	}
	if v > 100 {
		return 100
	}
	return byte(v)
}

func stripPower(proto string, on bool) []byte {
	if proto == protoTriones {
		if on {
			return []byte{0xCC, 0x23, 0x33}
		}
		return []byte{0xCC, 0x24, 0x33}
	}
	if on {
		return []byte{0x7E, 0x04, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF}
	}
	return []byte{0x7E, 0x04, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF}
}

func stripColor(proto string, c RGB) []byte {
	if proto == protoTriones {
		return []byte{0x56, c.R, c.G, c.B, 0x00, 0xF0, 0xAA}
	}
	return []byte{0x7E, 0x07, 0x05, 0x03, c.R, c.G, c.B, 0x10, 0xEF}
}

// stripBrightness returns nil for controllers without a brightness command
// (the color is scaled instead).
func stripBrightness(proto string, pct int) []byte {
	if proto == protoTriones {
		return nil
	}
	return []byte{0x7E, 0x04, 0x01, clamp100(pct), 0x01, 0xFF, 0xFF, 0x00, 0xEF}
}

func stripEffect(proto string, id, speedPct int) []byte {
	if proto == protoTriones {
		// Triones speed: 1 (fast) … 31 (slow).
		s := 31 - int(clamp100(speedPct))*30/100
		return []byte{0xBB, byte(id), byte(s), 0x44}
	}
	return []byte{0x7E, 0x05, 0x03, byte(id), 0x03, 0xFF, 0xFF, 0x00, 0xEF}
}

func stripSpeed(proto string, pct int) []byte {
	if proto == protoTriones {
		return nil // part of the effect command
	}
	return []byte{0x7E, 0x04, 0x02, clamp100(pct), 0xFF, 0xFF, 0xFF, 0x00, 0xEF}
}
