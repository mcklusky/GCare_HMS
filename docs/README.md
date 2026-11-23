# GCare HMS — Document Set

Implementation baseline: React + TypeScript frontend; Java + Spring Boot modular monolith; PostgreSQL; DICOM archive/PACS and diagnostic viewer integrated over approved interfaces.

## Core product and engineering documents
- [01 — Product Requirements](01-PRD.md) (v1.5, incl. requirement register FR-001–FR-046)
- [02 — User Flows and UI/UX](02-UI-UX.md)
- [03 — System Architecture](03-ARCHITECTURE.md)
- [04 — Database Design](04-DATA-MODEL.md)
- [05 — API Specification](05-API.md)
- [06 — Development Plan](06-PLAN.md)
- [07 — AI Coding Instructions](07-CLAUDE.md)
- [08 — First Sprint Checklist](08-FIRST-SPRINT.md)
- [Development Guide](DEVELOPMENT.md) (local setup, checks, data and secrets policy)
- [16 — Requirements Traceability](16-TRACEABILITY.md)
- [17 — Patient Identity Policy](17-PATIENT-IDENTITY-POLICY.md) (**provisional** defaults for OD-05–07; needs registration-leadership sign-off)
- [18 — Encounter Workflow](18-ENCOUNTER-WORKFLOW.md) (**provisional** visit lifecycle and queues; triage scale and alarm limits left for a clinical lead)
- [Consistency Review and Decision Register](REVIEW.md)

## Delivery, safety and operations documents
- [09 — Test Strategy and Quality Plan](09-TEST-STRATEGY.md)
- [10 — Data Migration and Reconciliation](10-DATA-MIGRATION.md)
- [11 — Interoperability and DICOM Integration](11-INTEGRATION-DICOM.md)
- [12 — Clinical Safety and Downtime](12-CLINICAL-SAFETY-DOWNTIME.md)
- [13 — Change Control and Release Management](13-CHANGE-RELEASE.md)
- [14 — User Training and Support](14-TRAINING-SUPPORT.md)
- [15 — Retention, Export and Exit](15-RETENTION-EXPORT-EXIT.md)

## Detailed drafts
Restored verbatim from the drafts embedded in `GCare_HMS_PRD_v1_4.docx`. They add column-level, endpoint-level and task-level detail. The core documents above are the approved baseline and win on conflict.

- [03 — Architecture detail](detail/03-ARCHITECTURE-DETAIL.md)
- [04 — Database detail](detail/04-DATA-MODEL-DETAIL.md)
- [05 — API detail](detail/05-API-DETAIL.md)
- [06 — Task breakdown with acceptance criteria](detail/06-TASKS-DETAIL.md)
- [07 — AI coding instructions detail](detail/07-CLAUDE-DETAIL.md)

## Project record
[project-record/](project-record/README.md) holds the site-specific evidence the documents depend on (approvals, permission matrix, tariffs, deployment record, modality inventory, hazard log, etc.). Files there are templates until filled from approved sources.

## Source document status
`../GCare_HMS_PRD_v1_4.docx` is **superseded** by [01-PRD.md](01-PRD.md) v1.5. Keep it as historical source only: its technology direction (Django) is replaced by ADR-001, and its content has been carried into the markdown set. Do not build from it.

## Detail reconciliation notes
Known differences between the detailed drafts and the baseline. Baseline wins unless a decision is recorded in [REVIEW.md](REVIEW.md).

| # | Detail draft says | Baseline says | Handling |
|---|---|---|---|
| 1 | `organizations` / `organization_id` | `Tenant` / `tenant_id` | Decided: `organization` / `organization_id` (ADR-009) |
| 2 | Phase 0 = repository setup, Phase 1 = core security | Phase 0 = discovery, Phase 1 = foundation | Detail Phases 0–1 → baseline Phase 1 (see [06-PLAN.md](06-PLAN.md)) |
| 3 | Laboratory, pharmacy, inventory, inpatient/maternity tables and endpoints; lab in Diagnostics phase | Laboratory and those modules are "Later" (ADR-005) | Do not build in the pilot; keep as future reference |
| 4 | `PUT` for updates; `POST /patients/duplicate-check`; `/patients/merge-requests` | `PATCH /patients/{id}`; `GET /patients/{id}/possible-duplicates`; `/patients/{id}/links` | Baseline paths win; finalize per endpoint in OpenAPI at Phase 2 |

These documents are templates and engineering baselines. Site-specific approvals, evidence, contacts, legal retention periods, equipment facts and service targets must come from approved project records. Synthetic data only in development and demos. Requirement approval does not by itself authorize clinical deployment.
