-- A staging receipt proves the authenticated agent durably stored a command.
-- It does not prove that an image ran or that artifacts were produced.
CREATE TABLE lease_execution_command_staging (
    command_id UUID PRIMARY KEY REFERENCES lease_execution_commands (id),
    lease_id UUID NOT NULL UNIQUE REFERENCES gpu_leases (id),
    node_id UUID NOT NULL REFERENCES provider_nodes (id),
    fencing_token BIGINT NOT NULL CHECK (fencing_token > 0),
    staged_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
