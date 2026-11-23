# GCare HMS — Claude Code instructions
- **Priority:** hospital management system. Patient safety, data integrity and security come before speed. Not a CRUD app.
- **Full rules:** docs/07-CLAUDE.md (summary), docs/detail/07-CLAUDE-DETAIL.md. Read them only when a task touches their area.
**Stack** (versions pinned, ADR-011)
- Backend: Spring Boot 4.2 modular monolith on Java 21, Spring Security 7, Jackson 3 (`tools.jackson`)
- Database: PostgreSQL 18 with Flyway and row-level security
- Frontend: React + TypeScript (Vite) with Mantine 9, React Router, TanStack Query and Tabler icons (ADR-013)
- Never add Django, a second backend, microservices or another database without an ADR. `GCare_HMS_PRD_v1_4.docx` is superseded.
**Paths**
- Backend `backend/src/main/java/com/gcare/hms/`: `audit`, `identity`, `organization`, `patient`, plus `shared/{security,tenancy,web}`
  - Placeholder modules (package-info only): `billing`, `encounter`, `integration`, `orders`, `radiology`, `reporting`
- Migrations: `backend/src/main/resources/db/migration/V<n>__*.sql` (V1–V6 so far; never edit an applied one)
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
- Done 1.1–1.4, 2.1–2.3, 3.1 (PRs stacked 1.2→…→2.3→3.1). Gates for Phases 1–2 not yet signed off. Encounter workflow PROVISIONAL (docs/18); triage scale/alarm limits empty until a clinical lead sets them. Next: 3.2 notes.
