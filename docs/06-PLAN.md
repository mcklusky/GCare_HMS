# 06 — Development Plan and Release Gates

Work is dependency ordered. Each task is a separately reviewed change; no live patient data until pilot launch gate is approved. Owners and estimates are assigned after discovery.

> Per-task steps and **acceptance criteria**: [detail/06-TASKS-DETAIL.md](detail/06-TASKS-DETAIL.md). Requirement mapping: [16-TRACEABILITY.md](16-TRACEABILITY.md). Phase mapping (detail → baseline): detail Phase 0 + Phase 1 → baseline Phase 1; Phases 2–5 match; detail Phase 6 Diagnostics → baseline Phase 6 (radiology only — Task 6.1 Laboratory is "Later"); detail Phases 7–8 → "Later modules".

## Phase 0 — Resolve and discover (approval confirmed; retain evidence)
0.1 Confirm pilot site, departments, clinical/finance/IT owners and decision rights.
0.2 Approve workflows, role matrix, patient identity/duplicate handling, data retention, tariffs, exemption/refund policy, downtime and critical-result process.
0.3 Inventory infrastructure, modalities, DICOM capabilities, storage, power/network, current systems and national interface requirements.
0.4 Confirm privacy/legal review, hosting/data residency, support ownership, RPO/RTO and incident response.
Exit: user confirms approval is complete. Store signed discovery pack, prioritized acceptance scenarios, decision log and site readiness evidence in the project record. Proceed to Phase 1.

## Phase 1 — Foundation
1.1 Repository, Java/React build, CI, environment templates and synthetic data policy.
1.2 PostgreSQL schema/migrations, tenant/facility model, audit framework, health/metrics and secrets handling.
1.3 Authentication integration, roles/scopes, authorization tests and admin bootstrap.
1.4 Design system, application shell, accessibility baseline, API error/loading patterns.
Exit: security-reviewed foundation; tests prove tenant/facility boundaries.

## Phase 2 — Patient registration
2.1 Patient schema/search and identifiers with provenance.
2.2 Registration UI, duplicate candidate workflow, correction history.
2.3 Controlled link/merge requests, approval, audit and recovery.
Exit: registration leadership signs off tested duplicate scenarios and usability.

## Phase 3 — Outpatient clinical core
3.1 Encounter and queue lifecycle; triage observations.
3.2 Clinical note draft, version, signing/amendment and diagnosis.
3.3 Orders/referrals and visible status.
3.4 Patient chart and clinician workspace.
Exit: end-to-end UAT with role checks; signed content immutable.

## Phase 4 — Billing
4.1 Service catalog and effective-dated tariff snapshot.
4.2 Invoice calculations with decimal arithmetic and currency.
4.3 Payments, idempotency, receipts and shift reconciliation.
4.4 Exemption approval, refund/reversal workflow and audit.
Exit: verified scenarios balance; finance owner approves permissions and receipt format.

## Phase 5 — Reporting, config and downtime
5.1 Scoped dashboards and operational reports/exports.
5.2 Configuration versioning, approval and facility branding.
5.3 Downtime forms and reconciliation queue.
5.4 Security, accessibility, backup/restore, alerting and UAT remediation.
Exit: operational readiness review; restore and downtime drills complete; training/support live.

## Phase 6 — Radiology pilot
6.1 Select archive/viewer after procurement, security and clinical evaluation.
6.2 Inventory and configure real target modalities on isolated LAN; test representative studies.
6.3 Imaging orders, worklist/status, metadata linking and reconciliation queue.
6.4 Viewer launch, report draft/sign/amend, critical-result communication.
Exit: radiology leadership accepts integrity, matching, image quality, access, restore and downtime scenarios.

## Later modules
Laboratory; pharmacy/inventory; emergency/inpatient/maternity; more facilities; national integrations; patient portal; advanced offline; AI or biometrics. Each requires independent discovery, risk review, scope and gate approval.

## Definition of done per task
Approved requirement linked; smallest focused implementation; backend validation and authorization; audit where required; migration and tests; documentation/OpenAPI updated; test results reported truthfully; no secrets or real patient data; review completed; acceptance criteria met. No claim of testing without execution.

