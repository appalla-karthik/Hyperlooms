# ADR-005: Linux NVIDIA Providers First

- Status: Accepted
- Date: 2026-11-20

## Decision

The first provider runtime supports explicitly opted-in Linux hosts with compatible NVIDIA GPUs and assigns one entire GPU to one job.

## Rationale

CUDA, NVIDIA Container Toolkit, and Linux isolation provide the clearest initial execution path. Windows services, other GPU vendors, and time-sliced multi-tenancy each multiply compatibility and security work. Exclusive assignment avoids presenting consumer GPU time slicing as memory or fault isolation.

## Consequences

The initial provider pool is smaller. Capability discovery must record driver, CUDA, GPU architecture, VRAM, and isolation compatibility. Additional platforms require separate testing matrices and ADRs.
