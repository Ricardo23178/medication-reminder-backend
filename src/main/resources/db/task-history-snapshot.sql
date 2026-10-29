-- One-time MySQL 8 migration after plan-version.sql.
-- Stop backend/schedulers and back up before applying to an existing database.
ALTER TABLE remind_task
    ADD COLUMN snapshot_medicine_id BIGINT UNSIGNED NULL COMMENT 'Medicine at task generation',
    ADD COLUMN snapshot_medicine_name VARCHAR(128) NULL,
    ADD COLUMN snapshot_dosage_per_time DECIMAL(8,2) NULL,
    ADD COLUMN snapshot_dosage_unit VARCHAR(16) NULL,
    ADD COLUMN snapshot_meal_relation TINYINT NULL;

-- Only a known matching version can be initialized from current rules.
-- Unknown legacy versions and older versions stay NULL; do not invent history.
UPDATE remind_task t
JOIN medication_plan p ON p.id=t.plan_id AND p.user_id=t.user_id
JOIN medicine m ON m.id=p.medicine_id AND m.user_id=t.user_id
SET t.snapshot_medicine_id=p.medicine_id,
    t.snapshot_medicine_name=m.name,
    t.snapshot_dosage_per_time=p.dosage_per_time,
    t.snapshot_dosage_unit=p.dosage_unit,
    t.snapshot_meal_relation=p.meal_relation,
    t.update_time=t.update_time
WHERE t.plan_version=p.plan_version AND t.plan_version IS NOT NULL;
