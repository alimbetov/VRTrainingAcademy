-- R3 numbered students and daily classroom reporting. PostgreSQL 16.
-- Run AFTER R3__classroom_learning_foundation.sql, in a reviewed migration.
-- Legacy nicknames remain nullable for compatibility; new rows need no names.
ALTER TABLE education.student_profile
  ADD COLUMN student_number integer;
ALTER TABLE education.student_profile
  ALTER COLUMN nickname DROP NOT NULL;
ALTER TABLE education.student_profile
  ALTER COLUMN normalized_nickname DROP NOT NULL;
ALTER TABLE education.student_profile
  ADD CONSTRAINT ck_student_number_range
  CHECK (student_number IS NULL OR student_number BETWEEN 100 AND 9999);
ALTER TABLE education.student_profile
  ADD CONSTRAINT ck_student_has_identifier
  CHECK (student_number IS NOT NULL OR nickname IS NOT NULL);
CREATE UNIQUE INDEX uq_student_number_per_class
  ON education.student_profile(classroom_id, student_number)
  WHERE student_number IS NOT NULL;

-- Daily session date is an explicit school-local calendar date, not UTC date.
-- App must compute this with the school's IANA timezone (e.g. Asia/Almaty).
ALTER TABLE education.classroom_session
  ADD COLUMN school_timezone varchar(64) NOT NULL DEFAULT 'Asia/Almaty',
  ADD COLUMN teaching_date date;
CREATE INDEX idx_classroom_session_daily_stats
  ON education.classroom_session(classroom_id,teaching_date);

-- Retain day-scoped records for 30 days for delayed sync and auditing.
-- Do not automatically delete unacknowledged events.
ALTER TABLE education.learning_attempt
  ADD COLUMN sync_acknowledged boolean NOT NULL DEFAULT false,
  ADD COLUMN protected_from_cleanup boolean NOT NULL DEFAULT false;

-- Production backfill requires migration of existing sessions to the correct
-- teaching_date, calculated from historical timezone before making it NOT NULL.
-- A retention worker must use the teacher-authorized retention policy.
-- Numbers are scoped to classroom and remain independent of device ID.
