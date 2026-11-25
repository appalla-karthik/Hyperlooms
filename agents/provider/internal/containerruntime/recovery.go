package containerruntime

import (
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"runtime"
	"strings"
)

const maxRecoveryContainers = 64

var dockerIDPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// RecoverOrphans removes containers owned by this provider node after an agent crash.
// Call it only during single-instance startup, before admitting any new workload.
// It is intentionally not connected to the current agent startup or lease transport.
func RecoverOrphans(ctx context.Context, nodeID string) (int, error) {
	if runtime.GOOS != "linux" || runtime.GOARCH != "amd64" {
		return 0, fmt.Errorf("container recovery requires qualified linux/amd64 host")
	}
	return recoverWith(ctx, nodeID, dockerCLI{})
}

func recoverWith(ctx context.Context, nodeID string, cli commandRunner) (int, error) {
	if !leaseIDPattern.MatchString(nodeID) {
		return 0, fmt.Errorf("invalid provider node UUID")
	}
	output, err := runRecoveryCommand(ctx, cli, "container", "ls", "--all", "--no-trunc",
		"--filter=label="+managedLabelKey+"="+managedLabelValue,
		"--filter=label="+nodeLabelKey+"="+nodeID, "--format={{.ID}}")
	if err != nil {
		return 0, fmt.Errorf("list managed containers: %w", err)
	}
	ids := strings.Fields(output)
	if len(ids) > maxRecoveryContainers {
		return 0, fmt.Errorf("managed container count exceeds recovery limit %d", maxRecoveryContainers)
	}
	seen := make(map[string]bool, len(ids))
	for _, id := range ids {
		if !dockerIDPattern.MatchString(id) || seen[id] {
			return 0, fmt.Errorf("invalid or duplicate Docker container ID in recovery list")
		}
		seen[id] = true
	}

	// Verify the entire candidate set before removing any container. Docker IDs are
	// immutable; removal by exact ID cannot switch to another container with the same name.
	for _, id := range ids {
		if err := verifyOwnedContainer(ctx, cli, id, nodeID); err != nil {
			return 0, fmt.Errorf("verify managed container %s: %w", id, err)
		}
	}
	for i, id := range ids {
		if _, err := runRecoveryCommand(ctx, cli, "container", "rm", "--force", id); err != nil {
			return i, fmt.Errorf("remove orphaned container %s: %w", id, err)
		}
	}
	return len(ids), nil
}

func verifyOwnedContainer(ctx context.Context, cli commandRunner, id, nodeID string) error {
	output, err := runRecoveryCommand(ctx, cli, "container", "inspect", "--format={{json .Config.Labels}}", id)
	if err != nil {
		return fmt.Errorf("inspect labels: %w", err)
	}
	var labels map[string]string
	if err := json.Unmarshal([]byte(output), &labels); err != nil {
		return fmt.Errorf("parse container labels: %w", err)
	}
	leaseID := labels[leaseLabelKey]
	if labels[managedLabelKey] != managedLabelValue || labels[nodeLabelKey] != nodeID ||
		!leaseIDPattern.MatchString(leaseID) {
		return fmt.Errorf("container ownership labels do not match")
	}
	name, err := runRecoveryCommand(ctx, cli, "container", "inspect", "--format={{.Name}}", id)
	if err != nil {
		return fmt.Errorf("inspect name: %w", err)
	}
	if !managedContainerName(strings.TrimPrefix(strings.TrimSpace(name), "/"), leaseID) {
		return fmt.Errorf("container name does not match its lease")
	}
	return nil
}

func runRecoveryCommand(ctx context.Context, cli commandRunner, args ...string) (string, error) {
	commandCtx, cancel := context.WithTimeout(ctx, commandTimeout)
	defer cancel()
	output, err := cli.run(commandCtx, args...)
	if err == nil && commandCtx.Err() != nil {
		err = commandCtx.Err()
	}
	return output, err
}
