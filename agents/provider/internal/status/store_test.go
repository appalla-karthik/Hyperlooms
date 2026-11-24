package status

import (
	"errors"
	"testing"
)

func TestConnectionAndGPUState(t *testing.T) {
	store := New("provider-1", "test", "node-1", "control.example:9090")
	if got := store.Get(); got.Connection != "connecting" || got.Ready {
		t.Fatalf("unexpected initial state: %+v", got)
	}
	store.ReportGPUs([]GPU{{UUID: "GPU-1", Model: "Example", VRAMBytes: 8589934592}})
	store.Connected()
	store.Heartbeat()
	got := store.Get()
	if got.Connection != "connected" || !got.Ready || got.LastConnected == nil || got.LastHeartbeat == nil || len(got.GPUs) != 1 {
		t.Fatalf("unexpected connected state: %+v", got)
	}
	got.GPUs[0].Model = "mutated"
	if store.Get().GPUs[0].Model != "Example" {
		t.Fatal("snapshot exposed mutable store state")
	}
	store.HardwareFailed(errors.New("nvidia-smi unavailable"))
	if hardware := store.Get(); hardware.HardwareError == "" || len(hardware.GPUs) != 0 {
		t.Fatalf("unexpected hardware failure state: %+v", hardware)
	}
	store.ReportGPUs([]GPU{{UUID: "GPU-1", Model: "Example"}})
	if store.Get().HardwareError != "" {
		t.Fatal("hardware recovery retained stale error")
	}
	if len(store.Get().Events) == 0 {
		t.Fatal("connection and hardware transitions did not create local events")
	}
	store.Disconnected(errors.New("connection unavailable"))
	got = store.Get()
	if got.Connection != "disconnected" || got.Ready || got.LastError != "connection unavailable" {
		t.Fatalf("unexpected disconnected state: %+v", got)
	}
	store.Connecting()
	store.Connected()
	if store.Get().LastError != "" {
		t.Fatal("recovered connection retained stale error")
	}
}
