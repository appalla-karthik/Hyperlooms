CREATE TABLE provider_enrollment_invites (
    id UUID PRIMARY KEY,
    owner_subject VARCHAR(200) NOT NULL,
    token_sha256 CHAR(64) NOT NULL UNIQUE
        CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    node_id UUID UNIQUE REFERENCES provider_nodes (id),
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (expires_at > created_at),
    CHECK ((consumed_at IS NULL) = (node_id IS NULL)),
    CHECK (revoked_at IS NULL OR consumed_at IS NULL)
);

CREATE INDEX provider_enrollment_invites_expiry_idx
    ON provider_enrollment_invites (expires_at)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
