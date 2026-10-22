package main

import (
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"unsafe"
)

// The AITOLED's USB chip is a QinHeng CH340 (USB vendor 1a86).
const ch340Vendor = "1a86"

type serialPort struct{ f *os.File }

func openSerial(path string) (*serialPort, error) {
	f, err := os.OpenFile(path, os.O_RDWR|syscall.O_NOCTTY, 0)
	if err != nil {
		return nil, err
	}
	var t syscall.Termios
	if err := ioctl(f.Fd(), syscall.TCGETS, uintptr(unsafe.Pointer(&t))); err != nil {
		f.Close()
		return nil, err
	}
	// Raw 8N1 at 115200, reads return after at most 0.1 s.
	t.Iflag &^= syscall.IGNBRK | syscall.BRKINT | syscall.PARMRK | syscall.ISTRIP | syscall.INLCR | syscall.IGNCR | syscall.ICRNL | syscall.IXON | syscall.IXOFF
	t.Oflag &^= syscall.OPOST
	t.Lflag &^= syscall.ECHO | syscall.ECHONL | syscall.ICANON | syscall.ISIG | syscall.IEXTEN
	t.Cflag &^= syscall.CSIZE | syscall.PARENB | syscall.CSTOPB | 0x100f /* CBAUD */ | 0x80000000 /* CRTSCTS */
	t.Cflag |= syscall.CS8 | syscall.CREAD | syscall.CLOCAL | syscall.B115200
	t.Ispeed, t.Ospeed = syscall.B115200, syscall.B115200
	t.Cc[syscall.VMIN] = 0
	t.Cc[syscall.VTIME] = 1
	if err := ioctl(f.Fd(), syscall.TCSETS, uintptr(unsafe.Pointer(&t))); err != nil {
		f.Close()
		return nil, err
	}
	ioctl(f.Fd(), 0x540B /* TCFLSH */, 2) // drop anything stale
	return &serialPort{f}, nil
}

func ioctl(fd, req, arg uintptr) error {
	if _, _, e := syscall.Syscall(syscall.SYS_IOCTL, fd, req, arg); e != 0 {
		return e
	}
	return nil
}

func (p *serialPort) Write(b []byte) (int, error) { return p.f.Write(b) }
func (p *serialPort) Read(b []byte) (int, error)  { return p.f.Read(b) }
func (p *serialPort) Close() error                { return p.f.Close() }

// usbVendor walks up from a tty's sysfs node to the USB device and returns its vendor id.
func usbVendor(tty string) string {
	dir, err := filepath.EvalSymlinks(filepath.Join("/sys/class/tty", tty, "device"))
	if err != nil {
		return ""
	}
	for i := 0; i < 5 && dir != "/"; i++ {
		if b, err := os.ReadFile(filepath.Join(dir, "idVendor")); err == nil {
			return strings.TrimSpace(string(b))
		}
		dir = filepath.Dir(dir)
	}
	return ""
}

// findAitoPorts lists CH340 serial ports, best guesses first.
func findAitoPorts() []string {
	var out []string
	for _, pat := range []string{"/dev/ttyUSB*", "/dev/ttyACM*"} {
		m, _ := filepath.Glob(pat)
		sort.Strings(m)
		for _, p := range m {
			if usbVendor(filepath.Base(p)) == ch340Vendor {
				out = append(out, p)
			}
		}
	}
	return out
}

// serialHint explains why no light was found, if we can tell.
func serialHint(openErr error) string {
	if errors.Is(openErr, os.ErrPermission) {
		return "Linux won't let Glow open the light's USB port. Install Glow from the .deb (it adds a rule for it), or run: sudo usermod -aG dialout $USER and log out and back in."
	}
	// CH340 plugged in but no serial port: on Ubuntu/Pop!_OS the braille-display service grabs it.
	devs, _ := filepath.Glob("/sys/bus/usb/devices/*/idVendor")
	for _, d := range devs {
		if b, _ := os.ReadFile(d); strings.TrimSpace(string(b)) == ch340Vendor {
			if len(findAitoPorts()) == 0 {
				return "The light is plugged in but has no serial port. The braille-display service (brltty) is probably taking it. Fix: sudo apt remove brltty, then unplug and replug the light."
			}
		}
	}
	return "Plug the AITOLED's USB cable into this computer."
}
