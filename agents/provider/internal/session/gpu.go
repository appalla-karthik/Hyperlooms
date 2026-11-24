package session

import (
	"context"
	"encoding/csv"
	"fmt"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"

	agentv1 "github.com/appalla-karthik/Hyperlooms/agents/provider/gen/hyperlooms/agent/v1"
)

var cudaVersion = regexp.MustCompile(`CUDA Version:\s*([0-9.]+)`)

// DiscoverGPUs reads local NVIDIA driver output. These are unverified claims.
func DiscoverGPUs(ctx context.Context) ([]*agentv1.GpuCapability, error) {
	probeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	info, err := exec.CommandContext(probeCtx, "nvidia-smi").Output()
	if err != nil {
		return nil, fmt.Errorf("nvidia-smi unavailable: %w", err)
	}
	match := cudaVersion.FindStringSubmatch(string(info))
	if len(match) != 2 {
		return nil, fmt.Errorf("nvidia-smi did not report a CUDA version")
	}
	data, err := exec.CommandContext(probeCtx, "nvidia-smi", "--query-gpu=uuid,name,memory.total,driver_version", "--format=csv,noheader,nounits").Output()
	if err != nil {
		return nil, fmt.Errorf("nvidia-smi GPU query failed: %w", err)
	}
	return parseGPUs(string(data), match[1])
}

func parseGPUs(data, cuda string) ([]*agentv1.GpuCapability, error) {
	records, err := csv.NewReader(strings.NewReader(data)).ReadAll()
	if err != nil || len(records) == 0 || len(records) > 16 {
		return nil, fmt.Errorf("expected 1-16 NVIDIA GPU records")
	}
	gpus := make([]*agentv1.GpuCapability, 0, len(records))
	for _, row := range records {
		if len(row) != 4 {
			return nil, fmt.Errorf("unexpected NVIDIA GPU query fields")
		}
		uuid, model, memoryMiB, driver := strings.TrimSpace(row[0]), strings.TrimSpace(row[1]), strings.TrimSpace(row[2]), strings.TrimSpace(row[3])
		mib, err := strconv.ParseUint(memoryMiB, 10, 64)
		if err != nil || mib == 0 || mib > ^uint64(0)/(1024*1024) {
			return nil, fmt.Errorf("invalid NVIDIA GPU memory capacity")
		}
		if uuid == "" || model == "" || driver == "" || cuda == "" {
			return nil, fmt.Errorf("incomplete NVIDIA GPU capability")
		}
		gpus = append(gpus, &agentv1.GpuCapability{
			GpuUuid: uuid, Model: model, VramBytes: mib * 1024 * 1024,
			DriverVersion: driver, CudaVersion: cuda,
		})
	}
	return gpus, nil
}
