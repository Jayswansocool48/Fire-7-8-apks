package main

import (
	"bytes"
	"testing"
)

func TestAitoColorPacket(t *testing.T) {
	got := aitoColorPacket([]RGB{{R: 1, G: 2, B: 3}, {R: 255, G: 0, B: 16}})
	// payload: B G R per LED, then sum 3+2+1+16+0+255 = 277 = 0x0115
	want := []byte{0xA5, 13, 3, 2, 1, 16, 0, 255, 0, 0, 0x01, 0x15, 0x5A}
	if !bytes.Equal(got, want) {
		t.Fatalf("got % x\nwant % x", got, want)
	}
}

func TestProbeIsSixteenBytes(t *testing.T) {
	if len(probeAito) != 16 || probeAito[1] != 16 || probeAito[15] != 0x5A {
		t.Fatalf("bad probe % x", probeAito)
	}
}

func TestFindAitoReply(t *testing.T) {
	r := make([]byte, 30)
	copy(r[2:], []byte{0xAC, 0x1F, 0xEF, 0x11, 0, 20, 38, 20, 0, 1, 0, 1, 1, 80})
	info, ok := findAitoReply(r)
	if !ok || info.Model != "A27" || info.Total() != 78 || info.Mode != 1 || !info.Power {
		t.Fatalf("bad parse %+v %v", info, ok)
	}
	st := aitoStatePacket(info.raw)
	if len(st) != 16 || st[2] != 1 || st[3] != 1 || st[4] != 80 || st[15] != 0x5A {
		t.Fatalf("bad state % x", st)
	}
}

func TestStripPackets(t *testing.T) {
	if c := stripColor(protoELK, RGB{1, 2, 3}); !bytes.Equal(c, []byte{0x7E, 0x07, 0x05, 0x03, 1, 2, 3, 0x10, 0xEF}) {
		t.Fatalf("elk color % x", c)
	}
	if c := stripColor(protoTriones, RGB{1, 2, 3}); !bytes.Equal(c, []byte{0x56, 1, 2, 3, 0, 0xF0, 0xAA}) {
		t.Fatalf("triones color % x", c)
	}
}

func TestLayoutOrder(t *testing.T) {
	l := Layout{Left: 2, Top: 3, Right: 2, Start: "bottom-left", Clockwise: true}
	z := l.zones()
	if len(z) != 7 || z[0].side != "left" || z[2].side != "top" || z[6].side != "right" {
		t.Fatalf("zones %+v", z)
	}
	// Left side runs bottom→top when starting bottom-left clockwise.
	if !(z[0].pos > z[1].pos) {
		t.Fatalf("left side should go upward: %+v", z[:2])
	}
	l.Clockwise = false
	l.Start = "bottom-right"
	z = l.zones()
	if z[0].side != "right" || z[2].side != "top" || z[6].side != "left" {
		t.Fatalf("ccw zones %+v", z)
	}
}

func TestSampleZones(t *testing.T) {
	px := make([]byte, capW*capH*3)
	for y := 0; y < capH; y++ {
		for x := 0; x < capW; x++ {
			o := (y*capW + x) * 3
			if x < capW/2 {
				px[o] = 255
			} else {
				px[o+2] = 255
			}
		}
	}
	l := Layout{Left: 4, Top: 6, Right: 4, Start: "bottom-left", Clockwise: true, Depth: 12}
	z := sampleZones(px, capW, capH, l)
	if z[0] != (RGB{255, 0, 0}) || z[len(z)-1] != (RGB{0, 0, 255}) {
		t.Fatalf("left should be red, right blue: %v … %v", z[0], z[len(z)-1])
	}
	if z[4] != (RGB{255, 0, 0}) || z[9] != (RGB{0, 0, 255}) {
		t.Fatalf("top should go red→blue: %v", z[4:10])
	}
}
