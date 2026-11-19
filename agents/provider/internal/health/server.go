package health

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"sync/atomic"
	"time"
)

const shutdownTimeout = 5 * time.Second

type Server struct {
	address string
	logger  *slog.Logger
	ready   atomic.Bool
}

type response struct {
	Status string `json:"status"`
	Phase  string `json:"phase"`
}

func NewServer(address string, logger *slog.Logger) *Server {
	return &Server{address: address, logger: logger}
}

func (s *Server) SetReady(ready bool) {
	s.ready.Store(ready)
}

func (s *Server) Run(ctx context.Context) error {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", s.live)
	mux.HandleFunc("GET /health/ready", s.readiness)

	httpServer := &http.Server{
		Addr:              s.address,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       30 * time.Second,
	}

	result := make(chan error, 1)
	go func() {
		s.logger.Info("agent health server listening", "address", s.address)
		result <- httpServer.ListenAndServe()
	}()

	select {
	case <-ctx.Done():
		shutdownContext, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
		defer cancel()
		if err := httpServer.Shutdown(shutdownContext); err != nil {
			return err
		}
		return nil
	case err := <-result:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	}
}

func (s *Server) live(writer http.ResponseWriter, _ *http.Request) {
	writeJSON(writer, http.StatusOK, response{Status: "UP", Phase: "FOUNDATION"})
}

func (s *Server) readiness(writer http.ResponseWriter, _ *http.Request) {
	if !s.ready.Load() {
		writeJSON(writer, http.StatusServiceUnavailable, response{Status: "NOT_READY", Phase: "FOUNDATION"})
		return
	}
	writeJSON(writer, http.StatusOK, response{Status: "READY", Phase: "FOUNDATION"})
}

func writeJSON(writer http.ResponseWriter, status int, payload response) {
	writer.Header().Set("Content-Type", "application/json")
	writer.WriteHeader(status)
	_ = json.NewEncoder(writer).Encode(payload)
}
