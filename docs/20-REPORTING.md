# 20 — Operational Reports and Dashboard (decisions and PROVISIONAL defaults)

> **Status:** the decisions below were made by the product owner on 2026-11-23. The measures are computed from data the system already records; **no statutory or national report is defined here** (those need their official definitions). Items marked *provisional* need confirmation by the operations and finance owners.

Requirements: FR-024 (operational dashboards), FR-025 (reports and export, partial). Code: `backend/.../reporting/`, migration `V13__report_permissions.sql` (permissions only), screen `/reports`.

## 1. Decisions (product owner, 2026-11-23)

| Topic | Decision |
|---|---|
| First reports | Visits and waiting, orders and turnaround, registrations, revenue and receipts (plus payments by method and cashier shifts). |
| Export | **Aggregates only**, as CSV, with a separate permission `REPORT_EXPORT`. No patient names or numbers. Every export is audited with its filters. Patient-level exports wait for de-identification rules. |
| Access | New provisional **Department manager** role for visit, order and registration reports; revenue reports need `REPORT_REVENUE_READ` (finance manager). Administrators get no reports by default. |
| Dates | Grouped by **calendar day in the facility's time zone** (stored per facility; Africa/Monrovia = UTC for the pilot). Period up to 366 days. |

## 2. Reports

All reports are for **one facility in the caller's scope** and a period of facility-local days. Each shows the facility, time zone, period, generation time and requesting user (also as `#` lines at the top of the CSV).

| Report | Rows | Measures |
|---|---|---|
| Visits and waiting | day × department | arrivals, called in, closed, cancelled, still open, average wait (arrival → call-in, minutes) |
| Orders and turnaround | requested day × category × item | requested, completed, cancelled, still open, average turnaround (request → completion, minutes) |
| Registrations | day × source | new records, details incomplete, later linked as duplicate |
| Revenue and receipts | day × currency | invoices issued, invoiced (not void now), invoices voided, collected, refunded, net collected, exempted |
| Payments by method | day × method × currency | payments and amount, refunds paid out and amount |
| Cashier shifts | day closed × cashier × currency | status, expected cash, counted, difference |

- Counts reflect each record's **status now**, grouped by the day of the event named in the row (arrival, request, registration, issue, payment, pay-out, approval, close).
- Money stays **per currency**; nothing is converted or added across currencies.
- **Dashboard** (top of `/reports`): arrivals today, waiting now, in consultation now, open orders, new records today; with `REPORT_REVENUE_READ`, net collected today per currency. Refreshes every minute.
- Every report view, dashboard view and export is audited (report, period, row count); data values are never written to the audit trail.
- CSV text that a spreadsheet could run as a formula is prefixed with `'`.

## 3. Provisional roles

| Role | Permissions |
|---|---|
| Department manager (`demo.manager`) | `REPORT_OPERATIONS_READ`, `REPORT_EXPORT` |
| Finance manager | adds `REPORT_REVENUE_READ`, `REPORT_EXPORT` |

## Not built yet (FR-024/FR-025)

- Scheduled reports, statutory/national reports, de-identified patient-level exports for analytics.
- Measures for appointments, unreported studies, bed occupancy and stock (no such data yet).

## Open questions for the operations and finance owners

1. Are these the measures you need first? What is missing for daily and monthly review?
2. Should a department manager see only their own departments rather than the whole facility?
3. Which statutory or ministry reports are required, and where are their official definitions?
4. Who may export, and is a CSV of aggregates acceptable for your data-protection rules?

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Decisions recorded; six aggregate reports, dashboard, audited CSV export | Engineering (Claude Code), task 5.1 |
