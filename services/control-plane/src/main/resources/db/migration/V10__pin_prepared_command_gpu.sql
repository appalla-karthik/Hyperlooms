-- Append-only upgrade: V9 may already be deployed and must not be edited.
ALTER TABLE lease_execution_commands ADD COLUMN gpu_uuid VARCHAR(100);

UPDATE lease_execution_commands c
SET gpu_uuid = l.gpu_uuid
FROM gpu_leases l
WHERE l.id = c.lease_id;

ALTER TABLE lease_execution_commands ALTER COLUMN gpu_uuid SET NOT NULL;
