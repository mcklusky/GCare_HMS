# 04 — Database Design (Detailed Draft)

> **Status:** Detailed draft 1.0, restored verbatim from the drafts embedded in `GCare_HMS_PRD_v1_4.docx`.
> **Precedence:** [04-DATA-MODEL.md](../04-DATA-MODEL.md) and [REVIEW.md](../REVIEW.md) are the approved baseline. Where this detail conflicts with them, the baseline wins; record the conflict in REVIEW.md. See [../README.md](../README.md#detail-reconciliation-notes) for known reconciliation notes.

This document defines the proposed database structure. It is a logical design: exact column types, indexes, constraints and migration scripts will be finalized during implementation. The key design decision is to separate patient identity, clinical encounters, financial transactions, configuration and audit records rather than placing everything in a few large tables.

> Source metadata: 04 — Database Design and Entity Relationship Model Version: 1.0 Status: Draft for technical review Database: PostgreSQL

## 1. Purpose

Define the main entities, relationships, constraints and data organization for GCare HMS.

The database must support multiple hospital customers, facilities and departments; a longitudinal patient record; traceable clinical and financial workflows; configurable modules; and secure audit history.

## 2. Database design principles

- Use UUID primary keys for internal record identity.
- Use human-readable patient numbers as separate business identifiers.
- Use foreign keys to enforce relationships.
- Include tenant/customer scope on tenant-owned records.
- Include facility and department scope where relevant.
- Use created_at, updated_at and actor references on important records.
- Preserve clinical authorship and signed-record history.
- Never overwrite a financial transaction to hide a correction; use reversals and adjustments.
- Store money as NUMERIC/DECIMAL with an explicit currency.
- Use database migrations for schema changes.
- Avoid storing DICOM image bytes in the transactional database.
- Store file references and metadata separately from file contents.
- Use controlled status values and documented transitions.
- Do not hard-code country-specific identifiers, currencies or tariffs.

## 3. Tenant and organization tables

### 3.1 organizations

Represents a hospital customer or customer organization.

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_code | VARCHAR | Unique organization code |
| name | VARCHAR | Organization name |
| country_code | VARCHAR | Country/region |
| status | VARCHAR | Active, suspended, closed |
| hosting_model | VARCHAR | Dedicated or shared |
| created_at | TIMESTAMP | Creation time |
| updated_at | TIMESTAMP | Last update |

### 3.2 facilities

Represents a hospital or facility belonging to an organization.

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Organization reference |
| facility_code | VARCHAR | Facility code |
| name | VARCHAR | Facility name |
| address | TEXT | Address |
| timezone | VARCHAR | Facility time zone |
| status | VARCHAR | Active/inactive |

### 3.3 departments

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Organization reference |
| facility_id | UUID | Facility reference |
| department_code | VARCHAR | Department code |
| name | VARCHAR | Department name |
| status | VARCHAR | Active/inactive |

### 3.4 service_points

Represents rooms, clinics, counters, wards or other service locations.

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Organization reference |
| facility_id | UUID | Facility reference |
| department_id | UUID | Department reference |
| name | VARCHAR | Service point name |
| type | VARCHAR | Room, clinic, ward, counter, etc. |
| status | VARCHAR | Active/inactive |

## 4. User and access tables

### 4.1 users

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| username | VARCHAR | Unique login name within defined scope |
| email | VARCHAR | Email where available |
| password_hash | VARCHAR | Secure password hash |
| first_name | VARCHAR | First name |
| last_name | VARCHAR | Last name |
| status | VARCHAR | Active, locked, disabled |
| last_login_at | TIMESTAMP | Last login |
| created_at | TIMESTAMP | Creation time |

### 4.2 user_facility_assignments

Connects users to facilities and optionally departments.

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| user_id | UUID | User reference |
| organization_id | UUID | Organization reference |
| facility_id | UUID | Facility reference |
| department_id | UUID, nullable | Department reference |
| active_from | TIMESTAMP | Assignment start |
| active_to | TIMESTAMP, nullable | Assignment end |

### 4.3 roles

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID, nullable | Null for approved platform roles |
| name | VARCHAR | Role name |
| description | TEXT | Role description |
| is_system_role | BOOLEAN | Protected system role flag |

### 4.4 permissions

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| permission_code | VARCHAR | Unique permission code |
| description | TEXT | Permission description |

### 4.5 role_permissions

| Column | Type | Description |
|---|---|---|
| role_id | UUID | Role reference |
| permission_id | UUID | Permission reference |

Composite primary key: role_id + permission_id.

### 4.6 user_role_assignments

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| user_id | UUID | User reference |
| role_id | UUID | Role reference |
| organization_id | UUID | Organization reference |
| facility_id | UUID, nullable | Facility scope |
| department_id | UUID, nullable | Department scope |
| assigned_by | UUID | Assigning user |
| assigned_at | TIMESTAMP | Assignment time |

Role assignment must not grant access outside the user's approved organization and facility scope.

## 5. Patient identity tables

### 5.1 patients

The main longitudinal patient record.

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Organization reference |
| primary_facility_id | UUID, nullable | Registration facility |
| patient_number | VARCHAR | Human-readable patient identifier |
| first_name | VARCHAR | First name |
| middle_name | VARCHAR, nullable | Middle name |
| last_name | VARCHAR | Last name |
| date_of_birth | DATE, nullable | Date of birth |
| birth_date_estimated | BOOLEAN | Whether date is estimated |
| sex | VARCHAR, nullable | Configured value |
| phone_number | VARCHAR, nullable | Contact number |
| email | VARCHAR, nullable | Email |
| address | TEXT, nullable | Address |
| preferred_language | VARCHAR, nullable | Language |
| marital_status | VARCHAR, nullable | Configured value |
| status | VARCHAR | Active, inactive, merged |
| created_at | TIMESTAMP | Registration time |
| created_by | UUID | Registering user |
| updated_at | TIMESTAMP | Last update |

Patient-number uniqueness must be enforced within the approved issuing scope. A patient identifier must not depend solely on a national identity number.

### 5.2 patient_identifiers

Allows multiple identity documents or identifiers per patient.

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| patient_id | UUID | Patient reference |
| identifier_type | VARCHAR | National ID, passport, local ID, etc. |
| identifier_value | VARCHAR | Identifier value |
| issuing_authority | VARCHAR, nullable | Issuer |
| country_code | VARCHAR, nullable | Country |
| is_primary | BOOLEAN | Primary identifier flag |
| verified | BOOLEAN | Verification status |

Sensitive identifiers require restricted access and appropriate protection.

### 5.3 patient_contacts

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| patient_id | UUID | Patient reference |
| contact_type | VARCHAR | Phone, email, address |
| contact_value | TEXT | Contact detail |
| is_primary | BOOLEAN | Primary contact |
| verified | BOOLEAN | Verification status |

### 5.4 next_of_kin

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| patient_id | UUID | Patient reference |
| full_name | VARCHAR | Contact name |
| relationship | VARCHAR | Relationship |
| phone_number | VARCHAR | Phone |
| address | TEXT, nullable | Address |

### 5.5 patient_merge_history

Records authorized patient-record merges and any approved reversal.

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| source_patient_id | UUID | Record being merged |
| target_patient_id | UUID | Surviving record |
| reason | TEXT | Reason |
| status | VARCHAR | Pending, approved, completed, reversed |
| requested_by | UUID | Requesting user |
| approved_by | UUID, nullable | Approver |
| created_at | TIMESTAMP | Event time |
| reversed_at | TIMESTAMP, nullable | Reversal time |

Never delete the history of a merge.

## 6. Encounter and clinical tables

### 6.1 encounters

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Organization reference |
| facility_id | UUID | Facility reference |
| department_id | UUID | Department reference |
| patient_id | UUID | Patient reference |
| encounter_number | VARCHAR | Encounter identifier |
| encounter_type | VARCHAR | Outpatient, emergency, inpatient, etc. |
| status | VARCHAR | Open, in progress, closed, cancelled |
| attending_clinician_id | UUID, nullable | Assigned clinician |
| started_at | TIMESTAMP | Start time |
| ended_at | TIMESTAMP, nullable | End time |
| created_by | UUID | Creator |

### 6.2 triage_records

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| encounter_id | UUID | Encounter reference |
| recorded_by | UUID | Nurse/user |
| recorded_at | TIMESTAMP | Time |
| urgency_level | VARCHAR | Configured urgency |
| pain_score | NUMERIC, nullable | Pain score |
| notes | TEXT, nullable | Triage notes |

### 6.3 observations

Stores individual vital signs and observations.

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| encounter_id | UUID | Encounter reference |
| patient_id | UUID | Patient reference |
| observation_code | VARCHAR | Temperature, pulse, BP, etc. |
| value | NUMERIC | Numeric value |
| text_value | TEXT, nullable | Text value |
| unit | VARCHAR, nullable | Measurement unit |
| observed_at | TIMESTAMP | Observation time |
| recorded_by | UUID | Recording user |

### 6.4 clinical_notes

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| encounter_id | UUID | Encounter reference |
| patient_id | UUID | Patient reference |
| note_type | VARCHAR | Consultation, progress, discharge, etc. |
| subjective | TEXT, nullable | History |
| objective | TEXT, nullable | Examination |
| assessment | TEXT, nullable | Assessment |
| plan | TEXT, nullable | Plan |
| status | VARCHAR | Draft, signed, amended |
| authored_by | UUID | Author |
| signed_by | UUID, nullable | Signatory |
| signed_at | TIMESTAMP, nullable | Signing time |

Signed notes must not be silently overwritten. Amendments must preserve the original content and identify the author, time and reason.

### 6.5 diagnoses

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| encounter_id | UUID | Encounter reference |
| patient_id | UUID | Patient reference |
| diagnosis_code | VARCHAR, nullable | Approved code |
| diagnosis_text | TEXT | Diagnosis |
| diagnosis_type | VARCHAR | Working, final, historical |
| recorded_by | UUID | Recording user |
| recorded_at | TIMESTAMP | Time |

## 7. Orders and referrals

### 7.1 orders

Common parent record for diagnostic, medication, procedure and other approved orders.

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Organization reference |
| facility_id | UUID | Facility reference |
| patient_id | UUID | Patient reference |
| encounter_id | UUID | Encounter reference |
| order_number | VARCHAR | Order number |
| order_type | VARCHAR | Laboratory, imaging, medication, procedure |
| priority | VARCHAR | Routine, urgent, etc. |
| status | VARCHAR | Draft, requested, accepted, completed, cancelled |
| indication | TEXT, nullable | Clinical indication |
| requested_by | UUID | Requesting user |
| requested_at | TIMESTAMP | Request time |

### 7.2 order_items

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| order_id | UUID | Order reference |
| catalog_item_id | UUID, nullable | Service/catalog reference |
| item_description | TEXT | Requested item |
| status | VARCHAR | Item status |

### 7.3 referrals

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| patient_id | UUID | Patient reference |
| encounter_id | UUID | Encounter reference |
| from_department_id | UUID | Referring department |
| to_department_id | UUID, nullable | Receiving department |
| external_provider | VARCHAR, nullable | External provider |
| reason | TEXT | Referral reason |
| status | VARCHAR | Requested, accepted, completed, cancelled |
| created_by | UUID | Creator |

## 8. Laboratory tables

### 8.1 lab_orders

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| order_id | UUID | Parent order |
| specimen_type | VARCHAR, nullable | Specimen type |
| accession_number | VARCHAR, nullable | Lab accession |
| collection_status | VARCHAR | Collection status |
| collected_at | TIMESTAMP, nullable | Collection time |
| collected_by | UUID, nullable | Collector |

### 8.2 lab_results

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| lab_order_id | UUID | Lab order reference |
| test_code | VARCHAR, nullable | Test code |
| test_name | VARCHAR | Test name |
| result_value | TEXT | Result |
| unit | VARCHAR, nullable | Unit |
| reference_range | VARCHAR, nullable | Reference range |
| abnormal_flag | VARCHAR, nullable | Abnormality flag |
| status | VARCHAR | Draft, validated, corrected |
| validated_by | UUID, nullable | Validator |
| validated_at | TIMESTAMP, nullable | Validation time |

### 8.3 critical_result_notifications

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| patient_id | UUID | Patient reference |
| order_id | UUID | Related order |
| result_id | UUID, nullable | Related result |
| result_type | VARCHAR | Laboratory or imaging |
| recipient_user_id | UUID, nullable | Recipient |
| notification_method | VARCHAR | Approved method |
| notified_at | TIMESTAMP, nullable | Notification time |
| acknowledged_at | TIMESTAMP, nullable | Acknowledgement time |
| status | VARCHAR | Open, acknowledged, escalated |

## 9. Radiology tables

### 9.1 imaging_orders

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| order_id | UUID | Parent order |
| modality | VARCHAR | CT, X-ray, ultrasound, EKG, etc. |
| body_part | VARCHAR, nullable | Body part |
| scheduled_at | TIMESTAMP, nullable | Appointment |
| status | VARCHAR | Requested, scheduled, acquired, reported |

### 9.2 imaging_studies

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| imaging_order_id | UUID, nullable | Related imaging order |
| patient_id | UUID | Patient reference |
| accession_number | VARCHAR, nullable | Accession |
| dicom_study_uid | VARCHAR | DICOM Study Instance UID |
| archive_reference | TEXT | Archive identifier/reference |
| study_date | TIMESTAMP, nullable | Study date |
| modality | VARCHAR | Modality |
| reconciliation_status | VARCHAR | Matched, uncertain, mismatched, resolved |

DICOM Study Instance UID must be unique within the appropriate integration scope. Unmatched studies must not silently become final reports.

### 9.3 radiology_reports

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| imaging_study_id | UUID | Study reference |
| report_version | INTEGER | Version number |
| report_text | TEXT | Report content |
| status | VARCHAR | Draft, final, amended |
| authored_by | UUID | Author |
| signed_by | UUID, nullable | Authorized signatory |
| signed_at | TIMESTAMP, nullable | Signing time |
| amendment_reason | TEXT, nullable | Amendment reason |

Use a versioned record strategy so that signed reports remain historically reproducible.

## 10. Billing and payment tables

### 10.1 service_catalog

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Organization reference |
| service_code | VARCHAR | Service code |
| name | VARCHAR | Service name |
| category | VARCHAR | Service category |
| active | BOOLEAN | Active status |

### 10.2 tariff_catalogs

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Organization reference |
| name | VARCHAR | Tariff list name |
| currency_code | VARCHAR | Currency |
| effective_from | DATE | Start date |
| effective_to | DATE, nullable | End date |
| status | VARCHAR | Draft, active, retired |

### 10.3 tariff_items

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| tariff_catalog_id | UUID | Tariff reference |
| service_catalog_id | UUID | Service reference |
| price | NUMERIC(14,2) | Price |
| effective_from | DATE | Effective date |
| effective_to | DATE, nullable | End date |

### 10.4 invoices

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Organization reference |
| facility_id | UUID | Facility reference |
| patient_id | UUID | Patient reference |
| encounter_id | UUID, nullable | Encounter reference |
| invoice_number | VARCHAR | Invoice number |
| currency_code | VARCHAR | Currency |
| subtotal | NUMERIC(14,2) | Subtotal |
| discount_total | NUMERIC(14,2) | Discount |
| total_amount | NUMERIC(14,2) | Total |
| status | VARCHAR | Draft, issued, partially paid, paid, void |
| issued_at | TIMESTAMP, nullable | Issue time |

### 10.5 invoice_items

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| invoice_id | UUID | Invoice reference |
| service_catalog_id | UUID, nullable | Service reference |
| description_snapshot | TEXT | Description at transaction time |
| quantity | NUMERIC(12,3) | Quantity |
| unit_price | NUMERIC(14,2) | Unit price |
| line_total | NUMERIC(14,2) | Line total |
| tariff_item_id | UUID, nullable | Source tariff item |

Store the applied description and price on the invoice item so historical invoices do not change when a tariff is updated.

### 10.6 payments

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| invoice_id | UUID | Invoice reference |
| payment_number | VARCHAR | Payment reference |
| amount | NUMERIC(14,2) | Amount |
| currency_code | VARCHAR | Currency |
| payment_method | VARCHAR | Cash or approved non-cash method |
| received_by | UUID | Cashier |
| received_at | TIMESTAMP | Time |
| status | VARCHAR | Completed, reversed |

### 10.7 payment_reversals

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| payment_id | UUID | Original payment |
| amount | NUMERIC(14,2) | Reversed amount |
| reason | TEXT | Reason |
| requested_by | UUID | Requester |
| approved_by | UUID | Approver |
| reversed_at | TIMESTAMP | Time |

### 10.8 receipts

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| payment_id | UUID | Payment reference |
| receipt_number | VARCHAR | Receipt number |
| issued_at | TIMESTAMP | Issue time |
| issued_by | UUID | Issuing user |

## 11. Eligibility and exemptions

```text
eligibility_schemes
```

Defines approved subsidy or exemption schemes.

```text
patient_eligibility
```

Links a patient to an eligibility scheme, including evidence, effective dates, status and approval.

```text
billing_adjustments
```

Records discounts, waivers and approved adjustments with reason, authorizer and transaction reference.

Eligibility decisions must be auditable and must not silently rewrite an already issued invoice.

## 12. Pharmacy and inventory tables

Suggested core tables:

```text
medication_catalog
prescriptions
prescription_items
dispensing_records
stores
inventory_items
inventory_batches
stock_transactions
stock_transaction_items
stock_adjustments
stocktakes
stocktake_items
```

Inventory changes must be recorded as transactions. Do not directly overwrite stock quantity without a traceable movement record.

Batch number, expiry date, store, item, quantity, transaction type and responsible user should be recorded where relevant.

## 13. Inpatient and maternity tables

These are expansion-phase tables:

```text
admissions
wards
beds
bed_assignments
patient_transfers
discharges
maternity_records
deliveries
newborn_records
mother_newborn_links
```

Mother and newborn must have separate patient records linked through an explicit relationship. Do not use one person's patient record for both.

## 14. Configuration tables

Suggested tables:

```text
country_configurations
organization_configurations
facility_configurations
department_configurations
module_catalog
organization_modules
workflow_definitions
form_definitions
form_fields
configuration_versions
configuration_change_approvals
branding_settings
integration_configurations
```

Configuration must support inherited defaults, local overrides, version history, approver, effective date and rollback.

## 15. Audit tables

```text
audit_events
```

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID, nullable | Organization scope |
| facility_id | UUID, nullable | Facility scope |
| actor_user_id | UUID, nullable | User responsible |
| action | VARCHAR | Action performed |
| entity_type | VARCHAR | Affected record type |
| entity_id | UUID, nullable | Affected record |
| patient_id | UUID, nullable | Related patient |
| outcome | VARCHAR | Success or failure |
| reason | TEXT, nullable | Reason where required |
| source_ip | VARCHAR, nullable | Source address |
| occurred_at | TIMESTAMP | Event time |

Audit records must be protected from unauthorized modification and deletion. Avoid putting unnecessary clinical content or secrets into audit payloads.

## 16. File metadata

```text
documents
```

Stores metadata for documents and attachments.

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| organization_id | UUID | Organization |
| patient_id | UUID, nullable | Patient |
| encounter_id | UUID, nullable | Encounter |
| storage_key | TEXT | Private storage reference |
| file_name | VARCHAR | Original name |
| content_type | VARCHAR | MIME type |
| file_size | BIGINT | Size |
| uploaded_by | UUID | User |
| uploaded_at | TIMESTAMP | Time |
| access_classification | VARCHAR | Access classification |

The actual file is stored in protected file/object storage, not as a public URL.

## 17. Key relationships

```text
Organization
  ├── Facilities
  │     └── Departments
  │            └── Service Points
  ├── Users
  │     ├── User Facility Assignments
  │     └── User Role Assignments
  └── Patients
        ├── Patient Identifiers
        ├── Patient Contacts
        ├── Encounters
        │     ├── Triage Records
        │     ├── Observations
        │     ├── Clinical Notes
        │     ├── Diagnoses
        │     └── Orders
        │           ├── Lab Orders
        │           │     └── Lab Results
        │           └── Imaging Orders
        │                 └── Imaging Studies
        │                       └── Radiology Reports
        └── Invoices
              ├── Invoice Items
              └── Payments
                    ├── Receipts
                    └── Payment Reversals
```

## 18. Important constraints and indexes

The implementation team must define and test:

- Unique organization code.
- Unique facility code within its organization.
- Unique patient number within its issuing scope.
- Unique encounter number within its defined scope.
- Unique invoice, payment and receipt numbers within the issuing scope.
- Unique DICOM Study Instance UID within the integration scope.
- Valid foreign keys for all patient, encounter and transaction relationships.
- Non-negative quantities and appropriate amount constraints.
- Valid effective date ranges for tariffs and configuration.
- Indexes for patient number, patient name, phone and approved demographic search.
- Indexes for encounter queues, order status, invoice status and study status.
- Indexes for audit event time, actor, patient and affected entity.

Do not use a patient's name or phone number as a unique key.

## 19. Data retention and deletion

Retention rules must be configured for each jurisdiction and approved by the responsible hospital authority.

Clinical records, signed reports, financial records and audit events must follow approved retention and correction procedures. Any deletion or anonymization process must be authorized, documented and auditable.

## 20. ERD and implementation deliverables

Before database implementation is considered complete, produce:

- Visual ERD covering the MVP entities.
- Data dictionary with field definitions.
- PostgreSQL schema and migration files.
- Foreign-key and uniqueness constraints.
- Index plan.
- Tenant-isolation strategy.
- Data retention and archival rules.
- Test data strategy using synthetic data.
- Migration and rollback procedures.

End of Document 04
