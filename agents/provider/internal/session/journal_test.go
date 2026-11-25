package session

import (
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"
)

func TestLeaseJournalSurvivesRestartAndDuplicateOffer(t *testing.T) {
	dir := t.TempDir()
	journal, err := openLeaseJournal(dir, testNodeID)
	if err != nil {
		t.Fatal(err)
	}
	offer := testOffer()
	if err := journal.store(offer); err != nil {
		t.Fatal(err)
	}
	reopened, err := openLeaseJournal(dir, testNodeID)
	if err != nil || reopened.active == nil || reopened.active.LeaseID != testLeaseID {
		t.Fatalf("receipt not recovered after restart: %v", err)
	}
	if err := reopened.store(offer); err != nil {
		t.Fatalf("duplicate offer should be idempotent: %v", err)
	}
	if err := reopened.confirm(testLeaseID); err != nil {
		t.Fatal(err)
	}
	if err := reopened.confirm(testLeaseID); err != nil {
		t.Fatalf("duplicate confirmation should be idempotent: %v", err)
	}
	reopened, err = openLeaseJournal(dir, testNodeID)
	if err != nil || reopened.active.ConfirmedAt == nil {
		t.Fatalf("confirmation not recovered after restart: %v", err)
	}
}

func TestLeaseJournalRejectsConflictingLiveOffer(t *testing.T) {
	journal, err := openLeaseJournal(t.TempDir(), testNodeID)
	if err != nil {
		t.Fatal(err)
	}
	if err := journal.store(testOffer()); err != nil {
		t.Fatal(err)
	}
	conflict := testOffer()
	conflict.LeaseId = "cafe0000-0000-4000-8000-000000000005"
	if err := journal.store(conflict); err == nil {
		t.Fatal("accepted conflicting live receipt")
	}
	reopened, err := openLeaseJournal(journal.dir, testNodeID)
	if err != nil || reopened.active.LeaseID != testLeaseID {
		t.Fatalf("conflict changed durable receipt: %v", err)
	}
}

func TestLeaseJournalCanReplaceExpiredReceipt(t *testing.T) {
	journal, err := openLeaseJournal(t.TempDir(), testNodeID)
	if err != nil {
		t.Fatal(err)
	}
	first := testOffer()
	first.ExpiresAt = timestamppb.New(time.Now().Add(-time.Minute))
	if err := journal.store(first); err != nil {
		t.Fatal(err)
	}
	second := testOffer()
	second.LeaseId = "cafe0000-0000-4000-8000-000000000005"
	if err := journal.store(second); err != nil {
		t.Fatalf("expired receipt prevented replacement: %v", err)
	}
	reopened, err := openLeaseJournal(journal.dir, testNodeID)
	if err != nil || reopened.active.LeaseID != second.LeaseId {
		t.Fatalf("replacement not durable: %v", err)
	}
}

func TestLeaseJournalFailsClosedOnUnsafeState(t *testing.T) {
	for _, content := range []string{"not-json", `{"nodeId":"other"}`} {
		t.Run(content, func(t *testing.T) {
			dir := t.TempDir()
			if err := os.WriteFile(filepath.Join(dir, journalFile), []byte(content), 0600); err != nil {
				t.Fatal(err)
			}
			if _, err := openLeaseJournal(dir, testNodeID); err == nil {
				t.Fatal("accepted corrupt or foreign journal")
			}
		})
	}
	if _, err := openLeaseJournal("relative/path", testNodeID); err == nil {
		t.Fatal("accepted relative state directory")
	}
	if runtime.GOOS != "windows" {
		dir := t.TempDir()
		if err := os.Chmod(dir, 0755); err != nil {
			t.Fatal(err)
		}
		if _, err := openLeaseJournal(dir, testNodeID); err == nil {
			t.Fatal("accepted world-readable state directory")
		}
	}
}

func TestLeaseJournalWriteFailurePreventsReceipt(t *testing.T) {
	journal, err := openLeaseJournal(t.TempDir(), testNodeID)
	if err != nil {
		t.Fatal(err)
	}
	journal.dir = filepath.Join(journal.dir, "missing")
	exchange := &fakeLeaseExchange{}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	if err := receiveOffer(exchange, testOffer(), testNodeID, testGPUs(),
		map[string]string{}, journal, logger); err == nil || !strings.Contains(err.Error(), "create temporary") {
		t.Fatalf("expected durable write failure, got %v", err)
	}
	if len(exchange.sent) != 0 {
		t.Fatal("sent transport receipt despite journal write failure")
	}
	if journal.active != nil {
		t.Fatal("failed write changed in-memory receipt")
	}
}
