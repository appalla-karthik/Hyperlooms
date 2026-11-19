package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/GodaniShubham/HYPER_LOOMS/agents/provider/internal/config"
	"github.com/GodaniShubham/HYPER_LOOMS/agents/provider/internal/health"
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

	server := health.NewServer(cfg.HealthAddress, logger)
	server.SetReady(true)

	logger.Info("provider agent foundation starting",
		"agent_id", cfg.AgentID,
		"version", version,
		"commit", commit,
		"health_address", cfg.HealthAddress,
		"phase", "foundation")

	if err := server.Run(ctx); err != nil {
		logger.Error("provider agent stopped unexpectedly", "error", err)
		os.Exit(1)
	}

	logger.Info("provider agent stopped cleanly", "agent_id", cfg.AgentID)
}

func newLogger(levelName string) *slog.Logger {
	var level slog.Level
	if err := level.UnmarshalText([]byte(levelName)); err != nil {
		level = slog.LevelInfo
	}
	return slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level}))
}
