-- V18: radiology reports and critical-result communication (task 6.4). FR-012, FR-013.
-- Decisions (user, 2026-11-23; docs/26):
--   * a radiologist writes and signs; drafts are visible to radiologists only; clinicians see signed
--     reports. Signed reports never change: corrections are amendments with a reason (as notes);
--   * fixed free-text sections: technique, comparison (optional), findings, impression (required);
--   * a critical finding is flagged at signing with the documented direct contact (who, how, when);
--     a clinician acknowledges it in the HMS; unacknowledged ones are listed for the radiology lead.
--     No time limits or escalation rules are defined here (clinical governance sets them).

INSERT INTO permissions (code, description) VALUES
    ('RADIOLOGY_REPORT_WRITE',      'Write, sign and amend own radiology reports; see report drafts'),
    ('RADIOLOGY_REPORT_READ',       'Read signed radiology reports and open linked studies in the viewer'),
    ('CRITICAL_RESULT_ACKNOWLEDGE', 'Acknowledge critical radiology results for patients at facilities in scope'),
    ('CRITICAL_RESULT_OVERSIGHT',   'See every unacknowledged critical radiology result at facilities in scope');

CREATE TABLE radiology_reports (
    id                 uuid          PRIMARY KEY,
    organization_id    uuid          NOT NULL,
    facility_id        uuid          NOT NULL,
    order_id           uuid          NOT NULL,
    author_id          uuid          NOT NULL,
    status             varchar(20)   NOT NULL CHECK (status IN ('DRAFT', 'SIGNED', 'DISCARDED')),
    technique          text          CHECK (length(technique) <= 20000),
    comparison         text          CHECK (length(comparison) <= 20000),
    findings           text          CHECK (length(findings) <= 20000),
    impression         text          CHECK (length(impression) <= 20000),
    critical           boolean       NOT NULL DEFAULT false,
    amends_report_id   uuid,
    amendment_reason   text,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    signed_at          timestamptz,
    discarded_at       timestamptz,
    version            bigint        NOT NULL DEFAULT 0,
    CONSTRAINT uq_radiology_reports_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_radiology_reports_order FOREIGN KEY (organization_id, order_id)
        REFERENCES imaging_orders (organization_id, order_id),
    CONSTRAINT fk_radiology_reports_author FOREIGN KEY (organization_id, author_id) REFERENCES users (organization_id, id),
    CONSTRAINT fk_radiology_reports_amends FOREIGN KEY (organization_id, amends_report_id)
        REFERENCES radiology_reports (organization_id, id),
    CONSTRAINT ck_radiology_reports_amendment CHECK (
        (amends_report_id IS NULL) = (amendment_reason IS NULL)
        AND (amendment_reason IS NULL OR length(trim(amendment_reason)) > 0)),
    CONSTRAINT ck_radiology_reports_signed CHECK ((status = 'SIGNED') = (signed_at IS NOT NULL)),
    CONSTRAINT ck_radiology_reports_discarded CHECK ((status = 'DISCARDED') = (discarded_at IS NOT NULL)),
    CONSTRAINT ck_radiology_reports_signed_content CHECK (
        status <> 'SIGNED' OR (length(trim(coalesce(impression, ''))) > 0 AND length(trim(coalesce(findings, ''))) > 0))
);
-- One open draft per order; each signed report can be amended once (amend the latest one).
CREATE UNIQUE INDEX uq_radiology_reports_draft ON radiology_reports (order_id) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX uq_radiology_reports_amends ON radiology_reports (amends_report_id)
    WHERE amends_report_id IS NOT NULL AND status <> 'DISCARDED';
CREATE INDEX ix_radiology_reports_order ON radiology_reports (order_id, created_at);

-- Only drafts change.
CREATE FUNCTION app_radiology_report_immutable() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'DRAFT' OR NEW.author_id IS DISTINCT FROM OLD.author_id
            OR NEW.order_id IS DISTINCT FROM OLD.order_id
            OR NEW.amends_report_id IS DISTINCT FROM OLD.amends_report_id THEN
        RAISE EXCEPTION 'radiology report % is % and cannot be changed', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_radiology_report_immutable BEFORE UPDATE ON radiology_reports
    FOR EACH ROW EXECUTE FUNCTION app_radiology_report_immutable();

-- One row per signed report flagged critical: the documented contact, then the acknowledgement.
CREATE TABLE critical_results (
    report_id               uuid          PRIMARY KEY,
    organization_id         uuid          NOT NULL,
    facility_id             uuid          NOT NULL,
    order_id                uuid          NOT NULL,
    flagged_by              uuid          NOT NULL,
    flagged_at              timestamptz   NOT NULL DEFAULT now(),
    communicated_to         varchar(200)  NOT NULL CHECK (length(trim(communicated_to)) > 0),
    communication_method    varchar(20)   NOT NULL CHECK (communication_method IN ('PHONE', 'IN_PERSON', 'OTHER')),
    communicated_at         timestamptz   NOT NULL,
    communication_note      varchar(1000),
    acknowledged_by         uuid,
    acknowledged_at         timestamptz,
    acknowledgement_note    varchar(1000),
    version                 bigint        NOT NULL DEFAULT 0,
    CONSTRAINT fk_critical_results_report FOREIGN KEY (organization_id, report_id)
        REFERENCES radiology_reports (organization_id, id),
    CONSTRAINT fk_critical_results_flagged_by FOREIGN KEY (organization_id, flagged_by) REFERENCES users (organization_id, id),
    CONSTRAINT fk_critical_results_ack_by FOREIGN KEY (organization_id, acknowledged_by) REFERENCES users (organization_id, id),
    CONSTRAINT ck_critical_results_ack CHECK ((acknowledged_by IS NULL) = (acknowledged_at IS NULL))
);
CREATE INDEX ix_critical_results_open ON critical_results (organization_id, facility_id) WHERE acknowledged_at IS NULL;

-- The communication record never changes; the acknowledgement is set once.
CREATE FUNCTION app_critical_result_checks() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.acknowledged_at IS NOT NULL
            OR NEW.communicated_to IS DISTINCT FROM OLD.communicated_to
            OR NEW.communication_method IS DISTINCT FROM OLD.communication_method
            OR NEW.communicated_at IS DISTINCT FROM OLD.communicated_at
            OR NEW.communication_note IS DISTINCT FROM OLD.communication_note
            OR NEW.flagged_by IS DISTINCT FROM OLD.flagged_by THEN
        RAISE EXCEPTION 'critical result % cannot change this way', OLD.report_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_critical_results_checks BEFORE UPDATE ON critical_results
    FOR EACH ROW EXECUTE FUNCTION app_critical_result_checks();

ALTER TABLE radiology_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE radiology_reports FORCE ROW LEVEL SECURITY;
CREATE POLICY radiology_reports_isolation ON radiology_reports
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

ALTER TABLE critical_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE critical_results FORCE ROW LEVEL SECURITY;
CREATE POLICY critical_results_isolation ON critical_results
    USING (organization_id = app_current_organization_id())
    WITH CHECK (organization_id = app_current_organization_id());

GRANT SELECT, INSERT ON radiology_reports TO gcare_app;
GRANT UPDATE (technique, comparison, findings, impression, critical, status, signed_at, discarded_at, updated_at, version)
    ON radiology_reports TO gcare_app;
GRANT SELECT, INSERT ON critical_results TO gcare_app;
GRANT UPDATE (acknowledged_by, acknowledged_at, acknowledgement_note, version) ON critical_results TO gcare_app;
