package health

import (
	"embed"
	"encoding/json"
	"net"
	"net/http"
	"strings"
)

//go:embed console.html console.css console.js
var consoleFiles embed.FS

func (s *Server) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", s.live)
	mux.HandleFunc("GET /health/ready", s.readiness)
	mux.HandleFunc("GET /{$}", s.localOnly(s.console))
	mux.HandleFunc("GET /console.css", s.localOnly(s.consoleCSS))
	mux.HandleFunc("GET /console.js", s.localOnly(s.consoleJS))
	mux.HandleFunc("GET /api/status", s.localOnly(s.status))
	return mux
}

// The console includes provider identifiers and diagnostic errors. Never serve it
// to a non-loopback peer, even if the health listener is configured on 0.0.0.0.
func (s *Server) localOnly(next http.HandlerFunc) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		peer, _, peerErr := net.SplitHostPort(request.RemoteAddr)
		host, _, hostErr := net.SplitHostPort(request.Host)
		if hostErr != nil {
			host = request.Host
		}
		peerIP := net.ParseIP(peer)
		hostIP := net.ParseIP(strings.Trim(host, "[]"))
		if peerErr != nil || peerIP == nil || !peerIP.IsLoopback() ||
			!(strings.EqualFold(host, "localhost") || hostIP != nil && hostIP.IsLoopback()) {
			http.Error(writer, "local console only", http.StatusForbidden)
			return
		}
		writer.Header().Set("Cache-Control", "no-store")
		writer.Header().Set("X-Content-Type-Options", "nosniff")
		writer.Header().Set("X-Frame-Options", "DENY")
		next(writer, request)
	}
}

func (s *Server) console(writer http.ResponseWriter, _ *http.Request) {
	writer.Header().Set("Content-Type", "text/html; charset=utf-8")
	writer.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'")
	serveConsoleFile(writer, "console.html")
}

func (s *Server) consoleCSS(writer http.ResponseWriter, _ *http.Request) {
	writer.Header().Set("Content-Type", "text/css; charset=utf-8")
	serveConsoleFile(writer, "console.css")
}

func (s *Server) consoleJS(writer http.ResponseWriter, _ *http.Request) {
	writer.Header().Set("Content-Type", "text/javascript; charset=utf-8")
	serveConsoleFile(writer, "console.js")
}

func (s *Server) status(writer http.ResponseWriter, _ *http.Request) {
	writer.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(writer).Encode(s.state.Get())
}

func serveConsoleFile(writer http.ResponseWriter, name string) {
	content, err := consoleFiles.ReadFile(name)
	if err != nil {
		http.Error(writer, "console asset unavailable", http.StatusInternalServerError)
		return
	}
	_, _ = writer.Write(content)
}
