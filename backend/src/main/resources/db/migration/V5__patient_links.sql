-- V5: controlled linking of duplicate patient records (task 2.3). FR-003, FR-005.
-- Rules: docs/17-PATIENT-IDENTITY-POLICY.md §5 (link instead of merge), PROVISIONAL.
-- Linking never moves or deletes data: the retired record only changes status and points to the
-- record that stays in use. Unlinking reverses that; the link row keeps both events forever.

INSERT INTO permissions (code, description) VALUES
    ('PATIENT_LINK_REQUEST', 'Request that a duplicate patient record be linked to the record in use'),
    ('PATIENT_LINK_APPROVE', 'Approve, reject or reverse patient record links (not your own requests)');

-- ---------------------------------------------------------------------------
-- Retired records point to the surviving record. LINKED <=> pointer set.
-- ---------------------------------------------------------------------------
ALTER TABLE patients ADD COLUMN linked_to_patient_id uuid;
ALTER TABLE patients
    ADD CONSTRAINT fk_patients_linked_to FOREIGN KEY (organization_id, linked_to_patient_id)
        REFERENCES patients (organization_id, id),
    ADD CONSTRAINT ck_patients_linked CHECK ((status = 'LINKED') = (linked_to_patient_id IS NOT NULL)),
    ADD CONSTRAINT ck_patients_not_linked_to_self CHECK (linked_to_patient_id <> id);
CREATE INDEX ix_patients_linked_to ON patients (linked_to_patient_id) WHERE linked_to_patient_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- One row per link request: request, decision and (if reversed) unlink, each with actor, time
-- and reason. Rows are never deleted. The two-person rule is also a database rule.
-- ---------------------------------------------------------------------------
CREATE TABLE patient_links (
    id                    uuid         PRIMARY KEY,
    organization_id       uuid         NOT NULL,
    retired_patient_id    uuid         NOT NULL,
    surviving_patient_id  uuid         NOT NULL,
    flag_id               uuid         REFERENCES patient_duplicate_flags (id),
    status                varchar(20)  NOT NULL CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'UNLINKED')),
    request_reason        text         NOT NULL CHECK (length(trim(request_reason)) > 0),
    requested_by          uuid         NOT NULL,
    requested_at          timestamptz  NOT NULL DEFAULT now(),
    decided_by            uuid,
    decided_at            timestamptz,
    decision_note         text,
    override_reason       text,
    unlinked_by           uuid,
    unlinked_at           timestamptz,
    unlink_reason         text,
    version               bigint       NOT NULL DEFAULT 0,
    CONSTRAINT fk_patient_links_retired FOREIGN KEY (organization_id, retired_patient_id)
        REFERENCES patients (organization_id, id),
    CONSTRAINT fk_patient_links_surviving FOREIGN KEY (organization_id, surviving_patient_id)
        REFERENCES patients (organization_id, id),
    CONSTRAINT fk_patient_links_requested_by FOREIGN KEY (organization_id, requested_by)
        REFERENCES users (organization_id, id),
    CONSTRAINT fk_patient_links_decided_by FOREIGN KEY (organization_id, decided_by)
        REFERENCES users (organization_id, id),
    CONSTRAINT fk_patient_links_unlinked_by FOREIGN KEY (organization_id, unlinked_by)
        REFERENCES users (organization_id, id),
    CONSTRAINT ck_patient_links_distinct CHECK (retired_patient_id <> surviving_patient_id),
    CONSTRAINT ck_patient_links_two_person CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT ck_patient_links_override CHECK (override_reason IS NULL OR length(trim(override_reason)) > 0),
    CONSTRAINT ck_patient_links_state CHECK (
        (status = 'REQUESTED'
            AND decided_by IS NULL AND decided_at IS NULL AND decision_note IS NULL
            AND unlinked_by IS NULL AND unlinked_at IS NULL AND unlink_reason IS NULL)
        OR (status IN ('APPROVED', 'REJECTED', 'UNLINKED')
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decision_note IS NOT NULL AND length(trim(decision_note)) > 0
            AND ((status = 'UNLINKED') = (unlinked_by IS NOT NULL))
            AND ((status = 'UNLINKED') = (unlinked_at IS NOT NULL))
            AND ((status = 'UNLINKED') = (unlink_reason IS NOT NULL AND length(trim(unlink_reason)) > 0))))
);

-- A record can have at most one pending or active link as the retired side.
CREATE UNIQUE INDEX uq_patient_links_live ON patient_links (retired_patient_id)
    WHERE status IN ('REQUESTED', 'APPROVED');
CREATE INDEX ix_patient_links_surviving ON patient_links (surviving_patient_id);
CREATE INDEX ix_patient_links_status ON patient_links (organization_id, status);

ALTER TABLE patient_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE patient_links FORCE ROW LEVEL SECURITY;
CREATE POLICY patient_links_isolation ON patient_links
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT, UPDATE ON patient_links TO gcare_app;
