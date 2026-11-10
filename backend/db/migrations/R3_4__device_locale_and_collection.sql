-- R3 revised product journey: student number -> learner language -> lesson.
-- Run after R3_2 and R3_3 on PostgreSQL 16.
-- A device must not impose a locale on all learners; learner locale belongs
-- to the learning attempt, selected after the numeric student ID.
ALTER TABLE education.learning_attempt
    ADD COLUMN learner_locale varchar(5),
    ADD COLUMN selected_lesson_id varchar(128),
    ADD CONSTRAINT ck_attempt_learner_locale
    CHECK (learner_locale IS NULL OR learner_locale IN ('kk-KZ', 'ru-RU', 'en-US'));

-- Teacher/course administration may scope the lesson menu to a collection.
-- Actual collection and lesson publication, tenant entitlement and compatible
-- release must be checked against trusted Content Domain on every selection.
-- Collection table/FK is deferred until Content Domain publication is persisted.
ALTER TABLE education.classroom_session
    ADD COLUMN collection_id varchar(128);

-- Existing learning_attempt records remain valid (nullable backfill). For new
-- lesson selections, application must save selected_lesson_id and learner_locale
-- atomically when starting the attempt, with a trusted device credential.
-- Never use user-supplied lesson IDs as S3 object keys.
