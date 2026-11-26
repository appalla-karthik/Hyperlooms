package containerruntime

import (
	"context"
	"errors"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
)

const (
	testLeaseID = "cafe0000-0000-4000-8000-000000000001"
	testGPUUUID = "GPU-3a23c669-1f69-c64e-cf85-44e9b07e7a2a"
	testImage   = "registry.example.invalid/hyperlooms/pytorch@sha256:" +
		"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	testContainerName = "hyperlooms-cafe0000-0000-4000-8000-000000000001-0000000000000001"
	testCreatedID     = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
)

type fakeDocker struct {
	calls     [][]string
	responses map[string]string
	errors    map[string]error
	onCall    func(string)
	handle    func([]string) (string, error)
}

func (fake *fakeDocker) run(_ context.Context, args ...string) (string, error) {
	fake.calls = append(fake.calls, append([]string{}, args...))
	if fake.onCall != nil {
		fake.onCall(args[0])
	}
	if fake.handle != nil {
		return fake.handle(args)
	}
	if args[0] == "create" && fake.errors[args[0]] == nil && fake.responses[args[0]] == "" {
		return testCreatedID, nil
	}
	return fake.responses[args[0]], fake.errors[args[0]]
}

func testSpec() Spec {
	return Spec{
		NodeID:  "cafe0000-0000-4000-8000-000000000002",
		LeaseID: testLeaseID, Image: testImage, GPUUUID: testGPUUUID,
		MemoryBytes: 2 << 30, MaxRuntime: time.Minute,
		LeaseExpiresAt: time.Now().Add(2 * time.Minute),
	}
}

func TestRestrictedDockerPlanAndCleanup(t *testing.T) {
	fake := &fakeDocker{responses: map[string]string{"wait": "0\n"}}
	result, err := executeWith(context.Background(), testSpec(), fake, testContainerName)
	if err != nil || result.ExitCode != 0 {
		t.Fatalf("execution failed: result=%+v err=%v", result, err)
	}
	if len(fake.calls) != 4 || fake.calls[0][0] != "create" ||
		fake.calls[1][0] != "start" || fake.calls[2][0] != "wait" || fake.calls[3][0] != "rm" {
		t.Fatalf("unexpected Docker lifecycle: %v", fake.calls)
	}
	args := fake.calls[0]
	for _, required := range []string{
		"--pull=never", "--platform=linux/amd64", "--network=none", "--read-only",
		"--cap-drop=ALL", "--security-opt=no-new-privileges=true",
		"--security-opt=seccomp=builtin", "--pids-limit=256",
		"--memory=2147483648", "--memory-swap=2147483648",
		"--gpus=device=" + testGPUUUID, "--user=65532:65532",
		"--label=" + managedLabelKey + "=" + managedLabelValue,
		"--label=" + nodeLabelKey + "=" + testSpec().NodeID,
		"--label=" + leaseLabelKey + "=" + testLeaseID,
		"--log-driver=none", "--restart=no", testImage,
	} {
		if !contains(args, required) {
			t.Fatalf("missing Docker restriction %q in %v", required, args)
		}
	}
	for _, forbidden := range []string{"--privileged", "--network=host", "--gpus=all"} {
		if contains(args, forbidden) {
			t.Fatalf("unsafe Docker option %q", forbidden)
		}
	}
	if !reflect.DeepEqual(fake.calls[3], []string{"rm", "-f", testCreatedID}) ||
		!reflect.DeepEqual(fake.calls[1], []string{"start", testCreatedID}) ||
		!reflect.DeepEqual(fake.calls[2], []string{"wait", testCreatedID}) {
		t.Fatalf("managed container was not removed: %v", fake.calls[3])
	}
}

func TestCreateWithoutExactIDDoesNotTargetNameForRemoval(t *testing.T) {
	fake := &fakeDocker{responses: map[string]string{"create": "not-an-id"}}
	if _, err := executeWith(context.Background(), testSpec(), fake, testContainerName); err == nil ||
		!strings.Contains(err.Error(), "manual recovery required") {
		t.Fatalf("invalid create output was accepted: %v", err)
	}
	if len(fake.calls) != 1 {
		t.Fatalf("unexpected command after unverified create: %v", fake.calls)
	}
}

func TestNonzeroExitIsAWorkloadResult(t *testing.T) {
	fake := &fakeDocker{responses: map[string]string{"wait": "17\n"}}
	result, err := executeWith(context.Background(), testSpec(), fake, testContainerName)
	if err != nil || result.ExitCode != 17 {
		t.Fatalf("expected workload exit code 17: result=%+v err=%v", result, err)
	}
}

func TestBoundedInputAndOutputArtifactWiring(t *testing.T) {
	spec := testSpec()
	spec.InputJSON = []byte(`{"schemaVersion":1}`)
	fake := &fakeDocker{responses: map[string]string{"wait": "0"}}
	fake.handle = func(args []string) (string, error) {
		switch args[0] {
		case "create":
			return testCreatedID, nil
		case "wait":
			return "0", os.WriteFile(outputMountSource(fake.calls[0]), []byte(`{"schemaVersion":1,"device":"cuda"}`), 0600)
		default:
			return "", nil
		}
	}
	result, err := executeWith(context.Background(), spec, fake, testContainerName)
	if err != nil || result.ExitCode != 0 || !strings.Contains(string(result.OutputJSON), `"device":"cuda"`) {
		t.Fatalf("artifact roundtrip failed: result=%+v err=%v", result, err)
	}
	if len(fake.calls) != 4 || fake.calls[3][0] != "rm" {
		t.Fatalf("result was not read before cleanup: %v", fake.calls)
	}
	var inputMounted bool
	for _, arg := range fake.calls[0] {
		if strings.HasPrefix(arg, "--mount=type=bind,src=") &&
			strings.HasSuffix(arg, ",dst=/inputs/request.json,readonly") {
			inputMounted = true
		}
	}
	if !inputMounted || outputMountSource(fake.calls[0]) == "" ||
		!contains(fake.calls[0], "--ulimit=fsize=4194304:4194304") {
		t.Fatalf("input/output mounts missing: %v", fake.calls[0])
	}
}

func outputMountSource(args []string) string {
	const prefix = "--mount=type=bind,src="
	const suffix = ",dst=/outputs/result.json"
	for _, arg := range args {
		if strings.HasPrefix(arg, prefix) && strings.HasSuffix(arg, suffix) {
			return strings.TrimSuffix(strings.TrimPrefix(arg, prefix), suffix)
		}
	}
	return ""
}

func TestMissingResultFailsClosedAndRemovesContainer(t *testing.T) {
	spec := testSpec()
	spec.InputJSON = []byte(`{}`)
	fake := &fakeDocker{responses: map[string]string{"wait": "0"}}
	if _, err := executeWith(context.Background(), spec, fake, testContainerName); err == nil ||
		!strings.Contains(err.Error(), "result artifact") {
		t.Fatalf("missing result accepted: %v", err)
	}
	if len(fake.calls) != 4 || fake.calls[3][0] != "rm" {
		t.Fatalf("missing result skipped cleanup: %v", fake.calls)
	}
}

func TestMalformedResultFailsClosedAndRemovesContainer(t *testing.T) {
	spec := testSpec()
	spec.InputJSON = []byte(`{}`)
	fake := &fakeDocker{responses: map[string]string{"wait": "0"}}
	fake.handle = func(args []string) (string, error) {
		switch args[0] {
		case "create":
			return testCreatedID, nil
		case "wait":
			return "0", os.WriteFile(outputMountSource(fake.calls[0]), []byte(`not-json`), 0600)
		}
		return "", nil
	}
	if _, err := executeWith(context.Background(), spec, fake, testContainerName); err == nil ||
		!strings.Contains(err.Error(), "invalid JSON") {
		t.Fatalf("malformed result accepted: %v", err)
	}
	if len(fake.calls) != 4 || fake.calls[3][0] != "rm" {
		t.Fatalf("malformed result skipped cleanup: %v", fake.calls)
	}
}

func TestValidationRejectsUnpinnedOrExpiredWorkWithoutDocker(t *testing.T) {
	bad := []Spec{
		func() Spec { s := testSpec(); s.NodeID = "wrong"; return s }(),
		func() Spec { s := testSpec(); s.Image = "ubuntu:latest"; return s }(),
		func() Spec { s := testSpec(); s.GPUUUID = "GPU-test"; return s }(),
		func() Spec { s := testSpec(); s.MemoryBytes = 0; return s }(),
		func() Spec { s := testSpec(); s.LeaseExpiresAt = time.Now().Add(-time.Second); return s }(),
		func() Spec { s := testSpec(); s.InputJSON = []byte("not-json"); return s }(),
		func() Spec { s := testSpec(); s.InputJSON = []byte(strings.Repeat("x", maxArtifactBytes+1)); return s }(),
	}
	for _, spec := range bad {
		fake := &fakeDocker{}
		if _, err := executeWith(context.Background(), spec, fake, testContainerName); err == nil {
			t.Fatalf("accepted invalid spec: %+v", spec)
		}
		if len(fake.calls) != 0 {
			t.Fatalf("invalid spec reached Docker: %v", fake.calls)
		}
	}
}

func TestExecutionRejectsForeignContainerName(t *testing.T) {
	fake := &fakeDocker{}
	if _, err := executeWith(context.Background(), testSpec(), fake,
		"hyperlooms-cafe0000-0000-4000-8000-000000000003-0000000000000001"); err == nil {
		t.Fatal("accepted a container name for another lease")
	}
	if len(fake.calls) != 0 {
		t.Fatalf("foreign container name reached Docker: %v", fake.calls)
	}
}

func TestFailureAfterCreateAlwaysCleansUp(t *testing.T) {
	fake := &fakeDocker{errors: map[string]error{"start": errors.New("start failed")}}
	if _, err := executeWith(context.Background(), testSpec(), fake, testContainerName); err == nil {
		t.Fatal("start failure was ignored")
	}
	if len(fake.calls) != 3 || fake.calls[2][0] != "rm" {
		t.Fatalf("container not removed after start failure: %v", fake.calls)
	}
	fake = &fakeDocker{responses: map[string]string{"wait": "0"},
		errors: map[string]error{"rm": errors.New("cleanup failed")}}
	if _, err := executeWith(context.Background(), testSpec(), fake, testContainerName); err == nil || !strings.Contains(err.Error(), "cleanup failed") {
		t.Fatalf("cleanup failure was not surfaced: %v", err)
	}
}

func TestCancellationStillUsesIndependentCleanupContext(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	fake := &fakeDocker{responses: map[string]string{"wait": "0"}}
	fake.onCall = func(command string) {
		if command == "start" {
			cancel()
		}
	}
	_, err := executeWith(ctx, testSpec(), fake, testContainerName)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("cancellation not surfaced: %v", err)
	}
	if len(fake.calls) != 3 || fake.calls[2][0] != "rm" {
		t.Fatalf("cancellation skipped cleanup: %v", fake.calls)
	}
}

func TestOutputBufferIsBounded(t *testing.T) {
	output := &boundedOutput{limit: 10}
	if n, err := output.Write([]byte(strings.Repeat("x", 100))); err != nil || n != 100 {
		t.Fatalf("bounded writer did not consume input: n=%d err=%v", n, err)
	}
	if len(output.String()) != 10 {
		t.Fatalf("output not bounded: %d", len(output.String()))
	}
}

func contains(values []string, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}
