package main

import (
	"fmt"
	"math"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf16"
	"unsafe"
)

var (
	user32   = syscall.NewLazyDLL("user32.dll")
	kernel32 = syscall.NewLazyDLL("kernel32.dll")
	ntdll    = syscall.NewLazyDLL("ntdll.dll")
	ole32    = syscall.NewLazyDLL("ole32.dll")

	procSendInput        = user32.NewProc("SendInput")
	procOpenClipboard    = user32.NewProc("OpenClipboard")
	procCloseClipboard   = user32.NewProc("CloseClipboard")
	procEmptyClipboard   = user32.NewProc("EmptyClipboard")
	procGetClipboardData = user32.NewProc("GetClipboardData")
	procSetClipboardData = user32.NewProc("SetClipboardData")
	procGlobalAlloc      = kernel32.NewProc("GlobalAlloc")
	procGlobalFree       = kernel32.NewProc("GlobalFree")
	procGlobalLock       = kernel32.NewProc("GlobalLock")
	procGlobalUnlock     = kernel32.NewProc("GlobalUnlock")
	procGetSystemTimes   = kernel32.NewProc("GetSystemTimes")
	procGlobalMemStatus  = kernel32.NewProc("GlobalMemoryStatusEx")
	procGetDiskFreeSpace = kernel32.NewProc("GetDiskFreeSpaceExW")
	procGetTickCount64   = kernel32.NewProc("GetTickCount64")
	procNtQuerySysInfo   = ntdll.NewProc("NtQuerySystemInformation")
	procCoInitializeEx   = ole32.NewProc("CoInitializeEx")
	procCoCreateInstance = ole32.NewProc("CoCreateInstance")
)

func hiddenCommand(name string, args ...string) *exec.Cmd {
	cmd := exec.Command(name, args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
	return cmd
}

// ---------------------------------------------------------------- keyboard (SendInput)

const (
	inputKeyboard     = 1
	keyeventfExtended = 0x0001
	keyeventfKeyUp    = 0x0002
	keyeventfUnicode  = 0x0004
)

// INPUT with the KEYBDINPUT member, padded to the size of the union on 64-bit Windows.
type kbInput struct {
	typ       uint32
	_         uint32
	wVk       uint16
	wScan     uint16
	dwFlags   uint32
	time      uint32
	_         uint32
	extraInfo uintptr
	_         [8]byte
}

var kbMu sync.Mutex

func sendInputs(in []kbInput) error {
	if len(in) == 0 {
		return nil
	}
	n, _, err := procSendInput.Call(uintptr(len(in)), uintptr(unsafe.Pointer(&in[0])), unsafe.Sizeof(in[0]))
	if int(n) != len(in) {
		return fmt.Errorf("SendInput failed: %v", err)
	}
	return nil
}

func vkInput(k keyDef, up bool) kbInput {
	in := kbInput{typ: inputKeyboard, wVk: k.win}
	if k.extended {
		in.dwFlags |= keyeventfExtended
	}
	if up {
		in.dwFlags |= keyeventfKeyUp
	}
	return in
}

func pressCombo(keys []keyDef) error {
	kbMu.Lock()
	defer kbMu.Unlock()
	var in []kbInput
	for _, k := range keys {
		if k.win == 0 {
			return fmt.Errorf("that key isn't available on Windows")
		}
		in = append(in, vkInput(k, false))
	}
	for i := len(keys) - 1; i >= 0; i-- {
		in = append(in, vkInput(keys[i], true))
	}
	return sendInputs(in)
}

func typeText(s string) error {
	kbMu.Lock()
	defer kbMu.Unlock()
	var in []kbInput
	for _, u := range utf16.Encode([]rune(s)) {
		if u == '\n' {
			in = append(in, vkInput(keyTable["enter"], false), vkInput(keyTable["enter"], true))
			continue
		}
		in = append(in,
			kbInput{typ: inputKeyboard, wScan: u, dwFlags: keyeventfUnicode},
			kbInput{typ: inputKeyboard, wScan: u, dwFlags: keyeventfUnicode | keyeventfKeyUp})
	}
	return sendInputs(in)
}

func inputReady() bool  { return true }
func inputHelp() string { return "" }
func setupInput() error { fmt.Println("Nothing to set up on Windows."); return nil }

func runCommand(c string) error {
	cmd := hiddenCommand("cmd", "/C", c)
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
	cmd := hiddenCommand("rundll32", "url.dll,FileProtocolHandler", t)
	if err := cmd.Start(); err != nil {
		return err
	}
	go cmd.Wait()
	return nil
}

func openURL(u string) { openTarget(u) }

func terminalAction() Action { return Action{Type: "run", Value: "start \"\" wt || start \"\" cmd"} }
func lockAction() Action {
	return Action{Type: "run", Value: "rundll32.exe user32.dll,LockWorkStation"}
}
func workspaceAction(next bool) Action {
	if next {
		return Action{Type: "keys", Value: "ctrl+win+right"}
	}
	return Action{Type: "keys", Value: "ctrl+win+left"}
}

// ---------------------------------------------------------------- stats

type filetime struct{ low, high uint32 }

func (f filetime) u64() uint64 { return uint64(f.high)<<32 | uint64(f.low) }

type procPerf struct {
	idle, kernel, user, dpc, interrupt int64
	count                              uint32
	_                                  uint32
}

type sampler struct {
	idle, kernel, user uint64
	cores              []procPerf
	rx, tx             uint64
	lastNet            time.Time
}

func systemTimes() (idle, kernel, user uint64) {
	var i, k, u filetime
	procGetSystemTimes.Call(uintptr(unsafe.Pointer(&i)), uintptr(unsafe.Pointer(&k)), uintptr(unsafe.Pointer(&u)))
	return i.u64(), k.u64(), u.u64()
}

func corePerf() []procPerf {
	n := runtime.NumCPU()
	buf := make([]procPerf, n)
	var ret uint32
	r, _, _ := procNtQuerySysInfo.Call(8, uintptr(unsafe.Pointer(&buf[0])), uintptr(n)*unsafe.Sizeof(buf[0]), uintptr(unsafe.Pointer(&ret)))
	if r != 0 {
		return nil
	}
	return buf[:int(ret)/int(unsafe.Sizeof(buf[0]))]
}

// netBytes reads total bytes received/sent from "netstat -e" (first row with two numbers).
func netBytes() (rx, tx uint64) {
	out, err := hiddenCommand("netstat", "-e").Output()
	if err != nil {
		return
	}
	for _, line := range strings.Split(string(out), "\n") {
		f := strings.Fields(line)
		if len(f) < 3 {
			continue
		}
		r, e1 := strconv.ParseUint(f[len(f)-2], 10, 64)
		t, e2 := strconv.ParseUint(f[len(f)-1], 10, 64)
		if e1 == nil && e2 == nil {
			return r, t
		}
	}
	return
}

func newSampler() *sampler {
	s := &sampler{}
	s.idle, s.kernel, s.user = systemTimes()
	s.cores = corePerf()
	s.rx, s.tx = netBytes()
	s.lastNet = time.Now()
	return s
}

type memStatusEx struct {
	length                                     uint32
	memoryLoad                                 uint32
	totalPhys, availPhys, totalPage, availPage uint64
	totalVirtual, availVirtual, availExtended  uint64
}

var netCounter int

func (s *sampler) sample() Stats {
	var st Stats
	idle, kernel, user := systemTimes()
	if total := (kernel - s.kernel) + (user - s.user); total > 0 {
		st.CPU = 100 * (1 - float64(idle-s.idle)/float64(total))
	}
	s.idle, s.kernel, s.user = idle, kernel, user

	cores := corePerf()
	if len(cores) == len(s.cores) {
		for i := range cores {
			total := float64((cores[i].kernel - s.cores[i].kernel) + (cores[i].user - s.cores[i].user))
			v := 0.0
			if total > 0 {
				v = 100 * (1 - float64(cores[i].idle-s.cores[i].idle)/total)
			}
			st.Cores = append(st.Cores, math.Max(0, v))
		}
	}
	s.cores = cores

	m := memStatusEx{length: uint32(unsafe.Sizeof(memStatusEx{}))}
	if r, _, _ := procGlobalMemStatus.Call(uintptr(unsafe.Pointer(&m))); r != 0 {
		st.MemTotal = m.totalPhys
		st.MemUsed = m.totalPhys - m.availPhys
	}

	drive, _ := syscall.UTF16PtrFromString(os.Getenv("SystemDrive") + `\`)
	var free, total, totalFree uint64
	if r, _, _ := procGetDiskFreeSpace.Call(uintptr(unsafe.Pointer(drive)), uintptr(unsafe.Pointer(&free)), uintptr(unsafe.Pointer(&total)), uintptr(unsafe.Pointer(&totalFree))); r != 0 {
		st.DiskTotal = total
		st.DiskUsed = total - totalFree
	}

	// netstat is a separate program, so only ask every other second.
	netCounter++
	if netCounter%2 == 0 {
		rx, tx := netBytes()
		now := time.Now()
		if dt := now.Sub(s.lastNet).Seconds(); dt > 0 && rx >= s.rx && tx >= s.tx {
			lastRx, lastTx = float64(rx-s.rx)/dt, float64(tx-s.tx)/dt
		}
		s.rx, s.tx, s.lastNet = rx, tx, now
	}
	st.NetRx, st.NetTx = lastRx, lastTx

	if r, _, _ := procGetTickCount64.Call(); r != 0 {
		st.Uptime = uint64(r) / 1000
	}
	return st
}

var lastRx, lastTx float64

func sampleGPU() *GPUStats { return nvidiaGPU() }

// ---------------------------------------------------------------- volume (Core Audio via COM)

type guid struct {
	d1     uint32
	d2, d3 uint16
	d4     [8]byte
}

var (
	clsidMMDeviceEnumerator = guid{0xBCDE0395, 0xE52F, 0x467C, [8]byte{0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E}}
	iidIMMDeviceEnumerator  = guid{0xA95664D2, 0x9614, 0x4F35, [8]byte{0xA7, 0x46, 0xDE, 0x8D, 0xB6, 0x36, 0x17, 0xE6}}
	iidIAudioEndpointVolume = guid{0x5CDF2C82, 0x841E, 0x4546, [8]byte{0x97, 0x22, 0x0C, 0xF7, 0x40, 0x78, 0x22, 0x9A}}
)

// comCall calls method #index on a COM object.
func comCall(obj uintptr, index int, args ...uintptr) uintptr {
	vtbl := *(*uintptr)(unsafe.Pointer(obj))
	fn := *(*uintptr)(unsafe.Pointer(vtbl + uintptr(index)*unsafe.Sizeof(uintptr(0))))
	r, _, _ := syscall.SyscallN(fn, append([]uintptr{obj}, args...)...)
	return r
}

type audioJob struct {
	run  func(speaker, mic uintptr) error
	done chan error
}

var (
	audioOnce sync.Once
	audioJobs = make(chan audioJob)
	audioOK   bool
)

// COM objects must stay on one OS thread, so one goroutine owns them.
func startAudio() {
	audioOnce.Do(func() {
		ready := make(chan bool)
		go func() {
			runtime.LockOSThread()
			procCoInitializeEx.Call(0, 0) // COINIT_MULTITHREADED
			var enum uintptr
			if hr, _, _ := procCoCreateInstance.Call(uintptr(unsafe.Pointer(&clsidMMDeviceEnumerator)), 0, 0x17,
				uintptr(unsafe.Pointer(&iidIMMDeviceEnumerator)), uintptr(unsafe.Pointer(&enum))); hr != 0 || enum == 0 {
				ready <- false
				return
			}
			endpoint := func(flow uintptr) uintptr {
				var dev, vol uintptr
				if comCall(enum, 4, flow, 0, uintptr(unsafe.Pointer(&dev))) != 0 || dev == 0 { // GetDefaultAudioEndpoint
					return 0
				}
				comCall(dev, 3, uintptr(unsafe.Pointer(&iidIAudioEndpointVolume)), 0x17, 0, uintptr(unsafe.Pointer(&vol))) // Activate
				comCall(dev, 2)                                                                                            // Release
				return vol
			}
			ready <- true
			for job := range audioJobs {
				// Look the devices up each time so a newly plugged-in headset is used.
				spk, mic := endpoint(0), endpoint(1)
				err := job.run(spk, mic)
				if spk != 0 {
					comCall(spk, 2)
				}
				if mic != 0 {
					comCall(mic, 2)
				}
				job.done <- err
			}
		}()
		audioOK = <-ready
	})
}

func audio(run func(speaker, mic uintptr) error) error {
	startAudio()
	if !audioOK {
		return fmt.Errorf("audio control unavailable")
	}
	j := audioJob{run: run, done: make(chan error)}
	audioJobs <- j
	return <-j.done
}

func mediaControl(action string) error { return pressMediaKey(action) }

type MediaInfo struct {
	Title    string  `json:"title"`
	Artist   string  `json:"artist"`
	Playing  bool    `json:"playing"`
	Volume   float64 `json:"volume"`
	Muted    bool    `json:"muted"`
	MicMuted bool    `json:"micMuted"`
}

func mediaInfo() MediaInfo {
	m := MediaInfo{Volume: -1}
	audio(func(spk, mic uintptr) error {
		if spk != 0 {
			var level float32
			var muted int32
			if comCall(spk, 9, uintptr(unsafe.Pointer(&level))) == 0 { // GetMasterVolumeLevelScalar
				m.Volume = float64(level)
			}
			if comCall(spk, 15, uintptr(unsafe.Pointer(&muted))) == 0 { // GetMute
				m.Muted = muted != 0
			}
		}
		if mic != 0 {
			var muted int32
			if comCall(mic, 15, uintptr(unsafe.Pointer(&muted))) == 0 {
				m.MicMuted = muted != 0
			}
		}
		return nil
	})
	return m
}

func setVolume(v float64) error {
	v = math.Max(0, math.Min(1, v))
	return audio(func(spk, _ uintptr) error {
		if spk == 0 {
			return fmt.Errorf("no speakers found")
		}
		// Go passes the first four arguments in XMM registers too, so a float works here.
		if comCall(spk, 7, uintptr(math.Float32bits(float32(v))), 0) != 0 { // SetMasterVolumeLevelScalar
			return fmt.Errorf("couldn't set volume")
		}
		return nil
	})
}

func toggleMute(micDevice bool) error {
	return audio(func(spk, mic uintptr) error {
		dev := spk
		if micDevice {
			dev = mic
		}
		if dev == 0 {
			return fmt.Errorf("no audio device found")
		}
		var muted int32
		comCall(dev, 15, uintptr(unsafe.Pointer(&muted)))
		next := uintptr(1)
		if muted != 0 {
			next = 0
		}
		comCall(dev, 14, next, 0) // SetMute
		return nil
	})
}

func volumeSupported() bool {
	startAudio()
	return audioOK
}
func mediaInfoSupported() bool { return false }

// ---------------------------------------------------------------- clipboard

const cfUnicodeText = 13

func clipboardSupported() bool { return true }

func openClipboard() bool {
	for i := 0; i < 10; i++ {
		if r, _, _ := procOpenClipboard.Call(0); r != 0 {
			return true
		}
		time.Sleep(20 * time.Millisecond)
	}
	return false
}

func getClipboard() (string, error) {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if !openClipboard() {
		return "", fmt.Errorf("clipboard is busy")
	}
	defer procCloseClipboard.Call()
	h, _, _ := procGetClipboardData.Call(cfUnicodeText)
	if h == 0 {
		return "", nil
	}
	p, _, _ := procGlobalLock.Call(h)
	if p == 0 {
		return "", nil
	}
	defer procGlobalUnlock.Call(h)
	var units []uint16
	for i := uintptr(0); ; i += 2 {
		c := *(*uint16)(unsafe.Pointer(p + i))
		if c == 0 {
			break
		}
		units = append(units, c)
	}
	return string(utf16.Decode(units)), nil
}

func setClipboard(text string) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	units := utf16.Encode([]rune(text + "\x00"))
	size := uintptr(len(units) * 2)
	h, _, _ := procGlobalAlloc.Call(0x0002, size) // GMEM_MOVEABLE
	if h == 0 {
		return fmt.Errorf("out of memory")
	}
	p, _, _ := procGlobalLock.Call(h)
	if p == 0 {
		procGlobalFree.Call(h)
		return fmt.Errorf("couldn't lock memory")
	}
	copy(unsafe.Slice((*uint16)(unsafe.Pointer(p)), len(units)), units)
	procGlobalUnlock.Call(h)
	if !openClipboard() {
		procGlobalFree.Call(h)
		return fmt.Errorf("clipboard is busy")
	}
	defer procCloseClipboard.Call()
	procEmptyClipboard.Call()
	if r, _, _ := procSetClipboardData.Call(cfUnicodeText, h); r == 0 {
		procGlobalFree.Call(h)
		return fmt.Errorf("couldn't set clipboard")
	}
	return nil
}

// ---------------------------------------------------------------- autostart

const runKey = `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`

func autostartEnabled() bool {
	return hiddenCommand("reg", "query", runKey, "/v", "Deskmate").Run() == nil
}

func setAutostart(on bool) error {
	if !on {
		hiddenCommand("reg", "delete", runKey, "/v", "Deskmate", "/f").Run()
		return nil
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	return hiddenCommand("reg", "add", runKey, "/v", "Deskmate", "/t", "REG_SZ", "/d", `"`+exe+`" --background`, "/f").Run()
}

// hideConsole detaches from the console window when started at login.
func hideConsole() { kernel32.NewProc("FreeConsole").Call() }
