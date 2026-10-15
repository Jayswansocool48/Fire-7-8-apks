package main

import (
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

type GPUStats struct {
	Name      string  `json:"name"`
	Util      float64 `json:"util"`
	Temp      float64 `json:"temp"`
	VRAMUsed  float64 `json:"vramUsed"`  // MiB
	VRAMTotal float64 `json:"vramTotal"` // MiB
}

type Stats struct {
	CPU       float64   `json:"cpu"`
	Cores     []float64 `json:"cores"`
	CPUTemp   float64   `json:"cpuTemp"` // 0 = unknown
	MemUsed   uint64    `json:"memUsed"`
	MemTotal  uint64    `json:"memTotal"`
	DiskUsed  uint64    `json:"diskUsed"`
	DiskTotal uint64    `json:"diskTotal"`
	NetRx     float64   `json:"netRx"` // bytes/s
	NetTx     float64   `json:"netTx"`
	GPU       *GPUStats `json:"gpu,omitempty"`
	Host      string    `json:"host"`
	OS        string    `json:"os"`
	Uptime    uint64    `json:"uptime"` // seconds
}

var (
	statsMu     sync.Mutex
	latestStats Stats
	gpuMu       sync.Mutex
	latestGPU   *GPUStats
)

func currentStats() Stats {
	statsMu.Lock()
	defer statsMu.Unlock()
	return latestStats
}

// statsLoop samples once a second. The GPU is sampled every 2s in its own goroutine
// because nvidia-smi can be slow to answer.
func statsLoop() {
	host, _ := os.Hostname()
	go func() {
		for {
			g := sampleGPU()
			gpuMu.Lock()
			latestGPU = g
			gpuMu.Unlock()
			time.Sleep(2 * time.Second)
		}
	}()
	s := newSampler()
	for {
		st := s.sample()
		st.Host = host
		st.OS = runtime.GOOS
		gpuMu.Lock()
		st.GPU = latestGPU
		gpuMu.Unlock()
		statsMu.Lock()
		latestStats = st
		statsMu.Unlock()
		time.Sleep(time.Second)
	}
}

// nvidiaGPU works on Linux and Windows whenever the NVIDIA driver is installed.
func nvidiaGPU() *GPUStats {
	path, err := exec.LookPath("nvidia-smi")
	if err != nil {
		return nil
	}
	out, err := hiddenCommand(path, "--query-gpu=name,utilization.gpu,temperature.gpu,memory.used,memory.total",
		"--format=csv,noheader,nounits").Output()
	if err != nil {
		return nil
	}
	line := strings.TrimSpace(strings.SplitN(string(out), "\n", 2)[0])
	f := strings.Split(line, ",")
	if len(f) < 5 {
		return nil
	}
	num := func(s string) float64 { v, _ := strconv.ParseFloat(strings.TrimSpace(s), 64); return v }
	return &GPUStats{Name: strings.TrimSpace(f[0]), Util: num(f[1]), Temp: num(f[2]), VRAMUsed: num(f[3]), VRAMTotal: num(f[4])}
}
