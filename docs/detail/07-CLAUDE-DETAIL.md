# 07 — AI Coding Instructions (Detailed Draft)

> **Status:** Detailed draft 1.0, restored verbatim from the drafts embedded in `GCare_HMS_PRD_v1_4.docx`.
> **Precedence:** [07-CLAUDE.md](../07-CLAUDE.md) and [REVIEW.md](../REVIEW.md) are the approved baseline. Where this detail conflicts with them, the baseline wins; record the conflict in REVIEW.md. See [../README.md](../README.md#detail-reconciliation-notes) for known reconciliation notes.

This is the most important document to place in the root of your repository. Claude Code should read it before starting any development task.

Project: GCare Hospital Management System Version: 1.0 Purpose: Permanent AI coding instructions for Claude Code

## 1. Project overview

You are working on GCare HMS, a configurable, multi-hospital management system.

GCare HMS is intended to support hospitals, healthcare organizations, facilities and departments across different countries.

The system will support:

- Patient registration and identity management.
- Outpatient and other clinical encounters.
- Triage and observations.
- Clinical documentation.
- Laboratory and radiology workflows.
- Orders and referrals.
- Billing and payments.
- Pharmacy and inventory.
- Inpatient and maternity workflows.
- Reports and dashboards.
- Administration, security and audit.
- Configurable hospital branding, workflows and modules.

The system handles sensitive clinical and financial information. Security, accuracy, privacy, auditability and clinical safety are mandatory.

## 2. Approved technology stack

Frontend

- React
- TypeScript
- Vite or the approved build tool
- React Router
- Approved component library
- Vitest and React Testing Library

Backend

- Java
- Spring Boot
- Spring Security
- Spring Data JPA
- Hibernate
- Bean Validation
- PostgreSQL
- Flyway or approved migration tool
- JUnit and Mockito
- OpenAPI

Infrastructure

- Git and GitHub
- Docker and Docker Compose where appropriate
- PostgreSQL
- Approved file/object storage
- Approved DICOM archive and viewer integration

Do not introduce a different primary framework or database without approval.

## 3. Source-of-truth documents

Before implementing a feature, consult:

```text
01-PRD.md
02-UI-UX-Specification.md
03-System-Architecture.md
04-Database-Design.md
05-API-Specification.md
06-Development-Task-Breakdown.md
CLAUDE.md
```

The PRD defines product requirements. The other documents define the approved design and implementation approach.

If documents conflict:

- Do not silently choose one.
- Identify the conflicting requirements.
- Explain the impact.
- Ask for a decision when the conflict affects security, clinical safety, financial integrity or architecture.
- Record approved decisions in the project documentation.

The user's approved decisions take priority over assumptions made by the AI.

## 4. General coding rules

- Write clean, readable and maintainable code.
- Use meaningful variable, class and method names.
- Avoid unnecessary complexity.
- Avoid duplicated business logic.
- Keep functions and classes focused.
- Use appropriate design patterns.
- Follow established project conventions.
- Do not create unnecessary abstractions.
- Do not install unnecessary dependencies.
- Do not leave unfinished placeholder functionality presented as complete.
- Do not silently remove existing features.
- Do not rewrite large parts of the application without approval.

## 5. Frontend rules

- Use TypeScript.
- Avoid any unless there is a documented reason.
- Keep components reusable and focused.
- Separate UI components from API communication.
- Use a consistent design system.
- Use shared components for forms, buttons, tables, dialogs and alerts.
- Implement loading, empty, error and success states.
- Validate forms for usability.
- Display backend validation errors clearly.
- Make interfaces responsive.
- Support keyboard navigation and accessibility.
- Avoid unnecessary page reloads.
- Display patient identity clearly on patient-specific screens.
- Do not store sensitive clinical information in browser storage without an approved design.
- Do not rely on frontend permission checks for security.

Suggested frontend organization:

```text
src/
├── app/
├── assets/
├── components/
├── features/
├── hooks/
├── services/
├── types/
└── utils/
```

## 6. Backend rules

- Use Spring Boot conventions.
- Separate controllers, services and repositories.
- Use DTOs for API requests and responses.
- Do not expose JPA entities directly as public API responses.
- Use Bean Validation.
- Keep business logic in services or appropriate domain components.
- Use transactions for operations that require atomicity.
- Use global exception handling.
- Return consistent API errors.
- Apply authorization to protected endpoints.
- Use database migrations for schema changes.
- Avoid putting business logic in controllers.
- Avoid exposing internal exception details to users.

Suggested backend organization:

```text
com.gcare.hms/
├── config/
├── security/
├── common/
├── audit/
├── organization/
├── identity/
├── patients/
├── encounters/
├── triage/
├── clinical/
├── orders/
├── laboratory/
├── radiology/
├── billing/
├── pharmacy/
├── inventory/
├── reporting/
└── integrations/
```

## 7. Database rules

- Use PostgreSQL.
- Use UUIDs for internal primary keys.
- Use foreign keys.
- Add indexes for frequently searched fields.
- Use database migrations.
- Do not manually alter production schemas.
- Use decimal types for money.
- Include explicit currency codes.
- Preserve clinical and financial history.
- Avoid hard deletion of important clinical or financial records.
- Use status transitions and correction records.
- Enforce tenant and facility scope.
- Avoid storing DICOM image bytes in the main application database.

Every schema change must include a migration and appropriate tests.

## 8. Security rules

Security is not optional.

- Use named individual accounts.
- Never create shared staff accounts.
- Never store plain-text passwords.
- Use secure password hashing.
- Enforce backend authorization.
- Apply least-privilege permissions.
- Enforce tenant, facility and department boundaries.
- Protect sensitive patient identifiers.
- Protect application secrets.
- Do not hard-code passwords, API keys or credentials.
- Do not expose sensitive information in logs.
- Protect file uploads and downloads.
- Record required audit events.
- Test unauthorized access.
- Test cross-facility and cross-tenant access.
- Never bypass authentication for convenience.

## 9. Clinical safety rules

- Never invent clinical business rules.
- Do not make clinical decisions on behalf of clinicians.
- Preserve the identity of the patient associated with every clinical record.
- Preserve clinical authorship and timestamps.
- Signed notes and reports must not be silently overwritten.
- Amendments must preserve the original version and reason.
- Only authorized users may sign clinical reports.
- Critical-result communication must be traceable.
- Do not automatically merge patient records.
- Emergency access must follow the approved break-glass workflow.
- Do not delay emergency care because optional identification information is unavailable.

AI-generated clinical suggestions, if approved in a future release, must remain advisory. AI must never independently sign or finalize a clinical report.

## 10. Financial integrity rules

- Do not use floating-point calculations for money.
- Preserve the currency on financial transactions.
- Preserve the tariff and price applied to historical transactions.
- Do not delete completed payments to correct mistakes.
- Use approved reversals, refunds and adjustments.
- Require authorization where the financial workflow specifies it.
- Prevent duplicate payment submissions.
- Generate traceable receipt numbers.
- Audit financial operations.
- Keep financial access separate from unrestricted clinical access.

## 11. Multi-hospital rules

GCare HMS is a configurable multi-hospital product.

Do not hard-code:

- Hospital names.
- Hospital logos.
- Department names.
- Tariffs.
- Currencies.
- Patient number formats.
- Country-specific identity rules.
- Country-specific clinical forms.
- Local legal requirements.

Use configuration and approved country/facility settings.

Every tenant-owned record and operation must enforce the appropriate tenant boundary. Never assume that hiding a record in the frontend is sufficient.

## 12. DICOM and imaging rules

- Do not build a custom diagnostic image viewer as a replacement for an approved DICOM viewer.
- Do not store all DICOM image data in PostgreSQL.
- Integrate with the configured DICOM archive/PACS.
- Validate patient and order matching.
- Flag uncertain or mismatched studies.
- Do not silently link a study to the wrong patient.
- Restrict viewer access.
- Preserve study and report audit history.
- Only authorized users can finalize radiology reports.

## 13. API rules

- Follow Document 05.
- Use /api/v1.
- Use consistent JSON structures.
- Validate all backend input.
- Apply authorization to every protected endpoint.
- Use pagination.
- Return consistent errors.
- Document endpoints with OpenAPI.
- Do not invent new API conventions for individual modules.
- Do not expose database entities directly.
- Protect sensitive and financial operations from duplicate submissions.

## 14. Testing rules

Write tests as features are implemented.

Frontend tests

- Component rendering.
- Form validation.
- API error handling.
- Permission-based interface behavior.
- Important user workflows.

Backend tests

- Service tests.
- Repository tests.
- API integration tests.
- Authorization tests.
- Tenant isolation tests.
- Database constraint tests.
- Audit tests.
- Clinical and financial business-rule tests.

End-to-end tests

Test complete workflows, not just isolated buttons.

At minimum, test:

- Login and logout.
- Patient registration.
- Duplicate patient detection.
- Encounter creation.
- Triage recording.
- Clinical note creation and signing.
- Order creation.
- Billing and payment.
- Receipt generation.
- Refund and reversal authorization.
- Unauthorized record access.
- Audit history.
- Downtime reconciliation.

Never claim tests passed unless they were actually executed.

## 15. Development workflow

For every assigned task:

- Read the relevant requirements and design documents.
- Identify dependencies.
- Inspect the existing code before making changes.
- Explain the implementation approach.
- Implement the smallest reasonable change.
- Write or update tests.
- Run relevant tests.
- Fix failures.
- Update documentation.
- Report completion and remaining issues.

Do not implement several unrelated modules in one task.

Do not proceed to the next dependent task if the current task is incomplete or failing.

## 16. Handling unclear requirements

If a requirement is unclear:

- Identify exactly what is unclear.
- Explain why it matters.
- State the possible approaches if useful.
- Ask for approval before making a high-impact assumption.

This is especially important for:

- Patient identification.
- Clinical signing.
- Medication workflows.
- Critical results.
- Tariffs and exemptions.
- Refunds and reversals.
- Privacy and retention.
- Tenant isolation.
- Offline synchronization.
- National health-system integrations.

## 17. Environment and data rules

- Use .env or approved secret management.
- Keep .env out of Git.
- Provide .env.example with placeholder values only.
- Use synthetic patient data in development.
- Never copy real patient information into test fixtures.
- Keep development, staging and production separate.
- Do not expose development services publicly.
- Do not use production credentials in local development.

## 18. Git rules

- Make focused commits.
- Use descriptive commit messages.
- Do not commit generated secrets or private files.
- Do not force-push shared branches without approval.
- Review changes before merging.
- Keep the main branch stable.
- Do not commit code that has not passed the required checks.

## 19. Required completion report

At the end of each assigned task, provide:

Task: Task ID and name.

Summary: What was implemented.

Files changed: List important files.

Database: Migrations and schema changes.

API: Endpoints added or updated.

Security: Permissions and access controls affected.

Tests: Tests executed and actual results.

Outstanding issues: Known problems or unresolved decisions.

Next task: The next recommended task from Document 06.

## 20. Most important instruction

GCare HMS is a hospital management system, not a simple CRUD application.

Do not prioritize speed over patient safety, data integrity, security or maintainability.

Build the system incrementally. Follow the approved documents. Test every important workflow. Ask before making high-impact assumptions.

End of CLAUDE.md
