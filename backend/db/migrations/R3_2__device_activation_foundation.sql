-- PostgreSQL foundation for org-owned VR device enrollment.
-- Requires OIDC administrator confirmation and Ed25519 private-key proof.
-- Device token issuance, revocation distribution and membership RLS are not
-- implemented by this migration alone.
CREATE SCHEMA IF NOT EXISTS platform;

CREATE TABLE platform.registered_device (
    device_id uuid PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    public_key_spki bytea NOT NULL,
    state varchar(16) NOT NULL,
    activated_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT ck_device_state CHECK (state IN ('ACTIVE','SUSPENDED','REVOKED')),
    CONSTRAINT ck_device_revocation CHECK (state != 'REVOKED' OR revoked_at IS NOT NULL)
);
CREATE INDEX idx_registered_device_tenant_state
    ON platform.registered_device(tenant_id,state);

CREATE TABLE platform.device_activation_flow (
    flow_id uuid PRIMARY KEY,
    code_digest bytea NOT NULL,
    challenge bytea NOT NULL,
    public_key_spki bytea NOT NULL,
    tenant_id varchar(128),
    state varchar(16) NOT NULL DEFAULT 'PENDING',
    expires_at timestamptz NOT NULL,
    approved_at timestamptz,
    activated_device_id uuid REFERENCES platform.registered_device(device_id) ON DELETE RESTRICT,
    unsuccessful_attempts integer NOT NULL DEFAULT 0,
    CONSTRAINT ck_device_flow_state CHECK (state IN ('PENDING','APPROVED','ACTIVE','EXPIRED')),
    CONSTRAINT ck_device_flow_attempts CHECK (unsuccessful_attempts BETWEEN 0 AND 10)
);
CREATE INDEX idx_device_activation_expiry
    ON platform.device_activation_flow(expires_at);

-- Threat model:
-- Store a server-peppered HMAC of the user code, not a reusable plaintext code.
-- Cap incorrect code attempts and enforce code TTL + atomically one-use approval.
-- Expired PENDING entries should be purged by retention job.
-- An admin session must be checked against actual organization membership.
-- Issued client tokens should be sender-constrained with registered key.
