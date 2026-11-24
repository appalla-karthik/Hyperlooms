package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/config"
	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/health"
	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/session"
	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/status"
)

var (
	version = "development"
	commit  = "unknown"
)

func main() {
	if len(os.Args) == 2 && os.Args[1] == "version" {
		fmt.Printf("hyperlooms-agent %s (%s)\n", version, commit)
		return
	}

	cfg, err := config.Load()
	if err != nil {
		fmt.Fprintf(os.Stderr, "configuration error: %v\n", err)
		os.Exit(2)
	}

	logger := newLogger(cfg.LogLevel)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	state := status.New(cfg.AgentID, version, cfg.NodeID, cfg.GrpcAddress)
	server := health.NewServer(cfg.HealthAddress, logger, state)
	server.SetReady(cfg.GrpcAddress == "")
	go probeHardware(ctx, state)
	if cfg.GrpcAddress != "" {
		go session.Run(ctx, cfg, version, logger, server.SetReady, state)
	}

	logger.Info("provider agent foundation starting",
		"agent_id", cfg.AgentID,
		"version", version,
		"commit", commit,
		"health_address", cfg.HealthAddress,
		"phase", "phase-one-agent-session")

	if err := server.Run(ctx); err != nil {
		logger.Error("provider agent stopped unexpectedly", "error", err)
		os.Exit(1)
	}

	logger.Info("provider agent stopped cleanly", "agent_id", cfg.AgentID)
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
