package config

import (
	"testing"
)

func TestLoadUsesFoundationDefaults(t *testing.T) {
	t.Setenv("HYPERLOOMS_AGENT_ID", "")
	t.Setenv("HYPERLOOMS_AGENT_HEALTH_ADDRESS", "127.0.0.1:19091")

	_, err := Load()
	if err == nil {
		t.Fatal("expected blank agent ID to be rejected")
	}
}

func TestLoadReadsEnvironment(t *testing.T) {
	t.Setenv("HYPERLOOMS_AGENT_ID", "node-test-1")
	t.Setenv("HYPERLOOMS_AGENT_HEALTH_ADDRESS", "127.0.0.1:19091")
	t.Setenv("HYPERLOOMS_LOG_LEVEL", "DEBUG")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() returned an error: %v", err)
	}
	if cfg.AgentID != "node-test-1" {
		t.Fatalf("unexpected agent ID: %s", cfg.AgentID)
	}
	if cfg.LogLevel != "DEBUG" {
		t.Fatalf("unexpected log level: %s", cfg.LogLevel)
	}
}

func TestLoadRequiresCompleteGrpcCredentials(t *testing.T) {
	t.Setenv("HYPERLOOMS_AGENT_GRPC_ADDRESS", "localhost:9090")
	t.Setenv("HYPERLOOMS_AGENT_NODE_ID", "")
	if _, err := Load(); err == nil {
		t.Fatal("expected missing gRPC credentials to fail")
	}
}

func TestLoadRejectsCredentialsWithoutAddress(t *testing.T) {
	t.Setenv("HYPERLOOMS_AGENT_GRPC_ADDRESS", "")
	t.Setenv("HYPERLOOMS_AGENT_CLIENT_KEY", "client.key")
	if _, err := Load(); err == nil {
		t.Fatal("expected incomplete gRPC configuration to fail")
	}
}

func TestLoadRequiresPrivateStateDirectoryForGrpc(t *testing.T) {
	t.Setenv("HYPERLOOMS_AGENT_GRPC_ADDRESS", "localhost:9090")
	t.Setenv("HYPERLOOMS_AGENT_NODE_ID", "cafe0000-0000-4000-8000-000000000001")
	t.Setenv("HYPERLOOMS_AGENT_SERVER_CA", "ca.pem")
	t.Setenv("HYPERLOOMS_AGENT_CLIENT_CERT", "client.pem")
	t.Setenv("HYPERLOOMS_AGENT_CLIENT_KEY", "client.key")
	for _, invalid := range []string{"", "relative/state"} {
		t.Setenv("HYPERLOOMS_AGENT_STATE_DIR", invalid)
		if _, err := Load(); err == nil {
			t.Fatalf("expected state directory %q to be rejected", invalid)
		}
	}
	t.Setenv("HYPERLOOMS_AGENT_STATE_DIR", t.TempDir())
	if _, err := Load(); err != nil {
		t.Fatalf("absolute state directory rejected: %v", err)
	}
}
