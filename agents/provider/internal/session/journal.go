package session

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"time"

	agentv1 "github.com/appalla-karthik/Hyperlooms/agents/provider/gen/hyperlooms/agent/v1"
)

const journalFile = "lease-receipt.json"

// savedLease is a receipt record, not permission or instructions to run a job.
type savedLease struct {
	NodeID       string     `json:"nodeId"`
	JobID        string     `json:"jobId"`
	AttemptID    string     `json:"attemptId"`
	LeaseID      string     `json:"leaseId"`
	GPUUUID      string     `json:"gpuUuid"`
	FencingToken uint64     `json:"fencingToken"`
	ExpiresAt    time.Time  `json:"expiresAt"`
	ConfirmedAt  *time.Time `json:"confirmedAt,omitempty"`
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
		saved.GPUUUID == "" || saved.FencingToken == 0 || saved.ExpiresAt.IsZero() {
		return nil, fmt.Errorf("lease receipt file is invalid or belongs to another node")
	}
	journal.active = &saved
	return journal, nil
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
