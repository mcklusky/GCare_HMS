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

-- ---------------------------------------------------------------------------
-- Identity and access (task 1.3). Users match the local Keycloak realm in
-- infra/keycloak/gcare-realm.local.json; issuer is the local Compose Keycloak.
-- System roles must match SystemRole.java (checked by LocalSeedTests).
-- ---------------------------------------------------------------------------
INSERT INTO roles (id, organization_id, code, name, description, is_system_role, status)
VALUES
    ('00000000-0000-4000-8000-000000000301', '00000000-0000-4000-8000-000000000001',
     'ORGANIZATION_ADMIN', 'Organization administrator',
     'Manages facilities, departments and user accounts. No clinical access.', true, 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000302', '00000000-0000-4000-8000-000000000001',
     'AUDITOR', 'Auditor', 'Reads the audit trail. Cannot change anything.', true, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO role_permissions (organization_id, role_id, permission_code)
VALUES
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000301', 'FACILITY_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000301', 'FACILITY_MANAGE'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000301', 'USER_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000301', 'USER_MANAGE'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000302', 'AUDIT_READ')
ON CONFLICT DO NOTHING;

INSERT INTO users (id, organization_id, username, display_name, email, status)
VALUES
    ('00000000-0000-4000-8000-000000000401', '00000000-0000-4000-8000-000000000001',
     'demo.admin', 'Demo Administrator', 'demo.admin@example.invalid', 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000402', '00000000-0000-4000-8000-000000000001',
     'demo.auditor', 'Demo Auditor', 'demo.auditor@example.invalid', 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000403', '00000000-0000-4000-8000-000000000001',
     'demo.unassigned', 'Demo Unassigned', 'demo.unassigned@example.invalid', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_identities (id, organization_id, user_id, issuer, subject)
VALUES
    ('00000000-0000-4000-8000-000000000501', '00000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000401', 'http://localhost:8180/realms/gcare',
     '00000000-0000-4000-8000-000000001001'),
    ('00000000-0000-4000-8000-000000000502', '00000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000402', 'http://localhost:8180/realms/gcare',
     '00000000-0000-4000-8000-000000001002'),
    ('00000000-0000-4000-8000-000000000503', '00000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000403', 'http://localhost:8180/realms/gcare',
     '00000000-0000-4000-8000-000000001003')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_role_assignments (id, organization_id, user_id, role_id)
VALUES
    ('00000000-0000-4000-8000-000000000601', '00000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000401', '00000000-0000-4000-8000-000000000301'),
    ('00000000-0000-4000-8000-000000000602', '00000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000402', '00000000-0000-4000-8000-000000000302')
ON CONFLICT (id) DO NOTHING;
