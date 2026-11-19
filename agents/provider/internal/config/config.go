package config

import (
	"fmt"
	"net"
	"os"
	"strings"
)

const (
	defaultAgentID       = "local-agent"
	defaultHealthAddress = "127.0.0.1:9091"
	defaultLogLevel      = "INFO"
)

type Config struct {
	AgentID       string
	HealthAddress string
	LogLevel      string
}

func Load() (Config, error) {
	cfg := Config{
		AgentID:       valueOrDefault("HYPERLOOMS_AGENT_ID", defaultAgentID),
		HealthAddress: valueOrDefault("HYPERLOOMS_AGENT_HEALTH_ADDRESS", defaultHealthAddress),
		LogLevel:      valueOrDefault("HYPERLOOMS_LOG_LEVEL", defaultLogLevel),
	}

	if strings.TrimSpace(cfg.AgentID) == "" {
		return Config{}, fmt.Errorf("HYPERLOOMS_AGENT_ID must not be blank")
	}
	if _, _, err := net.SplitHostPort(cfg.HealthAddress); err != nil {
		return Config{}, fmt.Errorf("invalid HYPERLOOMS_AGENT_HEALTH_ADDRESS: %w", err)
	}

	return cfg, nil
}

func valueOrDefault(name, fallback string) string {
	value, exists := os.LookupEnv(name)
	if !exists {
		return fallback
	}
	return strings.TrimSpace(value)
}
