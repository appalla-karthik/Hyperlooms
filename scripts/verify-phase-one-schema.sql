BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM flyway_schema_history WHERE version = '2' AND success = true
    ) THEN
        RAISE EXCEPTION 'Flyway V2 execution schema was not applied';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM flyway_schema_history WHERE version = '3' AND success = true
    ) THEN
        RAISE EXCEPTION 'Flyway V3 provider enrollment schema was not applied';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM flyway_schema_history WHERE version = '4' AND success = true
    ) THEN
        RAISE EXCEPTION 'Flyway V4 provider certificate schema was not applied';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM flyway_schema_history WHERE version = '5' AND success = true
    ) THEN
        RAISE EXCEPTION 'Flyway V5 staged certificate rotation schema was not applied';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM flyway_schema_history WHERE version = '6' AND success = true
    ) THEN
        RAISE EXCEPTION 'Flyway V6 GPU review audit schema was not applied';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM flyway_schema_history WHERE version = '7' AND success = true
    ) THEN
        RAISE EXCEPTION 'Flyway V7 versioned job request schema was not applied';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM flyway_schema_history WHERE version = '8' AND success = true
    ) THEN
        RAISE EXCEPTION 'Flyway V8 verified input binding schema was not applied';
    END IF;
END;
$$;

INSERT INTO provider_nodes (id, owner_subject, enrollment_state)
VALUES ('00000000-0000-4000-8000-000000000101', 'phase-one-test', 'VERIFIED');

INSERT INTO provider_certificates
    (id, node_id, certificate_sha256, not_after, activated_at)
VALUES
    ('00000000-0000-4000-8000-000000000111',
     '00000000-0000-4000-8000-000000000101', repeat('e', 64),
     CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP),
    ('00000000-0000-4000-8000-000000000112',
     '00000000-0000-4000-8000-000000000101', repeat('f', 64),
     CURRENT_TIMESTAMP + INTERVAL '1 day', NULL);

DO $$
BEGIN
    BEGIN
        INSERT INTO provider_certificates
            (id, node_id, certificate_sha256, not_after, activated_at)
        VALUES ('00000000-0000-4000-8000-000000000113',
                '00000000-0000-4000-8000-000000000101', repeat('1', 64),
                CURRENT_TIMESTAMP + INTERVAL '1 day', NULL);
        RAISE EXCEPTION 'second staged certificate was accepted';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
    BEGIN
        INSERT INTO provider_certificates
            (id, node_id, certificate_sha256, not_after, activated_at)
        VALUES ('00000000-0000-4000-8000-000000000114',
                '00000000-0000-4000-8000-000000000101', repeat('2', 64),
                CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP);
        RAISE EXCEPTION 'second active certificate was accepted';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END;
$$;

INSERT INTO provider_gpus (
    node_id, gpu_uuid, model, vram_bytes, driver_version, cuda_version,
    verification_state, verified_at
)
VALUES (
    '00000000-0000-4000-8000-000000000101', 'GPU-phase-one-test', 'Test GPU',
    8589934592, 'test-driver', 'test-cuda', 'VERIFIED', CURRENT_TIMESTAMP
);

INSERT INTO provider_gpu_reviews
    (id, node_id, gpu_uuid, report_sha256, evidence_sha256, reviewer_subject, decision)
VALUES
    ('00000000-0000-4000-8000-000000000121',
     '00000000-0000-4000-8000-000000000101', 'GPU-phase-one-test',
     repeat('a', 64), repeat('b', 64), 'phase-one-operator', 'APPROVED');

DO $$
BEGIN
    BEGIN
        INSERT INTO provider_gpu_reviews
            (id, node_id, gpu_uuid, report_sha256, evidence_sha256, reviewer_subject, decision)
        VALUES
            ('00000000-0000-4000-8000-000000000122',
             '00000000-0000-4000-8000-000000000101', 'GPU-phase-one-test',
             'invalid', repeat('b', 64), 'phase-one-operator', 'APPROVED');
        RAISE EXCEPTION 'invalid GPU report digest was accepted';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END;
$$;

INSERT INTO workload_recipes (
    id, name, image_repository, image_digest, max_runtime_seconds,
    min_vram_bytes, state, approved_at
)
VALUES (
    '00000000-0000-4000-8000-000000000201', 'phase-one-test-recipe',
    'registry.example/phase-one-test', 'sha256:' || repeat('a', 64),
    300, 1073741824, 'APPROVED', CURRENT_TIMESTAMP
);

INSERT INTO jobs (id, requester_subject, recipe_id, state, idempotency_key, request_sha256)
VALUES
    ('00000000-0000-4000-8000-000000000301', 'phase-one-test',
     '00000000-0000-4000-8000-000000000201', 'LEASED', 'first-request', repeat('b', 64)),
    ('00000000-0000-4000-8000-000000000302', 'phase-one-test',
     '00000000-0000-4000-8000-000000000201', 'LEASED', 'second-request', repeat('c', 64));

DO $$
BEGIN
    BEGIN
        INSERT INTO jobs
            (id, requester_subject, recipe_id, idempotency_key, request_sha256,
             request_version, input_manifest_sha256)
        VALUES
            ('00000000-0000-4000-8000-000000000304', 'phase-one-test',
             '00000000-0000-4000-8000-000000000201', 'bad-v1', repeat('d', 64), 1, NULL);
        RAISE EXCEPTION 'v1 job without input manifest digest was accepted';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
    BEGIN
        INSERT INTO jobs
            (id, requester_subject, recipe_id, state, idempotency_key,
             request_sha256, request_version, input_manifest_sha256)
        VALUES
            ('00000000-0000-4000-8000-000000000305', 'phase-one-test',
             '00000000-0000-4000-8000-000000000201', 'QUEUED', 'unverified-v1',
             repeat('d', 64), 1, repeat('e', 64));
        RAISE EXCEPTION 'v1 job queued without verified input was accepted';
    EXCEPTION WHEN check_violation THEN
        NULL;
    END;
END;
$$;

DO $$
BEGIN
    BEGIN
        INSERT INTO jobs (id, requester_subject, recipe_id, idempotency_key, request_sha256)
        VALUES ('00000000-0000-4000-8000-000000000303', 'phase-one-test',
                '00000000-0000-4000-8000-000000000201', 'first-request', repeat('d', 64));
        RAISE EXCEPTION 'duplicate idempotency key was accepted';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END;
$$;

INSERT INTO job_attempts (id, job_id, attempt_number, node_id)
VALUES
    ('00000000-0000-4000-8000-000000000401',
     '00000000-0000-4000-8000-000000000301', 1,
     '00000000-0000-4000-8000-000000000101'),
    ('00000000-0000-4000-8000-000000000402',
     '00000000-0000-4000-8000-000000000302', 1,
     '00000000-0000-4000-8000-000000000101');

DO $$
BEGIN
    BEGIN
        INSERT INTO job_attempts (id, job_id, attempt_number, node_id)
        VALUES ('00000000-0000-4000-8000-000000000403',
                '00000000-0000-4000-8000-000000000301', 2,
                '00000000-0000-4000-8000-000000000101');
        RAISE EXCEPTION 'second active attempt for one job was accepted';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END;
$$;

INSERT INTO gpu_leases (id, attempt_id, node_id, gpu_uuid, expires_at)
VALUES ('00000000-0000-4000-8000-000000000501',
        '00000000-0000-4000-8000-000000000401',
        '00000000-0000-4000-8000-000000000101', 'GPU-phase-one-test',
        CURRENT_TIMESTAMP + INTERVAL '5 minutes');

DO $$
BEGIN
    BEGIN
        INSERT INTO gpu_leases (id, attempt_id, node_id, gpu_uuid, expires_at)
        VALUES ('00000000-0000-4000-8000-000000000502',
                '00000000-0000-4000-8000-000000000402',
                '00000000-0000-4000-8000-000000000101', 'GPU-phase-one-test',
                CURRENT_TIMESTAMP + INTERVAL '5 minutes');
        RAISE EXCEPTION 'second active lease on one GPU was accepted';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END;
$$;

UPDATE gpu_leases SET state = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
WHERE id = '00000000-0000-4000-8000-000000000501';

INSERT INTO gpu_leases (id, attempt_id, node_id, gpu_uuid, expires_at)
VALUES ('00000000-0000-4000-8000-000000000502',
        '00000000-0000-4000-8000-000000000402',
        '00000000-0000-4000-8000-000000000101', 'GPU-phase-one-test',
        CURRENT_TIMESTAMP + INTERVAL '5 minutes');

DO $$
DECLARE
    first_token BIGINT;
    second_token BIGINT;
BEGIN
    SELECT fencing_token INTO first_token FROM gpu_leases
    WHERE id = '00000000-0000-4000-8000-000000000501';
    SELECT fencing_token INTO second_token FROM gpu_leases
    WHERE id = '00000000-0000-4000-8000-000000000502';
    IF second_token <= first_token THEN
        RAISE EXCEPTION 'lease fencing token did not advance';
    END IF;
END;
$$;

INSERT INTO outbox_events (id, event_key, aggregate_type, aggregate_id, event_type, payload)
VALUES ('00000000-0000-4000-8000-000000000601', 'phase-one-test-event', 'job',
        '00000000-0000-4000-8000-000000000301', 'job.leased', '{"attempt":1}'::jsonb);

DO $$
BEGIN
    BEGIN
        INSERT INTO outbox_events (id, event_key, aggregate_type, aggregate_id, event_type, payload)
        VALUES ('00000000-0000-4000-8000-000000000602', 'phase-one-test-event', 'job',
                '00000000-0000-4000-8000-000000000301', 'job.leased', '{}'::jsonb);
        RAISE EXCEPTION 'duplicate outbox event key was accepted';
    EXCEPTION WHEN unique_violation THEN
        NULL;
    END;
END;
$$;

ROLLBACK;
