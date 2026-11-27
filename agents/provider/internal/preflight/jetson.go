package preflight

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"strings"
)

// The Jetson lane is diagnostic only. In particular, the Nano's shared system
// memory is not discrete VRAM and its GPU has no desktop-style GPU UUID.
const jetsonQualification = "Not qualified: Jetson container GPU injection, shared-memory limits, an ARM64 image, restricted execution, and cleanup need a witnessed host smoke test. No GPU is reported to the control plane."

type jetsonProbes struct {
	readFile       func(string) ([]byte, error)
	exists         func(string) bool
	commandExists  func(string) bool
	dockerServer   func(context.Context) (string, error)
	dockerRuntimes func(context.Context) (string, error)
}

func checkJetson(ctx context.Context) Report {
	return checkJetsonWith(ctx, jetsonProbes{
		readFile: os.ReadFile,
		exists: func(path string) bool {
			_, err := os.Stat(path)
			return err == nil
		},
		commandExists: func(name string) bool {
			_, err := exec.LookPath(name)
			return err == nil
		},
		dockerServer: dockerServer,
		dockerRuntimes: func(ctx context.Context) (string, error) {
			return dockerInfo(ctx, "{{range $name, $_ := .Runtimes}}{{$name}} {{end}}")
		},
	})
}

func checkJetsonWith(ctx context.Context, probes jetsonProbes) Report {
	report := Report{
		HostOS: "linux", HostArch: "arm64", GPUs: []GPU{}, Checks: []Check{},
		Qualification: jetsonQualification,
	}
	model, modelErr := boundedHostText(probes.readFile, "/proc/device-tree/model")
	if modelErr == nil {
		report.HostModel = model
	}
	modelOK := modelErr == nil && model == "NVIDIA Jetson Nano Developer Kit"
	report.Checks = append(report.Checks, Check{
		Name: "jetson_model", Passed: modelOK,
		Detail: fmt.Sprintf("model=%q; experimental lane targets the original Jetson Nano Developer Kit", model),
	})
	release, releaseErr := boundedHostText(probes.readFile, "/etc/nv_tegra_release")
	if releaseErr == nil {
		report.L4TRelease = release
	}
	releaseOK := releaseErr == nil && strings.HasPrefix(release, "# R32 (release), REVISION: 7.6,")
	report.Checks = append(report.Checks, Check{
		Name: "l4t_release", Passed: releaseOK,
		Detail: fmt.Sprintf("release=%q; surveyed host runs L4T R32.7.6", release),
	})
	interfacesOK := probes.commandExists("tegrastats") &&
		probes.exists("/dev/nvhost-gpu") && probes.exists("/dev/nvhost-ctrl-gpu") && probes.exists("/dev/nvmap")
	report.Checks = append(report.Checks, Check{
		Name: "jetson_gpu_interfaces", Passed: interfacesOK,
		Detail: "requires tegrastats and the surveyed Jetson GPU device nodes; presence does not prove CUDA execution",
	})
	server, serverErr := probes.dockerServer(ctx)
	serverOK := serverErr == nil && server == "linux/aarch64"
	if serverErr != nil {
		server = serverErr.Error()
	}
	if serverOK {
		report.DockerServer = server
	}
	report.Checks = append(report.Checks, Check{
		Name: "docker_daemon", Passed: serverOK,
		Detail: fmt.Sprintf("Docker server=%q; must be accessible as the agent user and report linux/aarch64", server),
	})
	runtimes, runtimeErr := probes.dockerRuntimes(ctx)
	runtimeOK := runtimeErr == nil && containsWord(runtimes, "nvidia")
	runtimeDetail := "NVIDIA runtime registration is required but does not prove GPU injection"
	if runtimeErr != nil {
		runtimeDetail = runtimeErr.Error()
	}
	report.Checks = append(report.Checks, Check{
		Name: "nvidia_container_runtime", Passed: runtimeOK,
		Detail: runtimeDetail,
	})
	report.Checks = append(report.Checks, Check{
		Name: "execution_qualification", Detail: jetsonQualification,
	})
	return report
}

func boundedHostText(readFile func(string) ([]byte, error), path string) (string, error) {
	data, err := readFile(path)
	if err != nil {
		return "", err
	}
	if len(data) == 0 || len(data) > 256 {
		return "", fmt.Errorf("host identity file is empty or exceeds 256 bytes")
	}
	return strings.Trim(string(data), "\x00 \t\r\n"), nil
}

func containsWord(words, target string) bool {
	for _, word := range strings.Fields(words) {
		if word == target {
			return true
		}
	}
	return false
}
