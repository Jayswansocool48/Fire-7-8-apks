package main

import "strconv"

// Layout describes where the AITOLED strip runs around the monitor, as seen from the front.
type Layout struct {
	Left      int    `json:"left"`
	Top       int    `json:"top"`
	Right     int    `json:"right"`
	Bottom    int    `json:"bottom"`
	Start     string `json:"start"`     // corner where LED #1 is: bottom-left, top-left, top-right, bottom-right
	Clockwise bool   `json:"clockwise"` // direction the strip runs, seen from the front
	Depth     int    `json:"depth"`     // how far into the picture each LED looks, % of the screen (5-40)
	Offset    int    `json:"offset"`    // skip this many LEDs at the start (glue gap)
}

func (l Layout) Count() int { return l.Left + l.Top + l.Right + l.Bottom }

// Zone is where one LED takes its color from: a screen edge and a position along it,
// 0 = left/top end, 1 = right/bottom end.
type Zone struct {
	side string
	pos  float64
}

func (z Zone) MarshalJSON() ([]byte, error) {
	return []byte(`{"side":"` + z.side + `","pos":` + strconv.FormatFloat(z.pos, 'f', 4, 64) + `}`), nil
}

type segment struct {
	side    string
	count   int
	reverse bool // runs right→left or bottom→top
}

// zones lists one Zone per LED in strip order.
func (l Layout) zones() []Zone {
	var order []segment
	if l.Clockwise {
		// top L→R, right T→B, bottom R→L, left B→T
		order = []segment{{"top", l.Top, false}, {"right", l.Right, false}, {"bottom", l.Bottom, true}, {"left", l.Left, true}}
		switch l.Start {
		case "top-right":
			order = rotate(order, 1)
		case "bottom-right":
			order = rotate(order, 2)
		case "bottom-left", "":
			order = rotate(order, 3)
		}
	} else {
		// left T→B, bottom L→R, right B→T, top R→L
		order = []segment{{"left", l.Left, false}, {"bottom", l.Bottom, false}, {"right", l.Right, true}, {"top", l.Top, true}}
		switch l.Start {
		case "bottom-left", "":
			order = rotate(order, 1)
		case "bottom-right":
			order = rotate(order, 2)
		case "top-right":
			order = rotate(order, 3)
		}
	}
	var out []Zone
	for _, s := range order {
		for i := 0; i < s.count; i++ {
			p := (float64(i) + 0.5) / float64(s.count)
			if s.reverse {
				p = 1 - p
			}
			out = append(out, Zone{s.side, p})
		}
	}
	return out
}

func rotate(s []segment, n int) []segment {
	return append(append([]segment{}, s[n:]...), s[:n]...)
}
