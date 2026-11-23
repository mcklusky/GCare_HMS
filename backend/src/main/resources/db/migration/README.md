# Flyway migrations

Schema is owned here; Hibernate only validates it (`ddl-auto: validate`).

- Name files `V<n>__<description>.sql` (e.g. `V1__organization_foundation.sql`). Never edit a migration after it is merged; add a new one.
- V1 (task 1.2) creates the organization scope and audit trail. Follow OD-01 (`organization` / `organization_id` naming) and OD-02 (`organization_id` on every tenant-owned table, enforced by PostgreSQL row-level security) in `docs/project-record/open-decisions.md`.
- Every schema change ships with tests for its constraints, tenant isolation and audit behaviour.
