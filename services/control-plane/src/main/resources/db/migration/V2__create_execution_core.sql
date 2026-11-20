CREATE TABLE provider_nodes (
    id UUID PRIMARY KEY,
    owner_subject VARCHAR(200) NOT NULL,
    enrollment_state VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (enrollment_state IN ('PENDING', 'VERIFIED', 'REVOKED')),
    availability_state VARCHAR(20) NOT NULL DEFAULT 'OFFLINE'
        CHECK (availability_state IN ('OFFLINE', 'AVAILABLE', 'BUSY', 'DRAINING')),
    agent_version VARCHAR(100),
    protocol_version INTEGER CHECK (protocol_version > 0),
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE provider_gpus (
    node_id UUID NOT NULL REFERENCES provider_nodes (id),
    gpu_uuid VARCHAR(100) NOT NULL,
    model VARCHAR(200) NOT NULL,
    vram_bytes BIGINT NOT NULL CHECK (vram_bytes > 0),
    driver_version VARCHAR(100) NOT NULL,
    cuda_version VARCHAR(100) NOT NULL,
    verification_state VARCHAR(20) NOT NULL DEFAULT 'REPORTED'
        CHECK (verification_state IN ('REPORTED', 'VERIFIED', 'REJECTED')),
    verified_at TIMESTAMPTZ,
    reported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (node_id, gpu_uuid),
    CHECK ((verification_state = 'VERIFIED') = (verified_at IS NOT NULL))
);

CREATE TABLE workload_recipes (
    id UUID PRIMARY KEY,
    name VARCHAR(150) NOT NULL UNIQUE,
    image_repository VARCHAR(500) NOT NULL,
    image_digest VARCHAR(71) NOT NULL
        CHECK (image_digest ~ '^sha256:[0-9a-f]{64}$'),
    max_runtime_seconds INTEGER NOT NULL CHECK (max_runtime_seconds BETWEEN 1 AND 86400),
    min_vram_bytes BIGINT NOT NULL CHECK (min_vram_bytes > 0),
    state VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (state IN ('DRAFT', 'APPROVED', 'DISABLED')),
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (state != 'APPROVED' OR approved_at IS NOT NULL),
    CHECK (state != 'DRAFT' OR approved_at IS NULL)
);

CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    requester_subject VARCHAR(200) NOT NULL,
    recipe_id UUID NOT NULL REFERENCES workload_recipes (id),
    state VARCHAR(30) NOT NULL DEFAULT 'CREATED'
        CHECK (state IN (
            'CREATED', 'UPLOADING', 'QUEUED', 'LEASED', 'STARTING', 'RUNNING',
            'UPLOADING_RESULTS', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELED', 'EXPIRED'
        )),
    idempotency_key VARCHAR(200) NOT NULL,
    request_sha256 CHAR(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    input_artifact_prefix VARCHAR(1000),
    result_artifact_prefix VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (requester_subject, idempotency_key)
);

CREATE INDEX jobs_queued_order_idx ON jobs (created_at, id) WHERE state = 'QUEUED';

CREATE TABLE job_attempts (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs (id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    node_id UUID NOT NULL REFERENCES provider_nodes (id),
    state VARCHAR(30) NOT NULL DEFAULT 'LEASED'
        CHECK (state IN (
            'LEASED', 'STARTING', 'RUNNING', 'UPLOADING_RESULTS',
            'SUCCEEDED', 'FAILED', 'LEASE_EXPIRED', 'NODE_LOST', 'CANCELED'
        )),
    failure_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    UNIQUE (job_id, attempt_number),
    UNIQUE (id, node_id)
);

CREATE UNIQUE INDEX job_attempts_one_active_per_job_idx
    ON job_attempts (job_id)
    WHERE state IN ('LEASED', 'STARTING', 'RUNNING', 'UPLOADING_RESULTS');

CREATE SEQUENCE lease_fencing_token_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE TABLE gpu_leases (
    id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL UNIQUE,
    node_id UUID NOT NULL,
    gpu_uuid VARCHAR(100) NOT NULL,
    fencing_token BIGINT NOT NULL UNIQUE DEFAULT nextval('lease_fencing_token_seq'),
    state VARCHAR(20) NOT NULL DEFAULT 'ISSUED'
        CHECK (state IN ('ISSUED', 'ACKNOWLEDGED', 'EXPIRED', 'REVOKED', 'COMPLETED')),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CHECK (expires_at > issued_at),
    FOREIGN KEY (attempt_id, node_id) REFERENCES job_attempts (id, node_id),
    FOREIGN KEY (node_id, gpu_uuid) REFERENCES provider_gpus (node_id, gpu_uuid)
);

CREATE UNIQUE INDEX gpu_leases_one_active_per_gpu_idx
    ON gpu_leases (node_id, gpu_uuid)
    WHERE state IN ('ISSUED', 'ACKNOWLEDGED');

CREATE INDEX gpu_leases_expiry_idx ON gpu_leases (expires_at)
    WHERE state IN ('ISSUED', 'ACKNOWLEDGED');

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_key VARCHAR(200) NOT NULL UNIQUE,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    delivery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0)
);

CREATE INDEX outbox_events_ready_idx ON outbox_events (available_at, created_at, id)
    WHERE delivered_at IS NULL;
