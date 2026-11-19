# Threat Model

## Assets

- provider host integrity and personal data;
- consumer datasets, models, prompts, and results;
- control-plane credentials and signing keys;
- credit balances and accounting history;
- provider identity, reputation, and telemetry;
- artifact integrity and availability.

## Adversaries

### Malicious consumer

May attempt sandbox escape, host-file access, persistence, local-network scanning, cryptomining, resource exhaustion, prohibited network access, vulnerable-driver exploitation, or malicious artifact delivery.

### Malicious provider

May inspect plaintext inputs, retain data, modify workloads, fabricate outputs or usage, interrupt jobs, replay completion, impersonate hardware, or collude for credits.

### External attacker

May steal credentials, compromise the supply chain, exploit public APIs, tamper with artifacts, perform denial of service, or enroll fake nodes.

## Planned controls

- short-lived identities and mutual TLS for agents;
- immutable image digests and signature verification;
- non-root sandbox, read-only root filesystem, dropped capabilities, seccomp/AppArmor, quotas, and denied egress by default;
- no host mounts or privileged workloads;
- short-lived artifact authorization and digest manifests;
- leases, fencing, replay protection, idempotency keys, and append-only audit events;
- provider enrollment challenges and selected redundant verification;
- prompt patching, dependency review, secret scanning, and signed releases;
- immediate provider stop/drain controls and platform revocation.

## Residual risks

Ordinary consumer GPUs generally cannot protect plaintext workload data from the machine owner. gVisor reduces host-kernel exposure but does not eliminate NVIDIA driver risk or provide universal hardware isolation. Reputation cannot make hostile code safe. Sensitive workloads require trusted circles or future compatible confidential-computing hardware.
