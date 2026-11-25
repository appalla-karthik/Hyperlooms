package main

import (
	"context"
	"errors"
	"io"
	"reflect"
	"strings"
	"testing"

	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/config"
)

type fakeLock struct{ closed bool }

func (lock *fakeLock) Close() error {
	lock.closed = true
	return nil
}

func TestPrepareAgentLocksBeforeRecovery(t *testing.T) {
	var order []string
	lock := &fakeLock{}
	cfg := config.Config{GrpcAddress: "example.invalid:443", StateDir: "/private/state",
		NodeID: "cafe0000-0000-4000-8000-000000000001", RuntimeRecoveryEnabled: true}
	got, removed, err := prepareAgent(context.Background(), cfg, startupServices{
		acquire: func(path string) (io.Closer, error) {
			order = append(order, "lock")
			if path != cfg.StateDir {
				t.Fatalf("wrong state directory: %s", path)
			}
			return lock, nil
		},
		recover: func(_ context.Context, node string) (int, error) {
			order = append(order, "recover")
			if node != cfg.NodeID {
				t.Fatalf("wrong node: %s", node)
			}
			return 2, nil
		},
	})
	if err != nil || removed != 2 || got != lock || !reflect.DeepEqual(order, []string{"lock", "recover"}) || lock.closed {
		t.Fatalf("startup order=%v removed=%d err=%v closed=%v", order, removed, err, lock.closed)
	}
	got.Close()
}

func TestPrepareAgentFailureReleasesLock(t *testing.T) {
	lock := &fakeLock{}
	cfg := config.Config{GrpcAddress: "example.invalid:443", StateDir: "/private/state",
		NodeID: "cafe0000-0000-4000-8000-000000000001", RuntimeRecoveryEnabled: true}
	got, _, err := prepareAgent(context.Background(), cfg, startupServices{
		acquire: func(string) (io.Closer, error) { return lock, nil },
		recover: func(context.Context, string) (int, error) { return 0, errors.New("daemon unavailable") },
	})
	if got != nil || err == nil || !strings.Contains(err.Error(), "daemon unavailable") || !lock.closed {
		t.Fatalf("failure leaked lock: lock=%v err=%v closed=%v", got, err, lock.closed)
	}
}

func TestPrepareAgentSkipsRecoveryWhenDisabledAndAllWorkWhenLocal(t *testing.T) {
	called := 0
	services := startupServices{
		acquire: func(string) (io.Closer, error) { called++; return &fakeLock{}, nil },
		recover: func(context.Context, string) (int, error) { t.Fatal("unexpected recovery"); return 0, nil },
	}
	local, _, err := prepareAgent(context.Background(), config.Config{}, services)
	if err != nil || local != nil || called != 0 {
		t.Fatalf("local mode acquired lock: lock=%v calls=%d err=%v", local, called, err)
	}
	lock, _, err := prepareAgent(context.Background(), config.Config{GrpcAddress: "example.invalid:443"}, services)
	if err != nil || lock == nil || called != 1 {
		t.Fatalf("enrolled mode lock=%v calls=%d err=%v", lock, called, err)
	}
	lock.Close()
}
