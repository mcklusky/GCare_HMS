# Flyway migrations

Schema is owned here; Hibernate only validates it (`ddl-auto: validate`).

- Name files `V<n>__<description>.sql` (e.g. `V1__organization_foundation.sql`). Never edit a migration after it is merged; add a new one.
- V1 (task 1.2) creates the organization scope and audit trail. Follow OD-01 (`organization` / `organization_id` naming) and OD-02 (`organization_id` on every tenant-owned table, enforced by PostgreSQL row-level security) in `docs/project-record/open-decisions.md`.
- V2 (task 1.3) adds users, roles, permissions, role assignments and the Keycloak identity link (`user_identities`, readable only through `app_find_user_by_identity()`). New permission codes go in a migration **and** `shared/security/Permissions.java` (a test compares them).
- V3 (task 2.1) adds patients, identifiers (with provenance), per-organization patient-number counters and the duplicate review flags. Uses `pg_trgm` for fuzzy names. Rules: docs/17-PATIENT-IDENTITY-POLICY.md (provisional).
- Every schema change ships with tests for its constraints, tenant isolation and audit behaviour.
