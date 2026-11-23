-- V7: clinical notes (draft, sign, amend) and visit diagnoses (task 3.2). FR-008.
-- Decisions: OD-10 fixed sections; OD-09 free-text diagnoses, codes added later (docs/18 §7).
-- Signed notes never change: the database refuses any update to a note that is not a draft.
-- Corrections are amendments (new notes pointing to the original) or "entered in error" marks.

INSERT INTO permissions (code, description) VALUES
    ('CLINICAL_NOTE_READ',  'Read signed clinical notes and visit diagnoses'),
    ('CLINICAL_NOTE_WRITE', 'Write, sign and amend own clinical notes; record visit diagnoses');

-- ---------------------------------------------------------------------------
-- Notes. Sections are free text (PROVISIONAL names). A draft is private to its author.
-- ---------------------------------------------------------------------------
CREATE TABLE clinical_notes (
    id                uuid         PRIMARY KEY,
    organization_id   uuid         NOT NULL,
    encounter_id      uuid         NOT NULL,
    patient_id        uuid         NOT NULL,
    author_id         uuid         NOT NULL,
    status            varchar(20)  NOT NULL CHECK (status IN ('DRAFT', 'SIGNED', 'DISCARDED')),
    history           text         CHECK (length(history) <= 20000),
    examination       text         CHECK (length(examination) <= 20000),
    assessment        text         CHECK (length(assessment) <= 20000),
    plan              text         CHECK (length(plan) <= 20000),
    amends_note_id    uuid,
    amendment_reason  text,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    signed_at         timestamptz,
    discarded_at      timestamptz,
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_clinical_notes_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_clinical_notes_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES encounters (organization_id, id),
    CONSTRAINT fk_clinical_notes_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES patients (organization_id, id),
    CONSTRAINT fk_clinical_notes_author FOREIGN KEY (organization_id, author_id)
        REFERENCES users (organization_id, id),
    CONSTRAINT fk_clinical_notes_amends FOREIGN KEY (organization_id, amends_note_id)
        REFERENCES clinical_notes (organization_id, id),
    CONSTRAINT ck_clinical_notes_amendment CHECK (
        (amends_note_id IS NULL) = (amendment_reason IS NULL)
        AND (amendment_reason IS NULL OR length(trim(amendment_reason)) > 0)),
    CONSTRAINT ck_clinical_notes_signed CHECK ((status = 'SIGNED') = (signed_at IS NOT NULL)),
    CONSTRAINT ck_clinical_notes_discarded CHECK ((status = 'DISCARDED') = (discarded_at IS NOT NULL)),
    CONSTRAINT ck_clinical_notes_signed_content CHECK (
        status <> 'SIGNED'
        OR num_nonnulls(nullif(trim(history), ''), nullif(trim(examination), ''),
                        nullif(trim(assessment), ''), nullif(trim(plan), '')) > 0)
);
CREATE INDEX ix_clinical_notes_encounter ON clinical_notes (encounter_id, created_at);
CREATE INDEX ix_clinical_notes_amends ON clinical_notes (amends_note_id) WHERE amends_note_id IS NOT NULL;

-- Only drafts can change, and only their content and lifecycle columns (column grant below).
CREATE FUNCTION app_clinical_note_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'clinical note % is % and cannot be changed', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_clinical_note_immutable BEFORE UPDATE ON clinical_notes
    FOR EACH ROW EXECUTE FUNCTION app_clinical_note_immutable();

-- ---------------------------------------------------------------------------
-- Diagnoses on a visit. Free text now; code and code_system stay empty until OD-09 picks a
-- coding system (they are filled by a future, reviewed process, never typed in by hand here).
-- ---------------------------------------------------------------------------
CREATE TABLE encounter_diagnoses (
    id               uuid          PRIMARY KEY,
    organization_id  uuid          NOT NULL,
    encounter_id     uuid          NOT NULL,
    patient_id       uuid          NOT NULL,
    description      varchar(500)  NOT NULL CHECK (length(trim(description)) > 0),
    certainty        varchar(20)   NOT NULL CHECK (certainty IN ('PROVISIONAL', 'CONFIRMED')),
    code             varchar(50),
    code_system      varchar(50),
    status           varchar(20)   NOT NULL DEFAULT 'RECORDED' CHECK (status IN ('RECORDED', 'ENTERED_IN_ERROR')),
    recorded_by      uuid          NOT NULL,
    recorded_at      timestamptz   NOT NULL DEFAULT now(),
    error_reason     text,
    error_by         uuid,
    error_at         timestamptz,
    CONSTRAINT fk_diagnoses_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES encounters (organization_id, id),
    CONSTRAINT fk_diagnoses_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES patients (organization_id, id),
    CONSTRAINT fk_diagnoses_recorded_by FOREIGN KEY (organization_id, recorded_by)
        REFERENCES users (organization_id, id),
    CONSTRAINT fk_diagnoses_error_by FOREIGN KEY (organization_id, error_by)
        REFERENCES users (organization_id, id),
    CONSTRAINT ck_diagnoses_code CHECK ((code IS NULL) = (code_system IS NULL)),
    CONSTRAINT ck_diagnoses_error CHECK (
        (status = 'RECORDED' AND error_reason IS NULL AND error_by IS NULL AND error_at IS NULL)
        OR (status = 'ENTERED_IN_ERROR' AND error_by IS NOT NULL AND error_at IS NOT NULL
            AND error_reason IS NOT NULL AND length(trim(error_reason)) > 0))
);
CREATE INDEX ix_diagnoses_encounter ON encounter_diagnoses (encounter_id, recorded_at);
CREATE INDEX ix_diagnoses_patient ON encounter_diagnoses (patient_id, recorded_at DESC);

-- ---------------------------------------------------------------------------
-- Isolation and grants. No DELETE anywhere.
-- ---------------------------------------------------------------------------
ALTER TABLE clinical_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE clinical_notes FORCE ROW LEVEL SECURITY;
CREATE POLICY clinical_notes_isolation ON clinical_notes
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE encounter_diagnoses ENABLE ROW LEVEL SECURITY;
ALTER TABLE encounter_diagnoses FORCE ROW LEVEL SECURITY;
CREATE POLICY encounter_diagnoses_isolation ON encounter_diagnoses
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT ON clinical_notes TO gcare_app;
GRANT UPDATE (history, examination, assessment, plan, status, signed_at, discarded_at, updated_at, version)
    ON clinical_notes TO gcare_app;
GRANT SELECT, INSERT ON encounter_diagnoses TO gcare_app;
GRANT UPDATE (status, error_reason, error_by, error_at) ON encounter_diagnoses TO gcare_app;
