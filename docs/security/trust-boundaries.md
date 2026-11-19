# Trust Boundaries

## Browser to control plane

The browser is untrusted input. The control plane authenticates, authorizes, validates, rate-limits, and records security-relevant changes. Client-side validation is usability only.

## Control plane to provider agent

Networks and nodes are untrusted. Both endpoints authenticate with short-lived identities, commands are scoped, protocol versions are explicit, and replay-sensitive operations include unique identifiers.

## Agent to workload sandbox

The workload is hostile by default. It receives the minimum devices, files, credentials, network access, time, CPU, memory, processes, disk, and GPU capabilities required by its recipe.

## Workload to GPU driver

GPU access crosses into a large host driver attack surface. Driver compatibility and patch status are admission requirements. Sandboxing provides defense in depth but does not remove kernel-driver risk.

## Control plane to storage

Application services receive role-scoped credentials. Consumers and agents receive short-lived object-scoped authorization. Object integrity is verified independently with digests.
