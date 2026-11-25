ALTER TABLE jobs
    ADD COLUMN request_version SMALLINT NOT NULL DEFAULT 0
        CHECK (request_version IN (0, 1)),
    ADD COLUMN input_manifest_sha256 CHAR(64)
        CHECK (input_manifest_sha256 ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT jobs_v1_manifest_required
        CHECK ((request_version = 1) = (input_manifest_sha256 IS NOT NULL));

-- Version 0 marks pre-existing internal records and test fixtures only.
-- All new service submissions use version 1 and compute request_sha256 server-side.
