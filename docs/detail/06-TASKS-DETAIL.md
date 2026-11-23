# 06 — Development Task Breakdown (Detailed Draft)

> **Status:** Detailed draft 1.0, restored verbatim from the drafts embedded in `GCare_HMS_PRD_v1_4.docx`.
> **Precedence:** [06-PLAN.md](../06-PLAN.md) and [REVIEW.md](../REVIEW.md) are the approved baseline. Where this detail conflicts with them, the baseline wins; record the conflict in REVIEW.md. See [../README.md](../README.md#detail-reconciliation-notes) for known reconciliation notes.

This is the document that will help Claude Code work systematically. Each task should be completed, tested and reviewed before Claude moves to the next dependent task.

> Source metadata: 06 — Development Task Breakdown Version: 1.0 Status: Draft for implementation planning Technology: React + TypeScript, Java Spring Boot, PostgreSQL

## 1. Purpose

Break GCare HMS into small, sequential and testable development tasks.

Claude Code must use this document as the implementation roadmap. It must not attempt to build the entire hospital management system in one operation.

## 2. Development rules

- Complete one task or a small related group of tasks at a time.
- Read the PRD and all relevant technical documents before implementing a feature.
- Do not invent missing business rules.
- Ask for clarification when a clinical, financial, privacy or workflow decision is not defined.
- Build backend validation before relying on frontend validation.
- Write tests with each feature.
- Do not proceed when a task's acceptance criteria fail.
- Do not use real patient information in development or demonstration data.
- Keep database changes in migration files.
- Update API documentation when endpoints change.
- Record architectural decisions and unresolved issues.
- Do not introduce microservices unless separately approved.

## 3. Phase 0 — Project preparation and technical foundation

### Task 0.1 — Review project documents

- Read Documents 01–07.
- Identify conflicting or missing requirements.
- Create a list of technical decisions requiring approval.
- Create a requirements-to-feature traceability list.

**Acceptance criteria:** The implementation team has a clear list of requirements, dependencies and unresolved decisions.

### Task 0.2 — Initialize source control

- Create the Git repository.
- Create main and development branches using the agreed branching strategy.
- Add .gitignore.
- Add README.
- Add license and contribution information if required.
- Add environment variable examples without secrets.

**Acceptance criteria:** A clean repository exists and can be cloned and built.

### Task 0.3 — Initialize React frontend

- Create React + TypeScript project.
- Configure build and development scripts.
- Configure linting and formatting.
- Configure frontend test framework.
- Create the initial application entry point.

**Acceptance criteria:** Frontend starts successfully and automated tests run.

### Task 0.4 — Initialize Spring Boot backend

- Create Spring Boot project.
- Configure Java version.
- Add Spring Web, Spring Security, Validation, Spring Data JPA and PostgreSQL dependencies.
- Configure testing.
- Create health endpoint.
- Configure environment-based settings.

**Acceptance criteria:** Backend starts successfully, connects to the development database and passes its initial tests.

### Task 0.5 — Configure PostgreSQL and migrations

- Create development database.
- Configure database connection.
- Add migration tool.
- Create initial migration.
- Test clean database setup and migration execution.

**Acceptance criteria:** The database can be created and migrated from an empty state.

### Task 0.6 — Configure local development environment

- Create Docker Compose configuration where appropriate.
- Document startup commands.
- Add environment examples.
- Confirm frontend-to-backend communication.

**Acceptance criteria:** A developer can follow the setup instructions to run the application locally.

## 4. Phase 1 — Core architecture and security

### Task 1.1 — Establish backend module structure

Create the approved backend packages for:

- Organization.
- Identity.
- Authentication.
- Authorization.
- Patients.
- Encounters.
- Clinical.
- Billing.
- Audit.
- Configuration.

**Acceptance criteria:** Modules are separated logically and the application builds.

### Task 1.2 — Establish frontend structure

Create:

- Main application layout.
- Route organization.
- Shared components.
- API service layer.
- Error handling.
- Loading and empty states.
- Feature folders.

**Acceptance criteria:** The frontend has a consistent structure and navigation foundation.

### Task 1.3 — Implement organization and facility foundation

- Create organization entity and migration.
- Create facility entity and migration.
- Create department entity and migration.
- Implement services and repositories.
- Implement permitted CRUD endpoints.
- Add tests for organization/facility boundaries.

**Acceptance criteria:** Authorized administrators can manage permitted organization and facility records.

### Task 1.4 — Implement authentication

- Implement login.
- Implement logout.
- Implement current-user endpoint.
- Configure secure password hashing.
- Configure session/token strategy.
- Implement session expiration and revocation.
- Add failed-login protections.

**Acceptance criteria:** Named users can authenticate and unauthorized requests are rejected.

### Task 1.5 — Implement roles and permissions

- Create role and permission tables.
- Create role-permission relationships.
- Implement user assignments.
- Enforce facility and department scope.
- Add authorization tests.

**Acceptance criteria:** Users can access only the features and data allowed by their assigned roles and scopes.

### Task 1.6 — Implement audit foundation

- Create audit event model and migration.
- Implement audit service.
- Record authentication and administrative events.
- Protect audit records.
- Add tests.

**Acceptance criteria:** Required events are recorded and ordinary users cannot alter audit history.

## 5. Phase 2 — Patient management

### Task 2.1 — Patient database model

- Create patient entity.
- Create patient identifier entity.
- Create patient contact entity.
- Create next-of-kin entity.
- Add indexes and constraints.
- Create migrations.

**Acceptance criteria:** Patient data can be stored with valid relationships and unique patient identifiers.

### Task 2.2 — Patient registration API

- Implement patient registration endpoint.
- Validate mandatory and optional fields.
- Generate patient number.
- Record registration facility and user.
- Add audit events.

**Acceptance criteria:** An authorized clerk can register a patient and receive a unique patient number.

### Task 2.3 — Patient search API

- Implement search by patient number.
- Implement approved demographic search.
- Implement pagination.
- Add duplicate candidate detection.
- Apply access-scope checks.

**Acceptance criteria:** Search returns permitted possible matches and does not automatically merge records.

### Task 2.4 — Patient frontend

- Build patient search screen.
- Build registration form.
- Build patient details/profile screen.
- Add validation and feedback.
- Display patient number prominently.

**Acceptance criteria:** An authorized user can search, register and view permitted patient information.

### Task 2.5 — Duplicate review and merge workflow

- Implement duplicate review queue.
- Implement merge request.
- Implement approval permissions.
- Preserve merge history.
- Design controlled reversal process.
- Add audit and tests.

**Acceptance criteria:** No automatic merge occurs; authorized staff can review and process a documented merge.

## 6. Phase 3 — MVP encounter and clinical workflows

### Task 3.1 — Encounter model and API

- Create encounter model.
- Implement encounter creation.
- Implement encounter search.
- Implement encounter status transitions.
- Implement transfer and closure.
- Add audit events.

**Acceptance criteria:** Authorized staff can create and manage encounters within approved workflows.

### Task 3.2 — Encounter frontend

- Build encounter creation screen.
- Build encounter list and filters.
- Build encounter details.
- Add patient identity context.
- Add status indicators.

**Acceptance criteria:** Users can manage permitted encounters from the interface.

### Task 3.3 — Triage and observations

- Create triage model.
- Create observation model.
- Implement configurable observation codes and units.
- Implement triage endpoints.
- Add history and correction handling.
- Add audit events.

**Acceptance criteria:** Authorized nursing staff can record and review observations with timestamps and authorship.

### Task 3.4 — Triage frontend

- Build triage form.
- Build observation entry.
- Display previous observations.
- Show urgency and escalation indicators.
- Validate values and units.

**Acceptance criteria:** Triage users can record observations and see relevant encounter information.

### Task 3.5 — Clinical notes and diagnoses

- Create clinical note model.
- Create diagnosis model.
- Implement draft note workflow.
- Implement signing permissions.
- Implement amendment history.
- Add tests.

**Acceptance criteria:** Authorized clinicians can document, sign and amend records without silently overwriting signed content.

### Task 3.6 — Clinical frontend

- Build consultation screen.
- Build history and examination fields.
- Build assessment and plan fields.
- Build diagnosis entry.
- Build draft and signing workflow.
- Display authorship and status.

**Acceptance criteria:** Clinicians can complete an approved consultation workflow.

### Task 3.7 — Orders and referrals

- Create common order model.
- Implement order types and status transitions.
- Implement diagnostic order creation.
- Implement referral creation.
- Add authorization and audit tests.

**Acceptance criteria:** Authorized users can create and track orders and referrals.

## 7. Phase 4 — MVP billing and financial controls

### Task 4.1 — Service catalog and tariff model

- Create service catalog.
- Create tariff catalogs and items.
- Support currency and effective dates.
- Implement tariff administration.
- Preserve applied tariff information on transactions.

**Acceptance criteria:** Authorized finance staff can manage approved services and tariffs without changing historical invoice prices.

### Task 4.2 — Invoice workflow

- Create invoice and invoice-item models.
- Implement invoice creation.
- Implement item calculation.
- Implement issue and status workflow.
- Add transaction tests.

**Acceptance criteria:** An authorized user can create and issue an invoice with correct calculations.

### Task 4.3 — Payments and receipts

- Implement payment recording.
- Support approved payment methods.
- Support partial payments.
- Generate unique receipt numbers.
- Implement receipt retrieval and printing.
- Add duplicate-submission protection.

**Acceptance criteria:** Payments are traceable and receipts are generated only after successful payment recording.

### Task 4.4 — Refunds, reversals and exemptions

- Implement authorization workflow.
- Record reasons and approvers.
- Preserve original transactions.
- Add audit events.
- Test unauthorized and duplicate requests.

**Acceptance criteria:** Financial corrections are traceable and cannot be performed by unauthorized users.

### Task 4.5 — Billing frontend

- Build service selection.
- Build invoice screen.
- Build payment screen.
- Build receipt view/print.
- Build approved adjustment workflow.
- Build cashier reconciliation screen.

**Acceptance criteria:** Cashiers can perform their authorized financial workflows.

## 8. Phase 5 — MVP reporting, configuration and pilot readiness

### Task 5.1 — Core dashboards

- Implement role-specific dashboard APIs.
- Display permitted queue counts.
- Display approved operational measures.
- Show data refresh status.

**Acceptance criteria:** Users see only the dashboard data permitted by their scope.

### Task 5.2 — Core reports

- Implement approved daily operational reports.
- Implement registration and encounter reports.
- Implement billing summaries.
- Add permission checks and export audit.

**Acceptance criteria:** Authorized users can generate reports and exports are traceable.

### Task 5.3 — Configuration foundation

- Implement branding settings.
- Implement facility and department settings.
- Implement module catalog.
- Implement configuration versioning.
- Implement approval and publishing workflow.

**Acceptance criteria:** Authorized administrators can manage permitted configuration without code changes.

### Task 5.4 — Downtime and reconciliation

- Design approved downtime forms.
- Build reconciliation queue.
- Implement duplicate checks during post-outage entry.
- Record reconciliation decisions.
- Test interrupted and repeated submissions.

**Acceptance criteria:** Downtime records can be reconciled without silently creating duplicate transactions or patient records.

### Task 5.5 — MVP security and quality review

- Test role access.
- Test tenant/facility isolation.
- Test audit events.
- Test backups and restore.
- Test common workflows.
- Conduct user acceptance testing.
- Resolve critical security and workflow issues.

**Acceptance criteria:** Pilot leadership approves the MVP readiness checklist before real patient use.

## 9. Phase 6 — Diagnostics

### Task 6.1 — Laboratory workflow

- Build laboratory worklist.
- Implement specimen/accession tracking.
- Implement result entry.
- Implement validation and correction.
- Implement critical-result notification.
- Add tests.

### Task 6.2 — DICOM archive evaluation

- Identify actual modalities and vendors.
- Confirm DICOM conformance.
- Evaluate archive options, including Orthanc.
- Validate network and storage requirements.
- Document integration design.

### Task 6.3 — Radiology integration

- Implement imaging worklist.
- Integrate archive metadata.
- Implement study reconciliation.
- Implement authorized viewer launch.
- Implement report draft/sign/amend workflow.
- Implement critical-result communication.
- Test using representative studies and actual equipment.

**Acceptance criteria:** Radiology leadership accepts the end-to-end workflow after real integration testing.

## 10. Phase 7 — Hospital expansion

Build these modules only after their workflows and owners are approved:

- Pharmacy and prescription management.
- Inventory and stock movements.
- Emergency workflow.
- Inpatient admission and bed management.
- Maternity and newborn records.
- Additional referrals and departments.
- Approved external system integrations.
- Additional facilities.

Each module must have its own requirements, database changes, API contract, UI screens, authorization tests and acceptance criteria.

## 11. Phase 8 — Advanced capabilities

Separate approval and planning are required for:

- Patient portal.
- Expanded mobile workflows.
- Advanced offline synchronization.
- SMS notifications.
- Advanced analytics.
- AI decision support.
- Biometric-assisted identification.

AI must remain advisory and cannot sign or finalize a clinical report. Biometric identification must not automatically merge patient records or serve as sole proof of identity.

## 12. Required task completion report

After each task, Claude Code must report:

- Task ID and title.
- Files created or changed.
- Database migrations added.
- API endpoints added or changed.
- Tests executed and results.
- Acceptance criteria passed or failed.
- Security and permission implications.
- Known issues.
- Next recommended task.

## 13. Definition of done

A task is complete only when:

- Implementation matches the approved requirements.
- Backend validation is present.
- Authorization is enforced.
- Audit events are added where required.
- Tests pass.
- No existing tests are broken.
- Documentation is updated.
- No secrets or real patient data are committed.
- Acceptance criteria are met.
- Changes are reviewed before merging.

End of Document 06
