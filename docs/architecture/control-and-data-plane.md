# Control Plane and Data Plane

## Control plane traffic

- user authentication and authorization;
- job and node metadata;
- scheduling and lease commands;
- heartbeat and status messages;
- audit, credit, and policy changes.

Browser control traffic uses REST/JSON described by OpenAPI. Agent control traffic will use typed Protobuf messages over bidirectional gRPC with mutual TLS.

## Data plane traffic

- datasets and model inputs;
- workload images;
- checkpoints and results;
- bounded logs and execution metrics.

Large artifacts do not transit through ordinary control-plane request bodies. The control plane authorizes scoped access while agents and consumers transfer directly to S3-compatible storage or an approved registry.

## Live browser updates

Server-Sent Events are the initial preference for one-way status and log updates to browsers. WebSockets remain available if a later interaction requires continuous bidirectional browser messaging.
