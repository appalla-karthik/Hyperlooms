package preflight

import (
	"context"
	"errors"
	"strings"
	"testing"
)

func surveyedJetsonProbes() jetsonProbes {
	return jetsonProbes{
		readFile: func(path string) ([]byte, error) {
			switch path {
			case "/proc/device-tree/model":
				return []byte("NVIDIA Jetson Nano Developer Kit\x00"), nil
			case "/etc/nv_tegra_release":
				return []byte("# R32 (release), REVISION: 7.6, GCID: 38171779, BOARD: t210ref\n"), nil
			default:
				return nil, errors.New("unexpected file")
			}
		},
		exists:         func(path string) bool { return strings.HasPrefix(path, "/dev/nv") },
		commandExists:  func(name string) bool { return name == "tegrastats" },
		dockerServer:   func(context.Context) (string, error) { return "linux/aarch64", nil },
		dockerRuntimes: func(context.Context) (string, error) { return "runc nvidia", nil },
	}
}

func TestJetsonDiagnosticsNeverQualifyExecution(t *testing.T) {
	report := checkJetsonWith(context.Background(), surveyedJetsonProbes())
	if report.PrerequisitesObserved || len(report.GPUs) != 0 ||
		report.HostModel != "NVIDIA Jetson Nano Developer Kit" ||
		report.DockerServer != "linux/aarch64" || len(report.Checks) != 6 {
		t.Fatalf("Jetson was qualified or evidence missing: %+v", report)
	}
	for _, check := range report.Checks[:5] {
		if !check.Passed {
			t.Fatalf("surveyed prerequisite missing: %+v", check)
		}
	}
	if report.Checks[5].Passed || report.Checks[5].Name != "execution_qualification" {
		t.Fatalf("Jetson execution was marked qualified: %+v", report.Checks[5])
	}
}

func TestJetsonRejectsOtherModelAndDockerPermissionFailure(t *testing.T) {
	probes := surveyedJetsonProbes()
	probes.readFile = func(path string) ([]byte, error) {
		if path == "/proc/device-tree/model" {
			return []byte("NVIDIA Jetson Orin Nano Developer Kit\x00"), nil
		}
		return nil, errors.New("missing release")
	}
	probes.dockerServer = func(context.Context) (string, error) { return "", errors.New("access denied") }
	probes.dockerRuntimes = func(context.Context) (string, error) { return "", errors.New("access denied") }
	report := checkJetsonWith(context.Background(), probes)
	if report.Checks[0].Passed || report.Checks[1].Passed || report.Checks[3].Passed ||
		report.Checks[4].Passed || report.PrerequisitesObserved || len(report.GPUs) != 0 ||
		!strings.Contains(report.Checks[3].Detail, "access denied") {
		t.Fatalf("wrong model or Docker error was hidden: %+v", report)
	}
}

func TestJetsonRuntimeMatchRequiresWholeWord(t *testing.T) {
	if containsWord("not-nvidia runc", "nvidia") || !containsWord("runc nvidia", "nvidia") {
		t.Fatal("NVIDIA runtime name was matched incorrectly")
	}
}
