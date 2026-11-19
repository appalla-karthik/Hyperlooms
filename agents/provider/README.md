# HYPER_LOOMS Provider Agent

The provider agent is the only HYPER_LOOMS component intended to run directly on a contributor machine. Phase 0 contains configuration, structured logging, graceful shutdown, version reporting, and local health endpoints only.

It does **not** currently discover GPUs, connect to the control plane, download artifacts, or execute workloads.

```powershell
go run ./cmd/hyperlooms-agent
go run ./cmd/hyperlooms-agent version
go test ./...
```

Environment variables are documented in the repository `.env.example`.
