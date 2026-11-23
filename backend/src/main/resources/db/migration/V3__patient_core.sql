-- V3: patient records, identifiers, patient numbers and duplicate review flags (task 2.1).
-- Rules: docs/17-PATIENT-IDENTITY-POLICY.md (PROVISIONAL, OD-05/06/07). FR-003, FR-004, FR-005.
--
-- Nothing here is ever deleted: no DELETE grants. Linking (task 2.3) changes status, never data.
-- *_norm columns are written by the application (patient/PatientNormalizer) and used for search
-- and duplicate candidates; display columns keep exactly what was entered.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

INSERT INTO permissions (code, description) VALUES
    ('PATIENT_READ',     'Search and view patient records of the organization'),
    ('PATIENT_REGISTER', 'Register new patients at facilities in scope');

-- ---------------------------------------------------------------------------
-- Patients. One row per registered person per organization (longitudinal record).
-- ---------------------------------------------------------------------------
CREATE TABLE patients (
    id                       uuid         PRIMARY KEY,
    organization_id          uuid         NOT NULL REFERENCES organizations (id),
    patient_number           varchar(12)  NOT NULL CHECK (patient_number ~ '^P[0-9]{7}-[0-9]$'),
    registration_facility_id uuid         NOT NULL,
    status                   varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'LINKED')),
    family_name              varchar(100),
    given_names              varchar(150),
    family_name_norm         varchar(100),
    given_names_norm         varchar(150),
    full_name_norm           varchar(260) GENERATED ALWAYS AS
                                 (trim(coalesce(given_names_norm, '') || ' ' || coalesce(family_name_norm, ''))) STORED,
    birth_date               date,
    birth_date_estimated     boolean      NOT NULL DEFAULT false,
    sex                      varchar(10)  NOT NULL CHECK (sex IN ('FEMALE', 'MALE', 'OTHER', 'UNKNOWN')),
    phone                    varchar(30),
    phone_norm               varchar(20),
    details_incomplete       boolean      NOT NULL,
    registration_source      varchar(20)  NOT NULL CHECK (registration_source IN ('WALK_IN', 'REFERRAL', 'EMERGENCY', 'OTHER')),
    created_at               timestamptz  NOT NULL DEFAULT now(),
    created_by               uuid         NOT NULL,
    updated_at               timestamptz  NOT NULL DEFAULT now(),
    version                  bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_patients_org_number UNIQUE (organization_id, patient_number),
    CONSTRAINT uq_patients_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_patients_facility FOREIGN KEY (organization_id, registration_facility_id)
        REFERENCES facilities (organization_id, id),
    CONSTRAINT fk_patients_created_by FOREIGN KEY (organization_id, created_by)
        REFERENCES users (organization_id, id)
    -- "Birth date not in the future" is checked by the application (needs the facility's time zone).
);

CREATE INDEX ix_patients_full_name_trgm ON patients USING gin (full_name_norm gin_trgm_ops);
CREATE INDEX ix_patients_family_trgm ON patients USING gin (family_name_norm gin_trgm_ops);
CREATE INDEX ix_patients_phone ON patients (organization_id, phone_norm);
CREATE INDEX ix_patients_birth_date ON patients (organization_id, birth_date);

-- ---------------------------------------------------------------------------
-- Identity documents and other identifiers, with provenance. Never unique: two records with the
-- same identifier are a likely duplicate for people to resolve, not an insert error.
-- ---------------------------------------------------------------------------
CREATE TABLE patient_identifiers (
    id               uuid         PRIMARY KEY,
    organization_id  uuid         NOT NULL,
    patient_id       uuid         NOT NULL,
    identifier_type  varchar(20)  NOT NULL CHECK (identifier_type IN ('NATIONAL_ID', 'PASSPORT', 'INSURANCE', 'OTHER')),
    value            varchar(50)  NOT NULL,
    value_norm       varchar(50)  NOT NULL,
    issuer           varchar(100),
    verified         boolean      NOT NULL DEFAULT false,
    status           varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'RETIRED')),
    source           varchar(20)  NOT NULL CHECK (source IN ('REGISTRATION', 'CORRECTION', 'IMPORT')),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    created_by       uuid         NOT NULL,
    CONSTRAINT fk_patient_identifiers_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES patients (organization_id, id),
    CONSTRAINT fk_patient_identifiers_created_by FOREIGN KEY (organization_id, created_by)
        REFERENCES users (organization_id, id)
);

CREATE INDEX ix_patient_identifiers_lookup ON patient_identifiers (organization_id, identifier_type, value_norm);
CREATE INDEX ix_patient_identifiers_patient ON patient_identifiers (patient_id);

-- ---------------------------------------------------------------------------
-- Patient number counter, one row per organization. Numbers are never reused.
-- ---------------------------------------------------------------------------
CREATE TABLE patient_number_counters (
    organization_id uuid   PRIMARY KEY REFERENCES organizations (id),
    next_value      bigint NOT NULL CHECK (next_value BETWEEN 1000001 AND 10000000)
);

-- ---------------------------------------------------------------------------
-- Duplicate review queue: pairs a person must look at (record created despite a likely
-- duplicate, or reported by staff). The reason is free text entered by staff, kept here rather
-- than in the audit metadata.
-- ---------------------------------------------------------------------------
CREATE TABLE patient_duplicate_flags (
    id                   uuid         PRIMARY KEY,
    organization_id      uuid         NOT NULL,
    patient_id           uuid         NOT NULL,
    candidate_patient_id uuid         NOT NULL,
    score                integer      NOT NULL,
    origin               varchar(30)  NOT NULL CHECK (origin IN ('REGISTERED_DESPITE_MATCH', 'STAFF_REPORT')),
    reason               text         NOT NULL CHECK (length(trim(reason)) > 0),
    status               varchar(20)  NOT NULL CHECK (status IN ('OPEN', 'NOT_DUPLICATE', 'LINK_REQUESTED')),
    created_at           timestamptz  NOT NULL DEFAULT now(),
    created_by           uuid         NOT NULL,
    resolved_at          timestamptz,
    resolved_by          uuid,
    version              bigint       NOT NULL DEFAULT 0,
    CONSTRAINT fk_dup_flags_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES patients (organization_id, id),
    CONSTRAINT fk_dup_flags_candidate FOREIGN KEY (organization_id, candidate_patient_id)
        REFERENCES patients (organization_id, id),
    CONSTRAINT ck_dup_flags_distinct CHECK (patient_id <> candidate_patient_id)
);

CREATE INDEX ix_dup_flags_open ON patient_duplicate_flags (organization_id, status);

-- ---------------------------------------------------------------------------
-- Row-level security (same model as V1/V2).
-- ---------------------------------------------------------------------------
ALTER TABLE patients ENABLE ROW LEVEL SECURITY;
ALTER TABLE patients FORCE ROW LEVEL SECURITY;
CREATE POLICY patients_isolation ON patients
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE patient_identifiers ENABLE ROW LEVEL SECURITY;
ALTER TABLE patient_identifiers FORCE ROW LEVEL SECURITY;
CREATE POLICY patient_identifiers_isolation ON patient_identifiers
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE patient_number_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE patient_number_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY patient_number_counters_isolation ON patient_number_counters
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE patient_duplicate_flags ENABLE ROW LEVEL SECURITY;
ALTER TABLE patient_duplicate_flags FORCE ROW LEVEL SECURITY;
CREATE POLICY patient_duplicate_flags_isolation ON patient_duplicate_flags
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

-- No DELETE anywhere.
GRANT SELECT, INSERT, UPDATE ON patients TO gcare_app;
GRANT SELECT, INSERT, UPDATE ON patient_identifiers TO gcare_app;
GRANT SELECT, INSERT, UPDATE ON patient_number_counters TO gcare_app;
GRANT SELECT, INSERT, UPDATE ON patient_duplicate_flags TO gcare_app;
