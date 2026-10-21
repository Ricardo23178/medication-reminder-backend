-- One-time MySQL 8 migration. Stop backend/schedulers and back up the database first.
-- Existing completed/expired task versions cannot be reconstructed: leave them NULL.
ALTER TABLE medication_plan
    ADD COLUMN plan_version INT NOT NULL DEFAULT 1 COMMENT 'Plan rule version';

ALTER TABLE remind_task
    ADD COLUMN plan_version INT NULL COMMENT 'Version at generation; NULL for unknown legacy history';

-- Only current, active, unfinished rules receive baseline version 1.
UPDATE remind_task t
JOIN medication_plan p ON p.id=t.plan_id AND p.user_id=t.user_id
JOIN plan_time pt ON pt.id=t.plan_time_id AND pt.plan_id=p.id
SET t.plan_version=p.plan_version
WHERE t.status IN ('WAITING','TRIGGERED')
  AND p.status='ACTIVE' AND p.deleted=0 AND pt.enabled=1;

UPDATE remind_task
SET status='CANCELLED'
WHERE status IN ('WAITING','TRIGGERED') AND plan_version IS NULL;

-- NULL legacy versions may repeat; all newly generated tasks carry a non-NULL version.
-- Add the replacement before dropping the old key in the same ALTER.
ALTER TABLE remind_task
    ADD UNIQUE KEY uk_plan_version_scheduled (plan_id, plan_version, scheduled_at),
    DROP INDEX uk_plantime_scheduled;
