# Flyway migrations

Schema is owned here; Hibernate only validates it (`ddl-auto: validate`).

- Name files `V<n>__<description>.sql` (e.g. `V1__organization_foundation.sql`). Never edit a migration after it is merged; add a new one.
- V1 (task 1.2) creates the organization scope and audit trail. Follow OD-01 (`organization` / `organization_id` naming) and OD-02 (`organization_id` on every tenant-owned table, enforced by PostgreSQL row-level security) in `docs/project-record/open-decisions.md`.
- V2 (task 1.3) adds users, roles, permissions, role assignments and the Keycloak identity link (`user_identities`, readable only through `app_find_user_by_identity()`). New permission codes go in a migration **and** `shared/security/Permissions.java` (a test compares them).
- V3 (task 2.1) adds patients, identifiers (with provenance), per-organization patient-number counters and the duplicate review flags. Uses `pg_trgm` for fuzzy names. Rules: docs/17-PATIENT-IDENTITY-POLICY.md (provisional).
- V4 (task 2.2) adds `patient_changes` (append-only correction history) and review decisions on `patient_duplicate_flags` (one open item per pair).
- V5 (task 2.3) adds `patient_links` (request, decision, unlink; requester and decider must differ, enforced by a CHECK) and `patients.linked_to_patient_id` (set exactly when status is LINKED).
- V18 (task 6.4) adds `radiology_reports` (only drafts change; amendments reference the amended report) and `critical_results` (documented contact never changes; acknowledgement set once).
- V17 (task 6.3) adds `imaging_orders` (HMS accession numbers, arrival/examination steps; immutable by trigger), `imaging_studies` (metadata only; two-person CHECK for reviewed links) and `imaging_study_events`.
- V16 (task 6.1) adds the `IMAGING_ARCHIVE_ADMIN` permission only; no imaging tables (DICOM stays in the external archive).
- V15 (task 5.3) adds `downtime_episodes`, numbered form counters and batches, `downtime_entries` (keyed data immutable by trigger, two-person CHECK for registration/order/payment, one live keying per form number) and `downtime_entry_events`.
- V14 (task 5.2) adds `config_versions` (jsonb values, two-person CHECK, one active and one pending per setting, values immutable by trigger) and `facility_logos` (PNG/JPEG ≤ 256 KiB).
- V13 (task 5.1) adds the report permissions only; reports read existing tables.
- V12 (task 4.4) adds `exemption_reasons` (admin-managed), `exemptions` and `refunds` (two-person CHECKs; decided exemptions and paid refunds immutable by trigger; paid-out refunds need an open shift) and extends the no-void trigger to approved exemptions.
- V11 (task 4.3) adds `payment_methods` (admin-managed), `cashier_shifts` (two-person sign-off CHECK), `shift_cash_counts`, `receipt_counters` and `payments` (insert-only; idempotency key unique; trigger: issued invoice + open shift; paid invoices cannot be voided).
- V10 (task 4.2) adds `invoices`, `invoice_lines` and `invoice_counters` (lines copy price and tax; issued invoices only voidable and lines frozen by trigger; gapless numbers per organization).
- V9 (task 4.1) adds `billable_services`, `service_prices` and `tax_rates` (no rows: entered by finance; decided rows immutable by trigger; two-person CHECK).
- V8 (task 3.3) adds `order_catalog_items` (no rows: admin-managed), `clinical_orders` (item snapshot; only lifecycle columns updatable) and `order_events` (append-only).
- V7 (task 3.2) adds `clinical_notes` (a trigger refuses any update to a non-draft; column grant limits draft updates) and `encounter_diagnoses` (free text; empty code columns; only error columns updatable).
- V6 (task 3.1) adds `encounters` (one open visit per patient and facility; department must belong to the facility), `encounter_events` (append-only) and `triage_observations` (append-only; only the entered-in-error columns can be updated, by column grant).
- Every schema change ships with tests for its constraints, tenant isolation and audit behaviour.
