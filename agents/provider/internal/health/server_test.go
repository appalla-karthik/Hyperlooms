package health

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
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
