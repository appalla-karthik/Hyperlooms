# Terminology

| Term | Meaning |
|---|---|
| Control plane | Central services that own identity, policy, job state, scheduling, accounting, and audit history. |
| Provider node | Independently owned machine that opts in to execute approved workloads. |
| Provider agent | HYPER_LOOMS software running on a provider node. |
| Consumer | User or service submitting a workload. |
| Workload recipe | Versioned, approved definition of an executable workload and its policy. |
| Job | Consumer request to execute a recipe with specific inputs and requirements. |
| Attempt | One execution try for a job. A job may have multiple attempts. |
| Lease | Time-bounded assignment authorizing one node to own an attempt. |
| Fencing token | Monotonically ordered or unique proof that prevents a stale attempt from finalizing a reassigned job. |
| Heartbeat | Periodic node status message; it is evidence of connectivity, not proof that work is correct. |
| Artifact | Immutable input, output, checkpoint, log bundle, or manifest stored outside the relational database. |
| Compute Circle | Membership and policy boundary for sharing compute. |
| Compute credit | Internal accounting unit earned and spent inside the product. |
| Compute Passport | Evidence-based node reliability and capability history. |
| Data plane | Movement of workload artifacts, logs, and execution traffic. |
