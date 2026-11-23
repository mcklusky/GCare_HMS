-- V14: versioned configuration with two-person approval, and facility logos (task 5.2). FR-042.
-- Decisions (user, 2026-11-23; docs/21): settings are proposed as a new version and become active
-- only when a different person approves them; every version is kept. Clinical settings (triage
-- scale and alarm limits) need CONFIG_CLINICAL_MANAGE; branding, receipt text and operational
-- options need CONFIG_ORGANIZATION_MANAGE. No values are defined here: everything starts empty.

INSERT INTO permissions (code, description) VALUES
    ('CONFIG_CLINICAL_MANAGE',     'Propose and approve clinical settings such as the triage scale and alarm limits (not your own)'),
    ('CONFIG_ORGANIZATION_MANAGE', 'Propose and approve facility branding, receipt text and operational options (not your own)');

-- ---------------------------------------------------------------------------
-- One row per version of a setting. facility_id is NULL for organization-wide settings.
-- PROPOSED → ACTIVE | REJECTED; ACTIVE → SUPERSEDED when a newer version is approved.
-- ---------------------------------------------------------------------------
CREATE TABLE config_versions (
    id               uuid          PRIMARY KEY,
    organization_id  uuid          NOT NULL REFERENCES organizations (id),
    setting_key      varchar(50)   NOT NULL CHECK (setting_key ~ '^[A-Z][A-Z0-9_]*$'),
    facility_id      uuid,
    version_number   integer       NOT NULL CHECK (version_number > 0),
    value            jsonb         NOT NULL,
    status           varchar(20)   NOT NULL CHECK (status IN ('PROPOSED', 'ACTIVE', 'SUPERSEDED', 'REJECTED')),
    reason           text          NOT NULL CHECK (length(trim(reason)) > 0),
    proposed_by      uuid          NOT NULL,
    proposed_at      timestamptz   NOT NULL DEFAULT now(),
    decided_by       uuid,
    decided_at       timestamptz,
    decision_note    text,
    version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT fk_config_versions_facility FOREIGN KEY (organization_id, facility_id)
        REFERENCES facilities (organization_id, id),
    CONSTRAINT fk_config_versions_proposed_by FOREIGN KEY (organization_id, proposed_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_config_versions_decided_by FOREIGN KEY (organization_id, decided_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_config_versions_two_person CHECK (decided_by IS NULL OR decided_by <> proposed_by),
    CONSTRAINT ck_config_versions_decision CHECK (
        (status = 'PROPOSED' AND decided_by IS NULL AND decided_at IS NULL AND decision_note IS NULL)
        OR (status <> 'PROPOSED' AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decision_note IS NOT NULL AND length(trim(decision_note)) > 0))
);
-- Version numbers are unique per setting (and facility); at most one active and one pending.
CREATE UNIQUE INDEX uq_config_versions_number
    ON config_versions (organization_id, setting_key, coalesce(facility_id, '00000000-0000-0000-0000-000000000000'::uuid), version_number);
CREATE UNIQUE INDEX uq_config_versions_active
    ON config_versions (organization_id, setting_key, coalesce(facility_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_config_versions_pending
    ON config_versions (organization_id, setting_key, coalesce(facility_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status = 'PROPOSED';

-- The value never changes; status only moves forward.
CREATE FUNCTION app_config_version_forward_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.value IS DISTINCT FROM OLD.value
            OR OLD.status IN ('REJECTED', 'SUPERSEDED')
            OR (OLD.status = 'PROPOSED' AND NEW.status NOT IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status <> 'SUPERSEDED') THEN
        RAISE EXCEPTION 'configuration version % (%) cannot change this way', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_config_versions_forward_only BEFORE UPDATE ON config_versions
    FOR EACH ROW EXECUTE FUNCTION app_config_version_forward_only();

-- ---------------------------------------------------------------------------
-- Facility logos (PNG or JPEG, at most 256 KiB). A logo is shown only once an approved branding
-- version refers to it.
-- ---------------------------------------------------------------------------
CREATE TABLE facility_logos (
    id               uuid          PRIMARY KEY,
    organization_id  uuid          NOT NULL,
    facility_id      uuid          NOT NULL,
    content_type     varchar(20)   NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg')),
    bytes            bytea         NOT NULL CHECK (octet_length(bytes) BETWEEN 1 AND 262144),
    sha256           char(64)      NOT NULL,
    uploaded_by      uuid          NOT NULL,
    uploaded_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fk_facility_logos_facility FOREIGN KEY (organization_id, facility_id)
        REFERENCES facilities (organization_id, id),
    CONSTRAINT fk_facility_logos_uploaded_by FOREIGN KEY (organization_id, uploaded_by) REFERENCES users (organization_id, id)
);

ALTER TABLE config_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE config_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY config_versions_isolation ON config_versions
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE facility_logos ENABLE ROW LEVEL SECURITY;
ALTER TABLE facility_logos FORCE ROW LEVEL SECURITY;
CREATE POLICY facility_logos_isolation ON facility_logos
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT ON config_versions TO gcare_app;
GRANT UPDATE (status, decided_by, decided_at, decision_note, version) ON config_versions TO gcare_app;
GRANT SELECT, INSERT ON facility_logos TO gcare_app;
