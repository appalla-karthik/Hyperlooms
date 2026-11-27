-- Prepared commands are internal durable snapshots, not delivery or execution.
CREATE TABLE lease_execution_commands (
    id UUID PRIMARY KEY,
    lease_id UUID NOT NULL UNIQUE REFERENCES gpu_leases (id),
    node_id UUID NOT NULL REFERENCES provider_nodes (id),
    fencing_token BIGINT NOT NULL CHECK (fencing_token > 0),
    image_repository VARCHAR(500) NOT NULL,
    image_digest VARCHAR(71) NOT NULL
        CHECK (image_digest ~ '^sha256:[0-9a-f]{64}$'),
    max_runtime_seconds INTEGER NOT NULL
        CHECK (max_runtime_seconds BETWEEN 1 AND 86400),
    verified_input_prefix VARCHAR(1000) NOT NULL,
    input_manifest_sha256 CHAR(64) NOT NULL
        CHECK (input_manifest_sha256 ~ '^[0-9a-f]{64}$'),
    input_file_count SMALLINT NOT NULL CHECK (input_file_count BETWEEN 0 AND 256),
    input_total_bytes BIGINT NOT NULL CHECK (input_total_bytes BETWEEN 0 AND 33554432),
    prepared_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- A new lease/attempt is required to change any prepared command bytes.
CREATE FUNCTION reject_lease_execution_command_update()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'prepared execution commands are immutable';
END;
$$;

CREATE TRIGGER lease_execution_commands_no_update
    BEFORE UPDATE ON lease_execution_commands
    FOR EACH ROW EXECUTE FUNCTION reject_lease_execution_command_update();
