//go:build linux

package singleinstance

import (
	"fmt"
	"os"

	"golang.org/x/sys/unix"
)

func openAndLock(path string) (*os.File, func(*os.File) error, error) {
	fd, err := unix.Open(path, unix.O_CREAT|unix.O_RDWR|unix.O_CLOEXEC|unix.O_NOFOLLOW, 0600)
	if err != nil {
		return nil, nil, err
	}
	file := os.NewFile(uintptr(fd), path)
	var stat unix.Stat_t
	if err := unix.Fstat(fd, &stat); err != nil {
		file.Close()
		return nil, nil, err
	}
	if stat.Mode&unix.S_IFMT != unix.S_IFREG || stat.Nlink != 1 || stat.Mode&0077 != 0 ||
		stat.Uid != uint32(os.Geteuid()) {
		file.Close()
		return nil, nil, fmt.Errorf("agent lock file ownership or type is unsafe")
	}
	if err := unix.Flock(fd, unix.LOCK_EX|unix.LOCK_NB); err != nil {
		file.Close()
		return nil, nil, err
	}
	return file, func(file *os.File) error {
		return unix.Flock(int(file.Fd()), unix.LOCK_UN)
	}, nil
}
