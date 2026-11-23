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

-- ---------------------------------------------------------------------------
-- Registration (task 2.1). demo.clerk is a REGISTRATION_CLERK at DEMO-GH.
-- Patients are obviously fictional. Numbers follow docs/17 (Luhn check digit).
-- ---------------------------------------------------------------------------
INSERT INTO roles (id, organization_id, code, name, description, is_system_role, status)
VALUES ('00000000-0000-4000-8000-000000000303', '00000000-0000-4000-8000-000000000001', 'REGISTRATION_CLERK', 'Registration clerk',
        'Searches, registers and corrects patients at assigned facilities. No clinical access.', true, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO role_permissions (organization_id, role_id, permission_code)
VALUES
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000303', 'PATIENT_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000303', 'PATIENT_REGISTER')
ON CONFLICT DO NOTHING;

INSERT INTO users (id, organization_id, username, display_name, email, status)
VALUES ('00000000-0000-4000-8000-000000000404', '00000000-0000-4000-8000-000000000001', 'demo.clerk', 'Demo Registration Clerk', 'demo.clerk@example.invalid', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_identities (id, organization_id, user_id, issuer, subject)
VALUES ('00000000-0000-4000-8000-000000000504', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000404', 'http://localhost:8180/realms/gcare',
        '00000000-0000-4000-8000-000000001005')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_role_assignments (id, organization_id, user_id, role_id, facility_id)
VALUES ('00000000-0000-4000-8000-000000000603', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000404', '00000000-0000-4000-8000-000000000303', '00000000-0000-4000-8000-000000000101')
ON CONFLICT (id) DO NOTHING;

INSERT INTO patients (id, organization_id, patient_number, registration_facility_id, status, family_name,
    given_names, family_name_norm, given_names_norm, birth_date, birth_date_estimated, sex, phone,
    phone_norm, details_incomplete, registration_source, created_by)
VALUES
    ('00000000-0000-4000-8000-000000000701', '00000000-0000-4000-8000-000000000001', 'P1000001-6', '00000000-0000-4000-8000-000000000101', 'ACTIVE', 'Testperson', 'Alpha',
     'testperson', 'alpha', '1985-03-02', false, 'FEMALE', '0000000001', '000000001', false, 'WALK_IN', '00000000-0000-4000-8000-000000000404'),
    ('00000000-0000-4000-8000-000000000702', '00000000-0000-4000-8000-000000000001', 'P1000002-4', '00000000-0000-4000-8000-000000000101', 'ACTIVE', 'Testperson', 'Bravo',
     'testperson', 'bravo', '2010-11-20', false, 'MALE', '0000000001', '000000001', false, 'WALK_IN', '00000000-0000-4000-8000-000000000404'),
    ('00000000-0000-4000-8000-000000000703', '00000000-0000-4000-8000-000000000001', 'P1000003-2', '00000000-0000-4000-8000-000000000101', 'ACTIVE', NULL, NULL,
     NULL, NULL, '1970-01-01', true, 'UNKNOWN', NULL, NULL, true, 'EMERGENCY', '00000000-0000-4000-8000-000000000404')
ON CONFLICT (id) DO NOTHING;

INSERT INTO patient_identifiers (id, organization_id, patient_id, identifier_type, value, value_norm,
    issuer, verified, status, source, created_by)
VALUES ('00000000-0000-4000-8000-000000000801', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000701',
        'NATIONAL_ID', 'TEST-0000-0001', 'TEST00000001', 'Fictional issuer', true, 'ACTIVE', 'REGISTRATION', '00000000-0000-4000-8000-000000000404')
ON CONFLICT (id) DO NOTHING;

INSERT INTO patient_number_counters (organization_id, next_value)
VALUES ('00000000-0000-4000-8000-000000000001', 1000004)
ON CONFLICT (organization_id) DO UPDATE SET next_value = GREATEST(patient_number_counters.next_value, 1000004);

-- ---------------------------------------------------------------------------
-- Correction and review (task 2.2). Clerk may correct; demo.supervisor reviews duplicates.
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (organization_id, role_id, permission_code)
VALUES ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000303', 'PATIENT_UPDATE')
ON CONFLICT DO NOTHING;

INSERT INTO roles (id, organization_id, code, name, description, is_system_role, status)
VALUES ('00000000-0000-4000-8000-000000000304', '00000000-0000-4000-8000-000000000001', 'REGISTRATION_SUPERVISOR', 'Registration supervisor',
        'Registration clerk duties plus reviewing possible duplicate patients. No clinical access.', true, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO role_permissions (organization_id, role_id, permission_code)
VALUES
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000304', 'PATIENT_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000304', 'PATIENT_REGISTER'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000304', 'PATIENT_UPDATE'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000304', 'PATIENT_DUPLICATE_REVIEW')
ON CONFLICT DO NOTHING;

INSERT INTO users (id, organization_id, username, display_name, email, status)
VALUES ('00000000-0000-4000-8000-000000000405', '00000000-0000-4000-8000-000000000001', 'demo.supervisor', 'Demo Registration Supervisor', 'demo.supervisor@example.invalid', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_identities (id, organization_id, user_id, issuer, subject)
VALUES ('00000000-0000-4000-8000-000000000505', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000405', 'http://localhost:8180/realms/gcare',
        '00000000-0000-4000-8000-000000001006')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_role_assignments (id, organization_id, user_id, role_id, facility_id)
VALUES ('00000000-0000-4000-8000-000000000604', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000405', '00000000-0000-4000-8000-000000000304', '00000000-0000-4000-8000-000000000101')
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Linking (task 2.3). demo.supervisor may request links; demo.records (health records manager)
-- reviews, requests and approves. Two people are needed for every link (docs/17 §5).
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (organization_id, role_id, permission_code)
VALUES ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000304', 'PATIENT_LINK_REQUEST')
ON CONFLICT DO NOTHING;

INSERT INTO roles (id, organization_id, code, name, description, is_system_role, status)
VALUES ('00000000-0000-4000-8000-000000000305', '00000000-0000-4000-8000-000000000001', 'HEALTH_RECORDS_MANAGER', 'Health records manager',
        'Reviews possible duplicates and approves, rejects or reverses patient record links. No clinical access.', true, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO role_permissions (organization_id, role_id, permission_code)
VALUES
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000305', 'PATIENT_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000305', 'PATIENT_DUPLICATE_REVIEW'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000305', 'PATIENT_LINK_REQUEST'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000305', 'PATIENT_LINK_APPROVE')
ON CONFLICT DO NOTHING;

INSERT INTO users (id, organization_id, username, display_name, email, status)
VALUES ('00000000-0000-4000-8000-000000000406', '00000000-0000-4000-8000-000000000001', 'demo.records', 'Demo Health Records Manager', 'demo.records@example.invalid', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_identities (id, organization_id, user_id, issuer, subject)
VALUES ('00000000-0000-4000-8000-000000000506', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000406', 'http://localhost:8180/realms/gcare',
        '00000000-0000-4000-8000-000000001007')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_role_assignments (id, organization_id, user_id, role_id, facility_id)
VALUES ('00000000-0000-4000-8000-000000000605', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000406', '00000000-0000-4000-8000-000000000305', '00000000-0000-4000-8000-000000000101')
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------------
-- Encounters and triage (task 3.1). Clerks and supervisors open visits; demo.nurse records triage;
-- demo.clinician calls, transfers, closes and reopens. Roles are PROVISIONAL (docs/18).
-- ---------------------------------------------------------------------------
INSERT INTO role_permissions (organization_id, role_id, permission_code)
VALUES
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000303', 'ENCOUNTER_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000303', 'ENCOUNTER_OPEN'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000304', 'ENCOUNTER_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000304', 'ENCOUNTER_OPEN')
ON CONFLICT DO NOTHING;

INSERT INTO roles (id, organization_id, code, name, description, is_system_role, status)
VALUES
    ('00000000-0000-4000-8000-000000000306', '00000000-0000-4000-8000-000000000001', 'TRIAGE_NURSE', 'Triage nurse', 'Sees department queues and records triage observations.', true, 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000307', '00000000-0000-4000-8000-000000000001', 'CLINICIAN', 'Clinician', 'Sees patients from the department queue: calls, transfers, closes and reopens visits.', true, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO role_permissions (organization_id, role_id, permission_code)
VALUES
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000306', 'PATIENT_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000306', 'ENCOUNTER_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000306', 'OBSERVATION_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000306', 'OBSERVATION_RECORD'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000307', 'PATIENT_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000307', 'ENCOUNTER_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000307', 'ENCOUNTER_MANAGE'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000307', 'ENCOUNTER_REOPEN'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000307', 'OBSERVATION_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000307', 'OBSERVATION_RECORD')
ON CONFLICT DO NOTHING;

INSERT INTO users (id, organization_id, username, display_name, email, status)
VALUES
    ('00000000-0000-4000-8000-000000000407', '00000000-0000-4000-8000-000000000001', 'demo.nurse', 'Demo Triage Nurse', 'demo.nurse@example.invalid', 'ACTIVE'),
    ('00000000-0000-4000-8000-000000000408', '00000000-0000-4000-8000-000000000001', 'demo.clinician', 'Demo Clinician', 'demo.clinician@example.invalid', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_identities (id, organization_id, user_id, issuer, subject)
VALUES
    ('00000000-0000-4000-8000-000000000507', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000407', 'http://localhost:8180/realms/gcare', '00000000-0000-4000-8000-000000001008'),
    ('00000000-0000-4000-8000-000000000508', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000408', 'http://localhost:8180/realms/gcare', '00000000-0000-4000-8000-000000001009')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_role_assignments (id, organization_id, user_id, role_id, facility_id)
VALUES
    ('00000000-0000-4000-8000-000000000606', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000407', '00000000-0000-4000-8000-000000000306', '00000000-0000-4000-8000-000000000101'),
    ('00000000-0000-4000-8000-000000000607', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000408', '00000000-0000-4000-8000-000000000307', '00000000-0000-4000-8000-000000000101')
ON CONFLICT (id) DO NOTHING;

-- Clinical notes (task 3.2): nurses read signed notes; clinicians write, sign and amend.
INSERT INTO role_permissions (organization_id, role_id, permission_code)
VALUES
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000306', 'CLINICAL_NOTE_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000307', 'CLINICAL_NOTE_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000307', 'CLINICAL_NOTE_WRITE')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- Orders (task 3.3). The admin manages the catalog; clinicians order; demo.lab works the
-- laboratory worklist. Catalog items below are FICTIONAL demo entries, not a real test list.
-- ---------------------------------------------------------------------------
INSERT INTO departments (id, organization_id, facility_id, code, name, status)
VALUES ('00000000-0000-4000-8000-000000000205', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000101', 'LAB', 'Laboratory', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO role_permissions (organization_id, role_id, permission_code)
VALUES
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000301', 'ORDER_CATALOG_MANAGE'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000306', 'ORDER_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000307', 'ORDER_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000307', 'ORDER_CREATE')
ON CONFLICT DO NOTHING;

INSERT INTO roles (id, organization_id, code, name, description, is_system_role, status)
VALUES ('00000000-0000-4000-8000-000000000308', '00000000-0000-4000-8000-000000000001', 'SERVICE_DEPARTMENT_STAFF', 'Service department staff',
        'Sees the order worklist and starts, completes or cancels orders. No access to notes.', true, 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO role_permissions (organization_id, role_id, permission_code)
VALUES
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000308', 'PATIENT_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000308', 'ORDER_READ'),
    ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000308', 'ORDER_PROCESS')
ON CONFLICT DO NOTHING;

INSERT INTO users (id, organization_id, username, display_name, email, status)
VALUES ('00000000-0000-4000-8000-000000000409', '00000000-0000-4000-8000-000000000001', 'demo.lab', 'Demo Laboratory Staff', 'demo.lab@example.invalid', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_identities (id, organization_id, user_id, issuer, subject)
VALUES ('00000000-0000-4000-8000-000000000509', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000409', 'http://localhost:8180/realms/gcare', '00000000-0000-4000-8000-000000001010')
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_role_assignments (id, organization_id, user_id, role_id, facility_id)
VALUES ('00000000-0000-4000-8000-000000000608', '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000409', '00000000-0000-4000-8000-000000000308', '00000000-0000-4000-8000-000000000101')
ON CONFLICT (id) DO NOTHING;

INSERT INTO order_catalog_items (id, organization_id, code, name, category, performing_department_id, status, created_by)
VALUES
    ('00000000-0000-4000-8000-000000000801', '00000000-0000-4000-8000-000000000001', 'DEMO-LAB-A', 'Demo lab test A (fictional)', 'LAB', '00000000-0000-4000-8000-000000000205', 'ACTIVE', '00000000-0000-4000-8000-000000000401'),
    ('00000000-0000-4000-8000-000000000802', '00000000-0000-4000-8000-000000000001', 'DEMO-IMG-B', 'Demo imaging study B (fictional)', 'IMAGING', '00000000-0000-4000-8000-000000000204', 'ACTIVE', '00000000-0000-4000-8000-000000000401'),
    ('00000000-0000-4000-8000-000000000803', '00000000-0000-4000-8000-000000000001', 'DEMO-REF-C', 'Demo referral C (fictional)', 'REFERRAL', NULL, 'ACTIVE', '00000000-0000-4000-8000-000000000401')
ON CONFLICT (id) DO NOTHING;
