-- Synthetic seed data for LOCAL development only (loaded by the "local" profile).
-- Every value here is fictional. Never add real organizations, staff or patients.
-- Repeatable migration: must stay idempotent.

INSERT INTO organizations (id, code, name, country_code, status, hosting_model)
VALUES ('00000000-0000-4000-8000-000000000001', 'DEMO-ORG', 'Demo Health Organization (synthetic)', 'LR', 'ACTIVE', 'DEDICATED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO facilities (id, organization_id, code, name, address, timezone, status)
VALUES ('00000000-0000-4000-8000-000000000101', '00000000-0000-4000-8000-000000000001',
        'DEMO-GH', 'Demo General Hospital (synthetic)', '1 Example Road (fictional)', 'Africa/Monrovia', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO departments (id, organization_id, facility_id, code, name, status)
VALUES
    ('00000000-0000-4000-8000-000000000201', '00000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000101', 'REG', 'Registration', 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000202', '00000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000101', 'OPD', 'Outpatient Department', 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000203', '00000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000101', 'BILL', 'Billing and Cashier', 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000204', '00000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000101', 'RAD', 'Radiology', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;
