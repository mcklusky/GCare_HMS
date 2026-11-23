# Flyway migrations

Schema is owned here; Hibernate only validates it (`ddl-auto: validate`).

- Name files `V<n>__<description>.sql` (e.g. `V1__organization_foundation.sql`). Never edit a migration after it is merged; add a new one.
- V1 (task 1.2) creates the organization scope and audit trail. Follow OD-01 (`organization` / `organization_id` naming) and OD-02 (`organization_id` on every tenant-owned table, enforced by PostgreSQL row-level security) in `docs/project-record/open-decisions.md`.
- V2 (task 1.3) adds users, roles, permissions, role assignments and the Keycloak identity link (`user_identities`, readable only through `app_find_user_by_identity()`). New permission codes go in a migration **and** `shared/security/Permissions.java` (a test compares them).
- V3 (task 2.1) adds patients, identifiers (with provenance), per-organization patient-number counters and the duplicate review flags. Uses `pg_trgm` for fuzzy names. Rules: docs/17-PATIENT-IDENTITY-POLICY.md (provisional).
- V4 (task 2.2) adds `patient_changes` (append-only correction history) and review decisions on `patient_duplicate_flags` (one open item per pair).
- V5 (task 2.3) adds `patient_links` (request, decision, unlink; requester and decider must differ, enforced by a CHECK) and `patients.linked_to_patient_id` (set exactly when status is LINKED).
- V7 (task 3.2) adds `clinical_notes` (a trigger refuses any update to a non-draft; column grant limits draft updates) and `encounter_diagnoses` (free text; empty code columns; only error columns updatable).
- V6 (task 3.1) adds `encounters` (one open visit per patient and facility; department must belong to the facility), `encounter_events` (append-only) and `triage_observations` (append-only; only the entered-in-error columns can be updated, by column grant).
- Every schema change ships with tests for its constraints, tenant isolation and audit behaviour.
