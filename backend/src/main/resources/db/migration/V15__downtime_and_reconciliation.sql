-- V15: downtime episodes, pre-numbered paper forms and the reconciliation queue (task 5.3). FR-027,
-- FR-035 (partial). Decisions (user, 2026-11-23; docs/22):
--   * paper forms: registration + visit, triage + vitals, orders, payments; printed in advance with
--     unique numbers per facility and form type;
--   * after the outage each paper form is keyed into a queue; nothing becomes a real record until it
--     is reconciled through the normal services (duplicate checks, permissions, audit);
--   * registrations (patient matching), orders and payments need a second person: one proposes, a
--     different person approves; triage and vitals need one person;
--   * a downtime coordinator (DOWNTIME_MANAGE) declares, ends and closes episodes and issues forms.
-- Real records keep the time they were entered in the system; the paper time stays on the entry.

INSERT INTO permissions (code, description) VALUES
    ('DOWNTIME_MANAGE', 'Declare, end and close downtime episodes and issue numbered paper forms'),
    ('DOWNTIME_RECORD', 'Key paper downtime forms and propose or reconcile them (reconciling also needs the permission for the record it creates)');

-- ---------------------------------------------------------------------------
-- Downtime episodes: ACTIVE (ended_at NULL) → ENDED → CLOSED (queue empty, closing note).
-- Usually declared after recovery, so started_at may lie in the past.
-- ---------------------------------------------------------------------------
CREATE TABLE downtime_episodes (
    id               uuid          PRIMARY KEY,
    organization_id  uuid          NOT NULL REFERENCES organizations (id),
    facility_id      uuid          NOT NULL,
    episode_number   integer       NOT NULL CHECK (episode_number > 0),
    started_at       timestamptz   NOT NULL,
    ended_at         timestamptz,
    affected_areas   text          NOT NULL CHECK (length(trim(affected_areas)) > 0),
    reason           text          NOT NULL CHECK (length(trim(reason)) > 0),
    status           varchar(20)   NOT NULL CHECK (status IN ('ACTIVE', 'ENDED', 'CLOSED')),
    declared_by      uuid          NOT NULL,
    declared_at      timestamptz   NOT NULL DEFAULT now(),
    ended_by         uuid,
    closed_by        uuid,
    closed_at        timestamptz,
    closing_note     text,
    version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_downtime_episodes_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_downtime_episodes_number UNIQUE (organization_id, facility_id, episode_number),
    CONSTRAINT fk_downtime_episodes_facility FOREIGN KEY (organization_id, facility_id)
        REFERENCES facilities (organization_id, id),
    CONSTRAINT fk_downtime_episodes_declared_by FOREIGN KEY (organization_id, declared_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_downtime_episodes_ended_by FOREIGN KEY (organization_id, ended_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_downtime_episodes_closed_by FOREIGN KEY (organization_id, closed_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_downtime_episodes_window CHECK (ended_at IS NULL OR ended_at > started_at),
    CONSTRAINT ck_downtime_episodes_status CHECK (
        (status = 'ACTIVE' AND ended_at IS NULL AND ended_by IS NULL AND closed_by IS NULL)
        OR (status = 'ENDED' AND ended_at IS NOT NULL AND ended_by IS NOT NULL AND closed_by IS NULL)
        OR (status = 'CLOSED' AND ended_at IS NOT NULL AND closed_by IS NOT NULL AND closed_at IS NOT NULL
            AND closing_note IS NOT NULL AND length(trim(closing_note)) > 0))
);
CREATE UNIQUE INDEX uq_downtime_episodes_active ON downtime_episodes (organization_id, facility_id)
    WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Numbered paper forms. Numbers are issued in batches per facility and form type and never reused.
-- ---------------------------------------------------------------------------
CREATE TABLE downtime_form_counters (
    organization_id  uuid          NOT NULL,
    facility_id      uuid          NOT NULL,
    form_type        varchar(20)   NOT NULL CHECK (form_type IN ('REGISTRATION', 'TRIAGE', 'ORDER', 'PAYMENT')),
    last_number      integer       NOT NULL CHECK (last_number > 0),
    PRIMARY KEY (organization_id, facility_id, form_type),
    CONSTRAINT fk_downtime_form_counters_facility FOREIGN KEY (organization_id, facility_id)
        REFERENCES facilities (organization_id, id)
);

CREATE TABLE downtime_form_batches (
    id               uuid          PRIMARY KEY,
    organization_id  uuid          NOT NULL,
    facility_id      uuid          NOT NULL,
    form_type        varchar(20)   NOT NULL CHECK (form_type IN ('REGISTRATION', 'TRIAGE', 'ORDER', 'PAYMENT')),
    first_number     integer       NOT NULL CHECK (first_number > 0),
    last_number      integer       NOT NULL,
    issued_by        uuid          NOT NULL,
    issued_at        timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fk_downtime_form_batches_facility FOREIGN KEY (organization_id, facility_id)
        REFERENCES facilities (organization_id, id),
    CONSTRAINT fk_downtime_form_batches_issued_by FOREIGN KEY (organization_id, issued_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_downtime_form_batches_range CHECK (last_number BETWEEN first_number AND first_number + 499)
);
CREATE INDEX ix_downtime_form_batches ON downtime_form_batches (facility_id, form_type, first_number);

-- ---------------------------------------------------------------------------
-- One row per keyed paper form. The keyed data never changes; a wrongly keyed form is voided (with
-- a reason) and keyed again. PENDING → PROPOSED → RECONCILED | VOIDED (or back to PENDING when the
-- approver sends a proposal back). TRIAGE goes PENDING → RECONCILED | VOIDED by one person.
-- ---------------------------------------------------------------------------
CREATE TABLE downtime_entries (
    id                        uuid          PRIMARY KEY,
    organization_id           uuid          NOT NULL,
    facility_id               uuid          NOT NULL,
    episode_id                uuid          NOT NULL,
    form_type                 varchar(20)   NOT NULL CHECK (form_type IN ('REGISTRATION', 'TRIAGE', 'ORDER', 'PAYMENT')),
    form_number               integer       NOT NULL CHECK (form_number > 0),
    registration_form_number  integer       CHECK (registration_form_number > 0),
    occurred_at               timestamptz   NOT NULL,
    data                      jsonb         NOT NULL,
    status                    varchar(20)   NOT NULL CHECK (status IN ('PENDING', 'PROPOSED', 'RECONCILED', 'VOIDED')),
    entered_by                uuid          NOT NULL,
    entered_at                timestamptz   NOT NULL DEFAULT now(),
    proposal                  jsonb,
    proposed_by               uuid,
    proposed_at               timestamptz,
    proposal_note             text,
    decided_by                uuid,
    decided_at                timestamptz,
    decision_note             text,
    patient_id                uuid,
    encounter_id              uuid,
    record_id                 uuid,
    version                   bigint        NOT NULL DEFAULT 0,
    CONSTRAINT fk_downtime_entries_episode FOREIGN KEY (organization_id, episode_id)
        REFERENCES downtime_episodes (organization_id, id),
    CONSTRAINT fk_downtime_entries_facility FOREIGN KEY (organization_id, facility_id)
        REFERENCES facilities (organization_id, id),
    CONSTRAINT fk_downtime_entries_entered_by FOREIGN KEY (organization_id, entered_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_downtime_entries_proposed_by FOREIGN KEY (organization_id, proposed_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_downtime_entries_decided_by FOREIGN KEY (organization_id, decided_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_downtime_entries_link CHECK ((form_type = 'REGISTRATION') = (registration_form_number IS NULL)),
    CONSTRAINT ck_downtime_entries_two_person CHECK (decided_by IS NULL OR proposed_by IS NULL OR decided_by <> proposed_by),
    -- Registrations, orders and payments are decided only on a proposal by another person.
    CONSTRAINT ck_downtime_entries_high_risk CHECK (
        form_type = 'TRIAGE' OR status NOT IN ('RECONCILED', 'VOIDED') OR proposed_by IS NOT NULL),
    CONSTRAINT ck_downtime_entries_state CHECK (
        (status = 'PENDING' AND proposal IS NULL AND proposed_by IS NULL AND decided_by IS NULL)
        OR (status = 'PROPOSED' AND proposal IS NOT NULL AND proposed_by IS NOT NULL AND proposed_at IS NOT NULL
            AND decided_by IS NULL)
        OR (status IN ('RECONCILED', 'VOIDED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL
            AND decision_note IS NOT NULL AND length(trim(decision_note)) > 0)),
    CONSTRAINT ck_downtime_entries_result CHECK (
        status <> 'RECONCILED' OR (encounter_id IS NOT NULL AND patient_id IS NOT NULL
            AND (form_type = 'REGISTRATION' OR record_id IS NOT NULL)))
);
-- A paper form is keyed once (a voided keying may be keyed again).
CREATE UNIQUE INDEX uq_downtime_entries_form ON downtime_entries (organization_id, facility_id, form_type, form_number)
    WHERE status <> 'VOIDED';
CREATE INDEX ix_downtime_entries_episode ON downtime_entries (episode_id, status);
CREATE INDEX ix_downtime_entries_encounter ON downtime_entries (encounter_id) WHERE encounter_id IS NOT NULL;

-- Keyed data never changes; decided entries are final.
CREATE FUNCTION app_downtime_entry_checks() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.data IS DISTINCT FROM OLD.data
            OR NEW.form_type IS DISTINCT FROM OLD.form_type
            OR NEW.form_number IS DISTINCT FROM OLD.form_number
            OR NEW.registration_form_number IS DISTINCT FROM OLD.registration_form_number
            OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
            OR NEW.episode_id IS DISTINCT FROM OLD.episode_id
            OR NEW.entered_by IS DISTINCT FROM OLD.entered_by
            OR OLD.status IN ('RECONCILED', 'VOIDED') THEN
        RAISE EXCEPTION 'downtime entry % (%) cannot change this way', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_downtime_entries_checks BEFORE UPDATE ON downtime_entries
    FOR EACH ROW EXECUTE FUNCTION app_downtime_entry_checks();

-- Append-only history of every step (keyed, proposed, sent back, reconciled, voided).
CREATE TABLE downtime_entry_events (
    id               uuid          PRIMARY KEY,
    organization_id  uuid          NOT NULL,
    entry_id         uuid          NOT NULL REFERENCES downtime_entries (id),
    event_type       varchar(20)   NOT NULL CHECK (event_type IN ('ENTERED', 'PROPOSED', 'SENT_BACK', 'RECONCILED', 'VOIDED')),
    note             text,
    by_user          uuid          NOT NULL,
    occurred_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fk_downtime_entry_events_by FOREIGN KEY (organization_id, by_user) REFERENCES users (organization_id, id)
);
CREATE INDEX ix_downtime_entry_events_entry ON downtime_entry_events (entry_id, occurred_at);

-- ---------------------------------------------------------------------------
-- Isolation and grants. No DELETE anywhere.
-- ---------------------------------------------------------------------------
ALTER TABLE downtime_episodes ENABLE ROW LEVEL SECURITY;
ALTER TABLE downtime_episodes FORCE ROW LEVEL SECURITY;
CREATE POLICY downtime_episodes_isolation ON downtime_episodes
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE downtime_form_counters ENABLE ROW LEVEL SECURITY;
ALTER TABLE downtime_form_counters FORCE ROW LEVEL SECURITY;
CREATE POLICY downtime_form_counters_isolation ON downtime_form_counters
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE downtime_form_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE downtime_form_batches FORCE ROW LEVEL SECURITY;
CREATE POLICY downtime_form_batches_isolation ON downtime_form_batches
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE downtime_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE downtime_entries FORCE ROW LEVEL SECURITY;
CREATE POLICY downtime_entries_isolation ON downtime_entries
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE downtime_entry_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE downtime_entry_events FORCE ROW LEVEL SECURITY;
CREATE POLICY downtime_entry_events_isolation ON downtime_entry_events
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT ON downtime_episodes TO gcare_app;
GRANT UPDATE (ended_at, ended_by, status, closed_by, closed_at, closing_note, version) ON downtime_episodes TO gcare_app;
GRANT SELECT, INSERT, UPDATE ON downtime_form_counters TO gcare_app;
GRANT SELECT, INSERT ON downtime_form_batches TO gcare_app;
GRANT SELECT, INSERT ON downtime_entries TO gcare_app;
GRANT UPDATE (status, proposal, proposed_by, proposed_at, proposal_note, decided_by, decided_at, decision_note,
              patient_id, encounter_id, record_id, version) ON downtime_entries TO gcare_app;
GRANT SELECT, INSERT ON downtime_entry_events TO gcare_app;
