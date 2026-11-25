package singleinstance

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sync"
)

const lockFileName = "agent.lock"

// Lock is a process-lifetime advisory lock. The file is never unlinked: unlinking
// a locked inode would let another process acquire a new lock at the same path.
type Lock struct {
	file   *os.File
	unlock func(*os.File) error
	once   sync.Once
	err    error
}

// Acquire serializes enrolled agents sharing one private state directory.
func Acquire(dir string) (*Lock, error) {
	if dir == "" || !filepath.IsAbs(dir) || filepath.Dir(filepath.Clean(dir)) == filepath.Clean(dir) {
		return nil, fmt.Errorf("agent state directory must be absolute and non-root")
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, fmt.Errorf("create agent state directory: %w", err)
	}
	info, err := os.Lstat(dir)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 ||
		(runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0) {
		return nil, fmt.Errorf("agent state directory must be a private real directory")
	}
	path := filepath.Join(dir, lockFileName)
	if info, err := os.Lstat(path); err == nil {
		if !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 ||
			(runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0) {
			return nil, fmt.Errorf("agent lock file is unsafe")
		}
	} else if !os.IsNotExist(err) {
		return nil, fmt.Errorf("inspect agent lock file: %w", err)
	}
	file, unlock, err := openAndLock(path)
	if err != nil {
		return nil, fmt.Errorf("acquire agent lock: %w", err)
	}
	return &Lock{file: file, unlock: unlock}, nil
}

func (lock *Lock) Close() error {
	if lock == nil {
		return nil
	}
	lock.once.Do(func() {
		lock.err = lock.unlock(lock.file)
		if err := lock.file.Close(); err != nil && lock.err == nil {
			lock.err = err
		}
	})
	return lock.err
}
