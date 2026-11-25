package containerruntime

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	minMemoryBytes    = 256 << 20
	maxMemoryBytes    = 1 << 40
	commandTimeout    = 15 * time.Second
	maxCommandOutput  = 32 << 10
	managedLabelKey   = "dev.hyperlooms.managed"
	managedLabelValue = "provider-agent"
	nodeLabelKey      = "dev.hyperlooms.node"
	leaseLabelKey     = "dev.hyperlooms.lease"
)

var (
	leaseIDPattern       = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	gpuUUIDPattern       = regexp.MustCompile(`^GPU-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)
	digestPattern        = regexp.MustCompile(`^sha256:[0-9a-f]{64}$`)
	repositoryPattern    = regexp.MustCompile(`^[a-z0-9][a-z0-9.-]*(?::[0-9]{1,5})?/[a-z0-9]+(?:[._/-][a-z0-9]+)*$`)
	containerNamePattern = regexp.MustCompile(`^hyperlooms-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-[0-9a-f]{16}$`)
)

// Spec is an internal, already-authorized local execution request. No network message creates one yet.
// Image verification, artifact staging and executable command delivery are separate gates.
type Spec struct {
	NodeID         string
	LeaseID        string
	Image          string
	GPUUUID        string
	MemoryBytes    int64
	MaxRuntime     time.Duration
	LeaseExpiresAt time.Time
}

type Result struct {
	ExitCode int
}

// Execute is Linux amd64 only until other platform/driver matrices are qualified.
func Execute(ctx context.Context, spec Spec) (Result, error) {
	if runtime.GOOS != "linux" || runtime.GOARCH != "amd64" {
		return Result{}, fmt.Errorf("container execution requires qualified linux/amd64 host")
	}
	nonce := make([]byte, 8)
	if _, err := rand.Read(nonce); err != nil {
		return Result{}, fmt.Errorf("generate container identity: %w", err)
	}
	name := "hyperlooms-" + spec.LeaseID + "-" + hex.EncodeToString(nonce)
	return executeWith(ctx, spec, dockerCLI{}, name)
}

type commandRunner interface {
	run(context.Context, ...string) (string, error)
}

func executeWith(ctx context.Context, spec Spec, cli commandRunner, name string) (result Result, err error) {
	if err := validate(spec); err != nil {
		return Result{}, err
	}
	if !managedContainerName(name, spec.LeaseID) {
		return Result{}, fmt.Errorf("invalid managed container name")
	}
	deadline := spec.LeaseExpiresAt
	if maxDeadline := time.Now().Add(spec.MaxRuntime); maxDeadline.Before(deadline) {
		deadline = maxDeadline
	}
	runCtx, cancel := context.WithDeadline(ctx, deadline)
	defer cancel()
	if err := runCtx.Err(); err != nil {
		return Result{}, err
	}
	createArgs := []string{
		"create", "--pull=never", "--name=" + name, "--platform=linux/amd64",
		"--label=" + managedLabelKey + "=" + managedLabelValue,
		"--label=" + nodeLabelKey + "=" + spec.NodeID,
		"--label=" + leaseLabelKey + "=" + spec.LeaseID,
		"--network=none", "--read-only", "--cap-drop=ALL",
		"--security-opt=no-new-privileges=true", "--security-opt=seccomp=builtin",
		"--pids-limit=256", "--memory=" + strconv.FormatInt(spec.MemoryBytes, 10),
		"--memory-swap=" + strconv.FormatInt(spec.MemoryBytes, 10),
		"--gpus=device=" + spec.GPUUUID, "--user=65532:65532",
		"--tmpfs=/tmp:rw,nosuid,nodev,size=64m", "--log-driver=none",
		"--init", "--restart=no", spec.Image,
	}
	createdID, err := cli.run(runCtx, createArgs...)
	if err != nil {
		return Result{}, fmt.Errorf("create restricted container: %w", err)
	}
	createdID = strings.TrimSpace(createdID)
	if !dockerIDPattern.MatchString(createdID) {
		return Result{}, fmt.Errorf("Docker create did not return an exact container ID; manual recovery required for %s", name)
	}
	defer func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), commandTimeout)
		defer cleanupCancel()
		_, cleanupErr := cli.run(cleanupCtx, "rm", "-f", createdID)
		if cleanupErr != nil {
			err = errors.Join(err, fmt.Errorf("remove managed container: %w", cleanupErr))
		}
	}()
	if err := runCtx.Err(); err != nil {
		return Result{}, err
	}
	if _, err := cli.run(runCtx, "start", createdID); err != nil {
		return Result{}, fmt.Errorf("start restricted container: %w", err)
	}
	if err := runCtx.Err(); err != nil {
		return Result{}, err
	}
	output, err := cli.run(runCtx, "wait", createdID)
	if err != nil {
		return Result{}, fmt.Errorf("wait for restricted container: %w", err)
	}
	if err := runCtx.Err(); err != nil {
		return Result{}, err
	}
	exitCode, err := strconv.Atoi(strings.TrimSpace(output))
	if err != nil || exitCode < 0 || exitCode > 255 {
		return Result{}, fmt.Errorf("invalid container exit status")
	}
	return Result{ExitCode: exitCode}, nil
}

func validate(spec Spec) error {
	if !leaseIDPattern.MatchString(spec.NodeID) || !leaseIDPattern.MatchString(spec.LeaseID) ||
		!gpuUUIDPattern.MatchString(spec.GPUUUID) {
		return fmt.Errorf("invalid node, lease, or GPU UUID")
	}
	repository, digest, ok := strings.Cut(spec.Image, "@")
	if !ok || !repositoryPattern.MatchString(repository) || !digestPattern.MatchString(digest) {
		return fmt.Errorf("image must be an exact registry repository and SHA-256 digest")
	}
	if spec.MemoryBytes < minMemoryBytes || spec.MemoryBytes > maxMemoryBytes ||
		spec.MaxRuntime <= 0 || spec.MaxRuntime > 24*time.Hour ||
		!spec.LeaseExpiresAt.After(time.Now().Add(time.Second)) {
		return fmt.Errorf("invalid resource limit or expired lease")
	}
	return nil
}

func managedContainerName(name, leaseID string) bool {
	match := containerNamePattern.FindStringSubmatch(name)
	return len(match) == 2 && match[1] == leaseID
}

type dockerCLI struct{}

func (dockerCLI) run(ctx context.Context, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, "docker", args...)
	stdout := &boundedOutput{limit: maxCommandOutput}
	stderr := &boundedOutput{limit: maxCommandOutput}
	cmd.Stdout = stdout
	cmd.Stderr = stderr
	if err := cmd.Run(); err != nil {
		return stdout.String(), fmt.Errorf("docker command failed: %w: %s", err, stderr.String())
	}
	return stdout.String(), nil
}

type boundedOutput struct {
	mu    sync.Mutex
	data  []byte
	limit int
}

func (output *boundedOutput) Write(p []byte) (int, error) {
	output.mu.Lock()
	defer output.mu.Unlock()
	if room := output.limit - len(output.data); room > 0 {
		output.data = append(output.data, p[:min(room, len(p))]...)
	}
	return len(p), nil
}

func (output *boundedOutput) String() string {
	output.mu.Lock()
	defer output.mu.Unlock()
	return string(output.data)
}

var _ io.Writer = (*boundedOutput)(nil)
