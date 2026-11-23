-- V6: encounters, department queues and triage observations (task 3.1). FR-006, FR-007.
-- Workflow: docs/18-ENCOUNTER-WORKFLOW.md (PROVISIONAL). No clinical thresholds live here: the
-- triage urgency scale and alarm limits are configuration that a clinical lead must supply.
-- Nothing is deleted. Encounter history and observations are append-only; an observation can only
-- be marked "entered in error" (with reason), never changed.

INSERT INTO permissions (code, description) VALUES
    ('ENCOUNTER_READ',     'See encounters and department queues at facilities in scope'),
    ('ENCOUNTER_OPEN',     'Open a visit for a patient and cancel it before they are seen'),
    ('ENCOUNTER_MANAGE',   'Call a patient, transfer between departments and close a visit'),
    ('ENCOUNTER_REOPEN',   'Reopen a closed visit (with a reason)'),
    ('OBSERVATION_READ',   'See triage observations'),
    ('OBSERVATION_RECORD', 'Record triage observations and mark them entered in error');

-- Lets encounters prove their department belongs to their facility.
ALTER TABLE departments ADD CONSTRAINT uq_departments_org_facility_id UNIQUE (organization_id, facility_id, id);

-- ---------------------------------------------------------------------------
-- One row per visit. Status changes are also written to encounter_events.
-- ---------------------------------------------------------------------------
CREATE TABLE encounters (
    id                      uuid         PRIMARY KEY,
    organization_id         uuid         NOT NULL,
    facility_id             uuid         NOT NULL,
    department_id           uuid         NOT NULL,
    patient_id              uuid         NOT NULL,
    encounter_type          varchar(20)  NOT NULL CHECK (encounter_type IN ('NEW_VISIT', 'FOLLOW_UP')),
    status                  varchar(20)  NOT NULL CHECK (status IN ('WAITING', 'IN_CONSULTATION', 'CLOSED', 'CANCELLED')),
    clinician_id            uuid,
    arrived_at              timestamptz  NOT NULL DEFAULT now(),
    consultation_started_at timestamptz,
    ended_at                timestamptz,
    created_by              uuid         NOT NULL,
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    version                 bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_encounters_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_encounters_department FOREIGN KEY (organization_id, facility_id, department_id)
        REFERENCES departments (organization_id, facility_id, id),
    CONSTRAINT fk_encounters_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES patients (organization_id, id),
    CONSTRAINT fk_encounters_clinician FOREIGN KEY (organization_id, clinician_id)
        REFERENCES users (organization_id, id),
    CONSTRAINT fk_encounters_created_by FOREIGN KEY (organization_id, created_by)
        REFERENCES users (organization_id, id),
    CONSTRAINT ck_encounters_clinician CHECK (status <> 'IN_CONSULTATION' OR clinician_id IS NOT NULL),
    CONSTRAINT ck_encounters_ended CHECK ((status IN ('CLOSED', 'CANCELLED')) = (ended_at IS NOT NULL))
);

-- A patient has at most one open visit per facility.
CREATE UNIQUE INDEX uq_encounters_open_per_patient ON encounters (patient_id, facility_id)
    WHERE status IN ('WAITING', 'IN_CONSULTATION');
CREATE INDEX ix_encounters_queue ON encounters (facility_id, department_id, status, arrived_at);
CREATE INDEX ix_encounters_patient ON encounters (patient_id, arrived_at DESC);

-- ---------------------------------------------------------------------------
-- What happened to each visit, by whom, when and why. Append-only.
-- ---------------------------------------------------------------------------
CREATE TABLE encounter_events (
    id               uuid         PRIMARY KEY,
    organization_id  uuid         NOT NULL,
    encounter_id     uuid         NOT NULL,
    event_type       varchar(20)  NOT NULL CHECK (event_type IN ('OPENED', 'CALLED', 'TRANSFERRED', 'CLOSED', 'CANCELLED', 'REOPENED')),
    from_status      varchar(20),
    to_status        varchar(20)  NOT NULL,
    department_id    uuid         NOT NULL,
    reason           text,
    actor_id         uuid         NOT NULL,
    occurred_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fk_encounter_events_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES encounters (organization_id, id),
    CONSTRAINT fk_encounter_events_actor FOREIGN KEY (organization_id, actor_id)
        REFERENCES users (organization_id, id),
    CONSTRAINT ck_encounter_events_reason CHECK (
        event_type NOT IN ('TRANSFERRED', 'CANCELLED', 'REOPENED')
        OR (reason IS NOT NULL AND length(trim(reason)) > 0))
);
CREATE INDEX ix_encounter_events_encounter ON encounter_events (encounter_id, occurred_at);

-- ---------------------------------------------------------------------------
-- Triage observations. Fixed units (°C, /min, mmHg, %, kg, cm, pain 0–10). Flags are computed
-- with the alarm limits configured at the time of recording (none until a clinical lead sets them).
-- ---------------------------------------------------------------------------
CREATE TABLE triage_observations (
    id                 uuid          PRIMARY KEY,
    organization_id    uuid          NOT NULL,
    encounter_id       uuid          NOT NULL,
    patient_id         uuid          NOT NULL,
    temperature_c      numeric(4,1),
    pulse_bpm          integer,
    respiratory_rate   integer,
    systolic_mmhg      integer,
    diastolic_mmhg     integer,
    spo2_pct           integer,
    weight_kg          numeric(5,2),
    height_cm          numeric(4,1),
    pain_score         integer       CHECK (pain_score BETWEEN 0 AND 10),
    urgency            varchar(30),
    allergy_note       varchar(500),
    notes              varchar(1000),
    flags              jsonb         NOT NULL DEFAULT '[]'::jsonb,
    status             varchar(20)   NOT NULL DEFAULT 'RECORDED' CHECK (status IN ('RECORDED', 'ENTERED_IN_ERROR')),
    recorded_by        uuid          NOT NULL,
    recorded_at        timestamptz   NOT NULL DEFAULT now(),
    error_reason       text,
    error_by           uuid,
    error_at           timestamptz,
    CONSTRAINT fk_triage_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES encounters (organization_id, id),
    CONSTRAINT fk_triage_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES patients (organization_id, id),
    CONSTRAINT fk_triage_recorded_by FOREIGN KEY (organization_id, recorded_by)
        REFERENCES users (organization_id, id),
    CONSTRAINT fk_triage_error_by FOREIGN KEY (organization_id, error_by)
        REFERENCES users (organization_id, id),
    CONSTRAINT ck_triage_something_recorded CHECK (
        num_nonnulls(temperature_c, pulse_bpm, respiratory_rate, systolic_mmhg, diastolic_mmhg,
                     spo2_pct, weight_kg, height_cm, pain_score, urgency, allergy_note, notes) > 0),
    CONSTRAINT ck_triage_error CHECK (
        (status = 'RECORDED' AND error_reason IS NULL AND error_by IS NULL AND error_at IS NULL)
        OR (status = 'ENTERED_IN_ERROR' AND error_by IS NOT NULL AND error_at IS NOT NULL
            AND error_reason IS NOT NULL AND length(trim(error_reason)) > 0))
);
CREATE INDEX ix_triage_encounter ON triage_observations (encounter_id, recorded_at DESC);

-- ---------------------------------------------------------------------------
-- Isolation and grants. No DELETE anywhere; observations may only change their error columns.
-- ---------------------------------------------------------------------------
ALTER TABLE encounters ENABLE ROW LEVEL SECURITY;
ALTER TABLE encounters FORCE ROW LEVEL SECURITY;
CREATE POLICY encounters_isolation ON encounters
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE encounter_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE encounter_events FORCE ROW LEVEL SECURITY;
CREATE POLICY encounter_events_isolation ON encounter_events
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE triage_observations ENABLE ROW LEVEL SECURITY;
ALTER TABLE triage_observations FORCE ROW LEVEL SECURITY;
CREATE POLICY triage_observations_isolation ON triage_observations
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT, UPDATE ON encounters TO gcare_app;
GRANT SELECT, INSERT ON encounter_events TO gcare_app;
GRANT SELECT, INSERT ON triage_observations TO gcare_app;
GRANT UPDATE (status, error_reason, error_by, error_at) ON triage_observations TO gcare_app;
