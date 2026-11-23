# 05 — API Specification

Contract baseline: HTTPS JSON REST, `/api/v1`, OpenAPI published from code. Exact schemas and permissions require approval alongside workflows. Never expose persistence entities directly.

> Endpoint tables per module (authentication, organizations/facilities, users/roles, patients, encounters, triage, clinical records, orders, radiology, billing, configuration, reports/audit), JSON examples, status codes and API readiness definition: [detail/05-API-DETAIL.md](detail/05-API-DETAIL.md). Where paths differ (e.g. detail `PUT /patients/{id}` and `POST /patients/duplicate-check` vs baseline `PATCH` and `GET .../possible-duplicates`), this baseline wins; the final contract is fixed per endpoint in OpenAPI. Laboratory and pharmacy endpoints in the detail are out of pilot scope.

## Conventions
UUID resource IDs; ISO-8601 timestamps with offsets; money is decimal string plus currency code; pagination uses `page`, `size`, stable sort and returns `items`, `page`, `size`, `total`. Standard error: `{ "code": "...", "message": "...", "field_errors": {}, "correlation_id": "..." }`. Authenticated requests require server-side tenant/facility authorization. Mutating requests validate input and emit audit events. Idempotency-Key is required for payment/refund and other retry-sensitive commands. ETags/version fields prevent conflicting edits. Do not include unnecessary patient data in responses or logs.

## Resource groups (initial pilot)
- `GET/POST /patients`; `GET/PATCH /patients/{id}`; `GET /patients/{id}/possible-duplicates`; controlled `POST /patients/{id}/links` and merge request/approval actions.
- `GET/POST /encounters`; `GET /encounters/{id}`; encounter triage, note draft/version/sign/amend, diagnosis and close actions.
- `POST /orders`; `GET /orders?status=&facilityId=`; status transition actions; referral create/read.
- `GET /imaging-worklist`; `POST /imaging-studies/{id}/reconciliation`; report draft/sign/amend; critical result notify/acknowledge; viewer launch token endpoint (short-lived, scoped; never expose archive credentials).
- `GET /service-catalog`; `GET /tariffs?effectiveAt=`; finance-scoped invoice create/read/issue, payment record, receipt read, exemption approval, refund/reversal request/approve, shift reconciliation.
- `GET /reports/{reportType}` and controlled exports; `GET /audit-events` restricted to authorized audit roles.
- `GET /me`, scoped facility/department context and session/logout endpoints according to chosen identity provider.

## Authorization matrix (minimum; final matrix signed off)
Registration: patient demographics and duplicate review. Clinician: assigned-care encounters, notes, orders. Nurse/triage: observations and queue. Technologist: assigned imaging worklist and acquisition status. Radiologist: imaging studies and report signing. Cashier: invoices/payments/receipts, no unrestricted notes. Finance: tariff and reconciliation controls. Facility administrator: scoped users/configuration, no implicit clinical access. Auditor: read audit trail, no mutation. Support: operational metadata only unless time-bound approved break-glass. Every API checks tenant, facility and role; UI visibility is not authorization.

## State transitions and safety
Patient link/merge, note sign/amend, report sign/amend, critical result acknowledgement, payment, refund/reversal, tariff publish and config publish are explicit commands with reason/actor/time as applicable. Signed artifacts are immutable; amendments append versions. Uncertain study linkage cannot be finalized automatically. Conflict returns `409`; invalid transition returns `422`; unauthorized access returns `403` or `404` per disclosure policy.

## Versioning and interoperability
Breaking contract changes require a new API version or compatible migration plan. OpenAPI schemas and examples are version controlled. External adapters map approved partner contracts to internal models; do not assume FHIR version/profile or national API before authority confirmation. Webhooks, if added, require signatures, replay protection and delivery audit.

## Contract acceptance
For each endpoint document request/response, required scope, validation, state transitions, audit events, idempotency, errors and example. Test unauthorized and cross-tenant access, duplicate submissions, stale versions, validation, pagination and audit behavior.
