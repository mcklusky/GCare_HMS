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

## Visual design (product owner, 2026-11-23; "navy console", chosen from a reference layout)
- **Style: navy console, high density.** Dark navy sidebar, bright blue accent (`clinical` palette, now a blue scale; filled buttons shade 6 `#2557e6`, white text 5.6:1), light grey page with white cards, hairline borders, colour-coded KPI icons and pills. Tokens live in `frontend/src/theme.ts` and `frontend/src/index.css`; pages use the theme, not raw colours.
- **Density.** 14 px body, 13 px secondary text, tight spacing, 32 px navigation rows, dense tables (uppercase 11 px headers, framed white panels). Secondary text is `#566365` (6.1:1 on white), darker than Mantine's default grey, so small text stays AA.
- **Light and dark.** The default follows the computer's setting, with a switch in the header. Printing (receipts, paper forms) is always black on white without the shell.
- **Shell.** Navy sidebar: product mark, sections with small uppercase labels, the current page highlighted with a blue edge, quick actions the role allows, and the working-facility card. Light top bar: wide search by patient number (held in memory only, never in the URL or browser storage) with Ctrl/Cmd+K for the jump-to palette, a small environment pill, the facility picker, the light/dark switch and the user chip (account menu, sign-out). Pages show the date and time in the facility zone on the right of the title.
- **List pages (Invoices first).** KPI cards, a work-queue card with a filter, tabs over a filter bar, a dense table (date over time, monospace references, coloured pills with status dots, right-aligned amounts, an eye action) with "Showing a–b of n" pagination, and a green info banner restating the rule that matters.
- **Building blocks.** `PageHeader` (title, one-line description, actions; with `icon` it becomes a hero band with a soft teal glow and summary chips), settings-style form sections (title and help left, fields right; stacks when narrow), selectable cards, live previews, KPI strip (one card split by hairlines), list cards with a header row, description lists (`gcare-dl`), `StatCard`, the patient identity banner, `ConfirmDialog` and `StatusBadge`.
- **Fonts.** No web fonts are downloaded (offline-capable, no third-party requests): Inter when installed, otherwise the system font (Segoe UI Variable on Windows).
- **Real data only.** Trend arrows, charts, agendas and staff schedules appear only when an API supplies the data; nothing is mocked on live screens.
- **Next.** Lighter row actions in long tables, monospace patient numbers, and horizontal scrolling for wide tables on phones, done screen by screen.
