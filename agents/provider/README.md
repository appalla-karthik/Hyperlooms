# HYPER_LOOMS Provider Agent

The provider agent is the only Hyperlooms component intended to run directly on a contributor machine. It retains configuration, structured logging, graceful shutdown, version reporting, and local health endpoints. Its embedded provider console is available at `http://127.0.0.1:9091/` by default. It shows live connection state, heartbeat times, NVIDIA GPU discovery, diagnostics, and a bounded local activity history without reading terminal logs. `/api/status` is a read-only JSON view. The console and JSON endpoint reject non-loopback peers and non-loopback Host headers even if health checks bind to a wider interface.

The console checks local NVIDIA hardware independently of enrollment, then refreshes that inventory every minute. With `HYPERLOOMS_AGENT_GRPC_ADDRESS` and all certificate/node settings provided, the agent opens an outbound mTLS gRPC session, sends a capability hello, and continues heartbeats. Connection readiness is false while disconnected. Without those settings, it runs local-only. This is a Phase 1 transport increment, **not** a safe public-provider release: self-service enrollment, production certificate issuance, hardware verification, image execution, artifacts, and job commands are not implemented. Reported GPUs stay offline and unschedulable. A service installer and first-run setup wizard are also still pending; the embedded console currently provides observation, not credential upload or configuration mutation.

```powershell
go run ./cmd/hyperlooms-agent
go run ./cmd/hyperlooms-agent version
go test ./...
```

Environment variables are documented in the repository `.env.example`.

For unattended Linux hosts, a hardened [systemd unit and setup notes](packaging/systemd/README.md) are provided. The unit has not yet been qualified on a real NVIDIA host; it does not replace a signed installer or first-run setup flow.

Do not use a self-signed or shared client certificate in a public network. An operator must first create a pending provider node, issue a trusted client certificate containing the URI SAN `urn:hyperlooms:provider:<node-uuid>`, and bind its exact certificate fingerprint to that node. The trusted local CLI and rotation/revocation sequence are documented in [provider identity operations](../../docs/security/provider-identity-operations.md); they are not exposed as a public API.
