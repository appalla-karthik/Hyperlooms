# Requester API identity boundary

The requester API is opt-in (`HYPERLOOMS_REQUESTER_API_ENABLED=false` by default). Enabling it requires an HTTPS OIDC issuer, an HTTPS JWKS endpoint, and a dedicated API audience. The Spring Security resource server validates the JWT signature, expiry, issuer, and audience using the issuer's rotating public keys. No password database or self-issued bearer token is added to Hyperlooms. An optional [loopback-only Keycloak development issuer](../../infrastructure/identity/README.md) now exists; its explicit insecure-local flag must never be enabled in a deployment.

The API accepts `Authorization: Bearer <access-token>` only. `POST /api/v1/jobs`, input uploads, `POST /api/v1/jobs/{jobId}/queue`, and `POST /api/v1/jobs/{jobId}/cancel` require the `jobs:write` scope; `GET /api/v1/jobs/{jobId}` requires `jobs:read`. The verified issuer and subject are hashed into a stable, bounded requester ID. The client cannot supply or override that ID in a request body or header. Reads and cancellation are owner-scoped and return `404` for both nonexistent and other users' jobs. Responses do not include private object keys or provider identities. A queued job is not yet executed.

Configure these together in the control-plane deployment:

```text
HYPERLOOMS_REQUESTER_API_ENABLED=true
HYPERLOOMS_REQUESTER_ISSUER_URI=https://your-issuer.example/
HYPERLOOMS_REQUESTER_JWK_SET_URI=https://your-issuer.example/.well-known/jwks.json
HYPERLOOMS_REQUESTER_AUDIENCE=hyperlooms-api
HYPERLOOMS_ARTIFACT_BUCKET=your-private-bucket
HYPERLOOMS_S3_REGION=us-east-1
```

The issuer URL must exactly match the JWT `iss` claim. The audience must be intended specifically for this API. Both URLs must use HTTPS; missing or invalid configuration prevents startup with the API enabled. S3 uses the default AWS credential chain unless both optional access-key variables are set; prefer workload identity in production. A custom S3 endpoint must use HTTPS. Local `localhost` HTTP requires the explicit insecure-local flag and must not be used for a public deployment. Use a real identity provider that issues the two scopes; registration, login, consent, and token acquisition are not provided by this repository yet. Do not commit tokens or include them in logs.

Submission requires an `Idempotency-Key` header (1–200 characters from letters, digits, `.`, `_`, `:`, `-`) and a JSON body with `recipeId` and lowercase `inputManifestSha256`. The first accepted submission returns `201 Created` and a `Location` header; an identical retry for the same requester/key returns `200` and the same job ID. A changed request with the same key returns `409`. An unavailable recipe returns `404`. The declared manifest digest is **not** uploaded or verified by this endpoint.

After submission, the requester can call `PUT /api/v1/jobs/{jobId}/inputs/manifest` with canonical manifest JSON (64 KiB maximum), then `PUT /api/v1/jobs/{jobId}/inputs/files?path=<relative-path>` with an octet-stream body (8 MiB per file). Both endpoints check job ownership and stream no more than one byte past the limit before rejecting oversized bodies. Staging is create-once; identical retries return `200`, first writes return `201`. The manifest caps the job at 32 MiB total. Finally, `POST /api/v1/jobs/{jobId}/queue` verifies every staged byte against the manifest, copies it to a server-only verified prefix, and atomically moves the job to `QUEUED`. Missing/invalid inputs cannot queue. This is server-mediated upload, not a presigned direct-to-S3 grant; it deliberately limits the first slice to small workloads.

`POST /api/v1/jobs/{jobId}/cancel` atomically cancels only an owned `CREATED` or `QUEUED` job. A duplicate cancellation returns the same `CANCELED` state without another event. The API returns `409` for leased, running, or other noncancelable states: those require a fenced agent stop protocol before resources can be treated as free. Cancellation does not delete staged objects; orphan cleanup is still pending.

This initial boundary is not public launch readiness. The local Keycloak profile is a development issuer, not production identity operations or user registration. Synthetic-principal integration and claim-validation tests exist; the local issuer smoke also exercises token claims. Production ingress rate/body limits, abuse controls, bucket policy, orphan cleanup, and browser CORS/CSRF design are still pending. Keep the feature disabled outside a controlled environment until those gates are addressed.
