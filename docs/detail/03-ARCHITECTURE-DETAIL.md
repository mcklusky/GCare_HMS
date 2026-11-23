# 03 — System Architecture (Detailed Draft)

> **Status:** Detailed draft 1.0, restored verbatim from the drafts embedded in `GCare_HMS_PRD_v1_4.docx`.
> **Precedence:** [03-ARCHITECTURE.md](../03-ARCHITECTURE.md) and [REVIEW.md](../REVIEW.md) are the approved baseline. Where this detail conflicts with them, the baseline wins; record the conflict in REVIEW.md. See [../README.md](../README.md#detail-reconciliation-notes) for known reconciliation notes.

> Source metadata: 03 — System Architecture Version: 1.0 Status: Draft for technical review Product: GCare HMS Frontend: React + TypeScript Backend: Java + Spring Boot Database: PostgreSQL Architecture approach: Modular monolith for the initial releases

## 1. Purpose

This document defines the technical architecture of GCare HMS. It explains how the frontend, backend, database, security services, diagnostic systems and deployment environment work together.

The architecture must support a configurable hospital management platform that can serve different hospitals, facilities, departments and jurisdictions without requiring a separate product codebase for each customer.

## 2. Architecture principles

- Modular design: Organize the system into clearly separated business modules.
- Security by design: Enforce authentication, authorization, audit logging and data protection throughout the application.
- Clinical safety: Protect patient identity, clinical records, orders, results and signed reports.
- Configuration over customization: Use configurable workflows, forms, tariffs, branding and modules instead of customer-specific code forks.
- Reliable local operation: Support hospital LAN operation when public internet connectivity is unavailable, subject to the deployment design.
- Integration readiness: Provide documented APIs and integration boundaries for approved external systems.
- Maintainability: Keep the initial architecture simple enough for a small development team to build, test and operate.

## 3. High-level architecture

The initial product should use a modular monolith rather than starting with microservices. The frontend and backend are separate deployable applications. The backend contains independently organized business modules.

Main components:

- React web application — user interface.
- Java Spring Boot application — business logic and API.
- PostgreSQL — transactional application database.
- DICOM archive/PACS — imaging study storage and retrieval.
- DICOM viewer — diagnostic image viewing.
- File/object storage — approved documents and attachments.
- Background processing — notifications, scheduled jobs and other asynchronous tasks.
- Monitoring and backup services — operational reliability and recovery.

Logical architecture

```text
Hospital Users
      |
      v
React + TypeScript Web Application
      |
      | HTTPS / REST API
      v
Java Spring Boot Backend
      |
      +-- Authentication & Authorization
      +-- Hospital / Facility Configuration
      +-- Patient Management
      +-- Encounter Management
      +-- Triage & Clinical Records
      +-- Orders & Referrals
      +-- Laboratory
      +-- Radiology Integration
      +-- Billing & Payments
      +-- Pharmacy & Inventory
      +-- Reports & Audit
      |
      +--------------------+
      |                    |
      v                    v
PostgreSQL           File / Object Storage
      |
      v
Background Jobs / Notifications
Radiology Modalities
      |
      | DICOM
      v
DICOM Archive / PACS
      |
      | DICOMweb or approved viewer integration
      v
Diagnostic Viewer
      |
      v
GCare HMS Radiology Workflow
```

## 4. Frontend architecture

### 4.1 Technology

- React
- TypeScript
- Vite or an equivalent approved build tool
- React Router
- A consistent component library and design system
- A server-state/data-fetching library selected during implementation
- Form validation library selected during implementation
- Vitest and React Testing Library for frontend tests

The final library choices must be documented before implementation. Avoid introducing multiple libraries that solve the same problem.

### 4.2 Frontend structure

Suggested folder organization:

```text
frontend/
├── public/
├── src/
│   ├── app/
│   │   ├── routes/
│   │   ├── providers/
│   │   └── layouts/
│   ├── assets/
│   ├── components/
│   │   ├── common/
│   │   ├── forms/
│   │   ├── tables/
│   │   └── feedback/
│   ├── features/
│   │   ├── authentication/
│   │   ├── dashboard/
│   │   ├── patients/
│   │   ├── encounters/
│   │   ├── triage/
│   │   ├── clinical/
│   │   ├── orders/
│   │   ├── laboratory/
│   │   ├── radiology/
│   │   ├── billing/
│   │   ├── pharmacy/
│   │   ├── inventory/
│   │   ├── reports/
│   │   └── administration/
│   ├── services/
│   │   ├── api/
│   │   ├── authentication/
│   │   └── permissions/
│   ├── hooks/
│   ├── types/
│   ├── utils/
│   └── main.tsx
├── tests/
├── package.json
└── README.md
```

### 4.3 Frontend responsibilities

The frontend must:

- Display screens and workflows according to the user's permissions.
- Validate input for usability while leaving authoritative validation to the backend.
- Communicate with the backend through documented APIs.
- Show loading, empty, error, success and offline states.
- Display patient identifiers prominently in patient-specific workflows.
- Prevent accidental repeated submissions where possible.
- Show the status and freshness of data when connectivity is interrupted.
- Avoid storing sensitive clinical information in browser storage unless a specific approved design requires it.
- Never treat hidden buttons or frontend route restrictions as the security boundary.

## 5. Backend architecture

### 5.1 Technology

- Java (version to be selected and pinned before project setup)
- Spring Boot
- Spring Security
- Spring Data JPA
- Hibernate
- Bean Validation
- PostgreSQL driver
- Flyway or another approved database migration tool
- OpenAPI documentation
- JUnit and Mockito for backend testing

### 5.2 Backend structure

Organize the backend by business module. Each module should contain its own controllers, services, repositories, entities and DTOs where appropriate.

```text
backend/
└── src/
    ├── main/
    │   ├── java/com/gcare/hms/
    │   │   ├── GcareHmsApplication.java
    │   │   ├── config/
    │   │   ├── security/
    │   │   ├── common/
    │   │   ├── audit/
    │   │   ├── organization/
    │   │   ├── identity/
    │   │   ├── patients/
    │   │   ├── encounters/
    │   │   ├── triage/
    │   │   ├── clinical/
    │   │   ├── orders/
    │   │   ├── laboratory/
    │   │   ├── radiology/
    │   │   ├── billing/
    │   │   ├── pharmacy/
    │   │   ├── inventory/
    │   │   ├── inpatient/
    │   │   ├── maternity/
    │   │   ├── reporting/
    │   │   ├── notifications/
    │   │   └── integrations/
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    └── test/
```

### 5.3 Backend responsibilities

The backend is responsible for:

- Authentication and authorization.
- Enforcing facility, department and tenant boundaries.
- Validating all business rules.
- Creating and updating patient records.
- Managing encounters and clinical workflows.
- Managing orders, results and referrals.
- Managing billing transactions and payment records.
- Enforcing approval and signing permissions.
- Recording audit events.
- Managing configuration and module entitlements.
- Exposing documented APIs.
- Coordinating approved integrations.
- Protecting data integrity through database transactions.

Business rules must not exist only in React. The backend is the authoritative source for validation and permission enforcement.

## 6. Database architecture

PostgreSQL will be the primary transactional database.

It will store:

- Organizations and hospital workspaces.
- Facilities and departments.
- Users, roles and permissions.
- Patients and patient identifiers.
- Encounters and clinical records.
- Triage observations.
- Orders and referrals.
- Laboratory results.
- Radiology metadata and report records.
- Billing, payments and receipts.
- Pharmacy and inventory transactions.
- Configuration and module settings.
- Audit events.

Database rules

- Use primary keys that are unique and stable.
- Use foreign keys for relationships.
- Add indexes for frequent patient searches and operational queues.
- Use database transactions for financial and clinical operations that must succeed or fail together.
- Preserve historical records and signed-document versions.
- Use explicit status fields and timestamps for workflow tracking.
- Use database migrations; do not make undocumented manual production schema changes.
- Store money using appropriate decimal types, never floating-point types.
- Store timestamps consistently and display them using the configured facility time zone.
- Apply tenant and facility scope checks to all relevant data access.

Patient records, clinical records and financial transactions must not be silently deleted to correct mistakes. Use approved correction, reversal, amendment or archival workflows.

## 7. Multi-hospital and tenant architecture

GCare HMS must support multiple hospital customers.

The system must distinguish:

- GCare platform administration.
- Customer organization.
- Hospital workspace.
- Facility.
- Department.
- Service point or unit.

Initial deployment recommendation

For the first pilot, use a dedicated hospital environment unless the implementation team has completed and independently tested a shared-tenant isolation design.

The architecture must allow future deployment models:

- Dedicated on-premises environment.
- Dedicated approved hosted environment.
- Shared hosted environment with strict tenant isolation, if separately approved.

In a shared environment, tenant isolation must be enforced by the backend and database design. A tenant identifier in a table is not sufficient by itself. Every relevant query, authorization decision, background job, export and integration must enforce the tenant boundary.

GCare support access must be approved, scoped, time-limited and audited.

## 8. Authentication and authorization

Use Spring Security for backend security.

Authentication

- Named individual accounts only.
- Secure password hashing.
- Configurable password and session policies.
- Account lockout or throttling against repeated failed attempts.
- Secure session/token handling.
- Logout and session revocation.
- Multi-factor authentication where approved and supported.

The final authentication mechanism (session-based or token-based) must be selected and documented before implementation. Do not store long-lived sensitive tokens in insecure browser storage.

Authorization

Use role-based access control combined with contextual checks.

Access decisions may depend on:

- User role.
- Customer organization.
- Facility.
- Department.
- Care relationship.
- Purpose of access.
- Specific permission or signing authority.

Examples:

- A registration clerk can register a patient but does not automatically receive access to clinical notes.
- A cashier can access authorized billing information but not unrestricted clinical records.
- A radiology technologist can manage acquisition workflow but cannot sign a final radiology report.
- A radiologist with the correct authority can finalize a report.
- An auditor can review approved audit records without changing clinical records.

Emergency break-glass access

Emergency access must:

- Require a reason.
- Be limited in duration and scope.
- Generate an audit event.
- Trigger an appropriate alert.
- Be available for authorized emergency purposes.
- Be reviewable by an approved privacy or security role.

## 9. Audit architecture

Audit logging is a core platform capability.

Record relevant events including:

- Login and failed login.
- Patient record creation and changes.
- Viewing of sensitive records where required.
- Clinical note creation, signing and amendment.
- Order creation and status changes.
- Result validation and correction.
- Report signing and addenda.
- Payment, refund, reversal and exemption.
- Permission and configuration changes.
- Data export.
- Emergency break-glass access.
- Administrative and support access.

Audit entries should capture the actor, action, affected object or patient, timestamp, facility/tenant scope, source information where available, and outcome.

Audit records must be protected from ordinary application users changing or deleting them. Access to audit data must itself be controlled and logged.

## 10. Radiology and DICOM architecture

GCare HMS must not directly replace a PACS or diagnostic image viewer.

Proposed workflow

- A clinician creates an imaging order in GCare HMS.
- The order appears in the radiology worklist.
- The modality acquires the study.
- The modality sends DICOM data to the configured archive/PACS.
- The archive stores the study.
- GCare HMS receives or retrieves the relevant study metadata through an approved integration.
- The system matches the study to the patient and order.
- Uncertain or mismatched studies enter an authorized reconciliation workflow.
- An authorized user opens the study in the approved diagnostic viewer.
- An authorized radiologist creates, signs or amends the report.
- The result is made available to authorized users and critical-result communication is tracked.

Integration considerations

- DICOM networking and supported services.
- DICOMweb where supported.
- Modality Worklist where supported and validated.
- Patient and order identifiers.
- Study reconciliation.
- Viewer launch and authentication.
- Storage capacity and retention.
- Backup and restore of imaging data.
- Access logging.

Orthanc may be evaluated as a candidate DICOM archive for the pilot. It is not a fixed requirement until actual modality compatibility, security, capacity and operational suitability have been tested.

The application database should store imaging references and workflow metadata, not all image bytes.

## 11. API architecture

The React frontend communicates with the Java backend through REST APIs.

API standards

- Use a consistent API base path, such as /api/v1.
- Use JSON for normal request and response bodies.
- Use HTTP status codes correctly.
- Validate all request data on the backend.
- Use DTOs rather than exposing database entities directly.
- Apply authorization to every protected endpoint.
- Use pagination for large result sets.
- Provide consistent error response structures.
- Document APIs with OpenAPI.
- Use idempotency controls for operations where duplicate submission could cause financial or clinical harm.

External integrations must use separately documented and secured interfaces.

## 12. Background processing and notifications

Use background processing for tasks that should not delay a user's request, such as:

- Approved notifications.
- Scheduled reports.
- Data imports.
- Integration retries.
- Reconciliation jobs.
- Long-running exports.
- System maintenance tasks.

The selected job-processing mechanism must be documented. Failed jobs must be visible to administrators, retry safely and avoid duplicate financial or clinical actions.

SMS or external notifications must only be enabled where approved. Sensitive patient information should not be included unnecessarily in notification messages.

## 13. File and document storage

Use a dedicated file/object storage boundary for attachments and generated documents.

Examples include:

- Approved patient documents.
- Referral attachments.
- Scanned documents.
- Receipts.
- Reports.
- Approved forms.

The database stores metadata and secure references. File access must be authorized by the backend. Validate file type and size, scan uploads where supported, and protect stored files from public access.

DICOM image storage remains the responsibility of the DICOM archive/PACS.

## 14. Offline and local-network operation

The initial design should support use over the hospital LAN without depending on continuous public internet access.

However, the system must not assume that every workflow can safely operate offline.

The implementation must define:

- Which workflows are permitted during an internet outage.
- Whether the backend and database remain available locally.
- How users identify stale information.
- How downtime forms are used.
- How records entered during downtime are reconciled.
- How duplicate patient and transaction creation is prevented.
- How synchronization is handled if a future distributed/offline model is approved.

Do not display a successful save message until the backend confirms the operation. Do not silently queue clinical or financial transactions without an approved design.

## 15. Deployment architecture

Development

- Developer workstation.
- React development server.
- Spring Boot application.
- Local PostgreSQL database.
- Synthetic test data.
- Docker Compose where useful.

Testing / staging

- Separate environment from development and production.
- Representative configuration.
- Synthetic or approved test data.
- Integration testing.
- User acceptance testing.
- Backup and restore testing.

Production

- Approved hospital server or hosting environment.
- React application served through a web server/reverse proxy.
- Spring Boot backend.
- PostgreSQL database.
- Private file storage.
- DICOM archive and viewer where enabled.
- Network segmentation and firewall rules.
- Monitoring, backup and recovery services.
- TLS for network connections as appropriate to the deployment.

Production must never use demo credentials or synthetic configuration accidentally carried over from development.

## 16. Security and privacy controls

The implementation must include:

- TLS for application traffic.
- Secure password storage.
- Least-privilege permissions.
- Tenant and facility isolation.
- Protected secrets and credentials.
- Encryption at rest where supported and required.
- Controlled file access.
- Audit logging.
- Secure backups.
- Dependency and vulnerability review.
- Input validation and output encoding.
- Protection against common web application vulnerabilities.
- Session expiration and revocation.
- Data export controls.
- Incident response procedures.

Applicable privacy, retention, hosting and health-data requirements must be confirmed for each deployment jurisdiction before real patient data is processed.

## 17. Testing strategy

Frontend

- Component tests.
- Form validation tests.
- Role-based screen tests.
- User workflow tests.
- Accessibility checks.

Backend

- Unit tests.
- Service/business-rule tests.
- Repository and database tests.
- API integration tests.
- Authorization tests.
- Tenant isolation tests.
- Audit tests.

End-to-end

Test complete workflows, including:

- Patient registration and duplicate detection.
- Encounter creation.
- Triage and clinical documentation.
- Order creation and result completion.
- Billing, payment and receipt.
- Refund and reversal authorization.
- Report signing and amendment.
- Break-glass access.
- Downtime reconciliation.
- Data export permissions.
- Backup restoration.

Clinical and financial workflows must be tested with negative cases, not only successful scenarios.

## 18. Performance and reliability

The PRD proposes that common patient searches and queue views should respond within three seconds at the 95th percentile on the hospital LAN under an agreed pilot load. This is a proposed target, not a guarantee.

Performance testing must use realistic patient volumes, concurrent users, network conditions and representative data.

The hospital and implementation team must agree on:

- Recovery Point Objective (RPO).
- Recovery Time Objective (RTO).
- Backup frequency.
- Backup retention.
- Off-site backup arrangements where approved.
- Restore-test frequency.
- Monitoring and escalation responsibilities.

## 19. Implementation phases

Phase 1 — Foundation

- Project setup.
- Authentication and authorization.
- Organization/facility configuration.
- Audit foundation.
- Database migrations.
- Core application layout.

Phase 2 — MVP patient and outpatient workflows

- Patient search and registration.
- Patient profile.
- Encounter management.
- Triage.
- Basic clinical notes.
- Orders.
- Billing and receipts.
- Core reports.

Phase 3 — Diagnostics

- Laboratory workflow within validated scope.
- DICOM archive integration.
- Radiology worklist.
- Study reconciliation.
- Viewer integration.
- Report signing and critical-result communication.

Phase 4 — Hospital expansion

- Pharmacy.
- Inventory.
- Emergency and inpatient.
- Maternity and newborn.
- Referrals.
- Additional facilities and departments.

Phase 5 — Advanced capabilities

- Patient portal.
- Expanded mobile/offline workflows.
- Advanced analytics.
- AI decision support only after separate governance and approval.

## 20. Architecture decisions requiring approval

Before implementation, the technical team must confirm:

- Java and Spring Boot versions.
- React build tooling and component library.
- Authentication/session strategy.
- Initial hosting model.
- Dedicated versus shared tenant deployment.
- Database backup and recovery approach.
- File storage solution.
- Background job mechanism.
- DICOM archive and viewer selection.
- Actual scanner and modality interoperability.
- API and interoperability standards required by the pilot.
- Offline workflow boundaries.
- Logging, monitoring and incident response tools.

## 21. Definition of architecture readiness

The architecture is ready for implementation when:

- The team approves the technology versions and project structure.
- The module boundaries are agreed.
- Authentication and authorization design is documented.
- Tenant and facility isolation are defined.
- Database and migration strategy is agreed.
- API conventions are documented.
- DICOM integration boundaries are understood.
- Deployment and backup plans are documented.
- Security and audit requirements are included in the foundation.
- Unresolved decisions have named owners and deadlines.

End of Document 03

The architecture is based on the PRD's modular, configurable multi-hospital approach, its role and facility access boundaries, its audit and downtime requirements, and its separation of the HMS from the DICOM archive and viewer.

Pasted text.txt

Pasted text.txt

Pasted text.txt

Pasted text.txt
