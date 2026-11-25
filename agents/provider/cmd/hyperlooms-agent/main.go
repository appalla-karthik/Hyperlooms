package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/config"
	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/containerruntime"
	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/health"
	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/session"
	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/singleinstance"
	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/status"
)

var (
	version = "development"
	commit  = "unknown"
)

func main() {
	os.Exit(run())
}

func run() int {
	if len(os.Args) == 2 && os.Args[1] == "version" {
		fmt.Printf("hyperlooms-agent %s (%s)\n", version, commit)
		return 0
	}

	cfg, err := config.Load()
	if err != nil {
		fmt.Fprintf(os.Stderr, "configuration error: %v\n", err)
		return 2
	}

	logger := newLogger(cfg.LogLevel)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	lock, recovered, err := prepareAgent(ctx, cfg, startupServices{
		acquire: func(dir string) (io.Closer, error) { return singleinstance.Acquire(dir) },
		recover: containerruntime.RecoverOrphans,
	})
	if err != nil {
		logger.Error("provider agent startup blocked", "error", err)
		return 1
	}
	if lock != nil {
		defer func() {
			if err := lock.Close(); err != nil {
				logger.Error("release provider agent lock", "error", err)
			}
		}()
	}
	if cfg.RuntimeRecoveryEnabled {
		logger.Info("managed container startup recovery complete", "removed", recovered)
	}

	state := status.New(cfg.AgentID, version, cfg.NodeID, cfg.GrpcAddress)
	server := health.NewServer(cfg.HealthAddress, logger, state)
	server.SetReady(cfg.GrpcAddress == "")
	var workers sync.WaitGroup
	workers.Add(1)
	go func() {
		defer workers.Done()
		probeHardware(ctx, state)
	}()
	if cfg.GrpcAddress != "" {
		workers.Add(1)
		go func() {
			defer workers.Done()
			session.Run(ctx, cfg, version, logger, server.SetReady, state)
		}()
	}

	logger.Info("provider agent foundation starting",
		"agent_id", cfg.AgentID,
		"version", version,
		"commit", commit,
		"health_address", cfg.HealthAddress,
		"phase", "phase-one-agent-session")

	serveErr := server.Run(ctx)
	stop()
	workers.Wait() // Keep the state lock until both background workers have exited.
	if serveErr != nil {
		logger.Error("provider agent stopped unexpectedly", "error", serveErr)
		return 1
	}

	logger.Info("provider agent stopped cleanly", "agent_id", cfg.AgentID)
	return 0
}

type startupServices struct {
	acquire func(string) (io.Closer, error)
	recover func(context.Context, string) (int, error)
}

func prepareAgent(ctx context.Context, cfg config.Config, services startupServices) (io.Closer, int, error) {
	if cfg.GrpcAddress == "" {
		return nil, 0, nil
	}
	lock, err := services.acquire(cfg.StateDir)
	if err != nil {
		return nil, 0, fmt.Errorf("single-instance state lock: %w", err)
	}
	if !cfg.RuntimeRecoveryEnabled {
		return lock, 0, nil
	}
	count, err := services.recover(ctx, cfg.NodeID)
	if err != nil {
		closeErr := lock.Close()
		if closeErr != nil {
			return nil, 0, errors.Join(fmt.Errorf("container recovery: %w", err),
				fmt.Errorf("release lock: %w", closeErr))
		}
		return nil, 0, fmt.Errorf("container recovery: %w", err)
	}
	return lock, count, nil
}

func probeHardware(ctx context.Context, state *status.Store) {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		gpus, err := session.DiscoverGPUs(ctx)
		if err != nil {
			state.HardwareFailed(err)
		} else {
			state.ReportGPUs(session.StatusGPUs(gpus))
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func newLogger(levelName string) *slog.Logger {
	var level slog.Level
	if err := level.UnmarshalText([]byte(levelName)); err != nil {
		level = slog.LevelInfo
	}
	return slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level}))
}
