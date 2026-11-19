# Security Policy

HYPER_LOOMS will execute supported workloads on independently owned machines. Both the workload submitter and provider must be treated as potentially hostile. Security is a product requirement, not a deployment afterthought.

## Current status

Phase 0 provides a development foundation only. It is not approved for untrusted workloads, public exposure, sensitive datasets, or production credentials.

## Reporting

Do not publish an exploitable vulnerability in a public issue. Until a private reporting address is configured, contact the repository owner directly and include reproduction steps, affected versions, impact, and suggested mitigations if known.

## Never assume

- ordinary containers are a complete hostile-code boundary;
- encryption at rest protects data while a provider processes it;
- a provider-reported GPU identity or usage measurement is automatically trustworthy;
- retries happen exactly once;
- a node that stopped sending heartbeats has stopped executing;
- private datasets are safe on unknown consumer hardware.

See [the threat model](docs/security/threat-model.md) for the planned controls and residual risks.
