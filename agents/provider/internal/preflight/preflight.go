package preflight

import (
	"context"
	"fmt"
	"os/exec"
	"regexp"
	"runtime"
	"strings"
	"time"

	agentv1 "github.com/appalla-karthik/Hyperlooms/agents/provider/gen/hyperlooms/agent/v1"
	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/session"
)

var gpuUUID = regexp.MustCompile(`^GPU-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

type Check struct {
	Name   string `json:"name"`
	Passed bool   `json:"passed"`
	Detail string `json:"detail"`
}

type GPU struct {
	UUID          string `json:"uuid"`
	Model         string `json:"model"`
	VRAMBytes     uint64 `json:"vramBytes"`
	DriverVersion string `json:"driverVersion"`
	CUDAVersion   string `json:"cudaVersion"`
}

// Report is a read-only prerequisite report, not execution qualification.
type Report struct {
	HostOS                string  `json:"hostOs"`
	HostArch              string  `json:"hostArch"`
	HostModel             string  `json:"hostModel,omitempty"`
	L4TRelease            string  `json:"l4tRelease,omitempty"`
	DockerServer          string  `json:"dockerServer,omitempty"`
	GPUs                  []GPU   `json:"gpus"`
	Checks                []Check `json:"checks"`
	PrerequisitesObserved bool    `json:"prerequisitesObserved"`
	Qualification         string  `json:"qualification"`
}

// CheckHost never creates or removes a Docker container or changes host state.
func CheckHost(ctx context.Context) Report {
	if runtime.GOOS == "linux" && runtime.GOARCH == "arm64" {
		return checkJetson(ctx)
	}
	return checkWith(ctx, runtime.GOOS, runtime.GOARCH, session.DiscoverGPUs, dockerServer)
}

func checkWith(ctx context.Context, hostOS, hostArch string,
	discover func(context.Context) ([]*agentv1.GpuCapability, error),
	docker func(context.Context) (string, error)) Report {
	report := Report{
		HostOS: hostOS, HostArch: hostArch, GPUs: []GPU{}, Checks: []Check{},
		Qualification: "Not qualified: a local Docker daemon, NVIDIA container injection, restricted execution, and cleanup still require a witnessed host smoke test.",
	}
	platformOK := hostOS == "linux" && hostArch == "amd64"
	report.Checks = append(report.Checks, Check{
		Name: "host_platform", Passed: platformOK,
		Detail: fmt.Sprintf("host is %s/%s; initial runtime requires linux/amd64", hostOS, hostArch),
	})
	if !platformOK {
		return report
	}

	gpus, gpuErr := discover(ctx)
	if gpuErr == nil {
		if len(gpus) == 0 || len(gpus) > 16 {
			gpuErr = fmt.Errorf("expected 1-16 NVIDIA GPU records")
		} else {
			for _, gpu := range gpus {
				if gpu == nil || !gpuUUID.MatchString(gpu.GetGpuUuid()) || gpu.GetVramBytes() == 0 {
					gpuErr = fmt.Errorf("GPU report contains an unsupported UUID or zero VRAM")
					break
				}
				report.GPUs = append(report.GPUs, GPU{
					UUID: gpu.GetGpuUuid(), Model: gpu.GetModel(), VRAMBytes: gpu.GetVramBytes(),
					DriverVersion: gpu.GetDriverVersion(), CUDAVersion: gpu.GetCudaVersion(),
				})
			}
		}
	}
	if gpuErr != nil {
		report.GPUs = []GPU{}
		report.Checks = append(report.Checks, Check{Name: "nvidia_discovery", Detail: gpuErr.Error()})
	} else {
		report.Checks = append(report.Checks, Check{
			Name: "nvidia_discovery", Passed: true,
			Detail: fmt.Sprintf("discovered %d NVIDIA GPU(s); hardware identity is not attested", len(report.GPUs)),
		})
	}

	server, dockerErr := docker(ctx)
	if dockerErr == nil {
		dockerErr = validateDockerServer(server)
	}
	if dockerErr != nil {
		report.Checks = append(report.Checks, Check{Name: "docker_daemon", Detail: dockerErr.Error()})
	} else {
		report.DockerServer = server
		report.Checks = append(report.Checks, Check{
			Name: "docker_daemon", Passed: true,
			Detail: "compatible Docker server reported; daemon locality and GPU injection are not verified",
		})
	}
	report.PrerequisitesObserved = gpuErr == nil && dockerErr == nil
	return report
}

func dockerServer(ctx context.Context) (string, error) {
	return dockerInfo(ctx, "{{.OSType}}/{{.Architecture}}")
}

func dockerInfo(ctx context.Context, format string) (string, error) {
	probeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	output, err := exec.CommandContext(probeCtx, "docker", "info", "--format="+format).Output()
	if err != nil {
		return "", fmt.Errorf("Docker daemon unavailable to this user: %w", err)
	}
	if len(output) > 128 {
		return "", fmt.Errorf("Docker server identity output exceeds limit")
	}
	return strings.TrimSpace(string(output)), nil
}

func validateDockerServer(server string) error {
	if server != "linux/x86_64" && server != "linux/amd64" {
		return fmt.Errorf("Docker server must report linux/x86_64 or linux/amd64; got %q", server)
	}
	return nil
}
