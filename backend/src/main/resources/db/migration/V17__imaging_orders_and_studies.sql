-- V17: imaging orders, accession numbers and study linking (task 6.3). FR-010, FR-011 (partial).
-- Decisions (user, 2026-11-23; docs/24, docs/25):
--   * the HMS issues the accession number of every imaging order (ACC + 9 digits, ≤ 16 characters);
--   * radiographer steps: patient arrived → examination done; "images available" when a study links;
--   * studies found in the archive (scheduled check + on demand) link automatically only when the
--     accession number AND the patient number match exactly; everything else waits in a mismatch
--     queue where one radiographer proposes a link and a different one approves it;
--   * unlinking needs a reason. Only study metadata is stored; DICOM stays in the archive.

INSERT INTO permissions (code, description) VALUES
    ('IMAGING_WORKLIST', 'See the imaging worklist, record patient arrival and examination done, and open linked studies in the viewer'),
    ('IMAGING_LINK',     'Check the archive for studies and propose, approve (not your own) or undo links between studies and imaging orders');

CREATE TABLE imaging_accession_counters (
    organization_id  uuid    PRIMARY KEY REFERENCES organizations (id),
    last_number      bigint  NOT NULL CHECK (last_number > 0 AND last_number < 1000000000)
);

-- One row per imaging order (clinical order of category IMAGING).
CREATE TABLE imaging_orders (
    order_id          uuid          PRIMARY KEY,
    organization_id   uuid          NOT NULL,
    facility_id       uuid          NOT NULL,
    accession_number  varchar(16)   NOT NULL CHECK (accession_number ~ '^ACC[0-9]{9}$'),
    assigned_at       timestamptz   NOT NULL DEFAULT now(),
    arrived_at        timestamptz,
    arrived_by        uuid,
    examined_at       timestamptz,
    examined_by       uuid,
    version           bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_imaging_orders_org_order UNIQUE (organization_id, order_id),
    CONSTRAINT uq_imaging_orders_accession UNIQUE (organization_id, accession_number),
    CONSTRAINT fk_imaging_orders_order FOREIGN KEY (organization_id, order_id)
        REFERENCES clinical_orders (organization_id, id),
    CONSTRAINT fk_imaging_orders_arrived_by FOREIGN KEY (organization_id, arrived_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_imaging_orders_examined_by FOREIGN KEY (organization_id, examined_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_imaging_orders_arrived CHECK ((arrived_at IS NULL) = (arrived_by IS NULL)),
    CONSTRAINT ck_imaging_orders_examined CHECK (
        (examined_at IS NULL) = (examined_by IS NULL) AND (examined_at IS NULL OR arrived_at IS NOT NULL))
);

-- The accession number and recorded steps never change once set.
CREATE FUNCTION app_imaging_order_checks() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.accession_number IS DISTINCT FROM OLD.accession_number
            OR NEW.order_id IS DISTINCT FROM OLD.order_id
            OR (OLD.arrived_at IS NOT NULL AND NEW.arrived_at IS DISTINCT FROM OLD.arrived_at)
            OR (OLD.examined_at IS NOT NULL AND NEW.examined_at IS DISTINCT FROM OLD.examined_at) THEN
        RAISE EXCEPTION 'imaging order % cannot change this way', OLD.order_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_imaging_orders_checks BEFORE UPDATE ON imaging_orders
    FOR EACH ROW EXECUTE FUNCTION app_imaging_order_checks();

-- Studies seen in the archive (metadata only).
CREATE TABLE imaging_studies (
    id                   uuid          PRIMARY KEY,
    organization_id      uuid          NOT NULL,
    study_instance_uid   varchar(64)   NOT NULL CHECK (study_instance_uid ~ '^[0-9]+(\.[0-9]+)*$'),
    accession_number     varchar(64),
    dicom_patient_id     varchar(64),
    modalities           varchar(200),
    study_date           date,
    description          varchar(200),
    series_count         integer,
    instance_count       integer,
    first_seen_at        timestamptz   NOT NULL DEFAULT now(),
    last_seen_at         timestamptz   NOT NULL DEFAULT now(),
    status               varchar(20)   NOT NULL CHECK (status IN ('UNMATCHED', 'PROPOSED', 'LINKED')),
    order_id             uuid,
    link_method          varchar(10)   CHECK (link_method IN ('AUTOMATIC', 'REVIEWED')),
    linked_at            timestamptz,
    linked_by            uuid,
    proposed_order_id    uuid,
    proposed_by          uuid,
    proposed_at          timestamptz,
    proposal_note        text,
    version              bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_imaging_studies_uid UNIQUE (organization_id, study_instance_uid),
    CONSTRAINT fk_imaging_studies_order FOREIGN KEY (organization_id, order_id)
        REFERENCES imaging_orders (organization_id, order_id),
    CONSTRAINT fk_imaging_studies_proposed_order FOREIGN KEY (organization_id, proposed_order_id)
        REFERENCES imaging_orders (organization_id, order_id),
    CONSTRAINT fk_imaging_studies_linked_by FOREIGN KEY (organization_id, linked_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_imaging_studies_proposed_by FOREIGN KEY (organization_id, proposed_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_imaging_studies_state CHECK (
        (status = 'UNMATCHED' AND order_id IS NULL AND link_method IS NULL AND linked_at IS NULL AND linked_by IS NULL
            AND proposed_order_id IS NULL AND proposed_by IS NULL)
        OR (status = 'PROPOSED' AND order_id IS NULL AND link_method IS NULL AND proposed_order_id IS NOT NULL
            AND proposed_by IS NOT NULL AND proposed_at IS NOT NULL
            AND proposal_note IS NOT NULL AND length(trim(proposal_note)) > 0)
        OR (status = 'LINKED' AND order_id IS NOT NULL AND linked_at IS NOT NULL
            AND ((link_method = 'AUTOMATIC' AND linked_by IS NULL AND proposed_by IS NULL)
                 OR (link_method = 'REVIEWED' AND linked_by IS NOT NULL AND proposed_by IS NOT NULL
                     AND linked_by <> proposed_by AND proposed_order_id = order_id))))
);
CREATE INDEX ix_imaging_studies_status ON imaging_studies (organization_id, status);
CREATE INDEX ix_imaging_studies_order ON imaging_studies (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX ix_imaging_studies_accession ON imaging_studies (organization_id, accession_number);

-- Append-only history of every link decision.
CREATE TABLE imaging_study_events (
    id               uuid          PRIMARY KEY,
    organization_id  uuid          NOT NULL,
    study_id         uuid          NOT NULL REFERENCES imaging_studies (id),
    event_type       varchar(20)   NOT NULL CHECK (event_type IN ('DISCOVERED', 'AUTO_LINKED', 'PROPOSED', 'SENT_BACK', 'LINKED', 'UNLINKED')),
    order_id         uuid,
    note             text,
    by_user          uuid,          -- NULL: the automatic matcher
    occurred_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fk_imaging_study_events_by FOREIGN KEY (organization_id, by_user) REFERENCES users (organization_id, id),
    CONSTRAINT ck_imaging_study_events_actor CHECK (by_user IS NOT NULL OR event_type IN ('DISCOVERED', 'AUTO_LINKED'))
);
CREATE INDEX ix_imaging_study_events_study ON imaging_study_events (study_id, occurred_at);

ALTER TABLE imaging_accession_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE imaging_accession_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY imaging_accession_counters_isolation ON imaging_accession_counters
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE imaging_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE imaging_orders FORCE ROW LEVEL SECURITY;
CREATE POLICY imaging_orders_isolation ON imaging_orders
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE imaging_studies ENABLE ROW LEVEL SECURITY;
ALTER TABLE imaging_studies FORCE ROW LEVEL SECURITY;
CREATE POLICY imaging_studies_isolation ON imaging_studies
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE imaging_study_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE imaging_study_events FORCE ROW LEVEL SECURITY;
CREATE POLICY imaging_study_events_isolation ON imaging_study_events
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT, UPDATE ON imaging_accession_counters TO gcare_app;
GRANT SELECT, INSERT ON imaging_orders TO gcare_app;
GRANT UPDATE (arrived_at, arrived_by, examined_at, examined_by, version) ON imaging_orders TO gcare_app;
GRANT SELECT, INSERT ON imaging_studies TO gcare_app;
GRANT UPDATE (accession_number, dicom_patient_id, modalities, study_date, description, series_count, instance_count,
              last_seen_at, status, order_id, link_method, linked_at, linked_by, proposed_order_id, proposed_by,
              proposed_at, proposal_note, version) ON imaging_studies TO gcare_app;
GRANT SELECT, INSERT ON imaging_study_events TO gcare_app;
