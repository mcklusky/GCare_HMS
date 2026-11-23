# 25 — Imaging Orders, Worklist and Study Linking (decisions and PROVISIONAL defaults)

> **Status:** decisions by the product owner on 2026-11-23. The archive is **not selected** (OD-14, docs/24); everything here works with any DICOMweb archive and was tested against a stub and a local Orthanc with synthetic studies only. The Radiographer role is **provisional**.

Requirements: FR-010 (imaging worklist), FR-011 (DICOM receive and link, partial). Code: `backend/.../radiology/` (`ImagingService`, `ImagingStore`, `ImagingSyncJob`), migration `V17__imaging_orders_and_studies.sql`, screens `/imaging` and `/imaging/studies`.

## 1. Decisions (product owner, 2026-11-23)

| Topic | Decision |
|---|---|
| Accession number | **The HMS issues it**: `ACC` + 9 digits (12 characters, within DICOM's 16), unique per organization, never reused or changed. |
| Steps | **Ordered → patient arrived → examination done → images available** (the last set when a study links). Cancel as for other orders. Reporting is task 6.4. |
| Finding studies | **Scheduled check + on demand**: a background job asks the archive for recent studies; radiographers can press "Check archive now" on an order. |
| Matching | **Exact accession number AND patient number** link automatically; everything else waits for review. |
| Review | **Radiographer + second person**: one proposes the link with a note, a different person approves it; a proposal can be sent back; unlinking needs a reason. |
| Roles | New provisional **Radiographer** role (`IMAGING_WORKLIST`, `IMAGING_LINK`, plus patient and order read/process). |

## 2. How it works

- **Accession numbers** are assigned to every imaging order (catalog category IMAGING) that is not cancelled, the first time the worklist, an archive check or the scheduled job sees it. The worklist shows it for entry at the scanner (Modality Worklist is evaluated in 6.2).
- **Steps:** *Patient arrived* starts the order and *Examination done* completes it, through the normal order rules and history; the imaging record keeps who and when. Both ask the radiographer to check the patient's identity first.
- **Automatic link** only when the study's accession number equals the order's **and** the study's patient ID (DICOM 0010,0020) equals the order's patient number, the order is not cancelled and has no linked study yet. A link records the method (automatic or reviewed).
- **Studies to review** shows every study that did not link, with the reason (no accession number; no order with this number; order cancelled; patient ID differs; order already has a study). One person proposes a link by the order's accession number with a note; another approves (enforced by the service and a database CHECK). Unlinking returns the study to the queue with a reason. The full history is kept per study.
- **Viewer:** linked studies open in the archive's viewer by link (docs/24); images are never stored by the HMS.
- **Scheduled check:** runs only when the archive is bound to an organization: `GCARE_IMAGING_ARCHIVE_ORGANIZATION_ID`; interval `GCARE_IMAGING_SYNC_INTERVAL` (default 5 minutes — an engineering default to tune), look-back `GCARE_IMAGING_SYNC_LOOKBACK_DAYS` (default 2). Failures are logged and retried at the next run; nothing is linked on doubt.
- **Audit:** worklist and queue views (counts), steps, archive checks and sweeps (counts), proposals, approvals and unlinks (ids only). Never patient numbers or study values.

## 3. Provisional roles

| Role | Permissions |
|---|---|
| Radiographer (`demo.radiographer`, `demo.radiographer2`) | `PATIENT_READ`, `ORDER_READ`, `ORDER_PROCESS`, `IMAGING_WORKLIST`, `IMAGING_LINK` |

## Not built yet

- Modality Worklist (DICOM MWL) feed from the HMS, and MPPS (6.2, needs the real modalities).
- Radiology reports, viewer access for referring clinicians, critical results (6.4).
- Studies whose facility cannot be known (no matching order) are visible to every user with `IMAGING_LINK` in the organization.

## Open questions

1. Should the accession number carry a facility prefix when several facilities share one archive?
2. Who may approve links: a second radiographer, or only a radiologist once that role exists (6.4)?
3. What scheduled-check interval and look-back fit the pilot's volume and the archive's load?
4. Should clinicians see "images available" and the viewer link on the visit before the report (6.4)?

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Decisions recorded; accession numbers, radiographer steps, automatic exact-match linking, two-person review queue, scheduled archive check | Engineering (Claude Code), task 6.3 |
