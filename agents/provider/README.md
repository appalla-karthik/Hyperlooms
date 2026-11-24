# HYPER_LOOMS Provider Agent

The provider agent is the only Hyperlooms component intended to run directly on a contributor machine. It retains configuration, structured logging, graceful shutdown, version reporting, and local health endpoints.

With `HYPERLOOMS_AGENT_GRPC_ADDRESS` and all certificate/node settings provided, it discovers NVIDIA GPUs through `nvidia-smi` and opens an outbound mTLS gRPC session. It sends a capability hello and heartbeats. Readiness is false while disconnected. Without these settings, it runs the foundation health server only. This is a Phase 1 transport increment, **not** a safe public-provider release: certificate issuance, hardware verification, image execution, artifacts, and job commands are not implemented. Reported GPUs stay offline and unschedulable.

```powershell
go run ./cmd/hyperlooms-agent
go run ./cmd/hyperlooms-agent version
go test ./...
```

Environment variables are documented in the repository `.env.example`.

Do not use a self-signed or shared client certificate in a public network. An operator must first create a pending provider node, issue a trusted client certificate containing the URI SAN `urn:hyperlooms:provider:<node-uuid>`, and bind its exact certificate fingerprint to that node. The trusted local CLI and rotation/revocation sequence are documented in [provider identity operations](../../docs/security/provider-identity-operations.md); they are not exposed as a public API.
