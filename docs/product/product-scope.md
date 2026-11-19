# Product Scope

## First usable release

- one centrally coordinated control plane;
- Linux NVIDIA provider nodes;
- provider opt-in, availability schedules, and immediate drain/stop controls;
- approved, signed AI workload images;
- exclusive whole-GPU job leases;
- artifact upload and result download;
- durable job state, retries, and auditable attempts;
- basic Compute Circles, reciprocal credit ledger, and node history;
- observable operation through logs, metrics, traces, and alerts.

## Explicit non-goals for the first release

- arbitrary third-party containers or unrestricted shell commands;
- anonymous sensitive-data processing;
- Windows, macOS, AMD, or Intel provider support;
- fractional sharing of consumer GPUs between untrusted workloads;
- treating geographically distributed GPUs as one large shared-memory GPU;
- synchronous wide-area tensor or data parallel training;
- game streaming;
- fiat payouts, cryptocurrency, or token issuance;
- fully peer-to-peer scheduling or settlement;
- Kubernetes on contributor machines.

## Why AI batch work comes first

Batch inference and bounded fine-tuning tolerate coordination and transfer delay, can use explicit artifacts, and can be retried or checkpointed. Remote gaming has fundamentally different latency, input, video, licensing, and session-management requirements. Wide-area distributed training adds specialized synchronization and fault-tolerance requirements. These may become separate product modes after reliable remote execution exists.
