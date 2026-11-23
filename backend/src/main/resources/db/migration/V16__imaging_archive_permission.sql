-- V16: permission for checking the imaging archive and looking up studies (task 6.1). ADR-004, OD-14.
-- Decisions (user, 2026-11-23; docs/24): the archive is external and not selected yet; the HMS
-- reads study metadata over DICOMweb and opens the archive's viewer by link. No new tables: DICOM
-- payloads never enter PostgreSQL.

INSERT INTO permissions (code, description) VALUES
    ('IMAGING_ARCHIVE_ADMIN', 'Check the imaging archive connection and look up studies by accession number (metadata only; audited)');
