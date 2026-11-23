# 26 — Radiology Reports, Viewer Access and Critical Results (decisions and PROVISIONAL defaults)

> **Status:** decisions by the product owner on 2026-11-23. The Radiologist role and the report layout are **provisional**. **No time limits or escalation rules for critical results are defined**: clinical governance sets them. The archive/viewer is still not selected (docs/24).

Requirements: FR-012 (image viewing), FR-013 (radiology reporting). Code: `backend/.../radiology/` (`RadiologyReportService`, `RadiologyReportStore`), migration `V18__radiology_reports_and_critical_results.sql`, screens `/radiology/reporting`, `/radiology/orders/{id}`, `/critical-results`, and the "Imaging results" section on the visit and in the patient chart.

## 1. Decisions (product owner, 2026-11-23)

| Topic | Decision |
|---|---|
| Signing | **The radiologist signs; drafts are private**: drafts are visible to radiologists only; clinicians see signed reports. Signed reports never change; corrections are amendments with a reason (as for clinical notes). |
| Format | **Fixed sections, free text**: Technique, Comparison (optional), Findings and Impression (both required to sign). No coded terms or modality templates yet. |
| Critical results | **Flag + documented contact + acknowledgement**: the radiologist marks the report critical and records whom they told, how and when; a clinician acknowledges it in the HMS; unacknowledged ones are listed for oversight. No automatic time limits. |
| Access | **Clinical roles in scope**: users with report read at the facility (clinicians, nurses) see signed reports and viewer links on the visit and in the chart; images without a report are marked "Not yet reported" (docs/25 default 4). |

## 2. How it works

- **Reporting list** (radiologists): examinations recorded as done, without a signed report or with a draft open; urgent first. A report can only be started once the examination is recorded as done.
- **Drafts:** one open draft per order; only its author edits, signs or discards it. Other radiologists can read it.
- **Signing:** needs findings and impression. A report marked critical can only be signed with the documented contact (who, how, when ≤ now); a report not marked critical cannot carry one.
- **Amendments:** start from the latest signed report with a reason; the text starts as a copy; signing it makes it the current report. The visit shows "Reported (amended)" and keeps earlier versions. Each signed report can be amended once (amend the latest).
- **Critical results:** a red banner on the visit and in the chart until acknowledged; "Critical results" lists open ones (clinicians with acknowledge, radiologists with oversight). Acknowledging records who, when and what was done; it cannot be changed (database trigger).
- **Viewer:** links to the archive's viewer per linked study, with "not yet reported" when there is no signed report.
- **Audit:** list views (counts), report views (counts), starts, saves (field names), signing, amendments, critical flag (method only) and acknowledgement. Never report text or patient data.

## 3. Provisional roles

| Role | Permissions |
|---|---|
| Radiologist (`demo.radiologist`) | `RADIOLOGY_REPORT_WRITE`, `RADIOLOGY_REPORT_READ`, `CRITICAL_RESULT_OVERSIGHT`, `IMAGING_WORKLIST`, `IMAGING_LINK` (may approve study links, docs/25), `PATIENT_READ`, `ORDER_READ` |
| Clinician | adds `RADIOLOGY_REPORT_READ`, `CRITICAL_RESULT_ACKNOWLEDGE` |
| Triage nurse | adds `RADIOLOGY_REPORT_READ` |

## Not built yet

- Time limits, escalation and paging for unacknowledged critical results (clinical governance first).
- Preliminary reports, trainee reporting, coded findings, modality templates, report printing/export.
- Viewer access for clinicians outside the facility; access expiry depends on the selected viewer (docs/24 criterion 6).

## Open questions

1. Who is the radiology lead for oversight, and which time limits and escalation path apply to unacknowledged critical results?
2. Which findings count as critical (a facility list), and must the receiving clinician read back?
3. Is a printable/signed PDF report needed for patients or referrers?
4. Should a second radiologist review (double-read) any modalities?

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Decisions recorded; drafts, signing, amendments, critical-result contact and acknowledgement, imaging results on visit and chart | Engineering (Claude Code), task 6.4 |
