-- Phase 5-6 review fixes (2026-11-23; docs/27).
--
-- Imaging: when the archive changes a study's accession number or patient ID after a link was proposed or made, the
-- matcher returns the study to the review queue and records ARCHIVE_CHANGED (no user: the automatic matcher).
ALTER TABLE imaging_study_events DROP CONSTRAINT imaging_study_events_event_type_check;
ALTER TABLE imaging_study_events ADD CONSTRAINT ck_imaging_study_events_type CHECK (
    event_type IN ('DISCOVERED', 'AUTO_LINKED', 'PROPOSED', 'SENT_BACK', 'LINKED', 'UNLINKED', 'ARCHIVE_CHANGED'));
ALTER TABLE imaging_study_events DROP CONSTRAINT ck_imaging_study_events_actor;
ALTER TABLE imaging_study_events ADD CONSTRAINT ck_imaging_study_events_actor CHECK (
    by_user IS NOT NULL OR event_type IN ('DISCOVERED', 'AUTO_LINKED', 'ARCHIVE_CHANGED'));
