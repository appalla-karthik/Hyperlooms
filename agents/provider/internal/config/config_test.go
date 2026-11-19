package config

import "testing"

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
