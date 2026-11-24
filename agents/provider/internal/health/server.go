package health

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"sync/atomic"
	"time"

	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/status"
)

const shutdownTimeout = 5 * time.Second

type Server struct {
	address string
	logger  *slog.Logger
	ready   atomic.Bool
	state   *status.Store
}

type response struct {
	Status string `json:"status"`
	Phase  string `json:"phase"`
}

func NewServer(address string, logger *slog.Logger, shared ...*status.Store) *Server {
	state := status.New("local-agent", "development", "", "")
	if len(shared) != 0 && shared[0] != nil {
		state = shared[0]
	}
	return &Server{address: address, logger: logger, state: state}
}

func (s *Server) SetReady(ready bool) {
	s.ready.Store(ready)
	s.state.SetReady(ready)
}

func (s *Server) Run(ctx context.Context) error {
	httpServer := &http.Server{
		Addr:              s.address,
		Handler:           s.handler(),
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
