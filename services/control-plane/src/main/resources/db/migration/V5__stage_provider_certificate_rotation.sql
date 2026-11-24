DROP INDEX provider_certificates_one_current_per_node_idx;

-- One active certificate may coexist with one staged replacement. The old
-- certificate is revoked only when the replacement authenticates successfully.
CREATE UNIQUE INDEX provider_certificates_one_active_per_node_idx
    ON provider_certificates (node_id)
    WHERE revoked_at IS NULL AND activated_at IS NOT NULL;

CREATE UNIQUE INDEX provider_certificates_one_staged_per_node_idx
    ON provider_certificates (node_id)
    WHERE revoked_at IS NULL AND activated_at IS NULL;
