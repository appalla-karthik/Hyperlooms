//go:build !linux && !windows

package singleinstance

import (
	"fmt"
	"os"
)

func openAndLock(_ string) (*os.File, func(*os.File) error, error) {
	return nil, nil, fmt.Errorf("single-instance lock unsupported on this operating system")
}
