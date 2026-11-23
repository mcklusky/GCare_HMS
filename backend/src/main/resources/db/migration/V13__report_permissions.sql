-- V13: permissions for operational reports, revenue reports and CSV export (task 5.1). FR-024, FR-025.
-- Decisions (user, 2026-11-23; docs/20): reports are aggregates computed from existing tables, by
-- facility-local day; exports contain the same aggregates (no patient names or numbers) and are
-- audited. No new tables: reports read the existing, row-level-secured tables.

INSERT INTO permissions (code, description) VALUES
    ('REPORT_OPERATIONS_READ', 'See visit, order and registration reports and the dashboard for facilities in scope'),
    ('REPORT_REVENUE_READ',    'See revenue, payment-method and cash-shift reports for facilities in scope'),
    ('REPORT_EXPORT',          'Download the reports you can see as CSV (aggregates only; every export is audited)');
