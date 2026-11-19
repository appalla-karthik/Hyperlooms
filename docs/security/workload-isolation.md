# Workload Isolation Strategy

## Initial policy

- approved images only, pinned by digest and verified by signature;
- one job per whole GPU;
- non-root identity and no privilege escalation;
- read-only image filesystem with per-attempt scratch space;
- no host paths, Docker socket, device discovery, or arbitrary devices;
- CPU, memory, process, disk, duration, and log-size limits;
- outbound network denied unless the recipe explicitly requires an allowlisted destination;
- short-lived artifact credentials scoped to one attempt;
- teardown and cleanup verified before the next lease.

## Why containers are insufficient alone

Standard containers share the host kernel and GPU driver. Namespace, capability, seccomp, and MAC controls reduce exposure but cannot guarantee safety against all kernel or driver vulnerabilities. Compatible gVisor GPU support may add a user-space kernel boundary; VM passthrough or confidential computing may serve stricter future tiers.

## GPU sharing

Consumer GPU time slicing does not guarantee memory or fault isolation. Phase 1 therefore leases a full GPU exclusively. MIG or supported virtual-GPU technology may be introduced only for compatible hardware with explicit isolation testing.
