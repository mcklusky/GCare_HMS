# 07 — AI Coding Instructions (CLAUDE.md)

Read `docs/README.md`, `docs/01-PRD.md` through `docs/06-PLAN.md`, and `docs/REVIEW.md` before implementation. These documents define the baseline; unresolved decisions must be surfaced, not silently guessed.

> Full rule set (frontend, backend, database, security, clinical, financial, multi-hospital, DICOM, API, testing, Git and environment rules): [detail/07-CLAUDE-DETAIL.md](detail/07-CLAUDE-DETAIL.md). The repository-root `CLAUDE.md` no longer imports this file. It carries a short summary of these rules and points here; read this file when a task needs the full wording.

## Approved stack
Frontend: React + TypeScript. Backend: Java + Spring Boot modular monolith. Persistence: PostgreSQL with Flyway migrations. Do not introduce Django, a second backend language/framework, microservices, or another database without an explicit recorded architecture decision. Integrate imaging through approved DICOM archive/PACS and viewer; never store image payloads in PostgreSQL or build a replacement diagnostic viewer.

## Non-negotiable safety
- Synthetic data only outside approved production; never commit patient data, secrets, credentials or production extracts.
- Enforce authorization in backend services for every protected action. Apply tenant/facility/department scope to queries and writes. Frontend checks are usability only.
- Named accounts, least privilege, audit sensitive access and mutations. Do not log clinical content or secrets.
- Preserve patient identity, source/provenance, authorship and timestamps. Never silently merge records or overwrite signed notes/reports/completed financial transactions.
- Use explicit amendment, reversal and reconciliation workflows with reason/actor and audit.
- Money uses decimal types and explicit currency; preserve tariff snapshot. Protect payment submissions with idempotency.
- DICOM mismatch/uncertain linkage goes to reconciliation. AI, if separately approved, is advisory only; it cannot sign. Biometrics never auto-confirm identity or merge.
- Do not invent clinical rules, local legal interpretations, tariffs, or national interface contracts.

## Engineering rules
Use focused modules, DTOs, Bean Validation, service-layer business rules, transactions, consistent API errors, OpenAPI and versioned `/api/v1` contracts. Use database constraints and migrations. Prefer configuration over customer forks. Avoid unnecessary dependencies and abstractions. Keep accessible responsive UI with loading/empty/error states and clear patient identity context. Do not put sensitive clinical data in browser storage.

## Workflow
Before coding: inspect repository and relevant requirements; identify dependencies and unresolved high-impact decisions; state approach. Implement one scoped task. Add/update tests for feature, authorization, tenant isolation, audit, clinical/financial rules and failure paths. Run relevant checks and report actual results. Update docs and migrations. Do not claim tests passed unless run. Do not proceed past a failed dependency or gate.

## Completion report
Task ID/title; summary; files changed; migrations/data impact; API changes; security/clinical/financial implications; tests executed and results; acceptance criteria status; known issues/decisions; next task. Review changes before merge; do not force-push or deploy without explicit authorization.
