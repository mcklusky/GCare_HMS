-- V4: correction history and duplicate review (task 2.2). FR-003, FR-004.
-- Rules: docs/17-PATIENT-IDENTITY-POLICY.md §3 (review queue) and §6 (corrections), PROVISIONAL.

INSERT INTO permissions (code, description) VALUES
    ('PATIENT_UPDATE',           'Correct patient demographic details (with a reason; history kept)'),
    ('PATIENT_DUPLICATE_REVIEW', 'Review possible duplicate patient records');

-- ---------------------------------------------------------------------------
-- One row per correction: which fields changed, from what, to what, by whom, when and why.
-- Append-only for the application (no UPDATE or DELETE grant). Holds patient details, so it is
-- protected like the patient record itself, not copied into the audit trail.
-- ---------------------------------------------------------------------------
CREATE TABLE patient_changes (
    id               uuid         PRIMARY KEY,
    organization_id  uuid         NOT NULL,
    patient_id       uuid         NOT NULL,
    patient_version  bigint       NOT NULL,
    changes          jsonb        NOT NULL,
    reason           text         NOT NULL CHECK (length(trim(reason)) > 0),
    changed_by       uuid         NOT NULL,
    changed_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_patient_changes_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES patients (organization_id, id),
    CONSTRAINT fk_patient_changes_user FOREIGN KEY (organization_id, changed_by)
        REFERENCES users (organization_id, id),
    CONSTRAINT uq_patient_changes_version UNIQUE (patient_id, patient_version)
);

ALTER TABLE patient_changes ENABLE ROW LEVEL SECURITY;
ALTER TABLE patient_changes FORCE ROW LEVEL SECURITY;
CREATE POLICY patient_changes_isolation ON patient_changes
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT ON patient_changes TO gcare_app;

-- Review decisions: who resolved a flag and the note they gave.
ALTER TABLE patient_duplicate_flags ADD COLUMN resolution_note text;
ALTER TABLE patient_duplicate_flags
    ADD CONSTRAINT fk_dup_flags_created_by FOREIGN KEY (organization_id, created_by)
        REFERENCES users (organization_id, id),
    ADD CONSTRAINT fk_dup_flags_resolved_by FOREIGN KEY (organization_id, resolved_by)
        REFERENCES users (organization_id, id),
    ADD CONSTRAINT ck_dup_flags_resolution CHECK (
        (status = 'OPEN' AND resolved_at IS NULL AND resolved_by IS NULL)
        OR (status <> 'OPEN' AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL
            AND resolution_note IS NOT NULL AND length(trim(resolution_note)) > 0));

-- At most one open flag per pair, in either order.
CREATE UNIQUE INDEX uq_dup_flags_open_pair ON patient_duplicate_flags
    (organization_id, LEAST(patient_id, candidate_patient_id), GREATEST(patient_id, candidate_patient_id))
    WHERE status = 'OPEN';
