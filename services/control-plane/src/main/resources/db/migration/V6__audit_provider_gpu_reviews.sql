-- Operator review evidence is immutable history. A later GPU report may revoke
-- the current VERIFIED state without erasing the review that produced it.
CREATE TABLE provider_gpu_reviews (
    id UUID PRIMARY KEY,
    node_id UUID NOT NULL,
    gpu_uuid VARCHAR(100) NOT NULL,
    report_sha256 CHAR(64) NOT NULL CHECK (report_sha256 ~ '^[0-9a-f]{64}$'),
    evidence_sha256 CHAR(64) NOT NULL CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    reviewer_subject VARCHAR(200) NOT NULL CHECK (length(trim(reviewer_subject)) > 0),
    decision VARCHAR(20) NOT NULL CHECK (decision = 'APPROVED'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (node_id, gpu_uuid) REFERENCES provider_gpus (node_id, gpu_uuid)
);

CREATE INDEX provider_gpu_reviews_gpu_order_idx
    ON provider_gpu_reviews (node_id, gpu_uuid, created_at DESC);
