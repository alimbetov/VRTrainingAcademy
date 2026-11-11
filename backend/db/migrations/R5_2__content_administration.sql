-- R5.2: Trusted administrative membership and append-only action journal.
-- Run after R5_1 content catalog DDL in a reviewed migration transaction.
CREATE TABLE IF NOT EXISTS content.admin_membership (
    membership_id uuid PRIMARY KEY,
    subject varchar(255) NOT NULL,
    role varchar(24) NOT NULL,
    scope_world_id uuid REFERENCES content.catalog_world(world_id) ON DELETE RESTRICT,
    organization_id uuid,
    active boolean NOT NULL DEFAULT true,
    granted_by varchar(255) NOT NULL,
    granted_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_content_admin_role CHECK
        (role IN ('PLATFORM_ADMIN','CONTENT_ADMIN','CONTENT_AUTHOR')),
    CONSTRAINT ck_content_admin_scope CHECK
        ((role='PLATFORM_ADMIN' AND scope_world_id IS NULL AND organization_id IS NULL)
         OR (role IN ('CONTENT_ADMIN','CONTENT_AUTHOR') AND scope_world_id IS NOT NULL)),
    CONSTRAINT ck_content_admin_subject CHECK (length(trim(subject)) > 0),
    CONSTRAINT ck_content_admin_granter CHECK (length(trim(granted_by)) > 0),
    CONSTRAINT uq_content_admin_role_scope UNIQUE NULLS NOT DISTINCT
        (subject,role,scope_world_id,organization_id)
);
-- Membership provisioning is separate trusted IAM administration; no self-grant REST.
-- A nullable organization_id means a global scope under that World;
-- organization-bound roles must be checked against the requested organization
-- by a future multi-tenant authoring endpoint. R5.2 intentionally denies such writes.
CREATE INDEX IF NOT EXISTS idx_content_admin_subject_active
    ON content.admin_membership(subject,active,role,scope_world_id);

CREATE TABLE IF NOT EXISTS content.admin_audit (
    event_id uuid PRIMARY KEY,
    actor_subject varchar(255) NOT NULL,
    action varchar(48) NOT NULL,
    object_type varchar(24) NOT NULL,
    object_id uuid NOT NULL,
    before_state text,
    after_state text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_admin_audit_actor CHECK (length(trim(actor_subject)) > 0)
);
CREATE INDEX IF NOT EXISTS idx_content_admin_audit_target
    ON content.admin_audit(object_type,object_id,created_at);
-- DB operator grants should prevent UPDATE/DELETE on admin_audit and
-- direct client INSERT into admin_membership. Application exposes no such API.
