# 27 — Gate Readiness Pack (Phases 1–6)

> **Status:** prepared by engineering on 2026-11-23 for the gate approvers. **No gate is signed off.** This pack says what each gate asks for, what evidence exists, what is still to be done and which provisional decisions the approver must confirm or change. Signatures go in [project-record/approvals.md](project-record/approvals.md) ("Gate sign-offs"), not here. Nothing here authorizes real patient data; that needs the separate launch authorization (01-PRD §10).

**How to read the evidence.** "Automated" means a test that ran on 2026-11-23 against synthetic data: backend `mvnw verify` passed (200 tests, 1 skipped: the opt-in Orthanc check, run separately and passed) and frontend `vitest run` passed (100 tests). The test names per requirement are in [16-TRACEABILITY.md](16-TRACEABILITY.md). Automated tests show that the software follows the rules as written. They do not show that the rules are right for the hospital: that is what the approver decides.

## Summary

| Gate (06-PLAN exit) | Approver(s) | Code | Still to do before signing |
|---|---|---|---|
| Phase 1: security-reviewed foundation, tenant/facility boundaries proven | IT / security lead | Done | Independent security review; session timeouts (docs/23 Q4) |
| Phase 2: duplicate scenarios and usability signed off | Registration leadership | Done | Confirm docs/17 policy (provisional); UAT row 1 |
| Phase 3: end-to-end UAT with role checks; signed content immutable | Clinical lead, nursing / operations lead | Done | Answer docs/18 questions; UAT rows 2–4 and 12 |
| Phase 4: verified scenarios balance; finance approves permissions and receipt | Finance lead | Done | Enter tariff; approve receipt; docs/19 questions; UAT rows 5–7 |
| Phase 5: operational readiness; restore and downtime drills; training/support live | Operations owner, IT lead, clinical safety owner | Done | Drills on pilot server; RPO/RTO; monitoring owners; training; UAT rows 8–13 |
| Phase 6: radiology accepts integrity, matching, image quality, access, restore, downtime | Radiology lead, IT / security lead | 6.1 kit, 6.3, 6.4 done; **6.2 not started** | Select archive (OD-14); 6.2 on site; radiology scenarios R1–R8 below |

## Phase 1 — Foundation

**Gate:** "Security-reviewed foundation; tests prove tenant/facility boundaries."

Evidence (automated):
- **Tenant and facility boundaries:** every tenant table has row-level security, and each module has an isolation test (`OrganizationIsolationTests`, plus one per module, e.g. `ReportTests`, `DowntimeTests`, `RadiologyReportTests`).
- **Deny by default:** `EndpointSecuritySweepTests` calls every API endpoint without a session and with a user without permissions. Only `GET /api/v1/me` answers without permission.
- **Sign-in:** `KeycloakSignInTests` and `SessionLifetimeTests`.
- **Audit:** `AuditTrailTests` and `MetadataRedactorTests` (no values or clinical text in audit metadata).
- **Internal review:** the engineering security review in task 5.4 (docs/23 §3) fixed five missing-parameter errors and added security headers.

Still to do:
1. **Independent security review** (penetration test or review by the IT / security lead). The internal review does not replace it.
2. Decide session idle and absolute timeouts (placeholders today) and proxy rate limiting (docs/23 Q4).
3. Confirm hosting, data residency and secrets handling for the pilot server (Phase 0.4; [project-record/deployment-record.md](project-record/deployment-record.md) is still empty).

## Phase 2 — Patient registration

**Gate:** "Registration leadership signs off tested duplicate scenarios and usability."

Evidence (automated): all ten acceptance scenarios in [docs/17 §4](17-PATIENT-IDENTITY-POLICY.md) pass. Scenarios 1–7 are in `DuplicateScoringScenarioTests`, and 8–10 in `PatientApiTests`. Other tests cover corrections with reason and history (`PatientCorrectionAndReviewTests`) and link-not-merge with a second person (`PatientLinkTests`).

Decisions to confirm: the whole identity policy is **provisional**. Engineering wrote it at the product owner's request.
- Searchable details (OD-05).
- Duplicate scores and thresholds (OD-06).
- Link instead of merge (OD-07).
- Patient number format.

Still to do:
1. Registration leadership reviews docs/17, then confirms or changes it.
2. UAT row 1 (docs/23 §6) with real clerks on synthetic patients, including usability.

## Phase 3 — Outpatient clinical core

**Gate:** "End-to-end UAT with role checks; signed content immutable."

Evidence (automated):
- **Visit lifecycle and triage:** `EncounterTests`, `ConfiguredTriageTests`.
- **Notes:** `ClinicalNoteTests`. Signed notes are refused by the database, and amendments need a reason.
- **Orders:** `OrderTests`.
- **Chart:** `ChartTests`.
- **Role checks:** the module tests check that roles without the permission are refused (403) and that other facilities are invisible (404).

Decisions to confirm:
- The encounter workflow (docs/18) is **provisional**.
- The triage scale and alarm limits are **empty** until clinical leads approve them (docs/21 Q1). No triage colour or alarm appears until then.
- The order catalog is empty by design. The facility enters it.

Still to do:
1. Answer docs/18 questions 1–5 (visit types, who closes or reopens, whether triage is mandatory, transfers).
2. Clinical leads enter and approve the triage scale (two-person setting, docs/21).
3. UAT rows 2, 3, 4 and 12.

## Phase 4 — Billing

**Gate:** "Verified scenarios balance; finance owner approves permissions and receipt format."

Evidence (automated):
- `TariffTests`: two-person price approval; prices can't change after approval.
- `InvoiceTests`: decimal arithmetic, per-line half-up tax, one currency per invoice, void and reissue.
- `PaymentTests`: part payments up to the balance, idempotent retries, shift count with second-person sign-off.
- `AdjustmentTests`: two-person refunds and exemptions.
- `ReportTests`: the finance totals.

Decisions to confirm (docs/19):
- Permissions per finance role.
- Invoice number format `INV-000001`.
- Receipt layout (**provisional**).
- No price or tax value is built in. Finance enters them.

Still to do:
1. Answer docs/19 questions 1–3 and 5–11, starting with the pilot tariff and tax.
2. Finance enters the tariff and approves it in the HMS.
3. Approve the receipt layout together with branding and legal wording (docs/21 Q2).
4. UAT rows 5, 6 and 7, including a shift that balances and one that does not.

## Phase 5 — Reporting, configuration, downtime and operations

**Gate:** "Operational readiness review; restore and downtime drills complete; training/support live."

Evidence:
- **Automated:**
  - `ReportTests`: aggregates only, facility-local days, audited CSV.
  - `ConfigurationTests`: versioned settings, second approver, branding shown only once approved.
  - `DowntimeTests`: forms become records only through reconciliation, second person for high-risk forms.
  - `BackupRestoreDrillTests`: encrypted backup and verified restore.
  - `ManagementPortTests`: health and metrics only on the management port.
- **Local drill:** the restore drill on a developer machine with synthetic data restored 46 tables at schema version 15, with counts verified (docs/23 §2).

Still to do:
1. **Restore drill on the pilot server**, with the duration recorded against the RTO (UAT row 11). The RPO and RTO must be set first (docs/23 Q1).
2. **Downtime drill** with paper forms on the ward (UAT row 10). The facility must first approve the paper form layout and the procedure (docs/22 Q1, docs/12).
3. Name who holds the backup key and its second copy (docs/23 Q2), and who receives which alert and when (docs/23 Q3). The alert rules in `infra/monitoring/` are provisional.
4. **Training and support are live:** user training per role (docs/14), support contacts ([project-record/support-contacts.md](project-record/support-contacts.md) is empty) and the incident process.
5. Answer docs/20 (reports), docs/21 Q2–Q3 and docs/22 Q2–Q4.
6. UAT rows 8, 9, 12 and 13. Row 13, the keyboard and screen-reader pass, is still needed: the automated accessibility checks are not a manual review.

## Phase 6 — Radiology pilot

**Gate:** "Radiology leadership accepts integrity, matching, image quality, access, restore and downtime scenarios."

Status:
- **6.1, archive and viewer:** **not selected** (OD-14). The evaluation kit and criteria are in docs/24. The HMS speaks standard DICOMweb and was tested against a local Orthanc with synthetic studies only.
- **6.2, real modalities:** **not started**. It needs the scanners, the isolated network and site access. [project-record/modality-inventory.md](project-record/modality-inventory.md) is empty.
- **6.3, worklist and linking:** done (`ImagingWorklistTests`). A study links automatically only on an exact accession number and patient number. Anything else goes to a two-person review queue.
- **6.4, reports and critical results:** done (`RadiologyReportTests`). Drafts are private, signed reports never change, and amendments keep earlier versions. A critical result needs a documented contact and a clinician's acknowledgement.

Decisions to confirm:
- The four provisional defaults in docs/25 (accession prefix, who approves links, check interval, "Not yet reported" images).
- The Radiographer and Radiologist roles (docs/25, docs/26).
- The report layout (docs/26).

Still to do:
1. Select the archive and viewer (docs/24 Q1, Q4), then run 6.2 on site (docs/24 Q2).
2. Set critical-result time limits, escalation and the critical findings list (docs/26 Q1–Q2). **Until then the HMS has no escalation.** It lists unacknowledged results, and someone must watch that list.
3. Answer docs/26 Q3–Q4 (printable report, double-reading).
4. Run the radiology scenarios below on the selected archive, with synthetic studies first.

### Radiology acceptance scenarios (outcomes left empty)

| # | Scenario | Covers | Roles | Outcome | Date |
|---|---|---|---|---|---|
| R1 | Order an X-ray; the patient arrives; the radiographer marks arrived and examined; the study from the real modality links automatically | Integrity, matching | Clinician, radiographer | | |
| R2 | Study with a wrong or missing accession number: it stays unlinked; one radiographer proposes the link and a second person approves it | Matching | Radiographers | | |
| R3 | Study whose patient number does not match the order: never linked automatically; sent back | Matching | Radiographer | | |
| R4 | Open the images from the visit through the viewer link: before the report ("Not yet reported") and after it | Access, image quality | Clinician, nurse, radiologist | | |
| R5 | Image quality on the selected viewer meets the radiology lead's standard for each pilot modality | Image quality | Radiology lead | | |
| R6 | Draft, sign and amend a report; a clinician cannot see the draft; the visit shows "Reported (amended)" | Integrity | Radiologist, clinician | | |
| R7 | Critical result: phone the clinician, record the contact, sign; the clinician acknowledges in the HMS | Critical results | Radiologist, clinician | | |
| R8 | A clerk or cashier tries to open imaging results or the worklist and is refused | Access | Clerk, cashier | | |
| R9 | Restore the HMS and the archive from backup; links and reports still match | Restore | Operations owner, radiology lead | | |
| R10 | Archive unavailable: the worklist still works, studies link once it is back; scanner downtime follows docs/22 | Downtime | Radiographer, coordinator | | |

## Cross-cutting before real patient data

These belong to the launch authorization, not to a single phase:
- Privacy and legal review, the retention schedule and data residency (docs/15; [project-record/retention-schedule.md](project-record/retention-schedule.md) is empty).
- Clinical safety hazard log ([project-record/hazard-log.md](project-record/hazard-log.md) is empty).
- Named approvers ([project-record/approvals.md](project-record/approvals.md)). The pilot decisions were confirmed on 2026-11-23 without names.
- The pull requests (stacked 1.2 → … → 6.4) are reviewed and merged.

## Review of Phases 5–6 (2026-11-23)

Four independent reviewers read the code for tasks 5.3 to 6.4: downtime, the imaging archive and worklist, radiology reports, and operations plus the screens. Each finding was checked against the code before it was fixed. Fixes are on branch `review/phase5-6-fixes` (migration V19).

| Area | Fixed |
|---|---|
| Patient safety (screens) | The confirm dialog no longer keeps a reason typed for the previous record, which could have recorded a false critical-result acknowledgement. The "propose link" form resets for each study, so it cannot keep the previous study's accession number. Downtime approvers see the proposed patient, visit and invoice by name. Errors (e.g. "someone else acted first") show inside the dialog, and the list reloads. If the paper-record notice on a visit cannot load, a warning shows instead of nothing. |
| Lists cut short | The imaging worklist, reporting list, critical-results list and downtime episode list now filter by facility (and the worklist also hides finished orders) before their row limit. Before, old or other-facility rows could push current work, including critical results, out of view. |
| Imaging linking | Unlinked pairings are never re-linked automatically. Archive-side changes to accession number or patient ID send a study back to review. Two concurrent checks cannot double-link. The scheduled check pages through results. No lock is held while the archive answers. The size cap applies while reading. Archive look-ups are bound to one organization. Send back and history respect facility scope. Orders started from the orders worklist still move on. Viewer links must be http(s), checked both in configuration and on screen. |
| Access | Keyed downtime contents only for roles that may see that kind of record. Approving a registration form needs registration rights, and the visit must still be open. Choosing a paper payment's invoice needs invoice access. Voiding a triage form needs observation rights. |
| Concurrency (downtime) | Locks prevent overlapping episodes, forms keyed into an episode being closed, and paper times after the recorded end. |
| Errors and audit | A miswritten paper patient number no longer blocks matching. Implausible contact years give 400, not 500. A changed invoice at approval gives 422 ("send it back"). Imaging results reads are audited against the visit or patient. |
| Operations | A failed backup deletes its partial files. Restore grants the application role to `GCARE_APP_LOGIN`. Isolation tests now cover all five downtime tables. |

**Decided afterwards (2026-11-23, following established practice; branch `review/decisions-followup`, V20):**
- **Time zones:** stored in UTC, shown and entered in the facility's zone (docs/23).
- **Amending a critical report:** the change is communicated again and acknowledged; the earlier open result is replaced (docs/26, provisional until radiology leadership confirms).
- **Archive binding:** the HMS refuses to start with an archive URL but no `organization-id` (docs/25).

**Still open (minor):**
- Minor items: an unknown visit or patient id returns an empty list rather than 404. The foreign key of `downtime_entry_events` is not organization-qualified (RLS still isolates it).

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Phase 5–6 review: findings fixed or recorded above (V19) | Engineering (Claude Code) |
| 2026-11-23 | Pack prepared for Phases 1–6; radiology scenarios R1–R10 added; traceability rows FR-014, FR-035, FR-040, FR-043 filled from tests that ran | Engineering (Claude Code) |
