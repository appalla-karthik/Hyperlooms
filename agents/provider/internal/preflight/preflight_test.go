package preflight

import (
	"context"
	"errors"
	"testing"

	agentv1 "github.com/appalla-karthik/Hyperlooms/agents/provider/gen/hyperlooms/agent/v1"
)

func testGPUs(context.Context) ([]*agentv1.GpuCapability, error) {
	return []*agentv1.GpuCapability{{
		GpuUuid: "GPU-3a23c669-1f69-c64e-cf85-44e9b07e7a2a",
		Model:   "NVIDIA test GPU", VramBytes: 4 << 30,
		DriverVersion: "test", CudaVersion: "test",
	}}, nil
}

func TestPrerequisitesAreNotExecutionQualification(t *testing.T) {
	report := checkWith(context.Background(), "linux", "amd64", testGPUs,
		func(context.Context) (string, error) { return "linux/x86_64", nil })
	if !report.PrerequisitesObserved || len(report.GPUs) != 1 || len(report.Checks) != 3 ||
		report.Qualification == "" {
		t.Fatalf("unexpected preflight report: %+v", report)
	}
	for _, check := range report.Checks {
		if !check.Passed {
			t.Fatalf("expected observed prerequisite: %+v", check)
		}
	}
}

func TestUnsupportedPlatformDoesNotProbeHost(t *testing.T) {
	called := false
	report := checkWith(context.Background(), "linux", "arm64",
		func(context.Context) ([]*agentv1.GpuCapability, error) { called = true; return nil, nil },
		func(context.Context) (string, error) { called = true; return "", nil })
	if report.PrerequisitesObserved || called || len(report.Checks) != 1 || report.Checks[0].Passed {
		t.Fatalf("unsupported host was probed or accepted: %+v", report)
	}
}

func TestFailedProbesAreReportedIndependently(t *testing.T) {
	report := checkWith(context.Background(), "linux", "amd64",
		func(context.Context) ([]*agentv1.GpuCapability, error) { return nil, errors.New("driver absent") },
		func(context.Context) (string, error) { return "", errors.New("daemon absent") })
	if report.PrerequisitesObserved || len(report.Checks) != 3 ||
		report.Checks[1].Passed || report.Checks[2].Passed {
		t.Fatalf("probe failures hidden: %+v", report)
	}
}

func TestUnsupportedGPUAndDockerArchitecture(t *testing.T) {
	report := checkWith(context.Background(), "linux", "amd64",
		func(context.Context) ([]*agentv1.GpuCapability, error) {
			return []*agentv1.GpuCapability{{GpuUuid: "MIG-unsupported", VramBytes: 4 << 30}}, nil
		},
		func(context.Context) (string, error) { return "linux/aarch64", nil })
	if report.PrerequisitesObserved || len(report.GPUs) != 0 ||
		report.Checks[1].Passed || report.Checks[2].Passed {
		t.Fatalf("unsupported GPU or daemon accepted: %+v", report)
	}
}
