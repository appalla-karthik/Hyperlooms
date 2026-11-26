# Local OIDC identity provider

The optional `identity-provider` Compose profile runs a real Keycloak 26.8.2 issuer on **127.0.0.1:8180 only**. It imports a local-development realm with a public OIDC client, PKCE S256, an explicit `hyperlooms-api` access-token audience, and `jobs:read` / `jobs:write` scopes. Self-registration is off; there are no committed user accounts or client secrets. The direct password grant exists only to automate a loopback smoke test. Do not expose this `start-dev` instance or its example admin password to a network.

Start the identity service:

```powershell
docker compose -f infrastructure/compose/compose.yaml --profile identity up -d identity-provider
```

For a local smoke, set the admin password in your shell (matching your private `.env`, never in a committed file), then run:

```powershell
$env:HYPERLOOMS_IDENTITY_ADMIN_PASSWORD = 'your-local-admin-password'
py infrastructure/identity/smoke.py
```

The smoke creates a short-lived local user with a random password, obtains a real Keycloak access token, checks issuer/audience/subject/scopes, and deletes the user in a `finally` block. It does not print credentials or token bytes. An optional `--api-base http://localhost:8080` also expects Spring to return `401` without a token and `404` for an authenticated read of a nonexistent job. Keycloak's imported realm is not re-imported over an existing realm; changing the JSON does not update an existing local volume automatically.

To run the control plane against this local issuer, set these values in the shell alongside the existing PostgreSQL/S3 settings:

```text
HYPERLOOMS_REQUESTER_API_ENABLED=true
HYPERLOOMS_REQUESTER_ISSUER_URI=http://localhost:8180/realms/hyperlooms-dev
HYPERLOOMS_REQUESTER_JWK_SET_URI=http://localhost:8180/realms/hyperlooms-dev/protocol/openid-connect/certs
HYPERLOOMS_REQUESTER_AUDIENCE=hyperlooms-api
HYPERLOOMS_REQUESTER_ALLOW_INSECURE_LOCAL_ISSUER=true
```

The insecure-issuer flag only accepts literal `localhost` or `127.0.0.1` HTTP endpoints. It is false by default and must never be used outside local development. For production, configure an operator-managed OIDC deployment with HTTPS, a durable database, secure admin bootstrap, backups, MFA/SSO policy, client registration, and controlled user lifecycle. The control plane already accepts an external HTTPS issuer/JWKS and continues to enforce JWT signature, issuer, audience, expiry, and scopes; this Compose profile is not a production IdP deployment or a dashboard login UI.

Keycloak's [container/import guidance](https://www.keycloak.org/server/containers) and [audience mapper reference](https://www.keycloak.org/admin-api/protocol-mappers) inform this local profile. Keycloak explicitly says `start-dev` is unsuitable for production.
