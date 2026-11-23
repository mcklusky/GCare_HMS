# 09 — Test Strategy and Quality Plan

## Purpose
Define how GCare HMS proves that requirements work safely across UI, API, database, integrations and operations. Tests use synthetic data; production data is prohibited in test fixtures.

## Test levels
- Unit: domain rules, validation, tariff calculations, status transitions, duplicate decisions.
- Component: React rendering, keyboard behavior, form states, error handling.
- API/service integration: Spring services, persistence, transaction boundaries, authorization and audit.
- Contract: OpenAPI compatibility and partner interface schemas.
- End-to-end: patient registration through encounter, orders, billing and radiology workflow.
- Operational: migrations, restore, downtime/reconciliation, monitoring/alerts and deployment rollback.
- Human review: clinical usability, accessibility, security review, workflow UAT and radiologist image suitability.

## Required quality checks
Every protected endpoint tests unauthenticated, insufficient role, wrong tenant and wrong facility. Mutations verify audit event and actor/scope. Clinical tests verify patient linkage, signed-version immutability, amendments and critical-result acknowledgement. Financial tests verify decimal arithmetic, explicit currency, tariff snapshot, duplicate submission safety, receipt uniqueness and authorized reversal. DICOM tests cover correct/ambiguous/mismatched identity and failed transfer. UI checks include loading/empty/error/forbidden states and keyboard navigation.

## Environments and release gates
CI: formatting/static analysis, build, unit/integration tests, migration checks, dependency/security scan, frontend checks and artifact build. Staging/UAT uses synthetic or specifically de-identified data approved under policy. Production release requires owner-approved acceptance scenarios, no unresolved critical security finding, backup/restore evidence, rollback procedure, monitoring and support readiness. Pilot sign-off is recorded by accountable facility owners.

## Defect severity
Critical: risk of patient harm, wrong-patient linkage, unauthorized disclosure, financial corruption, data loss or bypass of signing controls; block release. High: major workflow or security failure with no safe workaround; block affected module. Medium/low: triage, document workaround and target release. Clinical and security owners determine severity for their domains.

## Evidence
Each release links requirement IDs to test cases and stores CI results, UAT sign-offs, security findings, migration/restore evidence and known issues. Never state a test passed unless it ran successfully; record skipped tests and reasons.
