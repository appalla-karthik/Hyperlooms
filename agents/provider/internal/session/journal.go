package session

import (
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	agentv1 "github.com/appalla-karthik/Hyperlooms/agents/provider/gen/hyperlooms/agent/v1"
)

const journalFile = "lease-receipt.json"

// savedLease records transport receipt and optional command staging, never permission to run.
type savedLease struct {
	NodeID       string        `json:"nodeId"`
	JobID        string        `json:"jobId"`
	AttemptID    string        `json:"attemptId"`
	LeaseID      string        `json:"leaseId"`
	GPUUUID      string        `json:"gpuUuid"`
	FencingToken uint64        `json:"fencingToken"`
	ExpiresAt    time.Time     `json:"expiresAt"`
	ConfirmedAt  *time.Time    `json:"confirmedAt,omitempty"`
	Command      *savedCommand `json:"command,omitempty"`
}

type savedCommand struct {
	CommandID           string     `json:"commandId"`
	ImageRepository     string     `json:"imageRepository"`
	ImageDigest         string     `json:"imageDigest"`
	MaxRuntimeSeconds   uint32     `json:"maxRuntimeSeconds"`
	VerifiedInputPrefix string     `json:"verifiedInputPrefix"`
	InputManifestSHA256 string     `json:"inputManifestSha256"`
	InputFileCount      uint32     `json:"inputFileCount"`
	InputTotalBytes     uint64     `json:"inputTotalBytes"`
	StagedAt            time.Time  `json:"stagedAt"`
	ServerConfirmedAt   *time.Time `json:"serverConfirmedAt,omitempty"`
}

type leaseJournal struct {
	dir    string
	nodeID string
	active *savedLease
}

func openLeaseJournal(dir, nodeID string) (*leaseJournal, error) {
	if dir == "" || !filepath.IsAbs(dir) || filepath.Dir(filepath.Clean(dir)) == filepath.Clean(dir) {
		return nil, fmt.Errorf("lease state directory must be absolute and non-root")
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, fmt.Errorf("create lease state directory: %w", err)
	}
	info, err := os.Lstat(dir)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 ||
		(runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0) {
		return nil, fmt.Errorf("lease state directory must be a private real directory")
	}
	journal := &leaseJournal{dir: dir, nodeID: nodeID}
	path := filepath.Join(dir, journalFile)
	fileInfo, err := os.Lstat(path)
	if os.IsNotExist(err) {
		return journal, nil
	}
	if err != nil || !fileInfo.Mode().IsRegular() || fileInfo.Size() > 4096 ||
		(runtime.GOOS != "windows" && fileInfo.Mode().Perm()&0077 != 0) {
		return nil, fmt.Errorf("lease receipt file is unsafe")
	}
	content, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read lease receipt: %w", err)
	}
	var saved savedLease
	if err := json.Unmarshal(content, &saved); err != nil || saved.NodeID != nodeID ||
		!validUUID(saved.JobID) || !validUUID(saved.AttemptID) || !validUUID(saved.LeaseID) ||
		saved.GPUUUID == "" || saved.FencingToken == 0 || saved.ExpiresAt.IsZero() ||
		(saved.Command != nil && (saved.ConfirmedAt == nil || !validSavedCommand(saved.Command, saved.JobID))) {
		return nil, fmt.Errorf("lease receipt file is invalid or belongs to another node")
	}
	journal.active = &saved
	return journal, nil
}

func validSHA256(value string) bool {
	if len(value) != 64 || strings.ToLower(value) != value {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}

func validSavedCommand(command *savedCommand, jobID string) bool {
	if command == nil || !validUUID(command.CommandID) ||
		len(command.ImageRepository) == 0 || len(command.ImageRepository) > 500 ||
		strings.ContainsAny(command.ImageRepository, "@\\ \t\r\n") ||
		!strings.HasPrefix(command.ImageDigest, "sha256:") ||
		!validSHA256(strings.TrimPrefix(command.ImageDigest, "sha256:")) ||
		command.MaxRuntimeSeconds == 0 || command.MaxRuntimeSeconds > 86400 ||
		len(command.VerifiedInputPrefix) > 1000 ||
		!strings.HasPrefix(command.VerifiedInputPrefix, "jobs/"+jobID+"/verified/") ||
		strings.Contains(command.VerifiedInputPrefix, "..") ||
		strings.Contains(command.VerifiedInputPrefix, "\\") ||
		!validSHA256(command.InputManifestSHA256) ||
		command.InputFileCount > 256 || command.InputTotalBytes > 32*1024*1024 ||
		command.StagedAt.IsZero() {
		return false
	}
	return true
}

func (journal *leaseJournal) stageCommand(command *agentv1.ExecutionCommand) error {
	if journal == nil || journal.active == nil || journal.active.ConfirmedAt == nil || command == nil {
		return fmt.Errorf("confirmed lease receipt required before command staging")
	}
	lease := journal.active
	if command.GetNodeId() != lease.NodeID || command.GetJobId() != lease.JobID ||
		command.GetAttemptId() != lease.AttemptID || command.GetLeaseId() != lease.LeaseID ||
		command.GetGpuUuid() != lease.GPUUUID || command.GetFencingToken() != lease.FencingToken ||
		command.GetExpiresAt() == nil || command.GetExpiresAt().CheckValid() != nil ||
		!command.GetExpiresAt().AsTime().Equal(lease.ExpiresAt) ||
		!lease.ExpiresAt.After(time.Now()) {
		return fmt.Errorf("command does not match current unexpired lease")
	}
	next := savedCommand{
		CommandID: command.GetCommandId(), ImageRepository: command.GetImageRepository(),
		ImageDigest: command.GetImageDigest(), MaxRuntimeSeconds: command.GetMaxRuntimeSeconds(),
		VerifiedInputPrefix: command.GetVerifiedInputPrefix(),
		InputManifestSHA256: command.GetInputManifestSha256(),
		InputFileCount:      command.GetInputFileCount(), InputTotalBytes: command.GetInputTotalBytes(),
		StagedAt: time.Now().UTC(),
	}
	if !validSavedCommand(&next, lease.JobID) {
		return fmt.Errorf("invalid command payload")
	}
	if lease.Command != nil {
		current := lease.Command
		if current.CommandID == next.CommandID &&
			current.ImageRepository == next.ImageRepository && current.ImageDigest == next.ImageDigest &&
			current.MaxRuntimeSeconds == next.MaxRuntimeSeconds &&
			current.VerifiedInputPrefix == next.VerifiedInputPrefix &&
			current.InputManifestSHA256 == next.InputManifestSHA256 &&
			current.InputFileCount == next.InputFileCount && current.InputTotalBytes == next.InputTotalBytes {
			return nil
		}
		return fmt.Errorf("conflicting command for active lease")
	}
	updated := *lease
	updated.Command = &next
	if err := journal.write(updated); err != nil {
		return err
	}
	journal.active = &updated
	return nil
}

func (journal *leaseJournal) confirmCommand(commandID string) error {
	if journal == nil || journal.active == nil || journal.active.Command == nil ||
		journal.active.Command.CommandID != commandID {
		return fmt.Errorf("command staging acknowledgement does not match local journal")
	}
	if journal.active.Command.ServerConfirmedAt != nil {
		return nil
	}
	updated := *journal.active
	command := *updated.Command
	now := time.Now().UTC()
	command.ServerConfirmedAt = &now
	updated.Command = &command
	if err := journal.write(updated); err != nil {
		return err
	}
	journal.active = &updated
	return nil
}

func (journal *leaseJournal) store(offer *agentv1.LeaseOffer) error {
	if journal == nil {
		return fmt.Errorf("lease journal is not configured")
	}
	if offer == nil || offer.GetExpiresAt() == nil || offer.GetExpiresAt().CheckValid() != nil {
		return fmt.Errorf("lease offer is invalid")
	}
	next := savedLease{
		NodeID: offer.GetNodeId(), JobID: offer.GetJobId(),
		AttemptID: offer.GetAttemptId(), LeaseID: offer.GetLeaseId(),
		GPUUUID: offer.GetGpuUuid(), FencingToken: offer.GetFencingToken(),
		ExpiresAt: offer.GetExpiresAt().AsTime().UTC(),
	}
	if next.NodeID != journal.nodeID {
		return fmt.Errorf("lease offer belongs to another node")
	}
	if journal.active != nil {
		current := journal.active
		if current.LeaseID == next.LeaseID && current.JobID == next.JobID &&
			current.AttemptID == next.AttemptID && current.GPUUUID == next.GPUUUID &&
			current.FencingToken == next.FencingToken && current.ExpiresAt.Equal(next.ExpiresAt) {
			return nil
		}
		if current.ExpiresAt.After(time.Now()) {
			return fmt.Errorf("another unexpired lease receipt is already stored")
		}
	}
	if err := journal.write(next); err != nil {
		return err
	}
	journal.active = &next
	return nil
}

func (journal *leaseJournal) confirm(leaseID string) error {
	if journal == nil || journal.active == nil || journal.active.LeaseID != leaseID {
		return fmt.Errorf("lease receipt acknowledgement does not match local journal")
	}
	if journal.active.ConfirmedAt != nil {
		return nil
	}
	updated := *journal.active
	now := time.Now().UTC()
	updated.ConfirmedAt = &now
	if err := journal.write(updated); err != nil {
		return err
	}
	journal.active = &updated
	return nil
}

func (journal *leaseJournal) write(saved savedLease) error {
	content, err := json.Marshal(saved)
	if err != nil {
		return fmt.Errorf("encode lease receipt: %w", err)
	}
	temporary, err := os.CreateTemp(journal.dir, ".lease-receipt-*.tmp")
	if err != nil {
		return fmt.Errorf("create temporary lease receipt: %w", err)
	}
	defer os.Remove(temporary.Name())
	if err := temporary.Chmod(0600); err != nil {
		temporary.Close()
		return fmt.Errorf("restrict lease receipt permissions: %w", err)
	}
	if _, err := temporary.Write(content); err != nil {
		temporary.Close()
		return fmt.Errorf("write lease receipt: %w", err)
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return fmt.Errorf("sync lease receipt: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close lease receipt: %w", err)
	}
	if err := os.Rename(temporary.Name(), filepath.Join(journal.dir, journalFile)); err != nil {
		return fmt.Errorf("commit lease receipt: %w", err)
	}
	if runtime.GOOS != "windows" {
		directory, err := os.Open(journal.dir)
		if err != nil {
			return fmt.Errorf("open lease state directory for sync: %w", err)
		}
		defer directory.Close()
		if err := directory.Sync(); err != nil {
			return fmt.Errorf("sync lease state directory: %w", err)
		}
	}
	return nil
}
