package health

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/status"
)

func TestReadinessReflectsServerState(t *testing.T) {
	server := NewServer("127.0.0.1:0", slog.New(slog.NewTextHandler(io.Discard, nil)))

	notReadyRequest := httptest.NewRequest(http.MethodGet, "/health/ready", nil)
	notReadyResponse := httptest.NewRecorder()
	server.readiness(notReadyResponse, notReadyRequest)
	if notReadyResponse.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected 503 before readiness, got %d", notReadyResponse.Code)
	}

	server.SetReady(true)
	readyRequest := httptest.NewRequest(http.MethodGet, "/health/ready", nil)
	readyResponse := httptest.NewRecorder()
	server.readiness(readyResponse, readyRequest)
	if readyResponse.Code != http.StatusOK {
		t.Fatalf("expected 200 after readiness, got %d", readyResponse.Code)
	}
}

func TestConsoleIsLoopbackOnlyAndReportsLiveState(t *testing.T) {
	state := status.New("provider-a", "test-version", "node-a", "control.example:9090")
	server := NewServer("127.0.0.1:0", slog.New(slog.NewTextHandler(io.Discard, nil)), state)
	state.ReportGPUs([]status.GPU{{UUID: "GPU-a", Model: "Test GPU", VRAMBytes: 8589934592}})
	state.Connected()

	request := httptest.NewRequest(http.MethodGet, "http://localhost:9091/api/status", nil)
	request.RemoteAddr = "127.0.0.1:50000"
	response := httptest.NewRecorder()
	server.handler().ServeHTTP(response, request)
	if response.Code != http.StatusOK || response.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("unexpected local status response: %d", response.Code)
	}
	var got status.Snapshot
	if err := json.Unmarshal(response.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got.AgentID != "provider-a" || got.Connection != "connected" || len(got.GPUs) != 1 {
		t.Fatalf("unexpected status response: %+v", got)
	}

	request.RemoteAddr = "192.0.2.1:50000"
	response = httptest.NewRecorder()
	server.handler().ServeHTTP(response, request)
	if response.Code != http.StatusForbidden {
		t.Fatalf("expected non-local peer to be denied, got %d", response.Code)
	}

	request.RemoteAddr = "127.0.0.1:50000"
	request.Host = "attacker.example:9091"
	response = httptest.NewRecorder()
	server.handler().ServeHTTP(response, request)
	if response.Code != http.StatusForbidden {
		t.Fatalf("expected DNS rebinding host to be denied, got %d", response.Code)
	}
}

func TestConsoleAssetsAreEmbedded(t *testing.T) {
	server := NewServer("127.0.0.1:0", slog.New(slog.NewTextHandler(io.Discard, nil)))
	for _, path := range []string{"/", "/console.css", "/console.js"} {
		request := httptest.NewRequest(http.MethodGet, "http://localhost:9091"+path, nil)
		request.RemoteAddr = "127.0.0.1:50000"
		response := httptest.NewRecorder()
		server.handler().ServeHTTP(response, request)
		if response.Code != http.StatusOK || response.Body.Len() == 0 {
			t.Fatalf("asset %s was unavailable: %d", path, response.Code)
		}
	}
}
