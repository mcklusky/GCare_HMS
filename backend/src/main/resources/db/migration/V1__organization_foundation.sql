-- V1: organization scope, facilities, departments and the audit trail.
-- Naming: ADR-009 (organization / organization_id). Isolation: ADR-010 (PostgreSQL row-level security).
--
-- Isolation model
--   * The application runs every query as the NOLOGIN role gcare_app (SET ROLE on connection checkout).
--   * The current organization is passed in the session setting app.current_organization_id.
--   * Policies only expose rows of that organization; with no organization set, no rows are visible.
--   * FORCE ROW LEVEL SECURITY applies policies to the table owner too. Superusers still bypass RLS,
--     which is why the application never queries as the migration user.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'gcare_app') THEN
        CREATE ROLE gcare_app NOLOGIN;
    END IF;
END
$$;

-- Lets the migration/login user switch to gcare_app (needed when it is not a superuser).
GRANT gcare_app TO CURRENT_USER;
GRANT USAGE ON SCHEMA public TO gcare_app;

CREATE FUNCTION app_current_organization_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$
    SELECT NULLIF(current_setting('app.current_organization_id', true), '')::uuid
$$;

-- ---------------------------------------------------------------------------
-- Organizations (hospital customers). Created by platform operations, not by the app role.
-- ---------------------------------------------------------------------------
CREATE TABLE organizations (
    id            uuid         PRIMARY KEY,
    code          varchar(50)  NOT NULL UNIQUE,
    name          varchar(200) NOT NULL,
    country_code  varchar(2)   NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    status        varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    hosting_model varchar(20)  NOT NULL CHECK (hosting_model IN ('DEDICATED', 'SHARED')),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    version       bigint       NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------
-- Facilities (hospitals/sites) within an organization.
-- ---------------------------------------------------------------------------
CREATE TABLE facilities (
    id              uuid         PRIMARY KEY,
    organization_id uuid         NOT NULL REFERENCES organizations (id),
    code            varchar(50)  NOT NULL,
    name            varchar(200) NOT NULL,
    address         text,
    timezone        varchar(64)  NOT NULL,
    status          varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_facilities_org_code UNIQUE (organization_id, code),
    CONSTRAINT uq_facilities_org_id UNIQUE (organization_id, id) -- target for same-organization FKs
);

-- ---------------------------------------------------------------------------
-- Departments within a facility. The composite FK guarantees the facility belongs to the
-- same organization as the department.
-- ---------------------------------------------------------------------------
CREATE TABLE departments (
    id              uuid         PRIMARY KEY,
    organization_id uuid         NOT NULL,
    facility_id     uuid         NOT NULL,
    code            varchar(50)  NOT NULL,
    name            varchar(200) NOT NULL,
    status          varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT fk_departments_facility FOREIGN KEY (organization_id, facility_id)
        REFERENCES facilities (organization_id, id),
    CONSTRAINT uq_departments_facility_code UNIQUE (facility_id, code)
);

CREATE INDEX ix_departments_facility ON departments (facility_id);

-- ---------------------------------------------------------------------------
-- Audit trail (FR-026). Append-only for everyone, including the table owner.
-- metadata holds redacted context only; never clinical content or secrets.
-- organization_id is NULL for platform-level and pre-authentication events.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_events (
    id              uuid         PRIMARY KEY,
    organization_id uuid         REFERENCES organizations (id),
    facility_id     uuid,
    actor_id        uuid,
    action          varchar(100) NOT NULL,
    resource_type   varchar(100) NOT NULL,
    resource_id     varchar(100),
    outcome         varchar(20)  NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
    purpose         varchar(200),
    occurred_at     timestamptz  NOT NULL,
    recorded_at     timestamptz  NOT NULL DEFAULT now(),
    correlation_id  varchar(64),
    source_address  varchar(64),
    metadata        jsonb        NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX ix_audit_events_org_time ON audit_events (organization_id, occurred_at DESC);
CREATE INDEX ix_audit_events_actor_time ON audit_events (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_events_resource ON audit_events (resource_type, resource_id);

CREATE FUNCTION audit_events_reject_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only (% rejected)', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER trg_audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_reject_change();

CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION audit_events_reject_change();

-- ---------------------------------------------------------------------------
-- Row-level security policies.
-- ---------------------------------------------------------------------------
ALTER TABLE organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE organizations FORCE ROW LEVEL SECURITY;
CREATE POLICY organizations_isolation ON organizations
    USING (id = app_current_organization_id())
    WITH CHECK (id = app_current_organization_id());

ALTER TABLE facilities ENABLE ROW LEVEL SECURITY;
ALTER TABLE facilities FORCE ROW LEVEL SECURITY;
CREATE POLICY facilities_isolation ON facilities
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE departments ENABLE ROW LEVEL SECURITY;
ALTER TABLE departments FORCE ROW LEVEL SECURITY;
CREATE POLICY departments_isolation ON departments
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_events_read ON audit_events FOR SELECT
    USING (organization_id = app_current_organization_id());
CREATE POLICY audit_events_write ON audit_events FOR INSERT
    WITH CHECK (organization_id IS NULL OR organization_id = app_current_organization_id());

-- ---------------------------------------------------------------------------
-- Application role privileges. No DELETE anywhere: clinical/financial/config records use status.
-- Organizations are created by platform operations (privileged path), so no INSERT for the app.
-- ---------------------------------------------------------------------------
GRANT SELECT, UPDATE ON organizations TO gcare_app;
GRANT SELECT, INSERT, UPDATE ON facilities TO gcare_app;
GRANT SELECT, INSERT, UPDATE ON departments TO gcare_app;
GRANT SELECT, INSERT ON audit_events TO gcare_app;
GRANT EXECUTE ON FUNCTION app_current_organization_id() TO gcare_app;
