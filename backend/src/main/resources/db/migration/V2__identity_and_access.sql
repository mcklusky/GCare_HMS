-- V2: users, roles, permissions and the link to the identity provider (task 1.3, OD-03, ADR-012).
--
-- Authentication (passwords, MFA, lockout) happens in Keycloak. Authorization lives here:
-- a signed-in Keycloak identity only gets access if it is linked to an ACTIVE GCare user, and
-- that user's permissions come from role assignments in this database, scoped to the
-- organization and optionally to one facility. No passwords are stored in GCare.

-- ---------------------------------------------------------------------------
-- Permission catalog. Platform-defined codes checked in code; not organization data.
-- ---------------------------------------------------------------------------
CREATE TABLE permissions (
    code        varchar(100) PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    description text         NOT NULL
);

INSERT INTO permissions (code, description) VALUES
    ('FACILITY_READ',   'View facilities and departments in scope'),
    ('FACILITY_MANAGE', 'Create and update facilities and departments in scope'),
    ('USER_READ',       'View user accounts and their role assignments in scope'),
    ('USER_MANAGE',     'Create, deactivate and assign roles to user accounts in scope'),
    ('AUDIT_READ',      'Read the audit trail of the organization');

-- ---------------------------------------------------------------------------
-- GCare user accounts. One account belongs to exactly one organization.
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id              uuid         PRIMARY KEY,
    organization_id uuid         NOT NULL REFERENCES organizations (id),
    username        varchar(100) NOT NULL,
    display_name    varchar(200) NOT NULL,
    email           varchar(254),
    status          varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    last_login_at   timestamptz,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_org_username UNIQUE (organization_id, username),
    CONSTRAINT uq_users_org_id UNIQUE (organization_id, id)
);

-- ---------------------------------------------------------------------------
-- Link from an identity-provider account (issuer + subject) to a GCare user.
-- Sign-in happens before the organization is known, so this table has no row-level security.
-- Instead the application role has NO direct access: it can only call
-- app_find_user_by_identity(), which returns the user and organization for one exact identity.
-- ---------------------------------------------------------------------------
CREATE TABLE user_identities (
    id              uuid         PRIMARY KEY,
    organization_id uuid         NOT NULL,
    user_id         uuid         NOT NULL,
    issuer          varchar(500) NOT NULL,
    subject         varchar(255) NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_user_identities_user FOREIGN KEY (organization_id, user_id)
        REFERENCES users (organization_id, id),
    CONSTRAINT uq_user_identities_issuer_subject UNIQUE (issuer, subject)
);

CREATE INDEX ix_user_identities_user ON user_identities (user_id);

REVOKE ALL ON user_identities FROM PUBLIC;

CREATE FUNCTION app_find_user_by_identity(p_issuer text, p_subject text)
    RETURNS TABLE (user_id uuid, organization_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT ui.user_id, ui.organization_id
    FROM user_identities ui
    WHERE ui.issuer = p_issuer AND ui.subject = p_subject
$$;

REVOKE ALL ON FUNCTION app_find_user_by_identity(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_find_user_by_identity(text, text) TO gcare_app;

-- ---------------------------------------------------------------------------
-- Roles are defined per organization. System roles (is_system_role) are created by the
-- platform and cannot be edited by organization administrators.
-- ---------------------------------------------------------------------------
CREATE TABLE roles (
    id              uuid         PRIMARY KEY,
    organization_id uuid         NOT NULL REFERENCES organizations (id),
    code            varchar(50)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    name            varchar(200) NOT NULL,
    description     text,
    is_system_role  boolean      NOT NULL DEFAULT false,
    status          varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_roles_org_code UNIQUE (organization_id, code),
    CONSTRAINT uq_roles_org_id UNIQUE (organization_id, id)
);

CREATE TABLE role_permissions (
    organization_id uuid         NOT NULL,
    role_id         uuid         NOT NULL,
    permission_code varchar(100) NOT NULL REFERENCES permissions (code),
    PRIMARY KEY (role_id, permission_code),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (organization_id, role_id)
        REFERENCES roles (organization_id, id)
);

-- ---------------------------------------------------------------------------
-- Role assignments. facility_id NULL = organization-wide; otherwise limited to that facility
-- (and optionally one department in it). Composite keys keep every referenced row inside the
-- same organization. Assignments are ended by setting active_until, never deleted.
-- ---------------------------------------------------------------------------
ALTER TABLE departments
    ADD CONSTRAINT uq_departments_facility_id UNIQUE (facility_id, id);

CREATE TABLE user_role_assignments (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    role_id         uuid        NOT NULL,
    facility_id     uuid,
    department_id   uuid,
    active_from     timestamptz NOT NULL DEFAULT now(),
    active_until    timestamptz,
    assigned_by     uuid,
    assigned_at     timestamptz NOT NULL DEFAULT now(),
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT fk_ura_user FOREIGN KEY (organization_id, user_id)
        REFERENCES users (organization_id, id),
    CONSTRAINT fk_ura_role FOREIGN KEY (organization_id, role_id)
        REFERENCES roles (organization_id, id),
    CONSTRAINT fk_ura_facility FOREIGN KEY (organization_id, facility_id)
        REFERENCES facilities (organization_id, id),
    CONSTRAINT fk_ura_department FOREIGN KEY (facility_id, department_id)
        REFERENCES departments (facility_id, id),
    CONSTRAINT fk_ura_assigned_by FOREIGN KEY (organization_id, assigned_by)
        REFERENCES users (organization_id, id),
    CONSTRAINT ck_ura_department_needs_facility CHECK (department_id IS NULL OR facility_id IS NOT NULL),
    CONSTRAINT ck_ura_active_period CHECK (active_until IS NULL OR active_until > active_from)
);

CREATE INDEX ix_ura_user ON user_role_assignments (user_id);

-- ---------------------------------------------------------------------------
-- Row-level security (same model as V1).
-- ---------------------------------------------------------------------------
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
CREATE POLICY users_isolation ON users
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE roles FORCE ROW LEVEL SECURITY;
CREATE POLICY roles_isolation ON roles
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE role_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE role_permissions FORCE ROW LEVEL SECURITY;
CREATE POLICY role_permissions_isolation ON role_permissions
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE user_role_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_role_assignments FORCE ROW LEVEL SECURITY;
CREATE POLICY user_role_assignments_isolation ON user_role_assignments
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

-- ---------------------------------------------------------------------------
-- Application role privileges. No DELETE: accounts are disabled, assignments are ended.
-- ---------------------------------------------------------------------------
GRANT SELECT ON permissions TO gcare_app;
GRANT SELECT, INSERT, UPDATE ON users TO gcare_app;
GRANT SELECT, INSERT, UPDATE ON roles TO gcare_app;
GRANT SELECT, INSERT ON role_permissions TO gcare_app;
GRANT SELECT, INSERT, UPDATE ON user_role_assignments TO gcare_app;
