package main

import (
	"encoding/binary"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Virtual keyboard through /dev/uinput. The kernel sees it as a real keyboard, so it works
// on X11 and on every Wayland desktop (COSMIC, GNOME, KDE, Sway, …) without special APIs.

const (
	uiSetEvBit     = 0x40045564 // _IOW('U', 100, int)
	uiSetKeyBit    = 0x40045565 // _IOW('U', 101, int)
	uiDevCreate    = 0x5501     // _IO('U', 1)
	evSyn          = 0
	evKey          = 1
	synReport      = 0
	uinputUdevRule = `KERNEL=="uinput", SUBSYSTEM=="misc", TAG+="uaccess", OPTIONS+="static_node=uinput"`
)

var (
	kbMu   sync.Mutex
	kbFile *os.File
)

func ioctl(fd uintptr, req, arg uintptr) error {
	if _, _, e := syscall.Syscall(syscall.SYS_IOCTL, fd, req, arg); e != 0 {
		return e
	}
	return nil
}

func openKeyboard() (*os.File, error) {
	if kbFile != nil {
		return kbFile, nil
	}
	f, err := os.OpenFile("/dev/uinput", os.O_WRONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, fmt.Errorf("can't use the virtual keyboard (%v). Run: deskmate --setup", err)
	}
	fd := f.Fd()
	if err := ioctl(fd, uiSetEvBit, evKey); err != nil {
		f.Close()
		return nil, err
	}
	for code := 1; code < 256; code++ {
		ioctl(fd, uiSetKeyBit, uintptr(code))
	}
	// struct uinput_user_dev: name[80], input_id{4×u16}, ff_effects_max u32, 4×abs[64] i32
	dev := make([]byte, 80+8+4+64*4*4)
	copy(dev, "Deskmate virtual keyboard")
	binary.LittleEndian.PutUint16(dev[80:], 0x06) // BUS_VIRTUAL
	binary.LittleEndian.PutUint16(dev[82:], 0x1209)
	binary.LittleEndian.PutUint16(dev[84:], 0xD35C)
	binary.LittleEndian.PutUint16(dev[86:], 1)
	if _, err := f.Write(dev); err != nil {
		f.Close()
		return nil, err
	}
	if err := ioctl(fd, uiDevCreate, 0); err != nil {
		f.Close()
		return nil, err
	}
	// Give the desktop a moment to notice the new keyboard.
	time.Sleep(300 * time.Millisecond)
	kbFile = f
	return f, nil
}

func emit(f *os.File, typ, code uint16, value int32) error {
	// struct input_event on 64-bit: timeval (16 bytes), type u16, code u16, value s32
	var ev [24]byte
	binary.LittleEndian.PutUint16(ev[16:], typ)
	binary.LittleEndian.PutUint16(ev[18:], code)
	binary.LittleEndian.PutUint32(ev[20:], uint32(value))
	_, err := f.Write(ev[:])
	return err
}

func key(f *os.File, code uint16, down bool) {
	v := int32(0)
	if down {
		v = 1
	}
	emit(f, evKey, code, v)
	emit(f, evSyn, synReport, 0)
}

func pressCombo(keys []keyDef) error {
	kbMu.Lock()
	defer kbMu.Unlock()
	f, err := openKeyboard()
	if err != nil {
		return err
	}
	for _, k := range keys {
		key(f, k.linux, true)
		time.Sleep(8 * time.Millisecond)
	}
	for i := len(keys) - 1; i >= 0; i-- {
		key(f, keys[i].linux, false)
		time.Sleep(4 * time.Millisecond)
	}
	return nil
}

func typeText(s string) error {
	kbMu.Lock()
	defer kbMu.Unlock()
	f, err := openKeyboard()
	if err != nil {
		return err
	}
	shift := keyTable["shift"].linux
	for _, r := range s {
		k, needShift, ok := asciiKey(r)
		if !ok {
			continue // non-ASCII characters can't be typed with a US keymap
		}
		if needShift {
			key(f, shift, true)
		}
		key(f, k.linux, true)
		key(f, k.linux, false)
		if needShift {
			key(f, shift, false)
		}
		time.Sleep(6 * time.Millisecond)
	}
	return nil
}

func inputReady() bool {
	kbMu.Lock()
	defer kbMu.Unlock()
	_, err := openKeyboard()
	return err == nil
}

func inputHelp() string {
	return "Keyboard shortcuts need permission to create a virtual keyboard. Run this once:  deskmate --setup"
}

// setupInput installs a udev rule giving the logged-in user access to /dev/uinput
// (the same approach Steam uses for controllers) and loads the module at boot.
func setupInput() error {
	script := fmt.Sprintf(`set -e
echo '%s' > /etc/udev/rules.d/60-deskmate-uinput.rules
echo uinput > /etc/modules-load.d/deskmate-uinput.conf
modprobe uinput || true
udevadm control --reload-rules
udevadm trigger --name-match=uinput || udevadm trigger
`, uinputUdevRule)
	var cmd *exec.Cmd
	if _, err := exec.LookPath("pkexec"); err == nil && os.Getenv("DISPLAY")+os.Getenv("WAYLAND_DISPLAY") != "" {
		cmd = exec.Command("pkexec", "sh", "-c", script)
	} else {
		cmd = exec.Command("sudo", "sh", "-c", script)
	}
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		return err
	}
	time.Sleep(time.Second)
	return nil
}

func runCommand(c string) error {
	cmd := exec.Command("sh", "-c", c)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		return err
	}
	go cmd.Wait()
	return nil
}

func openTarget(t string) error {
	if t == "" {
		return fmt.Errorf("nothing to open")
	}
	return runCommand("xdg-open " + shellQuote(t))
}

func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

func terminalAction() Action {
	return Action{Type: "run", Value: "x-terminal-emulator || cosmic-term || gnome-terminal || konsole || xfce4-terminal || kitty || alacritty || xterm"}
}

func workspaceAction(next bool) Action {
	// COSMIC's default (vertical workspaces). GNOME uses super+pageup/pagedown,
	// KDE ctrl+f1…; change it in the settings page if yours differs.
	if next {
		return Action{Type: "keys", Value: "super+ctrl+down"}
	}
	return Action{Type: "keys", Value: "super+ctrl+up"}
}

func lockAction() Action {
	return Action{Type: "run", Value: "loginctl lock-session"}
}
