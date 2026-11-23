# Development Guide

Local setup and day-to-day commands. Requirements and rules live in the numbered docs; start with [README.md](README.md).

## Prerequisites
| Tool | Version | Notes |
|---|---|---|
| JDK | 21 (Temurin) | Set `JAVA_HOME`. Maven is provided by the wrapper (`backend/mvnw`). |
| Node.js | 24 (see `frontend/.nvmrc`) | npm ships with it |
| Docker | Docker Desktop or Engine | Local PostgreSQL and backend integration tests (Testcontainers) |

Pinned versions (OD-04): Spring Boot 4.2.0, Java 21, PostgreSQL 18, React 19, Vite 8, TypeScript 7. Upgrades go through a reviewed PR; Dependabot proposes weekly updates.

## First run from a fresh checkout
```sh
cp .env.example .env                 # local-only values; never commit .env
docker compose up -d postgres keycloak  # PostgreSQL 18 on :5432, Keycloak on :8180 (both localhost-only)

cd backend && ./mvnw spring-boot:run # http://localhost:8080/actuator/health
cd frontend && npm ci && npm run dev # http://localhost:5173 (proxies /api and /actuator to :8080)
```
On Windows use `mvnw.cmd` from PowerShell/cmd, or `./mvnw` from Git Bash.

**Port already in use?** Run the backend on another port and point the frontend proxy at it:
`SERVER_PORT=8082 ./mvnw spring-boot:run`, then `GCARE_BACKEND_URL=http://localhost:8082 npm run dev`.

Keycloak must be up before the backend starts (the backend reads its OIDC configuration at start-up).

Alternatively, `./mvnw spring-boot:test-run` starts the backend against a throwaway Testcontainers database without Compose (Keycloak from Compose is still needed for sign-in).

## Signing in locally (ADR-012)
1. Start the frontend (`npm run dev`) and open `http://localhost:5173`. Select **Sign in**; Keycloak shows its login page.
2. Use a synthetic user from [infra/keycloak/README.md](../infra/keycloak/README.md), e.g. `demo.admin` / `Local-Demo-Only-1!`.
3. You return to the app signed in. The Vite dev server proxies `/api`, `/oauth2`, `/login/oauth2` and `/v3/api-docs` to the backend and keeps the Host header, so sign-in, cookies and CSRF behave as in production behind one host name.
4. **Sign out** in the account menu ends the GCare session and then the Keycloak session.

If you changed the realm file, recreate the local Keycloak so it re-imports it: `docker compose up -d --force-recreate keycloak` (local demo data only).

The OpenAPI document is at `/v3/api-docs` when signed in (local profile only).
Keycloak's admin console is `http://localhost:8180` (`admin` / `admin`, local only).

## Checks (same as CI)
| Area | Command | What it runs |
|---|---|---|
| Backend | `cd backend && ./mvnw verify` | Spotless format check, compile, tests against real PostgreSQL |
| Backend format fix | `./mvnw spotless:apply` | google-java-format |
| Frontend | `npm run typecheck && npm run lint && npm test && npm run build` | tsc, oxlint, Vitest + Testing Library, Vite build |
| Frontend deps | `npm audit --audit-level=high` | Known-vulnerability check |

**Windows:** run the frontend tests from a path with an upper-case drive letter (`C:\Users\...`, as PowerShell does by default). From `c:\...` (lower case, as some terminals start), Vitest loads itself twice and every suite fails with "Vitest failed to find the current suite".

Never report a check as passing unless it ran ([07-CLAUDE.md](07-CLAUDE.md)).

## Architecture map
- `backend/src/main/java/com/gcare/hms/<module>/`: one package per module (identity, organization, patient, encounter, orders, radiology, billing, reporting, audit, integration). Each `package-info.java` states the module's purpose. Modules talk through application services or events, never each other's repositories.
- `backend/.../shared/`: cross-cutting infrastructure only (security config, current user and permissions, organization context, errors, correlation IDs).
- `infra/keycloak/`: the local Keycloak realm (synthetic users).
- `backend/src/main/resources/db/migration/`: Flyway migrations, the only owner of the schema.
- `frontend/src/` (ADR-013): `api/` (fetch client with CSRF and the standard error type), `auth/` (session, signed-in user, facility context), `shell/` (app layout, navigation, session gate and permission guard), `components/` (loading/empty/error/forbidden states, status badge, confirmation dialog), `pages/`, `routes.tsx`. Hiding navigation is for usability only; the API enforces every permission.

## Migration workflow
1. Add `V<n>__<description>.sql` under `db/migration`. Never edit a merged migration.
2. Run `./mvnw verify`. Tests start a fresh PostgreSQL and apply every migration, and Hibernate validates the entities against the schema.
3. In the PR, describe the data impact and the rollback/forward-fix plan ([13-CHANGE-RELEASE.md](13-CHANGE-RELEASE.md)).
4. Every new organization-owned table needs `organization_id`, `ENABLE` + `FORCE ROW LEVEL SECURITY`, an isolation policy using `app_current_organization_id()`, explicit grants to `gcare_app` (no `DELETE` for clinical/financial/config records), and an isolation test.

Local synthetic seed data lives in `db/seed-local/` (repeatable migration, local profile only). It must stay idempotent and fictional.

## Organization isolation (ADR-010)
Isolation is enforced by PostgreSQL row-level security, so a missed `WHERE` clause cannot leak another hospital's rows.

- Every pooled connection runs as the NOLOGIN role `gcare_app` (`SET ROLE` on checkout, in `shared/tenancy/RowLevelSecurityDataSource`).
- The organization comes from `OrganizationContext` and is copied into the session setting `app.current_organization_id`. With no organization set, organization-owned tables look empty.
- Set the context **before** a transaction starts: `OrganizationContext.callAs(orgId, () -> service.doWork())`. Switching organization inside a context throws.
- `OrganizationContext.callAsSchemaOwner(...)` bypasses RLS. It is only for migrations and audited platform operations, never request handling.
- For web requests, `identity/CurrentUserFilter` sets it from the signed-in user. Tests and platform code set it explicitly.
- **Deployment note:** the database login used by the app must be able to `SET ROLE gcare_app`. Migration V1 grants this to the migrating user. Superusers bypass RLS, so the application must never run queries without `SET ROLE` (the DataSource wrapper guarantees this).
- **Deployment note:** `callAsSchemaOwner` (migrations, admin bootstrap) relies on the login role being a superuser or having `BYPASSRLS`; tables use `FORCE ROW LEVEL SECURITY`, which otherwise applies to the owner too. Local Compose and Testcontainers use a superuser. Record the production role setup in the deployment record.

## Sign-in and authorization (ADR-012)
- **Keycloak authenticates; GCare authorizes.** A Keycloak account grants nothing until it is linked (issuer + subject, table `user_identities`) to an `ACTIVE` GCare user in an `ACTIVE` organization.
- **Checked on every request.** `CurrentUserFilter` reloads the user's status and active role assignments from the database, so disabling an account or ending an assignment takes effect on the next request. Sessions also end after `SESSION_MAX_LIFETIME` (absolute) or `SESSION_IDLE_TIMEOUT` (idle); both values are placeholders until the security owner approves them.
- **In services:** annotate with `@PreAuthorize("hasAuthority('FACILITY_READ')")` (constants in `shared/security/Permissions`), then narrow data with `CurrentUser.require().scopeOf(Permissions.X)`. A facility outside scope answers 404. Never take organization or facility IDs from the client as proof of access.
- **New permission:** add it to a migration (`permissions` table) and to `Permissions.java`; `IdentitySchemaTests` fails if they differ. Roles are data (per organization); system roles are defined in `identity/SystemRole.java`.
- **Audited automatically:** `SIGNED_IN`, `SIGN_IN_REFUSED`, `SIGN_IN_FAILED`, `SIGNED_OUT`, `SESSION_ENDED` and `ACCESS_DENIED`.
- **Tests:** MockMvc tests sign in with `IdentityFixtures.user(...).signedIn()`; `KeycloakSignInTests` runs the real flow against Keycloak in Testcontainers.

## Patients (task 2.1)
- Rules are **provisional** ([17-PATIENT-IDENTITY-POLICY.md](17-PATIENT-IDENTITY-POLICY.md)); weights and thresholds are configuration (`gcare.patient.matching.*`). Change the policy document, `DuplicateScoringScenarioTests` and the configuration together.
- `POST /api/v1/patients` answers **409 `DUPLICATE_CANDIDATES`** with scored candidates when likely/possible duplicates exist. The client resends with `duplicate_review.reviewed_candidate_ids` naming every candidate, plus a `reason` for likely duplicates; those pairs go to `patient_duplicate_flags` for review. Nothing is ever linked or merged automatically.
- Search needs a number, identifier, phone, or a name with birth date/year or sex (`SEARCH_TOO_BROAD` otherwise). Patients are organization-wide: `PATIENT_READ` anywhere allows searching; registering needs `PATIENT_REGISTER` at the facility.
- Audit metadata for patients holds criteria *types* and counts only, never names, numbers or free-text reasons.
- **Corrections** (`PATCH /api/v1/patients/{id}`): send `version`, `reason` and only the changed fields; previous values go to `patient_changes` (append-only) and `GET .../history`. A stale `version` answers 409.
- **Duplicate review** (`/api/v1/patient-duplicate-flags`, permission `PATIENT_DUPLICATE_REVIEW`): staff report pairs with `POST /patients/{id}/duplicate-reports`; a reviewer who did not raise the item marks it `NOT_DUPLICATE` with a note, and the pair is not suggested again.
- **Linking** (`/api/v1/patient-links`, docs/17 §5): never a merge. `PATIENT_LINK_REQUEST` requests (from the queue with `flag_id` + `flag_version`); a different user with `PATIENT_LINK_APPROVE` approves or rejects, with a note, plus `override_reason` when verified IDs differ (`400 IDENTIFIER_CONFLICT`). The retired record becomes `LINKED` with `linked_to`; `POST .../{id}/unlink` reverses it. History: `GET /patients/{id}/links`.
- Screens: `/patients` (search), `/patients/new` (registration with candidate review), `/patients/{id}` (record, history, correction, report), `/patients/duplicates` (review queue, link requests, approvals). Search criteria and form input stay in memory, never in the URL or browser storage.
- **Encounters** (`/api/v1/encounters`, docs/18, PROVISIONAL): open (`ENCOUNTER_OPEN`) → WAITING; `call`, `transfer` (reason), `close` (`ENCOUNTER_MANAGE`); `cancel` (reason, WAITING only); `reopen` (reason, `ENCOUNTER_REOPEN`). Send `version`. Departments for clerks/nurses: `GET /api/v1/encounter-departments?facility_id=`.
- **Triage** (`POST /api/v1/encounters/{id}/observations`, `OBSERVATION_RECORD`): fixed units; `POST /api/v1/observations/{id}/entered-in-error` with a reason. The scale and alarm limits come from the approved `TRIAGE` setting (docs/21); `gcare.triage.urgency-levels` and `gcare.triage.alarm-limits` are an empty fallback on purpose; never commit invented values.
- **Notes** (`/api/v1/encounters/{id}/notes`, `/api/v1/notes/{id}` PATCH, `/sign`, `/discard`; docs/18 §7): drafts private to the author; signed notes never change (DB trigger); amend with `amends_note_id` + `amendment_reason`. **Diagnoses**: `/api/v1/encounters/{id}/diagnoses` (free text + certainty), `/api/v1/diagnoses/{id}/entered-in-error`.
- **Orders** (docs/18 §8): catalog `GET/POST /api/v1/order-catalog`, `PUT /api/v1/order-catalog/{id}` (`ORDER_CATALOG_MANAGE`; starts empty); `POST/GET /api/v1/encounters/{id}/orders`; worklist `GET /api/v1/orders`; `POST /api/v1/orders/{id}/start|complete|cancel`. Screens `/orders` (worklist), `/admin/order-catalog`. Demo: `demo.lab`; fictional items `DEMO-LAB-A`, `DEMO-IMG-B`, `DEMO-REF-C`.
- **Chart and workspace** (docs/18 §9): `GET /api/v1/patients/{id}/chart` (sections by permission; null = not permitted), `GET /api/v1/patients/{id}/orders`, `GET /api/v1/workspace`. Screens: chart on `/patients/{id}`, `/workspace`.
- **Tariffs** (docs/19): `/api/v1/billing/services`, `.../services/{id}/prices` (propose), `/api/v1/billing/prices/{id}/approve|reject`, `/api/v1/billing/tax-rates`, `/api/v1/billing/approvals`, `/api/v1/billing/effective-price`. Amounts are decimals with an ISO 4217 currency; never commit real prices. Screens `/billing/tariffs`, `/billing/approvals`. Demo: `demo.finance`, `demo.financemgr`.
- **Invoices** (docs/19 §3): `GET /api/v1/invoices`, `/api/v1/invoices/billable-visits`, `POST /api/v1/encounters/{id}/invoices` (currency), `GET /api/v1/invoices/{id}`, `POST .../order-charges`, `.../lines`, `.../lines/{lineId}/remove`, `.../issue`, `.../void`. Screens `/billing/invoices`, `/billing/invoices/{id}`. Demo: `demo.cashier` (start, issue), `demo.financemgr` (void issued).
- **Payments** (docs/19 §4): `/api/v1/payment-methods` (`PAYMENT_METHOD_MANAGE`), `POST /api/v1/invoices/{id}/payments` (with `idempotency_key`), `GET /api/v1/payments/{id}/receipt`, `POST /api/v1/shifts`, `/api/v1/shifts/mine`, `/api/v1/shifts/{id}/close`, `/api/v1/shifts/to-review`, `/api/v1/shifts/{id}/review`. Screens `/billing/shift`, `/billing/shift-reviews`, `/billing/receipts/{id}`, `/admin/payment-methods`. Demo methods `DEMO-CASH`, `DEMO-NONCASH`.
- **Refunds and exemptions** (docs/19 §5): `POST /api/v1/payments/{id}/refunds`, `/api/v1/refunds/{id}/approve|reject|pay`, `GET /api/v1/refunds/{id}/receipt`, `POST /api/v1/invoices/{id}/exemptions`, `/api/v1/exemptions/{id}/approve|reject`, `GET /api/v1/invoices/{id}/adjustments`, `GET /api/v1/adjustments/pending`, `/api/v1/exemption-reasons` (`EXEMPTION_REASON_MANAGE`). Screens `/billing/adjustment-approvals`, `/billing/refund-receipts/{id}`, `/admin/exemption-reasons`. Demo reason `DEMO-REASON`.
- **Reports** (docs/20): `GET /api/v1/reports`, `/api/v1/reports/dashboard?facility_id=`, `/api/v1/reports/{visits|orders|registrations|revenue|payment-methods|cash-shifts}?facility_id=&from=&to=`, `.../export` (CSV, `REPORT_EXPORT`). Screen `/reports`. Demo: `demo.manager` (operations), `demo.financemgr` (revenue).
- **Downtime** (docs/22): `GET|POST /api/v1/downtime/episodes`, `/episodes/{id}`, `/episodes/{id}/end|close`, `POST|GET /api/v1/downtime/forms`, `/forms/{id}`, `/downtime/form-options`, `GET|POST /episodes/{id}/entries`, `/downtime/entries/{id}`, `/entries/{id}/candidates`, `POST /entries/{id}/propose|approve|send-back|reconcile|void`, `GET /api/v1/encounters/{id}/downtime-sources`. Screens `/downtime`, `/downtime/episodes/{id}`. Demo: `demo.downtime` declares and keys; `demo.clerk` + `demo.supervisor` reconcile registrations, `demo.nurse` triage, `demo.clinician` orders, `demo.cashier` payments.
- **Settings** (docs/21): `GET /api/v1/settings`, `/api/v1/settings/pending`, `GET|POST /api/v1/settings/{key}/versions` (`facility_id` for `FACILITY_BRANDING`), `POST /api/v1/settings/versions/{id}/approve|reject`, `GET /api/v1/facilities/{id}/branding`, `POST /api/v1/facilities/{id}/logos`, `GET /api/v1/facility-logos/{id}`. Screen `/admin/settings`. Demo: `demo.clinlead` + `demo.clinlead2` (triage), `demo.admin` + `demo.admin2` (branding, billing options).
- Screens: `/queue`, `/encounters/{id}`, and "Start visit" on the patient record. Demo: `demo.nurse` (triage), `demo.clinician` (call, transfer, close, reopen).
- Local demo: `demo.clerk` (registration clerk at DEMO-GH), `demo.supervisor` (adds duplicate review and link requests), `demo.records` (health records manager: approves links) and three fictional patients `P1000001-6`, `P1000002-4`, `P1000003-2`.

## First administrator (admin bootstrap)
For a new environment, after Keycloak is set up and the administrator's Keycloak account exists:
1. Copy the account's ID from the Keycloak admin console (Users → the user → ID). That is the `subject`.
2. Start the backend once with the bootstrap settings (environment variables, where Spring drops dashes: `country-code` → `COUNTRYCODE`; or `--gcare.bootstrap.*` arguments):
   ```sh
   export GCARE_BOOTSTRAP_ENABLED=true
   export GCARE_BOOTSTRAP_ORGANIZATION_CODE=...  GCARE_BOOTSTRAP_ORGANIZATION_NAME=...
   export GCARE_BOOTSTRAP_ORGANIZATION_COUNTRYCODE=LR
   export GCARE_BOOTSTRAP_FACILITY_CODE=...  GCARE_BOOTSTRAP_FACILITY_NAME=...
   export GCARE_BOOTSTRAP_FACILITY_TIMEZONE=Africa/Monrovia
   export GCARE_BOOTSTRAP_ADMIN_USERNAME=...  GCARE_BOOTSTRAP_ADMIN_DISPLAYNAME=...
   export GCARE_BOOTSTRAP_ADMIN_ISSUER=https://<keycloak-host>/realms/<realm>
   export GCARE_BOOTSTRAP_ADMIN_SUBJECT=<keycloak user id>
   java -jar gcare-hms-backend.jar   # or ./mvnw spring-boot:run in development
   ```
3. It creates the organization, facility, system roles and the administrator (organization-wide `ORGANIZATION_ADMIN`), audits each step, and changes nothing if run again. Remove the settings afterwards.

The issuer must match the token's `iss` exactly (scheme, host, port, no trailing slash). The bootstrap never creates Keycloak accounts or passwords.

## Audit and errors
- Record security-relevant access and changes with `AuditService.record(...)`. It commits in its own transaction (it survives a rollback), redacts sensitive metadata keys, and `audit_events` rejects UPDATE, DELETE and TRUNCATE for everyone.
- Throw `ApiException.NotFound` / `Conflict` / `InvalidTransition` for expected failures. `GlobalExceptionHandler` and the security handlers return `{code, message, field_errors, correlation_id}`; unexpected errors are logged with the correlation ID and answered generically.
- Every response carries `X-Correlation-Id`, which also appears on every log line.

## Data and secrets policy
- **Synthetic data only** in development, demo, test and CI. Use obviously fictional values (names like "Test Patient", phone `0000000000`, IDs from test generators). Never copy, paste, screenshot or "anonymize by hand" real patient records into the repo, issues, PRs or chat.
- No production extracts in lower environments ([03-ARCHITECTURE.md](03-ARCHITECTURE.md)).
- Secrets come from environment variables or a secret store, never from committed files. `.env` is git-ignored; `.env.example` holds placeholders and throwaway local values only.
- If real data or a secret is committed by mistake: stop, tell the security owner, and rotate or purge. Deleting it in a later commit is not enough.

## Backups
Local Compose data (`gcare-pgdata` volume) is disposable. Backup and restore for shared environments are owned by the operator named in [project-record/deployment-record.md](project-record/deployment-record.md) and tested before pilot ([06-PLAN.md](06-PLAN.md) Phase 5).

Scripts and procedure: [23-OPERATIONS-READINESS.md](23-OPERATIONS-READINESS.md) §2 (`infra/backup/backup.sh`, `restore.sh`). Local restore drill (throwaway key, scratch database, verified): `bash infra/backup/drill-local.sh` (on Git Bash prefix `MSYS_NO_PATHCONV=1`).

## Imaging archive (evaluation)
Local candidate archive (synthetic only): `docker compose --profile imaging up -d orthanc` (127.0.0.1:8142 HTTP, :4342 DICOM); load a study with `bash infra/imaging/load-synthetic-studies.sh P-000001 ACC-000001 CR "Synthetic chest"`. Point the backend at it with `GCARE_IMAGING_ARCHIVE_BASE_URL`, `..._USERNAME`, `..._PASSWORD`, `..._VIEWER_URL_TEMPLATE` (values in [24-IMAGING-ARCHIVE-EVALUATION.md](24-IMAGING-ARCHIVE-EVALUATION.md) §3). API: `GET /api/v1/imaging/archive/status`, `GET /api/v1/imaging/archive/studies?accession_number=`. Screen `/admin/imaging-archive`. Demo: `demo.pacsadmin`.

## Imaging worklist
API: `GET /api/v1/imaging/worklist`, `POST /api/v1/imaging/orders/{id}/arrived|examined|check-archive`, `GET /api/v1/imaging/studies/unlinked`, `/studies/{id}/history`, `POST /studies/{id}/propose|approve|send-back|unlink`. Screens `/imaging`, `/imaging/studies`. Scheduled archive check: set `GCARE_IMAGING_ARCHIVE_ORGANIZATION_ID` (+ `GCARE_IMAGING_SYNC_INTERVAL`, default PT5M). Demo: `demo.radiographer`, `demo.radiographer2` ([25-IMAGING-WORKLIST.md](25-IMAGING-WORKLIST.md)).

## Radiology reports
API: `GET /api/v1/radiology/reporting`, `GET|POST /api/v1/imaging/orders/{id}/reports`, `PUT /api/v1/radiology-reports/{id}`, `POST /radiology-reports/{id}/sign|discard`, `GET /api/v1/encounters/{id}/imaging-results`, `GET /api/v1/patients/{id}/imaging-results`, `GET /api/v1/critical-results`, `POST /critical-results/{reportId}/acknowledge`. Screens `/radiology/reporting`, `/critical-results`. Demo: `demo.radiologist` reports; `demo.clinician` acknowledges ([26-RADIOLOGY-REPORTS.md](26-RADIOLOGY-REPORTS.md)).

## Health and metrics
Application port: `/actuator/health`, `/livez`, `/readyz` (status only). Management port `MANAGEMENT_PORT` (default 9091, bound to `MANAGEMENT_ADDRESS` 127.0.0.1): `/actuator/health` with details, `/actuator/prometheus`. Example alert rules: `infra/monitoring/alert-rules.yml` (provisional thresholds).
