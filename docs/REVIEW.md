# Consistency Review and Decision Register

Review date: 2026-11-23. Source: supplied PRD v1.4, embedded draft documents 03–07, and user's confirmation that pilot decisions are approved. This is an engineering consistency review, not legal, clinical, procurement, or security certification.

## Reconciliation
- Backend: Java + Spring Boot, confirmed by user. Django removed from implementation baseline.
- Frontend: React + TypeScript. Database: PostgreSQL.
- Architecture: modular monolith initially; DICOM archive/viewer external.
- Pilot scope: registration, outpatient workflow, billing, and one validated radiology pathway.

## Approval status
The user confirms that the previously listed pilot decisions and approvals are complete. Names, approval dates, signed artifacts, and detailed site evidence were not provided, so this register records the confirmation without attributing approvals to named individuals. Attach the signed workflow, security/privacy, tariff, infrastructure and modality artifacts to the project before any production launch.

## Decision register
| ID | Decision | Baseline | Status |
|---|---|---|---|
| ADR-001 | Backend stack | Java + Spring Boot | Approved by user |
| ADR-002 | Frontend | React + TypeScript | Approved baseline |
| ADR-003 | Architecture | Modular monolith initially | Approved baseline |
| ADR-004 | Imaging | External DICOM archive + viewer | Pilot decision approved; technical validation evidence belongs in deployment record |
| ADR-005 | Pilot scope | Registration, outpatient, billing, one radiology pathway | Approved by user |
| ADR-006 | Identity and duplicate policy | Stable internal ID; controlled duplicate review/linking | Pilot decisions approved; encode exact policy in acceptance tests |
| ADR-007 | Hosting/security/operations | Per approved pilot decision | Approved by user; record selected topology and RPO/RTO in deployment record |
| ADR-008 | National integration | Only approved interfaces | Approved boundary; implement only against supplied partner contract |
| ADR-009 | Scope entity naming (OD-01) | `organization` / `organization_id` | Accepted by user 2026-11-23 |
| ADR-010 | Tenant isolation (OD-02) | Shared DB, `organization_id` + PostgreSQL row-level security; one hospital per environment until hosting approved | Accepted by user 2026-11-23 |
| ADR-011 | Pinned versions (OD-04) | Java 21, Spring Boot 4.2.0, PostgreSQL 18, Node 24, React 19, Vite 8, TypeScript 7 | Accepted by user 2026-11-23. Spring Boot 4.2 and Node 24 replace the 3.x/22 originally suggested, because they were the current stable releases at setup |

## Remaining execution controls (not approval requests)
- Capture approver names, dates, versioned workflow diagrams, tariff tables, permission matrix, local privacy/hosting review, modality conformance details, network diagram, backup/restore targets and support contacts in the project record.
- Treat these as traceability and implementation inputs. Do not reopen decisions already approved unless implementation evidence reveals a material conflict or safety issue.
- Production launch still requires demonstrated acceptance gates in `01-PRD.md` and `06-PLAN.md`; approval of requirements is not proof that software passes them.

## Document restoration (2026-11-23)
The first markdown set condensed the v1.4 source heavily and dropped its requirement IDs and detail. Restored:
- `01-PRD.md` v1.5: requirement register FR-001–FR-046, role table, release plan, outcome measures, NFR targets (provisional), DICOM validation steps, full gate checklist, discovery checklist, analytics, risks, governance, onboarding/branding, sources and approval record. The v1.4 Django architecture section was intentionally not restored (superseded by ADR-001).
- `detail/03`–`detail/07`: the embedded React + Java drafts, verbatim, with the baseline docs taking precedence. Known differences are listed in [README.md](README.md#detail-reconciliation-notes).
- `16-TRACEABILITY.md`: FR → pilot scope → plan task → detailed task → test focus.
- `project-record/`: templates for the evidence listed under "Remaining execution controls", plus [open-decisions.md](project-record/open-decisions.md) for the "must decide before schema freeze" and "decisions still gated" items. OD-01 to OD-04 must be settled before the first migration and auth task.

`GCare_HMS_PRD_v1_4.docx` is superseded and kept as historical source only.

## Next action
Settle OD-01 to OD-04 in `project-record/open-decisions.md` and fill `project-record/deployment-record.md`, then begin Phase 1 using the first-sprint checklist in `08-FIRST-SPRINT.md`. Keep development and demo data synthetic. Record actual test results and unresolved implementation details in the relevant issue/PR.

## Limitations
External references in the source were not independently verified. This review does not certify regulatory compliance, clinical safety, cybersecurity, equipment suitability, or production readiness.
