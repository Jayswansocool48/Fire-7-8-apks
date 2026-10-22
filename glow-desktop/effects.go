package main

import "math"

// Effects the PC draws on the AITOLED strip, frame by frame.
var aitoEffects = []struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}{
	{"rainbow", "Rainbow wave"},
	{"cycle", "Color cycle"},
	{"breathe", "Breathe"},
	{"comet", "Comet"},
	{"fire", "Fire"},
	{"ocean", "Ocean"},
	{"aurora", "Aurora"},
	{"police", "Police"},
}

func hsv(h, s, v float64) RGB {
	h = math.Mod(h, 1)
	if h < 0 {
		h++
	}
	i := math.Floor(h * 6)
	f := h*6 - i
	p, q, t := v*(1-s), v*(1-f*s), v*(1-(1-f)*s)
	var r, g, b float64
	switch int(i) % 6 {
	case 0:
		r, g, b = v, t, p
	case 1:
		r, g, b = q, v, p
	case 2:
		r, g, b = p, v, t
	case 3:
		r, g, b = p, q, v
	case 4:
		r, g, b = t, p, v
	default:
		r, g, b = v, p, q
	}
	return RGB{uint8(r * 255), uint8(g * 255), uint8(b * 255)}
}

func scale(c RGB, f float64) RGB {
	if f >= 1 {
		return c
	}
	if f <= 0 {
		return RGB{}
	}
	return RGB{uint8(float64(c.R) * f), uint8(float64(c.G) * f), uint8(float64(c.B) * f)}
}

func mix(a, b RGB, t float64) RGB {
	l := func(x, y uint8) uint8 { return uint8(float64(x) + (float64(y)-float64(x))*t) }
	return RGB{l(a.R, b.R), l(a.G, b.G), l(a.B, b.B)}
}

// hash-based noise so effects need no state
func noise(x float64) float64 {
	i := math.Floor(x)
	f := x - i
	h := func(n float64) float64 { return math.Mod(math.Sin(n*127.1)*43758.5453, 1) }
	a, b := math.Abs(h(i)), math.Abs(h(i+1))
	f = f * f * (3 - 2*f)
	return a + (b-a)*f
}

// renderEffect fills n LEDs at time t (seconds). speed is 0-100.
func renderEffect(name string, n int, t float64, speed int, base RGB) []RGB {
	out := make([]RGB, n)
	if n == 0 {
		return out
	}
	sp := 0.15 + float64(speed)/100*1.6 // cycles per second-ish
	fn := float64(n)
	switch name {
	case "cycle":
		c := hsv(t*sp*0.25, 1, 1)
		for i := range out {
			out[i] = c
		}
	case "breathe":
		v := 0.08 + 0.92*(0.5-0.5*math.Cos(t*sp*2))
		for i := range out {
			out[i] = scale(base, v)
		}
	case "comet":
		head := math.Mod(t*sp*0.5*fn, fn)
		tail := math.Max(4, fn/6)
		for i := range out {
			d := head - float64(i)
			if d < 0 {
				d += fn
			}
			if d < tail {
				out[i] = scale(base, math.Pow(1-d/tail, 2))
			}
		}
	case "fire":
		for i := range out {
			v := noise(float64(i)*0.35+t*sp*3) * noise(float64(i)*0.11-t*sp*1.3+7)
			v = math.Min(1, v*1.8)
			out[i] = RGB{uint8(255 * math.Min(1, 0.4+v)), uint8(170 * v * v), uint8(20 * v * v * v)}
		}
	case "ocean":
		for i := range out {
			w := 0.5 + 0.5*math.Sin(float64(i)/fn*math.Pi*4-t*sp*1.5)
			out[i] = mix(RGB{0, 30, 120}, RGB{0, 200, 210}, w*noise(float64(i)*0.2+t*sp))
		}
	case "aurora":
		for i := range out {
			h := 0.33 + 0.25*noise(float64(i)*0.06+t*sp*0.4)
			v := 0.25 + 0.75*noise(float64(i)*0.15-t*sp*0.7+3)
			out[i] = hsv(h, 0.85, v)
		}
	case "police":
		phase := int(t*sp*3) % 4
		for i := range out {
			left := i < n/2
			on := (phase == 0 || phase == 1) == left
			if phase%2 == 1 {
				on = false
			}
			if on {
				if left {
					out[i] = RGB{255, 0, 0}
				} else {
					out[i] = RGB{0, 40, 255}
				}
			}
		}
	default: // rainbow
		for i := range out {
			out[i] = hsv(float64(i)/fn-t*sp*0.2, 1, 1)
		}
	}
	return out
}
