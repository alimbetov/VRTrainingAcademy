-- R4 PostgreSQL reference schema; include in reviewed Liquibase baseline later.
-- Server remains fail-closed unless vr.licensing.persistence-enabled is explicit.
CREATE SCHEMA IF NOT EXISTS platform;

CREATE TABLE platform.organization_account (
    organization_id uuid PRIMARY KEY,
    owner_subject varchar(255) NOT NULL UNIQUE,
    verified_contact_email varchar(254) NOT NULL,
    display_name varchar(120) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_org_status CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    CONSTRAINT ck_org_name CHECK (length(trim(display_name)) > 0)
);
CREATE INDEX idx_org_contact_email ON platform.organization_account(verified_contact_email);

CREATE TABLE platform.organization_license_request (
    request_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES platform.organization_account(organization_id) ON DELETE RESTRICT,
    requested_by_subject varchar(255) NOT NULL,
    requested_device_limit integer NOT NULL,
    requested_collections text[] NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL,
    resolved_at timestamptz,
    resolved_by_subject varchar(255),
    CONSTRAINT ck_license_req_devices CHECK (requested_device_limit BETWEEN 1 AND 10000),
    CONSTRAINT ck_license_req_state CHECK (state IN ('PENDING','APPROVED','REJECTED')),
    CONSTRAINT ck_license_req_collections CHECK (cardinality(requested_collections) BETWEEN 1 AND 100)
);
CREATE INDEX idx_license_req_org_status
    ON platform.organization_license_request(organization_id,state,created_at);

CREATE TABLE platform.organization_license (
    license_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES platform.organization_account(organization_id) ON DELETE RESTRICT,
    kind varchar(20) NOT NULL,
    request_id uuid UNIQUE REFERENCES platform.organization_license_request(request_id) ON DELETE RESTRICT,
    starts_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    device_limit integer NOT NULL,
    allowed_collections text[] NOT NULL,
    issued_by_subject varchar(255) NOT NULL,
    issued_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT ck_license_kind CHECK (kind IN ('TRIAL','COMMERCIAL','TRIAL_EXTENSION')),
    CONSTRAINT ck_license_dates CHECK (expires_at > starts_at),
    CONSTRAINT ck_license_devices CHECK (device_limit BETWEEN 1 AND 10000),
    CONSTRAINT ck_license_collections CHECK (cardinality(allowed_collections) BETWEEN 1 AND 100)
);
CREATE UNIQUE INDEX uq_one_lifetime_trial_per_org
    ON platform.organization_license(organization_id) WHERE kind='TRIAL';
CREATE INDEX idx_org_license_active_window
    ON platform.organization_license(organization_id,starts_at,expires_at)
    WHERE revoked_at IS NULL;

-- Device limit must be checked atomically during activation, not merely at
-- license issuance. Actual registered-device seat transaction is R4 follow-up.
-- Collection IDs must be resolved against trustworthy published content
-- before admin issues commercial rights; currently an integration boundary.
