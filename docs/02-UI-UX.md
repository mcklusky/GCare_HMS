# 02 — User Flows and UI/UX Specification

Status: Baseline for discovery; facility workflows require sign-off.

## Product navigation
Persistent application shell: GCare HMS + facility identity; role-scoped navigation; facility/department context; global patient search; task/alert area; account and logout. Patient-specific pages keep a clear identity banner (name, internal ID, DOB/estimated age, identity confidence/status, alerts permitted by role). Never rely on color alone.

## Screen inventory
- Sign-in, session timeout, password reset (if locally supported).
- Role dashboard and queues.
- Patient search/results, registration, duplicate review, patient chart.
- Encounter workspace: triage, history, note, diagnosis, orders/referrals, results, close/follow-up.
- Billing: charge review, invoice, payment, receipt, exemption/adjustment approval, cashier shift reconciliation.
- Radiology: order worklist, study reconciliation, report draft/sign/amend, critical-result acknowledgement, viewer launch.
- Administration: users/roles, facility/department, catalogs/tariffs, module settings, audit search.

## Interaction rules
- Every list has loading, empty, error, permission-denied and retry states; forms retain safe unsaved input where possible without browser storage of sensitive clinical content.
- Confirm patient identity before orders, report actions, and irreversible financial actions. Display patient identifier and encounter context on all clinical transactions.
- Distinguish draft, pending, completed, signed, amended, cancelled and failed statuses with text and icon.
- Use explicit confirmation plus reason for correction, merge/link, refund/reversal, exemption approval and break-glass access.
- Signed notes/reports are read-only; create an amendment with author, time and reason.
- Ambiguous patient/study matching routes to a reconciliation queue; never silently auto-link uncertain records.
- Keyboard accessible dialogs, labels, focus management, visible focus, responsive layout, readable contrast, WCAG 2.2 AA target.

## Flows and failure paths
### Register patient
Search by approved identifiers → review possible matches → either select existing patient or create record → assign stable internal ID → show confirmation. Duplicate candidates require authorized review. Corrections preserve previous values/provenance. Merge is controlled, reversible where feasible, approved, and audited.

### Outpatient encounter
Select patient → register encounter/department and eligibility → triage observations and queue priority → clinician reviews context and drafts note → add diagnosis/orders/referral → review results and critical-result acknowledgements → billing steps per local policy → sign/close and schedule follow-up. If offline, use approved downtime procedure; do not pretend unsynced records are saved.

### Radiology
Order with indication/priority → schedule/worklist where validated → identity check at modality → receive study → matched studies enter worklist; uncertain matches enter reconciliation → authorized radiologist launches diagnostic viewer → draft/sign → communicate critical result and record recipient/acknowledgement → referring clinician sees permitted result.

### Payment
Load approved charges/tariff snapshot → collect payment → server confirms transaction → issue unique receipt → reconcile shift. Duplicate submit is idempotently rejected/returned. Refund/reversal requires role, reason and approval; original remains visible.

## UI approval artifacts
Before implementation sign off: workflow swimlanes per department, field dictionary, role-to-screen/action matrix, paper/downtime forms, patient identity banner, radiology status model, invoice/receipt examples, localization conventions, and representative low-bandwidth/device review.

## Visual design (product owner, 2026-11-23)
- **Style: calm clinical.** Deep teal accent (`clinical` palette; filled buttons and the active sidebar pill use shade 7 `#256a6c`, white text 6.2:1) on a soft mint-grey page with white, well-rounded cards and quiet shadows (modern dashboard look, round 3). Tokens live in `frontend/src/theme.ts` and `frontend/src/index.css`; pages use the theme, not raw colours.
- **Light and dark.** The default follows the computer's setting, with a switch in the header. Printing (receipts, paper forms) is always black on white without the shell.
- **Shell.** The product name sits at the top of the sidebar; the header sits beside it on the page colour. The sidebar is grouped into sections (Patients & visits, Diagnostics, Billing, Operations, Administration) and shows only what the role allows. The header has a pill-shaped quick search by patient number (held in memory only, never in the URL or browser storage), the working facility, the light/dark switch and the account menu.
- **Building blocks.** `PageHeader` (title, one-line description, actions), `StatCard` (label, big number, dark icon tile, optional tinted note strip), the patient identity banner with a teal edge, and tables with quiet uppercase headers and row highlight.
- **Fonts.** No web fonts are downloaded (offline-capable, no third-party requests): Inter when installed, otherwise the system font.
- **Real data only.** Trend arrows, charts, agendas and staff schedules appear only when an API supplies the data; nothing is mocked on live screens.
- **Restyled so far:** Home (today's numbers for report users, shortcut tiles by section, side column with this month's calendar in the facility zone, working context and access), Find a patient, Queue, Visit banner and Critical results. Other screens use the new theme automatically and move to `PageHeader` as they are touched.
