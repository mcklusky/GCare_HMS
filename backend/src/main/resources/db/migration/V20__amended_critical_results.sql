-- Amended critical reports (follow-up decision 2026-11-23; docs/26).
--
-- Following closed-loop critical-result practice (an amended result that may change care is communicated
-- directly again and acknowledged; the earlier alert is not left open on its own):
--   * kind CRITICAL: a report signed as critical (as before);
--   * kind CHANGED: an amendment, no longer critical, of a report that carried a critical result. The change
--     was communicated directly and must be acknowledged like a critical result;
--   * an open (unacknowledged) result is superseded when its report is amended and the amendment signed: the
--     clinician acknowledges the latest one. Superseding is set once and never changes.
ALTER TABLE critical_results
    ADD COLUMN kind           varchar(10)  NOT NULL DEFAULT 'CRITICAL' CHECK (kind IN ('CRITICAL', 'CHANGED')),
    ADD COLUMN superseded_by  uuid,
    ADD COLUMN superseded_at  timestamptz,
    ADD CONSTRAINT fk_critical_results_superseded_by FOREIGN KEY (organization_id, superseded_by)
        REFERENCES radiology_reports (organization_id, id),
    ADD CONSTRAINT ck_critical_results_superseded CHECK ((superseded_by IS NULL) = (superseded_at IS NULL)),
    ADD CONSTRAINT ck_critical_results_closed_once CHECK (superseded_at IS NULL OR acknowledged_at IS NULL);

DROP INDEX ix_critical_results_open;
CREATE INDEX ix_critical_results_open ON critical_results (organization_id, facility_id)
    WHERE acknowledged_at IS NULL AND superseded_at IS NULL;

-- The communication record never changes; acknowledgement or superseding happens once, never both.
CREATE OR REPLACE FUNCTION app_critical_result_checks() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.acknowledged_at IS NOT NULL
            OR OLD.superseded_at IS NOT NULL
            OR NEW.kind IS DISTINCT FROM OLD.kind
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

GRANT UPDATE (superseded_by, superseded_at) ON critical_results TO gcare_app;
