# GCare HMS — Product Requirements Document

Version 1.5 | Status: Engineering baseline, pending pilot-site validation | Updated 2026-11-23

> Supersedes `GCare_HMS_PRD_v1_4.docx`. v1.5 replaces the v1.4 Django technology direction with the approved React + Java Spring Boot stack (ADR-001) and restores the v1.4 requirement register, tables and governance sections that were dropped during condensation. Requirement-to-plan traceability is in [16-TRACEABILITY.md](16-TRACEABILITY.md).

## 1. Purpose and product

GCare HMS is a configurable hospital information system for multi-facility organizations. Liberia is the initial reference market; JFK Medical Center is a candidate reference configuration, not a confirmed customer or source of internal workflow truth. The product supports a longitudinal record and coordination across registration, outpatient care, diagnostics, radiology, billing, pharmacy, inventory, referrals, and operations.

**Approved technology direction:** React + TypeScript web client; Java + Spring Boot backend organized as a modular monolith; PostgreSQL transactional store. Imaging is handled by an external DICOM archive/PACS and diagnostic viewer, never by a custom Django image renderer or by storing image payloads in PostgreSQL.

## 2. Outcomes

- Authorized staff can find the correct patient and see care status across approved workflows.
- Reduce duplicate entry while preserving provenance and correction history.
- Provide accountable ordering, results, reporting, payments, and operational reporting.
- Operate on the hospital LAN under the deployment’s validated connectivity and power conditions.
- Configure country, tenant, facility, department, branding, catalog, tariffs, and enabled modules without customer-specific forks.

Targets remain provisional until baseline measurement and pilot approval.

### 2.1 Proposed outcome measures

*Restored from PRD v1.4.*

| Outcome | How success is measured (proposed) |
|---|---|
| Reliable patient identity | Duplicate patient rate and successful duplicate resolution tracked monthly |
| Faster service coordination | Median registration-to-triage and order-to-result times measurable by department |
| Closed diagnostic loop | Orders, studies, reports, and acknowledgement statuses linked and auditable |
| Accountable financial workflow | Charges, waivers, receipts, reversals, and cashier reconciliation traceable |
| Operational visibility | Approved daily and monthly service, workload, and revenue reports generated in system |
| Resilient operations | Core local workflows remain available through internet outages; backups are restorable |

### 2.2 Reference case and design implications

*Restored from PRD v1.4.*

| Public reference signal | Product implication |
|---|---|
| Multiple institutions/units: Memorial, maternity, ES Grant/mental health, and rehabilitation are presented on JFKMC public pages. | Model organizations, facilities, departments, locations, and service points as configurable entities. |
| Clinical services include medicine, dentistry, ophthalmology, pediatrics, obstetrics and gynecology, pathology, radiology, psychiatry, and anesthesia. | Use a shared patient identity and role-aware departmental workflows, with phased activation. |
| Radiology information lists CT, X-ray, ultrasound/echography, and EKG. | Design a modality-neutral imaging order and study workflow; test actual device interoperability per scanner. |
| Public program descriptions include under-five services, social services, elderly care, and infectious disease services. | Support configurable eligibility categories, subsidies/waivers, and restricted sensitive workflows, subject to approved policy. |
| JFKMC service information publishes fees and categories. | Provide a versioned tariff catalog and auditable exceptions; do not hard-code public website prices as current policy. |

The website is a public-facing source, not an internal system specification. Validate with JFKMC or the selected pilot facility before adopting any workflow, organization chart, fee, staffing pattern, or equipment assumption.

## 3. Users and access

Named accounts only. Roles include registration, triage/nursing, clinician, laboratory, technologist, radiologist, cashier, finance, pharmacy, inventory, administrator, auditor, and support/operator. Authorization is enforced server-side and scoped by tenant, facility, department, care relationship, and purpose as applicable. Break-glass requires reason, time limit, alert, and immutable audit entry.

### 3.1 Role definitions

*Restored from PRD v1.4.* Final permissions come from the signed permission matrix ([project-record/permission-matrix.md](project-record/permission-matrix.md)).

| Role | Primary tasks | Access boundary |
|---|---|---|
| Patient registration clerk | Search/create patient, verify demographics, register visit, print identifier | Demographics and encounter registration; no clinical note access by default |
| Triage nurse | Capture vitals, urgency, initial observations | Assigned unit and encounter; clinical documentation |
| Clinician | Review history, document assessment, place orders, prescribe, refer | Care relationship and assigned unit; emergency break-glass with audit |
| Radiology technologist | Receive worklist, acquire study, mark study status | Imaging orders and acquisition metadata; no final report signing |
| Radiologist | Review images, draft/finalize report, amend report | Imaging studies and related clinical context; signing authority |
| Laboratory staff | Receive orders, record specimens/results, validate results | Laboratory workflow; controlled result validation |
| Pharmacist | Review and dispense medication orders, manage stock | Medication and inventory functions |
| Cashier/billing officer | Apply charges, record payments, print receipts, reconcile shift | Financial data; no unrestricted clinical note access |
| Department manager | Monitor queues and department reports | Aggregated data and assigned department records as approved |
| System administrator | Configure users, facilities, catalogs, devices, integrations | Technical administration; privileged actions audited |
| Auditor/privacy officer | Review access history and compliance evidence | Read-only audit access with approval process |

## 4. Scope and releases

### Pilot MVP

- Authentication, organization/facility configuration, user and role administration, audit foundation.
- Patient registration/search, stable internal identifier, duplicate candidates, controlled correction and merge/link workflow.
- Outpatient encounter, triage observations, clinical note draft/sign/amend lifecycle, orders and referrals.
- Service catalog, effective-dated tariffs, invoice, payments, receipts, exemptions, reversals/refunds, cashier reconciliation.
- Operational dashboards/reports, permissioned exports, downtime forms and reconciliation.
- One radiology pathway integrated with a selected DICOM archive/viewer after device and network validation.

### Later, separately approved

Laboratory, emergency/inpatient/maternity, pharmacy, inventory, additional facilities and national integrations. Patient portal, advanced offline sync, SMS, AI decision support, and biometric-assisted identity are gated capabilities. AI remains advisory and never signs reports; biometric matches never confirm identity or auto-merge records.

### Release plan and exit conditions

*Restored from PRD v1.4.* Pilot scope is narrowed by ADR-005: laboratory moves from the diagnostics release to "later, separately approved".

| Release | Included capability | Exit condition |
|---|---|---|
| Foundation and discovery | Workflow mapping, governance, infrastructure survey, data dictionary, prototype, security model, procurement/integration inventory | Clinical and operational owners approve workflows, pilot boundaries, readiness gaps, and success measures |
| MVP pilot | User/facility setup, patient identity, registration, outpatient encounter, triage, basic clinical note, orders, billing/receipts, audit, core reports, training and support | Pilot users complete agreed scenarios; reconciliation, backup restore, and downtime drills pass; leadership authorizes expansion |
| Diagnostics phase | DICOM archive and viewer, modality connectivity, radiology worklist, study reconciliation, report draft/sign/amend, result notification; laboratory orders/results if in validated scope | End-to-end tests pass with actual modality and representative studies; radiology leadership accepts workflow |
| Hospital expansion | Emergency/inpatient, maternity, pharmacy, inventory, referrals, additional facilities/departments, national reporting/API integration | Each department signs off; performance and support capacity demonstrated |
| Advanced capabilities | Patient portal, approved SMS, expanded mobile/offline workflows, advanced analytics, AI decision support subject to governance | Separate business case, clinical safety case, privacy/security review, local validation, and approval |

## 5. Core workflows

### Outpatient visit
Search and resolve candidate duplicates → register visit and eligibility → triage → clinician reviews history and documents encounter → orders/referrals → services return results → cashier records approved charges/payments → clinician closes with plan and follow-up.

### Imaging
Clinician orders with indication/priority → scheduling/worklist if supported → technologist confirms identity → modality sends DICOM to archive → system links by validated identifiers or queues uncertain match → radiologist views, drafts and signs → amendments preserve prior version → critical-result communication is acknowledged and audited.

### Financial correction
Use approved effective tariff and eligibility → record exemption basis and approver → issue receipt only after successful payment record → corrections use authorized reversal/refund with reason; preserve original transactions.

### Unknown emergency identity (future module)
Temporary identity permits care without waiting for identification, connectivity, or biometrics. Later linkage is reviewed and audited; no silent merge.

### Unknown emergency identity — detailed steps (future module)

*Restored from PRD v1.4.*

- Create a temporary emergency identity and proceed with triage and treatment without waiting for biometric enrollment, consent, network availability, or a match.
- If approved by local policy and technically available, authorized registration staff may perform a biometric search to retrieve possible existing records. A biometric result is a lead for identity verification, not confirmation.
- Compare candidate records against available details such as name, date of birth/estimated age, known contacts, documents, or clinical context. If uncertain, retain the temporary identity and send the case to an authorized reconciliation queue.
- Record who initiated the search, purpose, time, device/result status, verification basis, and any access to candidate records. Notify the patient or representative and complete required notice/consent steps when practical and lawful.
- When identity is later confirmed, link the temporary emergency encounter under a controlled, auditable process; do not erase the temporary identity or merge records silently.

## 6. Functional requirements

- Patient search, creation, duplicate detection, controlled merge/link and provenance.
- Encounter lifecycle with authorship, timestamps, status, amendment history and patient identity confirmation.
- Structured observations, notes, diagnoses, orders, referrals and result status.
- Imaging order/study/report linkage, reconciliation queue, viewer launch and report signing controls.
- Effective-dated catalogs/tariffs, currency-safe invoices, payments, receipts, exemptions and controlled corrections.
- Configurable facilities, departments, branding and modules with versioned approval/publishing.
- Reports show date range, source, filters, generation time and requesting user; exports are permissioned and logged.
- Open-format data export including clinical records, audit extracts, configuration, and DICOM metadata/studies as contractually applicable.

Detailed user acceptance scenarios must be approved by department owners before each module is activated.

### 6.1 Requirement register (FR-001 – FR-046)

*Restored from PRD v1.4.* IDs are stable: reference them in tickets, tests and PRs. Pilot vs later scope and plan-task mapping are in [16-TRACEABILITY.md](16-TRACEABILITY.md). "Must" means required for the release that contains the capability, not for the pilot.

Priority legend: Must = required for the relevant release; Should = strongly preferred; Could = later option. Detailed acceptance scenarios are finalized with users during discovery.

| ID | Requirement | Description | Priority |
|---|---|---|---|
| FR-001 | Facility and department configuration | Create multiple facilities, departments, service points, rooms, shifts, locations, and operating calendars. Assign users and services to one or more scopes. | Must |
| FR-002 | User and access administration | Create named user accounts; assign roles and scoped permissions; enforce account activation, deactivation, password/session policy, and privileged-action logging. | Must |
| FR-003 | Patient search and identity | Search using patient number and configurable demographic identifiers; present possible matches before creating a new record; support documented merge/unmerge under authorized review. | Must |
| FR-004 | Patient registration | Capture approved demographics, contacts, next of kin, address/locality, preferred language, identity-document details where available, and registration source. Allow unknown or incomplete details when care must proceed. | Must |
| FR-005 | Patient identifier | Issue a unique, human-readable facility identifier with check digit or equivalent collision protection. Support labels/cards/barcodes where hardware is available; never depend solely on a national ID. | Must |
| FR-006 | Encounter management | Open, update, transfer, close, and reopen encounters with encounter type, department, clinician, status, timestamps, and linked notes/orders. | Must |
| FR-007 | Triage and observations | Record configurable vital signs, pain, allergies, urgency, and triage notes with units, timestamps, user attribution, and escalation flags. | Must |
| FR-008 | Clinical record | Record problems/diagnoses, history, examination, assessment, plan, allergies, medication, procedures, and attachments. Preserve authorship and amendment history. | Must |
| FR-009 | Orders and referrals | Create diagnostic, medication, procedure, and internal/external referral orders; show order status, priority, indication, requester, and timestamps. | Must |
| FR-010 | Imaging worklist | Present radiology work queues by modality/service, priority, scheduled time, and state; prevent unlinked studies from silently becoming final results. | Must |
| FR-011 | DICOM receive and link | Receive DICOM studies through configured archive; match patient/order using approved identifiers and metadata; flag uncertain/mismatched studies for authorized reconciliation. | Must |
| FR-012 | Image viewing | Open a study in a licensed/approved DICOM viewer supporting diagnostic workflows as determined by radiology; preserve study identity and user access audit. | Must |
| FR-013 | Radiology reporting | Create structured or free-text report templates by examination; save draft, sign final, issue addendum/amendment with reason, and maintain version history. Only authorized clinicians can sign. | Must |
| FR-014 | Critical result communication | Flag critical findings using locally approved policy; record recipient, method, time, acknowledgement, escalation, and unresolved state. | Must |
| FR-015 | Laboratory workflow | Manage lab orders, specimen labels/accession, collection, processing, result entry, validation, correction, and critical-result notification. | Must |
| FR-016 | Medication and pharmacy | Maintain formulary/catalog; create prescriptions; review allergies/interactions as configured; dispense quantities; record returns and stock movements. | Must |
| FR-017 | Billing and tariff catalog | Configure services, price lists, payer/category, currency, effective dates, discounts, exemptions, and authorization rules; preserve the tariff used on each transaction. | Must |
| FR-018 | Payments and receipts | Record cash and approved non-cash payment methods, split/partial payments, refunds/reversals with authorization, printable receipt numbers, and cashier reconciliation. | Must |
| FR-019 | Program eligibility | Configure approved subsidy/eligibility schemes (such as age- or program-based categories) with evidence, effective dates, approval, and audit trail. | Must |
| FR-020 | Inventory | Track catalog items, stores, batches, expiry, receipts, transfers, issues, adjustments, reorder threshold, and stocktake; support role separation for approval. | Must |
| FR-021 | Inpatient and bed management | Track admission, ward/bed, transfer, observation, discharge, discharge summary, and movement history. | Should |
| FR-022 | Maternity and newborn record | Support configurable antenatal, delivery, postpartum, and newborn workflows, linked mother/infant records, and approved forms. | Should |
| FR-023 | Notifications | Show in-app task/result alerts and, only where approved and available, SMS notifications with minimal sensitive content and delivery status. | Should |
| FR-024 | Operational dashboards | Show role-scoped queues and approved measures: arrivals, waiting, appointments, order turnaround, unreported studies, bed occupancy, charges, receipts, and stock alerts. | Must |
| FR-025 | Reports and export | Generate scheduled operational and statutory reports; export only authorized fields; log exports and support de-identification for approved analytics. | Must |
| FR-026 | Audit and patient access history | Record user, action, patient/object, time, source/device, and outcome for view/change/export/sign/override events. Allow authorized review and export. | Must |
| FR-027 | Downtime and recovery workflow | Provide printable downtime forms, post-outage entry/reconciliation, duplicate prevention, and a documented reconciliation queue. | Must |
| FR-028 | Data import | Import approved legacy demographics and reference catalogs with validation, duplicate review, reconciliation counts, and rollback plan. | Should |
| FR-029 | AI decision support | If separately approved, display model name/version, intended use, input study, output, confidence/limitations, and clinician disposition. AI output remains advisory and cannot sign reports. | Could |
| FR-030 | Configuration and localization | Support internationalization and per-deployment configuration for languages, translated labels, date/time formats, time zones, calendars, measurement units, address/name formats, currencies, tax/fee rules, facility terminology, and printed forms. English is the initial product language; the Liberia reference configuration includes Liberian dollar support. | Must |
| FR-031 | Country and jurisdiction configuration | Maintain deployment-level configuration for country/region, applicable privacy and retention policies, consent language, health reporting requirements, identifiers, coding/terminology catalogs, and approved integrations. Configuration must be versioned and must not silently change historical records. | Must |
| FR-032 | Deployment and tenant isolation | Support separately governed customer deployments and, if multi-tenant hosting is chosen, enforce tenant-level separation for data, users, configuration, encryption keys, backups, and audit. Default to isolated deployment boundaries until a hosting model is explicitly approved. | Must |
| FR-033 | Standards-based interoperability | Provide documented, versioned APIs and support an agreed HL7 FHIR release and implementation profiles for applicable clinical and administrative exchanges. Maintain interface contracts, conformance tests, error handling, and compatibility records for each external integration. National profiles and permitted data exchange must be agreed with the relevant health authority. | Must |
| FR-034 | Patient access | Provide a phased patient-facing portal or mobile web experience for identity-verified patients to view appointments, approved care instructions, and released results/reports; support locally approved correction, consent, and record-request processes. Keep sensitive content out of unapproved messaging channels. | Should |
| FR-035 | Offline continuity and synchronization | Define which high-priority tasks can continue during internet or service interruptions. Use encrypted, minimum-necessary local data where offline entry is approved; show data freshness and sync state; prevent duplicate submissions with idempotent identifiers; queue, reconcile, and visibly resolve conflicts after reconnection. | Must |
| FR-036 | Operational monitoring and alerting | Monitor application availability, background jobs, database/storage capacity, backup completion, restore status, DICOM receive queues, modality connectivity, and interface failures. Notify named operational owners with severity, timestamp, affected service, and recovery guidance; avoid patient identifiers in alerts. | Must |
| FR-037 | Accessible user experience | Target WCAG 2.2 AA for core web workflows, subject to local legal requirements and user testing. Support keyboard use, visible focus, readable contrast, scalable text, accessible form labels/errors, and responsive layouts; assess the diagnostic viewer separately. | Must |
| FR-038 | Optional biometric-assisted patient identification | Allow an authorized staff member to capture an optional fingerprint or other specifically approved biometric and search for candidate patient records when identity is unknown, including emergency arrivals. Treat results as candidate matches only: require staff verification against available demographics/context before linking records; never automatically merge records or use a biometric match as sole proof of identity. Store a protected biometric template rather than raw fingerprint imagery where technically feasible; separate and encrypt the biometric index, restrict access, log every enrollment/search, support purpose notice and consent/approved legal basis, retention/deletion, and non-biometric alternatives. Enable only after jurisdictional legal/privacy review, clinical safety assessment, and approval. For Liberia, confirm lawful processing and sensitive-data safeguards under the applicable Act and implementing rules. Emergency treatment must not be delayed when consent, device, connectivity, or a usable biometric is unavailable. | Should |
| FR-039 | Hospital workspace provisioning | Allow authorized GCare operations staff to create, configure, suspend, export, and close a hospital workspace. Capture customer organization, facilities, contacts, hosting model, country pack, enabled modules, subscription entitlements, support tier, and named customer administrators. Provisioning must be auditable and use an approved customer onboarding checklist. | Must |
| FR-040 | Configuration inheritance and versioning | Support configuration layers for GCare product defaults, country packs, customer organizations, hospitals, facilities, and departments. Hospital administrators can edit permitted settings without code changes. Show inherited values, local overrides, change history, approver, effective date, rollback, and impact before publishing a change. | Must |
| FR-041 | Selectable modules and packages | Maintain a catalog of modules and module dependencies. Enable modules per customer subscription and facility readiness, with clear access controls and staged activation. Modules may include registration, clinical records, billing, pharmacy, laboratory, imaging, inpatient, maternity, inventory, patient portal, and AI capabilities. | Must |
| FR-042 | Hospital workflow configuration | Provide authorized configuration tools for forms, fields, requiredness, queues, statuses, service catalogs, tariffs, payer categories, eligibility rules, report templates, department names, and user roles. Validate configuration changes and preserve clinical record meaning and history. | Must |
| FR-043 | Customer branding | Allow per-hospital logo, display name, approved color palette, contact details, and branded receipts, reports, portal pages, and printable forms. Show GCare HMS product identification by default alongside the hospital brand; any white-label option is controlled by contract and must not misrepresent clinical responsibility. | Must |
| FR-044 | Upgrade-safe extensions and integrations | Provide documented extension points and tenant-specific integration adapters for local systems. Keep extensions versioned, permissioned, reviewed, and compatible with supported product releases. Do not require a separate unmaintained source-code fork for ordinary hospital configuration. | Must |
| FR-045 | Customer environment and tenant isolation | Support dedicated on-premises or approved hosted environments and, if offered, a shared service with strict tenant isolation. Maintain separate tenant data, configuration, keys, backups, audit records, and administrative scopes. GCare support access is time-limited, approved, and logged. | Must |
| FR-046 | Product release management | Support staged releases, release notes, test/sandbox environments, configuration compatibility checks, database migrations, backup before upgrade, rollback/forward recovery plans, and customer maintenance windows. Provide a defined support lifecycle for each release. | Must |

## 7. Non-functional and safety requirements

- Least privilege, named users, tenant/facility isolation, encryption in transit and at rest where supported, secure secret handling, audit trails, tested backup/restore.
- No silent overwrite of signed clinical documents or completed financial transactions.
- Store timestamps as timezone-aware instants; display facility-local time. Preserve source/effective dates and external identifiers.
- Validated downtime and reconciliation process; no silent duplicate encounter/order/payment creation.
- Accessible core workflows targeting WCAG 2.2 AA, verified by automated and human review.
- Performance, availability, recovery objectives, retention and archive sizing are discovery outputs, not promises until measured and contracted.

### 7.1 Proposed non-functional targets

*Restored from PRD v1.4.* All values are provisional until measured under site conditions and agreed in the deployment record.

| Area | Proposed requirement |
|---|---|
| Availability | Core service target to be agreed after infrastructure survey; design local network operation to continue without public internet. Measure planned and unplanned downtime. |
| Performance | Common patient search and queue views should respond within 3 seconds at the 95th percentile on the hospital LAN under agreed pilot load; study opening depends on image size/network and is measured separately. |
| Scale | Support multiple facilities and departments; size users, visits, and DICOM storage after data-volume and retention discovery. |
| Recovery | Set recovery point and recovery time objectives with hospital leadership; provide encrypted backups, off-site copy where approved, restore drills, and documented ownership. |
| Connectivity | Assume intermittent internet and variable power; core app should work over LAN, with UPS/generator and network resilience addressed in site readiness. |
| Usability | English first; clear workflows, large readable controls, keyboard-friendly registration, printable outputs, and user-tested training materials. |
| Accessibility | Target WCAG 2.2 AA for core application workflows; validate with automated checks and human testing, including keyboard and assistive technology review. Assess diagnostic viewer accessibility separately. |
| Maintainability | Document deployment, configuration, integrations, schemas, change log, and operator procedures; use supported dependency versions and automated deployment controls. |
| Auditability | Use synchronized clocks where possible; log time source and operational health; audit entries are tamper-resistant and exportable to authorized auditors. |
| Portability | Back up application database and DICOM archive independently; document restoration and migration to another supported environment. |
| Operational monitoring | Provide health checks, service metrics, centralized error logs, alert routing, and operator runbooks; exclude patient details from general telemetry and alerts. |

## 8. DICOM requirements

Inventory modality make/model/software, conformance statement, supported C-STORE, Modality Worklist, MPPS, Storage Commitment/DICOMweb, network and vendor support. Restrict traffic to known devices. Validate C-ECHO and representative study transfer, identity/order matching, images/series/orientation/timestamps/compression, archive/viewer performance, backup/restore and downtime. Orthanc is a candidate for evaluation only; radiology leadership approves clinical suitability.

### 8.1 Validation steps

*Restored from PRD v1.4.*

- Inventory each CT, X-ray, ultrasound, and other modality: manufacturer/model, software version, DICOM conformance statement, network port, AE title, supported transfer syntaxes, and vendor support contact.
- Confirm whether the scanner supports DICOM Storage (C-STORE), Modality Worklist, MPPS, Storage Commitment, and/or DICOMweb; support varies by model and license.
- Provide a stable hospital LAN address and configure the modality and archive with unique AE titles, IPs, ports, and mutually agreed network rules. Restrict DICOM traffic to known devices and hospital network segments.
- Run DICOM C-ECHO for basic reachability, then send test studies. Verify patient/study identifiers, image counts, series, orientation, timestamps, compression, and correct patient linkage.
- Enable modality worklist only after scheduling/order data mapping is verified to reduce demographic re-entry and mismatched studies. Test downtime and manual fallback.
- Validate archive storage, replication/backup, restore, audit, viewer performance, and diagnostic displays with radiology leadership before clinical use.

### 8.2 Integration types

| Integration | Use | Design note |
|---|---|---|
| DICOM C-STORE | Scanner sends completed images to archive | Common first integration; confirm supported SOP classes and transfer syntax per modality. |
| Modality Worklist | Scanner retrieves scheduled patient/procedure details | Reduces re-keying; requires scheduling/order mapping and modality support. |
| MPPS / Storage Commitment | Procedure status and storage confirmation | Enable if supported and operationally useful; do not assume vendor support. |
| DICOMweb QIDO-RS/WADO-RS/STOW-RS | Search, retrieve, and store DICOM over HTTP | Useful boundary between application/viewer and archive; secure with network controls and authentication. |
| FHIR / national interfaces | Exchange clinical/administrative records where approved | Coordinate with the applicable health authority and existing programs before selecting profiles or identifiers; the Liberia reference deployment coordinates with its Ministry of Health. |

## 9. Interoperability and jurisdiction

National identifiers remain optional until approved. Confirm national HMIS/EMR interface, data elements, terminology, consent, data-sharing and hosting requirements with the relevant authority before implementing interfaces. Liberia-specific laws and operating rules require qualified local review; this document is not legal advice.

### 9.1 Data principles

*Restored from PRD v1.4.*

- Use a stable internal patient ID; keep national ID optional unless the applicable health authority mandates a validated identifier.
- Capture source and effective date for demographics, tariffs, diagnoses, and external identifiers. Preserve original data and provenance during migrations.
- Use a consistent time strategy: store timestamps with timezone; display local time configured for the facility; record device time uncertainty when needed for imaging reconciliation.
- Define an approved terminology/catalog strategy for services, diagnoses, medications, lab tests, imaging procedures, units, and locations. Avoid free text where reliable structured choices are feasible; retain a free-text pathway for exceptional care.
- Coordinate reporting and exchange with the applicable national HMIS/EMR architecture and its approved identifiers, APIs, data elements, and consent rules before building national-facing interfaces. The Liberia reference deployment must coordinate with the Ministry of Health.
- Provide a controlled data export and exit strategy in documented, non-proprietary formats, including clinical records, audit extracts, and DICOM studies/metadata.

## 10. Acceptance and pilot gates

Pilot cannot use real patient data until: owners approve workflows and permission matrix; duplicate scenarios are signed off; financial scenarios reconcile against verified examples; representative imaging transfers and uncertain matches are handled safely; authorized report signing/amendment and critical-result communication are demonstrated; backup restore and downtime reconciliation pass; security findings are triaged with critical issues resolved; alerts have named owners; training/support and incident process are live; and accountable facility leadership authorizes launch.

### 10.1 Full release-gate checklist

*Restored from PRD v1.4.* Gates for modules outside the pilot (portal, biometrics) apply only when those modules are separately approved.

- Approved role and permission matrix exists; all production users have named accounts.
- Patient creation, duplicate detection, correction, and merge scenarios are signed off by registration leadership.
- End-to-end visit and billing scenarios balance expected charges, receipts, exemptions, and reversals against manually verified examples.
- Radiology pilot receives representative studies from the target modality, preserves image integrity, and links correct patient/order; ambiguous matches are held for reconciliation.
- Radiologist can view, draft, sign, amend, and communicate a report; only authorized roles can sign; access and changes appear in audit.
- Backup restoration is demonstrated for application data and imaging archive; owners document recovery steps and expected recovery times.
- Downtime drill is completed and reconciliation is demonstrated without losing or silently duplicating encounters/orders.
- API interfaces have versioned contracts and pass agreed interoperability/conformance scenarios with partner systems.
- Operational alerts are tested for failed backups, low storage, stalled DICOM transfers, and service outages; named owners receive and acknowledge them.
- Core user workflows are reviewed against the WCAG 2.2 AA target using automated and human accessibility checks.
- Patient portal access is not enabled until identity verification, release rules, consent, and support workflows are approved and tested.
- If biometric identification is approved, test enrollment notice and consent, candidate matching, false/ambiguous match handling, emergency fallback, audit review, retention/deletion, and breach-response scenarios before activation.
- Security review findings are triaged; critical issues are resolved before production and residual risks are accepted by accountable leadership.
- Users complete role-based training; support contact, incident escalation, and change process are live.
- Department and facility owners approve their workflows, forms, reports, and service catalog before activation.

## 11. Discovery decisions

Confirm pilot facility/departments and approvers; current registration/clinical/radiology/billing processes; patient identifiers and legacy data; tariffs/exemptions/currency/tax; actual modalities and DICOM capability; LAN, power, server and storage; hosting, data residency, privacy, retention and sharing; national interfaces; terminology; local support and operations. Document owner, decision, evidence, date, and review date for each.

### 11.1 Discovery checklist

*Restored from PRD v1.4.* Record answers in [project-record/](project-record/README.md).

- Which country, legal entity, facilities, and departments are included in the first pilot, and who approves each workflow?
- What are current registration, outpatient, emergency, inpatient, maternity, lab, radiology, billing, pharmacy, and referral steps?
- What patient identifier is currently used, and what duplicate records or legacy data exist?
- Which CT, X-ray, ultrasound, and other equipment models/software versions are installed; do vendors enable DICOM C-STORE and Modality Worklist?
- What are daily study counts, average study sizes, retention requirements, backup media, and archive capacity needs?
- What LAN/WAN, Wi-Fi, power, generator/UPS, server room, and internet availability can be relied on?
- Which tariffs, exemptions, payer categories, currencies, tax rules, and receipt/reconciliation rules are approved in the launch jurisdiction?
- What existing HMIS/EMR, laboratory, pharmacy, finance, identity, or national reporting systems must interoperate?
- What national standards, data-sharing agreements, privacy requirements, hosting constraints, and reporting formats apply?
- Will biometric identification be used? If so, which modalities/devices, enrollment process, legal basis, retention, local matching boundary, and non-biometric fallback are approved?
- Who will operate the service, answer support requests, manage access, patch servers, restore backups, and maintain scanner integrations?
- Which languages, date/address conventions, coding systems, currencies, and local support arrangements are needed for future target markets?

## 12. Explicit exclusions

No autonomous diagnosis, report signing by AI, replacement of PACS/viewer, parallel national identity, automated claims adjudication, payroll, advanced accounting, or full procurement in MVP. No assumption of reliable internet, a particular scanner, approved national interface, or confirmed JFKMC participation.

## 13. Analytics and reporting

*Restored from PRD v1.4.*

| Audience | Example reports |
|---|---|
| Clinical leadership | Visit volume by service, waiting time, diagnosis mix, referral completion, critical-result acknowledgement, radiology/lab turnaround. |
| Operations | Daily census, encounter status, department workload, staffing/queue pressure, bed occupancy, equipment downtime. |
| Finance | Charges by tariff/service/category, collections by payment method, exemptions, refunds, cashier reconciliation, outstanding balances. |
| Pharmacy/stores | Stock on hand, expiry risk, consumption, stockouts, adjustments, reorder alerts. |
| National/regulatory reporting | Only approved indicators, formats, schedules, and data fields after coordination with the applicable health authority and authorization. |

Reports must display date range, source, filters, generation time, and user. Small-cell suppression/de-identification should be configurable for secondary use where appropriate. Exports are permissioned and logged.

## 14. Assumptions, dependencies and risks

*Restored from PRD v1.4.* Clinical hazards are tracked separately in [project-record/hazard-log.md](project-record/hazard-log.md).

| Type | Item | Response |
|---|---|---|
| Assumption | JFKMC-like services and departments are a design reference, not a confirmed implementation contract. | Validate scope with facility leadership and frontline users. |
| Dependency | Scanner models and DICOM licenses vary. | Collect conformance statements and conduct vendor-supported connectivity proof before committing dates. |
| Dependency | Hospital LAN, server room, stable power, backup connectivity, and trained operators may vary. | Complete site readiness assessment; budget UPS, network segmentation, cooling, storage, and support. |
| Dependency | National EMR/HMIS deployment and interfaces may change. | Request applicable health authority architecture and coordinate before creating national identifiers or reporting interfaces. |
| Risk | Duplicate records or wrong-patient study linkage can create clinical harm. | Use identity confirmation, match confidence/reconciliation queue, and audit; prohibit silent ambiguous auto-linking. |
| Risk | Biometric data can cause lasting harm if exposed, misused, or falsely matched. | Make collection optional and purpose-limited; require jurisdictional legal/privacy review and a privacy impact assessment; isolate and encrypt templates; tightly restrict and audit searches; retain non-biometric alternatives; prohibit automatic record merges and emergency-care blocking. |
| Risk | Image storage growth can exceed capacity and backup budget. | Measure per-modality volume, define approved retention, forecast capacity, test restoration, and monitor storage. |
| Risk | Internet/power outage disrupts digital operations. | Local hosting, UPS/generator/network plans, printed downtime workflow, and reconciliation drills. |
| Risk | Scope expands across many departments before the pilot is stable. | Use release gates and named departmental owners; no module enters production without sign-off. |
| Risk | AI suggestions may be inaccurate for local population or image protocols. | Defer AI; require external evidence, local validation, safety governance, clinician control, and rollback. |
| Risk | Privacy or regulatory obligations are misinterpreted in a deployment jurisdiction. | Obtain current local legal/privacy advice and health authority guidance before real patient data processing; maintain a country-specific compliance register. |

## 15. Implementation and governance

*Restored from PRD v1.4.* Named owners are recorded in [project-record/approvals.md](project-record/approvals.md).

| Workstream | Accountable owner (proposed) | Core participants |
|---|---|---|
| Product and clinical governance | Hospital executive sponsor / steering committee | Medical leadership, nursing, department heads, operations |
| Clinical workflow and safety | Clinical informatics lead | Clinicians, radiology, laboratory, pharmacy, nursing |
| Technology and hosting | Hospital IT lead | Network/server administrators, vendor, security adviser |
| Imaging integration | Radiology lead and IT integration lead | Biomedical engineering, scanner vendor, PACS/viewer vendor |
| Finance and eligibility | Finance lead | Cashiers, billing, social services/program representatives |
| Privacy and security | Designated privacy/security owner | Hospital management, legal adviser, IT, Ministry liaison |
| Training and change | Implementation lead | Department champions, HR/training, help desk |
| National interoperability | Ministry of Health liaison | HMIS/ICT/HMER counterparts and approved partners |

Use short delivery cycles with review by actual users. Keep a decision log for workflow, terminology, tariffs, patient identifiers, data access, retention, and integration choices. Any clinical workflow or AI change requires the designated clinical governance owner’s approval.

## 16. Customer onboarding and service model

*Restored from PRD v1.4.*

GCare HMS is one maintained product configured for each hospital. A new hospital should receive a discovery and readiness review, a configured workspace or dedicated environment, agreed modules, migrated and reconciled data where approved, integrations, training, and operational support. Customer-specific behavior should be delivered through tested configuration and supported extensions rather than permanent customer code forks.

| Onboarding stage | Required output |
|---|---|
| Qualify and discover | Customer profile, country and legal context, facility inventory, workflow map, user roles, integration/device inventory, hosting and connectivity assessment, data migration scope, and success measures. |
| Configure | Country/locale settings, facilities/departments, subscribed modules, staff roles, forms, catalogs, tariffs, templates, branding, integrations, support contacts, and security/privacy settings. |
| Validate | Customer review of configurations, interface tests, migrated-data reconciliation, access/security review, backup and downtime drills, user acceptance, and clinical/finance sign-off. |
| Launch | Approved go-live checklist, trained users, named support contacts, monitoring and escalation, rollback plan, and explicit authorization to process real patient data. |
| Operate and improve | Customer support, patching/upgrades, service monitoring, periodic access review, backup restore drills, configuration change approvals, incident review, and roadmap feedback. |

### 16.1 Product branding

Present the system as GCare HMS. Let each hospital configure its own logo, name, contact details, color theme, and document/portal branding within defined design and safety limits. The default presentation should preserve GCare HMS identification alongside the hospital identity; a white-label option can be offered by contract. Customer branding must not obscure which organization is responsible for care, data handling, or support.

### 16.2 Commercial packaging and operations

- Define modular product packages and optional modules, with implementation and support services described separately from software access.
- For each customer agreement, set hosting model, support tier, upgrade policy, service targets, data ownership, backup responsibilities, retention, export, and exit assistance.
- Maintain separate demo/sandbox and production environments; demos use synthetic data only.
- Give hospital administrators scoped tools to manage local users and approved configuration, while vendor operations retain global release and platform-security controls.
- Provide a documented, open-format data export and a customer exit process that includes clinical records, audit records, configuration, and imaging archive data as applicable.

## 17. Source references

*Restored from PRD v1.4.* Not independently re-verified for v1.5 (see [REVIEW.md](REVIEW.md#limitations)).

Public references used to ground this planning document. Public website material is descriptive and must not be treated as internal operating procedure. Standards references inform proposed requirements; the selected versions and profiles must be validated for each deployment.

- JFK Medical Center homepage — https://jfkmc.gov.lr/ (Public institution overview, facilities, programs, and departments.)
- JFKMC Radiology — https://jfkmc.gov.lr/radiology/ (Public description of radiology services and listed modalities.)
- JFKMC Service Delivery Charter — https://jfkmc.gov.lr/wp-content/uploads/2025/01/2024-SAMPLE-SERVICE-DELIVERY-CHARTER-JFKMC.pdf (Published service and imaging fee information; confirm current approved tariff locally.)
- Liberia Ministry of Health EMR readiness notice, 17 Nov 2026 — https://moh.gov.lr/news/2026/moh-sand-technologies-assess-digital-health-progress-ahead-of-december-emr-deployment/ (Signals planned national EMR activity; confirm actual deployment status and interoperability requirements with Ministry.)
- Liberia Personal Data Protection and Privacy Act of 2024 — https://emansion.gov.lr/sites/default/files/documents/An%20Act%20For%20The%20Collection%20Processing%20Transmission%20Storage%20Protection%20%20And%20Use%20Of%20Personal%20Information%20in%20Liberia.pdf (Official statutory reference. Sections 12–13 address lawful and sensitive-information processing, including life/health and medical-treatment grounds; confirm commencement, implementing rules, and biometric-data interpretation with qualified Liberian counsel.)
- Orthanc Book: Connect your modality — https://orthanc.uclouvain.be/book/integrations/modality.html (Candidate DICOM server integration guide for modality configuration.)
- DICOM PS3.18 — https://dicom.nema.org/medical/dicom/current/output/html/part18.html (DICOMweb standard reference for web-based imaging services.)
- HL7 FHIR overview — https://fhir.hl7.org/fhir/overview.html (Standard for exchanging healthcare information electronically; select compatible versions and jurisdictional profiles during integration planning.)
- W3C WCAG 2.2 — https://www.w3.org/TR/WCAG22/ (Accessibility guidance for web content and interfaces.)
- WHO ethics and governance of AI for health — https://www.who.int/publications/i/item/9789240029200 (Guidance on ethical and accountable design, deployment, and use of AI in health.)

## 18. Approval record

*Restored from PRD v1.4.* Fill in [project-record/approvals.md](project-record/approvals.md); this table is retained for reference.

| Approver role | Name | Decision / comments | Date |
|---|---|---|---|
| Executive sponsor |  |  |  |
| Clinical lead |  |  |  |
| Radiology lead |  |  |  |
| Nursing / operations lead |  |  |  |
| Finance lead |  |  |  |
| IT / security lead |  |  |  |
| Ministry liaison (if applicable) |  |  |  |

Approval of this PRD authorizes detailed discovery and estimation. It does not by itself authorize clinical deployment, processing of real patient data, purchase of imaging equipment, or use of AI in patient care.
