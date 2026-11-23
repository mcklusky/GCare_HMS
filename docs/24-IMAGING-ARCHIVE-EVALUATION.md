# 24 — Imaging Archive and Viewer Evaluation Kit (task 6.1; decisions and PROVISIONAL defaults)

> **Status:** the archive and viewer are **not selected** (OD-14). This kit lets radiology, IT and procurement evaluate candidates against the HMS. Orthanc is included as a **candidate for evaluation only**. The checklist in §4 is a plan: its result columns stay empty until each candidate is evaluated on site.

Requirements and decisions: ADR-004 (external DICOM archive + viewer), docs/11 (DICOM workflow), OD-14. Code: `backend/.../radiology/` (`ImagingArchive`, `DicomWebArchive`), screen `/admin/imaging-archive`, local harness `docker compose --profile imaging`, `infra/imaging/`.

## 1. Decisions (product owner, 2026-11-23)

| Topic | Decision |
|---|---|
| Phase 6 approach | Build an evaluation kit for 6.1, then 6.3 (imaging orders, worklist/status, study linking, mismatch queue) against a vendor-neutral archive interface. 6.2 (real modalities) needs the site. |
| Matching (6.3) | **Accession number, exact**: a study links automatically only when accession number **and** patient number both match; anything else goes to a mismatch queue. |
| Mismatches (6.3) | **Radiographer + second person**: one proposes the link, another (radiographer or radiologist) approves; unlinking needs a reason. |
| Viewer | **Launch the archive's viewer by link**; images never pass through or are stored by the HMS. |

## 2. How the HMS talks to the archive

- **Standard DICOMweb QIDO-RS** (PS3.18), read-only: studies by `AccessionNumber` and by `StudyDate` range. The HMS reads study UID, accession number, patient ID (0010,0020), modalities, study date, description and series/image counts; never names or other demographics, never pixel data.
- **Viewer:** a URL template with `{StudyInstanceUID}`; UIDs are validated (digits and dots, ≤ 64) before use.
- **Safety:** no redirects followed, 5 MiB answer cap, timeouts, unusable UIDs skipped; an unreachable or refusing archive is reported (503 `ARCHIVE_UNAVAILABLE`), never treated as "no studies".
- **Configuration** (environment; no defaults, so the archive shows *Not configured* until set): `GCARE_IMAGING_ARCHIVE_BASE_URL` (DICOMweb root), `..._USERNAME` + `..._PASSWORD` or `..._BEARER_TOKEN`, `..._VIEWER_URL_TEMPLATE`, `..._TIMEOUT` (default 10 s), `..._MAX_RESULTS` (default 200). Credentials come from the secret store, never the repository.
- **Screen** `/admin/imaging-archive` (permission `IMAGING_ARCHIVE_ADMIN`, provisional *Imaging administrator* role, demo user `demo.pacsadmin`): connection status and latency; study look-up by accession number with "Open in viewer". Every look-up is audited (criteria type and count, never values).

## 3. Local evaluation harness (synthetic only)

1. `docker compose --profile imaging up -d orthanc` — Orthanc 1.12.10 with DICOMweb, Stone Web Viewer and the worklist plugin, bound to `127.0.0.1:8142` (HTTP) and `127.0.0.1:4342` (DICOM), basic auth `gcare-local` / `local-dev-only-not-a-secret` (local only; `infra/imaging/orthanc.local.json`).
2. `bash infra/imaging/load-synthetic-studies.sh P-000001 ACC-000001 CR "Synthetic chest"` — creates a blank synthetic study. Never load real images.
3. Start the backend with `GCARE_IMAGING_ARCHIVE_BASE_URL=http://127.0.0.1:8142/dicom-web`, the user/password above and `GCARE_IMAGING_ARCHIVE_VIEWER_URL_TEMPLATE=http://127.0.0.1:8142/stone-webviewer/index.html?study={StudyInstanceUID}`.
4. Automated check against the running candidate: `GCARE_ORTHANC_URL=http://127.0.0.1:8142 ./mvnw verify -Dtest=OrthancCompatibilityTests` (skipped in CI). Passed locally on 2026-11-23.

Ports 8042/4242 are avoided on purpose: other local imaging software may use them.

## 4. Evaluation checklist (per candidate; results left empty)

Evaluate each candidate on the pilot network with representative **test** studies from the real modalities (6.2). Attach evidence (screenshots, logs, conformance statements) to the deployment record.

| # | Criterion | Evidence needed | Candidate | Result | Notes |
|---|---|---|---|---|---|
| 1 | DICOMweb QIDO-RS answers HMS queries (accession, date range) | `OrthancCompatibilityTests`-style run against the candidate | | | |
| 2 | Conformance with each modality: C-STORE, transfer syntaxes, C-ECHO | Conformance statements + test sends | | | |
| 3 | Modality Worklist (and MPPS if used) for accession numbers | Worklist query from each modality | | | |
| 4 | Storage commitment / resend and retry behaviour | Interrupted transfer test | | | |
| 5 | Images, series counts, orientation, timestamps, compression intact | Radiologist review of test studies | | | |
| 6 | Viewer: launch by study UID, diagnostic display suitability, access control and expiry | Radiology sign-off | | | |
| 7 | Security: TLS, authentication for the HMS account, audit of access, network allowlists | IT security review | | | |
| 8 | Capacity and storage growth for expected volume; retention | Volume estimate × study size | | | |
| 9 | Backup, restore and disaster recovery of images and index | Restore drill | | | |
| 10 | Monitoring: availability, failed/stalled transfers, low storage | Alert test | | | |
| 11 | Downtime fallback and recovery reconciliation | Drill with docs/22 | | | |
| 12 | Licensing, support, vendor escalation, total cost | Procurement | | | |

## Open questions

1. Which candidates enter the evaluation, and who signs off each criterion (radiology lead, IT security, procurement)?
2. Which modalities (make, model, software) take part in 6.2, and do they support Modality Worklist?
3. Accession number format and who issues it (HMS or the archive/RIS) — needed before 6.3.
4. Viewer access for referring clinicians: same viewer, or a separate limited one?

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Decisions recorded; DICOMweb archive interface, status/look-up screen, local Orthanc harness, evaluation checklist | Engineering (Claude Code), task 6.1 |
