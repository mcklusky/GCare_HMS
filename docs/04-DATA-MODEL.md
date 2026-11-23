# 04 — Data Model and ERD

Logical baseline; detailed field dictionary, retention and terminology are implementation gates. All tenant-owned rows carry tenant_id and appropriate facility scope; database constraints and server authorization enforce boundaries.

> Column-level table definitions (~45 tables incl. `service_points`, `user_facility_assignments`, `next_of_kin`, `patient_merge_history`, `triage_records`, `order_items`, `payment_reversals`, file metadata), constraints, indexes and ERD deliverables: [detail/04-DATA-MODEL-DETAIL.md](detail/04-DATA-MODEL-DETAIL.md). The detail uses `organizations`/`organization_id` where this baseline uses `Tenant`/`tenant_id`; ADR-009 settles on `organization`/`organization_id`, so read "Tenant" here as "organization". Isolation: ADR-010. Laboratory, pharmacy, inventory and inpatient tables in the detail are out of pilot scope.

## Entities
- Tenant(id, code, name, status, country_code, timezone)
- Facility(id, tenant_id, code, name, timezone, status)
- Department(id, facility_id, code, name, status)
- User(id, identity_subject, username, status); Role(id, code); Permission(id, code); UserRole(user_id, role_id, scope); RolePermission(role_id, permission_id)
- Patient(id, tenant_id, internal_identifier, status, created_at); PatientName(patient_id, value, source, valid_from/to); PatientIdentifier(patient_id, type, value, issuer, status, source); PatientContact; PatientLink(source_patient_id,target_patient_id,reason,approved_by,created_at). Do not hard-delete or silently merge.
- Encounter(id, tenant_id, facility_id, patient_id, department_id, type, status, started_at, ended_at, created_by); Observation(id, encounter_id, code, value, unit, observed_at, source); ClinicalNote(id, encounter_id, author_id, status, signed_at); NoteVersion(note_id, version, content, reason, author_id, created_at); Diagnosis(encounter_id, code_system, code, display, status)
- ServiceOrder(id, encounter_id, patient_id, order_type, status, indication, priority, ordered_by); OrderStatusEvent; Referral(order_id, destination, reason)
- ImagingStudy(id, patient_id, order_id nullable, archive_uid, status, received_at, match_status); StudyReconciliation(study_id, candidate_patient_id, decision, reason, reviewer_id, decided_at); RadiologyReport(study_id, status, current_version); ReportVersion(report_id, version, text, author_id, signed_at, amendment_reason); CriticalResult(report_version_id, recipient, sent_at, acknowledged_at)
- ServiceCatalogItem(id, tenant_id, code, description, active); Tariff(id, facility_id, currency, effective_from/to, status); TariffItem(tariff_id, service_id, amount decimal); Invoice(id, encounter_id, currency, status, tariff_snapshot); InvoiceItem(invoice_id, service_id, description_snapshot, quantity, unit_amount decimal, total decimal); Payment(id, invoice_id, amount decimal, currency, method, external_reference, idempotency_key, recorded_by, status); Receipt(payment_id, receipt_number); Adjustment(id, invoice_id/payment_id, type, amount, reason, requested_by, approved_by, status)
- AuditEvent(id, tenant_id, actor_id, action, resource_type, resource_id, purpose, occurred_at, correlation_id, metadata_redacted); ConfigurationVersion(id, tenant_id/facility_id, type, version, status, effective_at, approved_by)

## Relationships and invariants
Tenant 1:N facilities; facility 1:N departments. Patient 1:N identifiers, encounters and contacts. Encounter belongs to exactly one patient and facility. Notes/reports have version history; signed versions cannot be overwritten. Orders link patient and encounter; studies may remain unmatched until reviewed. Invoice item snapshots preserve historical price/currency even when tariffs change. Payments cannot exceed policy limits; reversals reference original transaction and remain auditable. Internal patient ID is stable and distinct from optional external/national identifiers.

## Database conventions
UUID primary keys; foreign keys; unique (tenant_id, internal_identifier); effective date checks; numeric(precision,scale) for money and explicit currency; UTC instants; indexes for scoped patient search, active queues, encounter dates and order status. Use soft status/retention workflows rather than destructive deletes for clinical/financial records. Encrypt/selectively protect high-risk identifiers; never log clinical payloads. Add migrations for every schema change and test constraints, tenant isolation, versioning and idempotency.

## Must decide before schema freeze
Searchable demographics and normalization; duplicate scoring/manual review; patient merge vs link semantics; retention/deletion/legal hold; terminology systems; tenant isolation topology; clinical note data structure; invoice tax/exemption representation; audit retention and tamper-evidence requirements.
