-- R5.4: server-bound, durable Quest artifact ingestion. No publishing grant.
-- Run after R5.1 and R5.2 with reviewed migration tooling.
CREATE TABLE IF NOT EXISTS content.lesson_upload_session (
  upload_id uuid PRIMARY KEY,
  lesson_id uuid NOT NULL REFERENCES content.catalog_lesson(lesson_id) ON DELETE RESTRICT,
  subject_id uuid NOT NULL REFERENCES content.catalog_subject(subject_id) ON DELETE RESTRICT,
  world_id uuid NOT NULL REFERENCES content.catalog_world(world_id) ON DELETE RESTRICT,
  created_by_subject varchar(255) NOT NULL,
  idempotency_key varchar(128) NOT NULL,
  state varchar(20) NOT NULL DEFAULT 'CREATED',
  artifact_name varchar(120),
  artifact_sha256 varchar(64),
  artifact_size_bytes bigint,
  object_key text,
  runtime_build_id varchar(128),
  entry_level varchar(240),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT uq_content_upload_idempotency UNIQUE(created_by_subject,lesson_id,idempotency_key),
  CONSTRAINT ck_content_upload_state CHECK(state IN ('CREATED','UPLOADING','STAGED','FAILED')),
  CONSTRAINT ck_content_upload_artifact CHECK (
      (state IN ('CREATED','FAILED') AND object_key IS NULL)
      OR (state='UPLOADING' AND object_key IS NULL)
      OR (state='STAGED' AND object_key IS NOT NULL AND artifact_sha256 ~ '^[0-9a-f]{64}$'
           AND artifact_size_bytes>0)),
  CONSTRAINT ck_content_upload_name CHECK (artifact_name IS NULL OR
      (artifact_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}\\.pak$'
       AND position('..' in artifact_name)=0))
);
CREATE INDEX IF NOT EXISTS idx_content_upload_lesson
  ON content.lesson_upload_session(lesson_id,created_at DESC);

-- Membership and placement are checked transactionally by application code.
-- A future worker must recover abandoned UPLOADING sessions and delete any
-- orphaned S3 object if the final database transaction fails.
