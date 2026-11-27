package config

import (
	"fmt"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

const (
	defaultAgentID       = "local-agent"
	defaultHealthAddress = "127.0.0.1:9091"
	defaultLogLevel      = "INFO"
)

var canonicalNodeID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

type Config struct {
	AgentID                string
	HealthAddress          string
	LogLevel               string
	GrpcAddress            string
	NodeID                 string
	ServerCA               string
	ClientCert             string
	ClientKey              string
	StateDir               string
	RuntimeRecoveryEnabled bool
	InputReadEnabled       bool
}

func Load() (Config, error) {
	recoverySetting := valueOrDefault("HYPERLOOMS_AGENT_RUNTIME_RECOVERY_ENABLED", "false")
	if recoverySetting != "true" && recoverySetting != "false" {
		return Config{}, fmt.Errorf("HYPERLOOMS_AGENT_RUNTIME_RECOVERY_ENABLED must be true or false")
	}
	inputReadSetting := valueOrDefault("HYPERLOOMS_AGENT_INPUT_READ_ENABLED", "false")
	if inputReadSetting != "true" && inputReadSetting != "false" {
		return Config{}, fmt.Errorf("HYPERLOOMS_AGENT_INPUT_READ_ENABLED must be true or false")
	}
	cfg := Config{
		AgentID:                valueOrDefault("HYPERLOOMS_AGENT_ID", defaultAgentID),
		HealthAddress:          valueOrDefault("HYPERLOOMS_AGENT_HEALTH_ADDRESS", defaultHealthAddress),
		LogLevel:               valueOrDefault("HYPERLOOMS_LOG_LEVEL", defaultLogLevel),
		GrpcAddress:            valueOrDefault("HYPERLOOMS_AGENT_GRPC_ADDRESS", ""),
		NodeID:                 valueOrDefault("HYPERLOOMS_AGENT_NODE_ID", ""),
		ServerCA:               valueOrDefault("HYPERLOOMS_AGENT_SERVER_CA", ""),
		ClientCert:             valueOrDefault("HYPERLOOMS_AGENT_CLIENT_CERT", ""),
		ClientKey:              valueOrDefault("HYPERLOOMS_AGENT_CLIENT_KEY", ""),
		StateDir:               valueOrDefault("HYPERLOOMS_AGENT_STATE_DIR", ""),
		RuntimeRecoveryEnabled: recoverySetting == "true",
		InputReadEnabled:       inputReadSetting == "true",
	}

	if strings.TrimSpace(cfg.AgentID) == "" {
		return Config{}, fmt.Errorf("HYPERLOOMS_AGENT_ID must not be blank")
	}
	if _, _, err := net.SplitHostPort(cfg.HealthAddress); err != nil {
		return Config{}, fmt.Errorf("invalid HYPERLOOMS_AGENT_HEALTH_ADDRESS: %w", err)
	}
	if cfg.GrpcAddress != "" {
		host, _, err := net.SplitHostPort(cfg.GrpcAddress)
		if err != nil {
			return Config{}, fmt.Errorf("invalid HYPERLOOMS_AGENT_GRPC_ADDRESS: %w", err)
		}
		if host == "" {
			return Config{}, fmt.Errorf("HYPERLOOMS_AGENT_GRPC_ADDRESS requires a host")
		}
		if !canonicalNodeID.MatchString(cfg.NodeID) || cfg.ServerCA == "" || cfg.ClientCert == "" || cfg.ClientKey == "" {
			return Config{}, fmt.Errorf("agent gRPC requires node ID, server CA, client certificate and key")
		}
		if cfg.StateDir == "" || !filepath.IsAbs(cfg.StateDir) ||
			filepath.Dir(filepath.Clean(cfg.StateDir)) == filepath.Clean(cfg.StateDir) {
			return Config{}, fmt.Errorf("agent gRPC requires an absolute, non-root HYPERLOOMS_AGENT_STATE_DIR")
		}
	} else if cfg.NodeID != "" || cfg.ServerCA != "" || cfg.ClientCert != "" || cfg.ClientKey != "" {
		return Config{}, fmt.Errorf("agent gRPC address is required when credentials are configured")
	}
	if cfg.RuntimeRecoveryEnabled && cfg.GrpcAddress == "" {
		return Config{}, fmt.Errorf("runtime recovery requires enrolled agent gRPC configuration")
	}
	if cfg.InputReadEnabled && cfg.GrpcAddress == "" {
		return Config{}, fmt.Errorf("input read requires enrolled agent gRPC configuration")
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
