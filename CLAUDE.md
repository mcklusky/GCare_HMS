# GCare HMS — Claude Code instructions
- **Priority:** hospital management system. Patient safety, data integrity and security come before speed. Not a CRUD app.
- **Full rules:** docs/07-CLAUDE.md (summary), docs/detail/07-CLAUDE-DETAIL.md. Read them only when a task touches their area.
**Stack** (versions pinned, ADR-011)
- Backend: Spring Boot 4.2 modular monolith on Java 21, Spring Security 7, Jackson 3 (`tools.jackson`)
- Database: PostgreSQL 18 with Flyway and row-level security
- Frontend: React + TypeScript (Vite) with Mantine 9, React Router, TanStack Query and Tabler icons (ADR-013)
- Never add Django, a second backend, microservices or another database without an ADR. `GCare_HMS_PRD_v1_4.docx` is superseded.
**Paths**
- Backend `backend/src/main/java/com/gcare/hms/`: `audit`, `identity`, `organization`, `patient`, `encounter`, `orders`, `billing`, `reporting`, `configuration`, `downtime`, `radiology`, plus `shared/{security,tenancy,web}`
  - Placeholder module (package-info only): `integration`
- Migrations: `backend/src/main/resources/db/migration/V<n>__*.sql` (V1–V18 so far; never edit an applied one)
- Frontend: `frontend/src/{api,auth,shell,components,pages/<feature>,test}`
- Local infra: `docker-compose.yml` (Postgres, Keycloak on 127.0.0.1:8180); realm in `infra/keycloak/gcare-realm.local.json`
- Docs in `docs/`: README (index), 01-PRD (FR-001–046), 06-PLAN (tasks), 16-TRACEABILITY, 17-PATIENT-IDENTITY-POLICY, REVIEW (ADRs), DEVELOPMENT (commands)
**Auth** (ADR-012)
- Keycloak OIDC with authorization code + PKCE. The backend is the confidential client; the browser holds only the HttpOnly `GCARE_SESSION` cookie.
- CSRF: cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN` on every non-GET request
- GCare authorizes, not Keycloak:
  - each identity is linked via `user_identities`
  - `identity/CurrentUserFilter` reloads the user and permissions on every request and sets `OrganizationContext`
- Services: `@PreAuthorize("hasAuthority('<PERMISSION>')")` + `CurrentUser.require().scopeOf(...)` for facility scope.
- Deny-by-default `shared/security/SecurityConfig`. Never loosen it. Never trust organization or facility IDs sent by the client.
- Config `AUTH_ISSUER_URI` / `AUTH_CLIENT_SECRET` have no defaults (fail closed). Local demo users `demo.*`, password `Local-Demo-Only-1!`.
**Backend conventions**
- Versioned `/api/v1` REST with DTOs, Bean Validation, OpenAPI, and business rules in the service layer
- Errors go through `shared/web/ApiException` subtypes, giving one consistent error format
- Tenant queries run only inside `shared/tenancy/OrganizationContext.callAs(...)`. Every new tenant table needs RLS, grants and an isolation test.
- Audit sensitive reads and every mutation. Audit metadata holds field names and criteria types, never values or clinical content.
- Optional JSON booleans are `Boolean`, not `boolean` (Jackson 3 fails on missing primitives)
- Corrections and reversals need a version, a reason and history. Never silently merge or overwrite records.
**Frontend conventions**
- Use the shared states in `components/states.tsx`, `ConfirmDialog` (reason required) and `StatusBadge`
- Navigation is gated by permission in `shell/navigation.ts`; this is for usability only, the API enforces
- No patient data in URLs, localStorage or sessionStorage
- Accessible, responsive UI with loading, empty and error states; tests use axe-core
**Testing and commands**
- Backend: `./mvnw verify` (Testcontainers, Spotless with UNIX line endings). If `java` is missing, set `JAVA_HOME` to `~/.jdks/jdk-21*`.
- Frontend: `npm run lint`, `typecheck`, `test` and `build`. On Windows run Vitest from an upper-case `C:\` path.
- Test the feature plus authorization, tenant isolation, audit and failure paths
- Never claim a test passed unless it ran. Update 16-TRACEABILITY only with tests that ran.
**Domain rules**
- Patient identity rules (OD-05/06/07, docs/17) are PROVISIONAL. Never present them as approved.
- Do not invent clinical rules, tariffs, legal values or national interface contracts
- Money: decimal types, an explicit currency, a tariff snapshot and idempotent payments
- Imaging goes to a PACS/DICOM archive, never PostgreSQL
**Git and data**
- One branch and PR per plan task (`phaseN/N.M-name`). Reference FR and task IDs.
- Synthetic data only. Never commit `.env`, secrets, real patient data or production extracts.
- Project-record templates stay empty; never fill them with invented values
- No force-push or deploy without explicit approval
**State (2026-11-23)**
- Done 1.1–1.4, 2.1–2.3, 3.1–3.4, 4.1–4.4, 5.1–5.4, 6.1 kit, 6.3, 6.4 (PRs stacked 1.2→…→6.3→6.4). Gates for Phases 1–2 not yet signed off. Encounter workflow PROVISIONAL (docs/18); triage scale/alarm limits empty until clinical leads approve them (docs/21). Notes: fixed sections, signed = immutable (OD-10); diagnoses free text, coding open (OD-09). Order catalog admin-managed, empty by design. Phase 3 code done; its exit needs end-to-end UAT with role checks. Billing (docs/19): prices entered by finance, any ISO currency never converted, tax rate configurable, two-person approval; never invent prices. Invoices: orders + cashier lines, one currency each, per-line half-up tax, void + reissue. Payments: idempotent, part payments up to balance, shift cash count + second-person sign-off, receipt format provisional. Refunds/exemptions two-person; refunds paid out in shifts. Phase 4 code done; exit needs finance-owner sign-off. Reports (docs/20): aggregates only, facility-local days, audited CSV. Settings (docs/21): versioned, two-person approval; triage scale/limits now an approved setting (still empty). Downtime (docs/22): numbered paper forms keyed into a queue, reconciled through normal services; second person for registration/order/payment. Ops (docs/23): encrypted backup + verified restore drill (infra/backup), endpoint security sweep test, management port for health/metrics, provisional alert rules; RPO/RTO open; UAT not run. Imaging (docs/24): archive NOT selected (OD-14); vendor-neutral DICOMweb interface, local Orthanc harness on :8142 (synthetic only), viewer by link. Worklist (docs/25): HMS accession ACC+9 digits, arrived/examined move the order, auto-link only on exact accession + patient number, else two-person review queue; scheduled check needs GCARE_IMAGING_ARCHIVE_ORGANIZATION_ID. Provisional defaults (docs/25): no facility prefix; any second IMAGING_LINK holder approves; check every 5 min / 2 days; clinicians see images marked "Not yet reported" (build in 6.4). Reports (docs/26): radiologist signs, private drafts, amendments with reason, critical = documented contact + clinician acknowledgement, no time limits (clinical governance). Remaining: 6.2 needs real modalities; Phase 5/6 exits need sign-off.
