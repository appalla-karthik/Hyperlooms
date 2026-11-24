CREATE TABLE provider_certificates (
    id UUID PRIMARY KEY,
    node_id UUID NOT NULL REFERENCES provider_nodes (id),
    certificate_sha256 CHAR(64) NOT NULL UNIQUE
        CHECK (certificate_sha256 ~ '^[0-9a-f]{64}$'),
    not_after TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (not_after > created_at),
    CHECK (activated_at IS NULL OR activated_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE UNIQUE INDEX provider_certificates_one_current_per_node_idx
    ON provider_certificates (node_id) WHERE revoked_at IS NULL;
