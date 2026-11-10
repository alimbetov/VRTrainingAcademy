-- R3 PostgreSQL DDL reference. Requires PostgreSQL 15+.
-- Apply in a reviewed migration tool transaction; NOT auto-executed on startup.
-- This schema is not accessible via API until authorization adapters are wired.
CREATE SCHEMA IF NOT EXISTS education;

CREATE TABLE education.classroom (
    classroom_id uuid PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    teacher_subject varchar(128) NOT NULL,
    title varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_classroom_tenant_nonempty CHECK (length(trim(tenant_id)) > 0)
);
CREATE INDEX idx_classroom_tenant_teacher
    ON education.classroom(tenant_id, teacher_subject);

CREATE TABLE education.student_profile (
    student_id uuid PRIMARY KEY,
    classroom_id uuid NOT NULL REFERENCES education.classroom(classroom_id) ON DELETE RESTRICT,
    nickname varchar(40) NOT NULL,
    normalized_nickname varchar(40) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_student_classroom_nickname UNIQUE (classroom_id, normalized_nickname),
    CONSTRAINT ck_student_nickname_not_blank CHECK (length(trim(nickname)) > 0)
);
CREATE INDEX idx_student_classroom ON education.student_profile(classroom_id);

CREATE TABLE education.classroom_session (
    session_id uuid PRIMARY KEY,
    classroom_id uuid NOT NULL REFERENCES education.classroom(classroom_id) ON DELETE RESTRICT,
    device_id uuid NOT NULL,
    lesson_id varchar(128) NOT NULL,
    lesson_revision varchar(128) NOT NULL,
    begins_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_by_subject varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_session_time CHECK (ends_at > begins_at AND ends_at <= begins_at + interval '8 hours')
);
CREATE INDEX idx_session_device_time
    ON education.classroom_session(device_id, begins_at, ends_at);
CREATE INDEX idx_session_classroom ON education.classroom_session(classroom_id);

CREATE TABLE education.learning_attempt (
    attempt_id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES education.classroom_session(session_id) ON DELETE RESTRICT,
    student_id uuid NOT NULL REFERENCES education.student_profile(student_id) ON DELETE RESTRICT,
    device_id uuid NOT NULL,
    lesson_revision varchar(128) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT ck_attempt_completion CHECK (completed_at IS NULL OR completed_at >= started_at),
    CONSTRAINT uq_attempt_identity UNIQUE (attempt_id, session_id)
);
CREATE INDEX idx_attempt_student ON education.learning_attempt(student_id, started_at);
CREATE INDEX idx_attempt_session ON education.learning_attempt(session_id);

CREATE TABLE education.learning_event (
    event_id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL REFERENCES education.learning_attempt(attempt_id) ON DELETE RESTRICT,
    sequence_number integer NOT NULL,
    event_type varchar(40) NOT NULL,
    score_delta integer NOT NULL DEFAULT 0,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_event_attempt_sequence UNIQUE (attempt_id, sequence_number),
    CONSTRAINT ck_event_sequence CHECK (sequence_number >= 0 AND sequence_number <= 1000000),
    CONSTRAINT ck_event_score CHECK (score_delta BETWEEN 0 AND 100),
    CONSTRAINT ck_event_type CHECK (event_type ~ '^[A-Z_]{2,40}$')
);
CREATE INDEX idx_event_attempt_received
    ON education.learning_event(attempt_id, received_at);

-- Privacy: no legal names, email, phone, student password, or biometric fields.
-- Deletion and retention policy must be implemented by an authorized admin
-- service without CASCADE deleting educational evidence unintentionally.
--
-- Future tenant safety: enforce device/session/teacher membership within
-- transaction service, preferably with dedicated session claims and RLS.
-- Do not consider these constraints sufficient by themselves.
