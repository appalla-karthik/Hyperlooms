ALTER TABLE gpu_leases ADD COLUMN execution_started_at TIMESTAMPTZ;
ALTER TABLE jobs ADD COLUMN stop_requested_at TIMESTAMPTZ;
ALTER TABLE jobs ADD COLUMN result_sha256 CHAR(64)
    CHECK (result_sha256 IS NULL OR result_sha256 ~ '^[0-9a-f]{64}$');
ALTER TABLE jobs ADD COLUMN result_size_bytes BIGINT
    CHECK (result_size_bytes IS NULL OR result_size_bytes BETWEEN 1 AND 1048576);
ALTER TABLE jobs ADD CONSTRAINT jobs_result_evidence_pair
    CHECK ((result_sha256 IS NULL) = (result_size_bytes IS NULL));
