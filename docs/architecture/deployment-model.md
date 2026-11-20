# Deployment Model

## Local development

- dashboard, control plane, and provider agent run as developer processes;
- PostgreSQL and a SeaweedFS S3-compatible development instance run with Docker Compose;
- generated development certificates are excluded from Git;
- a CPU/mock execution path will support CI where no GPU exists.

## Initial hosted control plane

The first hosted environment should use managed PostgreSQL, managed S3-compatible storage, a small number of replicated control-plane instances, centralized identity, encrypted ingress, backups, and managed telemetry. Kubernetes is optional and must be justified by operational need.

## Provider environment

The first provider target is a dedicated or explicitly opted-in Linux NVIDIA machine. The agent makes an outbound authenticated connection, enforces owner policy, and runs one exclusive GPU job at a time. It is not a Kubernetes worker joined to the control-plane cluster.
