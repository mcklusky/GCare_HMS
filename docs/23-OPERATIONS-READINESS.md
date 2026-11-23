# 23 — Operations Readiness: Backup, Security, Accessibility, Monitoring (decisions and PROVISIONAL defaults)

> **Status:** engineering work for task 5.4, decisions by the product owner on 2026-11-23. Recovery targets (RPO/RTO), the deployment topology, the monitoring server and the operations owner are **not approved yet** (docs/08); nothing here sets them. The UAT checklist in §6 is a plan: its outcome columns stay empty until UAT is run with the pilot users.

Requirements: FR-036 (monitoring and alerting), FR-037 (accessibility), FR-027 (restore drill), FR-002/FR-032 (access and isolation). Code: `infra/backup/`, `infra/monitoring/alert-rules.yml`, `shared/security/SecurityConfig` + `ManagementPort`, tests in `backend/.../operations/`.

## 1. Decisions (product owner, 2026-11-23)

| Topic | Decision |
|---|---|
| Scope now | Backup + restore drill, security review + fixes, accessibility sweep, health + alerting. UAT remediation waits for real UAT findings; a checklist is prepared (§6). |
| RPO / RTO | **Left open, configurable**: backup frequency and retention have no built-in values; the drill records how long a restore actually takes so leadership can set targets. |
| Encryption | **Yes, key held outside**: backups are encrypted to the operator's public key; the private key needed to restore is kept off the server. |
| Health and metrics | **Local network only, no login**: a status-only check on the application port; health details and metrics on a separate management port bound to localhost / the internal network, never through the public proxy. |

## 2. Backup and restore (`infra/backup/`)

- `backup.sh` streams `pg_dump` (custom format) straight into `gpg` public-key encryption, so no plain dump touches the disk. A manifest beside it records the migration version, the row count of every table (taken in the **same snapshot** as the dump) and the SHA-256 of the encrypted file. Old backups are deleted only after a successful one.
- `restore.sh` refuses a damaged file (checksum), refuses any **non-empty** target database, decrypts with the private key into `pg_restore`, then compares the migration version and every table's row count with the manifest and fails on any difference. It prints the elapsed time.
- `drill.sh` backs up, restores into a new scratch database on the same server, verifies and drops it, using a throwaway key pair made for the drill. `drill-local.sh` runs it against the Compose database. `BackupRestoreDrillTests` runs the same scripts in CI against the test database, including the refusals (non-empty target, damaged file, missing key, missing retention setting).
- **Required settings, no defaults:** `BACKUP_DIR`, `BACKUP_GPG_RECIPIENT` (operator public-key fingerprint), `BACKUP_RETENTION_DAYS`; plus `PGHOST`/`PGPORT`/`PGDATABASE`/`PGUSER`. Schedule it with cron or a systemd timer at the frequency the approved RPO needs. Optional `BACKUP_METRICS_FILE` (node_exporter textfile) and `BACKUP_MAX_AGE_SECONDS` (approved RPO) feed the backup alerts.
- **Backup database user:** must read every table with row security off (a dedicated role with `BYPASSRLS` and read access, or a superuser); otherwise PostgreSQL refuses instead of producing a partial backup. The application role `gcare_app` is never used for backups.
- **Keys:** generate the key pair on an operator workstation; install only the public key on the server; keep the private key (and a second copy) offline under the operations owner's custody. A backup cannot be restored without it — losing it loses the backups.
- **First local drill (2026-11-23, synthetic data):** 46 tables, migration version 15, backup and restore each about 1 s, verification passed. Real timings must come from a drill on the pilot server with production-like volume.

**Review fixes (2026-11-23, docs/27):** a failed backup now deletes its partial files (a truncated encrypted file could look like a backup), and a failed manifest query stops the run. `restore.sh` grants `gcare_app` to `GCARE_APP_LOGIN` (the application's login role); without it, an application on a new server could not run a single query after restore. Set it for every real restore.

**Time zones (decided 2026-11-23, following common EHR practice):** times are stored in UTC and every screen shows and enters them in the time zone of the facility the user works in (the facility's configured zone), not the workstation's. Date-time inputs say "Facility time (<zone>)". A user working at facilities in different zones sees each in the zone of the facility selected in the shell.

## 3. Security review

- **Endpoint sweep** (`EndpointSecuritySweepTests`): every `/api/v1` operation is discovered from the running application and checked: without sign-in → 401; state changes without the CSRF token → 403; a signed-in user with no permissions → 4xx (only `GET /api/v1/me` is open to any signed-in user). New endpoints are covered automatically.
- **Fixed:** five endpoints answered **500** when a required query parameter was missing (`/reports/dashboard`, `/encounter-departments`, `/downtime/forms`, `/downtime/form-options`, `/billing/effective-price`); they now answer 400 with the missing field.
- **Added headers** on every response: `Referrer-Policy: no-referrer`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `Permissions-Policy` (camera, microphone, geolocation, payment off); existing `nosniff`, `X-Frame-Options: DENY` and `Cache-Control: no-store` are checked.
- **Dependencies:** Dependabot (already configured) plus a `dependency-review` CI job that blocks pull requests adding dependencies with known high-severity vulnerabilities; `npm audit` stays in CI.
- **Not covered by automation:** penetration test, TLS/proxy configuration, Keycloak production hardening, rate limiting, and the session timeouts (still placeholders, OD-03 follow-up). These belong to the security owner's pre-pilot review.

## 4. Accessibility

- Every screen now has an automated axe check (added: facility detail, shift sign-off, refund receipt, downtime queue with a form open, printable forms); the keyboard test checks the skip link is the first tab stop, main content is a landmark and each page has one level-1 heading.
- jsdom cannot judge colour contrast, so that axe rule is off in tests. **A manual review with real devices and assistive technology is still required** (FR-037: "automated + human review").

## 5. Health, metrics and alerting

- Application port: `/actuator/health`, `/livez`, `/readyz` answer status only (no components). Everything else under `/actuator` is refused there.
- Management port (`MANAGEMENT_PORT`, default 9091; `MANAGEMENT_ADDRESS`, default 127.0.0.1): health details (including the database), `info` and Prometheus metrics at `/actuator/prometheus`; nothing else (`ManagementPortTests`). Do not publish this port through the proxy.
- `infra/monitoring/alert-rules.yml` (checked with `promtool`): backend down, database unreachable, server errors above 5%, slow responses, connection pool saturated, heap high, backup older than the approved maximum, backup metrics missing. Thresholds are **provisional engineering starting points**; the operations owner tunes them and decides who is paged.

## 6. UAT checklist (to run with pilot users; outcomes left empty)

Run with synthetic data, one session per role, on the pilot environment. Record each finding with role, step, expected, actual, severity and correlation id (never patient data).

| # | Scenario | Roles | Outcome | Findings |
|---|---|---|---|---|
| 1 | Register a new patient; duplicate warning; correct a field with a reason | Clerk, supervisor | | |
| 2 | Open a visit, triage, queue order, call in, transfer, close | Clerk, nurse, clinician | | |
| 3 | Write and sign a note, add a diagnosis, amend after close | Clinician | | |
| 4 | Order a test, process it on the worklist | Clinician, service staff | | |
| 5 | Invoice, part payment, receipt, shift close and sign-off | Cashier, finance manager | | |
| 6 | Refund and exemption with second-person approval | Cashier, finance manager | | |
| 7 | Prices and tax approval | Finance officer, finance manager | | |
| 8 | Reports and CSV export | Department manager, finance manager | | |
| 9 | Settings change with second approver (triage scale, branding) | Clinical leads, administrators | | |
| 10 | Downtime drill: paper forms, keying, reconciliation, close | Coordinator, clerks, nurse, clinician, cashier | | |
| 11 | Restore drill on the pilot server; record duration | Operations owner | | |
| 12 | Access checks: each role tries a screen it must not use | All | | |
| 13 | Keyboard-only and screen-reader pass of the main workflows | Accessibility reviewer | | |

## Open questions

1. RPO and RTO (sets backup frequency, retention and `BACKUP_MAX_AGE_SECONDS`).
2. Who holds the backup private key and its second copy; where offline copies of backups are stored.
3. Monitoring server and paging: who receives which alert, and at what hours.
4. Session idle and absolute timeouts (still placeholders) and whether rate limiting is required at the proxy.

## Change log
| Date | Change | By |
|---|---|---|
| 2026-11-23 | Decisions recorded; backup/restore drill, endpoint sweep and fixes, accessibility sweep, management port and alert rules | Engineering (Claude Code), task 5.4 |
