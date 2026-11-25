package singleinstance

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestAcquireExcludesSecondAgentAndReleases(t *testing.T) {
	dir := t.TempDir()
	first, err := Acquire(dir)
	if err != nil {
		t.Fatalf("first lock: %v", err)
	}
	if _, err := Acquire(dir); err == nil {
		first.Close()
		t.Fatal("second agent acquired same state directory")
	}
	if err := first.Close(); err != nil {
		t.Fatalf("release first lock: %v", err)
	}
	if err := first.Close(); err != nil {
		t.Fatalf("second close: %v", err)
	}
	second, err := Acquire(dir)
	if err != nil {
		t.Fatalf("lock after release: %v", err)
	}
	defer second.Close()
	if _, err := os.Stat(filepath.Join(dir, lockFileName)); err != nil {
		t.Fatalf("lock file should remain on disk: %v", err)
	}
}

func TestAcquireRejectsUnsafePaths(t *testing.T) {
	for _, path := range []string{"", "relative/path", filepath.VolumeName(t.TempDir()) + string(filepath.Separator)} {
		if _, err := Acquire(path); err == nil {
			t.Fatalf("accepted unsafe state path %q", path)
		}
	}
	if runtime.GOOS != "windows" {
		dir := filepath.Join(t.TempDir(), "shared")
		if err := os.Mkdir(dir, 0755); err != nil {
			t.Fatal(err)
		}
		if _, err := Acquire(dir); err == nil {
			t.Fatal("accepted non-private state directory")
		}
	}
}

func TestAcquireRejectsExistingDirectoryAsLockFile(t *testing.T) {
	dir := t.TempDir()
	if err := os.Mkdir(filepath.Join(dir, lockFileName), 0700); err != nil {
		t.Fatal(err)
	}
	if _, err := Acquire(dir); err == nil {
		t.Fatal("accepted directory as lock file")
	}
}
