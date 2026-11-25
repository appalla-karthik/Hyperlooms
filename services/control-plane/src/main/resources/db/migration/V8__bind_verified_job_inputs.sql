ALTER TABLE jobs
    ADD COLUMN input_verified_at TIMESTAMPTZ,
    ADD COLUMN verified_input_manifest_sha256 CHAR(64)
        CHECK (verified_input_manifest_sha256 ~ '^[0-9a-f]{64}$'),
    ADD COLUMN input_file_count SMALLINT
        CHECK (input_file_count BETWEEN 0 AND 256),
    ADD COLUMN input_total_bytes BIGINT
        CHECK (input_total_bytes BETWEEN 0 AND 33554432),
    ADD CONSTRAINT jobs_v1_verified_input_complete CHECK (
        request_version = 0 OR (
            input_verified_at IS NULL
            AND verified_input_manifest_sha256 IS NULL
            AND input_artifact_prefix IS NULL
            AND input_file_count IS NULL
            AND input_total_bytes IS NULL
        ) OR (
            input_verified_at IS NOT NULL
            AND verified_input_manifest_sha256 IS NOT NULL
            AND verified_input_manifest_sha256 = input_manifest_sha256
            AND input_artifact_prefix IS NOT NULL
            AND input_artifact_prefix LIKE 'jobs/' || id::text || '/verified/%'
            AND input_file_count IS NOT NULL
            AND input_total_bytes IS NOT NULL
        )
    ),
    ADD CONSTRAINT jobs_v1_queued_input_required CHECK (
        request_version = 0 OR state NOT IN (
            'QUEUED', 'LEASED', 'STARTING', 'RUNNING',
            'UPLOADING_RESULTS', 'RETRY_WAIT', 'SUCCEEDED'
        ) OR input_verified_at IS NOT NULL
    );

CREATE UNIQUE INDEX jobs_verified_input_prefix_idx
    ON jobs (input_artifact_prefix)
    WHERE request_version = 1 AND input_artifact_prefix IS NOT NULL;
